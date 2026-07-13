package com.squarely.auth.service;

import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Base64;
import java.util.Locale;

/**
 * Per-account throttle on failed logins — the defence the gateway's per-IP limiter cannot
 * provide. Credential stuffing tries one password against a million accounts from a million
 * IPs; every IP sends a single request and no per-IP bucket ever fills. So the counter keys
 * on the thing under attack: the account.
 *
 * <p>Counters live in Redis because they are ephemeral, high-write, and TTL-shaped — INCR +
 * EXPIRE is exactly this. They are deliberately NOT in Postgres: a failed login would then
 * write to the users table, churning the most important table in the system for data that is
 * worthless in an hour.
 *
 * <p><b>Fail closed.</b> If Redis is unreachable we reject the login (503) rather than let it
 * through unthrottled. Redis being down is precisely when an attacker would want the brakes
 * off, and every attempt we admit costs a BCrypt hash — the flood pins auth-service's CPU
 * long before Postgres notices. The cost of this choice is real: Redis is now a hard
 * dependency of logging in, and the single node in docker-compose is a single point of
 * failure for authentication. Give it a replica before this matters.
 * ponytail: refresh deliberately does NOT go through here. Failing login closed is a bounded
 * annoyance; failing refresh closed would sign out every active user the moment Redis
 * blipped, and refresh runs no BCrypt and is not the stuffing surface.
 */
@Component
public class LoginThrottle {

    /** Wrong passwords allowed before the delays start. */
    private static final int FREE_ATTEMPTS = 5;
    /** How long failures are remembered. Renewed on each failure, so it is a sliding window. */
    private static final Duration COUNTER_TTL = Duration.ofMinutes(15);
    private static final Duration FIRST_BACKOFF = Duration.ofSeconds(30);
    private static final Duration MAX_BACKOFF = Duration.ofMinutes(15);

    private final StringRedisTemplate redis;

    public LoginThrottle(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /** Rejects with 429 while the account is backing off, or 503 if Redis can't be reached. */
    public void check(String email) {
        Long ttl;
        try {
            ttl = redis.getExpire(blockKey(email));
        } catch (DataAccessException e) {
            // Fail closed. See the class comment: an unthrottled login path is worse than a
            // login outage, because it is the one that gets accounts taken over.
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Login temporarily unavailable", e);
        }
        // getExpire returns -2 when the key is gone (not blocked) and -1 when it has no TTL.
        if (ttl != null && ttl > 0) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "Too many failed attempts. Try again in " + ttl + "s");
        }
    }

    /**
     * Escalates the backoff after a wrong password. Never throws: the caller is already on
     * its way to a 401, and a Redis blip must not turn "wrong password" into "server error".
     */
    public void recordFailure(String email) {
        try {
            Long failures = redis.opsForValue().increment(counterKey(email));
            redis.expire(counterKey(email), COUNTER_TTL);
            if (failures != null && failures > FREE_ATTEMPTS) {
                redis.opsForValue().set(blockKey(email), "1", backoff(failures));
            }
        } catch (DataAccessException ignored) {
            // Best-effort. check() is the gate, and it fails closed on the next attempt.
        }
    }

    /** A correct password clears the slate — the attacker's failures don't stack forever. */
    public void recordSuccess(String email) {
        try {
            redis.delete(java.util.List.of(counterKey(email), blockKey(email)));
        } catch (DataAccessException ignored) {
            // A stale counter costs the user a delay at worst; not worth failing the login.
        }
    }

    /**
     * 30s after the 6th failure, doubling to a 15-minute ceiling. Escalating delay, not a
     * hard lockout, on purpose: a hard lockout hands anyone a griefing DoS — type a wrong
     * password at someone six times and they can't log in. A delay that decays is annoying
     * for the victim and useless for the attacker.
     */
    private static Duration backoff(long failures) {
        long steps = failures - FREE_ATTEMPTS - 1;             // 0 on the first blocked attempt
        if (steps >= 62) return MAX_BACKOFF;                   // guard the shift below
        Duration backoff = FIRST_BACKOFF.multipliedBy(1L << steps);
        return backoff.compareTo(MAX_BACKOFF) > 0 ? MAX_BACKOFF : backoff;
    }

    // Emails are hashed: Redis holds no PII, and someone who dumps the keyspace learns no
    // addresses. Lowercased first so Alice@x.com and alice@x.com share one counter.
    private static String counterKey(String email) {
        return "login:fail:" + hash(email);
    }

    private static String blockKey(String email) {
        return "login:block:" + hash(email);
    }

    private static String hash(String email) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(email.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (Exception e) {
            throw new IllegalStateException(e); // SHA-256 always present
        }
    }
}

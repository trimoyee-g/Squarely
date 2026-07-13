package com.squarely.gateway;

import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import javax.crypto.SecretKey;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

/**
 * Rate-limit bucket key: the authenticated user, falling back to the client IP.
 *
 * <p>IP alone is a bad key in both directions. It is too coarse — a NAT'd office shares one
 * bucket, so a stranger's traffic throttles you — and too easy to escape, since proxy pools
 * are cheap and a single IPv6 /64 hands one machine 2^64 source addresses. The access token
 * fixes both: it is unforgeable without the signing secret, so a caller cannot escape their
 * bucket without registering new accounts, and fifty people behind one NAT get fifty buckets.
 *
 * <p>The signature is verified here, not merely decoded — an unverified `sub` claim would be
 * attacker-chosen, which is a rate-limit bypass with extra steps. Services still validate the
 * token themselves; this is a bucket key, not an authorisation decision.
 *
 * <p>The IP fallback only carries `/auth/**` (the sole unauthenticated routes) and any
 * request arriving with a missing or junk token. IPv6 is bucketed by /64 rather than by
 * address, so one allocation is one bucket instead of quintillions of free ones.
 */
@Component
public class UserKeyResolver implements KeyResolver {

    private final SecretKey key;

    public UserKeyResolver(@Value("${security.jwt.secret}") String secret) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public Mono<String> resolve(ServerWebExchange exchange) {
        String subject = subjectOf(exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION));
        return Mono.just(subject != null ? "user:" + subject : "ip:" + clientIp(exchange));
    }

    private String subjectOf(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            return null;
        }
        try {
            return Jwts.parser().verifyWith(key).build()
                    .parseSignedClaims(authorization.substring(7))
                    .getPayload().getSubject();
        } catch (JwtException | IllegalArgumentException e) {
            return null;   // Expired, forged, or malformed — treat it as anonymous, not an error.
        }
    }

    /**
     * Honours X-Forwarded-For via forward-headers-strategy, so a proxy in front of the
     * gateway doesn't collapse every client into one bucket.
     * ponytail: that header is client-supplied and spoofable unless a proxy you control
     * overwrites it. Acceptable because the IP path now only covers /auth, which has its own
     * per-account throttle in auth-service — the defence that actually stops credential
     * stuffing. If the gateway is ever exposed with no proxy in front, pin the trusted proxy
     * list rather than trusting the header.
     */
    private static String clientIp(ServerWebExchange exchange) {
        InetSocketAddress remote = exchange.getRequest().getRemoteAddress();
        if (remote == null || remote.getAddress() == null) {
            return "unknown";
        }
        byte[] address = remote.getAddress().getAddress();
        if (address.length != 16) {
            return remote.getAddress().getHostAddress();   // IPv4: the address is the bucket
        }
        StringBuilder prefix = new StringBuilder(16);      // IPv6: bucket the /64
        for (int i = 0; i < 8; i++) {
            prefix.append(String.format("%02x", address[i]));
        }
        return prefix.toString();
    }
}

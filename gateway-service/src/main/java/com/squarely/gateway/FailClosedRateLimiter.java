package com.squarely.gateway;

import org.springframework.cloud.gateway.filter.ratelimit.RateLimiter;
import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * Inverts {@link RedisRateLimiter}'s fail-open behaviour.
 *
 * <p>Out of the box, when Redis is unreachable the limiter logs the error and returns
 * "allowed" — its comment says it doesn't want a hard dependency on Redis to allow traffic.
 * That is the wrong default here: Redis down is precisely when an attacker wants the brakes
 * off, and an unthrottled flood reaches auth-service, where every login attempt costs a
 * BCrypt hash. We would rather serve 429s than melt.
 *
 * <p>The wrapper is needed because RedisRateLimiter swallows the error internally, so a
 * delegate never sees the exception. What it does leave behind is a sentinel: the error path
 * returns allowed=true with tokensLeft = -1, which no successful call can produce (a real
 * response has tokensLeft >= 0). That -1 surfaces on the remaining-tokens header, so it is
 * what we match on.
 *
 * <p>This means {@code redis-rate-limiter.include-headers} must stay at its default of true —
 * with headers off, the sentinel is invisible and the gateway silently reverts to fail-open.
 * ponytail: matching a sentinel is uglier than catching an exception, but the alternative is
 * reimplementing the Lua token-bucket script to own the error path. Do that only if this
 * sentinel ever breaks — {@link FailClosedRateLimiterTest} fails loudly if it does.
 */
// @Primary because RedisRateLimiter is still a bean (we delegate to it), and the filter
// factory injects a single RateLimiter — without this it can't choose between the two.
@Primary
@Component("failClosedRateLimiter")
public class FailClosedRateLimiter implements RateLimiter<RedisRateLimiter.Config> {

    private static final String REDIS_UNREACHABLE = "-1";

    private final RedisRateLimiter delegate;

    public FailClosedRateLimiter(RedisRateLimiter delegate) {
        this.delegate = delegate;
    }

    @Override
    public Mono<Response> isAllowed(String routeId, String id) {
        return delegate.isAllowed(routeId, id).map(FailClosedRateLimiter::denyIfRedisUnreachable);
    }

    private static Response denyIfRedisUnreachable(Response response) {
        String remaining = response.getHeaders().get(RedisRateLimiter.REMAINING_HEADER);
        if (response.isAllowed() && REDIS_UNREACHABLE.equals(remaining)) {
            return new Response(false, response.getHeaders());
        }
        return response;
    }

    // The route configs (replenishRate, burstCapacity) are bound onto the delegate by the
    // `redis-rate-limiter.*` filter args, so hand every config question straight to it.
    @Override
    public Map<String, RedisRateLimiter.Config> getConfig() {
        return delegate.getConfig();
    }

    @Override
    public Class<RedisRateLimiter.Config> getConfigClass() {
        return delegate.getConfigClass();
    }

    @Override
    public RedisRateLimiter.Config newConfig() {
        return delegate.newConfig();
    }
}

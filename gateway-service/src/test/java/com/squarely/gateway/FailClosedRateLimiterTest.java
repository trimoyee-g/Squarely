package com.squarely.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.ratelimit.RateLimiter.Response;
import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter;
import reactor.core.publisher.Mono;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Guards the sentinel this class is built on. RedisRateLimiter swallows Redis errors and
 * returns allowed=true with tokensLeft=-1; if a Spring Cloud upgrade ever changes that, the
 * gateway would silently revert to fail-open and this test is what says so.
 */
class FailClosedRateLimiterTest {

    private final RedisRateLimiter delegate = mock(RedisRateLimiter.class);
    private final FailClosedRateLimiter limiter = new FailClosedRateLimiter(delegate);

    @Test
    void deniesWhenRedisIsUnreachable() {
        // What RedisRateLimiter actually returns on a Redis failure: allowed, remaining -1.
        stub(new Response(true, Map.of(RedisRateLimiter.REMAINING_HEADER, "-1")));

        assertThat(limiter.isAllowed("auth", "user:7").block().isAllowed()).isFalse();
    }

    @Test
    void allowsWhenUnderTheLimit() {
        stub(new Response(true, Map.of(RedisRateLimiter.REMAINING_HEADER, "19")));

        assertThat(limiter.isAllowed("auth", "user:7").block().isAllowed()).isTrue();
    }

    @Test
    void deniesWhenOverTheLimit() {
        stub(new Response(false, Map.of(RedisRateLimiter.REMAINING_HEADER, "0")));

        assertThat(limiter.isAllowed("auth", "user:7").block().isAllowed()).isFalse();
    }

    private void stub(Response response) {
        when(delegate.isAllowed(anyString(), anyString())).thenReturn(Mono.just(response));
    }
}

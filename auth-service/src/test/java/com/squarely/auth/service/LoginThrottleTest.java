package com.squarely.auth.service;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LoginThrottleTest {

    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final ValueOperations<String, String> values = mock(ValueOperations.class);
    private final LoginThrottle throttle = new LoginThrottle(redis);

    /** The decision this whole class exists for: no Redis means no login, not a free pass. */
    @Test
    void failsClosedWhenRedisIsUnreachable() {
        when(redis.getExpire(anyString())).thenThrow(new RedisConnectionFailureException("down"));

        assertThatThrownBy(() -> throttle.check("a@b.com"))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test
    void rejectsWhileBackingOff() {
        when(redis.getExpire(anyString())).thenReturn(42L);

        assertThatThrownBy(() -> throttle.check("a@b.com"))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    void allowsWhenNoBlockKeyExists() {
        when(redis.getExpire(anyString())).thenReturn(-2L);   // Redis: key does not exist

        assertThatCode(() -> throttle.check("a@b.com")).doesNotThrowAnyException();
    }

    @Test
    void firstFailuresAreFreeThenTheBackoffEscalates() {
        when(redis.opsForValue()).thenReturn(values);

        // 5 free attempts: counted, but no block key written.
        when(values.increment(anyString())).thenReturn(5L);
        throttle.recordFailure("a@b.com");
        verify(values, never()).set(anyString(), anyString(), any(Duration.class));

        // 6th failure starts the delay; each further failure doubles it.
        when(values.increment(anyString())).thenReturn(6L);
        throttle.recordFailure("a@b.com");
        verify(values).set(anyString(), eq("1"), eq(Duration.ofSeconds(30)));

        when(values.increment(anyString())).thenReturn(8L);
        throttle.recordFailure("a@b.com");
        verify(values).set(anyString(), eq("1"), eq(Duration.ofMinutes(2)));

        // ...up to a ceiling, so the delay never becomes a permanent lockout.
        when(values.increment(anyString())).thenReturn(40L);
        throttle.recordFailure("a@b.com");
        verify(values).set(anyString(), eq("1"), eq(Duration.ofMinutes(15)));
    }

    /** A key with no TTL (-1) is not an active block — getExpire can also return null. */
    @Test
    void allowsWhenExpiryIsUnknown() {
        when(redis.getExpire(anyString())).thenReturn(null);

        assertThatCode(() -> throttle.check("a@b.com")).doesNotThrowAnyException();
    }

    /** A counter Redis failed to increment (null) must not be treated as a failure count. */
    @Test
    void recordFailureWritesNoBlockWhenCounterIsNull() {
        when(redis.opsForValue()).thenReturn(values);
        when(values.increment(anyString())).thenReturn(null);

        throttle.recordFailure("a@b.com");
        verify(values, never()).set(anyString(), anyString(), any(Duration.class));
    }

    /** The shift in backoff() overflows past ~62 steps; the guard must hold the ceiling. */
    @Test
    void backoffStaysAtTheCeilingForAbsurdFailureCounts() {
        when(redis.opsForValue()).thenReturn(values);
        when(values.increment(anyString())).thenReturn(1_000L);

        throttle.recordFailure("a@b.com");
        verify(values).set(anyString(), eq("1"), eq(Duration.ofMinutes(15)));
    }

    /** A wrong password must not turn into a 500 just because Redis hiccuped. */
    @Test
    void recordFailureSwallowsRedisErrors() {
        when(redis.opsForValue()).thenReturn(values);
        when(values.increment(anyString())).thenThrow(new RedisConnectionFailureException("down"));

        assertThatCode(() -> throttle.recordFailure("a@b.com")).doesNotThrowAnyException();
    }

    @Test
    void recordSuccessClearsCounterAndBlock() {
        throttle.recordSuccess("a@b.com");
        verify(redis).delete(org.mockito.ArgumentMatchers.anyCollection());
    }

    /** A correct password must still log the user in when Redis can't be cleared. */
    @Test
    void recordSuccessSwallowsRedisErrors() {
        when(redis.delete(org.mockito.ArgumentMatchers.anyCollection()))
                .thenThrow(new RedisConnectionFailureException("down"));

        assertThatCode(() -> throttle.recordSuccess("a@b.com")).doesNotThrowAnyException();
    }

    /** Emails are hashed, so a keyspace dump leaks no addresses — and case doesn't matter. */
    @Test
    void keysAreHashedAndCaseInsensitive() {
        when(redis.getExpire(anyString())).thenReturn(-2L);
        throttle.check("Alice@Example.com");
        throttle.check("alice@example.com");

        var key = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(redis, org.mockito.Mockito.times(2)).getExpire(key.capture());
        assertThat(key.getAllValues()).doesNotContain("login:block:alice@example.com");
        assertThat(key.getAllValues().get(0)).isEqualTo(key.getAllValues().get(1));
    }
}

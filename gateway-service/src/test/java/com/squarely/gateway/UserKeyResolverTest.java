package com.squarely.gateway;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;

class UserKeyResolverTest {

    private static final String SECRET = "test-only-secret-not-used-outside-the-test-jvm-256bit!!";
    private static final SecretKey KEY = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));

    private final UserKeyResolver resolver = new UserKeyResolver(SECRET);

    @Test
    void keysOnTheUserWhenTheTokenIsValid() {
        assertThat(resolve("Bearer " + tokenFor("42", KEY))).isEqualTo("user:42");
    }

    /** The whole point: a forged sub must not buy a fresh bucket, so it falls back to IP. */
    @Test
    void forgedTokenFallsBackToIp() {
        SecretKey attackerKey = Keys.hmacShaKeyFor(
                "an-attacker-key-that-is-long-enough-to-be-accepted!!".getBytes(StandardCharsets.UTF_8));

        assertThat(resolve("Bearer " + tokenFor("999", attackerKey))).startsWith("ip:");
    }

    @Test
    void anonymousAndJunkTokensFallBackToIp() {
        assertThat(resolve(null)).startsWith("ip:");
        assertThat(resolve("Bearer not-a-jwt")).startsWith("ip:");
        assertThat(resolve("Basic abc")).startsWith("ip:");
    }

    /** One IPv6 /64 is one bucket — otherwise a single allocation is 2^64 free buckets. */
    @Test
    void ipv6IsBucketedByPrefix() {
        String first = resolveFrom("[2001:db8:1:2:aaaa:aaaa:aaaa:aaaa]:443");
        String second = resolveFrom("[2001:db8:1:2:ffff:ffff:ffff:ffff]:443");

        assertThat(first).isEqualTo(second).isEqualTo("ip:20010db800010002");
    }

    @Test
    void ipv4KeysOnTheAddress() {
        assertThat(resolveFrom("203.0.113.7:443")).isEqualTo("ip:203.0.113.7");
    }

    private String resolve(String authorization) {
        MockServerHttpRequest.BaseBuilder<?> request = MockServerHttpRequest.get("/groups")
                .remoteAddress(new java.net.InetSocketAddress("198.51.100.4", 443));
        if (authorization != null) {
            request.header(HttpHeaders.AUTHORIZATION, authorization);
        }
        return resolver.resolve(MockServerWebExchange.from(request.build())).block();
    }

    private String resolveFrom(String hostPort) {
        int split = hostPort.lastIndexOf(':');
        String host = hostPort.substring(0, split).replaceAll("[\\[\\]]", "");
        MockServerHttpRequest request = MockServerHttpRequest.get("/groups")
                .remoteAddress(new java.net.InetSocketAddress(host, Integer.parseInt(hostPort.substring(split + 1))))
                .build();
        return resolver.resolve(MockServerWebExchange.from(request)).block();
    }

    private static String tokenFor(String subject, SecretKey key) {
        return Jwts.builder()
                .subject(subject)
                .expiration(new Date(System.currentTimeMillis() + 60_000))
                .signWith(key)
                .compact();
    }
}

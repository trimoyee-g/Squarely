package com.squarely.common.security;

import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.security.SignatureException;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class JwtServiceTest {

    private static final String SECRET = "test-secret-that-is-at-least-256-bits-long-yes!!";

    @Test
    void roundTripCarriesSubjectAndEmail() {
        JwtService jwt = new JwtService(SECRET, Duration.ofMinutes(15));
        String token = jwt.generateAccessToken(42L, "a@b.com");
        var claims = jwt.parse(token);
        assertEquals("42", claims.getSubject());
        assertEquals("a@b.com", claims.get("email"));
    }

    @Test
    void rejectsTokenSignedWithDifferentSecret() {
        String token = new JwtService(SECRET, Duration.ofMinutes(15)).generateAccessToken(1L, "x@y.com");
        JwtService other = new JwtService("a-completely-different-secret-256-bits-long-ok!!", Duration.ofMinutes(15));
        assertThrows(SignatureException.class, () -> other.parse(token));
    }

    @Test
    void rejectsExpiredToken() {
        JwtService jwt = new JwtService(SECRET, Duration.ofSeconds(-1)); // already expired
        String token = jwt.generateAccessToken(1L, "x@y.com");
        assertThrows(ExpiredJwtException.class, () -> jwt.parse(token));
    }
}

package com.squarely.common.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;

/**
 * Issues and validates access tokens. Symmetric HS256 with a shared secret so any
 * service can validate without calling auth.
 * ponytail: shared secret via SECURITY_JWT_SECRET env. Move to RS256 + JWKS (auth
 * holds the private key, others fetch public keys) only if you need key rotation
 * without redeploying every service.
 */
@Component
public class JwtService {

    private final SecretKey key;
    private final Duration accessTtl;

    public JwtService(@Value("${security.jwt.secret}") String secret,
                      @Value("${security.jwt.access-ttl:PT15M}") Duration accessTtl) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.accessTtl = accessTtl;
    }

    public String generateAccessToken(long userId, String email) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(Long.toString(userId))
                .claim("email", email)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(accessTtl)))
                .signWith(key)
                .compact();
    }

    /** Returns claims if the token is valid and unexpired; throws JwtException otherwise. */
    public Claims parse(String token) {
        return Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
    }
}

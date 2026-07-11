package com.squarely.auth.service;

import com.squarely.auth.api.Dtos.*;
import com.squarely.auth.domain.RefreshToken;
import com.squarely.auth.domain.User;
import com.squarely.auth.repo.RefreshTokenRepository;
import com.squarely.auth.repo.UserRepository;
import com.squarely.common.security.JwtService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;

@Service
public class AuthService {

    private final UserRepository users;
    private final RefreshTokenRepository refreshTokens;
    private final PasswordEncoder encoder;
    private final JwtService jwt;
    private final Duration refreshTtl;
    private final SecureRandom random = new SecureRandom();

    public AuthService(UserRepository users, RefreshTokenRepository refreshTokens,
                       PasswordEncoder encoder, JwtService jwt,
                       @Value("${security.jwt.refresh-ttl:P30D}") Duration refreshTtl) {
        this.users = users;
        this.refreshTokens = refreshTokens;
        this.encoder = encoder;
        this.jwt = jwt;
        this.refreshTtl = refreshTtl;
    }

    @Transactional
    public TokenResponse signup(SignupRequest req) {
        if (users.existsByEmail(req.email())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Email already registered");
        }
        User user = users.save(new User(req.email(), encoder.encode(req.password()), req.displayName()));
        return issueTokens(user);
    }

    @Transactional
    public TokenResponse login(LoginRequest req) {
        User user = users.findByEmail(req.email())
                .filter(u -> encoder.matches(req.password(), u.getPasswordHash()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid credentials"));
        return issueTokens(user);
    }

    @Transactional
    public TokenResponse refresh(String rawRefresh) {
        String hash = sha256(rawRefresh);
        RefreshToken token = refreshTokens.findByTokenHash(hash)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid refresh token"));
        if (!token.isActive()) {
            // ponytail: presenting a revoked token could mean theft — revoke the whole
            // family for real reuse-detection. Add if you need that guarantee.
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Expired or revoked refresh token");
        }
        token.setRevoked(true);               // rotate: kill the presented token
        User user = users.findById(token.getUserId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "User gone"));
        return issueTokens(user);
    }

    @Transactional
    public void logout(long userId) {
        refreshTokens.revokeAllForUser(userId);
    }

    private TokenResponse issueTokens(User user) {
        String access = jwt.generateAccessToken(user.getId(), user.getEmail());
        String rawRefresh = randomToken();
        refreshTokens.save(new RefreshToken(user.getId(), sha256(rawRefresh), Instant.now().plus(refreshTtl)));
        return new TokenResponse(access, rawRefresh);
    }

    private String randomToken() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(digest);
        } catch (Exception e) {
            throw new IllegalStateException(e); // SHA-256 always present
        }
    }
}

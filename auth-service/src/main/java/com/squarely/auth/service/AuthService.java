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
import java.util.List;

@Service
public class AuthService {

    private static final int MAX_BATCH = 100;

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokens;
    private final PasswordEncoder encoder;
    private final JwtService jwt;
    private final Duration refreshTtl;
    private final SecureRandom random = new SecureRandom();

    public AuthService(UserRepository userRepository, RefreshTokenRepository refreshTokens,
            PasswordEncoder encoder, JwtService jwt,
            @Value("${security.jwt.refresh-ttl:P30D}") Duration refreshTtl) {
        this.userRepository = userRepository;
        this.refreshTokens = refreshTokens;
        this.encoder = encoder;
        this.jwt = jwt;
        this.refreshTtl = refreshTtl;
    }

    @Transactional
    public TokenResponse signup(SignupRequest req) {
        if (userRepository.existsByEmail(req.email())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Email already registered");
        }
        User user = userRepository.save(new User(req.email(), encoder.encode(req.password()), req.displayName()));
        return issueTokens(user);
    }

    @Transactional
    public TokenResponse login(LoginRequest req) {
        User user = userRepository.findByEmail(req.email())
                .filter(u -> encoder.matches(req.password(), u.getPasswordHash()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid credentials"));
        return issueTokens(user);
    }

    @Transactional
    public TokenResponse refresh(String rawRefresh) {
        String hash = sha256(rawRefresh);
        RefreshToken token = refreshTokens.findByTokenHash(hash)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid refresh token"));
        if (token.getExpiresAt().isBefore(Instant.now()) && !token.isRevoked()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Expired refresh token");
        }
        // Rotate by claiming the token atomically. 0 rows means someone else already
        // spent it — a leaked chain, or a racing duplicate refresh. We cannot tell the
        // two apart, and we choose strict: either way the chain is no longer single-use,
        // so kill the whole family.
        //
        // CLIENT CONTRACT: this means two concurrent refreshes with the same token end
        // the session outright — the loser's family-revoke also kills the winner's brand
        // new token. Every client MUST single-flight its refresh (one shared in-flight
        // request), and MUST NOT blindly retry a refresh whose response was dropped.
        // ponytail: the alternative is a `revoked_at` grace window that forgives races
        // within N seconds. Add it if flaky-network logouts show up in the wild.
        if (refreshTokens.revokeIfActive(token.getId()) == 0) {
            refreshTokens.revokeAllForFamily(token.getFamilyId());
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Refresh token reuse detected");
        }
        User user = userRepository.findById(token.getUserId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "User gone"));
        return issueTokens(user, token.getFamilyId());
    }

    @Transactional
    public void logout(long userId) {
        refreshTokens.revokeAllForUser(userId);
    }

    @Transactional(readOnly = true)
    public UserView findUser(long userId) {
        return userRepository.findById(userId)
                .map(u -> new UserView(u.getId(), u.getEmail(), u.getDisplayName()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    /**
     * Batch id -> displayName lookup for the SPA (Avatars / "User #123"
     * resolution).
     * Any valid token can call it, so it must not leak PII: email is nulled out and
     * the batch size is capped to blunt enumeration. Callers only render
     * displayName.
     */
    @Transactional(readOnly = true)
    public List<UserView> findUsers(List<Long> ids) {
        if (ids.size() > MAX_BATCH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Too many ids (max " + MAX_BATCH + ")");
        }
        return userRepository.findByIdIn(ids).stream()
                // null email; name enumeration stays open, add group-scope if names become
                // sensitive
                .map(u -> new UserView(u.getId(), null, u.getDisplayName()))
                .toList();
    }

    private TokenResponse issueTokens(User user) {
        return issueTokens(user, randomToken()); // fresh login starts a new family
    }

    private TokenResponse issueTokens(User user, String familyId) {
        String access = jwt.generateAccessToken(user.getId(), user.getEmail());
        String rawRefresh = randomToken();
        refreshTokens
                .save(new RefreshToken(user.getId(), familyId, sha256(rawRefresh), Instant.now().plus(refreshTtl)));
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

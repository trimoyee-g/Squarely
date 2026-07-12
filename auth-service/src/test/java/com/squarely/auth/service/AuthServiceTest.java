package com.squarely.auth.service;

import com.squarely.auth.api.Dtos.*;
import com.squarely.auth.domain.RefreshToken;
import com.squarely.auth.domain.User;
import com.squarely.auth.repo.RefreshTokenRepository;
import com.squarely.auth.repo.UserRepository;
import com.squarely.common.security.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock UserRepository users;
    @Mock RefreshTokenRepository refreshTokens;
    @Mock PasswordEncoder encoder;
    @Mock JwtService jwt;

    AuthService auth;

    @BeforeEach
    void setUp() {
        auth = new AuthService(users, refreshTokens, encoder, jwt, Duration.ofDays(30));
    }

    private static User userWithId(long id, String email, String hash) {
        User u = new User(email, hash, "Name");
        ReflectionTestUtils.setField(u, "id", id);
        return u;
    }

    @Test
    void signupRejectsDuplicateEmail() {
        when(users.existsByEmail("a@b.com")).thenReturn(true);
        var ex = assertThrows(ResponseStatusException.class,
                () -> auth.signup(new SignupRequest("a@b.com", "password1", "A")));
        assertEquals(409, ex.getStatusCode().value());
        verify(users, never()).save(any());
    }

    @Test
    void signupHashesPasswordAndIssuesTokens() {
        when(users.existsByEmail(any())).thenReturn(false);
        when(encoder.encode("password1")).thenReturn("HASHED");
        when(users.save(any())).thenReturn(userWithId(1L, "a@b.com", "HASHED"));
        when(jwt.generateAccessToken(1L, "a@b.com")).thenReturn("access-jwt");

        TokenResponse res = auth.signup(new SignupRequest("a@b.com", "password1", "A"));

        assertEquals("access-jwt", res.accessToken());
        assertNotNull(res.refreshToken());
        // password stored hashed, never raw
        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(users).save(saved.capture());
        assertEquals("HASHED", saved.getValue().getPasswordHash());
        // a refresh token row is persisted
        verify(refreshTokens).save(any(RefreshToken.class));
    }

    @Test
    void loginRejectsUnknownEmail() {
        when(users.findByEmail("nope@b.com")).thenReturn(Optional.empty());
        var ex = assertThrows(ResponseStatusException.class,
                () -> auth.login(new LoginRequest("nope@b.com", "password1")));
        assertEquals(401, ex.getStatusCode().value());
    }

    @Test
    void loginRejectsWrongPassword() {
        when(users.findByEmail("a@b.com")).thenReturn(Optional.of(userWithId(1L, "a@b.com", "HASHED")));
        when(encoder.matches("wrong", "HASHED")).thenReturn(false);
        var ex = assertThrows(ResponseStatusException.class,
                () -> auth.login(new LoginRequest("a@b.com", "wrong")));
        assertEquals(401, ex.getStatusCode().value());
    }

    @Test
    void loginSucceedsWithMatchingPassword() {
        when(users.findByEmail("a@b.com")).thenReturn(Optional.of(userWithId(1L, "a@b.com", "HASHED")));
        when(encoder.matches("password1", "HASHED")).thenReturn(true);
        when(jwt.generateAccessToken(1L, "a@b.com")).thenReturn("access-jwt");

        TokenResponse res = auth.login(new LoginRequest("a@b.com", "password1"));
        assertEquals("access-jwt", res.accessToken());
        verify(refreshTokens).save(any());
    }

    @Test
    void refreshRejectsUnknownToken() {
        when(refreshTokens.findByTokenHash(anyString())).thenReturn(Optional.empty());
        var ex = assertThrows(ResponseStatusException.class, () -> auth.refresh("raw"));
        assertEquals(401, ex.getStatusCode().value());
    }

    @Test
    void refreshRejectsRevokedToken() {
        RefreshToken revoked = new RefreshToken(1L, "fam-1", "hash", Instant.now().plusSeconds(3600));
        revoked.setRevoked(true);
        when(refreshTokens.findByTokenHash(anyString())).thenReturn(Optional.of(revoked));
        var ex = assertThrows(ResponseStatusException.class, () -> auth.refresh("raw"));
        assertEquals(401, ex.getStatusCode().value());
    }

    @Test
    void refreshReuseRevokesWholeFamily() {
        RefreshToken revoked = new RefreshToken(1L, "fam-1", "hash", Instant.now().plusSeconds(3600));
        revoked.setRevoked(true);
        when(refreshTokens.findByTokenHash(anyString())).thenReturn(Optional.of(revoked));
        assertThrows(ResponseStatusException.class, () -> auth.refresh("raw"));
        verify(refreshTokens).revokeAllForFamily("fam-1");
        verify(refreshTokens, never()).save(any());
    }

    @Test
    void refreshRotatesTokenAndIssuesNew() {
        RefreshToken active = new RefreshToken(1L, "fam-1", "hash", Instant.now().plusSeconds(3600));
        when(refreshTokens.findByTokenHash(anyString())).thenReturn(Optional.of(active));
        when(refreshTokens.revokeIfActive(any())).thenReturn(1); // we won the rotation race
        when(users.findById(1L)).thenReturn(Optional.of(userWithId(1L, "a@b.com", "HASHED")));
        when(jwt.generateAccessToken(1L, "a@b.com")).thenReturn("access-jwt");

        TokenResponse res = auth.refresh("raw");

        assertEquals("access-jwt", res.accessToken());
        verify(refreshTokens).revokeIfActive(active.getId()); // presented token claimed atomically
        ArgumentCaptor<RefreshToken> saved = ArgumentCaptor.forClass(RefreshToken.class);
        verify(refreshTokens).save(saved.capture()); // new token persisted
        assertEquals("fam-1", saved.getValue().getFamilyId(), "rotated token stays in the same family");
    }

    @Test
    void logoutRevokesEntireFamily() {
        auth.logout(9L);
        verify(refreshTokens).revokeAllForUser(9L);
    }
}

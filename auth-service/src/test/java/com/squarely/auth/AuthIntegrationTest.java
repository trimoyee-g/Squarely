package com.squarely.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.squarely.auth.domain.User;
import com.squarely.auth.repo.UserRepository;
import com.squarely.common.security.JwtService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Full HTTP → service → JPA → Postgres slice for auth. Exercises the real security
 * filter chain, Flyway schema, BCrypt hashing, and DB-backed refresh-token rotation —
 * none of which the mocked unit tests can prove.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class AuthIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired UserRepository users;
    @Autowired JwtService jwt;

    private String body(Object o) throws Exception { return json.writeValueAsString(o); }

    private JsonNode signup(String email) throws Exception {
        var res = mvc.perform(post("/auth/signup").contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("email", email, "password", "password1", "displayName", "A"))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(res);
    }

    @Test
    void signupPersistsUserWithHashedPasswordAndReturnsTokens() throws Exception {
        JsonNode tokens = signup("alice@example.com");
        assertTrue(tokens.hasNonNull("accessToken"));
        assertTrue(tokens.hasNonNull("refreshToken"));

        User saved = users.findByEmail("alice@example.com").orElseThrow();
        assertNotEquals("password1", saved.getPasswordHash());   // never stored raw
        assertTrue(saved.getPasswordHash().startsWith("$2"));    // BCrypt
    }

    @Test
    void duplicateSignupReturns409() throws Exception {
        signup("dupe@example.com");
        mvc.perform(post("/auth/signup").contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("email", "dupe@example.com", "password", "password1", "displayName", "B"))))
                .andExpect(status().isConflict());
    }

    @Test
    void loginRejectsWrongPasswordAcceptsRight() throws Exception {
        signup("bob@example.com");
        mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("email", "bob@example.com", "password", "wrongpass"))))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("email", "bob@example.com", "password", "password1"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty());
    }

    @Test
    void refreshRotatesSoOldTokenCannotBeReused() throws Exception {
        String oldRefresh = signup("carol@example.com").get("refreshToken").asText();

        // First use rotates it and returns a fresh pair.
        mvc.perform(post("/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("refreshToken", oldRefresh))))
                .andExpect(status().isOk());

        // Re-presenting the now-revoked token must fail (persisted revocation).
        mvc.perform(post("/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("refreshToken", oldRefresh))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void meRequiresAuthentication() throws Exception {
        mvc.perform(get("/auth/me")).andExpect(status().isUnauthorized());

        User u = users.save(new User("dave@example.com", "$2a$hash", "Dave"));
        String token = jwt.generateAccessToken(u.getId(), u.getEmail());
        mvc.perform(get("/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("dave@example.com"));
    }
}

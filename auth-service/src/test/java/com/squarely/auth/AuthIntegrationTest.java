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
import org.testcontainers.containers.GenericContainer;
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

    // Real Redis, because LoginThrottle fails closed: with no Redis to reach, every login
    // here would be a 503. A mock would hide exactly the coupling this test exists to prove.
    @Container
    @ServiceConnection(name = "redis")
    static final GenericContainer<?> redis =
            new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

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

    private String refreshOk(String token) throws Exception {
        var res = mvc.perform(post("/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("refreshToken", token))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(res).get("refreshToken").asText();
    }

    @Test
    void reuseOfRotatedTokenKillsTheWholeFamily() throws Exception {
        String a = signup("mallory@example.com").get("refreshToken").asText();
        String b = refreshOk(a);   // legit rotation: A -> B, A now revoked

        // Attacker replays the leaked, already-rotated A. Reuse detected -> 401.
        mvc.perform(post("/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("refreshToken", a))))
                .andExpect(status().isUnauthorized());

        // The whole point of family revocation: B (the victim's live token) must be
        // dead too. If this returns 200, the family revoke never actually committed.
        mvc.perform(post("/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("refreshToken", b))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void concurrentRefreshWithSameTokenIssuesExactlyOnePair() throws Exception {
        String refresh = signup("eve@example.com").get("refreshToken").asText();

        int threads = 8;
        var start = new java.util.concurrent.CountDownLatch(1);
        var pool = java.util.concurrent.Executors.newFixedThreadPool(threads);
        var statuses = java.util.Collections.synchronizedList(new java.util.ArrayList<Integer>());
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                start.await();
                statuses.add(mvc.perform(post("/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("refreshToken", refresh)))).andReturn().getResponse().getStatus());
                return null;
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, java.util.concurrent.TimeUnit.SECONDS));

        // Single-use means single-use: one winner, everyone else is reuse.
        assertEquals(1, statuses.stream().filter(s -> s == 200).count(), "rotation double-spend: " + statuses);
        assertTrue(statuses.stream().allMatch(s -> s == 200 || s == 401), "unexpected statuses: " + statuses);
        // NOTE: we deliberately do NOT assert the winner's new token still works. Under
        // strict semantics the losers' family-revoke collaterally kills it, and the whole
        // session ends. That is the intended trade: clients MUST single-flight refresh.
        // See AuthService.refresh().
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

    @Test
    void internalUsersResolvesNamesButNeverLeaksEmail() throws Exception {
        User target = users.save(new User("victim@example.com", "$2a$hash", "Victim"));
        User caller = users.save(new User("nosy@example.com", "$2a$hash", "Nosy"));
        String token = jwt.generateAccessToken(caller.getId(), caller.getEmail());

        mvc.perform(get("/auth/internal/users").param("ids", target.getId().toString())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].displayName").value("Victim"))  // name resolution still works
                .andExpect(jsonPath("$[0].email").value(org.hamcrest.Matchers.nullValue())) // PII stripped
                .andExpect(content().string(org.hamcrest.Matchers.not(     // and never on the wire
                        org.hamcrest.Matchers.containsString("victim@example.com"))));
    }

    @Test
    void internalUsersRejectsOversizedBatch() throws Exception {
        User caller = users.save(new User("greedy@example.com", "$2a$hash", "Greedy"));
        String token = jwt.generateAccessToken(caller.getId(), caller.getEmail());
        String ids = java.util.stream.LongStream.rangeClosed(1, 101)
                .mapToObj(Long::toString).collect(java.util.stream.Collectors.joining(","));

        mvc.perform(get("/auth/internal/users").param("ids", ids)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest());
    }
}

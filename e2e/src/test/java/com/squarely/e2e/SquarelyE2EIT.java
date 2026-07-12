package com.squarely.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Full end-to-end test: brings up the entire stack (Postgres, Kafka, Redis and all four
 * services) via the real docker-compose, then drives the cross-service flow over HTTP —
 * signup → group → expense (Kafka → ledger) → balances/simplify → settlement handshake →
 * notifications (Kafka) → personal debt → recurring tick. This is the only layer that
 * exercises the real Kafka wire the integration tests deliberately mock.
 *
 * Runs on `mvn -f e2e/pom.xml verify` (failsafe). Skips itself if Docker is unavailable.
 * Set -De2e.manageStack=false to run against an already-running `docker compose up` stack.
 */
class SquarelyE2EIT {

    static final int AUTH = 8081, GROUP = 8082, LEDGER = 8083, NOTIFY = 8084;

    static final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();
    static final ObjectMapper json = new ObjectMapper();

    static boolean manageStack = !"false".equals(System.getProperty("e2e.manageStack"));
    static File composeFile;

    // ---- lifecycle ----

    @BeforeAll
    static void up() throws Exception {
        Assumptions.assumeTrue(dockerAvailable(), "Docker not available — skipping e2e");
        composeFile = locateComposeFile();
        if (manageStack) {
            System.out.println("== docker compose up --build (this builds all service images) ==");
            run(600, "docker", "compose", "-f", composeFile.getAbsolutePath(), "up", "-d", "--build");
        }
        waitForHealth(AUTH, GROUP, LEDGER, NOTIFY);
    }

    @AfterAll
    static void down() throws Exception {
        if (manageStack && composeFile != null) {
            System.out.println("== docker compose down -v ==");
            run(120, "docker", "compose", "-f", composeFile.getAbsolutePath(), "down", "-v");
        }
    }

    // ---- the flow ----

    @Test
    void fullCrossServiceFlow() throws Exception {
        System.out.println("== signup two users ==");
        String priyaEmail = "priya-" + UUID.randomUUID().toString().substring(0, 8) + "@x.com";
        String rahulEmail = "rahul-" + UUID.randomUUID().toString().substring(0, 8) + "@x.com";
        JsonNode p = call(AUTH, "/auth/signup", "POST", null,
                Map.of("email", priyaEmail, "password", "password123", "displayName", "Priya"), null, 201).body;
        JsonNode r = call(AUTH, "/auth/signup", "POST", null,
                Map.of("email", rahulEmail, "password", "password123", "displayName", "Rahul"), null, 201).body;
        String priyaTok = p.get("accessToken").asText(), rahulTok = r.get("accessToken").asText();
        long priyaId = call(AUTH, "/auth/me", "GET", priyaTok, null, null, 200).body.get("id").asLong();
        long rahulId = call(AUTH, "/auth/me", "GET", rahulTok, null, null, 200).body.get("id").asLong();
        assertNotEquals(priyaId, rahulId, "distinct user ids");

        System.out.println("== refresh token rotation ==");
        JsonNode refreshed = call(AUTH, "/auth/refresh", "POST", null,
                Map.of("refreshToken", p.get("refreshToken").asText()), null, 200).body;
        assertTrue(refreshed.hasNonNull("accessToken") && refreshed.hasNonNull("refreshToken"));
        // the rotated-away token must be dead now
        assertEquals(401, call(AUTH, "/auth/refresh", "POST", null,
                Map.of("refreshToken", p.get("refreshToken").asText()), null, null).status);
        priyaTok = refreshed.get("accessToken").asText();
        final String tok = priyaTok;   // stable copy for use inside polling lambdas

        System.out.println("== group + members ==");
        long gid = call(GROUP, "/groups", "POST", priyaTok, Map.of("name", "Goa Trip"), null, 201).body.get("id").asLong();
        call(GROUP, "/groups/" + gid + "/members", "POST", priyaTok, Map.of("userId", rahulId), null, 201);
        JsonNode members = call(GROUP, "/groups/" + gid + "/members", "GET", priyaTok, null, null, 200).body;
        assertEquals(2, members.size(), "group has 2 members");

        System.out.println("== add equally-split expense (Priya pays 3000) ==");
        JsonNode exp = call(GROUP, "/groups/" + gid + "/expenses", "POST", priyaTok, Map.of(
                "description", "Hotel", "category", "TRAVEL", "amount", 3000, "currency", "INR",
                "paidByUserId", priyaId, "splitType", "EQUAL",
                "participants", Map.of(String.valueOf(priyaId), 0, String.valueOf(rahulId), 0)), null, 201).body;
        assertEquals(0, new java.math.BigDecimal("1500.00").compareTo(
                exp.get("splits").get(String.valueOf(rahulId)).decimalValue()), "Rahul owes 1500");

        System.out.println("== wait for Kafka -> ledger to record the debt ==");
        JsonNode owed = pollUntil(() -> {
            JsonNode bal = call(LEDGER, "/balances/group/" + gid, "GET", tok, null, null, 200).body;
            return bal.get("net").size() > 0 ? bal : null;
        }, 25, "ledger consumed expense.added via Kafka");
        assertEquals(0, new java.math.BigDecimal("1500.0").compareTo(
                owed.get("net").get(String.valueOf(priyaId)).decimalValue()), "Priya net +1500");
        boolean simplified = false;
        for (JsonNode t : owed.get("simplified"))
            if (t.get("fromUserId").asLong() == rahulId && t.get("toUserId").asLong() == priyaId
                    && t.get("amount").decimalValue().compareTo(new java.math.BigDecimal("1500.00")) == 0) simplified = true;
        assertTrue(simplified, "simplified: Rahul -> Priya 1500");

        System.out.println("== settlement create with idempotency ==");
        String idem = UUID.randomUUID().toString();
        Map<String, Object> sbody = Map.of("groupId", gid, "fromUserId", rahulId,
                "toUserId", priyaId, "amount", 1500, "currency", "INR");
        long sid1 = call(LEDGER, "/settlements", "POST", rahulTok, sbody, Map.of("Idempotency-Key", idem), 201).body.get("id").asLong();
        long sid2 = call(LEDGER, "/settlements", "POST", rahulTok, sbody, Map.of("Idempotency-Key", idem), 201).body.get("id").asLong();
        assertEquals(sid1, sid2, "idempotency: same settlement on retry");

        System.out.println("== settlement handshake ==");
        assertEquals("PAYMENT_CLAIMED", call(LEDGER, "/settlements/" + sid1 + "/claim", "POST", rahulTok,
                Map.of("utr", "UPI123456"), null, 200).body.get("status").asText());
        // payer cannot acknowledge his own claim
        assertEquals(403, call(LEDGER, "/settlements/" + sid1 + "/acknowledge", "POST", rahulTok, null, null, null).status);
        assertEquals("SETTLED", call(LEDGER, "/settlements/" + sid1 + "/acknowledge", "POST", priyaTok,
                null, null, 200).body.get("status").asText());
        // invalid transition on an already-settled payment
        assertEquals(409, call(LEDGER, "/settlements/" + sid1 + "/acknowledge", "POST", priyaTok, null, null, null).status);

        System.out.println("== balances zero out after settlement ==");
        assertEquals(0, call(LEDGER, "/balances/group/" + gid, "GET", priyaTok, null, null, 200).body.get("net").size(),
                "group fully settled");

        System.out.println("== notifications generated via Kafka ==");
        Set<String> priyaTypes = pollUntil(() -> {
            Set<String> types = typesOf(call(NOTIFY, "/notifications", "GET", tok, null, null, 200).body);
            return types.containsAll(Set.of("EXPENSE_ADDED", "PAYMENT_CLAIMED")) ? types : null;
        }, 20, "Priya notified of expense + claim");
        assertTrue(priyaTypes.containsAll(Set.of("EXPENSE_ADDED", "PAYMENT_CLAIMED")));
        assertTrue(typesOf(call(NOTIFY, "/notifications", "GET", rahulTok, null, null, 200).body)
                .contains("PAYMENT_ACKNOWLEDGED"), "Rahul notified of acknowledgement");

        System.out.println("== personal debt (no group) ==");
        JsonNode pd = call(LEDGER, "/personal-debts", "POST", rahulTok, Map.of(
                "debtorId", rahulId, "creditorId", priyaId, "amount", 1000, "currency", "INR"), null, 201).body;
        assertEquals("PERSONAL_DEBT", pd.get("type").asText());
        assertTrue(pd.get("groupId").isNull(), "personal debt has no group");

        System.out.println("== recurring rule + tick ==");
        long ruleId = call(NOTIFY, "/recurring", "POST", priyaTok, Map.of(
                "groupId", gid, "description", "Flat Rent", "category", "RENT", "amount", 20000, "currency", "INR",
                "cadence", "MONTHLY", "firstDueDate", java.time.LocalDate.now().toString(),
                "memberUserIds", java.util.List.of(priyaId, rahulId)), null, 201).body.get("id").asLong();
        call(NOTIFY, "/recurring/run", "POST", priyaTok, null, null, 204);
        JsonNode obs = call(NOTIFY, "/obligations", "GET", priyaTok, null, null, 200).body;
        boolean generated = false;
        for (JsonNode o : obs) if (o.get("recurringRuleId").asLong() == ruleId) generated = true;
        assertTrue(generated, "recurring tick generated an obligation");

        System.out.println("\n==== FULL E2E PASSED ====");
    }

    // ---- helpers ----

    record Resp(int status, JsonNode body) {}

    Resp call(int port, String path, String method, String token, Object body,
              Map<String, String> headers, Integer expect) throws Exception {
        var b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(15)).header("Content-Type", "application/json");
        HttpRequest.BodyPublisher pub = body == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body));
        b.method(method, pub);
        if (token != null) b.header("Authorization", "Bearer " + token);
        if (headers != null) headers.forEach(b::header);
        HttpResponse<String> res = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        if (expect != null && res.statusCode() != expect)
            fail(method + " " + path + " -> " + res.statusCode() + " (expected " + expect + ")\n" + res.body());
        JsonNode node = res.body() == null || res.body().isBlank() ? null : json.readTree(res.body());
        return new Resp(res.statusCode(), node);
    }

    static Set<String> typesOf(JsonNode notes) {
        return java.util.stream.StreamSupport.stream(notes.spliterator(), false)
                .map(n -> n.get("type").asText()).collect(Collectors.toSet());
    }

    interface Poll<T> { T get() throws Exception; }

    static <T> T pollUntil(Poll<T> poll, int attempts, String what) throws Exception {
        for (int i = 0; i < attempts; i++) {
            T v = poll.get();
            if (v != null) return v;
            Thread.sleep(1000);
        }
        return fail("timed out waiting for: " + what);
    }

    // ---- infra ----

    static boolean dockerAvailable() {
        try { return run(20, "docker", "info") == 0; } catch (Exception e) { return false; }
    }

    static File locateComposeFile() {
        File dir = new File(System.getProperty("user.dir"));
        for (int i = 0; i < 4 && dir != null; i++, dir = dir.getParentFile()) {
            File f = new File(dir, "docker-compose.yml");
            if (f.isFile()) return f;
        }
        throw new IllegalStateException("docker-compose.yml not found from " + System.getProperty("user.dir"));
    }

    static void waitForHealth(int... ports) throws Exception {
        for (int port : ports) {
            boolean up = false;
            for (int i = 0; i < 180 && !up; i++) {
                try {
                    Resp res = new SquarelyE2EIT().call(port, "/actuator/health", "GET", null, null, null, null);
                    up = res.status == 200 && res.body != null && "UP".equals(res.body.path("status").asText());
                } catch (Exception ignored) { /* not up yet */ }
                if (!up) Thread.sleep(1000);
            }
            if (!up) fail("service on port " + port + " never became healthy");
            System.out.println("  healthy: " + port);
        }
    }

    /** Runs a command, streaming output, returns exit code. */
    static int run(int timeoutSeconds, String... cmd) throws Exception {
        Process proc = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        try (var in = proc.getInputStream()) {
            in.transferTo(System.out);
        }
        if (!proc.waitFor(timeoutSeconds, java.util.concurrent.TimeUnit.SECONDS)) {
            proc.destroyForcibly();
            throw new IllegalStateException("command timed out: " + String.join(" ", cmd));
        }
        return proc.exitValue();
    }
}

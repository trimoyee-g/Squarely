package com.squarely.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

/**
 * End-to-end smoke test for Squarely — the Java twin of smoke-test.py. Exercises the real
 * running services (assumes `docker compose up` is already healthy on 8081-8084):
 * signup -> group -> expense (Kafka -> ledger) -> balances/simplify -> settlement handshake
 * (create/claim/ack) with idempotency + a concurrency/invalid-transition check.
 * Exits non-zero on the first failed assertion.
 *
 *   run:  mvn -f e2e/pom.xml -q compile exec:java
 */
public final class SmokeTest {

    static final int AUTH = 8081, GROUP = 8082, LEDGER = 8083, NOTIFY = 8084;
    static final String PASS = "[92mPASS[0m";

    static final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    static final ObjectMapper json = new ObjectMapper();

    public static void main(String[] args) {
        try {
            run();
            System.out.println("\n[92m==== ALL SMOKE TESTS PASSED ====[0m");
        } catch (AssertionError e) {
            System.err.println("[91m" + e.getMessage() + "[0m");
            System.exit(1);
        } catch (Exception e) {
            System.err.println("[91mERROR: " + e + "[0m");
            System.exit(2);
        }
    }

    static void run() throws Exception {
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
        check("signup + JWT + /me", priyaId != rahulId, "priya=" + priyaId + " rahul=" + rahulId);

        System.out.println("== refresh token rotation ==");
        JsonNode refreshed = call(AUTH, "/auth/refresh", "POST", null,
                Map.of("refreshToken", p.get("refreshToken").asText()), null, 200).body;
        check("refresh issues a token pair", refreshed.hasNonNull("accessToken") && refreshed.hasNonNull("refreshToken"), "");
        int st = call(AUTH, "/auth/refresh", "POST", null, Map.of("refreshToken", p.get("refreshToken").asText()), null, null).status;
        check("old refresh token revoked after rotation (401)", st == 401, "got " + st);
        priyaTok = refreshed.get("accessToken").asText();

        System.out.println("== group + members ==");
        long gid = call(GROUP, "/groups", "POST", priyaTok, Map.of("name", "Goa Trip"), null, 201).body.get("id").asLong();
        call(GROUP, "/groups/" + gid + "/members", "POST", priyaTok, Map.of("userId", rahulId), null, 201);
        JsonNode members = call(GROUP, "/groups/" + gid + "/members", "GET", priyaTok, null, null, 200).body;
        check("group has 2 members", members.size() == 2, "members=" + members.size());

        System.out.println("== add equally-split expense (Priya pays 3000, split with Rahul) ==");
        JsonNode exp = call(GROUP, "/groups/" + gid + "/expenses", "POST", priyaTok, Map.of(
                "description", "Hotel", "category", "TRAVEL", "amount", 3000, "currency", "INR",
                "paidByUserId", priyaId, "splitType", "EQUAL",
                "participants", Map.of(String.valueOf(priyaId), 0, String.valueOf(rahulId), 0)), null, 201).body;
        var rahulOwes = exp.get("splits").get(String.valueOf(rahulId)).decimalValue();
        check("expense split equally", rahulOwes.compareTo(new java.math.BigDecimal("1500.00")) == 0, "rahul owes " + rahulOwes);

        System.out.println("== wait for Kafka -> ledger to record the debt ==");
        JsonNode owed = null;
        for (int i = 0; i < 20 && owed == null; i++) {
            Thread.sleep(1000);
            JsonNode bal = call(LEDGER, "/balances/group/" + gid, "GET", priyaTok, null, null, 200).body;
            if (bal.get("net").size() > 0) owed = bal;
        }
        check("ledger recorded expense via Kafka", owed != null, "(consumed expense.added)");
        check("Priya is owed 1500 net",
                owed.get("net").get(String.valueOf(priyaId)).decimalValue().compareTo(new java.math.BigDecimal("1500.0")) == 0,
                "net=" + owed.get("net"));
        boolean simplified = false;
        for (JsonNode t : owed.get("simplified"))
            if (t.get("fromUserId").asLong() == rahulId && t.get("toUserId").asLong() == priyaId
                    && t.get("amount").decimalValue().compareTo(new java.math.BigDecimal("1500.00")) == 0) simplified = true;
        check("simplified: Rahul -> Priya 1500", simplified, "simplified=" + owed.get("simplified"));

        System.out.println("== settlement create with idempotency (Rahul pays Priya 1500) ==");
        String idem = UUID.randomUUID().toString();
        Map<String, Object> body = Map.of("groupId", gid, "fromUserId", rahulId, "toUserId", priyaId, "amount", 1500, "currency", "INR");
        long sid1 = call(LEDGER, "/settlements", "POST", rahulTok, body, Map.of("Idempotency-Key", idem), 201).body.get("id").asLong();
        long sid2 = call(LEDGER, "/settlements", "POST", rahulTok, body, Map.of("Idempotency-Key", idem), 201).body.get("id").asLong();
        check("idempotency: same settlement on retry", sid1 == sid2, "id=" + sid1);

        System.out.println("== settlement handshake ==");
        JsonNode sc = call(LEDGER, "/settlements/" + sid1 + "/claim", "POST", rahulTok, Map.of("utr", "UPI123456"), null, 200).body;
        check("payer claim -> PAYMENT_CLAIMED", "PAYMENT_CLAIMED".equals(sc.get("status").asText()), "utr=" + sc.get("utr").asText());
        st = call(LEDGER, "/settlements/" + sid1 + "/acknowledge", "POST", rahulTok, null, null, null).status;
        check("payer cannot acknowledge (403)", st == 403, "got " + st);
        JsonNode sa = call(LEDGER, "/settlements/" + sid1 + "/acknowledge", "POST", priyaTok, null, null, 200).body;
        check("receiver acknowledge -> SETTLED", "SETTLED".equals(sa.get("status").asText()), "");
        st = call(LEDGER, "/settlements/" + sid1 + "/acknowledge", "POST", priyaTok, null, null, null).status;
        check("cannot settle an already-settled payment (409)", st == 409, "got " + st);

        System.out.println("== balances zero out after settlement ==");
        JsonNode bal2 = call(LEDGER, "/balances/group/" + gid, "GET", priyaTok, null, null, 200).body;
        check("group fully settled (no net balances)", bal2.get("net").size() == 0, "net=" + bal2.get("net"));

        System.out.println("== notifications generated via Kafka ==");
        Set<String> prcv = null;
        for (int i = 0; i < 15 && prcv == null; i++) {
            Thread.sleep(1000);
            Set<String> types = typesOf(call(NOTIFY, "/notifications", "GET", priyaTok, null, null, 200).body);
            if (types.containsAll(Set.of("EXPENSE_ADDED", "PAYMENT_CLAIMED"))) prcv = types;
        }
        check("Priya notified of expense + claim", prcv != null, "types=" + prcv);
        Set<String> rtypes = typesOf(call(NOTIFY, "/notifications", "GET", rahulTok, null, null, 200).body);
        check("Rahul notified of acknowledgement", rtypes.contains("PAYMENT_ACKNOWLEDGED"), "");

        System.out.println("== personal debt (no group) ==");
        JsonNode pd = call(LEDGER, "/personal-debts", "POST", rahulTok, Map.of(
                "debtorId", rahulId, "creditorId", priyaId, "amount", 1000, "currency", "INR"), null, 201).body;
        check("personal debt recorded", "PERSONAL_DEBT".equals(pd.get("type").asText()) && pd.get("groupId").isNull(), "");

        System.out.println("== recurring rule + tick ==");
        JsonNode rule = call(NOTIFY, "/recurring", "POST", priyaTok, Map.of(
                "groupId", gid, "description", "Flat Rent", "category", "RENT", "amount", 20000, "currency", "INR",
                "cadence", "MONTHLY", "firstDueDate", LocalDate.now().toString(),
                "memberUserIds", List.of(priyaId, rahulId)), null, 201).body;
        call(NOTIFY, "/recurring/run", "POST", priyaTok, null, null, 204);
        JsonNode obs = call(NOTIFY, "/obligations", "GET", priyaTok, null, null, 200).body;
        boolean generated = false;
        for (JsonNode o : obs) if (o.get("recurringRuleId").asLong() == rule.get("id").asLong()) generated = true;
        check("recurring tick generated an obligation", generated, "");
    }

    // ---- helpers ----

    record Resp(int status, JsonNode body) {}

    static Resp call(int port, String path, String method, String token, Object bodyObj,
                     Map<String, String> headers, Integer expect) throws Exception {
        var b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(15)).header("Content-Type", "application/json");
        HttpRequest.BodyPublisher pub = bodyObj == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(bodyObj));
        b.method(method, pub);
        if (token != null) b.header("Authorization", "Bearer " + token);
        if (headers != null) headers.forEach(b::header);
        HttpResponse<String> res = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        if (expect != null && res.statusCode() != expect)
            throw new AssertionError(method + " :" + port + path + " -> " + res.statusCode() + " (expected " + expect + ")\n" + res.body());
        JsonNode node = res.body() == null || res.body().isBlank() ? null : json.readTree(res.body());
        return new Resp(res.statusCode(), node);
    }

    static void check(String name, boolean cond, String detail) {
        if (!cond) throw new AssertionError("FAILED: " + name + " " + detail);
        System.out.println("  " + PASS + "  " + name + "  " + detail);
    }

    static Set<String> typesOf(JsonNode notes) {
        return StreamSupport.stream(notes.spliterator(), false).map(n -> n.get("type").asText()).collect(Collectors.toSet());
    }

    private SmokeTest() {}
}

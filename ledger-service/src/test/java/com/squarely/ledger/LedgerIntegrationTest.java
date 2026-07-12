package com.squarely.ledger;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.squarely.common.events.Events;
import com.squarely.common.security.JwtService;
import com.squarely.ledger.repo.Repos.LedgerRepository;
import com.squarely.ledger.service.LedgerService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration slice for ledger + settlements against real Postgres. The point of this
 * layer: prove the DB-enforced idempotency the mocked unit tests can only assume —
 * uq_ledger_ref (no double-recorded expense) and uq_settlement_idem (replayed key
 * returns the same settlement). Kafka producer is mocked; the consumer is disabled
 * and its logic exercised by calling the service directly.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@TestPropertySource(properties = "spring.kafka.listener.auto-startup=false")
class LedgerIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JwtService jwt;
    @Autowired LedgerService ledgerService;
    @Autowired LedgerRepository ledger;

    @MockBean KafkaTemplate<String, Object> kafka;

    private MockHttpServletRequestBuilder as(long userId, MockHttpServletRequestBuilder b) {
        return b.header("Authorization", "Bearer " + jwt.generateAccessToken(userId, "u" + userId + "@x.com"));
    }

    private String body(Object o) throws Exception { return json.writeValueAsString(o); }

    /**
     * Redelivery idempotency through the real service + Postgres: recording the same
     * expense twice must leave exactly one ledger row and must NOT throw. The check-first
     * guard avoids flushing a duplicate INSERT; uq_ledger_ref is the backstop.
     */
    @Test
    void recordingSameExpenseTwiceIsIdempotent() {
        long groupId = 700L;
        var event = new Events.ExpenseAdded(9001L, groupId, "Lunch", "food",
                new BigDecimal("40.00"), "INR", 1L, Map.of(2L, new BigDecimal("40.00")), Instant.now());

        ledgerService.recordExpense(event);
        assertDoesNotThrow(() -> ledgerService.recordExpense(event));   // redelivery: no poison
        assertEquals(1, ledger.findByGroupId(groupId).size());
    }

    @Test
    void personalDebtRejectsSelfDebtAndAcceptsValid() throws Exception {
        mvc.perform(as(1L, post("/personal-debts")).contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("debtorId", 1, "creditorId", 1, "amount", "10.00", "currency", "inr"))))
                .andExpect(status().isBadRequest());
        mvc.perform(as(1L, post("/personal-debts")).contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("debtorId", 1, "creditorId", 2, "amount", "10.00", "currency", "inr"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.currency").value("INR"));
    }

    @Test
    void settlementReplayWithSameIdempotencyKeyReturnsSameRow() throws Exception {
        var req = body(Map.of("groupId", 5, "fromUserId", 1, "toUserId", 2, "amount", "30.00", "currency", "inr"));

        long first = json.readTree(mvc.perform(as(1L, post("/settlements"))
                        .header("Idempotency-Key", "idem-1").contentType(MediaType.APPLICATION_JSON).content(req))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).get("id").asLong();

        long replay = json.readTree(mvc.perform(as(1L, post("/settlements"))
                        .header("Idempotency-Key", "idem-1").contentType(MediaType.APPLICATION_JSON).content(req))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).get("id").asLong();

        assertEquals(first, replay);   // uq_settlement_idem -> replay, not a duplicate
    }

    @Test
    void fullSettlementHandshakeSettlesAndWritesLedgerEntry() throws Exception {
        var req = body(Map.of("groupId", 5, "fromUserId", 1, "toUserId", 2, "amount", "30.00", "currency", "inr"));
        long id = json.readTree(mvc.perform(as(1L, post("/settlements"))
                        .header("Idempotency-Key", "idem-2").contentType(MediaType.APPLICATION_JSON).content(req))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).get("id").asLong();

        // payer claims
        mvc.perform(as(1L, post("/settlements/" + id + "/claim")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAYMENT_CLAIMED"));
        // receiver acknowledges -> SETTLED + settling ledger entry
        mvc.perform(as(2L, post("/settlements/" + id + "/acknowledge")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SETTLED"));

        mvc.perform(as(1L, get("/ledger/group/5")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.type=='SETTLEMENT')].length()").isNotEmpty());
    }

    @Test
    void claimByNonPayerIsForbidden() throws Exception {
        var req = body(Map.of("groupId", 5, "fromUserId", 1, "toUserId", 2, "amount", "30.00", "currency", "inr"));
        long id = json.readTree(mvc.perform(as(1L, post("/settlements"))
                        .header("Idempotency-Key", "idem-3").contentType(MediaType.APPLICATION_JSON).content(req))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).get("id").asLong();
        // user 2 is the receiver, not the payer
        mvc.perform(as(2L, post("/settlements/" + id + "/claim")))
                .andExpect(status().isForbidden());
    }
}

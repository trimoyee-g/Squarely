package com.squarely.notification;

import com.squarely.common.events.Events;
import com.squarely.common.security.JwtService;
import com.squarely.notification.domain.RecurringRule;
import com.squarely.notification.domain.RecurringRule.Cadence;
import com.squarely.notification.kafka.EventConsumer;
import com.squarely.notification.repo.Repos.ObligationRepository;
import com.squarely.notification.repo.Repos.RecurringRuleRepository;
import com.squarely.notification.service.RecurringService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration slice for notifications against real Postgres. Proves the DB-enforced
 * uq_obligation_rule_due idempotency (a re-run tick can't duplicate an obligation) and
 * the consumer → NotificationService → DB fan-out. Kafka listener is disabled; the
 * consumer bean is invoked directly.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@TestPropertySource(properties = "spring.kafka.listener.auto-startup=false")
class NotificationIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired MockMvc mvc;
    @Autowired JwtService jwt;
    @Autowired RecurringService recurring;
    @Autowired ObligationRepository obligations;
    @Autowired RecurringRuleRepository rules;
    @Autowired EventConsumer consumer;

    private MockHttpServletRequestBuilder as(long userId, MockHttpServletRequestBuilder b) {
        return b.header("Authorization", "Bearer " + jwt.generateAccessToken(userId, "u" + userId + "@x.com"));
    }

    /**
     * Re-running the tick for the same (rule, due) through the real service + Postgres
     * must not duplicate the obligation and must NOT throw. generateUpcoming's check-first
     * guard skips the existing row; uq_obligation_rule_due is the backstop.
     */
    @Test
    void reRunTickForSameDueDateIsIdempotent() {
        LocalDate today = LocalDate.now();
        RecurringRule rule = recurring.createRule(new RecurringRule(5L, 1L, "Rent", "housing",
                new BigDecimal("1000.00"), "INR", Cadence.MONTHLY, null, today, List.of(10L, 11L)));

        recurring.tick(today);   // generates the obligation for `today`, advances the rule

        // Force the same (rule, due) to be processed again.
        RecurringRule fresh = rules.findById(rule.getId()).orElseThrow();
        fresh.setNextDueDate(today);
        rules.save(fresh);

        assertDoesNotThrow(() -> recurring.tick(today));
        assertEquals(1, obligations.findByRecurringRuleIdInOrderByDueDateAsc(List.of(rule.getId())).size());
    }

    @Test
    void consumedExpenseEventCreatesNotificationPerParticipant() throws Exception {
        var event = new Events.ExpenseAdded(4242L, 5L, "Pizza", "food",
                new BigDecimal("20.00"), "INR", 20L,
                java.util.Map.of(20L, new BigDecimal("10.00"), 21L, new BigDecimal("10.00")), Instant.now());

        consumer.onExpenseAdded(event);

        mvc.perform(as(20L, get("/notifications")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].type").value("EXPENSE_ADDED"))
                .andExpect(jsonPath("$[0].refId").value(4242));
    }

    @Test
    void markReadClearsUnreadCount() throws Exception {
        var event = new Events.ExpenseAdded(5555L, 5L, "Cab", "travel",
                new BigDecimal("8.00"), "INR", 30L,
                java.util.Map.of(30L, new BigDecimal("8.00")), Instant.now());
        consumer.onExpenseAdded(event);

        String list = mvc.perform(as(30L, get("/notifications")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        long notifId = new com.fasterxml.jackson.databind.ObjectMapper().readTree(list).get(0).get("id").asLong();

        mvc.perform(as(30L, post("/notifications/" + notifId + "/read")))
                .andExpect(status().isNoContent());
        mvc.perform(as(30L, get("/notifications/unread-count")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(0));
    }
}

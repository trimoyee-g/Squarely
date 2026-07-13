package com.squarely.notification.api;

import com.squarely.notification.api.Dtos.*;
import com.squarely.notification.domain.PaymentObligation;
import com.squarely.notification.domain.PaymentObligation.Status;
import com.squarely.notification.domain.RecurringRule;
import com.squarely.notification.domain.RecurringRule.Cadence;
import com.squarely.notification.service.RecurringService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * The controller is thin, but not empty: it pins the acting user from the token rather than
 * the body, normalizes currency, and tolerates a missing settle body. Those are the paths here
 * — the HTTP wiring itself is covered by NotificationIntegrationTest.
 */
@ExtendWith(MockitoExtension.class)
class RecurringControllerTest {

    private static final long ACTING_USER = 9L;

    @Mock RecurringService service;
    RecurringController controller;

    @BeforeEach
    void setUp() {
        controller = new RecurringController(service);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(ACTING_USER, null, List.of()));
    }

    @AfterEach
    void tearDown() { SecurityContextHolder.clearContext(); }

    private static RecurringRule rule(long id, boolean active) {
        RecurringRule r = new RecurringRule(5L, ACTING_USER, "Rent", "housing", new BigDecimal("1000.00"),
                "INR", Cadence.MONTHLY, null, LocalDate.of(2026, 8, 1), List.of(9L, 2L));
        ReflectionTestUtils.setField(r, "id", id);
        r.setActive(active);
        return r;
    }

    @Test
    void createUsesTheTokensUserAndUppercasesCurrency() {
        // save() assigns the id; echo the rule back with one, as the repository would.
        when(service.createRule(any())).thenAnswer(inv -> {
            RecurringRule r = inv.getArgument(0);
            ReflectionTestUtils.setField(r, "id", 1L);
            return r;
        });

        RuleView v = controller.create(new CreateRecurringRuleRequest(5L, "Rent", "housing",
                new BigDecimal("1000.00"), "inr", Cadence.MONTHLY, null,
                LocalDate.of(2026, 8, 1), List.of(9L, 2L)));

        assertEquals("INR", v.currency());
        assertEquals(ACTING_USER, v.createdBy());   // from the token, never from the body
        assertTrue(v.active());
    }

    @Test
    void myRulesMapsEachRuleToItsView() {
        when(service.rulesOf(ACTING_USER)).thenReturn(List.of(rule(1L, true)));

        List<RuleView> views = controller.myRules();

        assertEquals(1, views.size());
        assertEquals(1L, views.get(0).id());
        assertEquals(List.of(9L, 2L), views.get(0).memberUserIds());
    }

    @Test
    void updatePassesTheActingUserSoTheServiceCanCheckOwnership() {
        when(service.updateRule(eq(1L), eq(ACTING_USER), any(), any(), any(), eq("USD"),
                any(), any(), any(), any())).thenReturn(rule(1L, true));

        RuleView v = controller.update(1L, new UpdateRecurringRuleRequest("Rent", "housing",
                new BigDecimal("1000.00"), "usd", Cadence.MONTHLY, null,
                LocalDate.of(2026, 8, 1), List.of(9L)));

        assertEquals(1L, v.id());
    }

    @Test
    void deleteDeactivatesAsTheActingUser() {
        controller.delete(1L);
        verify(service).deactivateRule(1L, ACTING_USER);
    }

    @Test
    void myObligationsMapsEachObligationToItsView() {
        PaymentObligation o = new PaymentObligation(1L, LocalDate.of(2026, 8, 1),
                new BigDecimal("1000.00"), "INR", "Rent");
        ReflectionTestUtils.setField(o, "id", 7L);
        o.setStatus(Status.DUE);
        when(service.obligationsFor(ACTING_USER)).thenReturn(List.of(o));

        List<ObligationView> views = controller.myObligations();

        assertEquals(1, views.size());
        assertEquals(Status.DUE, views.get(0).status());
        assertNull(views.get(0).settlementId());
    }

    @Test
    void settleAcceptsASettlementId() {
        controller.settle(7L, new SettleObligationRequest(42L));
        verify(service).settle(7L, 42L);
    }

    /** Settling a cash payment sends no body at all — that must not NPE. */
    @Test
    void settleWithNoBodySettlesWithoutASettlementId() {
        controller.settle(7L, null);
        verify(service).settle(7L, null);
    }

    @Test
    void manualRunTicksToday() {
        controller.run();
        verify(service).tick(LocalDate.now());
    }
}

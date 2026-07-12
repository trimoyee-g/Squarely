package com.squarely.notification.service;

import com.squarely.notification.domain.PaymentObligation;
import com.squarely.notification.domain.PaymentObligation.Status;
import com.squarely.notification.domain.RecurringRule;
import com.squarely.notification.domain.RecurringRule.Cadence;
import com.squarely.notification.repo.Repos.ObligationRepository;
import com.squarely.notification.repo.Repos.RecurringRuleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RecurringServiceTest {

    @Mock RecurringRuleRepository rules;
    @Mock ObligationRepository obligations;
    @Mock NotificationService notifications;

    RecurringService service;

    @BeforeEach
    void setUp() { service = new RecurringService(rules, obligations, notifications); }

    private static RecurringRule rule(long id, long createdBy, Cadence cadence, LocalDate nextDue, List<Long> members) {
        RecurringRule r = new RecurringRule(5L, createdBy, "Rent", "housing",
                new BigDecimal("1000.00"), "INR", cadence, null, nextDue, members);
        ReflectionTestUtils.setField(r, "id", id);
        return r;
    }

    private static PaymentObligation obligation(long id, long ruleId, LocalDate dueDate, Status status) {
        PaymentObligation o = new PaymentObligation(ruleId, dueDate, new BigDecimal("1000.00"), "INR", "Rent");
        ReflectionTestUtils.setField(o, "id", id);
        o.setStatus(status);
        return o;
    }

    @Test
    void tickGeneratesObligationNotifiesMembersAndAdvancesDate() {
        LocalDate today = LocalDate.of(2026, 1, 1);
        LocalDate due = today.plusDays(2);
        RecurringRule r = rule(1L, 9L, Cadence.WEEKLY, due, List.of(1L, 2L));
        when(rules.findByActiveTrueAndNextDueDateLessThanEqual(any())).thenReturn(List.of(r));
        when(obligations.findByStatusInAndDueDateLessThanEqual(any(), any())).thenReturn(List.of());

        service.tick(today);

        verify(obligations).save(argThat(o -> o.getDueDate().equals(due)));
        verify(notifications).notify(eq(1L), eq("PAYMENT_DUE"), anyString(), eq("OBLIGATION"), eq(1L));
        verify(notifications).notify(eq(2L), eq("PAYMENT_DUE"), anyString(), eq("OBLIGATION"), eq(1L));
        assertEquals(due.plusWeeks(1), r.getNextDueDate());   // advanced one period
    }

    @Test
    void tickAdvancesPastDueObligationToOverdue() {
        LocalDate today = LocalDate.of(2026, 1, 10);
        when(rules.findByActiveTrueAndNextDueDateLessThanEqual(any())).thenReturn(List.of());
        PaymentObligation o = obligation(7L, 1L, today.minusDays(1), Status.UPCOMING);
        when(obligations.findByStatusInAndDueDateLessThanEqual(any(), eq(today))).thenReturn(List.of(o));
        when(rules.findById(1L)).thenReturn(Optional.of(rule(1L, 9L, Cadence.WEEKLY, today, List.of(3L))));

        service.tick(today);

        assertEquals(Status.OVERDUE, o.getStatus());
        verify(notifications).notify(eq(3L), eq("PAYMENT_OVERDUE"), anyString(), eq("OBLIGATION"), eq(7L));
    }

    @Test
    void updateRuleRejectsNonCreator() {
        when(rules.findById(1L)).thenReturn(Optional.of(rule(1L, 9L, Cadence.WEEKLY, LocalDate.now(), List.of(1L))));
        var ex = assertThrows(ResponseStatusException.class, () -> service.updateRule(
                1L, 999L, "x", "c", new BigDecimal("1"), "INR", Cadence.WEEKLY, null, LocalDate.now(), List.of(1L)));
        assertEquals(403, ex.getStatusCode().value());
    }

    @Test
    void updateRuleAppliesChangesForCreator() {
        RecurringRule r = rule(1L, 9L, Cadence.WEEKLY, LocalDate.now(), List.of(1L));
        when(rules.findById(1L)).thenReturn(Optional.of(r));
        service.updateRule(1L, 9L, "New", "cat", new BigDecimal("2000.00"), "USD",
                Cadence.MONTHLY, null, LocalDate.of(2026, 2, 1), List.of(1L, 2L));
        assertEquals("New", r.getDescription());
        assertEquals(Cadence.MONTHLY, r.getCadence());
    }

    @Test
    void deactivateRuleRejectsNonCreator() {
        when(rules.findById(1L)).thenReturn(Optional.of(rule(1L, 9L, Cadence.WEEKLY, LocalDate.now(), List.of(1L))));
        var ex = assertThrows(ResponseStatusException.class, () -> service.deactivateRule(1L, 999L));
        assertEquals(403, ex.getStatusCode().value());
    }

    @Test
    void deactivateRuleFlagsInactiveForCreator() {
        RecurringRule r = rule(1L, 9L, Cadence.WEEKLY, LocalDate.now(), List.of(1L));
        when(rules.findById(1L)).thenReturn(Optional.of(r));
        service.deactivateRule(1L, 9L);
        assertFalse(r.isActive());
    }

    @Test
    void rulesOfMergesCreatorAndMemberRulesAndDropsInactive() {
        RecurringRule mine = rule(1L, 9L, Cadence.WEEKLY, LocalDate.now(), List.of(9L));
        RecurringRule shared = rule(2L, 5L, Cadence.WEEKLY, LocalDate.now(), List.of(9L));
        RecurringRule dead = rule(3L, 9L, Cadence.WEEKLY, LocalDate.now(), List.of(9L));
        dead.setActive(false);
        when(rules.findByCreatedBy(9L)).thenReturn(List.of(mine, dead));
        when(rules.findByMemberUserIdsContaining(9L)).thenReturn(List.of(shared));

        List<RecurringRule> result = service.rulesOf(9L);
        assertEquals(2, result.size());          // mine + shared, dead filtered out
        assertTrue(result.stream().allMatch(RecurringRule::isActive));
    }

    @Test
    void settleMarksObligationSettledWithSettlementId() {
        PaymentObligation o = obligation(7L, 1L, LocalDate.now(), Status.DUE);
        when(obligations.findById(7L)).thenReturn(Optional.of(o));
        service.settle(7L, 42L);
        assertEquals(Status.SETTLED, o.getStatus());
        assertEquals(42L, o.getSettlementId());
    }
}

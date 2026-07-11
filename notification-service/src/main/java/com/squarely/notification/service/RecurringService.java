package com.squarely.notification.service;

import com.squarely.notification.domain.PaymentObligation;
import com.squarely.notification.domain.PaymentObligation.Status;
import com.squarely.notification.domain.RecurringRule;
import com.squarely.notification.domain.RecurringRule.Cadence;
import com.squarely.notification.recurring.RecurrenceCalculator;
import com.squarely.notification.repo.Repos.ObligationRepository;
import com.squarely.notification.repo.Repos.RecurringRuleRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Manages recurring rules and the tick that generates obligations and advances their
 * lifecycle UPCOMING → DUE → OVERDUE. The tick is idempotent: obligation generation is
 * guarded by unique(rule_id, due_date), so running it twice a day changes nothing.
 */
@Service
public class RecurringService {

    /** How far ahead to pre-create the next obligation so members get an early heads-up. */
    private static final int LOOKAHEAD_DAYS = 7;

    private final RecurringRuleRepository rules;
    private final ObligationRepository obligations;
    private final NotificationService notifications;

    public RecurringService(RecurringRuleRepository rules, ObligationRepository obligations,
                            NotificationService notifications) {
        this.rules = rules;
        this.obligations = obligations;
        this.notifications = notifications;
    }

    @Transactional
    public RecurringRule createRule(RecurringRule rule) {
        return rules.save(rule);
    }

    /** One scheduler tick: generate upcoming obligations, then advance due/overdue states. */
    @Transactional
    public void tick(LocalDate today) {
        generateUpcoming(today);
        advanceStatuses(today);
    }

    private void generateUpcoming(LocalDate today) {
        LocalDate horizon = today.plusDays(LOOKAHEAD_DAYS);
        for (RecurringRule rule : rules.findByActiveTrueAndNextDueDateLessThanEqual(horizon)) {
            LocalDate due = rule.getNextDueDate();
            try {
                obligations.saveAndFlush(new PaymentObligation(
                        rule.getId(), due, rule.getAmount(), rule.getCurrency(), rule.getDescription()));
                String msg = "Upcoming: %s (%s %s) due %s"
                        .formatted(rule.getDescription(), rule.getCurrency(), rule.getAmount(), due);
                rule.getMemberUserIds().forEach(u ->
                        notifications.notify(u, "PAYMENT_DUE", msg, "OBLIGATION", rule.getId()));
            } catch (DataIntegrityViolationException dup) {
                // Obligation for this (rule, due date) already exists — idempotent.
            }
            // Advance one period so the next tick generates the following obligation.
            rule.setNextDueDate(RecurrenceCalculator.next(rule.getCadence(), rule.getIntervalDays(), due));
        }
    }

    private void advanceStatuses(LocalDate today) {
        List<PaymentObligation> pending = obligations.findByStatusInAndDueDateLessThanEqual(
                List.of(Status.UPCOMING, Status.DUE), today);
        for (PaymentObligation o : pending) {
            Status target = o.getDueDate().isBefore(today) ? Status.OVERDUE : Status.DUE;
            if (o.getStatus() == target) continue;
            o.setStatus(target);
            RecurringRule rule = rules.findById(o.getRecurringRuleId()).orElse(null);
            if (rule == null) continue;
            String verb = target == Status.OVERDUE ? "OVERDUE" : "due today";
            String msg = "%s: %s (%s %s)".formatted(verb, o.getDescription(), o.getCurrency(), o.getAmount());
            String type = target == Status.OVERDUE ? "PAYMENT_OVERDUE" : "PAYMENT_DUE";
            rule.getMemberUserIds().forEach(u -> notifications.notify(u, type, msg, "OBLIGATION", o.getId()));
        }
    }

    /** Rules where userId is either the creator or a listed party — a recurring rule is a
     *  1:1 IOU between two people, so the counterparty needs to see it too, not just whoever
     *  created it. Deduped in case someone is both (shouldn't happen, but be defensive).
     *  Active only — deleted rules disappear from "your rules". */
    @Transactional(readOnly = true)
    public List<RecurringRule> rulesOf(long userId) {
        return mergedRulesOf(userId).stream().filter(RecurringRule::isActive).toList();
    }

    /** Obligation history intentionally ignores the active flag — deleting a rule stops
     *  new bills from being generated, but shouldn't erase past due/settled records or
     *  their linked Settlements. */
    @Transactional(readOnly = true)
    public List<PaymentObligation> obligationsFor(long userId) {
        List<Long> ruleIds = mergedRulesOf(userId).stream().map(RecurringRule::getId).toList();
        return ruleIds.isEmpty() ? List.of() : obligations.findByRecurringRuleIdInOrderByDueDateAsc(ruleIds);
    }

    private List<RecurringRule> mergedRulesOf(long userId) {
        Map<Long, RecurringRule> byId = new LinkedHashMap<>();
        rules.findByCreatedBy(userId).forEach(r -> byId.put(r.getId(), r));
        rules.findByMemberUserIdsContaining(userId).forEach(r -> byId.put(r.getId(), r));
        return List.copyOf(byId.values());
    }

    @Transactional
    public void settle(long obligationId, Long settlementId) {
        obligations.findById(obligationId).ifPresent(o -> {
            o.setStatus(Status.SETTLED);
            if (settlementId != null) o.setSettlementId(settlementId);
        });
    }

    @Transactional
    public RecurringRule updateRule(long ruleId, long userId, String description, String category,
                                    BigDecimal amount, String currency, Cadence cadence, Integer intervalDays,
                                    LocalDate nextDueDate, List<Long> memberUserIds) {
        RecurringRule rule = rules.findById(ruleId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Rule not found"));
        if (!rule.getCreatedBy().equals(userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only the creator can edit this rule");
        }
        rule.applyUpdate(description, category, amount, currency, cadence, intervalDays, nextDueDate, memberUserIds);
        return rule;
    }

    @Transactional
    public void deactivateRule(long ruleId, long userId) {
        RecurringRule rule = rules.findById(ruleId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Rule not found"));
        if (!rule.getCreatedBy().equals(userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only the creator can delete this rule");
        }
        rule.setActive(false);
    }
}

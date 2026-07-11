package com.squarely.notification.api;

import com.squarely.notification.domain.PaymentObligation;
import com.squarely.notification.domain.PaymentObligation.Status;
import com.squarely.notification.domain.RecurringRule;
import com.squarely.notification.domain.RecurringRule.Cadence;
import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public final class Dtos {
    private Dtos() {}

    public record CreateRecurringRuleRequest(
            Long groupId,
            @NotBlank String description,
            @NotBlank String category,
            @NotNull @Positive BigDecimal amount,
            @NotBlank @Size(min = 3, max = 3) String currency,
            @NotNull Cadence cadence,
            Integer intervalDays,                 // required only for CUSTOM
            @NotNull @FutureOrPresent LocalDate firstDueDate,
            @NotEmpty List<Long> memberUserIds) {}

    /** No @FutureOrPresent on nextDueDate here — editing an already-overdue rule
     *  shouldn't be blocked just because its current due date is in the past. */
    public record UpdateRecurringRuleRequest(
            @NotBlank String description,
            @NotBlank String category,
            @NotNull @Positive BigDecimal amount,
            @NotBlank @Size(min = 3, max = 3) String currency,
            @NotNull Cadence cadence,
            Integer intervalDays,
            @NotNull LocalDate nextDueDate,
            @NotEmpty List<Long> memberUserIds) {}

    public record RuleView(long id, Long groupId, Long createdBy, String description, String category,
                           BigDecimal amount, String currency, Cadence cadence, Integer intervalDays,
                           LocalDate nextDueDate, List<Long> memberUserIds, boolean active) {

        public static RuleView of(RecurringRule r) {
            return new RuleView(r.getId(), r.getGroupId(), r.getCreatedBy(), r.getDescription(), r.getCategory(),
                    r.getAmount(), r.getCurrency(), r.getCadence(), r.getIntervalDays(),
                    r.getNextDueDate(), r.getMemberUserIds(), r.isActive());
        }
    }

    public record ObligationView(long id, long recurringRuleId, LocalDate dueDate, BigDecimal amount,
                                 String currency, String description, Status status, Long settlementId) {

        public static ObligationView of(PaymentObligation o) {
            return new ObligationView(o.getId(), o.getRecurringRuleId(), o.getDueDate(), o.getAmount(),
                    o.getCurrency(), o.getDescription(), o.getStatus(), o.getSettlementId());
        }
    }

    /** Body for POST /obligations/{id}/settle — settlementId is set once the frontend
     *  has created the actual ledger-service Settlement for this obligation. */
    public record SettleObligationRequest(Long settlementId) {}
}

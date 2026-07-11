package com.squarely.notification.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/** A concrete instance generated from a RecurringRule, tracked UPCOMING→DUE→OVERDUE→SETTLED. */
@Entity
@Table(name = "payment_obligations",
       uniqueConstraints = @UniqueConstraint(name = "uq_obligation_rule_due",
               columnNames = {"recurring_rule_id", "due_date"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PaymentObligation {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "recurring_rule_id", nullable = false)
    private Long recurringRuleId;

    @Column(name = "due_date", nullable = false)
    private LocalDate dueDate;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(nullable = false)
    private String description;

    @Enumerated(EnumType.STRING)
    @Setter
    @Column(nullable = false)
    private Status status = Status.UPCOMING;

    /** The ledger-service Settlement created when this obligation was settled, if any.
     *  Cross-service reference — no FK, just an id the frontend can resolve. */
    @Setter
    @Column(name = "settlement_id")
    private Long settlementId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    public enum Status { UPCOMING, DUE, OVERDUE, SETTLED }

    public PaymentObligation(Long recurringRuleId, LocalDate dueDate, BigDecimal amount,
                             String currency, String description) {
        this.recurringRuleId = recurringRuleId;
        this.dueDate = dueDate;
        this.amount = amount;
        this.currency = currency;
        this.description = description;
    }
}

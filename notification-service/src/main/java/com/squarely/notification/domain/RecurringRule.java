package com.squarely.notification.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** A recurring obligation like rent or maintenance. The scheduler generates the next
 *  {@link PaymentObligation} from this and advances {@code nextDueDate}. */
@Entity
@Table(name = "recurring_rules")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RecurringRule {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "group_id")
    private Long groupId;

    @Column(name = "created_by", nullable = false)
    private Long createdBy;

    @Column(nullable = false)
    private String description;

    @Column(nullable = false)
    private String category;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Cadence cadence;

    /** Only used when cadence = CUSTOM. */
    @Column(name = "interval_days")
    private Integer intervalDays;

    @Setter
    @Column(name = "next_due_date", nullable = false)
    private LocalDate nextDueDate;

    /** Who shares this recurring cost. */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "recurring_rule_members", joinColumns = @JoinColumn(name = "rule_id"))
    @Column(name = "user_id")
    private List<Long> memberUserIds;

    @Setter
    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    public enum Cadence { WEEKLY, MONTHLY, CUSTOM }

    public RecurringRule(Long groupId, Long createdBy, String description, String category,
                         BigDecimal amount, String currency, Cadence cadence, Integer intervalDays,
                         LocalDate firstDueDate, List<Long> memberUserIds) {
        this.groupId = groupId;
        this.createdBy = createdBy;
        this.description = description;
        this.category = category;
        this.amount = amount;
        this.currency = currency;
        this.cadence = cadence;
        this.intervalDays = intervalDays;
        this.nextDueDate = firstDueDate;
        this.memberUserIds = memberUserIds;
    }

    /** Edits everything except id/groupId/createdBy/active — those are identity/ownership,
     *  not editable fields. Obligations already generated keep their original amounts;
     *  this only affects what the next tick generates. */
    public void applyUpdate(String description, String category, BigDecimal amount, String currency,
                            Cadence cadence, Integer intervalDays, LocalDate nextDueDate, List<Long> memberUserIds) {
        this.description = description;
        this.category = category;
        this.amount = amount;
        this.currency = currency;
        this.cadence = cadence;
        this.intervalDays = intervalDays;
        this.nextDueDate = nextDueDate;
        this.memberUserIds = memberUserIds;
    }
}

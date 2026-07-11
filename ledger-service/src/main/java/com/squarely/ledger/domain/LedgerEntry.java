package com.squarely.ledger.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * One immutable financial event: "debtor owes creditor `amount`". Balances are
 * derived by summing these; rows are never updated or deleted. A correction is a
 * REVERSAL row that points at the entry it cancels — history stays auditable.
 */
@Entity
@Table(name = "ledger_entries",
       uniqueConstraints = @UniqueConstraint(
               name = "uq_ledger_ref",
               columnNames = {"ref_type", "ref_id", "debtor_id", "creditor_id"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LedgerEntry {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "group_id")
    private Long groupId;               // null = personal (non-group) debt

    @Column(name = "debtor_id", nullable = false)
    private Long debtorId;              // owes

    @Column(name = "creditor_id", nullable = false)
    private Long creditorId;            // is owed

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;          // always positive

    @Column(nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private EntryType type;

    /** What caused this entry, for idempotency + audit. */
    @Column(name = "ref_type", nullable = false)
    private String refType;             // EXPENSE | SETTLEMENT | MANUAL | REVERSAL

    @Column(name = "ref_id")
    private Long refId;                 // expenseId / settlementId / reversed-entry id

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    public enum EntryType { EXPENSE, SETTLEMENT, PERSONAL_DEBT, REVERSAL }

    public LedgerEntry(Long groupId, Long debtorId, Long creditorId, BigDecimal amount,
                       String currency, EntryType type, String refType, Long refId) {
        this.groupId = groupId;
        this.debtorId = debtorId;
        this.creditorId = creditorId;
        this.amount = amount;
        this.currency = currency;
        this.type = type;
        this.refType = refType;
        this.refId = refId;
    }
}

package com.squarely.ledger.domain;

import com.squarely.ledger.settlement.SettlementStatus;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * A payment workflow from payer (fromUser) to receiver (toUser). Status is driven by
 * {@link com.squarely.ledger.settlement.SettlementStateMachine}. @Version guards
 * against two concurrent transitions (e.g. double-acknowledge).
 */
@Entity
@Table(name = "settlements",
       uniqueConstraints = @UniqueConstraint(name = "uq_settlement_idem", columnNames = "idempotency_key"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Settlement {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "group_id")
    private Long groupId;               // null = personal debt settlement

    @Column(name = "from_user_id", nullable = false)
    private Long fromUserId;            // payer

    @Column(name = "to_user_id", nullable = false)
    private Long toUserId;              // receiver

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Setter
    @Column(nullable = false)
    private SettlementStatus status = SettlementStatus.PENDING;

    /** Client-supplied key; unique so retries don't create duplicate settlements. */
    @Column(name = "idempotency_key", updatable = false)
    private String idempotencyKey;

    @Setter @Column(name = "utr")
    private String utr;                 // optional UPI reference

    @Setter @Column(name = "claimed_by_user_id")
    private Long claimedByUserId;
    @Setter @Column(name = "claimed_at")
    private Instant claimedAt;

    @Setter @Column(name = "acknowledged_by_user_id")
    private Long acknowledgedByUserId;
    @Setter @Column(name = "acknowledged_at")
    private Instant acknowledgedAt;

    @Setter @Column(name = "dispute_reason")
    private String disputeReason;
    @Setter @Column(name = "disputed_at")
    private Instant disputedAt;

    @Version
    private Long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    public Settlement(Long groupId, Long fromUserId, Long toUserId, BigDecimal amount,
                      String currency, String idempotencyKey) {
        this.groupId = groupId;
        this.fromUserId = fromUserId;
        this.toUserId = toUserId;
        this.amount = amount;
        this.currency = currency;
        this.idempotencyKey = idempotencyKey;
    }
}

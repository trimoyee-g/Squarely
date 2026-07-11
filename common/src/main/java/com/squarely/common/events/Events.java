package com.squarely.common.events;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Event payloads carried on Kafka. Nested records so all contracts live in one file.
 * JSON-serialized (see each service's KafkaConfig).
 */
public final class Events {
    private Events() {}

    /** Published by group-service when an expense is created. Ledger + notify consume it. */
    public record ExpenseAdded(
            long expenseId,
            long groupId,
            String description,
            String category,
            BigDecimal amount,
            String currency,
            long paidByUserId,
            Map<Long, BigDecimal> splits,   // userId -> owed amount (sums to amount)
            Instant createdAt) {}

    /** Published by ledger-service when a payer claims "I've paid". Notify consumes it. */
    public record PaymentClaimed(
            long settlementId,
            String idempotencyKey,
            long fromUserId,   // payer
            long toUserId,     // receiver who must acknowledge
            BigDecimal amount,
            String currency,
            String utr,        // optional UPI reference, may be null
            Instant claimedAt) {}

    /** Published when the receiver confirms receipt. */
    public record PaymentAcknowledged(
            long settlementId,
            long fromUserId,
            long toUserId,
            long acknowledgedByUserId,
            Instant acknowledgedAt) {}

    /** Published when the receiver rejects/disputes the claim. */
    public record PaymentDisputed(
            long settlementId,
            long fromUserId,
            long toUserId,
            long disputedByUserId,
            String reason,
            Instant disputedAt) {}

    /** Published by notification-service scheduler when a recurring obligation comes due. */
    public record PaymentDue(
            long obligationId,
            long recurringRuleId,
            List<Long> memberUserIds,
            BigDecimal amount,
            String currency,
            String description,
            LocalDate dueDate) {}
}

package com.squarely.ledger.api;

import com.squarely.ledger.domain.LedgerEntry.EntryType;
import com.squarely.ledger.settlement.SettlementStatus;
import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class Dtos {
    private Dtos() {}

    // --- balances ---
    public record PairDebt(long fromUserId, long toUserId, BigDecimal amount) {}

    public record GroupBalances(long groupId, Map<Long, BigDecimal> net,
                                List<PairDebt> owes, List<PairDebt> simplified) {}

    public record UserBalances(long userId, BigDecimal totalOwed, BigDecimal totalReceivable,
                               List<PairDebt> breakdown) {}

    // --- personal debt ---
    public record CreatePersonalDebtRequest(
            @NotNull Long debtorId, @NotNull Long creditorId,
            @NotNull @Positive BigDecimal amount,
            @NotBlank @Size(min = 3, max = 3) String currency) {}

    // --- settlements ---
    public record CreateSettlementRequest(
            Long groupId,
            @NotNull Long fromUserId,
            @NotNull Long toUserId,
            @NotNull @Positive BigDecimal amount,
            @NotBlank @Size(min = 3, max = 3) String currency) {}

    public record ClaimRequest(String utr) {}
    public record DisputeRequest(@NotBlank String reason) {}

    public record SettlementView(
            long id, Long groupId, long fromUserId, long toUserId, BigDecimal amount,
            String currency, SettlementStatus status, String utr,
            Long claimedByUserId, Instant claimedAt,
            Long acknowledgedByUserId, Instant acknowledgedAt,
            String disputeReason, Instant disputedAt, Instant createdAt) {}

    // --- ledger / audit ---
    public record LedgerEntryView(
            long id, Long groupId, long debtorId, long creditorId, BigDecimal amount,
            String currency, EntryType type, String refType, Long refId, Instant createdAt) {}
}

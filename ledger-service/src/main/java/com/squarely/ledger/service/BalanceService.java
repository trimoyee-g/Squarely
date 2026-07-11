package com.squarely.ledger.service;

import com.squarely.ledger.api.Dtos.*;
import com.squarely.ledger.debt.DebtSimplifier;
import com.squarely.ledger.domain.LedgerEntry;
import com.squarely.ledger.repo.Repos.LedgerRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.*;

/**
 * Derives balances by summing the append-only ledger. Never stores a running balance.
 * ponytail: aggregates in Java over the fetched rows — fine for realistic group sizes.
 * Move to a SQL SUM ... GROUP BY (or a materialized view) only if a ledger grows large
 * enough to matter.
 */
@Service
public class BalanceService {

    private final LedgerRepository ledger;

    public BalanceService(LedgerRepository ledger) {
        this.ledger = ledger;
    }

    @Transactional(readOnly = true)
    public GroupBalances forGroup(long groupId) {
        List<LedgerEntry> entries = ledger.findByGroupId(groupId);
        Map<Long, BigDecimal> net = netByUser(entries);
        List<PairDebt> owes = pairwise(entries);
        List<PairDebt> simplified = DebtSimplifier.simplify(net).stream()
                .map(t -> new PairDebt(t.fromUserId(), t.toUserId(), t.amount()))
                .toList();
        return new GroupBalances(groupId, net, owes, simplified);
    }

    @Transactional(readOnly = true)
    public UserBalances forUser(long userId) {
        List<LedgerEntry> entries = ledger.findByDebtorIdOrCreditorId(userId, userId);
        // Net per counterparty from this user's perspective.
        Map<Long, BigDecimal> perCounterparty = new HashMap<>();
        for (LedgerEntry e : entries) {
            if (e.getCreditorId().equals(userId)) {                  // they owe me
                perCounterparty.merge(e.getDebtorId(), e.getAmount(), BigDecimal::add);
            } else if (e.getDebtorId().equals(userId)) {             // I owe them
                perCounterparty.merge(e.getCreditorId(), e.getAmount().negate(), BigDecimal::add);
            }
        }
        List<PairDebt> breakdown = new ArrayList<>();
        BigDecimal owed = BigDecimal.ZERO, receivable = BigDecimal.ZERO;
        for (var e : perCounterparty.entrySet()) {
            int sign = e.getValue().signum();
            if (sign == 0) continue;
            if (sign > 0) {                                           // counterparty owes me
                receivable = receivable.add(e.getValue());
                breakdown.add(new PairDebt(e.getKey(), userId, e.getValue()));
            } else {                                                  // I owe counterparty
                owed = owed.add(e.getValue().negate());
                breakdown.add(new PairDebt(userId, e.getKey(), e.getValue().negate()));
            }
        }
        return new UserBalances(userId, owed, receivable, breakdown);
    }

    /** Net position per user: positive = is owed (creditor), negative = owes (debtor). */
    private Map<Long, BigDecimal> netByUser(List<LedgerEntry> entries) {
        Map<Long, BigDecimal> net = new LinkedHashMap<>();
        for (LedgerEntry e : entries) {
            net.merge(e.getCreditorId(), e.getAmount(), BigDecimal::add);
            net.merge(e.getDebtorId(), e.getAmount().negate(), BigDecimal::add);
        }
        net.values().removeIf(v -> v.signum() == 0);
        return net;
    }

    /** Raw pairwise "A owes B" list (before simplification), one row per owing pair. */
    private List<PairDebt> pairwise(List<LedgerEntry> entries) {
        // directed[a][b] = how much a owes b, netted per unordered pair.
        Map<Long, Map<Long, BigDecimal>> directed = new HashMap<>();
        for (LedgerEntry e : entries) {
            directed.computeIfAbsent(e.getDebtorId(), k -> new HashMap<>())
                    .merge(e.getCreditorId(), e.getAmount(), BigDecimal::add);
        }
        List<PairDebt> result = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        for (Long a : directed.keySet()) {
            for (Long b : directed.get(a).keySet()) {
                long pairKey = Math.min(a, b) * 1_000_000L + Math.max(a, b);
                if (!seen.add(pairKey)) continue;
                BigDecimal aToB = directed.getOrDefault(a, Map.of()).getOrDefault(b, BigDecimal.ZERO);
                BigDecimal bToA = directed.getOrDefault(b, Map.of()).getOrDefault(a, BigDecimal.ZERO);
                BigDecimal net = aToB.subtract(bToA);
                if (net.signum() > 0) result.add(new PairDebt(a, b, net));
                else if (net.signum() < 0) result.add(new PairDebt(b, a, net.negate()));
            }
        }
        return result;
    }
}

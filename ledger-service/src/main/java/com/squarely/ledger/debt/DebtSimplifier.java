package com.squarely.ledger.debt;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reduces a web of who-owes-whom to the fewest transactions that settle everyone.
 *
 * Greedy min-cash-flow: repeatedly make the biggest debtor pay the biggest creditor.
 * ponytail: exact minimum-transaction settlement is NP-hard; this greedy is what
 * Splitwise-style apps use and is near-optimal. Don't reach for an ILP solver unless
 * a real complaint about transaction count shows up.
 */
public final class DebtSimplifier {
    private DebtSimplifier() {}

    public record Transaction(long fromUserId, long toUserId, BigDecimal amount) {}

    /**
     * @param netBalances userId -> net position (positive = is owed / creditor,
     *                    negative = owes / debtor). Must sum to zero.
     * @return minimal-ish list of debtor→creditor transfers.
     */
    public static List<Transaction> simplify(Map<Long, BigDecimal> netBalances) {
        // Work in integer cents so repeated subtraction never drifts.
        Map<Long, Long> cents = new LinkedHashMap<>();
        long check = 0;
        for (var e : netBalances.entrySet()) {
            long c = e.getValue().movePointRight(2).longValueExact();
            if (c != 0) cents.put(e.getKey(), c);
            check += c;
        }
        if (check != 0) {
            throw new IllegalArgumentException("Net balances must sum to zero, got " + check + " cents");
        }

        List<Transaction> result = new ArrayList<>();
        while (!cents.isEmpty()) {
            Long creditor = null, debtor = null;
            long maxCredit = 0, maxDebt = 0;
            for (var e : cents.entrySet()) {
                if (e.getValue() > maxCredit) { maxCredit = e.getValue(); creditor = e.getKey(); }
                if (e.getValue() < maxDebt) { maxDebt = e.getValue(); debtor = e.getKey(); }
            }
            if (creditor == null || debtor == null) break; // only zeros left

            long transfer = Math.min(maxCredit, -maxDebt);
            result.add(new Transaction(debtor, creditor, BigDecimal.valueOf(transfer, 2)));

            adjust(cents, creditor, -transfer);
            adjust(cents, debtor, transfer);
        }
        return result;
    }

    private static void adjust(Map<Long, Long> cents, Long user, long delta) {
        long v = cents.get(user) + delta;
        if (v == 0) cents.remove(user); else cents.put(user, v);
    }
}

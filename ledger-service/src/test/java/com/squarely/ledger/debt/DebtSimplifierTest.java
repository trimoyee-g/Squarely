package com.squarely.ledger.debt;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DebtSimplifierTest {

    private static Map<Long, BigDecimal> net(Object... kv) {
        Map<Long, BigDecimal> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put(((Number) kv[i]).longValue(), new BigDecimal(kv[i + 1].toString()));
        return m;
    }

    /** Every transaction must conserve money: applying them zeroes all balances. */
    private static void assertSettles(Map<Long, BigDecimal> balances, List<DebtSimplifier.Transaction> txns) {
        Map<Long, BigDecimal> b = new LinkedHashMap<>(balances);
        for (var t : txns) {
            assertTrue(t.amount().signum() > 0, "transfer must be positive");
            b.merge(t.fromUserId(), t.amount(), BigDecimal::add);   // debtor's negative moves toward 0
            b.merge(t.toUserId(), t.amount().negate(), BigDecimal::add);
        }
        b.values().forEach(v -> assertEquals(0, v.signum(), "all balances settle to zero"));
    }

    @Test
    void chainCollapsesToOneTransfer() {
        // A owes B 10, B owes C 10  ->  A pays C 10 (one transfer, not two)
        var balances = net(1, "-10.00", 2, "0.00", 3, "10.00");
        var txns = DebtSimplifier.simplify(balances);
        assertSettles(balances, txns);
        assertEquals(1, txns.size());
        assertEquals(1, txns.get(0).fromUserId());
        assertEquals(3, txns.get(0).toUserId());
    }

    @Test
    void oneCreditorManyDebtors() {
        // A paid for everyone: B,C,D each owe A 30
        var balances = net(1, "90.00", 2, "-30.00", 3, "-30.00", 4, "-30.00");
        var txns = DebtSimplifier.simplify(balances);
        assertSettles(balances, txns);
        assertEquals(3, txns.size()); // can't do better than 3 here
    }

    @Test
    void alreadySettledYieldsNothing() {
        assertTrue(DebtSimplifier.simplify(net(1, "0.00", 2, "0.00")).isEmpty());
    }

    @Test
    void unbalancedInputRejected() {
        assertThrows(IllegalArgumentException.class, () -> DebtSimplifier.simplify(net(1, "10.00", 2, "-5.00")));
    }

    @Test
    void tangledGraphSettlesAndReducesCount() {
        // 4-way tangle that naively needs many transfers
        var balances = net(1, "-5.00", 2, "-15.00", 3, "12.00", 4, "8.00");
        var txns = DebtSimplifier.simplify(balances);
        assertSettles(balances, txns);
        assertTrue(txns.size() <= 3);
    }
}

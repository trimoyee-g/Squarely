package com.squarely.group.split;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SplitCalculatorTest {

    private static BigDecimal bd(String s) { return new BigDecimal(s); }

    private static Map<Long, BigDecimal> map(Object... kv) {
        Map<Long, BigDecimal> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put(((Number) kv[i]).longValue(), bd(kv[i + 1].toString()));
        return m;
    }

    private static BigDecimal sum(Map<Long, BigDecimal> m) {
        return m.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    @Test
    void equalSplitDistributesRemainderCents() {
        // 100.00 / 3 = 33.34, 33.33, 33.33 — must sum back to 100.00
        var r = SplitCalculator.compute(bd("100.00"), SplitType.EQUAL, map(1, 0, 2, 0, 3, 0));
        assertEquals(0, sum(r).compareTo(bd("100.00")));
        assertEquals(bd("33.34"), r.get(1L));
        assertEquals(bd("33.33"), r.get(2L));
    }

    @Test
    void exactSplitMustSumToTotal() {
        var r = SplitCalculator.compute(bd("50.00"), SplitType.EXACT, map(1, "20.00", 2, "30.00"));
        assertEquals(bd("20.00"), r.get(1L));
        assertThrows(IllegalArgumentException.class,
                () -> SplitCalculator.compute(bd("50.00"), SplitType.EXACT, map(1, "20.00", 2, "20.00")));
    }

    @Test
    void percentSplitRoundsToExactTotal() {
        // 33.33% of 100 each for 3 people -> parts must still sum to 100.00
        var r = SplitCalculator.compute(bd("100.00"), SplitType.PERCENT,
                map(1, "33.34", 2, "33.33", 3, "33.33"));
        assertEquals(0, sum(r).compareTo(bd("100.00")));
    }

    @Test
    void percentMustSumTo100() {
        assertThrows(IllegalArgumentException.class,
                () -> SplitCalculator.compute(bd("100.00"), SplitType.PERCENT, map(1, "50", 2, "40")));
    }

    @Test
    void sharesSplitAllocatesProportionally() {
        // shares 1:3 of 100.00 -> 25.00 / 75.00
        var r = SplitCalculator.compute(bd("100.00"), SplitType.SHARES, map(1, "1", 2, "3"));
        assertEquals(bd("25.00"), r.get(1L));
        assertEquals(bd("75.00"), r.get(2L));
        assertEquals(0, sum(r).compareTo(bd("100.00")));
    }

    @Test
    void sharesWithAwkwardRemainderStillSumsExactly() {
        // 10.00 across shares 1:1:1 -> 3.34/3.33/3.33
        var r = SplitCalculator.compute(bd("10.00"), SplitType.SHARES, map(1, "1", 2, "1", 3, "1"));
        assertEquals(0, sum(r).compareTo(bd("10.00")));
    }

    @Test
    void rejectsNonPositiveAmount() {
        assertThrows(IllegalArgumentException.class,
                () -> SplitCalculator.compute(bd("0.00"), SplitType.EQUAL, map(1, 0)));
    }
}

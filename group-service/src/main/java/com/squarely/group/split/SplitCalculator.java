package com.squarely.group.split;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

/**
 * Turns a split spec into exact per-user owed amounts. All arithmetic is done in
 * integer minor units (cents/paise) so the parts always sum to the total with no
 * rounding drift. Uses the largest-remainder method to spread leftover cents fairly.
 *
 * ponytail: fixed 2 decimal places (paise/cents). Parameterize by currency only if
 * you ever support 0-dp (JPY) or 3-dp currencies.
 */
public final class SplitCalculator {
    private SplitCalculator() {}

    /**
     * @param amount total expense amount (2dp)
     * @param type   how to split
     * @param inputs userId -> value. Meaning depends on type:
     *               EQUAL: keys are participants, values ignored;
     *               EXACT: values are exact owed amounts;
     *               PERCENT: values are percentages (must sum to 100);
     *               SHARES: values are integer-ish weights.
     * @return userId -> owed amount (2dp), summing exactly to amount.
     */
    public static Map<Long, BigDecimal> compute(BigDecimal amount, SplitType type, Map<Long, BigDecimal> inputs) {
        if (inputs == null || inputs.isEmpty()) {
            throw new IllegalArgumentException("At least one participant is required");
        }
        long totalCents = toCents(amount);
        if (totalCents <= 0) {
            throw new IllegalArgumentException("Amount must be positive");
        }

        return switch (type) {
            case EXACT -> exact(totalCents, inputs);
            case EQUAL -> allocateByWeights(totalCents, equalWeights(inputs.keySet()));
            case PERCENT -> {
                requireSum(inputs.values(), new BigDecimal("100"), "Percentages must sum to 100");
                yield allocateByWeights(totalCents, inputs);
            }
            case SHARES -> allocateByWeights(totalCents, inputs);
        };
    }

    private static Map<Long, BigDecimal> exact(long totalCents, Map<Long, BigDecimal> inputs) {
        Map<Long, BigDecimal> result = new LinkedHashMap<>();
        long sum = 0;
        for (var e : inputs.entrySet()) {
            long c = toCents(e.getValue());
            if (c < 0) throw new IllegalArgumentException("Exact amounts cannot be negative");
            result.put(e.getKey(), fromCents(c));
            sum += c;
        }
        if (sum != totalCents) {
            throw new IllegalArgumentException("Exact amounts must sum to the total");
        }
        return result;
    }

    /** Largest-remainder allocation of totalCents proportional to positive weights. */
    private static Map<Long, BigDecimal> allocateByWeights(long totalCents, Map<Long, BigDecimal> weights) {
        BigDecimal totalWeight = weights.values().stream()
                .peek(w -> { if (w.signum() < 0) throw new IllegalArgumentException("Weights cannot be negative"); })
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (totalWeight.signum() <= 0) {
            throw new IllegalArgumentException("Weights must sum to a positive value");
        }

        BigDecimal total = BigDecimal.valueOf(totalCents);
        Map<Long, Long> floors = new LinkedHashMap<>();
        List<Map.Entry<Long, BigDecimal>> byRemainder = new ArrayList<>();
        long allocated = 0;
        for (var e : weights.entrySet()) {
            BigDecimal raw = total.multiply(e.getValue())
                    .divide(totalWeight, 10, RoundingMode.HALF_UP);
            long floor = raw.setScale(0, RoundingMode.FLOOR).longValueExact();
            floors.put(e.getKey(), floor);
            allocated += floor;
            byRemainder.add(Map.entry(e.getKey(), raw.subtract(BigDecimal.valueOf(floor))));
        }

        // Hand out remaining cents to the largest fractional remainders (stable, fair).
        long leftover = totalCents - allocated;
        byRemainder.sort(Comparator.comparing(Map.Entry<Long, BigDecimal>::getValue).reversed());
        for (int i = 0; i < leftover; i++) {
            Long userId = byRemainder.get(i).getKey();
            floors.merge(userId, 1L, Long::sum);
        }

        Map<Long, BigDecimal> result = new LinkedHashMap<>();
        weights.keySet().forEach(id -> result.put(id, fromCents(floors.get(id))));
        return result;
    }

    private static Map<Long, BigDecimal> equalWeights(Set<Long> userIds) {
        Map<Long, BigDecimal> w = new LinkedHashMap<>();
        userIds.forEach(id -> w.put(id, BigDecimal.ONE));
        return w;
    }

    private static void requireSum(Collection<BigDecimal> values, BigDecimal expected, String message) {
        BigDecimal sum = values.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        if (sum.compareTo(expected) != 0) {
            throw new IllegalArgumentException(message + " (got " + sum + ")");
        }
    }

    private static long toCents(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.HALF_UP).movePointRight(2).longValueExact();
    }

    private static BigDecimal fromCents(long cents) {
        return BigDecimal.valueOf(cents, 2);
    }
}

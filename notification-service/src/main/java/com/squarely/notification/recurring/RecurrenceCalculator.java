package com.squarely.notification.recurring;

import com.squarely.notification.domain.RecurringRule.Cadence;

import java.time.LocalDate;

/** Computes the next due date for a cadence. Pure so it's trivially testable. */
public final class RecurrenceCalculator {
    private RecurrenceCalculator() {}

    public static LocalDate next(Cadence cadence, Integer intervalDays, LocalDate from) {
        return switch (cadence) {
            case WEEKLY -> from.plusWeeks(1);
            case MONTHLY -> from.plusMonths(1);
            case CUSTOM -> {
                if (intervalDays == null || intervalDays <= 0) {
                    throw new IllegalArgumentException("CUSTOM cadence needs a positive intervalDays");
                }
                yield from.plusDays(intervalDays);
            }
        };
    }
}

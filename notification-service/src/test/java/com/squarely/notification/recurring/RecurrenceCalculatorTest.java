package com.squarely.notification.recurring;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static com.squarely.notification.domain.RecurringRule.Cadence.*;
import static org.junit.jupiter.api.Assertions.*;

class RecurrenceCalculatorTest {

    @Test
    void weeklyAndMonthlyAdvance() {
        assertEquals(LocalDate.of(2026, 1, 8), RecurrenceCalculator.next(WEEKLY, null, LocalDate.of(2026, 1, 1)));
        assertEquals(LocalDate.of(2026, 2, 1), RecurrenceCalculator.next(MONTHLY, null, LocalDate.of(2026, 1, 1)));
    }

    @Test
    void monthlyHandlesEndOfMonth() {
        // Jan 31 + 1 month -> Feb 28 (LocalDate clamps), never an invalid date
        assertEquals(LocalDate.of(2026, 2, 28), RecurrenceCalculator.next(MONTHLY, null, LocalDate.of(2026, 1, 31)));
    }

    @Test
    void customUsesIntervalDays() {
        assertEquals(LocalDate.of(2026, 1, 11), RecurrenceCalculator.next(CUSTOM, 10, LocalDate.of(2026, 1, 1)));
    }

    @Test
    void customRejectsBadInterval() {
        assertThrows(IllegalArgumentException.class, () -> RecurrenceCalculator.next(CUSTOM, 0, LocalDate.now()));
        assertThrows(IllegalArgumentException.class, () -> RecurrenceCalculator.next(CUSTOM, -1, LocalDate.now()));
        // intervalDays is only required for CUSTOM, so it arrives null from any other cadence's form
        assertThrows(IllegalArgumentException.class, () -> RecurrenceCalculator.next(CUSTOM, null, LocalDate.now()));
    }
}

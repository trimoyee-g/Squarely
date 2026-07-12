package com.squarely.ledger.service;

import com.squarely.common.events.Events;
import com.squarely.ledger.api.Dtos.*;
import com.squarely.ledger.domain.LedgerEntry;
import com.squarely.ledger.domain.LedgerEntry.EntryType;
import com.squarely.ledger.repo.Repos.LedgerRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class LedgerServiceTest {

    @Mock LedgerRepository ledger;
    LedgerService service;

    @BeforeEach
    void setUp() { service = new LedgerService(ledger); }

    private static LedgerEntry withId(long id, LedgerEntry e) {
        ReflectionTestUtils.setField(e, "id", id);
        return e;
    }

    @Test
    void recordExpenseSkipsPayerOwnShareAndZero() {
        // payer=1 owes nothing; 2 owes 40; 3's share is zero -> only one insert
        var e = new Events.ExpenseAdded(100L, 5L, "Lunch", "food", new BigDecimal("40.00"),
                "INR", 1L,
                Map.of(1L, new BigDecimal("0.00"), 2L, new BigDecimal("40.00"), 3L, BigDecimal.ZERO),
                Instant.now());

        service.recordExpense(e);

        verify(ledger, times(1)).save(any());
        verify(ledger).save(argThat(le -> le.getDebtorId() == 2L && le.getCreditorId() == 1L));
    }

    @Test
    void recordExpenseSkipsSplitsAlreadyRecorded() {
        // Redelivery: the split already exists -> check-first skips it, no INSERT attempted.
        when(ledger.existsByRefTypeAndRefIdAndDebtorIdAndCreditorId("EXPENSE", 100L, 2L, 1L))
                .thenReturn(true);
        var e = new Events.ExpenseAdded(100L, 5L, "Lunch", "food", new BigDecimal("40.00"),
                "INR", 1L, Map.of(2L, new BigDecimal("40.00")), Instant.now());

        service.recordExpense(e);
        verify(ledger, never()).save(any());
    }

    @Test
    void createPersonalDebtRejectsSelfDebt() {
        var req = new CreatePersonalDebtRequest(1L, 1L, new BigDecimal("10.00"), "inr");
        var ex = assertThrows(ResponseStatusException.class, () -> service.createPersonalDebt(req));
        assertEquals(400, ex.getStatusCode().value());
    }

    @Test
    void createPersonalDebtNormalizesCurrencyAndSaves() {
        when(ledger.save(any())).thenAnswer(inv -> withId(9L, inv.getArgument(0)));
        var req = new CreatePersonalDebtRequest(2L, 1L, new BigDecimal("10.00"), "inr");

        LedgerEntryView v = service.createPersonalDebt(req);
        assertEquals(9L, v.id());
        assertEquals("INR", v.currency());
        assertEquals(EntryType.PERSONAL_DEBT, v.type());
    }

    @Test
    void reverseNotFoundThrows404() {
        when(ledger.findById(1L)).thenReturn(Optional.empty());
        var ex = assertThrows(ResponseStatusException.class, () -> service.reverse(1L));
        assertEquals(404, ex.getStatusCode().value());
    }

    @Test
    void reverseRejectsReversingAReversal() {
        var rev = new LedgerEntry(5L, 1L, 2L, new BigDecimal("10.00"), "INR",
                EntryType.REVERSAL, "REVERSAL", 3L);
        when(ledger.findById(1L)).thenReturn(Optional.of(withId(1L, rev)));
        var ex = assertThrows(ResponseStatusException.class, () -> service.reverse(1L));
        assertEquals(409, ex.getStatusCode().value());
    }

    @Test
    void reverseSwapsDebtorAndCreditor() {
        var orig = new LedgerEntry(5L, 2L, 1L, new BigDecimal("10.00"), "INR",
                EntryType.EXPENSE, "EXPENSE", 100L);
        when(ledger.findById(1L)).thenReturn(Optional.of(withId(1L, orig)));
        when(ledger.saveAndFlush(any())).thenAnswer(inv -> withId(2L, inv.getArgument(0)));

        LedgerEntryView v = service.reverse(1L);
        assertEquals(EntryType.REVERSAL, v.type());
        assertEquals(1L, v.debtorId());   // swapped: original creditor now owes
        assertEquals(2L, v.creditorId());
    }

    @Test
    void reverseAlreadyReversedMapsToConflict() {
        var orig = new LedgerEntry(5L, 2L, 1L, new BigDecimal("10.00"), "INR",
                EntryType.EXPENSE, "EXPENSE", 100L);
        when(ledger.findById(1L)).thenReturn(Optional.of(withId(1L, orig)));
        when(ledger.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("dup"));
        var ex = assertThrows(ResponseStatusException.class, () -> service.reverse(1L));
        assertEquals(409, ex.getStatusCode().value());
    }
}

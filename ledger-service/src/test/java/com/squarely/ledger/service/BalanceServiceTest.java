package com.squarely.ledger.service;

import com.squarely.ledger.api.Dtos.*;
import com.squarely.ledger.domain.LedgerEntry;
import com.squarely.ledger.domain.LedgerEntry.EntryType;
import com.squarely.ledger.repo.Repos.LedgerRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BalanceServiceTest {

    @Mock LedgerRepository ledger;
    BalanceService service;

    @BeforeEach
    void setUp() { service = new BalanceService(ledger); }

    private static LedgerEntry entry(Long groupId, long debtor, long creditor, String amount) {
        return new LedgerEntry(groupId, debtor, creditor, new BigDecimal(amount),
                "INR", EntryType.EXPENSE, "EXPENSE", 1L);
    }

    @Test
    void forGroupNetsCreditorsAndDebtors() {
        // 2 owes 1 30, 3 owes 1 30  ->  net: 1:+60, 2:-30, 3:-30
        when(ledger.findByGroupId(5L)).thenReturn(List.of(
                entry(5L, 2L, 1L, "30.00"), entry(5L, 3L, 1L, "30.00")));

        GroupBalances b = service.forGroup(5L);

        assertEquals(0, b.net().get(1L).compareTo(new BigDecimal("60.00")));
        assertEquals(0, b.net().get(2L).compareTo(new BigDecimal("-30.00")));
        assertEquals(2, b.owes().size());       // two raw pairwise debts
        assertEquals(2, b.simplified().size()); // both still flow to user 1
    }

    @Test
    void forGroupDropsFullyOffsettingPairs() {
        // 1 owes 2 ten, 2 owes 1 ten -> everyone nets to zero
        when(ledger.findByGroupId(5L)).thenReturn(List.of(
                entry(5L, 1L, 2L, "10.00"), entry(5L, 2L, 1L, "10.00")));

        GroupBalances b = service.forGroup(5L);
        assertTrue(b.net().isEmpty());
        assertTrue(b.owes().isEmpty());
        assertTrue(b.simplified().isEmpty());
    }

    @Test
    void forUserSeparatesOwedFromReceivable() {
        // From user 1's view: 2 and 3 each owe them 30 (1 is creditor)
        when(ledger.findByDebtorIdOrCreditorId(1L, 1L)).thenReturn(List.of(
                entry(null, 2L, 1L, "30.00"), entry(null, 3L, 1L, "30.00")));

        UserBalances b = service.forUser(1L);
        assertEquals(0, b.totalReceivable().compareTo(new BigDecimal("60.00")));
        assertEquals(0, b.totalOwed().compareTo(BigDecimal.ZERO));
        assertEquals(2, b.breakdown().size());
    }

    @Test
    void forUserNetsBothDirectionsPerCounterparty() {
        // I (1) owe 2 fifty; 2 owes me 20  ->  net I owe 2 thirty
        when(ledger.findByDebtorIdOrCreditorId(1L, 1L)).thenReturn(List.of(
                entry(null, 1L, 2L, "50.00"), entry(null, 2L, 1L, "20.00")));

        UserBalances b = service.forUser(1L);
        assertEquals(0, b.totalOwed().compareTo(new BigDecimal("30.00")));
        assertEquals(0, b.totalReceivable().compareTo(BigDecimal.ZERO));
        assertEquals(1, b.breakdown().size());
    }
}

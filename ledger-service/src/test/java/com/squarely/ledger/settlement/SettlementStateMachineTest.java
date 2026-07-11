package com.squarely.ledger.settlement;

import org.junit.jupiter.api.Test;

import static com.squarely.ledger.settlement.SettlementStateMachine.Action.*;
import static com.squarely.ledger.settlement.SettlementStatus.*;
import static org.junit.jupiter.api.Assertions.*;

class SettlementStateMachineTest {

    @Test
    void happyPath() {
        assertEquals(PAYMENT_CLAIMED, SettlementStateMachine.next(PENDING, CLAIM));
        assertEquals(SETTLED, SettlementStateMachine.next(PAYMENT_CLAIMED, ACKNOWLEDGE));
    }

    @Test
    void disputeThenReclaim() {
        assertEquals(DISPUTED, SettlementStateMachine.next(PAYMENT_CLAIMED, DISPUTE));
        assertEquals(PAYMENT_CLAIMED, SettlementStateMachine.next(DISPUTED, CLAIM));
    }

    @Test
    void cannotSettleAnAlreadySettledPayment() {
        assertThrows(SettlementStateMachine.InvalidStateTransitionException.class,
                () -> SettlementStateMachine.next(SETTLED, ACKNOWLEDGE));
    }

    @Test
    void cannotAcknowledgeBeforeClaim() {
        assertThrows(SettlementStateMachine.InvalidStateTransitionException.class,
                () -> SettlementStateMachine.next(PENDING, ACKNOWLEDGE));
    }

    @Test
    void cannotClaimAnAlreadyClaimedPayment() {
        assertThrows(SettlementStateMachine.InvalidStateTransitionException.class,
                () -> SettlementStateMachine.next(PAYMENT_CLAIMED, CLAIM));
    }
}

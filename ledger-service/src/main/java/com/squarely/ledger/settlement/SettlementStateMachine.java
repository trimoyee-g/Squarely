package com.squarely.ledger.settlement;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

import java.util.Map;
import java.util.Set;

import static com.squarely.ledger.settlement.SettlementStatus.*;

/**
 * The only source of truth for legal settlement transitions. Everything else must
 * route mutations through {@link #next} so an invalid transition (e.g. settling an
 * already-settled payment) is impossible, not just discouraged.
 */
public final class SettlementStateMachine {
    private SettlementStateMachine() {}

    public enum Action { CLAIM, ACKNOWLEDGE, DISPUTE }

    // current status -> action -> resulting status
    private static final Map<SettlementStatus, Map<Action, SettlementStatus>> TRANSITIONS = Map.of(
            PENDING,          Map.of(Action.CLAIM, PAYMENT_CLAIMED),
            PAYMENT_CLAIMED,  Map.of(Action.ACKNOWLEDGE, SETTLED, Action.DISPUTE, DISPUTED),
            DISPUTED,         Map.of(Action.CLAIM, PAYMENT_CLAIMED),   // payer re-claims after a dispute
            SETTLED,          Map.of()                                  // terminal
    );

    public static SettlementStatus next(SettlementStatus current, Action action) {
        SettlementStatus target = TRANSITIONS.getOrDefault(current, Map.of()).get(action);
        if (target == null) {
            throw new InvalidStateTransitionException(
                    "Cannot " + action + " a settlement in state " + current);
        }
        return target;
    }

    public static Set<Action> allowedActions(SettlementStatus current) {
        return TRANSITIONS.getOrDefault(current, Map.of()).keySet();
    }

    @ResponseStatus(HttpStatus.CONFLICT)
    public static class InvalidStateTransitionException extends RuntimeException {
        public InvalidStateTransitionException(String message) { super(message); }
    }
}

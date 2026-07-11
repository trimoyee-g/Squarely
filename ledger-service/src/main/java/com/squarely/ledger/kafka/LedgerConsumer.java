package com.squarely.ledger.kafka;

import com.squarely.common.events.Events;
import com.squarely.common.events.Topics;
import com.squarely.ledger.service.LedgerService;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/** Consumes expense events and records them into the ledger (idempotently). */
@Component
public class LedgerConsumer {

    private final LedgerService ledger;

    public LedgerConsumer(LedgerService ledger) {
        this.ledger = ledger;
    }

    @KafkaListener(topics = Topics.EXPENSE_ADDED, groupId = "ledger-service")
    public void onExpenseAdded(Events.ExpenseAdded event) {
        ledger.recordExpense(event);
    }
}

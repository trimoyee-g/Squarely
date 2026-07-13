package com.squarely.common.events;

import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Defers an action until the current transaction commits.
 *
 * <p>Publishing a Kafka event from inside {@code @Transactional} is a dual write: the producer
 * dispatches asynchronously, so the event can reach the broker and *then* the transaction rolls
 * back — ledger-service records an expense that does not exist in group-service. Sending after
 * commit removes that direction: no commit, no event.
 *
 * <p>ponytail: the other direction — commit succeeds, broker is down, event lost — is NOT fixed
 * by this. The send is still best-effort and a lost event means a permanently wrong balance,
 * logged and nothing more. The real fix is a transactional outbox (write the event to a table in
 * the same transaction, poll and publish it). Build that when a lost event is unacceptable, which
 * for a money app it eventually is.
 *
 * <p>Deliberately Kafka-free so it can live in common: auth-service and gateway-service scan this
 * package and have no Kafka on their classpath.
 */
public final class AfterCommit {

    private AfterCommit() {}

    /** Runs {@code action} after the current transaction commits, or immediately if there is none. */
    public static void run(Runnable action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            action.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                action.run();
            }
        });
    }
}

package com.squarely.common.events;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class AfterCommitTest {

    private final AtomicInteger sends = new AtomicInteger();

    @AfterEach
    void tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void runsImmediatelyWhenNoTransactionIsActive() {
        AfterCommit.run(sends::incrementAndGet);
        assertThat(sends).hasValue(1);
    }

    @Test
    void waitsForCommitWhenInATransaction() {
        TransactionSynchronizationManager.initSynchronization();
        AfterCommit.run(sends::incrementAndGet);

        assertThat(sends).as("must not publish before commit").hasValue(0);

        TransactionSynchronizationManager.getSynchronizations().forEach(s -> s.afterCommit());
        assertThat(sends).hasValue(1);
    }

    @Test
    void neverRunsWhenTheTransactionRollsBack() {
        TransactionSynchronizationManager.initSynchronization();
        AfterCommit.run(sends::incrementAndGet);

        // Rollback = the synchronization's afterCommit is simply never invoked.
        TransactionSynchronizationManager.clearSynchronization();
        assertThat(sends).hasValue(0);
    }
}

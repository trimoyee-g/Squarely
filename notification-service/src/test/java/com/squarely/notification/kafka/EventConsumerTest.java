package com.squarely.notification.kafka;

import com.squarely.common.events.Events;
import com.squarely.notification.service.NotificationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

/** Who gets told what, per event. The routing here is the whole point of the class. */
@ExtendWith(MockitoExtension.class)
class EventConsumerTest {

    @Mock NotificationService notifications;

    private EventConsumer consumer() { return new EventConsumer(notifications); }

    /** Everyone in the split hears about it, payer included — they're a key in the map. */
    @Test
    void expenseAddedNotifiesEveryParticipant() {
        var event = new Events.ExpenseAdded(77L, 5L, "Lunch", "food", new BigDecimal("10.00"), "INR",
                1L, Map.of(1L, new BigDecimal("5.00"), 2L, new BigDecimal("5.00")), Instant.now());

        consumer().onExpenseAdded(event);

        verify(notifications).notify(eq(1L), eq("EXPENSE_ADDED"), contains("Lunch"), eq("EXPENSE"), eq(77L));
        verify(notifications).notify(eq(2L), eq("EXPENSE_ADDED"), contains("Lunch"), eq("EXPENSE"), eq(77L));
        verifyNoMoreInteractions(notifications);
    }

    /** Only the receiver is asked to confirm — telling the payer to confirm their own payment is noise. */
    @Test
    void paymentClaimedNotifiesOnlyTheReceiver() {
        var event = new Events.PaymentClaimed(9L, "key-1", 1L, 2L,
                new BigDecimal("30.00"), "INR", "UTR123", Instant.now());

        consumer().onPaymentClaimed(event);

        verify(notifications).notify(eq(2L), eq("PAYMENT_CLAIMED"), contains("30.00"), eq("SETTLEMENT"), eq(9L));
        verifyNoMoreInteractions(notifications);
    }

    /** The payer is the one waiting on the answer, so acknowledge/dispute go back to them. */
    @Test
    void paymentAcknowledgedNotifiesThePayer() {
        var event = new Events.PaymentAcknowledged(9L, 1L, 2L, 2L, Instant.now());

        consumer().onPaymentAcknowledged(event);

        verify(notifications).notify(eq(1L), eq("PAYMENT_ACKNOWLEDGED"), contains("confirmed"),
                eq("SETTLEMENT"), eq(9L));
        verifyNoMoreInteractions(notifications);
    }

    @Test
    void paymentDisputedNotifiesThePayerWithTheReason() {
        var event = new Events.PaymentDisputed(9L, 1L, 2L, 2L, "wrong amount", Instant.now());

        consumer().onPaymentDisputed(event);

        verify(notifications).notify(eq(1L), eq("PAYMENT_DISPUTED"), contains("wrong amount"),
                eq("SETTLEMENT"), eq(9L));
        verifyNoMoreInteractions(notifications);
    }
}

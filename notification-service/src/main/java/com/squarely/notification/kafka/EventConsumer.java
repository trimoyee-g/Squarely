package com.squarely.notification.kafka;

import com.squarely.common.events.Events;
import com.squarely.common.events.Topics;
import com.squarely.notification.service.NotificationService;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/** Turns cross-service events into per-user notifications. */
@Component
public class EventConsumer {

    private final NotificationService notifications;

    public EventConsumer(NotificationService notifications) {
        this.notifications = notifications;
    }

    @KafkaListener(topics = Topics.EXPENSE_ADDED, groupId = "notification-service")
    public void onExpenseAdded(Events.ExpenseAdded e) {
        String msg = "New expense '%s': %s %s".formatted(e.description(), e.currency(), e.amount());
        // Everyone involved in the split, including the payer.
        e.splits().keySet().forEach(userId ->
                notifications.notify(userId, "EXPENSE_ADDED", msg, "EXPENSE", e.expenseId()));
    }

    @KafkaListener(topics = Topics.PAYMENT_CLAIMED, groupId = "notification-service")
    public void onPaymentClaimed(Events.PaymentClaimed e) {
        String msg = "Payment of %s %s claimed — please confirm you received it."
                .formatted(e.currency(), e.amount());
        notifications.notify(e.toUserId(), "PAYMENT_CLAIMED", msg, "SETTLEMENT", e.settlementId());
    }

    @KafkaListener(topics = Topics.PAYMENT_ACKNOWLEDGED, groupId = "notification-service")
    public void onPaymentAcknowledged(Events.PaymentAcknowledged e) {
        notifications.notify(e.fromUserId(), "PAYMENT_ACKNOWLEDGED",
                "Your payment was confirmed by the receiver.", "SETTLEMENT", e.settlementId());
    }

    @KafkaListener(topics = Topics.PAYMENT_DISPUTED, groupId = "notification-service")
    public void onPaymentDisputed(Events.PaymentDisputed e) {
        notifications.notify(e.fromUserId(), "PAYMENT_DISPUTED",
                "Your payment was disputed: " + e.reason(), "SETTLEMENT", e.settlementId());
    }
}

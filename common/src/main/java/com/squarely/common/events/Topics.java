package com.squarely.common.events;

/**
 * Kafka topic names shared across services.
 * ponytail: String constants, not an enum — @KafkaListener(topics = ...) needs a
 * compile-time constant. Revisit only if we ever need to switch/iterate over topics.
 */
public final class Topics {
    private Topics() {}

    public static final String EXPENSE_ADDED = "expense.added";
    public static final String PAYMENT_CLAIMED = "payment.claimed";
    public static final String PAYMENT_ACKNOWLEDGED = "payment.acknowledged";
    public static final String PAYMENT_DISPUTED = "payment.disputed";
    public static final String PAYMENT_DUE = "payment.due";
}

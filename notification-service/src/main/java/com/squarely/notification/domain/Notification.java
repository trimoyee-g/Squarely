package com.squarely.notification.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "notifications")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Notification {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false)
    private String type;        // EXPENSE_ADDED, PAYMENT_CLAIMED, PAYMENT_ACKNOWLEDGED, PAYMENT_DISPUTED, PAYMENT_DUE, PAYMENT_OVERDUE

    @Column(nullable = false, length = 500)
    private String message;

    @Column(name = "ref_type")
    private String refType;
    @Column(name = "ref_id")
    private Long refId;

    @Setter
    @Column(nullable = false)
    private boolean read = false;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    public Notification(Long userId, String type, String message, String refType, Long refId) {
        this.userId = userId;
        this.type = type;
        this.message = message;
        this.refType = refType;
        this.refId = refId;
    }
}

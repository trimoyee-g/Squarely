package com.squarely.notification.repo;

import com.squarely.notification.domain.Notification;
import com.squarely.notification.domain.PaymentObligation;
import com.squarely.notification.domain.PaymentObligation.Status;
import com.squarely.notification.domain.RecurringRule;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;

public final class Repos {
    private Repos() {}

    public interface NotificationRepository extends JpaRepository<Notification, Long> {
        List<Notification> findByUserIdOrderByCreatedAtDesc(Long userId);
        List<Notification> findByUserIdAndReadFalseOrderByCreatedAtDesc(Long userId);
        long countByUserIdAndReadFalse(Long userId);
    }

    public interface RecurringRuleRepository extends JpaRepository<RecurringRule, Long> {
        List<RecurringRule> findByActiveTrueAndNextDueDateLessThanEqual(LocalDate date);
        List<RecurringRule> findByCreatedBy(Long createdBy);
        /** Rules where userId appears anywhere in the memberUserIds element collection —
         *  needed so the other party to a 1:1 recurring rule can see it too, not just the creator. */
        List<RecurringRule> findByMemberUserIdsContaining(Long userId);
    }

    public interface ObligationRepository extends JpaRepository<PaymentObligation, Long> {
        List<PaymentObligation> findByStatusInAndDueDateLessThanEqual(List<Status> statuses, LocalDate date);
        List<PaymentObligation> findByRecurringRuleIdInOrderByDueDateAsc(List<Long> ruleIds);
    }
}

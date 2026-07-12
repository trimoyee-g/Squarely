package com.squarely.notification.service;

import com.squarely.notification.domain.Notification;
import com.squarely.notification.repo.Repos.NotificationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class NotificationServiceTest {

    @Mock NotificationRepository repo;
    NotificationService service;

    @BeforeEach
    void setUp() { service = new NotificationService(repo); }

    private static Notification notif(long id, long userId) {
        Notification n = new Notification(userId, "PAYMENT_DUE", "msg", "OBLIGATION", 1L);
        ReflectionTestUtils.setField(n, "id", id);
        return n;
    }

    @Test
    void notifyPersistsAndReturnsNotification() {
        when(repo.save(any())).thenAnswer(inv -> notif(1L, 5L));
        // no SSE subscribers -> push is a no-op, must not throw
        var n = service.notify(5L, "PAYMENT_DUE", "msg", "OBLIGATION", 1L);
        assertEquals(5L, n.getUserId());
        verify(repo).save(any(Notification.class));
    }

    @Test
    void markReadIgnoresNotificationOwnedByAnotherUser() {
        Notification other = notif(1L, 5L);
        when(repo.findById(1L)).thenReturn(Optional.of(other));
        service.markRead(1L, 1L);           // acting user 1 != owner 5
        assertFalse(other.isRead());
    }

    @Test
    void markReadSetsReadForOwner() {
        Notification mine = notif(1L, 1L);
        when(repo.findById(1L)).thenReturn(Optional.of(mine));
        service.markRead(1L, 1L);
        assertTrue(mine.isRead());
    }

    @Test
    void listUsesUnreadQueryWhenUnreadOnly() {
        when(repo.findByUserIdAndReadFalseOrderByCreatedAtDesc(1L)).thenReturn(List.of(notif(1L, 1L)));
        service.list(1L, true);
        verify(repo).findByUserIdAndReadFalseOrderByCreatedAtDesc(1L);
        verify(repo, never()).findByUserIdOrderByCreatedAtDesc(anyLong());
    }

    @Test
    void listUsesAllQueryWhenNotUnreadOnly() {
        when(repo.findByUserIdOrderByCreatedAtDesc(1L)).thenReturn(List.of());
        service.list(1L, false);
        verify(repo).findByUserIdOrderByCreatedAtDesc(1L);
    }

    @Test
    void unreadCountDelegatesToRepo() {
        when(repo.countByUserIdAndReadFalse(1L)).thenReturn(3L);
        assertEquals(3L, service.unreadCount(1L));
    }
}

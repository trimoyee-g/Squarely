package com.squarely.notification.service;

import com.squarely.notification.domain.Notification;
import com.squarely.notification.repo.Repos.NotificationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Persists notifications and pushes them to connected clients over SSE.
 * ponytail: the SSE registry is in-memory, so it only reaches clients on THIS instance.
 * For multiple notification-service replicas, fan out via Redis pub/sub. Single instance
 * is fine for now.
 */
@Service
public class NotificationService {

    private final NotificationRepository repo;
    private final Map<Long, CopyOnWriteArrayList<SseEmitter>> emitters = new java.util.concurrent.ConcurrentHashMap<>();

    public NotificationService(NotificationRepository repo) {
        this.repo = repo;
    }

    @Transactional
    public Notification notify(Long userId, String type, String message, String refType, Long refId) {
        Notification saved = repo.save(new Notification(userId, type, message, refType, refId));
        push(userId, saved);
        return saved;
    }

    public SseEmitter subscribe(long userId) {
        SseEmitter emitter = new SseEmitter(Long.MAX_VALUE);   // no server-side timeout
        CopyOnWriteArrayList<SseEmitter> list = emitters.computeIfAbsent(userId, k -> new CopyOnWriteArrayList<>());
        list.add(emitter);
        emitter.onCompletion(() -> list.remove(emitter));
        emitter.onTimeout(() -> list.remove(emitter));
        emitter.onError(e -> list.remove(emitter));
        return emitter;
    }

    private void push(Long userId, Notification n) {
        CopyOnWriteArrayList<SseEmitter> list = emitters.get(userId);
        if (list == null) return;
        for (SseEmitter emitter : list) {
            try {
                emitter.send(SseEmitter.event().name("notification").data(view(n)));
            } catch (IOException e) {
                list.remove(emitter);   // client gone
            }
        }
    }

    @Transactional(readOnly = true)
    public List<NotificationView> list(long userId, boolean unreadOnly) {
        var rows = unreadOnly
                ? repo.findByUserIdAndReadFalseOrderByCreatedAtDesc(userId)
                : repo.findByUserIdOrderByCreatedAtDesc(userId);
        return rows.stream().map(NotificationService::view).toList();
    }

    @Transactional(readOnly = true)
    public long unreadCount(long userId) {
        return repo.countByUserIdAndReadFalse(userId);
    }

    @Transactional
    public void markRead(long userId, long notificationId) {
        repo.findById(notificationId)
                .filter(n -> n.getUserId() == userId)
                .ifPresent(n -> n.setRead(true));
    }

    public record NotificationView(long id, long userId, String type, String message,
                                   String refType, Long refId, boolean read,
                                   java.time.Instant createdAt) {}

    private static NotificationView view(Notification n) {
        return new NotificationView(n.getId(), n.getUserId(), n.getType(), n.getMessage(),
                n.getRefType(), n.getRefId(), n.isRead(), n.getCreatedAt());
    }
}

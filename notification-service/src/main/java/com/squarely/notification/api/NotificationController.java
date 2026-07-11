package com.squarely.notification.api;

import com.squarely.common.security.AuthContext;
import com.squarely.notification.service.NotificationService;
import com.squarely.notification.service.NotificationService.NotificationView;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/notifications")
public class NotificationController {

    private final NotificationService service;

    public NotificationController(NotificationService service) {
        this.service = service;
    }

    @GetMapping
    public List<NotificationView> list(@RequestParam(defaultValue = "false") boolean unreadOnly) {
        return service.list(AuthContext.userId(), unreadOnly);
    }

    @GetMapping("/unread-count")
    public Map<String, Long> unreadCount() {
        return Map.of("count", service.unreadCount(AuthContext.userId()));
    }

    @PostMapping("/{id}/read")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void markRead(@PathVariable long id) {
        service.markRead(AuthContext.userId(), id);
    }

    /** Real-time stream of this user's notifications. */
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream() {
        return service.subscribe(AuthContext.userId());
    }
}

package com.example.autoauction.notification.web;

import com.example.autoauction.auth.domain.UserPrincipal;
import com.example.autoauction.auth.infrastructure.security.CurrentUser;
import com.example.autoauction.notification.application.NotificationService;
import com.example.autoauction.notification.domain.Notification;
import com.example.autoauction.vehicle.domain.VinText;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping({"/api/notifications", "/api/v1/notifications"})
@PreAuthorize("isAuthenticated()")
public class NotificationController {

    private final NotificationService notificationService;

    public NotificationController(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @GetMapping
    public ResponseEntity<Page<Notification>> getMyNotifications(
            @CurrentUser UserPrincipal currentUser,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<Notification> result = notificationService.getForUser(
                currentUser.getUserId(), PageRequest.of(page, size));
        // VIN-AUTH-01: texts written before the fix may name a lot as "… (VIN: …)" - removed on the way out,
        // the stored history is not rewritten.
        result.forEach(n -> {
            n.setTitle(VinText.withoutVin(n.getTitle()));
            n.setMessage(VinText.withoutVin(n.getMessage()));
        });
        return ResponseEntity.ok(result);
    }

    @GetMapping("/unread-count")
    public ResponseEntity<Map<String, Long>> getUnreadCount(@CurrentUser UserPrincipal currentUser) {
        long count = notificationService.countUnread(currentUser.getUserId());
        return ResponseEntity.ok(Map.of("count", count));
    }

    @PatchMapping("/{id}/read")
    public ResponseEntity<Void> markRead(
            @PathVariable Long id,
            @CurrentUser UserPrincipal currentUser) {
        notificationService.markRead(id, currentUser.getUserId());
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/read-all")
    public ResponseEntity<Void> markAllRead(@CurrentUser UserPrincipal currentUser) {
        notificationService.markAllRead(currentUser.getUserId());
        return ResponseEntity.noContent().build();
    }
}

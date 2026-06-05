package com.example.autoauction.notification.application;

import com.example.autoauction.notification.domain.Notification;
import com.example.autoauction.notification.infrastructure.persistence.JpaNotificationEntity;
import com.example.autoauction.notification.infrastructure.persistence.SpringDataNotificationRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
public class NotificationService {

    private final SpringDataNotificationRepository repository;
    private final SimpMessagingTemplate messagingTemplate;

    public NotificationService(SpringDataNotificationRepository repository,
                               SimpMessagingTemplate messagingTemplate) {
        this.repository = repository;
        this.messagingTemplate = messagingTemplate;
    }

    @Transactional
    public void send(Long userId, String type, String title, String message) {
        JpaNotificationEntity entity = new JpaNotificationEntity();
        entity.setUserId(userId);
        entity.setType(type);
        entity.setTitle(title);
        entity.setMessage(message);
        repository.save(entity);

        Notification notification = toDto(entity);
        try {
            messagingTemplate.convertAndSend("/topic/user/" + userId, notification);
        } catch (Exception e) {
            log.warn("Failed to push notification via WS to user {}: {}", userId, e.getMessage());
        }

        log.debug("Notification sent to user {}: [{}] {}", userId, type, title);
    }

    @Transactional(readOnly = true)
    public Page<Notification> getForUser(Long userId, Pageable pageable) {
        return repository.findByUserIdOrderByCreatedAtDesc(userId, pageable)
                .map(this::toDto);
    }

    @Transactional(readOnly = true)
    public long countUnread(Long userId) {
        return repository.countByUserIdAndReadFalse(userId);
    }

    @Transactional
    public void markRead(Long notificationId, Long userId) {
        repository.findById(notificationId).ifPresent(n -> {
            if (n.getUserId().equals(userId)) {
                n.setRead(true);
                repository.save(n);
            }
        });
    }

    @Transactional
    public void markAllRead(Long userId) {
        repository.markAllReadByUserId(userId);
    }

    private Notification toDto(JpaNotificationEntity e) {
        Notification n = new Notification();
        n.setId(e.getId());
        n.setUserId(e.getUserId());
        n.setType(e.getType());
        n.setTitle(e.getTitle());
        n.setMessage(e.getMessage());
        n.setRead(e.isRead());
        n.setCreatedAt(e.getCreatedAt());
        return n;
    }
}

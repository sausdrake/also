package com.example.autoauction.notification.application;

import com.example.autoauction.notification.domain.Notification;
import com.example.autoauction.notification.infrastructure.persistence.JpaNotificationEntity;
import com.example.autoauction.notification.infrastructure.persistence.SpringDataNotificationRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

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
        send(userId, type, title, message, null, null);
    }

    @Transactional
    public void send(Long userId, String type, String title, String message, Long referenceId, String referenceType) {
        Notification notification = persist(userId, type, title, message, referenceId, referenceType);
        push(userId, notification);
        log.debug("Notification sent to user {}: [{}] {}", userId, type, title);
    }

    /**
     * BE-NOTIF-01: for callers that run AFTER the business transaction has committed - the
     * {@code @TransactionalEventListener(phase = AFTER_COMMIT)} listeners. There the committed
     * transaction's resources are still bound, so {@link #send} (REQUIRED) would join that finished
     * transaction and its INSERT would never be committed (proved by
     * NotificationPersistenceAfterCommitPgIntegrationTest). Each call is its own transaction, which
     * keeps the listeners' per-recipient failure isolation; the WebSocket push happens only after
     * the row has committed. Calls made inside a live business transaction keep using {@link #send},
     * so their notification commits or rolls back with the business change.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void sendAfterCommit(Long userId, String type, String title, String message, Long referenceId, String referenceType) {
        Notification notification = persist(userId, type, title, message, referenceId, referenceType);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    push(userId, notification);
                }
            });
        } else {
            push(userId, notification);
        }
        log.debug("Notification sent after commit to user {}: [{}] {}", userId, type, title);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void sendAfterCommit(Long userId, String type, String title, String message) {
        sendAfterCommit(userId, type, title, message, null, null);
    }

    private Notification persist(Long userId, String type, String title, String message, Long referenceId, String referenceType) {
        JpaNotificationEntity entity = new JpaNotificationEntity();
        entity.setUserId(userId);
        entity.setType(type);
        entity.setTitle(title);
        entity.setMessage(message);
        entity.setReferenceId(referenceId);
        entity.setReferenceType(referenceType);
        repository.save(entity);
        return toDto(entity);
    }

    private void push(Long userId, Notification notification) {
        try {
            messagingTemplate.convertAndSendToUser(userId.toString(), "/queue/notifications", notification);
        } catch (Exception e) {
            log.warn("Failed to push notification via WS to user {}: {}", userId, e.getMessage());
        }
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
        n.setReferenceId(e.getReferenceId());
        n.setReferenceType(e.getReferenceType());
        return n;
    }
}

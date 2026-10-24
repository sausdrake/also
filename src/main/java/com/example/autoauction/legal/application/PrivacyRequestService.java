package com.example.autoauction.legal.application;

import com.example.autoauction.legal.domain.PrivacyRequestStatus;
import com.example.autoauction.legal.domain.PrivacyRequestType;
import com.example.autoauction.legal.infrastructure.persistence.JpaPrivacyRequestEntity;
import com.example.autoauction.legal.infrastructure.persistence.SpringDataPrivacyRequestRepository;
import com.example.autoauction.notification.application.NotificationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.EnumSet;
import java.util.List;

/**
 * PRIVACY-REQ-01: the minimal safe workflow for data-subject requests (access, correction, deletion,
 * objection, consent withdrawal, other). A request and its answer are recorded; nothing is changed or deleted
 * automatically - the administrator acts by hand and writes what was done. At most {@value #MAX_OPEN} open
 * requests per user, so the queue cannot be flooded.
 */
@Slf4j
@Service
public class PrivacyRequestService {

    static final int MAX_OPEN = 5;

    private final SpringDataPrivacyRequestRepository requests;
    private final NotificationService notificationService;
    private final Clock clock;

    public PrivacyRequestService(SpringDataPrivacyRequestRepository requests, NotificationService notificationService,
                                 Clock clock) {
        this.requests = requests;
        this.notificationService = notificationService;
        this.clock = clock;
    }

    @Transactional
    public JpaPrivacyRequestEntity create(Long userId, PrivacyRequestType type, String message) {
        if (requests.countByUserIdAndStatusIn(userId, EnumSet.of(PrivacyRequestStatus.OPEN, PrivacyRequestStatus.IN_PROGRESS)) >= MAX_OPEN) {
            throw new IllegalStateException("У вас уже " + MAX_OPEN + " обращений без ответа. Дождитесь ответа на них.");
        }
        JpaPrivacyRequestEntity saved = requests.save(new JpaPrivacyRequestEntity(userId, type, message, OffsetDateTime.now(clock)));
        log.info("Privacy request {} ({}) created by user {}", saved.getId(), type, userId);
        return saved;
    }

    @Transactional(readOnly = true)
    public List<JpaPrivacyRequestEntity> mine(Long userId) {
        return requests.findByUserIdOrderByCreatedAtDesc(userId);
    }

    @Transactional(readOnly = true)
    public List<JpaPrivacyRequestEntity> all(PrivacyRequestStatus status) {
        return status == null ? requests.findAllByOrderByCreatedAtDesc() : requests.findByStatusOrderByCreatedAtDesc(status);
    }

    @Transactional
    public JpaPrivacyRequestEntity take(Long id, Long adminId) {
        JpaPrivacyRequestEntity request = lock(id);
        request.take(adminId, OffsetDateTime.now(clock));
        return requests.save(request);
    }

    @Transactional
    public JpaPrivacyRequestEntity answer(Long id, Long adminId, String answer, boolean rejected) {
        JpaPrivacyRequestEntity request = lock(id);
        request.answer(adminId, answer, rejected, OffsetDateTime.now(clock));
        JpaPrivacyRequestEntity saved = requests.save(request);
        try {
            notificationService.send(saved.getUserId(), "PRIVACY_REQUEST_ANSWERED", "Ответ на обращение по персональным данным",
                    "На ваше обращение #" + saved.getId() + " дан ответ. Откройте раздел «Обращения» в личном кабинете.",
                    saved.getId(), "PRIVACY_REQUEST");
        } catch (Exception e) {
            log.warn("Failed to notify user {} about privacy request {}: {}", saved.getUserId(), saved.getId(), e.getMessage());
        }
        log.info("Privacy request {} answered by admin {} ({})", id, adminId, saved.getStatus());
        return saved;
    }

    private JpaPrivacyRequestEntity lock(Long id) {
        return requests.findByIdForUpdate(id).orElseThrow(() -> new IllegalArgumentException("Обращение не найдено: " + id));
    }
}

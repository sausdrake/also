package com.example.autoauction.legal.infrastructure.persistence;

import com.example.autoauction.legal.domain.PrivacyRequestStatus;
import com.example.autoauction.legal.domain.PrivacyRequestType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.OffsetDateTime;

/**
 * PRIVACY-REQ-01: a user's request about their personal data and the administrator's answer
 * ({@code privacy_request}, V65). A record of the request and the decision - the system changes or deletes
 * nothing on its own because of it.
 */
@Entity
@Table(name = "privacy_request")
public class JpaPrivacyRequestEntity {

    public static final int MAX_TEXT = 4000;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 30)
    private PrivacyRequestType type;

    @Column(name = "message", nullable = false, length = MAX_TEXT)
    private String message;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private PrivacyRequestStatus status;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "taken_by")
    private Long takenBy;

    @Column(name = "taken_at")
    private OffsetDateTime takenAt;

    @Column(name = "answered_by")
    private Long answeredBy;

    @Column(name = "answer", length = MAX_TEXT)
    private String answer;

    @Column(name = "answered_at")
    private OffsetDateTime answeredAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    protected JpaPrivacyRequestEntity() {
    }

    public JpaPrivacyRequestEntity(Long userId, PrivacyRequestType type, String message, OffsetDateTime now) {
        if (type == null) {
            throw new IllegalArgumentException("Тип обращения обязателен");
        }
        this.userId = userId;
        this.type = type;
        this.message = requireText(message, "Опишите обращение");
        this.status = PrivacyRequestStatus.OPEN;
        this.createdAt = now;
    }

    public void take(Long adminId, OffsetDateTime now) {
        if (status != PrivacyRequestStatus.OPEN) {
            throw new IllegalStateException("Взять в работу можно только новое обращение. Сейчас: " + status);
        }
        this.status = PrivacyRequestStatus.IN_PROGRESS;
        this.takenBy = adminId;
        this.takenAt = now;
    }

    public void answer(Long adminId, String answer, boolean rejected, OffsetDateTime now) {
        if (status != PrivacyRequestStatus.OPEN && status != PrivacyRequestStatus.IN_PROGRESS) {
            throw new IllegalStateException("На обращение уже дан ответ");
        }
        this.answer = requireText(answer, "Текст ответа обязателен");
        this.answeredBy = adminId;
        this.answeredAt = now;
        this.status = rejected ? PrivacyRequestStatus.REJECTED : PrivacyRequestStatus.ANSWERED;
    }

    private static String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        String trimmed = value.trim();
        if (trimmed.length() > MAX_TEXT) {
            throw new IllegalArgumentException("Не длиннее " + MAX_TEXT + " символов");
        }
        return trimmed;
    }

    public Long getId() { return id; }
    public Long getUserId() { return userId; }
    public PrivacyRequestType getType() { return type; }
    public String getMessage() { return message; }
    public PrivacyRequestStatus getStatus() { return status; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public Long getTakenBy() { return takenBy; }
    public OffsetDateTime getTakenAt() { return takenAt; }
    public Long getAnsweredBy() { return answeredBy; }
    public String getAnswer() { return answer; }
    public OffsetDateTime getAnsweredAt() { return answeredAt; }
}

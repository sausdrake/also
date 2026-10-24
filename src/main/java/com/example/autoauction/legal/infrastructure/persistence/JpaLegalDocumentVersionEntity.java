package com.example.autoauction.legal.infrastructure.persistence;

import com.example.autoauction.legal.domain.AcceptanceKind;
import com.example.autoauction.legal.domain.LegalDocumentStatus;
import com.example.autoauction.legal.domain.LegalDocumentType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.Objects;

/**
 * LEGAL-DATA-01: one version of one legal document ({@code legal_document_version}, V65). The lifecycle lives
 * here (see {@link LegalDocumentStatus}); the text is frozen from submission on, and its SHA-256 is what every
 * acceptance points at, so a recorded acceptance always proves which exact text was accepted.
 */
@Entity
@Table(name = "legal_document_version")
public class JpaLegalDocumentVersionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "document_type", nullable = false, length = 40)
    private LegalDocumentType documentType;

    @Column(name = "version", nullable = false, length = 40)
    private String version;

    @Column(name = "title", nullable = false, length = 200)
    private String title;

    @Column(name = "content", nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(name = "content_sha256", nullable = false, length = 64)
    private String contentSha256;

    @Enumerated(EnumType.STRING)
    @Column(name = "acceptance_kind", nullable = false, length = 20)
    private AcceptanceKind acceptanceKind;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private LegalDocumentStatus status;

    @Column(name = "created_by", nullable = false)
    private Long createdBy;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "submitted_at")
    private OffsetDateTime submittedAt;

    @Column(name = "approved_by")
    private Long approvedBy;

    @Column(name = "approved_at")
    private OffsetDateTime approvedAt;

    @Column(name = "approval_note", length = 1000)
    private String approvalNote;

    @Column(name = "activated_by")
    private Long activatedBy;

    @Column(name = "effective_at")
    private OffsetDateTime effectiveAt;

    @Column(name = "retired_at")
    private OffsetDateTime retiredAt;

    @Version
    @Column(name = "version_lock", nullable = false)
    private Long versionLock;

    protected JpaLegalDocumentVersionEntity() {
    }

    public JpaLegalDocumentVersionEntity(LegalDocumentType documentType, String version, String title, String content,
                                         AcceptanceKind acceptanceKind, Long createdBy, OffsetDateTime now) {
        this.documentType = Objects.requireNonNull(documentType, "documentType");
        this.version = requireText(version, "Версия документа обязательна", 40);
        this.title = requireText(title, "Название документа обязательно", 200);
        this.acceptanceKind = Objects.requireNonNull(acceptanceKind, "acceptanceKind");
        setContent(content);
        this.createdBy = Objects.requireNonNull(createdBy, "createdBy");
        this.createdAt = now;
        this.status = LegalDocumentStatus.DRAFT;
    }

    /** Only a DRAFT is edited. */
    public void edit(String title, String content, AcceptanceKind acceptanceKind) {
        requireStatus(LegalDocumentStatus.DRAFT, "изменить текст");
        this.title = requireText(title, "Название документа обязательно", 200);
        this.acceptanceKind = Objects.requireNonNull(acceptanceKind, "acceptanceKind");
        setContent(content);
    }

    public void submitForLegalReview(OffsetDateTime now) {
        requireStatus(LegalDocumentStatus.DRAFT, "отправить на юридическую проверку");
        this.status = LegalDocumentStatus.PENDING_LEGAL_REVIEW;
        this.submittedAt = now;
    }

    /** Maker-checker: the author never approves their own text; the note records who reviewed it and how. */
    public void approve(Long adminId, String note, OffsetDateTime now) {
        requireStatus(LegalDocumentStatus.PENDING_LEGAL_REVIEW, "утвердить");
        if (Objects.equals(adminId, createdBy)) {
            throw new IllegalStateException("Документ утверждает другой администратор, не автор текста");
        }
        this.approvalNote = requireText(note, "Укажите, кто и когда проверил документ (юрист, владелец)", 1000);
        this.approvedBy = adminId;
        this.approvedAt = now;
        this.status = LegalDocumentStatus.APPROVED;
    }

    public void activate(Long adminId, OffsetDateTime now) {
        requireStatus(LegalDocumentStatus.APPROVED, "ввести в действие");
        this.activatedBy = adminId;
        this.effectiveAt = now;
        this.status = LegalDocumentStatus.ACTIVE;
    }

    public void retire(OffsetDateTime now) {
        if (status != LegalDocumentStatus.ACTIVE && status != LegalDocumentStatus.APPROVED) {
            throw new IllegalStateException("Снять с действия можно только действующую или утверждённую редакцию. Сейчас: " + status);
        }
        this.status = LegalDocumentStatus.RETIRED;
        this.retiredAt = now;
    }

    private void setContent(String content) {
        this.content = requireText(content, "Текст документа обязателен", 200_000);
        this.contentSha256 = sha256(this.content);
    }

    private void requireStatus(LegalDocumentStatus expected, String action) {
        if (status != expected) {
            throw new IllegalStateException(String.format("Нельзя %s документ в статусе %s", action, status));
        }
    }

    private static String requireText(String value, String message, int max) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        String trimmed = value.trim();
        if (trimmed.length() > max) {
            throw new IllegalArgumentException("Слишком длинное значение (максимум " + max + " символов)");
        }
        return trimmed;
    }

    static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public Long getId() { return id; }
    public LegalDocumentType getDocumentType() { return documentType; }
    public String getVersion() { return version; }
    public String getTitle() { return title; }
    public String getContent() { return content; }
    public String getContentSha256() { return contentSha256; }
    public AcceptanceKind getAcceptanceKind() { return acceptanceKind; }
    public LegalDocumentStatus getStatus() { return status; }
    public Long getCreatedBy() { return createdBy; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getSubmittedAt() { return submittedAt; }
    public Long getApprovedBy() { return approvedBy; }
    public OffsetDateTime getApprovedAt() { return approvedAt; }
    public String getApprovalNote() { return approvalNote; }
    public Long getActivatedBy() { return activatedBy; }
    public OffsetDateTime getEffectiveAt() { return effectiveAt; }
    public OffsetDateTime getRetiredAt() { return retiredAt; }
}

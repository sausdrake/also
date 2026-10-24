package com.example.autoauction.legal.infrastructure.persistence;

import com.example.autoauction.legal.domain.AcceptanceKind;
import com.example.autoauction.legal.domain.AcceptanceSource;
import com.example.autoauction.legal.domain.AcceptanceStatus;
import com.example.autoauction.legal.domain.LegalDocumentType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

/**
 * LEGAL-DATA-01: a user's acceptance / acknowledgement / consent of one exact document version
 * ({@code legal_acceptance}, V65). Type, version and text hash are copied from the version so the record
 * stays self-describing. One row per user and version (unique); never deleted - a withdrawal (CONSENT only)
 * is recorded on the row.
 */
@Entity
@Table(name = "legal_acceptance")
public class JpaLegalAcceptanceEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "document_version_id", nullable = false)
    private Long documentVersionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "document_type", nullable = false, length = 40)
    private LegalDocumentType documentType;

    @Column(name = "document_version", nullable = false, length = 40)
    private String documentVersion;

    @Column(name = "content_sha256", nullable = false, length = 64)
    private String contentSha256;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 20)
    private AcceptanceKind kind;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private AcceptanceStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, length = 30)
    private AcceptanceSource source;

    @Column(name = "accepted_at", nullable = false)
    private OffsetDateTime acceptedAt;

    @Column(name = "ip_address", length = 64)
    private String ipAddress;

    @Column(name = "user_agent", length = 512)
    private String userAgent;

    @Column(name = "withdrawn_at")
    private OffsetDateTime withdrawnAt;

    protected JpaLegalAcceptanceEntity() {
    }

    public JpaLegalAcceptanceEntity(Long userId, JpaLegalDocumentVersionEntity document, AcceptanceSource source,
                                    String ipAddress, String userAgent, OffsetDateTime now) {
        this.userId = userId;
        this.documentVersionId = document.getId();
        this.documentType = document.getDocumentType();
        this.documentVersion = document.getVersion();
        this.contentSha256 = document.getContentSha256();
        this.kind = document.getAcceptanceKind();
        this.status = AcceptanceStatus.ACCEPTED;
        this.source = source;
        this.acceptedAt = now;
        this.ipAddress = truncate(ipAddress, 64);
        this.userAgent = truncate(userAgent, 512);
    }

    /** Only a consent can be withdrawn; an acceptance of the Terms ends with the account, not by a click. */
    public void withdraw(OffsetDateTime now) {
        if (kind != AcceptanceKind.CONSENT) {
            throw new IllegalStateException("Отозвать можно только согласие. Принятие условий и ознакомление не отзываются");
        }
        if (status == AcceptanceStatus.WITHDRAWN) {
            return;
        }
        this.status = AcceptanceStatus.WITHDRAWN;
        this.withdrawnAt = now;
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    public Long getId() { return id; }
    public Long getUserId() { return userId; }
    public Long getDocumentVersionId() { return documentVersionId; }
    public LegalDocumentType getDocumentType() { return documentType; }
    public String getDocumentVersion() { return documentVersion; }
    public String getContentSha256() { return contentSha256; }
    public AcceptanceKind getKind() { return kind; }
    public AcceptanceStatus getStatus() { return status; }
    public AcceptanceSource getSource() { return source; }
    public OffsetDateTime getAcceptedAt() { return acceptedAt; }
    public String getIpAddress() { return ipAddress; }
    public String getUserAgent() { return userAgent; }
    public OffsetDateTime getWithdrawnAt() { return withdrawnAt; }
}

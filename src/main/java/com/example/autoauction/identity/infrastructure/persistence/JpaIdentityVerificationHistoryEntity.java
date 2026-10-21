package com.example.autoauction.identity.infrastructure.persistence;

import com.example.autoauction.identity.domain.IdentityEventType;
import com.example.autoauction.identity.domain.RevocationReason;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

@Entity
@Table(name = "identity_verification_history")
public class JpaIdentityVerificationHistoryEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "identity_verification_id", nullable = false)
    private Long identityVerificationId;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 24)
    private IdentityEventType eventType;

    @Column(name = "canonical_document_number", nullable = false, length = 64)
    private String canonicalDocumentNumber;

    @Column(name = "document_reference_id")
    private Long documentReferenceId;

    @Column(name = "document_back_reference_id")   // KYC-CAPTURE-01 / V62
    private Long documentBackReferenceId;

    @Column(name = "actor_admin_id")
    private Long actorAdminId;

    @Enumerated(EnumType.STRING)
    @Column(name = "revocation_reason_code", length = 24)
    private RevocationReason revocationReasonCode;

    @Column(name = "reason_text", length = 1000)
    private String reasonText;

    @Column(name = "occurred_at", nullable = false)
    private OffsetDateTime occurredAt;

    protected JpaIdentityVerificationHistoryEntity() {
    }

    public JpaIdentityVerificationHistoryEntity(Long id, Long identityVerificationId, IdentityEventType eventType,
                                                 String canonicalDocumentNumber, Long documentReferenceId,
                                                 Long actorAdminId, RevocationReason revocationReasonCode,
                                                 String reasonText, OffsetDateTime occurredAt) {
        this.id = id;
        this.identityVerificationId = identityVerificationId;
        this.eventType = eventType;
        this.canonicalDocumentNumber = canonicalDocumentNumber;
        this.documentReferenceId = documentReferenceId;
        this.actorAdminId = actorAdminId;
        this.revocationReasonCode = revocationReasonCode;
        this.reasonText = reasonText;
        this.occurredAt = occurredAt;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getIdentityVerificationId() { return identityVerificationId; }
    public void setIdentityVerificationId(Long identityVerificationId) { this.identityVerificationId = identityVerificationId; }
    public IdentityEventType getEventType() { return eventType; }
    public void setEventType(IdentityEventType eventType) { this.eventType = eventType; }
    public String getCanonicalDocumentNumber() { return canonicalDocumentNumber; }
    public void setCanonicalDocumentNumber(String canonicalDocumentNumber) { this.canonicalDocumentNumber = canonicalDocumentNumber; }
    public Long getDocumentReferenceId() { return documentReferenceId; }
    public void setDocumentReferenceId(Long documentReferenceId) { this.documentReferenceId = documentReferenceId; }
    public Long getDocumentBackReferenceId() { return documentBackReferenceId; }
    public void setDocumentBackReferenceId(Long documentBackReferenceId) { this.documentBackReferenceId = documentBackReferenceId; }
    public Long getActorAdminId() { return actorAdminId; }
    public void setActorAdminId(Long actorAdminId) { this.actorAdminId = actorAdminId; }
    public RevocationReason getRevocationReasonCode() { return revocationReasonCode; }
    public void setRevocationReasonCode(RevocationReason revocationReasonCode) { this.revocationReasonCode = revocationReasonCode; }
    public String getReasonText() { return reasonText; }
    public void setReasonText(String reasonText) { this.reasonText = reasonText; }
    public OffsetDateTime getOccurredAt() { return occurredAt; }
    public void setOccurredAt(OffsetDateTime occurredAt) { this.occurredAt = occurredAt; }
}

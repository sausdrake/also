package com.example.autoauction.identity.domain;

import java.time.OffsetDateTime;
import java.util.Objects;

/**
 * Phase 3/3.1: the current-state identity-verification aggregate - exactly one row per user
 * ({@code UNIQUE(user_id)} at the DB level), holding only the LATEST submission's identity and
 * status. {@code UNVERIFIED} has no value here - absence of a row for a user means unverified.
 *
 * <p>The cross-account uniqueness invariant ({@code UNIQUE(canonical_document_number,
 * issuing_country, document_type) WHERE status = 'VERIFIED'}) is enforced entirely at the
 * database level (Phase 3 ADR-06) - this class's own guards protect same-row state-transition
 * correctness (status must be the expected value before a transition), never the cross-account
 * invariant, which this class cannot see or enforce on its own.
 *
 * <p>Full lifecycle history (every submission/approval/rejection/revocation/failed attempt) is
 * recorded separately in {@link IdentityVerificationHistory}, never here - this aggregate only
 * ever represents "now."
 */
public class IdentityVerification {

    private Long id;
    private final Long userId;
    private IdentityVerificationStatus status;
    private String issuingCountry;
    private DocumentType documentType;
    private String canonicalDocumentNumber;
    private Long currentDocumentId;
    private Long currentSelfieDocumentId;
    /** KYC-CAPTURE-01: back side of an ID card / driver licence; null for a passport and for legacy rows. */
    private Long currentDocumentBackId;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
    private Long version;

    /**
     * First-ever submission for a user who has no existing row. MVP-004: every submission carries
     * both the identity-document image and the live selfie taken through the platform camera.
     * KYC-CAPTURE-01: plus the back side exactly when the document type has one.
     */
    public static IdentityVerification firstSubmission(Long userId, String issuingCountry, DocumentType documentType,
                                                         String canonicalDocumentNumber, Long documentId,
                                                         Long documentBackId, Long selfieDocumentId, OffsetDateTime now) {
        requireBackSideShape(documentType, documentBackId);
        IdentityVerification iv = new IdentityVerification(userId);
        iv.status = IdentityVerificationStatus.PENDING_REVIEW;
        iv.issuingCountry = Objects.requireNonNull(issuingCountry, "issuingCountry не может быть null");
        iv.documentType = Objects.requireNonNull(documentType, "documentType не может быть null");
        iv.canonicalDocumentNumber = Objects.requireNonNull(canonicalDocumentNumber, "canonicalDocumentNumber не может быть null");
        iv.currentDocumentId = Objects.requireNonNull(documentId, "documentId не может быть null");
        iv.currentSelfieDocumentId = Objects.requireNonNull(selfieDocumentId, "selfieDocumentId не может быть null");
        iv.currentDocumentBackId = documentBackId;
        iv.createdAt = now;
        iv.updatedAt = now;
        iv.version = 0L;
        return iv;
    }

    private IdentityVerification(Long userId) {
        this.userId = Objects.requireNonNull(userId, "userId не может быть null");
    }

    /** Reconstruction constructor, used exclusively by the persistence mapper. */
    public IdentityVerification(Long id, Long userId, IdentityVerificationStatus status, String issuingCountry,
                                 DocumentType documentType, String canonicalDocumentNumber, Long currentDocumentId,
                                 Long currentDocumentBackId, Long currentSelfieDocumentId, OffsetDateTime createdAt,
                                 OffsetDateTime updatedAt, Long version) {
        this.id = id;
        this.userId = Objects.requireNonNull(userId, "userId не может быть null");
        this.status = Objects.requireNonNull(status, "status не может быть null");
        this.issuingCountry = issuingCountry;
        this.documentType = documentType;
        this.canonicalDocumentNumber = canonicalDocumentNumber;
        this.currentDocumentId = currentDocumentId;
        this.currentDocumentBackId = currentDocumentBackId;
        this.currentSelfieDocumentId = currentSelfieDocumentId;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.version = version;
    }

    /**
     * Phase 3.1 §2: the single uniform rule governing every resubmission case. Permitted only
     * when the current status is {@code REJECTED} or {@code REVOKED} - callers (the application
     * service) are responsible for handling the {@code PENDING_REVIEW}-same-identity idempotent
     * case themselves (via {@link #matchesIdentity}) before ever reaching this method, and for
     * never calling this method at all when status is {@code PENDING_REVIEW} (different
     * identity) or {@code VERIFIED} - both of which must fail closed with no state change.
     */
    public void resubmit(String issuingCountry, DocumentType documentType, String canonicalDocumentNumber,
                          Long documentId, Long documentBackId, Long selfieDocumentId, OffsetDateTime now) {
        if (status != IdentityVerificationStatus.REJECTED && status != IdentityVerificationStatus.REVOKED) {
            throw new IllegalStateException(String.format(
                    "Невозможно отправить новую заявку для пользователя %d в статусе '%s'. Повторная отправка доступна только после REJECTED или REVOKED",
                    userId, status));
        }
        // MVP-004: validated before any mutation - a resubmission without a selfie changes nothing.
        Objects.requireNonNull(selfieDocumentId, "selfieDocumentId не может быть null");
        requireBackSideShape(Objects.requireNonNull(documentType, "documentType не может быть null"), documentBackId);
        this.status = IdentityVerificationStatus.PENDING_REVIEW;
        this.issuingCountry = Objects.requireNonNull(issuingCountry, "issuingCountry не может быть null");
        this.documentType = Objects.requireNonNull(documentType, "documentType не может быть null");
        this.canonicalDocumentNumber = Objects.requireNonNull(canonicalDocumentNumber, "canonicalDocumentNumber не может быть null");
        this.currentDocumentId = Objects.requireNonNull(documentId, "documentId не может быть null");
        this.currentSelfieDocumentId = selfieDocumentId;
        this.currentDocumentBackId = documentBackId;
        this.updatedAt = now;
    }

    /**
     * KYC-CAPTURE-01: an ID card / driver licence carries its back side, a passport never does
     * ({@link DocumentCapturePolicy}).
     */
    private static void requireBackSideShape(DocumentType documentType, Long documentBackId) {
        Objects.requireNonNull(documentType, "documentType не может быть null");
        boolean requiresBack = DocumentCapturePolicy.requiresBack(documentType);
        if (requiresBack && documentBackId == null) {
            throw new IllegalArgumentException("Для этого документа нужна фотография обратной стороны");
        }
        if (!requiresBack && documentBackId != null) {
            throw new IllegalArgumentException("Для загранпаспорта нужна только страница с данными, без обратной стороны");
        }
    }

    /** True if the given identity exactly matches this row's current identity. */
    public boolean matchesIdentity(String issuingCountry, DocumentType documentType, String canonicalDocumentNumber) {
        return Objects.equals(this.issuingCountry, issuingCountry)
                && this.documentType == documentType
                && Objects.equals(this.canonicalDocumentNumber, canonicalDocumentNumber);
    }

    /**
     * Same-row transition guard only. The cross-account uniqueness invariant is enforced by the
     * database partial unique index when this mutation is persisted (Phase 3.1 §1) - this method
     * has no visibility into other accounts and must never be mistaken for that enforcement.
     */
    public void approve(OffsetDateTime now) {
        requireStatus(IdentityVerificationStatus.PENDING_REVIEW, "подтвердить");
        // MVP-004: the administrator approves the selfie AND the document - a submission made
        // before the live selfie became mandatory cannot be approved; it must be rejected and
        // resubmitted.
        if (currentSelfieDocumentId == null) {
            throw new IllegalStateException(String.format(
                    "Невозможно подтвердить заявку пользователя %d: в ней нет живого селфи. "
                            + "Отклоните заявку, чтобы пользователь отправил её повторно", userId));
        }
        // KYC-CAPTURE-01: an ID card / driver licence is approved only with both sides - a submission made
        // before the back side became mandatory must be rejected and resubmitted (same rule as the selfie).
        if (documentType != null && DocumentCapturePolicy.requiresBack(documentType) && currentDocumentBackId == null) {
            throw new IllegalStateException(String.format(
                    "Невозможно подтвердить заявку пользователя %d: нет фотографии обратной стороны документа. "
                            + "Отклоните заявку, чтобы пользователь отправил её повторно", userId));
        }
        this.status = IdentityVerificationStatus.VERIFIED;
        this.updatedAt = now;
    }

    public void reject(OffsetDateTime now) {
        requireStatus(IdentityVerificationStatus.PENDING_REVIEW, "отклонить");
        this.status = IdentityVerificationStatus.REJECTED;
        this.updatedAt = now;
    }

    /** Frees the canonical identity for future, independent re-claim - never an automatic transfer. */
    public void revoke(OffsetDateTime now) {
        requireStatus(IdentityVerificationStatus.VERIFIED, "отозвать подтверждение");
        this.status = IdentityVerificationStatus.REVOKED;
        this.updatedAt = now;
    }

    private void requireStatus(IdentityVerificationStatus expected, String actionRu) {
        if (this.status != expected) {
            throw new IllegalStateException(String.format(
                    "Невозможно %s заявку пользователя %d в статусе '%s'. Требуется статус '%s'",
                    actionRu, userId, status, expected));
        }
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getUserId() { return userId; }
    public IdentityVerificationStatus getStatus() { return status; }
    public String getIssuingCountry() { return issuingCountry; }
    public DocumentType getDocumentType() { return documentType; }
    public String getCanonicalDocumentNumber() { return canonicalDocumentNumber; }
    public Long getCurrentDocumentId() { return currentDocumentId; }
    public Long getCurrentSelfieDocumentId() { return currentSelfieDocumentId; }
    public Long getCurrentDocumentBackId() { return currentDocumentBackId; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }
}

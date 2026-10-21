package com.example.autoauction.identity.domain;

import java.time.OffsetDateTime;
import java.util.Objects;

/**
 * Phase 3/3.1 §6/§11: an immutable, append-only history row - one per lifecycle event
 * ({@code SUBMISSION}, {@code APPROVAL}, {@code REJECTION}, {@code REVOCATION},
 * {@code FAILED_APPROVAL_ATTEMPT}). Snapshots {@code canonicalDocumentNumber} at event time
 * rather than joining back to the (mutable) current-state row, because a resubmission changes
 * that row's identifier - history must never silently reinterpret a past event through a later
 * value. Mirrors {@code SettlementCorrection}/{@code PaymentDeadlineHistory}'s own established
 * single-converging-constructor validation pattern in this codebase.
 *
 * <p>{@code documentReferenceId} is populated ONLY on {@code SUBMISSION} (Phase 3.1 §3) - every
 * decision event deterministically refers to the most recent prior {@code SUBMISSION} event for
 * the same {@code identityVerificationId}, because a new submission is only ever accepted after
 * the previous cycle reached a terminal decision (Phase 3.1 §2) - at most one submission cycle
 * is ever "open" at a time, so there is no ambiguity to resolve by duplicating the reference.
 */
public class IdentityVerificationHistory {

    private Long id;
    private final Long identityVerificationId;
    private final IdentityEventType eventType;
    private final String canonicalDocumentNumber;
    private final Long documentReferenceId;
    private final Long actorAdminId;
    private final RevocationReason revocationReasonCode;
    private final String reasonText;
    private final OffsetDateTime occurredAt;
    /** KYC-CAPTURE-01: the back-side image a SUBMISSION carried (ID card / driver licence); null otherwise. */
    private Long documentBackReferenceId;

    private IdentityVerificationHistory(Long identityVerificationId, IdentityEventType eventType,
                                         String canonicalDocumentNumber, Long documentReferenceId,
                                         Long actorAdminId, RevocationReason revocationReasonCode,
                                         String reasonText, OffsetDateTime occurredAt) {
        this.identityVerificationId = Objects.requireNonNull(identityVerificationId, "identityVerificationId не может быть null");
        this.eventType = Objects.requireNonNull(eventType, "eventType не может быть null");
        this.canonicalDocumentNumber = Objects.requireNonNull(canonicalDocumentNumber, "canonicalDocumentNumber не может быть null");
        this.occurredAt = Objects.requireNonNull(occurredAt, "occurredAt не может быть null");
        validate(eventType, documentReferenceId, actorAdminId, revocationReasonCode, reasonText);
        this.documentReferenceId = documentReferenceId;
        this.actorAdminId = actorAdminId;
        this.revocationReasonCode = revocationReasonCode;
        this.reasonText = reasonText;
    }

    /** Reconstruction constructor, used exclusively by the persistence mapper. */
    public IdentityVerificationHistory(Long id, Long identityVerificationId, IdentityEventType eventType,
                                        String canonicalDocumentNumber, Long documentReferenceId, Long actorAdminId,
                                        RevocationReason revocationReasonCode, String reasonText, OffsetDateTime occurredAt) {
        this(identityVerificationId, eventType, canonicalDocumentNumber, documentReferenceId, actorAdminId,
                revocationReasonCode, reasonText, occurredAt);
        this.id = id;
    }

    /** Reconstruction constructor with the back-side reference (KYC-CAPTURE-01), used by the persistence mapper. */
    public IdentityVerificationHistory(Long id, Long identityVerificationId, IdentityEventType eventType,
                                        String canonicalDocumentNumber, Long documentReferenceId,
                                        Long documentBackReferenceId, Long actorAdminId,
                                        RevocationReason revocationReasonCode, String reasonText, OffsetDateTime occurredAt) {
        this(id, identityVerificationId, eventType, canonicalDocumentNumber, documentReferenceId, actorAdminId,
                revocationReasonCode, reasonText, occurredAt);
        if (documentBackReferenceId != null && eventType != IdentityEventType.SUBMISSION) {
            throw new IllegalArgumentException("documentBackReferenceId допустим только для SUBMISSION");
        }
        this.documentBackReferenceId = documentBackReferenceId;
    }

    /**
     * The one authoritative eventType-to-shape rule, mirroring {@code PaymentDeadlineHistory}'s
     * own {@code validateActorAndPreviousValue} pattern: every constructor/factory converges
     * through here.
     */
    private static void validate(IdentityEventType eventType, Long documentReferenceId, Long actorAdminId,
                                  RevocationReason revocationReasonCode, String reasonText) {
        switch (eventType) {
            case SUBMISSION -> {
                if (documentReferenceId == null) {
                    throw new IllegalArgumentException("documentReferenceId обязателен для SUBMISSION");
                }
                if (actorAdminId != null) {
                    throw new IllegalArgumentException("actorAdminId должен быть null для SUBMISSION");
                }
                if (revocationReasonCode != null || reasonText != null) {
                    throw new IllegalArgumentException("SUBMISSION не принимает revocationReasonCode/reasonText");
                }
            }
            case APPROVAL -> {
                if (documentReferenceId != null) {
                    throw new IllegalArgumentException("documentReferenceId должен быть null для APPROVAL");
                }
                if (actorAdminId == null) {
                    throw new IllegalArgumentException("actorAdminId обязателен для APPROVAL");
                }
                if (revocationReasonCode != null || reasonText != null) {
                    throw new IllegalArgumentException("APPROVAL не принимает revocationReasonCode/reasonText");
                }
            }
            case REJECTION -> {
                if (documentReferenceId != null) {
                    throw new IllegalArgumentException("documentReferenceId должен быть null для REJECTION");
                }
                if (actorAdminId == null) {
                    throw new IllegalArgumentException("actorAdminId обязателен для REJECTION");
                }
                if (revocationReasonCode != null) {
                    throw new IllegalArgumentException("revocationReasonCode не применим для REJECTION");
                }
                if (reasonText == null || reasonText.isBlank()) {
                    throw new IllegalArgumentException("reasonText обязателен для REJECTION");
                }
            }
            case REVOCATION -> {
                if (documentReferenceId != null) {
                    throw new IllegalArgumentException("documentReferenceId должен быть null для REVOCATION");
                }
                if (actorAdminId == null) {
                    throw new IllegalArgumentException("actorAdminId обязателен для REVOCATION");
                }
                if (revocationReasonCode == null) {
                    throw new IllegalArgumentException("revocationReasonCode обязателен для REVOCATION");
                }
                if (revocationReasonCode == RevocationReason.OTHER && (reasonText == null || reasonText.isBlank())) {
                    throw new IllegalArgumentException("reasonText обязателен для REVOCATION с кодом OTHER");
                }
            }
            case FAILED_APPROVAL_ATTEMPT -> {
                if (documentReferenceId != null) {
                    throw new IllegalArgumentException("documentReferenceId должен быть null для FAILED_APPROVAL_ATTEMPT");
                }
                if (actorAdminId == null) {
                    throw new IllegalArgumentException("actorAdminId обязателен для FAILED_APPROVAL_ATTEMPT");
                }
                if (revocationReasonCode != null) {
                    throw new IllegalArgumentException("revocationReasonCode не применим для FAILED_APPROVAL_ATTEMPT");
                }
            }
        }
    }

    public static IdentityVerificationHistory submission(Long identityVerificationId, String canonicalDocumentNumber,
                                                           Long documentReferenceId, OffsetDateTime occurredAt) {
        return submission(identityVerificationId, canonicalDocumentNumber, documentReferenceId, null, occurredAt);
    }

    /** KYC-CAPTURE-01: a submission with its back-side image (null for a passport). */
    public static IdentityVerificationHistory submission(Long identityVerificationId, String canonicalDocumentNumber,
                                                           Long documentReferenceId, Long documentBackReferenceId,
                                                           OffsetDateTime occurredAt) {
        IdentityVerificationHistory event = new IdentityVerificationHistory(identityVerificationId, IdentityEventType.SUBMISSION,
                canonicalDocumentNumber, documentReferenceId, null, null, null, occurredAt);
        event.documentBackReferenceId = documentBackReferenceId;
        return event;
    }

    public static IdentityVerificationHistory approval(Long identityVerificationId, String canonicalDocumentNumber,
                                                         Long actorAdminId, OffsetDateTime occurredAt) {
        return new IdentityVerificationHistory(identityVerificationId, IdentityEventType.APPROVAL,
                canonicalDocumentNumber, null, actorAdminId, null, null, occurredAt);
    }

    public static IdentityVerificationHistory rejection(Long identityVerificationId, String canonicalDocumentNumber,
                                                          Long actorAdminId, String reasonText, OffsetDateTime occurredAt) {
        return new IdentityVerificationHistory(identityVerificationId, IdentityEventType.REJECTION,
                canonicalDocumentNumber, null, actorAdminId, null, reasonText, occurredAt);
    }

    public static IdentityVerificationHistory revocation(Long identityVerificationId, String canonicalDocumentNumber,
                                                           Long actorAdminId, RevocationReason reasonCode,
                                                           String reasonText, OffsetDateTime occurredAt) {
        return new IdentityVerificationHistory(identityVerificationId, IdentityEventType.REVOCATION,
                canonicalDocumentNumber, null, actorAdminId, reasonCode, reasonText, occurredAt);
    }

    public static IdentityVerificationHistory failedApprovalAttempt(Long identityVerificationId, String canonicalDocumentNumber,
                                                                      Long actorAdminId, OffsetDateTime occurredAt) {
        return new IdentityVerificationHistory(identityVerificationId, IdentityEventType.FAILED_APPROVAL_ATTEMPT,
                canonicalDocumentNumber, null, actorAdminId, null, null, occurredAt);
    }

    public Long getId() { return id; }
    public Long getIdentityVerificationId() { return identityVerificationId; }
    public IdentityEventType getEventType() { return eventType; }
    public String getCanonicalDocumentNumber() { return canonicalDocumentNumber; }
    public Long getDocumentReferenceId() { return documentReferenceId; }
    public Long getDocumentBackReferenceId() { return documentBackReferenceId; }
    public Long getActorAdminId() { return actorAdminId; }
    public RevocationReason getRevocationReasonCode() { return revocationReasonCode; }
    public String getReasonText() { return reasonText; }
    public OffsetDateTime getOccurredAt() { return occurredAt; }
}

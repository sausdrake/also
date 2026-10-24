package com.example.autoauction.legal.web.dto;

import com.example.autoauction.legal.domain.AcceptanceKind;
import com.example.autoauction.legal.domain.LegalDocumentType;
import com.example.autoauction.legal.domain.PrivacyRequestType;
import com.example.autoauction.legal.infrastructure.persistence.JpaLegalAcceptanceEntity;
import com.example.autoauction.legal.infrastructure.persistence.JpaLegalDocumentVersionEntity;
import com.example.autoauction.legal.infrastructure.persistence.JpaPrivacyRequestEntity;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;
import java.util.List;

/** LEGAL-DATA-01 / PRIVACY-REQ-01: request and response shapes of the legal endpoints. */
public final class LegalDtos {

    private LegalDtos() {
    }

    /** What anybody sees of a document version in force (no staff ids). */
    public record PublicDocument(Long id, LegalDocumentType documentType, String version, String title,
                                 AcceptanceKind acceptanceKind, OffsetDateTime effectiveAt, String contentSha256,
                                 String content) {
        public static PublicDocument of(JpaLegalDocumentVersionEntity d, boolean withContent) {
            return new PublicDocument(d.getId(), d.getDocumentType(), d.getVersion(), d.getTitle(), d.getAcceptanceKind(),
                    d.getEffectiveAt(), d.getContentSha256(), withContent ? d.getContent() : null);
        }
    }

    /** The administrator's view of a version, its whole lifecycle. */
    public record AdminDocument(Long id, LegalDocumentType documentType, String version, String title,
                                AcceptanceKind acceptanceKind, String status, String content, String contentSha256,
                                Long createdBy, OffsetDateTime createdAt, OffsetDateTime submittedAt, Long approvedBy,
                                OffsetDateTime approvedAt, String approvalNote, Long activatedBy,
                                OffsetDateTime effectiveAt, OffsetDateTime retiredAt) {
        public static AdminDocument of(JpaLegalDocumentVersionEntity d) {
            return new AdminDocument(d.getId(), d.getDocumentType(), d.getVersion(), d.getTitle(), d.getAcceptanceKind(),
                    d.getStatus().name(), d.getContent(), d.getContentSha256(), d.getCreatedBy(), d.getCreatedAt(),
                    d.getSubmittedAt(), d.getApprovedBy(), d.getApprovedAt(), d.getApprovalNote(), d.getActivatedBy(),
                    d.getEffectiveAt(), d.getRetiredAt());
        }
    }

    public record CreateDocumentRequest(@NotNull LegalDocumentType documentType,
                                        @NotNull @Size(max = 40) String version,
                                        @NotNull @Size(max = 200) String title,
                                        @NotNull AcceptanceKind acceptanceKind,
                                        @NotNull @Size(max = 200_000) String content) {
    }

    public record EditDocumentRequest(@NotNull @Size(max = 200) String title,
                                      @NotNull AcceptanceKind acceptanceKind,
                                      @NotNull @Size(max = 200_000) String content) {
    }

    public record ApproveDocumentRequest(@NotNull @Size(max = 1000) String note) {
    }

    public record AcceptRequest(@NotEmpty @Size(max = 20) List<Long> documentVersionIds) {
    }

    /** The user's own record: no IP or user-agent echoed back. */
    public record AcceptanceView(Long id, Long documentVersionId, LegalDocumentType documentType, String documentVersion,
                                 AcceptanceKind kind, String status, String source, OffsetDateTime acceptedAt,
                                 OffsetDateTime withdrawnAt) {
        public static AcceptanceView of(JpaLegalAcceptanceEntity a) {
            return new AcceptanceView(a.getId(), a.getDocumentVersionId(), a.getDocumentType(), a.getDocumentVersion(),
                    a.getKind(), a.getStatus().name(), a.getSource().name(), a.getAcceptedAt(), a.getWithdrawnAt());
        }
    }

    public record CreatePrivacyRequest(@NotNull PrivacyRequestType type,
                                       @NotNull @Size(max = JpaPrivacyRequestEntity.MAX_TEXT) String message) {
    }

    public record AnswerPrivacyRequest(@NotNull @Size(max = JpaPrivacyRequestEntity.MAX_TEXT) String answer,
                                       boolean rejected) {
    }

    public record PrivacyRequestView(Long id, Long userId, PrivacyRequestType type, String message, String status,
                                     OffsetDateTime createdAt, OffsetDateTime takenAt, String answer,
                                     OffsetDateTime answeredAt) {
        public static PrivacyRequestView of(JpaPrivacyRequestEntity r) {
            return new PrivacyRequestView(r.getId(), r.getUserId(), r.getType(), r.getMessage(), r.getStatus().name(),
                    r.getCreatedAt(), r.getTakenAt(), r.getAnswer(), r.getAnsweredAt());
        }
    }
}

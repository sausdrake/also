package com.example.autoauction.identity.application;

import com.example.autoauction.diagnostic.media.domain.port.StorageClient;
import com.example.autoauction.identity.domain.CanonicalDocumentNumber;
import com.example.autoauction.identity.domain.DocumentCapturePolicy;
import com.example.autoauction.identity.domain.DocumentType;
import com.example.autoauction.identity.domain.IdentityCaptureSide;
import com.example.autoauction.identity.domain.IdentityDocument;
import com.example.autoauction.identity.domain.IdentityVerification;
import com.example.autoauction.identity.domain.IdentityVerificationHistory;
import com.example.autoauction.identity.domain.IdentityVerificationStatus;
import com.example.autoauction.identity.domain.port.IdentityDocumentRepository;
import com.example.autoauction.identity.domain.port.IdentityVerificationHistoryRepository;
import com.example.autoauction.identity.domain.port.IdentityVerificationRepository;
import com.example.autoauction.auction.application.RateLimitException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Phase 3/4: self-service identity-verification operations - submission, own-status/history
 * queries, own-document access. Narrow, self-contained: never touches {@code User},
 * {@code BidService}, or {@code DepositService}. Enforcement of the resulting status on protected
 * operations lives in {@link VerifiedIdentityGate} (MVP-004).
 *
 * <p>Deliberately never logs {@code rawDocumentNumber} or any canonical document number
 * (Phase 3.1 §5) - the raw value exists only as a method parameter here, canonicalized
 * immediately, and is never persisted or included in any log statement.
 */
@Slf4j
@Service
public class IdentityVerificationService {

    /** MVP operating policy (application layer, not a DB CHECK - Phase 3.1 §2/Phase 4 §20). */
    private static final String SUPPORTED_ISSUING_COUNTRY = "KG";
    private static final String STORAGE_KEY_PREFIX = "identity-documents/";
    private static final Duration UPLOAD_URL_TTL = Duration.ofMinutes(15);
    private static final Duration READ_URL_TTL = Duration.ofMinutes(5);

    private final IdentityVerificationRepository identityVerificationRepository;
    private final IdentityVerificationHistoryRepository historyRepository;
    private final IdentityDocumentRepository documentRepository;
    private final StorageClient storageClient;
    private final IdentityCaptureVerifier captureVerifier;
    private final IdentityCaptureRateLimiter captureRateLimiter;
    private final Clock clock;

    public IdentityVerificationService(IdentityVerificationRepository identityVerificationRepository,
                                        IdentityVerificationHistoryRepository historyRepository,
                                        IdentityDocumentRepository documentRepository,
                                        StorageClient storageClient,
                                        IdentityCaptureVerifier captureVerifier,
                                        IdentityCaptureRateLimiter captureRateLimiter,
                                        Clock clock) {
        this.identityVerificationRepository = identityVerificationRepository;
        this.historyRepository = historyRepository;
        this.documentRepository = documentRepository;
        this.storageClient = storageClient;
        this.captureVerifier = captureVerifier;
        this.captureRateLimiter = captureRateLimiter;
        this.clock = clock;
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(clock);
    }

    /**
     * Phase 3.1 §4 step 1: server generates the opaque key and immediately creates the
     * ownership row - the client never chooses or influences the key.
     *
     * <p>KYC-CAPTURE-01: the slot is a camera capture of one side (of one document type) - JPEG only, the
     * content type the presigned PUT is signed with, so storage refuses any other file type.
     */
    @Transactional
    public IdentityUploadSlot requestUploadSlot(Long userId, String contentType, IdentityCaptureSide side,
                                               DocumentType documentType) {
        Objects.requireNonNull(userId, "userId не может быть null");
        if (!IdentityCaptureVerifier.CAPTURE_CONTENT_TYPE.equals(contentType)) {
            throw new IllegalArgumentException("Документы и селфи принимаются только как снимок камеры (image/jpeg)");
        }
        // KYC-SLOT-01: a slot is a row + a presigned URL - no bulk minting
        if (!captureRateLimiter.tryAcquire(userId)) {
            throw new RateLimitException("Слишком много снимков за час. Попробуйте позже.");
        }
        String storageKey = STORAGE_KEY_PREFIX + UUID.randomUUID();
        IdentityDocument document = IdentityDocument.captureSlot(storageKey, userId, side, documentType, contentType, now());
        documentRepository.save(document);
        StorageClient.PresignedUpload upload = storageClient.createPresignedUpload(storageKey, contentType, UPLOAD_URL_TTL);
        return new IdentityUploadSlot(storageKey, upload.uploadUrl(), upload.expiresAt());
    }

    /**
     * Phase 3.1 §2: the single uniform submission rule. Phase 4 §4: raw value is canonicalized
     * immediately and never persisted.
     *
     * <p>KYC-CAPTURE-01: the document is the camera captures its type requires ({@link DocumentCapturePolicy}:
     * ID card / driver licence = FRONT + BACK, passport = DATA_PAGE only) plus the live selfie (MVP-004);
     * every capture passes {@link IdentityCaptureVerifier} and is consumed by this submission.
     */
    @Transactional
    public IdentityVerification submit(Long userId, String issuingCountry, DocumentType documentType,
                                        String rawDocumentNumber, String documentStorageKey,
                                        String documentBackStorageKey, String selfieStorageKey) {
        if (!SUPPORTED_ISSUING_COUNTRY.equals(issuingCountry)) {
            throw new IllegalArgumentException("Неподдерживаемая страна выдачи документа: " + issuingCountry);
        }
        Objects.requireNonNull(documentType, "documentType не может быть null");
        String canonical = CanonicalDocumentNumber.from(rawDocumentNumber).value();

        OffsetDateTime at = now();

        // Phase 3.1 §4 step 3 + KYC-CAPTURE-01: ownership, purpose (side + type), single use, validity, JPEG.
        IdentityDocument document = captureVerifier.require(userId, documentStorageKey,
                DocumentCapturePolicy.mainSide(documentType), documentType, at);
        IdentityDocument back = null;
        if (DocumentCapturePolicy.requiresBack(documentType)) {
            back = captureVerifier.require(userId, documentBackStorageKey, IdentityCaptureSide.BACK, documentType, at);
        } else if (documentBackStorageKey != null && !documentBackStorageKey.isBlank()) {
            throw new IllegalArgumentException("Для загранпаспорта нужна только страница с данными, без обратной стороны");
        }
        // MVP-004: the live selfie taken through the platform camera - its own capture, never a document image.
        IdentityDocument selfie = captureVerifier.require(userId, selfieStorageKey, IdentityCaptureSide.SELFIE, null, at);
        Long backId = back == null ? null : back.getId();
        captureVerifier.consume(back == null ? List.of(document, selfie) : List.of(document, back, selfie), at);

        IdentityVerification existing = identityVerificationRepository.findByUserIdWithLock(userId).orElse(null);

        if (existing == null) {
            IdentityVerification created = IdentityVerification.firstSubmission(
                    userId, issuingCountry, documentType, canonical, document.getId(), backId, selfie.getId(), now());
            // save() returns the persisted instance carrying the generated id - the mapper
            // reconstructs a NEW IdentityVerification rather than mutating `created` in place, so
            // the return value must be captured, never discarded.
            created = identityVerificationRepository.save(created);
            historyRepository.save(IdentityVerificationHistory.submission(
                    created.getId(), canonical, document.getId(), backId, now()));
            log.info("User {} submitted a new identity verification", userId);
            return created;
        }

        if (existing.getStatus() == IdentityVerificationStatus.PENDING_REVIEW) {
            if (existing.matchesIdentity(issuingCountry, documentType, canonical)) {
                // Idempotent - same identity already pending, no new row/history.
                return existing;
            }
            throw new IllegalStateException(
                    "У пользователя уже есть заявка на рассмотрении. Дождитесь решения перед отправкой другого документа.");
        }
        if (existing.getStatus() == IdentityVerificationStatus.VERIFIED) {
            throw new IllegalStateException(
                    "Личность пользователя уже подтверждена. Замена документа пока не поддерживается.");
        }

        // REJECTED or REVOKED -> resubmission allowed, unlimited (Phase 2 §5).
        existing.resubmit(issuingCountry, documentType, canonical, document.getId(), backId, selfie.getId(), now());
        identityVerificationRepository.save(existing);
        historyRepository.save(IdentityVerificationHistory.submission(existing.getId(), canonical, document.getId(), backId, now()));
        log.info("User {} resubmitted a new identity verification", userId);
        return existing;
    }

    @Transactional(readOnly = true)
    public IdentityVerification getOwnStatus(Long userId) {
        return identityVerificationRepository.findByUserId(userId)
                .orElseThrow(() -> new IllegalArgumentException("Заявка на подтверждение личности не найдена"));
    }

    @Transactional(readOnly = true)
    public List<IdentityVerificationHistory> getOwnHistory(Long userId) {
        IdentityVerification iv = getOwnStatus(userId);
        return historyRepository.findByIdentityVerificationIdOrderByOccurredAtAscIdAsc(iv.getId());
    }

    /** Fresh, short-lived presigned read URL for the caller's own current document - never persisted. */
    @Transactional(readOnly = true)
    public URI getOwnDocumentUrl(Long userId) {
        IdentityVerification iv = getOwnStatus(userId);
        if (iv.getCurrentDocumentId() == null) {
            throw new IllegalArgumentException("Документ не найден");
        }
        IdentityDocument document = documentRepository.findById(iv.getCurrentDocumentId())
                .orElseThrow(() -> new IllegalArgumentException("Документ не найден"));
        if (!document.isOwnedBy(userId)) {
            throw new SecurityException("Документ не принадлежит текущему пользователю");
        }
        return storageClient.createPresignedReadUrl(document.getStorageKey(), READ_URL_TTL);
    }
}

package com.example.autoauction.identity.application;

import com.example.autoauction.diagnostic.media.domain.port.StorageClient;
import com.example.autoauction.identity.domain.IdentityDocument;
import com.example.autoauction.identity.domain.IdentityVerification;
import com.example.autoauction.identity.domain.IdentityVerificationHistory;
import com.example.autoauction.identity.domain.IdentityVerificationStatus;
import com.example.autoauction.identity.domain.RevocationReason;
import com.example.autoauction.identity.domain.port.IdentityDocumentRepository;
import com.example.autoauction.identity.domain.port.IdentityVerificationHistoryRepository;
import com.example.autoauction.identity.domain.port.IdentityVerificationRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;

/**
 * Phase 3/3.1: admin-only identity-review operations. Kept as its own service - distinct from
 * {@link IdentityVerificationService} - because admin review is a genuinely different
 * authorization boundary from self-service submission, mirroring {@code SaleAdministrationService}
 * being kept separate from user-facing flows elsewhere in this codebase.
 */
@Slf4j
@Service
public class AdminIdentityVerificationService {

    private static final Duration READ_URL_TTL = Duration.ofMinutes(5);

    private final IdentityVerificationRepository identityVerificationRepository;
    private final IdentityVerificationHistoryRepository historyRepository;
    private final IdentityDocumentRepository documentRepository;
    private final StorageClient storageClient;
    private final Clock clock;

    public AdminIdentityVerificationService(IdentityVerificationRepository identityVerificationRepository,
                                             IdentityVerificationHistoryRepository historyRepository,
                                             IdentityDocumentRepository documentRepository,
                                             StorageClient storageClient,
                                             Clock clock) {
        this.identityVerificationRepository = identityVerificationRepository;
        this.historyRepository = historyRepository;
        this.documentRepository = documentRepository;
        this.storageClient = storageClient;
        this.clock = clock;
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(clock);
    }

    @Transactional(readOnly = true)
    public List<IdentityVerification> listPending() {
        return identityVerificationRepository.findByStatusOrderByCreatedAtAsc(IdentityVerificationStatus.PENDING_REVIEW);
    }

    @Transactional(readOnly = true)
    public IdentityVerification inspect(Long id) {
        return identityVerificationRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Заявка не найдена: " + id));
    }

    @Transactional(readOnly = true)
    public URI getDocumentUrl(Long id) {
        IdentityVerification iv = inspect(id);
        if (iv.getCurrentDocumentId() == null) {
            throw new IllegalArgumentException("Документ не найден");
        }
        IdentityDocument document = documentRepository.findById(iv.getCurrentDocumentId())
                .orElseThrow(() -> new IllegalArgumentException("Документ не найден"));
        return storageClient.createPresignedReadUrl(document.getStorageKey(), READ_URL_TTL);
    }

    /** MVP-004: the live selfie the administrator compares with the document. */
    @Transactional(readOnly = true)
    public URI getSelfieUrl(Long id) {
        IdentityVerification iv = inspect(id);
        if (iv.getCurrentSelfieDocumentId() == null) {
            throw new IllegalArgumentException("Селфи не найдено");
        }
        IdentityDocument selfie = documentRepository.findById(iv.getCurrentSelfieDocumentId())
                .orElseThrow(() -> new IllegalArgumentException("Селфи не найдено"));
        return storageClient.createPresignedReadUrl(selfie.getStorageKey(), READ_URL_TTL);
    }

    @Transactional(readOnly = true)
    public List<IdentityVerificationHistory> getHistory(Long id) {
        inspect(id); // 404 if the parent row does not exist
        return historyRepository.findByIdentityVerificationIdOrderByOccurredAtAscIdAsc(id);
    }

    /**
     * Phase 3.1 §1 / Phase 4.2: the transactional write. Uses {@code findByIdWithLock} (same-row
     * serialization) together with the repository adapter's own explicit {@code saveAndFlush}
     * (cross-account serialization via the DB partial unique index, checked synchronously here).
     * If the flush violates the partial unique index, this method does NOT catch-and-continue -
     * it lets the exception propagate so Spring rolls the whole transaction back, since Postgres
     * has already aborted it at the statement level and no further write in it could succeed.
     *
     * <p>Callers MUST invoke this through this bean's own injected reference (i.e. from a
     * different bean), never via same-class self-invocation - Phase 4.1's real-Postgres
     * transaction-trace audit proved that a same-bean call silently bypasses this
     * {@code @Transactional} annotation entirely. {@link AdminIdentityApprovalOrchestrator} exists
     * specifically to be that different bean for the conflict-handling caller.
     */
    @Transactional
    public IdentityVerification approve(Long id, Long adminId) {
        IdentityVerification iv = identityVerificationRepository.findByIdWithLock(id)
                .orElseThrow(() -> new IllegalArgumentException("Заявка не найдена: " + id));
        iv.approve(now());
        identityVerificationRepository.save(iv);
        historyRepository.save(IdentityVerificationHistory.approval(iv.getId(), iv.getCanonicalDocumentNumber(), adminId, now()));
        log.info("Admin {} approved identity verification {}", adminId, id);
        return iv;
    }

    /**
     * Phase 3.1 §1 / Phase 4.2: isolated recovery-side-effect only. {@code REQUIRES_NEW} because
     * the triggering transaction ({@link #approve}) has already been rolled back by the time this
     * is called - no further statement in that aborted transaction/connection could succeed, so
     * this audit write needs its own fresh transaction (mirrors
     * {@code SettlementCorrectionRepositoryAdapter}'s own established isolation technique, though
     * not its purpose: there is no idempotency-key safe-replay ambiguity to resolve here, just a
     * genuine losing admin whose attempt must be recorded).
     *
     * <p>Same same-bean-self-invocation caveat as {@link #approve}: must be called from a
     * different bean ({@link AdminIdentityApprovalOrchestrator}), never via {@code this}.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFailedApprovalAttempt(Long identityVerificationId, String canonicalDocumentNumber, Long adminId) {
        historyRepository.save(IdentityVerificationHistory.failedApprovalAttempt(
                identityVerificationId, canonicalDocumentNumber, adminId, now()));
    }

    @Transactional
    public IdentityVerification reject(Long id, Long adminId, String reasonText) {
        if (reasonText == null || reasonText.isBlank()) {
            throw new IllegalArgumentException("reason обязателен для отклонения заявки");
        }
        IdentityVerification iv = identityVerificationRepository.findByIdWithLock(id)
                .orElseThrow(() -> new IllegalArgumentException("Заявка не найдена: " + id));
        iv.reject(now());
        identityVerificationRepository.save(iv);
        historyRepository.save(IdentityVerificationHistory.rejection(
                iv.getId(), iv.getCanonicalDocumentNumber(), adminId, reasonText.strip(), now()));
        log.info("Admin {} rejected identity verification {}", adminId, id);
        return iv;
    }

    @Transactional
    public IdentityVerification revoke(Long id, Long adminId, RevocationReason reasonCode, String reasonText) {
        Objects.requireNonNull(reasonCode, "reasonCode обязателен для отзыва подтверждения");
        String normalizedText = (reasonText == null || reasonText.isBlank()) ? null : reasonText.strip();
        if (reasonCode == RevocationReason.OTHER && normalizedText == null) {
            throw new IllegalArgumentException("reasonText обязателен для причины OTHER");
        }
        IdentityVerification iv = identityVerificationRepository.findByIdWithLock(id)
                .orElseThrow(() -> new IllegalArgumentException("Заявка не найдена: " + id));
        iv.revoke(now());
        identityVerificationRepository.save(iv);
        historyRepository.save(IdentityVerificationHistory.revocation(
                iv.getId(), iv.getCanonicalDocumentNumber(), adminId, reasonCode, normalizedText, now()));
        log.info("Admin {} revoked identity verification {} (reason={})", adminId, id, reasonCode);
        return iv;
    }
}

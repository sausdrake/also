// diagnostic/media/infrastructure/persistence/DiagnosticMediaRepositoryProcessingTest.java
package com.example.autoauction.diagnostic.media.infrastructure.persistence;

import com.example.autoauction.diagnostic.domain.DiagnosticRequest;
import com.example.autoauction.diagnostic.domain.InspectionGroup;
import com.example.autoauction.diagnostic.domain.InspectionItem;
import com.example.autoauction.diagnostic.domain.InspectionTemplate;
import com.example.autoauction.diagnostic.domain.port.DiagnosticRequestRepository;
import com.example.autoauction.diagnostic.domain.port.InspectionTemplateRepository;
import com.example.autoauction.diagnostic.media.domain.DiagnosticMedia;
import com.example.autoauction.diagnostic.media.domain.MediaType;
import com.example.autoauction.diagnostic.media.domain.ProcessingStatus;
import com.example.autoauction.diagnostic.media.domain.UploadStatus;
import com.example.autoauction.diagnostic.media.domain.port.DiagnosticMediaRepository;
import com.example.autoauction.user.domain.Role;
import com.example.autoauction.user.domain.User;
import com.example.autoauction.user.domain.port.RoleRepository;
import com.example.autoauction.user.domain.port.UserRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MEDIA-3.4 Phase 2: proves the atomic PENDING->PROCESSING->READY/FAILED
 * transition family against a real DB, mirroring MediaReconciliationServiceIntegrationTest's
 * conventions. @Transactional (rollback-per-test) - findPendingProcessing()/
 * findStaleProcessing() scan by status across ALL rows, not by ID, so this
 * class needs the same isolation MediaReconciliationServiceIntegrationTest's
 * neighbors already established for exactly this reason.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@DisplayName("MEDIA-3.4: DiagnosticMediaRepository processing transitions")
class DiagnosticMediaRepositoryProcessingTest {

    @Autowired
    private DiagnosticMediaRepository mediaRepository;

    @Autowired
    private DiagnosticRequestRepository requestRepository;

    @Autowired
    private InspectionTemplateRepository templateRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private record RequestAndItem(Long requestId, Long itemId, Long uploaderId) {}

    private Long createUser(String prefix) {
        Role userRole = roleRepository.findByName("ROLE_USER")
                .orElseGet(() -> roleRepository.save(new Role(null, "ROLE_USER", "Обычный пользователь")));
        User u = new User(null, prefix + System.nanoTime(), prefix + System.nanoTime() + "@test.com",
                "encoded", true, Set.of(userRole));
        return userRepository.save(u).getId();
    }

    private RequestAndItem createRequestAndItem() {
        InspectionTemplate t = new InspectionTemplate();
        t.setName("T-" + System.nanoTime());
        t.setActive(true);
        Long templateId = templateRepository.saveTemplate(t).getId();

        InspectionGroup g = new InspectionGroup();
        g.setTemplateId(templateId);
        g.setName("G-" + System.nanoTime());
        g.setSortOrder(1);
        Long groupId = templateRepository.saveGroup(g).getId();

        InspectionItem i = new InspectionItem();
        i.setGroupId(groupId);
        i.setTitle("I-" + System.nanoTime());
        i.setFieldType("TEXT");
        i.setHasVideo(true);
        i.setSortOrder(1);
        Long itemId = templateRepository.saveItem(i).getId();

        Long ownerId = createUser("owner");
        Long diagnosticId = createUser("diag");
        DiagnosticRequest request = new DiagnosticRequest();
        request.setUserId(ownerId);
        request.setBrand("BMW");
        request.setModel("X5");
        request.setYear(2022);
        request.setVin("P" + (System.nanoTime() % 100000000L));
        request.setDepositFrozen(new BigDecimal("10000"));
        request.setStatus(DiagnosticRequest.DiagnosticRequestStatus.IN_PROGRESS);
        request.setAssignedTo(diagnosticId);
        request.setCreatedAt(OffsetDateTime.now());
        request.setUpdatedAt(OffsetDateTime.now());
        Long requestId = requestRepository.save(request).getId();
        requestRepository.bindTemplateIfUnset(requestId, templateId);
        return new RequestAndItem(requestId, itemId, diagnosticId);
    }

    /** A VIDEO row in the given upload/processing status, as if it just finished upload. */
    private DiagnosticMedia createVideoMedia(RequestAndItem ctx, UploadStatus uploadStatus, ProcessingStatus processingStatus) {
        DiagnosticMedia media = new DiagnosticMedia();
        media.setDiagnosticRequestId(ctx.requestId());
        media.setInspectionItemId(ctx.itemId());
        media.setUploaderId(ctx.uploaderId());
        media.setStorageKey("diagnostic-media/" + ctx.requestId() + "/" + UUID.randomUUID() + ".mp4");
        media.setMediaType(MediaType.VIDEO);
        media.setMimeType("video/mp4");
        media.setSizeBytes(1000L);
        media.setSortOrder(0);
        media.setUploadStatus(uploadStatus);
        media.setProcessingStatus(processingStatus);
        return mediaRepository.save(media);
    }

    /** Test-only: back-dates processing_claimed_at directly, mirroring the established ageMediaCreatedAt pattern. */
    private void ageProcessingClaim(Long mediaId, OffsetDateTime claimedAt) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                entityManager.createNativeQuery("UPDATE diagnostic_media SET processing_claimed_at = ?1 WHERE id = ?2")
                        .setParameter(1, claimedAt)
                        .setParameter(2, mediaId)
                        .executeUpdate());
    }

    // ==================== claimForProcessing ====================

    @Test
    @DisplayName("Две попытки claim по одной записи -> ровно одна успешна")
    void twoClaimsAgainstSameRow_ExactlyOneSucceeds() {
        RequestAndItem ctx = createRequestAndItem();
        DiagnosticMedia media = createVideoMedia(ctx, UploadStatus.READY, ProcessingStatus.PENDING);

        boolean first = mediaRepository.claimForProcessing(media.getId());
        boolean second = mediaRepository.claimForProcessing(media.getId());

        assertThat(first).isTrue();
        assertThat(second).isFalse();
        DiagnosticMedia after = mediaRepository.findById(media.getId()).orElseThrow();
        assertThat(after.getProcessingStatus()).isEqualTo(ProcessingStatus.PROCESSING);
        assertThat(after.getProcessingClaimedAt()).isNotNull();
    }

    @Test
    @DisplayName("PENDING + DELETED -> claim не проходит")
    void pendingButDeleted_ClaimFails() {
        RequestAndItem ctx = createRequestAndItem();
        DiagnosticMedia media = createVideoMedia(ctx, UploadStatus.DELETED, ProcessingStatus.PENDING);

        boolean claimed = mediaRepository.claimForProcessing(media.getId());

        assertThat(claimed).isFalse();
        assertThat(mediaRepository.findById(media.getId()).orElseThrow().getProcessingStatus())
                .isEqualTo(ProcessingStatus.PENDING);
    }

    // ==================== finalizeProcessingReady ====================

    @Test
    @DisplayName("PROCESSING + READY -> finalize успешен")
    void processingAndReady_FinalizeSucceeds() {
        RequestAndItem ctx = createRequestAndItem();
        DiagnosticMedia media = createVideoMedia(ctx, UploadStatus.READY, ProcessingStatus.PROCESSING);

        boolean finalized = mediaRepository.finalizeProcessingReady(media.getId(), 12, 1920, 1080, "diagnostic-media/thumb.jpg");

        assertThat(finalized).isTrue();
        DiagnosticMedia after = mediaRepository.findById(media.getId()).orElseThrow();
        assertThat(after.getProcessingStatus()).isEqualTo(ProcessingStatus.READY);
        assertThat(after.getDurationSeconds()).isEqualTo(12);
        assertThat(after.getWidth()).isEqualTo(1920);
        assertThat(after.getHeight()).isEqualTo(1080);
        assertThat(after.getThumbnailStorageKey()).isEqualTo("diagnostic-media/thumb.jpg");
    }

    @Test
    @DisplayName("PROCESSING + DELETED -> finalize не проходит (удалённая запись не воскрешается зависшим воркером)")
    void processingButDeleted_FinalizeFails() {
        RequestAndItem ctx = createRequestAndItem();
        DiagnosticMedia media = createVideoMedia(ctx, UploadStatus.DELETED, ProcessingStatus.PROCESSING);

        boolean finalized = mediaRepository.finalizeProcessingReady(media.getId(), 12, 1920, 1080, "diagnostic-media/thumb.jpg");

        assertThat(finalized).isFalse();
        DiagnosticMedia after = mediaRepository.findById(media.getId()).orElseThrow();
        assertThat(after.getProcessingStatus()).isEqualTo(ProcessingStatus.PROCESSING); // untouched
        assertThat(after.getThumbnailStorageKey()).isNull();
    }

    // ==================== retryFailedProcessing ====================

    @Test
    @DisplayName("FAILED -> retry успешен, только пока uploadStatus=READY")
    void failedRetry_SucceedsOnlyWhileUploadReady() {
        RequestAndItem ctx = createRequestAndItem();
        DiagnosticMedia media = createVideoMedia(ctx, UploadStatus.READY, ProcessingStatus.FAILED);

        boolean retried = mediaRepository.retryFailedProcessing(media.getId());

        assertThat(retried).isTrue();
        assertThat(mediaRepository.findById(media.getId()).orElseThrow().getProcessingStatus())
                .isEqualTo(ProcessingStatus.PENDING);
    }

    @Test
    @DisplayName("FAILED + DELETED -> retry не проходит")
    void failedButDeleted_RetryFails() {
        RequestAndItem ctx = createRequestAndItem();
        DiagnosticMedia media = createVideoMedia(ctx, UploadStatus.DELETED, ProcessingStatus.FAILED);

        boolean retried = mediaRepository.retryFailedProcessing(media.getId());

        assertThat(retried).isFalse();
        assertThat(mediaRepository.findById(media.getId()).orElseThrow().getProcessingStatus())
                .isEqualTo(ProcessingStatus.FAILED);
    }

    // ==================== markProcessingFailed ====================

    @Test
    @DisplayName("PROCESSING -> FAILED успешен")
    void processing_MarkFailed_Succeeds() {
        RequestAndItem ctx = createRequestAndItem();
        DiagnosticMedia media = createVideoMedia(ctx, UploadStatus.READY, ProcessingStatus.PROCESSING);

        boolean failed = mediaRepository.markProcessingFailed(media.getId());

        assertThat(failed).isTrue();
        assertThat(mediaRepository.findById(media.getId()).orElseThrow().getProcessingStatus())
                .isEqualTo(ProcessingStatus.FAILED);
    }

    // ==================== stale recovery ====================

    @Test
    @DisplayName("Просроченный PROCESSING -> сбрасывается в PENDING")
    void staleProcessing_ResetToPending() {
        RequestAndItem ctx = createRequestAndItem();
        DiagnosticMedia media = createVideoMedia(ctx, UploadStatus.READY, ProcessingStatus.PENDING);
        mediaRepository.claimForProcessing(media.getId());
        ageProcessingClaim(media.getId(), OffsetDateTime.now().minusHours(2));

        List<DiagnosticMedia> stale = mediaRepository.findStaleProcessing(OffsetDateTime.now().minusMinutes(30), 10);
        assertThat(stale).extracting(DiagnosticMedia::getId).contains(media.getId());

        boolean reset = mediaRepository.resetStaleProcessing(media.getId());

        assertThat(reset).isTrue();
        assertThat(mediaRepository.findById(media.getId()).orElseThrow().getProcessingStatus())
                .isEqualTo(ProcessingStatus.PENDING);
    }

    @Test
    @DisplayName("Свежий PROCESSING не считается просроченным")
    void freshProcessing_NotSelectedAsStale() {
        RequestAndItem ctx = createRequestAndItem();
        DiagnosticMedia media = createVideoMedia(ctx, UploadStatus.READY, ProcessingStatus.PENDING);
        mediaRepository.claimForProcessing(media.getId()); // processing_claimed_at = now

        List<DiagnosticMedia> stale = mediaRepository.findStaleProcessing(OffsetDateTime.now().minusMinutes(30), 10);

        assertThat(stale).extracting(DiagnosticMedia::getId).doesNotContain(media.getId());
    }

    @Test
    @DisplayName("findStaleProcessing возвращает не более заданного лимита")
    void findStaleProcessing_RespectsLimit() {
        RequestAndItem ctx = createRequestAndItem();
        for (int i = 0; i < 5; i++) {
            DiagnosticMedia media = createVideoMedia(ctx, UploadStatus.READY, ProcessingStatus.PENDING);
            mediaRepository.claimForProcessing(media.getId());
            ageProcessingClaim(media.getId(), OffsetDateTime.now().minusHours(2));
        }

        List<DiagnosticMedia> stale = mediaRepository.findStaleProcessing(OffsetDateTime.now().minusMinutes(30), 3);

        assertThat(stale).hasSize(3);
        assertThat(stale).allSatisfy(m -> assertThat(m.getProcessingStatus()).isEqualTo(ProcessingStatus.PROCESSING));
    }

    // ==================== findPendingProcessing bounding ====================

    @Test
    @DisplayName("findPendingProcessing возвращает не более заданного лимита")
    void findPendingProcessing_RespectsLimit() {
        RequestAndItem ctx = createRequestAndItem();
        for (int i = 0; i < 5; i++) {
            createVideoMedia(ctx, UploadStatus.READY, ProcessingStatus.PENDING);
        }

        List<DiagnosticMedia> batch = mediaRepository.findPendingProcessing(3);

        assertThat(batch).hasSize(3);
        assertThat(batch).allSatisfy(m -> {
            assertThat(m.getUploadStatus()).isEqualTo(UploadStatus.READY);
            assertThat(m.getProcessingStatus()).isEqualTo(ProcessingStatus.PENDING);
        });
    }

    // ==================== MEDIA-HARDENING-1: declarePendingThumbnail ====================

    @Test
    @DisplayName("declarePendingThumbnail: PROCESSING -> true, ключ сохранён")
    void declarePendingThumbnail_Processing_SucceedsAndPersistsKey() {
        RequestAndItem ctx = createRequestAndItem();
        DiagnosticMedia media = createVideoMedia(ctx, UploadStatus.READY, ProcessingStatus.PROCESSING);

        boolean declared = mediaRepository.declarePendingThumbnail(media.getId(), "diagnostic-media-thumbnail/x/a.jpg");

        assertThat(declared).isTrue();
        assertThat(mediaRepository.findById(media.getId()).orElseThrow().getPendingThumbnailStorageKey())
                .isEqualTo("diagnostic-media-thumbnail/x/a.jpg");
    }

    @Test
    @DisplayName("declarePendingThumbnail: PENDING -> false")
    void declarePendingThumbnail_Pending_Fails() {
        RequestAndItem ctx = createRequestAndItem();
        DiagnosticMedia media = createVideoMedia(ctx, UploadStatus.READY, ProcessingStatus.PENDING);

        boolean declared = mediaRepository.declarePendingThumbnail(media.getId(), "diagnostic-media-thumbnail/x/a.jpg");

        assertThat(declared).isFalse();
        assertThat(mediaRepository.findById(media.getId()).orElseThrow().getPendingThumbnailStorageKey()).isNull();
    }

    @Test
    @DisplayName("declarePendingThumbnail: FAILED -> false")
    void declarePendingThumbnail_Failed_Fails() {
        RequestAndItem ctx = createRequestAndItem();
        DiagnosticMedia media = createVideoMedia(ctx, UploadStatus.READY, ProcessingStatus.FAILED);

        boolean declared = mediaRepository.declarePendingThumbnail(media.getId(), "diagnostic-media-thumbnail/x/a.jpg");

        assertThat(declared).isFalse();
    }

    @Test
    @DisplayName("declarePendingThumbnail: READY -> false")
    void declarePendingThumbnail_Ready_Fails() {
        RequestAndItem ctx = createRequestAndItem();
        DiagnosticMedia media = createVideoMedia(ctx, UploadStatus.READY, ProcessingStatus.READY);

        boolean declared = mediaRepository.declarePendingThumbnail(media.getId(), "diagnostic-media-thumbnail/x/a.jpg");

        assertThat(declared).isFalse();
    }

    // ==================== MEDIA-HARDENING-1: clearPendingThumbnail ====================

    @Test
    @DisplayName("clearPendingThumbnail: совпадающий ключ -> true, маркер NULL")
    void clearPendingThumbnail_MatchingKey_ClearsMarker() {
        RequestAndItem ctx = createRequestAndItem();
        DiagnosticMedia media = createVideoMedia(ctx, UploadStatus.READY, ProcessingStatus.PROCESSING);
        mediaRepository.declarePendingThumbnail(media.getId(), "diagnostic-media-thumbnail/x/a.jpg");

        boolean cleared = mediaRepository.clearPendingThumbnail(media.getId(), "diagnostic-media-thumbnail/x/a.jpg");

        assertThat(cleared).isTrue();
        assertThat(mediaRepository.findById(media.getId()).orElseThrow().getPendingThumbnailStorageKey()).isNull();
    }

    @Test
    @DisplayName("clearPendingThumbnail: несовпадающий ключ -> false, исходный маркер не тронут")
    void clearPendingThumbnail_WrongKey_LeavesMarkerUntouched() {
        RequestAndItem ctx = createRequestAndItem();
        DiagnosticMedia media = createVideoMedia(ctx, UploadStatus.READY, ProcessingStatus.PROCESSING);
        mediaRepository.declarePendingThumbnail(media.getId(), "diagnostic-media-thumbnail/x/a.jpg");

        boolean cleared = mediaRepository.clearPendingThumbnail(media.getId(), "diagnostic-media-thumbnail/x/WRONG.jpg");

        assertThat(cleared).isFalse();
        assertThat(mediaRepository.findById(media.getId()).orElseThrow().getPendingThumbnailStorageKey())
                .isEqualTo("diagnostic-media-thumbnail/x/a.jpg");
    }

    // ==================== MEDIA-HARDENING-1: findPendingThumbnailOrphans ====================

    @Test
    @DisplayName("findPendingThumbnailOrphans: включает PENDING+маркер и FAILED+маркер, исключает PROCESSING+маркер")
    void findPendingThumbnailOrphans_IncludesPendingAndFailed_ExcludesProcessing() {
        RequestAndItem ctx = createRequestAndItem();

        DiagnosticMedia pending = createVideoMedia(ctx, UploadStatus.READY, ProcessingStatus.PROCESSING);
        mediaRepository.declarePendingThumbnail(pending.getId(), "diagnostic-media-thumbnail/x/pending.jpg");
        mediaRepository.resetStaleProcessing(pending.getId()); // -> PENDING, marker survives

        DiagnosticMedia failed = createVideoMedia(ctx, UploadStatus.READY, ProcessingStatus.PROCESSING);
        mediaRepository.declarePendingThumbnail(failed.getId(), "diagnostic-media-thumbnail/x/failed.jpg");
        mediaRepository.markProcessingFailed(failed.getId()); // -> FAILED, marker survives

        DiagnosticMedia stillProcessing = createVideoMedia(ctx, UploadStatus.READY, ProcessingStatus.PROCESSING);
        mediaRepository.declarePendingThumbnail(stillProcessing.getId(), "diagnostic-media-thumbnail/x/active.jpg");

        List<DiagnosticMedia> orphans = mediaRepository.findPendingThumbnailOrphans(50);

        assertThat(orphans).extracting(DiagnosticMedia::getId)
                .contains(pending.getId(), failed.getId())
                .doesNotContain(stillProcessing.getId());
    }

    @Test
    @DisplayName("findPendingThumbnailOrphans: исключает READY с NULL-маркером и финализированную запись")
    void findPendingThumbnailOrphans_ExcludesReadyRows() {
        RequestAndItem ctx = createRequestAndItem();

        DiagnosticMedia readyNoMarker = createVideoMedia(ctx, UploadStatus.READY, ProcessingStatus.READY);

        DiagnosticMedia finalized = createVideoMedia(ctx, UploadStatus.READY, ProcessingStatus.PROCESSING);
        mediaRepository.declarePendingThumbnail(finalized.getId(), "diagnostic-media-thumbnail/x/final.jpg");
        mediaRepository.finalizeProcessingReady(finalized.getId(), 10, 640, 480, "diagnostic-media-thumbnail/x/final.jpg");

        List<DiagnosticMedia> orphans = mediaRepository.findPendingThumbnailOrphans(50);

        assertThat(orphans).extracting(DiagnosticMedia::getId)
                .doesNotContain(readyNoMarker.getId(), finalized.getId());
    }

    // ==================== MEDIA-HARDENING-1: atomic finalization invariant ====================

    @Test
    @DisplayName("finalizeProcessingReady очищает pendingThumbnailStorageKey атомарно вместе с thumbnailStorageKey/READY")
    void finalizeProcessingReady_AtomicallyClearsPendingMarker() {
        RequestAndItem ctx = createRequestAndItem();
        DiagnosticMedia media = createVideoMedia(ctx, UploadStatus.READY, ProcessingStatus.PROCESSING);
        mediaRepository.declarePendingThumbnail(media.getId(), "diagnostic-media-thumbnail/x/candidate.jpg");

        boolean finalized = mediaRepository.finalizeProcessingReady(
                media.getId(), 12, 1920, 1080, "diagnostic-media-thumbnail/x/candidate.jpg");

        assertThat(finalized).isTrue();
        DiagnosticMedia after = mediaRepository.findById(media.getId()).orElseThrow();
        assertThat(after.getProcessingStatus()).isEqualTo(ProcessingStatus.READY);
        assertThat(after.getThumbnailStorageKey()).isEqualTo("diagnostic-media-thumbnail/x/candidate.jpg");
        assertThat(after.getPendingThumbnailStorageKey()).isNull();

        List<DiagnosticMedia> orphans = mediaRepository.findPendingThumbnailOrphans(50);
        assertThat(orphans).extracting(DiagnosticMedia::getId).doesNotContain(media.getId());
    }
}

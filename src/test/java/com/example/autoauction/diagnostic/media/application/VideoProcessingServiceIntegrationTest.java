// diagnostic/media/application/VideoProcessingServiceIntegrationTest.java
package com.example.autoauction.diagnostic.media.application;

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
import com.example.autoauction.diagnostic.media.infrastructure.storage.FakeStorageClient;
import com.example.autoauction.diagnostic.media.infrastructure.video.FakeVideoProcessor;
import com.example.autoauction.user.domain.Role;
import com.example.autoauction.user.domain.User;
import com.example.autoauction.user.domain.port.RoleRepository;
import com.example.autoauction.user.domain.port.UserRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MEDIA-3.4 Phase 6: VideoProcessingService, exercised through the
 * VideoProcessor port with FakeVideoProcessor (no real ffmpeg) and
 * FakeStorageClient (no real R2), matching established conventions.
 *
 * @Transactional (rollback-per-test): processPending()/findPendingProcessing()
 * scan by status across ALL rows, not by ID - same reasoning as
 * DiagnosticMediaRepositoryProcessingTest and LegacyMediaMigrationServiceIntegrationTest.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@DisplayName("MEDIA-3.4: VideoProcessingService")
class VideoProcessingServiceIntegrationTest {

    @Autowired
    private VideoProcessingService videoProcessingService;

    @Autowired
    private DiagnosticMediaService diagnosticMediaService;

    @Autowired
    private DiagnosticMediaRepository mediaRepository;

    @Autowired
    private DiagnosticRequestRepository requestRepository;

    @Autowired
    private InspectionTemplateRepository templateRepository;

    @Autowired
    private FakeStorageClient storageClient;

    @Autowired
    private FakeVideoProcessor videoProcessor;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private static final byte[] VIDEO_BYTES = new byte[]{0x00, 0x00, 0x00, 0x18, 'f', 't', 'y', 'p'};

    /**
     * FakeVideoProcessor is a shared singleton bean, same as FakeStorageClient - its one-shot
     * configureFailure() is only consumed if process() is actually invoked. A test proving the
     * processor is NEVER invoked (nonVideoCandidate_ProcessorNeverInvoked) necessarily leaves a
     * configured failure dangling for whichever test runs next. Resetting to a known-good state
     * before every test avoids inter-test order-dependence without modifying FakeVideoProcessor
     * itself (Phase 6 leaves it untouched, per the approved scope).
     */
    @BeforeEach
    void resetFakeVideoProcessor() {
        videoProcessor.configureFailure(null);
        videoProcessor.configureSuccess(10, 640, 480);
    }

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
        request.setVin("V" + (System.nanoTime() % 100000000L));
        request.setDepositFrozen(new BigDecimal("10000"));
        request.setStatus(DiagnosticRequest.DiagnosticRequestStatus.IN_PROGRESS);
        request.setAssignedTo(diagnosticId);
        request.setCreatedAt(OffsetDateTime.now());
        request.setUpdatedAt(OffsetDateTime.now());
        Long requestId = requestRepository.save(request).getId();
        requestRepository.bindTemplateIfUnset(requestId, templateId);
        return new RequestAndItem(requestId, itemId, diagnosticId);
    }

    /** A VIDEO row uploaded and finalized as READY, ready to be picked up for processing. */
    private DiagnosticMedia createReadyVideo(RequestAndItem ctx) {
        DiagnosticMedia media = new DiagnosticMedia();
        media.setDiagnosticRequestId(ctx.requestId());
        media.setInspectionItemId(ctx.itemId());
        media.setUploaderId(ctx.uploaderId());
        media.setStorageKey("diagnostic-media/" + ctx.requestId() + "/" + UUID.randomUUID() + ".mp4");
        media.setMediaType(MediaType.VIDEO);
        media.setMimeType("video/mp4");
        media.setSizeBytes((long) VIDEO_BYTES.length);
        media.setSortOrder(0);
        media.setUploadStatus(UploadStatus.READY);
        media.setProcessingStatus(ProcessingStatus.PENDING);
        DiagnosticMedia saved = mediaRepository.save(media);
        storageClient.simulateUpload(saved.getStorageKey(), VIDEO_BYTES, "video/mp4");
        return saved;
    }

    /** Test-only: back-dates processing_claimed_at directly, mirroring DiagnosticMediaRepositoryProcessingTest's helper. */
    private void ageProcessingClaim(Long mediaId, OffsetDateTime claimedAt) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                entityManager.createNativeQuery("UPDATE diagnostic_media SET processing_claimed_at = ?1 WHERE id = ?2")
                        .setParameter(1, claimedAt)
                        .setParameter(2, mediaId)
                        .executeUpdate());
    }

    /** True if any leftover video-processing-{mediaId}-* temp directory remains under the JVM temp root. */
    private boolean hasLeftoverTempDir(Long mediaId) throws Exception {
        Path tmpRoot = Path.of(System.getProperty("java.io.tmpdir"));
        String prefix = "video-processing-" + mediaId + "-";
        try (Stream<Path> list = Files.list(tmpRoot)) {
            return list.anyMatch(p -> p.getFileName().toString().startsWith(prefix));
        }
    }

    // ==================== 1: happy path ====================

    @Test
    @DisplayName("1: PENDING VIDEO -> claim -> download -> process -> upload -> verify -> READY, метаданные сохранены")
    void happyPath_ClaimedDownloadedProcessedFinalized() {
        RequestAndItem ctx = createRequestAndItem();
        DiagnosticMedia media = createReadyVideo(ctx);
        videoProcessor.configureSuccess(15, 640, 480);

        videoProcessingService.processPending(10);

        DiagnosticMedia after = mediaRepository.findById(media.getId()).orElseThrow();
        assertThat(after.getProcessingStatus()).isEqualTo(ProcessingStatus.READY);
        assertThat(after.getDurationSeconds()).isEqualTo(15);
        assertThat(after.getWidth()).isEqualTo(640);
        assertThat(after.getHeight()).isEqualTo(480);
        assertThat(after.getThumbnailStorageKey()).isNotNull().startsWith("diagnostic-media-thumbnail/");
        assertThat(storageClient.hasObject(after.getThumbnailStorageKey())).isTrue();
        // MEDIA-HARDENING-1: successful finalization clears the pending marker atomically.
        assertThat(after.getPendingThumbnailStorageKey()).isNull();
    }

    // ==================== 2: claim lost (genuine two-thread race) ====================

    @Test
    @DisplayName("2: гонка двух потоков за одну запись -> ровно один процесс, без дублирования")
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void claimLost_ExactlyOneWorkerProcesses() throws Exception {
        // This one test deliberately opts OUT of the class-level @Transactional: the two worker
        // threads below run their own separate transactions and would never see a row created
        // by the still-open, not-yet-committed outer test transaction otherwise. Writes here
        // commit for real - explicitly cleaned up at the end so no state leaks to other tests.
        RequestAndItem ctx = createRequestAndItem();
        DiagnosticMedia media = createReadyVideo(ctx);
        videoProcessor.configureSuccess(10, 640, 480);
        long baseline = storageClient.objectCount();

        Runnable task = () -> videoProcessingService.processPending(1);
        Thread t1 = new Thread(task);
        Thread t2 = new Thread(task);
        t1.start();
        t2.start();
        t1.join(10_000);
        t2.join(10_000);

        // Whichever thread's claim won, the outcome must be identical either way: exactly one
        // successful processing, never two, never zero.
        DiagnosticMedia after = mediaRepository.findById(media.getId()).orElseThrow();
        assertThat(after.getProcessingStatus()).isEqualTo(ProcessingStatus.READY);
        assertThat(storageClient.objectCount()).isEqualTo(baseline + 1); // exactly one thumbnail added, never two
        mediaRepository.softDelete(media.getId()); // best-effort cleanup since this test bypasses rollback
    }

    // ==================== 3/4: download and processor failures ====================

    @Test
    @DisplayName("3: сбой скачивания -> FAILED, объект миниатюры не остаётся")
    void downloadFailure_MarksFailedNoThumbnailOrphan() {
        RequestAndItem ctx = createRequestAndItem();
        DiagnosticMedia media = createReadyVideo(ctx);
        storageClient.forgetObject(media.getStorageKey()); // simulates missing/failed download
        long baseline = storageClient.objectCount();

        videoProcessingService.processPending(10);

        assertThat(mediaRepository.findById(media.getId()).orElseThrow().getProcessingStatus())
                .isEqualTo(ProcessingStatus.FAILED);
        assertThat(storageClient.objectCount()).isEqualTo(baseline); // no thumbnail ever created
    }

    @Test
    @DisplayName("4: сбой VideoProcessor -> FAILED, объект миниатюры не остаётся")
    void processorFailure_MarksFailedNoThumbnailOrphan() {
        RequestAndItem ctx = createRequestAndItem();
        DiagnosticMedia media = createReadyVideo(ctx);
        videoProcessor.configureFailure(new RuntimeException("simulated processing failure"));
        long baseline = storageClient.objectCount();

        videoProcessingService.processPending(10);

        assertThat(mediaRepository.findById(media.getId()).orElseThrow().getProcessingStatus())
                .isEqualTo(ProcessingStatus.FAILED);
        assertThat(storageClient.objectCount()).isEqualTo(baseline); // no thumbnail ever attempted
    }

    // ==================== 5 (partial - duration/width/height): invalid ProcessingResult ====================

    @Test
    @DisplayName("5a: недопустимая длительность (<=0) -> FAILED, без загрузки миниатюры")
    void invalidDuration_RejectedBeforeUpload() {
        RequestAndItem ctx = createRequestAndItem();
        DiagnosticMedia media = createReadyVideo(ctx);
        videoProcessor.configureSuccess(0, 640, 480);
        long baseline = storageClient.objectCount();

        videoProcessingService.processPending(10);

        assertThat(mediaRepository.findById(media.getId()).orElseThrow().getProcessingStatus())
                .isEqualTo(ProcessingStatus.FAILED);
        assertThat(storageClient.objectCount()).isEqualTo(baseline); // no thumbnail uploaded
    }

    @Test
    @DisplayName("5b: недопустимая ширина (<=0) -> FAILED, без загрузки миниатюры")
    void invalidWidth_RejectedBeforeUpload() {
        RequestAndItem ctx = createRequestAndItem();
        DiagnosticMedia media = createReadyVideo(ctx);
        videoProcessor.configureSuccess(10, 0, 480);
        long baseline = storageClient.objectCount();

        videoProcessingService.processPending(10);

        assertThat(mediaRepository.findById(media.getId()).orElseThrow().getProcessingStatus())
                .isEqualTo(ProcessingStatus.FAILED);
        assertThat(storageClient.objectCount()).isEqualTo(baseline);
    }

    @Test
    @DisplayName("5c: недопустимая высота (<=0) -> FAILED, без загрузки миниатюры")
    void invalidHeight_RejectedBeforeUpload() {
        RequestAndItem ctx = createRequestAndItem();
        DiagnosticMedia media = createReadyVideo(ctx);
        videoProcessor.configureSuccess(10, 640, 0);
        long baseline = storageClient.objectCount();

        videoProcessingService.processPending(10);

        assertThat(mediaRepository.findById(media.getId()).orElseThrow().getProcessingStatus())
                .isEqualTo(ProcessingStatus.FAILED);
        assertThat(storageClient.objectCount()).isEqualTo(baseline);
    }

    @Test
    @DisplayName("5d: длительность превышает сконфигурированный максимум -> FAILED")
    void durationExceedsConfiguredLimit_Rejected() {
        RequestAndItem ctx = createRequestAndItem();
        DiagnosticMedia media = createReadyVideo(ctx);
        videoProcessor.configureSuccess(99999, 640, 480); // far beyond the 120s ceiling
        long baseline = storageClient.objectCount();

        videoProcessingService.processPending(10);

        assertThat(mediaRepository.findById(media.getId()).orElseThrow().getProcessingStatus())
                .isEqualTo(ProcessingStatus.FAILED);
        assertThat(storageClient.objectCount()).isEqualTo(baseline);
    }

    // ==================== 6/7: thumbnail upload/verification failures ====================

    @Test
    @DisplayName("6: сбой загрузки миниатюры -> FAILED, без orphan-объекта")
    void thumbnailUploadFailure_MarksFailedNoOrphan() {
        RequestAndItem ctx = createRequestAndItem();
        DiagnosticMedia media = createReadyVideo(ctx);
        videoProcessor.configureSuccess(10, 640, 480);
        storageClient.failNextPutObject();
        long baseline = storageClient.objectCount();

        videoProcessingService.processPending(10);

        DiagnosticMedia after = mediaRepository.findById(media.getId()).orElseThrow();
        assertThat(after.getProcessingStatus()).isEqualTo(ProcessingStatus.FAILED);
        assertThat(storageClient.objectCount()).isEqualTo(baseline); // no thumbnail actually stored
        // MEDIA-HARDENING-1: the marker was declared BEFORE the failed putObject and is
        // deliberately NOT cleared here - no delete ever ran, so clearing it would make the
        // marker inconsistent if a same-keyed object somehow existed. It is left for the orphan
        // sweep, whose delete of this (almost certainly never-created) object is a safe no-op.
        assertThat(after.getPendingThumbnailStorageKey()).isNotNull();
    }

    @Test
    @DisplayName("7: сбой верификации миниатюры -> FAILED, компенсирующее удаление выполнено")
    void thumbnailVerificationFailure_CompensatingDeleteAttempted() {
        RequestAndItem ctx = createRequestAndItem();
        DiagnosticMedia media = createReadyVideo(ctx);
        videoProcessor.configureSuccess(10, 640, 480);
        storageClient.failNextHeadObject();
        long baseline = storageClient.objectCount();

        videoProcessingService.processPending(10);

        DiagnosticMedia after = mediaRepository.findById(media.getId()).orElseThrow();
        assertThat(after.getProcessingStatus()).isEqualTo(ProcessingStatus.FAILED);
        assertThat(storageClient.objectCount()).isEqualTo(baseline); // thumbnail was deleted after verification failed
        // MEDIA-HARDENING-1: compensatingDelete() clears the marker only after the delete succeeds.
        assertThat(after.getPendingThumbnailStorageKey()).isNull();
    }

    // ==================== 8: DB finalization returns false (real race via soft-delete) ====================

    @Test
    @DisplayName("8: заявка soft-deleted между claim и finalize -> результат не публикуется, миниатюра удаляется")
    void deletedMidProcessing_FinalizeReturnsFalse_ResultDiscarded() {
        RequestAndItem ctx = createRequestAndItem();
        DiagnosticMedia media = createReadyVideo(ctx);
        videoProcessor.configureSuccess(10, 640, 480);
        // Simulates a delete racing in between claim and finalize: soft-delete right now,
        // BEFORE processPending() runs at all, so finalizeProcessingReady's own
        // uploadStatus=READY guard is what rejects it - the same guard that would catch a
        // delete arriving mid-flight in production.
        mediaRepository.softDelete(media.getId());

        videoProcessingService.processPending(10);

        DiagnosticMedia after = mediaRepository.findById(media.getId()).orElseThrow();
        assertThat(after.getUploadStatus()).isEqualTo(UploadStatus.DELETED);
        assertThat(after.getThumbnailStorageKey()).isNull(); // metadata never published
        assertThat(after.getProcessingStatus()).isNotEqualTo(ProcessingStatus.READY);
    }

    // ==================== 11: non-video invariant ====================

    @Test
    @DisplayName("11: не-VIDEO кандидат не может достичь VideoProcessor (защитная проверка типа)")
    void nonVideoCandidate_ProcessorNeverInvoked() {
        // PHOTO media is never processingStatus=PENDING under normal invariants (createIntent
        // always sets NOT_REQUIRED for photos) - directly construct this otherwise-impossible
        // state to prove the service's own defensive mediaType check, not just the query filter.
        RequestAndItem ctx = createRequestAndItem();
        DiagnosticMedia photo = new DiagnosticMedia();
        photo.setDiagnosticRequestId(ctx.requestId());
        photo.setInspectionItemId(ctx.itemId());
        photo.setUploaderId(ctx.uploaderId());
        photo.setStorageKey("diagnostic-media/" + ctx.requestId() + "/" + UUID.randomUUID() + ".jpg");
        photo.setMediaType(MediaType.PHOTO);
        photo.setMimeType("image/jpeg");
        photo.setSizeBytes(100L);
        photo.setSortOrder(0);
        photo.setUploadStatus(UploadStatus.READY);
        photo.setProcessingStatus(ProcessingStatus.PENDING); // otherwise-impossible for a real PHOTO
        DiagnosticMedia saved = mediaRepository.save(photo);
        videoProcessor.configureFailure(new RuntimeException("must never be invoked for a PHOTO"));

        videoProcessingService.processPending(10);

        DiagnosticMedia after = mediaRepository.findById(saved.getId()).orElseThrow();
        assertThat(after.getProcessingStatus()).isEqualTo(ProcessingStatus.PENDING); // untouched - skipped without claiming
    }

    // ==================== 13/14: batch isolation and boundedness ====================

    @Test
    @DisplayName("13: первый кандидат в партии проваливается, второй всё равно обрабатывается")
    void batchIsolation_FirstFailureDoesNotBlockSecond() {
        RequestAndItem ctx = createRequestAndItem();
        DiagnosticMedia failing = createReadyVideo(ctx);
        DiagnosticMedia ok = createReadyVideo(ctx);
        storageClient.forgetObject(failing.getStorageKey()); // first candidate's download will fail
        videoProcessor.configureSuccess(10, 640, 480);

        videoProcessingService.processPending(10);

        assertThat(mediaRepository.findById(failing.getId()).orElseThrow().getProcessingStatus())
                .isEqualTo(ProcessingStatus.FAILED);
        assertThat(mediaRepository.findById(ok.getId()).orElseThrow().getProcessingStatus())
                .isEqualTo(ProcessingStatus.READY);
    }

    @Test
    @DisplayName("14: кандидатов больше, чем batchSize -> обрабатывается ровно batchSize")
    void batchBoundedness_OnlyBatchSizeProcessed() {
        RequestAndItem ctx = createRequestAndItem();
        for (int i = 0; i < 5; i++) {
            createReadyVideo(ctx);
        }
        videoProcessor.configureSuccess(10, 640, 480);

        videoProcessingService.processPending(2);

        long readyCount = mediaRepository.findByRequest(ctx.requestId()).stream()
                .filter(m -> m.getProcessingStatus() == ProcessingStatus.READY)
                .count();
        assertThat(readyCount).isEqualTo(2);
    }

    // ==================== 10: temp directory cleanup ====================

    @Test
    @DisplayName("10: временная директория полностью удаляется и при успехе, и при сбое")
    void tempDirectoryCleanup_SuccessAndFailurePaths() throws Exception {
        RequestAndItem ctx = createRequestAndItem();
        DiagnosticMedia success = createReadyVideo(ctx);
        DiagnosticMedia failure = createReadyVideo(ctx);
        storageClient.forgetObject(failure.getStorageKey());
        videoProcessor.configureSuccess(10, 640, 480);

        videoProcessingService.processPending(10);

        assertThat(mediaRepository.findById(success.getId()).orElseThrow().getProcessingStatus())
                .isEqualTo(ProcessingStatus.READY);
        assertThat(mediaRepository.findById(failure.getId()).orElseThrow().getProcessingStatus())
                .isEqualTo(ProcessingStatus.FAILED);
        assertThat(hasLeftoverTempDir(success.getId())).isFalse();
        assertThat(hasLeftoverTempDir(failure.getId())).isFalse();
    }

    // ==================== 12: existing read behavior unaffected ====================

    @Test
    @DisplayName("12: processingStatus PENDING/PROCESSING/FAILED не блокирует чтение медиа (read URL)")
    void processingStatusNeverBlocksMediaRead() {
        RequestAndItem ctx = createRequestAndItem();

        DiagnosticMedia pending = createReadyVideo(ctx);
        DiagnosticMedia processing = createReadyVideo(ctx);
        mediaRepository.claimForProcessing(processing.getId());
        DiagnosticMedia failed = createReadyVideo(ctx);
        mediaRepository.claimForProcessing(failed.getId());
        mediaRepository.markProcessingFailed(failed.getId());

        // uploadStatus=READY is untouched by any of the above - this is the pre-existing,
        // unmodified read-path contract (DiagnosticMediaService.signedReadUrl) being re-verified,
        // not a new behavior added by this phase.
        assertThat(diagnosticMediaService.getReadUrl(pending.getId(), ctx.uploaderId(), true)).isNotNull();
        assertThat(diagnosticMediaService.getReadUrl(processing.getId(), ctx.uploaderId(), true)).isNotNull();
        assertThat(diagnosticMediaService.getReadUrl(failed.getId(), ctx.uploaderId(), true)).isNotNull();
    }

    // ==================== 15: independent storage failures ====================

    @Test
    @DisplayName("15: сбой скачивания и сбой загрузки миниатюры обрабатываются независимо друг от друга")
    void downloadAndUploadFailures_HandledIndependently() {
        RequestAndItem ctx = createRequestAndItem();
        DiagnosticMedia downloadFails = createReadyVideo(ctx);
        DiagnosticMedia uploadFails = createReadyVideo(ctx);
        storageClient.forgetObject(downloadFails.getStorageKey());
        videoProcessor.configureSuccess(10, 640, 480);
        long baseline = storageClient.objectCount();

        videoProcessingService.processPending(1); // processes downloadFails first (created first, oldest-first ordering)

        assertThat(mediaRepository.findById(downloadFails.getId()).orElseThrow().getProcessingStatus())
                .isEqualTo(ProcessingStatus.FAILED);

        storageClient.failNextPutObject();
        videoProcessingService.processPending(1); // now processes uploadFails

        assertThat(mediaRepository.findById(uploadFails.getId()).orElseThrow().getProcessingStatus())
                .isEqualTo(ProcessingStatus.FAILED);
        assertThat(storageClient.objectCount()).isEqualTo(baseline); // no thumbnails, either way
    }

    // ==================== Phase 7: stale PROCESSING recovery ====================

    @Test
    @DisplayName("Stale-1: просроченный PROCESSING -> сбрасывается в PENDING")
    void reconcileStale_ResetsOldClaim() {
        RequestAndItem ctx = createRequestAndItem();
        DiagnosticMedia media = createReadyVideo(ctx);
        mediaRepository.claimForProcessing(media.getId());
        ageProcessingClaim(media.getId(), OffsetDateTime.now().minusHours(2));

        videoProcessingService.reconcileStale(10);

        assertThat(mediaRepository.findById(media.getId()).orElseThrow().getProcessingStatus())
                .isEqualTo(ProcessingStatus.PENDING);
    }

    @Test
    @DisplayName("Stale-2: свежий PROCESSING остаётся нетронутым")
    void reconcileStale_FreshClaimUntouched() {
        RequestAndItem ctx = createRequestAndItem();
        DiagnosticMedia media = createReadyVideo(ctx);
        mediaRepository.claimForProcessing(media.getId()); // processing_claimed_at = now

        videoProcessingService.reconcileStale(10);

        assertThat(mediaRepository.findById(media.getId()).orElseThrow().getProcessingStatus())
                .isEqualTo(ProcessingStatus.PROCESSING);
    }

    @Test
    @DisplayName("Stale-3: FAILED никогда не сбрасывается автоматически, даже с устаревшим claim")
    void reconcileStale_FailedRowNeverAutoReset() {
        RequestAndItem ctx = createRequestAndItem();
        DiagnosticMedia media = createReadyVideo(ctx);
        mediaRepository.claimForProcessing(media.getId());
        mediaRepository.markProcessingFailed(media.getId());
        ageProcessingClaim(media.getId(), OffsetDateTime.now().minusHours(2));

        videoProcessingService.reconcileStale(10);

        assertThat(mediaRepository.findById(media.getId()).orElseThrow().getProcessingStatus())
                .isEqualTo(ProcessingStatus.FAILED);
    }

    @Test
    @DisplayName("Stale-4: soft-deleted во время PROCESSING -> uploadStatus остаётся DELETED, " +
            "запись никогда больше не подбирается для обработки")
    void reconcileStale_DeletedRowNeverResurrected() {
        RequestAndItem ctx = createRequestAndItem();
        DiagnosticMedia media = createReadyVideo(ctx);
        mediaRepository.claimForProcessing(media.getId());
        mediaRepository.softDelete(media.getId()); // uploadStatus -> DELETED; processingStatus untouched, stays PROCESSING
        ageProcessingClaim(media.getId(), OffsetDateTime.now().minusHours(2));

        videoProcessingService.reconcileStale(10);
        // Regardless of what processingStatus settles to, uploadStatus must never change, and the
        // row must never again be selectable by processPending() (which requires uploadStatus=READY).
        DiagnosticMedia after = mediaRepository.findById(media.getId()).orElseThrow();
        assertThat(after.getUploadStatus()).isEqualTo(UploadStatus.DELETED);

        videoProcessingService.processPending(10);
        assertThat(mediaRepository.findById(media.getId()).orElseThrow().getUploadStatus())
                .isEqualTo(UploadStatus.DELETED); // never resurrected, never (re)claimed for processing
    }

    @Test
    @DisplayName("Stale-5: несколько устаревших записей -> обрабатывается только ограниченная партия")
    void reconcileStale_BoundedBatchOnly() {
        RequestAndItem ctx = createRequestAndItem();
        for (int i = 0; i < 5; i++) {
            DiagnosticMedia media = createReadyVideo(ctx);
            mediaRepository.claimForProcessing(media.getId());
            ageProcessingClaim(media.getId(), OffsetDateTime.now().minusHours(2));
        }

        videoProcessingService.reconcileStale(3);

        long pendingCount = mediaRepository.findByRequest(ctx.requestId()).stream()
                .filter(m -> m.getProcessingStatus() == ProcessingStatus.PENDING)
                .count();
        assertThat(pendingCount).isEqualTo(3);
    }

    @Test
    @DisplayName("Stale-6: гонка - кандидат успевает завершиться между чтением и сбросом -> reset возвращает false, без ошибки")
    void reconcileStale_RaceWithCompletion_NoErrorZeroRowsAffected() {
        RequestAndItem ctx = createRequestAndItem();
        DiagnosticMedia media = createReadyVideo(ctx);
        mediaRepository.claimForProcessing(media.getId());
        ageProcessingClaim(media.getId(), OffsetDateTime.now().minusHours(2));

        List<DiagnosticMedia> staleCandidates = mediaRepository.findStaleProcessing(OffsetDateTime.now().minusMinutes(30), 10);
        assertThat(staleCandidates).extracting(DiagnosticMedia::getId).contains(media.getId());

        // Simulates the worker finishing normally in the window between the stale SELECT and the
        // reset UPDATE: the row legitimately leaves PROCESSING first.
        mediaRepository.finalizeProcessingReady(media.getId(), 10, 640, 480, "diagnostic-media-thumbnail/x/y.jpg");

        boolean reset = mediaRepository.resetStaleProcessing(media.getId()); // must not throw

        assertThat(reset).isFalse();
        assertThat(mediaRepository.findById(media.getId()).orElseThrow().getProcessingStatus())
                .isEqualTo(ProcessingStatus.READY); // untouched by the no-op reset
    }

    // ==================== MEDIA-HARDENING-1: pending-thumbnail marker lifecycle ====================

    @Test
    @DisplayName("HARDENING-B: PROCESSING с объявленным pending-маркером и загруженным объектом, финализация " +
            "НЕ произошла -> stale recovery -> reconcilePendingThumbnailOrphans удаляет объект и очищает маркер")
    void crashWindowRecovery_OrphanedThumbnailDeletedMarkerClearedAfterStaleRecovery() {
        RequestAndItem ctx = createRequestAndItem();
        DiagnosticMedia media = createReadyVideo(ctx);
        mediaRepository.claimForProcessing(media.getId());
        String candidateKey = "diagnostic-media-thumbnail/" + ctx.requestId() + "/" + UUID.randomUUID() + ".jpg";

        boolean declared = mediaRepository.declarePendingThumbnail(media.getId(), candidateKey);
        assertThat(declared).isTrue();
        storageClient.simulateUpload(candidateKey, VIDEO_BYTES, "image/jpeg"); // upload succeeded before the simulated crash
        // finalizeProcessingReady() is deliberately never called - this IS the crash window.

        mediaRepository.resetStaleProcessing(media.getId()); // simulated crash -> stale-claim recovery, PROCESSING -> PENDING
        assertThat(storageClient.hasObject(candidateKey)).isTrue(); // still orphaned before the sweep runs

        videoProcessingService.reconcilePendingThumbnailOrphans(10);

        DiagnosticMedia after = mediaRepository.findById(media.getId()).orElseThrow();
        assertThat(storageClient.hasObject(candidateKey)).isFalse();
        assertThat(after.getPendingThumbnailStorageKey()).isNull();
        assertThat(after.getProcessingStatus()).isEqualTo(ProcessingStatus.PENDING); // untouched by the sweep
        assertThat(after.getThumbnailStorageKey()).isNull(); // never finalized
    }

    @Test
    @DisplayName("HARDENING-D: более новый ключ перекрывает маркер - устаревшая реконсиляция по старому ключу " +
            "удаляет старый объект, но не может очистить маркер новой попытки, объект новой попытки остаётся")
    void expectedKeyRace_OlderReconciliationCannotClearNewerMarker() {
        RequestAndItem ctx = createRequestAndItem();
        DiagnosticMedia media = createReadyVideo(ctx);
        mediaRepository.claimForProcessing(media.getId());
        String keyA = "diagnostic-media-thumbnail/" + ctx.requestId() + "/" + UUID.randomUUID() + "-a.jpg";
        mediaRepository.declarePendingThumbnail(media.getId(), keyA);
        storageClient.simulateUpload(keyA, VIDEO_BYTES, "image/jpeg");
        mediaRepository.resetStaleProcessing(media.getId()); // attempt A crashes -> PENDING, marker=A

        // A newer attempt legitimately re-claims the same PENDING row and overwrites the marker
        // with its own candidate key B - this must never be blocked by attempt A's stale marker
        // (declarePendingThumbnail() is deliberately not guarded by "pending key currently null").
        mediaRepository.claimForProcessing(media.getId());
        String keyB = "diagnostic-media-thumbnail/" + ctx.requestId() + "/" + UUID.randomUUID() + "-b.jpg";
        mediaRepository.declarePendingThumbnail(media.getId(), keyB);
        storageClient.simulateUpload(keyB, VIDEO_BYTES, "image/jpeg");

        // A delayed reconciliation cycle for the now-stale keyA candidate (read before attempt B
        // overwrote the marker) runs the exact same two steps
        // reconcilePendingThumbnailOrphans() performs internally: unconditional delete of the
        // object it read, then the expected-key-guarded clear - reproducing the interleaving
        // that a single synchronous test cannot otherwise induce inside the real service call.
        storageClient.deleteObject(keyA);
        boolean cleared = mediaRepository.clearPendingThumbnail(media.getId(), keyA);

        assertThat(cleared).isFalse();
        assertThat(storageClient.hasObject(keyA)).isFalse(); // stale object A correctly deleted
        assertThat(storageClient.hasObject(keyB)).isTrue(); // newer object B untouched
        assertThat(mediaRepository.findById(media.getId()).orElseThrow().getPendingThumbnailStorageKey())
                .isEqualTo(keyB); // newer marker survives the stale reconciliation attempt
    }

    @Test
    @DisplayName("HARDENING-G: несколько pending-thumbnail orphan-кандидатов -> обрабатывается только ограниченная партия")
    void reconcilePendingThumbnailOrphans_BoundedBatchOnly() {
        RequestAndItem ctx = createRequestAndItem();
        for (int i = 0; i < 5; i++) {
            DiagnosticMedia media = createReadyVideo(ctx);
            mediaRepository.claimForProcessing(media.getId());
            String key = "diagnostic-media-thumbnail/" + ctx.requestId() + "/" + UUID.randomUUID() + ".jpg";
            mediaRepository.declarePendingThumbnail(media.getId(), key);
            storageClient.simulateUpload(key, VIDEO_BYTES, "image/jpeg");
            mediaRepository.resetStaleProcessing(media.getId());
        }

        videoProcessingService.reconcilePendingThumbnailOrphans(3);

        long stillPendingMarker = mediaRepository.findByRequest(ctx.requestId()).stream()
                .filter(m -> m.getPendingThumbnailStorageKey() != null)
                .count();
        assertThat(stillPendingMarker).isEqualTo(2); // exactly batchSize (3) cleared, the rest untouched this cycle
    }

    @Test
    @DisplayName("HARDENING-H: успешно финализированная запись не возвращается orphan-разверткой, миниатюра остаётся нетронутой")
    void reconcilePendingThumbnailOrphans_NeverMutatesFinalizedRow() {
        RequestAndItem ctx = createRequestAndItem();
        DiagnosticMedia media = createReadyVideo(ctx);
        videoProcessor.configureSuccess(10, 640, 480);

        videoProcessingService.processPending(10);

        DiagnosticMedia finalized = mediaRepository.findById(media.getId()).orElseThrow();
        assertThat(finalized.getProcessingStatus()).isEqualTo(ProcessingStatus.READY);
        assertThat(finalized.getPendingThumbnailStorageKey()).isNull();
        String thumbnailKey = finalized.getThumbnailStorageKey();
        assertThat(storageClient.hasObject(thumbnailKey)).isTrue();

        videoProcessingService.reconcilePendingThumbnailOrphans(10);

        DiagnosticMedia after = mediaRepository.findById(media.getId()).orElseThrow();
        assertThat(after.getProcessingStatus()).isEqualTo(ProcessingStatus.READY);
        assertThat(after.getThumbnailStorageKey()).isEqualTo(thumbnailKey);
        assertThat(after.getPendingThumbnailStorageKey()).isNull();
        assertThat(storageClient.hasObject(thumbnailKey)).isTrue(); // never touched by the orphan sweep
    }
}

// diagnostic/media/application/LegacyMediaMigrationServiceIntegrationTest.java
package com.example.autoauction.diagnostic.media.application;

import com.example.autoauction.common.media.domain.MediaAsset;
import com.example.autoauction.common.media.domain.port.MediaAssetRepository;
import com.example.autoauction.diagnostic.domain.DiagnosticRequest;
import com.example.autoauction.diagnostic.domain.InspectionAnswer;
import com.example.autoauction.diagnostic.domain.InspectionGroup;
import com.example.autoauction.diagnostic.domain.InspectionItem;
import com.example.autoauction.diagnostic.domain.InspectionTemplate;
import com.example.autoauction.diagnostic.domain.port.DiagnosticRequestRepository;
import com.example.autoauction.diagnostic.domain.port.InspectionAnswerRepository;
import com.example.autoauction.diagnostic.domain.port.InspectionTemplateRepository;
import com.example.autoauction.diagnostic.media.domain.DiagnosticMedia;
import com.example.autoauction.diagnostic.media.domain.MediaType;
import com.example.autoauction.diagnostic.media.domain.ProcessingStatus;
import com.example.autoauction.diagnostic.media.domain.UploadStatus;
import com.example.autoauction.diagnostic.media.domain.port.DiagnosticMediaRepository;
import com.example.autoauction.diagnostic.media.infrastructure.storage.FakeStorageClient;
import com.example.autoauction.diagnostic.media.web.dto.LegacyMediaMigrationResult;
import com.example.autoauction.user.domain.Role;
import com.example.autoauction.user.domain.User;
import com.example.autoauction.user.domain.port.RoleRepository;
import com.example.autoauction.user.domain.port.UserRepository;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MEDIA-3.3, Phase 9: real DB + FakeStorageClient + real local filesystem
 * proof of LegacyMediaMigrationService's scan/migrate/skip/fail algorithm.
 * Legacy files are written into media.upload.dir (target/test-uploads,
 * per application-test.properties) exactly as production legacy uploads
 * would be, and referenced by legacy URLs of the form
 * {media.base-url}/{filename} - the same shape LegacyMediaMigrationService
 * expects to resolve.
 *
 * @Transactional (rollback-per-test): unlike DiagnosticMediaService/
 * MediaReconciliationService methods, migrate() has no requestId/mediaId
 * scoping - it sweeps every COMPLETED/APPROVED DiagnosticRequest in the
 * whole DB by design (Phase 10's one-shot global migration). Without a
 * rollback boundary, leftover rows from one test method would corrupt the
 * next test's exact-count assertions on the returned LegacyMediaMigrationResult.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@DisplayName("MEDIA-3.3: LegacyMediaMigrationService")
class LegacyMediaMigrationServiceIntegrationTest {

    @Autowired
    private LegacyMediaMigrationService migrationService;

    @Autowired
    private DiagnosticMediaRepository mediaRepository;

    @Autowired
    private DiagnosticRequestRepository requestRepository;

    @Autowired
    private InspectionAnswerRepository answerRepository;

    @Autowired
    private InspectionTemplateRepository templateRepository;

    @Autowired
    private MediaAssetRepository mediaAssetRepository;

    @Autowired
    private FakeStorageClient storageClient;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RoleRepository roleRepository;

    @Value("${media.upload.dir}")
    private String uploadDirProp;

    @Value("${media.base-url}")
    private String mediaBaseUrl;

    private static final byte[] VALID_JPEG = new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0x01, 0x02, 0x03};
    private static final byte[] VALID_PNG = new byte[]{
            (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x01, 0x02};
    private static final byte[] VALID_MP4 = new byte[]{0x00, 0x00, 0x00, 0x18, 'f', 't', 'y', 'p', 0x01};
    private static final byte[] INVALID_CONTENT = "not a real image".getBytes(StandardCharsets.UTF_8);

    private Path uploadDir() {
        return Path.of(uploadDirProp).toAbsolutePath().normalize();
    }

    private Long createUser(String prefix) {
        Role userRole = roleRepository.findByName("ROLE_USER")
                .orElseGet(() -> roleRepository.save(new Role(null, "ROLE_USER", "Обычный пользователь")));
        User u = new User(null, prefix + System.nanoTime(), prefix + System.nanoTime() + "@test.com",
                "encoded", true, Set.of(userRole));
        return userRepository.save(u).getId();
    }

    private Long createTemplate() {
        InspectionTemplate t = new InspectionTemplate();
        t.setName("T-" + System.nanoTime());
        t.setActive(true);
        return templateRepository.saveTemplate(t).getId();
    }

    private Long createItem(Long templateId) {
        InspectionGroup g = new InspectionGroup();
        g.setTemplateId(templateId);
        g.setName("G-" + System.nanoTime());
        g.setSortOrder(1);
        Long groupId = templateRepository.saveGroup(g).getId();

        InspectionItem i = new InspectionItem();
        i.setGroupId(groupId);
        i.setTitle("I-" + System.nanoTime());
        i.setFieldType("TEXT");
        i.setHasPhoto(true);
        i.setHasVideo(true);
        i.setSortOrder(1);
        return templateRepository.saveItem(i).getId();
    }

    /**
     * A request already in the given status, optionally bound to a template. Despite the name
     * (kept for minimal diff churn), the status passed is NOT always migration-eligible - it is
     * used both for COMPLETED/APPROVED (in scope) and IN_PROGRESS/REJECTED (deliberately out of
     * scope, exercised by the tests that prove those are excluded).
     */
    private Long createTerminalRequest(Long templateId, DiagnosticRequest.DiagnosticRequestStatus status) {
        Long ownerId = createUser("owner");
        Long diagnosticId = createUser("diag");
        DiagnosticRequest request = new DiagnosticRequest();
        request.setUserId(ownerId);
        request.setBrand("BMW");
        request.setModel("X5");
        request.setYear(2022);
        request.setVin("L" + (System.nanoTime() % 100000000L));
        request.setDepositFrozen(new BigDecimal("10000"));
        request.setStatus(status);
        request.setAssignedTo(diagnosticId);
        request.setCreatedAt(OffsetDateTime.now());
        request.setUpdatedAt(OffsetDateTime.now());
        Long requestId = requestRepository.save(request).getId();
        if (templateId != null) {
            requestRepository.bindTemplateIfUnset(requestId, templateId);
        }
        return requestId;
    }

    private InspectionAnswer createAnswer(Long requestId, Long itemId, List<String> photoUrls, List<String> videoUrls) {
        InspectionAnswer answer = new InspectionAnswer();
        answer.setDiagnosticRequestId(requestId);
        answer.setInspectionItemId(itemId);
        answer.setPhotoUrls(photoUrls);
        answer.setVideoUrls(videoUrls);
        answer.setCreatedAt(OffsetDateTime.now());
        answer.setUpdatedAt(OffsetDateTime.now());
        return answerRepository.save(answer);
    }

    /** Writes a legacy file into media.upload.dir and returns its full legacy URL. */
    private String writeLegacyFile(byte[] content) throws IOException {
        Files.createDirectories(uploadDir());
        String filename = UUID.randomUUID() + ".bin";
        Files.write(uploadDir().resolve(filename), content);
        return mediaBaseUrl + "/" + filename;
    }

    private String legacyFilenameOf(String legacyUrl) {
        return legacyUrl.substring(mediaBaseUrl.length() + 1);
    }

    private void registerUploader(String legacyUrl, Long uploaderId) {
        mediaAssetRepository.save(new MediaAsset(null, legacyFilenameOf(legacyUrl), uploaderId, OffsetDateTime.now()));
    }

    private List<DiagnosticMedia> canonicalFor(Long requestId, Long itemId) {
        return mediaRepository.findByRequestAndItem(requestId, itemId).stream()
                .filter(m -> m.getStorageKey().startsWith("diagnostic-media-legacy/"))
                .toList();
    }

    // ==================== A-C: successful migration of each media type ====================

    @Test
    @DisplayName("A: одна легаси-фотография (JPEG) мигрирует в канонический DiagnosticMedia READY")
    void singleJpeg_MigratesToCanonicalReady() throws IOException {
        Long templateId = createTemplate();
        Long itemId = createItem(templateId);
        Long requestId = createTerminalRequest(templateId, DiagnosticRequest.DiagnosticRequestStatus.COMPLETED);
        String legacyUrl = writeLegacyFile(VALID_JPEG);
        registerUploader(legacyUrl, createUser("uploader"));
        createAnswer(requestId, itemId, List.of(legacyUrl), null);

        LegacyMediaMigrationResult result = migrationService.migrate();

        assertThat(result.migrated()).isEqualTo(1);
        assertThat(result.failedByReason()).isEmpty();
        List<DiagnosticMedia> canonical = canonicalFor(requestId, itemId);
        assertThat(canonical).hasSize(1);
        DiagnosticMedia media = canonical.get(0);
        assertThat(media.getUploadStatus()).isEqualTo(UploadStatus.READY);
        assertThat(media.getProcessingStatus()).isEqualTo(ProcessingStatus.NOT_REQUIRED);
        assertThat(media.getMediaType()).isEqualTo(MediaType.PHOTO);
        assertThat(media.getMimeType()).isEqualTo("image/jpeg");
        assertThat(media.getSortOrder()).isZero();
        assertThat(storageClient.hasObject(media.getStorageKey())).isTrue();
    }

    @Test
    @DisplayName("B: одна легаси-фотография (PNG) мигрирует корректно")
    void singlePng_MigratesToCanonicalReady() throws IOException {
        Long templateId = createTemplate();
        Long itemId = createItem(templateId);
        Long requestId = createTerminalRequest(templateId, DiagnosticRequest.DiagnosticRequestStatus.APPROVED);
        String legacyUrl = writeLegacyFile(VALID_PNG);
        registerUploader(legacyUrl, createUser("uploader"));
        createAnswer(requestId, itemId, List.of(legacyUrl), null);

        LegacyMediaMigrationResult result = migrationService.migrate();

        assertThat(result.migrated()).isEqualTo(1);
        DiagnosticMedia media = canonicalFor(requestId, itemId).get(0);
        assertThat(media.getMimeType()).isEqualTo("image/png");
        assertThat(media.getUploadStatus()).isEqualTo(UploadStatus.READY);
    }

    @Test
    @DisplayName("C: легаси-видео (MP4) мигрирует в READY + ProcessingStatus.PENDING")
    void mp4Video_MigratesReadyWithProcessingPending() throws IOException {
        Long templateId = createTemplate();
        Long itemId = createItem(templateId);
        Long requestId = createTerminalRequest(templateId, DiagnosticRequest.DiagnosticRequestStatus.COMPLETED);
        String legacyUrl = writeLegacyFile(VALID_MP4);
        registerUploader(legacyUrl, createUser("uploader"));
        createAnswer(requestId, itemId, null, List.of(legacyUrl));

        LegacyMediaMigrationResult result = migrationService.migrate();

        assertThat(result.migrated()).isEqualTo(1);
        DiagnosticMedia media = canonicalFor(requestId, itemId).get(0);
        assertThat(media.getMediaType()).isEqualTo(MediaType.VIDEO);
        assertThat(media.getMimeType()).isEqualTo("video/mp4");
        assertThat(media.getUploadStatus()).isEqualTo(UploadStatus.READY);
        assertThat(media.getProcessingStatus()).isEqualTo(ProcessingStatus.PENDING);
    }

    // ==================== D-G: failures/skips never fabricate a canonical row ====================

    @Test
    @DisplayName("D: отсутствующий локальный файл -> нет канонической записи, причина MISSING_LEGACY_FILE")
    void missingLocalFile_NoRowCreated() {
        Long templateId = createTemplate();
        Long itemId = createItem(templateId);
        Long requestId = createTerminalRequest(templateId, DiagnosticRequest.DiagnosticRequestStatus.COMPLETED);
        String legacyUrl = mediaBaseUrl + "/" + UUID.randomUUID() + ".jpg"; // never written to disk
        createAnswer(requestId, itemId, List.of(legacyUrl), null);

        LegacyMediaMigrationResult result = migrationService.migrate();

        assertThat(result.migrated()).isZero();
        assertThat(result.failedByReason()).containsEntry("MISSING_LEGACY_FILE", 1);
        assertThat(canonicalFor(requestId, itemId)).isEmpty();
    }

    @Test
    @DisplayName("E: повреждённый/нераспознанный контент -> нет записи, причина INVALID_MEDIA_CONTENT")
    void invalidContent_NoRowCreated() throws IOException {
        Long templateId = createTemplate();
        Long itemId = createItem(templateId);
        Long requestId = createTerminalRequest(templateId, DiagnosticRequest.DiagnosticRequestStatus.COMPLETED);
        String legacyUrl = writeLegacyFile(INVALID_CONTENT);
        registerUploader(legacyUrl, createUser("uploader"));
        createAnswer(requestId, itemId, List.of(legacyUrl), null);

        LegacyMediaMigrationResult result = migrationService.migrate();

        assertThat(result.migrated()).isZero();
        assertThat(result.failedByReason()).containsEntry("INVALID_MEDIA_CONTENT", 1);
        assertThat(canonicalFor(requestId, itemId)).isEmpty();
    }

    @Test
    @DisplayName("F: templateId==null -> заявка пропускается целиком, R2 не трогается")
    void unresolvedTemplate_SkippedEntirely() throws IOException {
        Long templateId = createTemplate();
        Long itemId = createItem(templateId);
        // request created WITHOUT binding a template
        Long requestId = createTerminalRequest(null, DiagnosticRequest.DiagnosticRequestStatus.COMPLETED);
        String legacyUrl = writeLegacyFile(VALID_JPEG);
        registerUploader(legacyUrl, createUser("uploader"));
        createAnswer(requestId, itemId, List.of(legacyUrl), null);

        LegacyMediaMigrationResult result = migrationService.migrate();

        assertThat(result.migrated()).isZero();
        assertThat(result.skippedUnresolvedTemplate()).isEqualTo(1);
        assertThat(result.failedByReason()).isEmpty();
        assertThat(mediaRepository.findByRequest(requestId)).isEmpty();
    }

    @Test
    @DisplayName("G: пункт не принадлежит шаблону заявки -> UNRESOLVED_ITEM, без записи")
    void itemNotBelongingToTemplate_Skipped() throws IOException {
        Long templateId = createTemplate();
        Long otherTemplateId = createTemplate();
        Long foreignItemId = createItem(otherTemplateId);
        Long requestId = createTerminalRequest(templateId, DiagnosticRequest.DiagnosticRequestStatus.COMPLETED);
        String legacyUrl = writeLegacyFile(VALID_JPEG);
        registerUploader(legacyUrl, createUser("uploader"));
        createAnswer(requestId, foreignItemId, List.of(legacyUrl), null);

        LegacyMediaMigrationResult result = migrationService.migrate();

        assertThat(result.migrated()).isZero();
        assertThat(result.failedByReason()).containsEntry("UNRESOLVED_ITEM", 1);
        assertThat(mediaRepository.findByRequest(requestId)).isEmpty();
    }

    @Test
    @DisplayName("K: не удаётся восстановить uploaderId -> UNRESOLVED_UPLOADER, без записи, без R2-объекта")
    void unresolvedUploader_SkippedWithoutFabrication() throws IOException {
        Long templateId = createTemplate();
        Long itemId = createItem(templateId);
        Long requestId = createTerminalRequest(templateId, DiagnosticRequest.DiagnosticRequestStatus.COMPLETED);
        String legacyUrl = writeLegacyFile(VALID_JPEG);
        // deliberately no registerUploader() call - MediaAsset row does not exist
        createAnswer(requestId, itemId, List.of(legacyUrl), null);

        LegacyMediaMigrationResult result = migrationService.migrate();

        assertThat(result.migrated()).isZero();
        assertThat(result.failedByReason()).containsEntry("UNRESOLVED_UPLOADER", 1);
        assertThat(canonicalFor(requestId, itemId)).isEmpty();
    }

    // ==================== H/T: idempotent reruns ====================

    @Test
    @DisplayName("H/T: уже полностью мигрированный пункт -> повторный запуск no-op, без дублей и повторной загрузки")
    void alreadyFullyMigrated_RerunIsNoop() throws IOException {
        Long templateId = createTemplate();
        Long itemId = createItem(templateId);
        Long requestId = createTerminalRequest(templateId, DiagnosticRequest.DiagnosticRequestStatus.COMPLETED);
        String legacyUrl = writeLegacyFile(VALID_JPEG);
        registerUploader(legacyUrl, createUser("uploader"));
        createAnswer(requestId, itemId, List.of(legacyUrl), null);

        LegacyMediaMigrationResult first = migrationService.migrate();
        assertThat(first.migrated()).isEqualTo(1);
        String storageKeyAfterFirstRun = canonicalFor(requestId, itemId).get(0).getStorageKey();

        LegacyMediaMigrationResult second = migrationService.migrate();

        assertThat(second.migrated()).isZero();
        assertThat(second.skippedAlreadyMigrated()).isEqualTo(1);
        List<DiagnosticMedia> canonical = canonicalFor(requestId, itemId);
        assertThat(canonical).hasSize(1); // no duplicate row
        assertThat(canonical.get(0).getStorageKey()).isEqualTo(storageKeyAfterFirstRun); // no re-upload
    }

    @Test
    @DisplayName("I: частичная миграция (одна из двух позиций не мигрирует) -> вторая позиция остаётся немигрированной, без дублей при перезапуске")
    void partialMigration_RemainingPositionStaysUnmigrated() throws IOException {
        Long templateId = createTemplate();
        Long itemId = createItem(templateId);
        Long requestId = createTerminalRequest(templateId, DiagnosticRequest.DiagnosticRequestStatus.COMPLETED);
        String goodUrl = writeLegacyFile(VALID_JPEG);
        registerUploader(goodUrl, createUser("uploader"));
        String missingUrl = mediaBaseUrl + "/" + UUID.randomUUID() + ".jpg"; // never written
        createAnswer(requestId, itemId, List.of(goodUrl, missingUrl), null);

        LegacyMediaMigrationResult first = migrationService.migrate();

        assertThat(first.migrated()).isEqualTo(1);
        assertThat(first.failedByReason()).containsEntry("MISSING_LEGACY_FILE", 1);
        List<DiagnosticMedia> canonical = canonicalFor(requestId, itemId);
        assertThat(canonical).hasSize(1);
        assertThat(canonical.get(0).getSortOrder()).isZero(); // only position 0 migrated

        // rerun must not duplicate the already-migrated position 0, and must retry position 1
        LegacyMediaMigrationResult second = migrationService.migrate();
        assertThat(second.migrated()).isZero(); // still missing
        assertThat(second.skippedAlreadyMigrated()).isEqualTo(1);
        assertThat(canonicalFor(requestId, itemId)).hasSize(1);
    }

    @Test
    @DisplayName("J: смешанная партия по нескольким заявкам -> точные суммарные счётчики")
    void mixedBatch_AcrossMultipleRequests_ExactCounts() throws IOException {
        Long templateId = createTemplate();
        Long itemId = createItem(templateId);

        Long requestOk = createTerminalRequest(templateId, DiagnosticRequest.DiagnosticRequestStatus.COMPLETED);
        String urlOk = writeLegacyFile(VALID_JPEG);
        registerUploader(urlOk, createUser("uploader"));
        createAnswer(requestOk, itemId, List.of(urlOk), null);

        Long requestMissing = createTerminalRequest(templateId, DiagnosticRequest.DiagnosticRequestStatus.APPROVED);
        String urlMissing = mediaBaseUrl + "/" + UUID.randomUUID() + ".jpg";
        createAnswer(requestMissing, itemId, List.of(urlMissing), null);

        // status is COMPLETED/APPROVED, not REJECTED, so it's still in scope and reaches the
        // per-answer "no legacy media" skip path rather than being excluded before that point
        Long requestNoMedia = createTerminalRequest(templateId, DiagnosticRequest.DiagnosticRequestStatus.COMPLETED);
        createAnswer(requestNoMedia, itemId, null, null);

        LegacyMediaMigrationResult result = migrationService.migrate();

        assertThat(result.migrated()).isEqualTo(1);
        assertThat(result.failedByReason()).containsEntry("MISSING_LEGACY_FILE", 1);
        assertThat(result.skippedNoLegacyMedia()).isGreaterThanOrEqualTo(1);
    }

    // ==================== Phase 12: security - path traversal / malformed legacy URLs ====================

    @Test
    @DisplayName("Security: URL с попыткой path traversal (..) отклоняется как MISSING_LEGACY_FILE, без обращения к ФС вне upload dir")
    void pathTraversalUrl_RejectedSafely() throws IOException {
        Long templateId = createTemplate();
        Long itemId = createItem(templateId);
        Long requestId = createTerminalRequest(templateId, DiagnosticRequest.DiagnosticRequestStatus.COMPLETED);
        String traversalUrl = mediaBaseUrl + "/../../../../etc/passwd";
        createAnswer(requestId, itemId, List.of(traversalUrl), null);

        LegacyMediaMigrationResult result = migrationService.migrate();

        assertThat(result.migrated()).isZero();
        assertThat(result.failedByReason()).containsEntry("MISSING_LEGACY_FILE", 1);
        assertThat(canonicalFor(requestId, itemId)).isEmpty();
    }

    @Test
    @DisplayName("Security: URL с обратным слешем отклоняется как MISSING_LEGACY_FILE")
    void backslashInUrl_RejectedSafely() throws IOException {
        Long templateId = createTemplate();
        Long itemId = createItem(templateId);
        Long requestId = createTerminalRequest(templateId, DiagnosticRequest.DiagnosticRequestStatus.COMPLETED);
        String backslashUrl = mediaBaseUrl + "/..\\..\\windows\\win.ini";
        createAnswer(requestId, itemId, List.of(backslashUrl), null);

        LegacyMediaMigrationResult result = migrationService.migrate();

        assertThat(result.migrated()).isZero();
        assertThat(result.failedByReason()).containsEntry("MISSING_LEGACY_FILE", 1);
        assertThat(canonicalFor(requestId, itemId)).isEmpty();
    }

    @Test
    @DisplayName("Security: URL с чужим префиксом (не media.base-url) отклоняется как MISSING_LEGACY_FILE")
    void foreignBaseUrl_RejectedSafely() throws IOException {
        Long templateId = createTemplate();
        Long itemId = createItem(templateId);
        Long requestId = createTerminalRequest(templateId, DiagnosticRequest.DiagnosticRequestStatus.COMPLETED);
        String foreignUrl = "https://evil.example.com/some-file.jpg";
        createAnswer(requestId, itemId, List.of(foreignUrl), null);

        LegacyMediaMigrationResult result = migrationService.migrate();

        assertThat(result.migrated()).isZero();
        assertThat(result.failedByReason()).containsEntry("MISSING_LEGACY_FILE", 1);
        assertThat(canonicalFor(requestId, itemId)).isEmpty();
    }

    @Test
    @DisplayName("M: заявка в нетерминальном статусе (IN_PROGRESS) никогда не рассматривается, даже если содержит легаси-медиа")
    void nonTerminalRequest_NeverConsidered() throws IOException {
        Long templateId = createTemplate();
        Long itemId = createItem(templateId);
        Long requestId = createTerminalRequest(templateId, DiagnosticRequest.DiagnosticRequestStatus.IN_PROGRESS);
        String legacyUrl = writeLegacyFile(VALID_JPEG);
        registerUploader(legacyUrl, createUser("uploader"));
        // bypasses the service-level IN_PROGRESS write gate deliberately, to prove
        // migrate() itself independently enforces the COMPLETED/APPROVED-only scope decision
        createAnswer(requestId, itemId, List.of(legacyUrl), null);

        LegacyMediaMigrationResult result = migrationService.migrate();

        assertThat(result.migrated()).isZero();
        assertThat(canonicalFor(requestId, itemId)).isEmpty();
        assertThat(storageClient.hasObject("diagnostic-media-legacy/" + requestId)).isFalse();
    }

    @Test
    @DisplayName("Review: заявка в статусе REJECTED никогда не мигрируется, даже с валидным легаси-медиа - resetForRework() делает её нестабильной")
    void rejectedRequest_NeverMigrated_EvenWithValidLegacyMedia() throws IOException {
        Long templateId = createTemplate();
        Long itemId = createItem(templateId);
        Long requestId = createTerminalRequest(templateId, DiagnosticRequest.DiagnosticRequestStatus.REJECTED);
        String legacyUrl = writeLegacyFile(VALID_JPEG);
        registerUploader(legacyUrl, createUser("uploader"));
        createAnswer(requestId, itemId, List.of(legacyUrl), null);

        LegacyMediaMigrationResult result = migrationService.migrate();

        assertThat(result.migrated()).isZero();
        assertThat(result.failedByReason()).isEmpty(); // excluded before ever being examined, not a failure
        assertThat(canonicalFor(requestId, itemId)).isEmpty();
        assertThat(mediaRepository.findByRequest(requestId)).isEmpty();

        // even a rerun after the request is later reworked back to IN_PROGRESS must never
        // retroactively migrate what was written while it was REJECTED
        requestRepository.resetForRework(requestId, requestRepository.findById(requestId).orElseThrow().getAssignedTo());
        LegacyMediaMigrationResult secondRun = migrationService.migrate();
        assertThat(secondRun.migrated()).isZero();
        assertThat(canonicalFor(requestId, itemId)).isEmpty();
    }

    // ==================== Phase 12/review: symlink containment ====================

    @Test
    @DisplayName("Security: symlink внутри media.upload.dir, указывающий за пределы директории, не читается")
    void symlinkInsideUploadDir_CannotEscapeContainment() throws IOException {
        Path outsideDir = Files.createTempDirectory("legacy-migration-outside-test");
        Path outsideFile = outsideDir.resolve("secret.jpg");
        Files.write(outsideFile, VALID_JPEG);

        Files.createDirectories(uploadDir());
        String symlinkName = UUID.randomUUID() + ".jpg";
        Path symlinkPath = uploadDir().resolve(symlinkName);
        try {
            Files.createSymbolicLink(symlinkPath, outsideFile);
        } catch (UnsupportedOperationException | IOException e) {
            Assumptions.assumeTrue(false, "Symlinks not supported/permitted in this environment: " + e.getMessage());
            return;
        }

        Long templateId = createTemplate();
        Long itemId = createItem(templateId);
        Long requestId = createTerminalRequest(templateId, DiagnosticRequest.DiagnosticRequestStatus.COMPLETED);
        String legacyUrl = mediaBaseUrl + "/" + symlinkName;
        registerUploader(legacyUrl, createUser("uploader"));
        createAnswer(requestId, itemId, List.of(legacyUrl), null);

        LegacyMediaMigrationResult result = migrationService.migrate();

        assertThat(result.migrated()).isZero();
        assertThat(canonicalFor(requestId, itemId)).isEmpty();
        assertThat(result.failedByReason()).containsEntry("MISSING_LEGACY_FILE", 1);
    }

    // ==================== Review point 4: storage-layer failure injection ====================

    @Test
    @DisplayName("Review: сбой putObject() не создаёт READY-запись, остальные позиции партии продолжают обрабатываться")
    void putObjectFailure_NoReadyRowCreated_OtherItemsContinue() throws IOException {
        Long templateId = createTemplate();
        Long itemId = createItem(templateId);

        Long failingRequestId = createTerminalRequest(templateId, DiagnosticRequest.DiagnosticRequestStatus.COMPLETED);
        String failingUrl = writeLegacyFile(VALID_JPEG);
        registerUploader(failingUrl, createUser("uploader"));
        createAnswer(failingRequestId, itemId, List.of(failingUrl), null);
        storageClient.failNextPutObject();

        Long okRequestId = createTerminalRequest(templateId, DiagnosticRequest.DiagnosticRequestStatus.COMPLETED);
        String okUrl = writeLegacyFile(VALID_PNG);
        registerUploader(okUrl, createUser("uploader"));
        createAnswer(okRequestId, itemId, List.of(okUrl), null);

        LegacyMediaMigrationResult result = migrationService.migrate();

        assertThat(result.migrated()).isEqualTo(1); // the OK request still migrated
        assertThat(result.failedByReason()).containsEntry("R2_UPLOAD_FAILED", 1);
        assertThat(canonicalFor(failingRequestId, itemId)).isEmpty(); // no READY row for the failed one
        assertThat(canonicalFor(okRequestId, itemId)).hasSize(1);
        assertThat(canonicalFor(okRequestId, itemId).get(0).getUploadStatus()).isEqualTo(UploadStatus.READY);
    }

    @Test
    @DisplayName("Review: сбой headObject() не создаёт READY-запись, остальные позиции партии продолжают обрабатываться")
    void headObjectFailure_NoReadyRowCreated_OtherItemsContinue() throws IOException {
        Long templateId = createTemplate();
        Long itemId = createItem(templateId);

        Long failingRequestId = createTerminalRequest(templateId, DiagnosticRequest.DiagnosticRequestStatus.COMPLETED);
        String failingUrl = writeLegacyFile(VALID_JPEG);
        registerUploader(failingUrl, createUser("uploader"));
        createAnswer(failingRequestId, itemId, List.of(failingUrl), null);
        storageClient.failNextHeadObject();

        Long okRequestId = createTerminalRequest(templateId, DiagnosticRequest.DiagnosticRequestStatus.COMPLETED);
        String okUrl = writeLegacyFile(VALID_PNG);
        registerUploader(okUrl, createUser("uploader"));
        createAnswer(okRequestId, itemId, List.of(okUrl), null);

        LegacyMediaMigrationResult result = migrationService.migrate();

        assertThat(result.migrated()).isEqualTo(1); // the OK request still migrated despite the other's headObject throwing
        assertThat(result.failedByReason()).containsEntry("R2_VERIFICATION_FAILED", 1);
        assertThat(canonicalFor(failingRequestId, itemId)).isEmpty(); // no READY row for the failed one
        assertThat(canonicalFor(okRequestId, itemId)).hasSize(1);
        assertThat(canonicalFor(okRequestId, itemId).get(0).getUploadStatus()).isEqualTo(UploadStatus.READY);
    }
}

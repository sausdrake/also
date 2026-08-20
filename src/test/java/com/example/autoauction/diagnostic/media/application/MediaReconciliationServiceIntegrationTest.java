// diagnostic/media/application/MediaReconciliationServiceIntegrationTest.java
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
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MEDIA-3.2, Phase 21 J-M: real DB + FakeStorageClient proof of
 * MediaReconciliationService's four cases.
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("MEDIA-3.2: MediaReconciliationService")
class MediaReconciliationServiceIntegrationTest {

    @Autowired
    private MediaReconciliationService reconciliationService;

    @Autowired
    private DiagnosticMediaRepository mediaRepository;

    @Autowired
    private DiagnosticRequestRepository requestRepository;

    @Autowired
    private InspectionTemplateRepository templateRepository;

    @Autowired
    private FakeStorageClient storageClient;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private static final byte[] VALID_JPEG = new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0x01};

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
        i.setHasPhoto(true);
        i.setSortOrder(1);
        Long itemId = templateRepository.saveItem(i).getId();

        Long ownerId = createUser("owner");
        Long diagnosticId = createUser("diag");
        DiagnosticRequest request = new DiagnosticRequest();
        request.setUserId(ownerId);
        request.setBrand("BMW");
        request.setModel("X5");
        request.setYear(2022);
        request.setVin("R" + (System.nanoTime() % 100000000L));
        request.setDepositFrozen(new BigDecimal("10000"));
        request.setStatus(DiagnosticRequest.DiagnosticRequestStatus.IN_PROGRESS);
        request.setAssignedTo(diagnosticId);
        request.setCreatedAt(OffsetDateTime.now());
        request.setUpdatedAt(OffsetDateTime.now());
        Long requestId = requestRepository.save(request).getId();
        requestRepository.bindTemplateIfUnset(requestId, templateId);
        return new RequestAndItem(requestId, itemId, diagnosticId);
    }

    private DiagnosticMedia createPendingMedia(RequestAndItem ctx) {
        DiagnosticMedia media = new DiagnosticMedia();
        media.setDiagnosticRequestId(ctx.requestId());
        media.setInspectionItemId(ctx.itemId());
        media.setUploaderId(ctx.uploaderId());
        media.setStorageKey("diagnostic-media/" + ctx.requestId() + "/" + UUID.randomUUID() + ".jpg");
        media.setMediaType(MediaType.PHOTO);
        media.setMimeType("image/jpeg");
        media.setSizeBytes(100L);
        media.setSortOrder(0);
        media.setUploadStatus(UploadStatus.PENDING_UPLOAD);
        media.setProcessingStatus(ProcessingStatus.NOT_REQUIRED);
        return mediaRepository.save(media);
    }

    /** MEDIA-3.4 Phase 7: a READY VIDEO row, no thumbnail yet (mirrors what processPending() leaves before processing runs). */
    private DiagnosticMedia createReadyVideo(RequestAndItem ctx) {
        DiagnosticMedia media = new DiagnosticMedia();
        media.setDiagnosticRequestId(ctx.requestId());
        media.setInspectionItemId(ctx.itemId());
        media.setUploaderId(ctx.uploaderId());
        media.setStorageKey("diagnostic-media/" + ctx.requestId() + "/" + UUID.randomUUID() + ".mp4");
        media.setMediaType(MediaType.VIDEO);
        media.setMimeType("video/mp4");
        media.setSizeBytes(1000L);
        media.setSortOrder(0);
        media.setUploadStatus(UploadStatus.READY);
        media.setProcessingStatus(ProcessingStatus.PENDING);
        DiagnosticMedia saved = mediaRepository.save(media);
        storageClient.simulateUpload(saved.getStorageKey(), VALID_JPEG, "video/mp4");
        return saved;
    }

    /**
     * Test-only: createdAt is set by @PrePersist and not otherwise mutable
     * through the port (deliberately - application code never rewrites it).
     * Ages a row directly via SQL to simulate an old PENDING_UPLOAD for the
     * expiry-threshold tests.
     */
    private void ageMediaCreatedAt(Long mediaId, OffsetDateTime createdAt) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            entityManager.createNativeQuery("UPDATE diagnostic_media SET created_at = ?1 WHERE id = ?2")
                    .setParameter(1, createdAt)
                    .setParameter(2, mediaId)
                    .executeUpdate();
        });
    }

    @Test
    @DisplayName("J: заброшенный PENDING_UPLOAD (без объекта, старше порога) -> EXPIRED")
    void abandonedPendingUpload_Expired() {
        RequestAndItem ctx = createRequestAndItem();
        DiagnosticMedia media = createPendingMedia(ctx);
        // no simulateUpload() - object never arrived
        ageMediaCreatedAt(media.getId(), OffsetDateTime.now().minusHours(2));

        reconciliationService.reconcile();

        assertThat(mediaRepository.findById(media.getId()).orElseThrow().getUploadStatus())
                .isEqualTo(UploadStatus.EXPIRED);
    }

    @Test
    @DisplayName("K: PENDING_UPLOAD, но объект реально существует и валиден -> promote к READY")
    void pendingUploadWithValidObject_PromotedToReady() {
        RequestAndItem ctx = createRequestAndItem();
        DiagnosticMedia media = createPendingMedia(ctx);
        storageClient.simulateUpload(media.getStorageKey(), VALID_JPEG, "image/jpeg");
        ageMediaCreatedAt(media.getId(), OffsetDateTime.now().minusHours(2));

        reconciliationService.reconcile();

        DiagnosticMedia result = mediaRepository.findById(media.getId()).orElseThrow();
        assertThat(result.getUploadStatus()).isEqualTo(UploadStatus.READY);
        assertThat(result.getMimeType()).isEqualTo("image/jpeg");
    }

    @Test
    @DisplayName("L: DELETED запись -> объект удаляется из хранилища, DB-статус сохраняется")
    void deletedMedia_ObjectRemovedFromStorage() {
        RequestAndItem ctx = createRequestAndItem();
        DiagnosticMedia media = createPendingMedia(ctx);
        storageClient.simulateUpload(media.getStorageKey(), VALID_JPEG, "image/jpeg");
        mediaRepository.softDelete(media.getId());
        assertThat(storageClient.hasObject(media.getStorageKey())).isTrue();

        reconciliationService.reconcile();

        assertThat(storageClient.hasObject(media.getStorageKey())).isFalse();
        assertThat(mediaRepository.findById(media.getId()).orElseThrow().getUploadStatus())
                .isEqualTo(UploadStatus.DELETED); // tombstone retained
    }

    @Test
    @DisplayName("M: READY запись, но объект отсутствует -> обнаруживается (без auto-мутации статуса)")
    void missingReadyObject_DetectedNotMutated() {
        RequestAndItem ctx = createRequestAndItem();
        DiagnosticMedia media = createPendingMedia(ctx);
        storageClient.simulateUpload(media.getStorageKey(), VALID_JPEG, "image/jpeg");
        mediaRepository.finalizeReady(media.getId(), "image/jpeg", VALID_JPEG.length);
        storageClient.forgetObject(media.getStorageKey()); // object vanishes out-of-band

        reconciliationService.reconcile(); // must not throw

        assertThat(mediaRepository.findById(media.getId()).orElseThrow().getUploadStatus())
                .as("a READY row with a missing object must NOT be silently mutated")
                .isEqualTo(UploadStatus.READY);
    }

    // ==================== MEDIA-3.4 Phase 7: DELETED thumbnail cleanup ====================

    @Test
    @DisplayName("Phase7-8: DELETED VIDEO без миниатюры -> удаляется только канонический объект")
    void deletedVideoWithoutThumbnail_CanonicalObjectCleaned() {
        RequestAndItem ctx = createRequestAndItem();
        DiagnosticMedia media = createReadyVideo(ctx); // thumbnailStorageKey stays null - never processed
        mediaRepository.softDelete(media.getId());
        assertThat(storageClient.hasObject(media.getStorageKey())).isTrue();

        reconciliationService.reconcile();

        assertThat(storageClient.hasObject(media.getStorageKey())).isFalse();
        assertThat(mediaRepository.findById(media.getId()).orElseThrow().getThumbnailStorageKey()).isNull();
    }

    @Test
    @DisplayName("Phase7-9: DELETED VIDEO с миниатюрой -> удаляются и канонический объект, и миниатюра")
    void deletedVideoWithThumbnail_BothObjectsCleaned() {
        RequestAndItem ctx = createRequestAndItem();
        DiagnosticMedia media = createReadyVideo(ctx);
        mediaRepository.claimForProcessing(media.getId());
        String thumbnailKey = "diagnostic-media-thumbnail/" + ctx.requestId() + "/" + UUID.randomUUID() + ".jpg";
        storageClient.simulateUpload(thumbnailKey, VALID_JPEG, "image/jpeg");
        mediaRepository.finalizeProcessingReady(media.getId(), 10, 640, 480, thumbnailKey);
        mediaRepository.softDelete(media.getId());
        assertThat(storageClient.hasObject(media.getStorageKey())).isTrue();
        assertThat(storageClient.hasObject(thumbnailKey)).isTrue();

        reconciliationService.reconcile();

        assertThat(storageClient.hasObject(media.getStorageKey())).isFalse();
        assertThat(storageClient.hasObject(thumbnailKey)).isFalse();
        assertThat(mediaRepository.findById(media.getId()).orElseThrow().getUploadStatus())
                .isEqualTo(UploadStatus.DELETED); // tombstone retained
    }

    @Test
    @DisplayName("Phase7-10: повторный прогон реконсиляции безопасен, когда оба объекта уже отсутствуют")
    void reconciliation_IdempotentWhenObjectsAlreadyAbsent() {
        RequestAndItem ctx = createRequestAndItem();
        DiagnosticMedia media = createReadyVideo(ctx);
        mediaRepository.claimForProcessing(media.getId());
        String thumbnailKey = "diagnostic-media-thumbnail/" + ctx.requestId() + "/" + UUID.randomUUID() + ".jpg";
        storageClient.simulateUpload(thumbnailKey, VALID_JPEG, "image/jpeg");
        mediaRepository.finalizeProcessingReady(media.getId(), 10, 640, 480, thumbnailKey);
        mediaRepository.softDelete(media.getId());

        reconciliationService.reconcile(); // first run deletes both objects
        assertThat(storageClient.hasObject(media.getStorageKey())).isFalse();
        assertThat(storageClient.hasObject(thumbnailKey)).isFalse();

        reconciliationService.reconcile(); // second run - objects already absent, must not throw

        assertThat(mediaRepository.findById(media.getId()).orElseThrow().getUploadStatus())
                .isEqualTo(UploadStatus.DELETED); // tombstone retained
    }
}

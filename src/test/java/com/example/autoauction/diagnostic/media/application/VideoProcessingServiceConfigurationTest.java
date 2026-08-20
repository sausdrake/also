// diagnostic/media/application/VideoProcessingServiceConfigurationTest.java
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
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MEDIA-3.4 Phase 10: proves that diagnostic.media.processing.* properties
 * actually reach VideoProcessingService and change its behavior - not just
 * that the (unchanged) default-equivalent values still work, which the
 * existing Phase 6/7 integration tests already cover. Each test overrides a
 * property to a value that is deliberately BETWEEN the configured-here value
 * and the old hardcoded default, so a test passing only by coincidence
 * (e.g. because the code still secretly uses the old hardcoded constant)
 * is distinguishable from a test passing because @Value wiring works.
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "diagnostic.media.processing.max-duration-seconds=5",
        "diagnostic.media.processing.stale-threshold-minutes=1"
})
@Transactional
@DisplayName("MEDIA-3.4 Phase 10: VideoProcessingService configuration wiring")
class VideoProcessingServiceConfigurationTest {

    @Autowired
    private VideoProcessingService videoProcessingService;

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

    private void ageProcessingClaim(Long mediaId, OffsetDateTime claimedAt) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                entityManager.createNativeQuery("UPDATE diagnostic_media SET processing_claimed_at = ?1 WHERE id = ?2")
                        .setParameter(1, claimedAt)
                        .setParameter(2, mediaId)
                        .executeUpdate());
    }

    @Test
    @DisplayName("configured max-duration-seconds (5) is enforced - a 10s result (below the old hardcoded 120 default) is rejected")
    void configuredMaxDurationSecondsIsEnforced() {
        RequestAndItem ctx = createRequestAndItem();
        DiagnosticMedia media = createReadyVideo(ctx);
        videoProcessor.configureSuccess(10, 640, 480); // 10s: passes the old hardcoded 120s default, must fail against 5s

        videoProcessingService.processPending(10);

        DiagnosticMedia after = mediaRepository.findById(media.getId()).orElseThrow();
        assertThat(after.getProcessingStatus()).isEqualTo(ProcessingStatus.FAILED);
    }

    @Test
    @DisplayName("configured stale-threshold-minutes (1) is used - a claim aged 2 minutes (well under the old 30-minute default) is reset")
    void configuredStaleThresholdIsUsed() {
        RequestAndItem ctx = createRequestAndItem();
        DiagnosticMedia media = createReadyVideo(ctx);
        boolean claimed = mediaRepository.claimForProcessing(media.getId());
        assertThat(claimed).isTrue();
        ageProcessingClaim(media.getId(), OffsetDateTime.now().minusMinutes(2));

        videoProcessingService.reconcileStale(10);

        DiagnosticMedia after = mediaRepository.findById(media.getId()).orElseThrow();
        assertThat(after.getProcessingStatus()).isEqualTo(ProcessingStatus.PENDING);
    }
}

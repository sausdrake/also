// diagnostic/media/application/VideoProcessingServiceProcessorResultTest.java
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
import com.example.autoauction.diagnostic.media.domain.port.VideoProcessor;
import com.example.autoauction.diagnostic.media.infrastructure.storage.FakeStorageClient;
import com.example.autoauction.user.domain.Role;
import com.example.autoauction.user.domain.User;
import com.example.autoauction.user.domain.port.RoleRepository;
import com.example.autoauction.user.domain.port.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * MEDIA-3.4 Phase 6: proves VideoProcessingService's OWN defensive
 * ProcessingResult validation, independent of whatever VideoProcessor
 * happens to return - covers the malformed-result shapes FakeVideoProcessor
 * cannot itself produce (it always returns a valid, non-empty, correctly
 * contained thumbnail), so this uses @MockBean VideoProcessor instead of
 * modifying FakeVideoProcessor, per the approved Phase 6 scope.
 *
 * @Transactional: same reasoning as VideoProcessingServiceIntegrationTest.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@DisplayName("MEDIA-3.4: VideoProcessingService defensive ProcessingResult validation")
class VideoProcessingServiceProcessorResultTest {

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

    @MockBean
    private VideoProcessor videoProcessor;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RoleRepository roleRepository;

    private static final byte[] VIDEO_BYTES = new byte[]{0x00, 0x00, 0x00, 0x18, 'f', 't', 'y', 'p'};

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

    private void runWithResult(VideoProcessor.ProcessingResult result) {
        when(videoProcessor.process(any(), any())).thenReturn(result);
    }

    // ==================== null / missing / empty / outside-temp-dir thumbnail ====================

    @Test
    @DisplayName("thumbnailFile == null -> FAILED, без загрузки в R2")
    void nullThumbnail_Rejected() {
        RequestAndItem ctx = createRequestAndItem();
        DiagnosticMedia media = createReadyVideo(ctx);
        long baseline = storageClient.objectCount();
        runWithResult(new VideoProcessor.ProcessingResult(10, 640, 480, null));

        videoProcessingService.processPending(10);

        assertThat(mediaRepository.findById(media.getId()).orElseThrow().getProcessingStatus())
                .isEqualTo(ProcessingStatus.FAILED);
        assertThat(storageClient.objectCount()).isEqualTo(baseline);
    }

    @Test
    @DisplayName("thumbnailFile указывает на несуществующий файл -> FAILED, без загрузки в R2")
    void missingThumbnailFile_Rejected() throws Exception {
        RequestAndItem ctx = createRequestAndItem();
        DiagnosticMedia media = createReadyVideo(ctx);
        long baseline = storageClient.objectCount();
        Path missing = Files.createTempDirectory("processor-result-test-").resolve("does-not-exist.jpg");
        runWithResult(new VideoProcessor.ProcessingResult(10, 640, 480, missing));

        videoProcessingService.processPending(10);

        assertThat(mediaRepository.findById(media.getId()).orElseThrow().getProcessingStatus())
                .isEqualTo(ProcessingStatus.FAILED);
        assertThat(storageClient.objectCount()).isEqualTo(baseline);
    }

    @Test
    @DisplayName("thumbnailFile пустой (0 байт) -> FAILED, без загрузки в R2")
    void emptyThumbnailFile_Rejected() throws Exception {
        RequestAndItem ctx = createRequestAndItem();
        DiagnosticMedia media = createReadyVideo(ctx);
        long baseline = storageClient.objectCount();
        Path emptyFile = Files.createTempDirectory("processor-result-test-").resolve("empty.jpg");
        Files.createFile(emptyFile);
        runWithResult(new VideoProcessor.ProcessingResult(10, 640, 480, emptyFile));

        videoProcessingService.processPending(10);

        assertThat(mediaRepository.findById(media.getId()).orElseThrow().getProcessingStatus())
                .isEqualTo(ProcessingStatus.FAILED);
        assertThat(storageClient.objectCount()).isEqualTo(baseline);
    }

    @Test
    @DisplayName("16 (безопасность): thumbnailFile вне temp-директории -> отклонено ДО putObject")
    void thumbnailOutsideTempDirectory_RejectedBeforeUpload() throws Exception {
        RequestAndItem ctx = createRequestAndItem();
        DiagnosticMedia media = createReadyVideo(ctx);
        long baseline = storageClient.objectCount();
        // A real, valid, non-empty file - but deliberately OUTSIDE any temp directory the
        // service itself created for this item, simulating a malicious/buggy processor result.
        Path outside = Files.createTempFile("outside-any-service-temp-dir-", ".jpg");
        Files.write(outside, new byte[]{1, 2, 3, 4});
        runWithResult(new VideoProcessor.ProcessingResult(10, 640, 480, outside));

        videoProcessingService.processPending(10);

        assertThat(mediaRepository.findById(media.getId()).orElseThrow().getProcessingStatus())
                .isEqualTo(ProcessingStatus.FAILED);
        assertThat(storageClient.objectCount())
                .as("putObject must never be called for a thumbnail outside the item's own temp directory")
                .isEqualTo(baseline);
        Files.deleteIfExists(outside);
    }
}

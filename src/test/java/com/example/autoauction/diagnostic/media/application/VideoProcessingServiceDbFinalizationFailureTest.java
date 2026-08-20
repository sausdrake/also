// diagnostic/media/application/VideoProcessingServiceDbFinalizationFailureTest.java
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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

/**
 * MEDIA-3.4 Phase 6: proves the DB_FINALIZATION_FAILED / compensating-delete
 * path (finalizeProcessingReady() throws) - mirrors
 * LegacyMediaMigrationOrphanTest exactly (@MockBean DiagnosticMediaRepository,
 * everything else real, @Transactional isolation).
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@DisplayName("MEDIA-3.4: VideoProcessingService DB finalization failure / compensating delete")
class VideoProcessingServiceDbFinalizationFailureTest {

    @Autowired
    private VideoProcessingService videoProcessingService;

    @MockBean
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

    private static final byte[] VIDEO_BYTES = new byte[]{0x00, 0x00, 0x00, 0x18, 'f', 't', 'y', 'p'};

    private Long createUser(String prefix) {
        Role userRole = roleRepository.findByName("ROLE_USER")
                .orElseGet(() -> roleRepository.save(new Role(null, "ROLE_USER", "Обычный пользователь")));
        User u = new User(null, prefix + System.nanoTime(), prefix + System.nanoTime() + "@test.com",
                "encoded", true, Set.of(userRole));
        return userRepository.save(u).getId();
    }

    private Long createTemplateWithVideoItem() {
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
        templateRepository.saveItem(i);
        return templateId;
    }

    private Long createRequest(Long templateId) {
        Long ownerId = createUser("owner");
        Long diagnosticId = createUser("diag");
        DiagnosticRequest request = new DiagnosticRequest();
        request.setUserId(ownerId);
        request.setBrand("BMW");
        request.setModel("X5");
        request.setYear(2022);
        request.setVin("D" + (System.nanoTime() % 100000000L));
        request.setDepositFrozen(new BigDecimal("10000"));
        request.setStatus(DiagnosticRequest.DiagnosticRequestStatus.IN_PROGRESS);
        request.setAssignedTo(diagnosticId);
        request.setCreatedAt(OffsetDateTime.now());
        request.setUpdatedAt(OffsetDateTime.now());
        Long requestId = requestRepository.save(request).getId();
        requestRepository.bindTemplateIfUnset(requestId, templateId);
        return requestId;
    }

    @Test
    @DisplayName("9: finalizeProcessingReady() выбрасывает исключение ПОСЛЕ успешной загрузки миниатюры -> " +
            "компенсирующее удаление, исходная ошибка не маскируется, orphan не остаётся")
    void dbFinalizationThrowsAfterThumbnailUpload_CompensatingDeleteRunsNoOrphan() {
        Long templateId = createTemplateWithVideoItem();
        Long requestId = createRequest(templateId);
        String storageKey = "diagnostic-media/" + requestId + "/" + UUID.randomUUID() + ".mp4";
        storageClient.simulateUpload(storageKey, VIDEO_BYTES, "video/mp4");
        videoProcessor.configureFailure(null);
        videoProcessor.configureSuccess(10, 640, 480);

        DiagnosticMedia media = new DiagnosticMedia();
        media.setId(1L);
        media.setDiagnosticRequestId(requestId);
        media.setInspectionItemId(1L);
        media.setUploaderId(1L);
        media.setStorageKey(storageKey);
        media.setMediaType(MediaType.VIDEO);
        media.setUploadStatus(UploadStatus.READY);
        media.setProcessingStatus(ProcessingStatus.PENDING);

        when(mediaRepository.findPendingProcessing(anyInt())).thenReturn(List.of(media));
        when(mediaRepository.claimForProcessing(media.getId())).thenReturn(true);
        when(mediaRepository.finalizeProcessingReady(anyLong(), any(), any(), any(), any()))
                .thenThrow(new RuntimeException("simulated DB finalization failure"));

        long baselineObjects = storageClient.objectCount(); // includes the original video upload

        videoProcessingService.processPending(10);

        // The compensating delete must have removed the thumbnail that WAS successfully
        // uploaded before finalizeProcessingReady threw - only the original video remains.
        assertThat(storageClient.objectCount())
                .as("compensating deleteObject() must remove the just-uploaded thumbnail, leaving no orphan")
                .isEqualTo(baselineObjects);
    }

    @Test
    @DisplayName("Stale-7: сбой resetStaleProcessing() для одной записи не прерывает обработку остальных")
    void staleRecoveryFailure_OnOneItem_DoesNotAbortRemaining() {
        DiagnosticMedia failing = new DiagnosticMedia();
        failing.setId(101L);
        failing.setProcessingStatus(ProcessingStatus.PROCESSING);

        DiagnosticMedia ok = new DiagnosticMedia();
        ok.setId(102L);
        ok.setProcessingStatus(ProcessingStatus.PROCESSING);

        when(mediaRepository.findStaleProcessing(any(), anyInt())).thenReturn(List.of(failing, ok));
        when(mediaRepository.resetStaleProcessing(101L)).thenThrow(new RuntimeException("simulated stale-reset DB failure"));
        when(mediaRepository.resetStaleProcessing(102L)).thenReturn(true);

        videoProcessingService.reconcileStale(10); // must not throw despite the first item's failure

        org.mockito.Mockito.verify(mediaRepository).resetStaleProcessing(101L);
        org.mockito.Mockito.verify(mediaRepository).resetStaleProcessing(102L); // second item still reached
    }
}

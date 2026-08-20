// diagnostic/web/VideoProcessingRetrySecurityTest.java
package com.example.autoauction.diagnostic.web;

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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Set;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MEDIA-3.4 Phase 9: HTTP-layer coverage for the admin video-processing
 * retry endpoint. Mirrors LegacyMediaMigrationSecurityTest's proven
 * @SpringBootTest + @AutoConfigureMockMvc + @WithMockUser convention for the
 * pure-auth cases, and DiagnosticMediaSecurityTest's real-fixture style for
 * the business-outcome cases (no @WebMvcTest - see DiagnosticWorkControllerTest's
 * pre-existing, unrelated failure for why that slice is unreliable here).
 *
 * @Transactional (rollback-per-test): consistent with every other MEDIA-3.4
 * integration test in this session.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@DisplayName("MEDIA-3.4 Phase 9: admin video-processing retry endpoint")
class VideoProcessingRetrySecurityTest {

    @Autowired
    private MockMvc mockMvc;

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

    private static String endpoint(Long mediaId) {
        return "/api/v1/admin/diagnostic/media/" + mediaId + "/retry-processing";
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
        request.setVin("Y" + (System.nanoTime() % 100000000L));
        request.setDepositFrozen(new BigDecimal("10000"));
        request.setStatus(DiagnosticRequest.DiagnosticRequestStatus.IN_PROGRESS);
        request.setAssignedTo(diagnosticId);
        request.setCreatedAt(OffsetDateTime.now());
        request.setUpdatedAt(OffsetDateTime.now());
        Long requestId = requestRepository.save(request).getId();
        requestRepository.bindTemplateIfUnset(requestId, templateId);
        return new RequestAndItem(requestId, itemId, diagnosticId);
    }

    private DiagnosticMedia createVideoMedia(UploadStatus uploadStatus, ProcessingStatus processingStatus) {
        RequestAndItem ctx = createRequestAndItem();
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

    // ==================== 1-3: pure auth ====================

    @Test
    @DisplayName("1: неаутентифицированный запрос -> 401")
    void unauthenticated_401() throws Exception {
        DiagnosticMedia media = createVideoMedia(UploadStatus.READY, ProcessingStatus.FAILED);
        mockMvc.perform(post(endpoint(media.getId())))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(roles = {"USER"})
    @DisplayName("2: роль USER -> 403")
    void userRole_403() throws Exception {
        DiagnosticMedia media = createVideoMedia(UploadStatus.READY, ProcessingStatus.FAILED);
        mockMvc.perform(post(endpoint(media.getId())))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = {"DIAGNOSTIC"})
    @DisplayName("3: роль DIAGNOSTIC -> 403")
    void diagnosticRole_403() throws Exception {
        DiagnosticMedia media = createVideoMedia(UploadStatus.READY, ProcessingStatus.FAILED);
        mockMvc.perform(post(endpoint(media.getId())))
                .andExpect(status().isForbidden());
    }

    // ==================== 4-9: ADMIN business outcomes ====================

    @Test
    @WithMockUser(roles = {"ADMIN"})
    @DisplayName("4: ADMIN + FAILED/READY -> 200, DB-состояние становится PENDING")
    void admin_FailedReady_200_TransitionsToPending() throws Exception {
        DiagnosticMedia media = createVideoMedia(UploadStatus.READY, ProcessingStatus.FAILED);

        mockMvc.perform(post(endpoint(media.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.processingStatus").value("PENDING"));

        org.assertj.core.api.Assertions.assertThat(mediaRepository.findById(media.getId()).orElseThrow().getProcessingStatus())
                .isEqualTo(ProcessingStatus.PENDING);
    }

    @Test
    @WithMockUser(roles = {"ADMIN"})
    @DisplayName("5: ADMIN + уже PENDING -> 200, состояние не меняется (идемпотентность)")
    void admin_AlreadyPending_200_Unchanged() throws Exception {
        DiagnosticMedia media = createVideoMedia(UploadStatus.READY, ProcessingStatus.PENDING);

        mockMvc.perform(post(endpoint(media.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.processingStatus").value("PENDING"));

        org.assertj.core.api.Assertions.assertThat(mediaRepository.findById(media.getId()).orElseThrow().getProcessingStatus())
                .isEqualTo(ProcessingStatus.PENDING);
    }

    @Test
    @WithMockUser(roles = {"ADMIN"})
    @DisplayName("6: ADMIN + DELETED -> 409, состояние не меняется")
    void admin_Deleted_409_Unchanged() throws Exception {
        DiagnosticMedia media = createVideoMedia(UploadStatus.READY, ProcessingStatus.FAILED);
        mediaRepository.softDelete(media.getId());

        mockMvc.perform(post(endpoint(media.getId())))
                .andExpect(status().isConflict());

        DiagnosticMedia after = mediaRepository.findById(media.getId()).orElseThrow();
        org.assertj.core.api.Assertions.assertThat(after.getUploadStatus()).isEqualTo(UploadStatus.DELETED);
        org.assertj.core.api.Assertions.assertThat(after.getProcessingStatus()).isEqualTo(ProcessingStatus.FAILED);
    }

    @Test
    @WithMockUser(roles = {"ADMIN"})
    @DisplayName("7: ADMIN + PROCESSING -> 409, состояние не меняется")
    void admin_Processing_409_Unchanged() throws Exception {
        DiagnosticMedia media = createVideoMedia(UploadStatus.READY, ProcessingStatus.PENDING);
        mediaRepository.claimForProcessing(media.getId());

        mockMvc.perform(post(endpoint(media.getId())))
                .andExpect(status().isConflict());

        org.assertj.core.api.Assertions.assertThat(mediaRepository.findById(media.getId()).orElseThrow().getProcessingStatus())
                .isEqualTo(ProcessingStatus.PROCESSING);
    }

    @Test
    @WithMockUser(roles = {"ADMIN"})
    @DisplayName("8: ADMIN + READY (уже обработано) -> 409, состояние не меняется")
    void admin_Ready_409_Unchanged() throws Exception {
        DiagnosticMedia media = createVideoMedia(UploadStatus.READY, ProcessingStatus.PENDING);
        mediaRepository.claimForProcessing(media.getId());
        mediaRepository.finalizeProcessingReady(media.getId(), 10, 640, 480, "diagnostic-media-thumbnail/x/y.jpg");

        mockMvc.perform(post(endpoint(media.getId())))
                .andExpect(status().isConflict());

        org.assertj.core.api.Assertions.assertThat(mediaRepository.findById(media.getId()).orElseThrow().getProcessingStatus())
                .isEqualTo(ProcessingStatus.READY);
    }

    @Test
    @WithMockUser(roles = {"ADMIN"})
    @DisplayName("9: ADMIN + несуществующий ID -> 404")
    void admin_NonexistentId_404() throws Exception {
        mockMvc.perform(post(endpoint(999_999_999L)))
                .andExpect(status().isNotFound());
    }
}

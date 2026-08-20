// diagnostic/media/web/ThumbnailReadUrlSecurityTest.java
package com.example.autoauction.diagnostic.media.web;

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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MEDIA-3.4 Phase 11: HTTP-layer coverage for the thumbnail read-URL
 * endpoints. Mirrors DiagnosticMediaSecurityTest's real-Spring-context
 * MockMvc convention (not @WebMvcTest - see DiagnosticWorkControllerTest's
 * pre-existing, unrelated failure for why that slice is unreliable here) and
 * VideoProcessingRetrySecurityTest's direct-construct-and-save media fixture
 * style, since these fixtures need specific uploadStatus/processingStatus/
 * thumbnailStorageKey combinations the normal intent/finalize flow doesn't
 * produce directly.
 *
 * @Transactional (rollback-per-test): consistent with every other MEDIA-3.4
 * integration test in this session.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@DisplayName("MEDIA-3.4 Phase 11: thumbnail read-URL endpoints")
class ThumbnailReadUrlSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private DiagnosticRequestRepository requestRepository;

    @Autowired
    private InspectionTemplateRepository templateRepository;

    @Autowired
    private DiagnosticMediaRepository mediaRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RoleRepository roleRepository;

    private static String authenticatedEndpoint(Long mediaId) {
        return "/api/v1/diagnostic/media/" + mediaId + "/thumbnail-url";
    }

    private static String publicEndpoint(Long mediaId) {
        return "/api/v1/public/reports/media/" + mediaId + "/thumbnail-url";
    }

    private record Ctx(Long requestId, Long itemId, Long ownerId, Long diagnosticId) {}

    private Long createUser(String prefix) {
        Role userRole = roleRepository.findByName("ROLE_USER")
                .orElseGet(() -> roleRepository.save(new Role(null, "ROLE_USER", "Обычный пользователь")));
        User u = new User(null, prefix + System.nanoTime(), prefix + System.nanoTime() + "@test.com",
                "encoded", true, Set.of(userRole));
        return userRepository.save(u).getId();
    }

    private Ctx setUp() {
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
        request.setVin("T" + (System.nanoTime() % 100000000L));
        request.setDepositFrozen(new BigDecimal("10000"));
        request.setStatus(DiagnosticRequest.DiagnosticRequestStatus.IN_PROGRESS);
        request.setAssignedTo(diagnosticId);
        request.setCreatedAt(OffsetDateTime.now());
        request.setUpdatedAt(OffsetDateTime.now());
        Long requestId = requestRepository.save(request).getId();
        requestRepository.bindTemplateIfUnset(requestId, templateId);

        return new Ctx(requestId, itemId, ownerId, diagnosticId);
    }

    private DiagnosticMedia createMedia(Ctx ctx, MediaType mediaType, UploadStatus uploadStatus,
                                         ProcessingStatus processingStatus, String thumbnailStorageKey) {
        DiagnosticMedia media = new DiagnosticMedia();
        media.setDiagnosticRequestId(ctx.requestId());
        media.setInspectionItemId(ctx.itemId());
        media.setUploaderId(ctx.diagnosticId());
        media.setStorageKey("diagnostic-media/" + ctx.requestId() + "/" + UUID.randomUUID() + ".mp4");
        media.setMediaType(mediaType);
        media.setMimeType(mediaType == MediaType.VIDEO ? "video/mp4" : "image/jpeg");
        media.setSizeBytes(1000L);
        media.setSortOrder(0);
        media.setUploadStatus(uploadStatus);
        media.setProcessingStatus(processingStatus);
        media.setThumbnailStorageKey(thumbnailStorageKey);
        return mediaRepository.save(media);
    }

    private static String thumbnailKeyFor(Long requestId) {
        return "diagnostic-media-thumbnail/" + requestId + "/" + UUID.randomUUID() + ".jpg";
    }

    private void approve(Long requestId) {
        DiagnosticRequest request = requestRepository.findById(requestId).orElseThrow();
        request.setStatus(DiagnosticRequest.DiagnosticRequestStatus.APPROVED);
        requestRepository.save(request);
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor user(Long userId, String role) {
        return org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
                .user(com.example.autoauction.auth.domain.TestUserPrincipal.createWithRoles(userId, "u" + userId, role));
    }

    // ==================== Authenticated endpoint ====================

    @Test
    @DisplayName("1: неаутентифицированный запрос -> 401")
    void unauthenticated_401() throws Exception {
        Ctx ctx = setUp();
        String key = thumbnailKeyFor(ctx.requestId());
        DiagnosticMedia media = createMedia(ctx, MediaType.VIDEO, UploadStatus.READY, ProcessingStatus.READY, key);

        mockMvc.perform(get(authenticatedEndpoint(media.getId())))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("2: посторонний пользователь без отношения к заявке -> 403")
    void unrelatedUser_403() throws Exception {
        Ctx ctx = setUp();
        String key = thumbnailKeyFor(ctx.requestId());
        DiagnosticMedia media = createMedia(ctx, MediaType.VIDEO, UploadStatus.READY, ProcessingStatus.READY, key);
        Long stranger = createUser("stranger");

        mockMvc.perform(get(authenticatedEndpoint(media.getId())).with(user(stranger, "USER")))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("3: владелец + READY + превью -> 200")
    void owner_ReadyWithThumbnail_200() throws Exception {
        Ctx ctx = setUp();
        String key = thumbnailKeyFor(ctx.requestId());
        DiagnosticMedia media = createMedia(ctx, MediaType.VIDEO, UploadStatus.READY, ProcessingStatus.READY, key);

        mockMvc.perform(get(authenticatedEndpoint(media.getId())).with(user(ctx.ownerId(), "USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.url").value(org.hamcrest.Matchers.containsString(key)));
    }

    @Test
    @DisplayName("4: назначенный диагност + READY + превью -> 200")
    void diagnostician_ReadyWithThumbnail_200() throws Exception {
        Ctx ctx = setUp();
        String key = thumbnailKeyFor(ctx.requestId());
        DiagnosticMedia media = createMedia(ctx, MediaType.VIDEO, UploadStatus.READY, ProcessingStatus.READY, key);

        mockMvc.perform(get(authenticatedEndpoint(media.getId())).with(user(ctx.diagnosticId(), "DIAGNOSTIC")))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("5: ADMIN + READY + превью -> 200")
    void admin_ReadyWithThumbnail_200() throws Exception {
        Ctx ctx = setUp();
        String key = thumbnailKeyFor(ctx.requestId());
        DiagnosticMedia media = createMedia(ctx, MediaType.VIDEO, UploadStatus.READY, ProcessingStatus.READY, key);
        Long admin = createUser("admin");

        mockMvc.perform(get(authenticatedEndpoint(media.getId())).with(user(admin, "ADMIN")))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("6: несуществующий медиафайл -> 404")
    void nonexistentMedia_404() throws Exception {
        Ctx ctx = setUp();
        mockMvc.perform(get(authenticatedEndpoint(999_999_999L)).with(user(ctx.ownerId(), "USER")))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("7: удалённый медиафайл (авторизованный владелец) с непустым thumbnailStorageKey -> 409, не 200")
    void deletedMedia_AuthorizedOwner_409() throws Exception {
        Ctx ctx = setUp();
        String key = thumbnailKeyFor(ctx.requestId());
        DiagnosticMedia media = createMedia(ctx, MediaType.VIDEO, UploadStatus.READY, ProcessingStatus.READY, key);
        mediaRepository.softDelete(media.getId());

        mockMvc.perform(get(authenticatedEndpoint(media.getId())).with(user(ctx.ownerId(), "USER")))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("8: READY медиафайл без превью -> 409")
    void readyWithoutThumbnail_409() throws Exception {
        Ctx ctx = setUp();
        DiagnosticMedia media = createMedia(ctx, MediaType.VIDEO, UploadStatus.READY, ProcessingStatus.READY, null);

        mockMvc.perform(get(authenticatedEndpoint(media.getId())).with(user(ctx.ownerId(), "USER")))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("9: PHOTO медиафайл -> 409")
    void photoMedia_409() throws Exception {
        Ctx ctx = setUp();
        DiagnosticMedia media = createMedia(ctx, MediaType.PHOTO, UploadStatus.READY, ProcessingStatus.NOT_REQUIRED, null);

        mockMvc.perform(get(authenticatedEndpoint(media.getId())).with(user(ctx.ownerId(), "USER")))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("10: VIDEO PENDING -> 409")
    void videoPending_409() throws Exception {
        Ctx ctx = setUp();
        DiagnosticMedia media = createMedia(ctx, MediaType.VIDEO, UploadStatus.READY, ProcessingStatus.PENDING, null);

        mockMvc.perform(get(authenticatedEndpoint(media.getId())).with(user(ctx.ownerId(), "USER")))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("11: VIDEO PROCESSING -> 409")
    void videoProcessing_409() throws Exception {
        Ctx ctx = setUp();
        DiagnosticMedia media = createMedia(ctx, MediaType.VIDEO, UploadStatus.READY, ProcessingStatus.PROCESSING, null);

        mockMvc.perform(get(authenticatedEndpoint(media.getId())).with(user(ctx.ownerId(), "USER")))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("12: VIDEO FAILED -> 409")
    void videoFailed_409() throws Exception {
        Ctx ctx = setUp();
        DiagnosticMedia media = createMedia(ctx, MediaType.VIDEO, UploadStatus.READY, ProcessingStatus.FAILED, null);

        mockMvc.perform(get(authenticatedEndpoint(media.getId())).with(user(ctx.ownerId(), "USER")))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("13 (copy-paste guard): возвращённый URL построен из thumbnailStorageKey, а не storageKey")
    void returnedUrl_UsesThumbnailKey_NotOriginalKey() throws Exception {
        Ctx ctx = setUp();
        String thumbnailKey = thumbnailKeyFor(ctx.requestId());
        DiagnosticMedia media = createMedia(ctx, MediaType.VIDEO, UploadStatus.READY, ProcessingStatus.READY, thumbnailKey);
        String originalKey = media.getStorageKey();

        mockMvc.perform(get(authenticatedEndpoint(media.getId())).with(user(ctx.ownerId(), "USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.url").value(org.hamcrest.Matchers.containsString(thumbnailKey)))
                .andExpect(jsonPath("$.url").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(originalKey))));
    }

    @Test
    @DisplayName("15: чтение превью не мутирует состояние БД")
    void readingThumbnailUrl_DoesNotMutateState() throws Exception {
        Ctx ctx = setUp();
        String key = thumbnailKeyFor(ctx.requestId());
        DiagnosticMedia media = createMedia(ctx, MediaType.VIDEO, UploadStatus.READY, ProcessingStatus.READY, key);

        mockMvc.perform(get(authenticatedEndpoint(media.getId())).with(user(ctx.ownerId(), "USER")))
                .andExpect(status().isOk());

        DiagnosticMedia after = mediaRepository.findById(media.getId()).orElseThrow();
        assertThat(after.getUploadStatus()).isEqualTo(UploadStatus.READY);
        assertThat(after.getProcessingStatus()).isEqualTo(ProcessingStatus.READY);
        assertThat(after.getThumbnailStorageKey()).isEqualTo(key);
    }

    // ==================== Public approved-report endpoint ====================

    @Test
    @DisplayName("16/17: неаутентифицированный доступ к превью APPROVED-отчёта -> 200")
    void publicThumbnailUrl_ApprovedReport_200() throws Exception {
        Ctx ctx = setUp();
        String key = thumbnailKeyFor(ctx.requestId());
        DiagnosticMedia media = createMedia(ctx, MediaType.VIDEO, UploadStatus.READY, ProcessingStatus.READY, key);
        approve(ctx.requestId());

        mockMvc.perform(get(publicEndpoint(media.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.url").value(org.hamcrest.Matchers.containsString(key)));
    }

    @Test
    @DisplayName("18: не-APPROVED отчёт -> 404 (как у существующего публичного media-url эндпоинта)")
    void publicThumbnailUrl_NonApprovedReport_404() throws Exception {
        Ctx ctx = setUp(); // IN_PROGRESS, never approved
        String key = thumbnailKeyFor(ctx.requestId());
        DiagnosticMedia media = createMedia(ctx, MediaType.VIDEO, UploadStatus.READY, ProcessingStatus.READY, key);

        mockMvc.perform(get(publicEndpoint(media.getId())))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("19: удалённый медиафайл на APPROVED-отчёте -> 404 (не раскрывает существование)")
    void publicThumbnailUrl_DeletedMedia_404() throws Exception {
        Ctx ctx = setUp();
        String key = thumbnailKeyFor(ctx.requestId());
        DiagnosticMedia media = createMedia(ctx, MediaType.VIDEO, UploadStatus.READY, ProcessingStatus.READY, key);
        approve(ctx.requestId());
        mediaRepository.softDelete(media.getId());

        mockMvc.perform(get(publicEndpoint(media.getId())))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("20: APPROVED-отчёт, READY медиафайл без превью -> 409, не 404")
    void publicThumbnailUrl_NullThumbnail_409() throws Exception {
        Ctx ctx = setUp();
        DiagnosticMedia media = createMedia(ctx, MediaType.VIDEO, UploadStatus.READY, ProcessingStatus.PENDING, null);
        approve(ctx.requestId());

        mockMvc.perform(get(publicEndpoint(media.getId())))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("21: публичный URL построен из thumbnailStorageKey, а не storageKey")
    void publicThumbnailUrl_UsesThumbnailKey_NotOriginalKey() throws Exception {
        Ctx ctx = setUp();
        String thumbnailKey = thumbnailKeyFor(ctx.requestId());
        DiagnosticMedia media = createMedia(ctx, MediaType.VIDEO, UploadStatus.READY, ProcessingStatus.READY, thumbnailKey);
        String originalKey = media.getStorageKey();
        approve(ctx.requestId());

        mockMvc.perform(get(publicEndpoint(media.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.url").value(org.hamcrest.Matchers.containsString(thumbnailKey)))
                .andExpect(jsonPath("$.url").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(originalKey))));
    }

    @Test
    @DisplayName("22: публичное чтение превью не мутирует состояние БД")
    void publicThumbnailUrl_DoesNotMutateState() throws Exception {
        Ctx ctx = setUp();
        String key = thumbnailKeyFor(ctx.requestId());
        DiagnosticMedia media = createMedia(ctx, MediaType.VIDEO, UploadStatus.READY, ProcessingStatus.READY, key);
        approve(ctx.requestId());

        mockMvc.perform(get(publicEndpoint(media.getId())))
                .andExpect(status().isOk());

        DiagnosticMedia after = mediaRepository.findById(media.getId()).orElseThrow();
        assertThat(after.getUploadStatus()).isEqualTo(UploadStatus.READY);
        assertThat(after.getThumbnailStorageKey()).isEqualTo(key);
    }
}

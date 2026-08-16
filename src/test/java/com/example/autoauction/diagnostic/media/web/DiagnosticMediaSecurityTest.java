// diagnostic/media/web/DiagnosticMediaSecurityTest.java
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
import com.example.autoauction.diagnostic.media.infrastructure.storage.FakeStorageClient;
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

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Set;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MEDIA-3.2 post-implementation audit, item 2: HTTP/security-layer coverage.
 * Uses the real Spring context (@SpringBootTest + @AutoConfigureMockMvc),
 * matching SecurityAccessTest's already-working convention - NOT
 * @WebMvcTest, which DiagnosticWorkControllerTest's pre-existing (unrelated,
 * not fixed here) missing-@MockBean failure shows is unreliable in this
 * codebase for controllers with multiple collaborators. Deliberately thin:
 * verifies HTTP status codes / wiring only. Business-rule correctness
 * (template membership, idempotency, magic-byte validation, etc.) is already
 * proven at the service level by DiagnosticMediaServiceIntegrationTest and is
 * not re-asserted here.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("MEDIA-3.2 audit: HTTP/security coverage")
class DiagnosticMediaSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private DiagnosticRequestRepository requestRepository;

    @Autowired
    private InspectionTemplateRepository templateRepository;

    @Autowired
    private DiagnosticMediaRepository mediaRepository;

    @Autowired
    private FakeStorageClient storageClient;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RoleRepository roleRepository;

    private Long createUser(String prefix) {
        Role userRole = roleRepository.findByName("ROLE_USER")
                .orElseGet(() -> roleRepository.save(new Role(null, "ROLE_USER", "Обычный пользователь")));
        User u = new User(null, prefix + System.nanoTime(), prefix + System.nanoTime() + "@test.com",
                "encoded", true, Set.of(userRole));
        return userRepository.save(u).getId();
    }

    private record Ctx(Long requestId, Long itemId, Long diagnosticId) {}

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
        i.setSortOrder(1);
        Long itemId = templateRepository.saveItem(i).getId();

        Long ownerId = createUser("owner");
        Long diagnosticId = createUser("diag");
        DiagnosticRequest request = new DiagnosticRequest();
        request.setUserId(ownerId);
        request.setBrand("BMW");
        request.setModel("X5");
        request.setYear(2022);
        request.setVin("S" + (System.nanoTime() % 100000000L));
        request.setDepositFrozen(new BigDecimal("10000"));
        request.setStatus(DiagnosticRequest.DiagnosticRequestStatus.IN_PROGRESS);
        request.setAssignedTo(diagnosticId);
        request.setCreatedAt(OffsetDateTime.now());
        request.setUpdatedAt(OffsetDateTime.now());
        Long requestId = requestRepository.save(request).getId();
        requestRepository.bindTemplateIfUnset(requestId, templateId);

        return new Ctx(requestId, itemId, diagnosticId);
    }

    private String intentBody() {
        return """
                {"mediaType":"PHOTO","mimeType":"image/jpeg","sizeBytes":1000}
                """;
    }

    @Test
    @DisplayName("Неаутентифицированный intent - 401")
    void createIntent_Unauthenticated_401() throws Exception {
        Ctx ctx = setUp();
        mockMvc.perform(post("/api/v1/diagnostic/work/{requestId}/items/{itemId}/media/intents",
                        ctx.requestId(), ctx.itemId())
                        .contentType("application/json")
                        .content(intentBody()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Неверный (не назначенный) диагност - 403")
    void createIntent_WrongDiagnostician_403() throws Exception {
        Ctx ctx = setUp();
        Long otherDiagnosticId = createUser("wrong");

        mockMvc.perform(post("/api/v1/diagnostic/work/{requestId}/items/{itemId}/media/intents",
                        ctx.requestId(), ctx.itemId())
                        .with(user(otherDiagnosticId, "DIAGNOSTIC"))
                        .contentType("application/json")
                        .content(intentBody()))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Правильный (назначенный) диагност - 201")
    void createIntent_CorrectDiagnostician_201() throws Exception {
        Ctx ctx = setUp();

        mockMvc.perform(post("/api/v1/diagnostic/work/{requestId}/items/{itemId}/media/intents",
                        ctx.requestId(), ctx.itemId())
                        .with(user(ctx.diagnosticId(), "DIAGNOSTIC"))
                        .contentType("application/json")
                        .content(intentBody()))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("Админ получает ссылку на медиафайл вне зависимости от назначения")
    void admin_CanReadAnyMedia_200() throws Exception {
        Ctx ctx = setUp();
        DiagnosticMedia media = new DiagnosticMedia();
        media.setDiagnosticRequestId(ctx.requestId());
        media.setInspectionItemId(ctx.itemId());
        media.setUploaderId(ctx.diagnosticId());
        media.setStorageKey("diagnostic-media/" + ctx.requestId() + "/admin-test.jpg");
        media.setMediaType(MediaType.PHOTO);
        media.setMimeType("image/jpeg");
        media.setSizeBytes(4L);
        media.setSortOrder(0);
        media.setUploadStatus(UploadStatus.READY);
        media.setProcessingStatus(ProcessingStatus.NOT_REQUIRED);
        Long mediaId = mediaRepository.save(media).getId();
        storageClient.simulateUpload(media.getStorageKey(),
                new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0x01}, "image/jpeg");

        Long adminId = createUser("admin");
        mockMvc.perform(get("/api/v1/diagnostic/media/{mediaId}/url", mediaId)
                        .with(user(adminId, "ADMIN")))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("Публичная ссылка для APPROVED отчёта - 200")
    void publicMediaUrl_ApprovedReport_200() throws Exception {
        Ctx ctx = setUp();
        DiagnosticMedia media = new DiagnosticMedia();
        media.setDiagnosticRequestId(ctx.requestId());
        media.setInspectionItemId(ctx.itemId());
        media.setUploaderId(ctx.diagnosticId());
        media.setStorageKey("diagnostic-media/" + ctx.requestId() + "/public-test.jpg");
        media.setMediaType(MediaType.PHOTO);
        media.setMimeType("image/jpeg");
        media.setSizeBytes(4L);
        media.setSortOrder(0);
        media.setUploadStatus(UploadStatus.READY);
        media.setProcessingStatus(ProcessingStatus.NOT_REQUIRED);
        Long mediaId = mediaRepository.save(media).getId();
        storageClient.simulateUpload(media.getStorageKey(),
                new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0x01}, "image/jpeg");

        DiagnosticRequest request = requestRepository.findById(ctx.requestId()).orElseThrow();
        request.setStatus(DiagnosticRequest.DiagnosticRequestStatus.APPROVED);
        requestRepository.save(request);

        mockMvc.perform(get("/api/v1/public/reports/media/{mediaId}/url", mediaId))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("Публичная ссылка для НЕ-APPROVED отчёта - 404 (не раскрывает существование)")
    void publicMediaUrl_NonApprovedReport_404() throws Exception {
        Ctx ctx = setUp(); // status is IN_PROGRESS, never approved
        DiagnosticMedia media = new DiagnosticMedia();
        media.setDiagnosticRequestId(ctx.requestId());
        media.setInspectionItemId(ctx.itemId());
        media.setUploaderId(ctx.diagnosticId());
        media.setStorageKey("diagnostic-media/" + ctx.requestId() + "/nonpublic-test.jpg");
        media.setMediaType(MediaType.PHOTO);
        media.setMimeType("image/jpeg");
        media.setSizeBytes(4L);
        media.setSortOrder(0);
        media.setUploadStatus(UploadStatus.READY);
        media.setProcessingStatus(ProcessingStatus.NOT_REQUIRED);
        Long mediaId = mediaRepository.save(media).getId();

        mockMvc.perform(get("/api/v1/public/reports/media/{mediaId}/url", mediaId))
                .andExpect(status().isNotFound());
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor user(Long userId, String role) {
        return org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
                .user(com.example.autoauction.auth.domain.TestUserPrincipal.createWithRoles(userId, "u" + userId, role));
    }
}

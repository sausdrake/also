// diagnostic/media/application/DiagnosticMediaServiceIntegrationTest.java
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
import com.example.autoauction.diagnostic.media.web.dto.MediaIntentResponse;
import com.example.autoauction.diagnostic.media.web.dto.MediaReadUrlResponse;
import com.example.autoauction.diagnostic.media.web.dto.MediaResponse;
import com.example.autoauction.user.domain.Role;
import com.example.autoauction.user.domain.User;
import com.example.autoauction.user.domain.port.RoleRepository;
import com.example.autoauction.user.domain.port.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * MEDIA-3.2: real Spring context + H2 + FakeStorageClient. Covers the
 * Phase 20 security matrix, Phase 21 integration scenarios, and the Phase 23
 * adversarial questions - one representative test per item, not a Mockito
 * simulation. The core invariant under test throughout: a DiagnosticMedia
 * record is never trusted merely because the client claims it belongs to a
 * diagnostic request - every operation independently re-derives
 * authorization, template membership, and (for finalize) actual object
 * validation.
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("MEDIA-3.2: DiagnosticMediaService")
class DiagnosticMediaServiceIntegrationTest {

    @Autowired
    private DiagnosticMediaService mediaService;

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

    private static final byte[] VALID_JPEG = new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0x01, 0x02, 0x03};
    private static final byte[] VALID_MP4 = new byte[]{0x00, 0x00, 0x00, 0x18, 'f', 't', 'y', 'p', 0x01};
    private static final byte[] VALID_WEBM = new byte[]{(byte) 0x1A, 0x45, (byte) 0xDF, (byte) 0xA3, 0x01, 0x02, 0x03, 0x04};
    private static final byte[] INVALID_CONTENT = "not a real image".getBytes(StandardCharsets.UTF_8);

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

    private Long createItem(Long templateId, boolean hasPhoto, boolean hasVideo) {
        InspectionGroup g = new InspectionGroup();
        g.setTemplateId(templateId);
        g.setName("G-" + System.nanoTime());
        g.setSortOrder(1);
        Long groupId = templateRepository.saveGroup(g).getId();

        InspectionItem i = new InspectionItem();
        i.setGroupId(groupId);
        i.setTitle("I-" + System.nanoTime());
        i.setFieldType("TEXT");
        i.setHasPhoto(hasPhoto);
        i.setHasVideo(hasVideo);
        i.setSortOrder(1);
        return templateRepository.saveItem(i).getId();
    }

    /** IN_PROGRESS request, bound to the given template, assigned to the given diagnostician. */
    private Long createBoundRequest(Long ownerId, Long diagnosticId, Long templateId) {
        DiagnosticRequest request = new DiagnosticRequest();
        request.setUserId(ownerId);
        request.setBrand("BMW");
        request.setModel("X5");
        request.setYear(2022);
        request.setVin("M" + (System.nanoTime() % 100000000L));
        request.setDepositFrozen(new BigDecimal("10000"));
        request.setStatus(DiagnosticRequest.DiagnosticRequestStatus.IN_PROGRESS);
        request.setAssignedTo(diagnosticId);
        request.setCreatedAt(OffsetDateTime.now());
        request.setUpdatedAt(OffsetDateTime.now());
        Long requestId = requestRepository.save(request).getId();
        requestRepository.bindTemplateIfUnset(requestId, templateId);
        return requestId;
    }

    private record Setup(Long ownerId, Long diagnosticId, Long templateId, Long itemId, Long requestId) {}

    private Setup setUpStandard(boolean hasPhoto, boolean hasVideo) {
        Long ownerId = createUser("owner");
        Long diagnosticId = createUser("diag");
        Long templateId = createTemplate();
        Long itemId = createItem(templateId, hasPhoto, hasVideo);
        Long requestId = createBoundRequest(ownerId, diagnosticId, templateId);
        return new Setup(ownerId, diagnosticId, templateId, itemId, requestId);
    }

    // ==================== Intent creation (Phase 20, items 1-9) ====================

    @Nested
    @DisplayName("Создание intent")
    class IntentCreationTests {

        @Test
        @DisplayName("Успешное создание intent для фото")
        void createIntent_Success() {
            Setup s = setUpStandard(true, false);
            MediaIntentResponse response = mediaService.createIntent(
                    s.requestId(), s.itemId(), s.diagnosticId(), MediaType.PHOTO, "image/jpeg", 1000L);

            assertThat(response.mediaId()).isNotNull();
            assertThat(response.uploadUrl()).isNotBlank();
            assertThat(response.uploadStatus()).isEqualTo(UploadStatus.PENDING_UPLOAD);

            DiagnosticMedia saved = mediaRepository.findById(response.mediaId()).orElseThrow();
            assertThat(saved.getUploaderId()).isEqualTo(s.diagnosticId());
            assertThat(saved.getDiagnosticRequestId()).isEqualTo(s.requestId());
            assertThat(saved.getStorageKey()).isNotBlank();
            assertThat(saved.getProcessingStatus()).isEqualTo(ProcessingStatus.NOT_REQUIRED);
        }

        @Test
        @DisplayName("1. Неназначенный диагност не может создать intent")
        void unassignedDiagnostician_Rejected() {
            Setup s = setUpStandard(true, false);
            Long otherDiagnostic = createUser("other");
            assertThatThrownBy(() -> mediaService.createIntent(
                    s.requestId(), s.itemId(), otherDiagnostic, MediaType.PHOTO, "image/jpeg", 1000L))
                    .isInstanceOf(SecurityException.class);
        }

        @Test
        @DisplayName("2. Диагност не может создать intent для чужой заявки (не назначенной ему)")
        void otherRequest_Rejected() {
            Setup s1 = setUpStandard(true, false);
            Setup s2 = setUpStandard(true, false);
            // s2's diagnostician tries to attach media to s1's request
            assertThatThrownBy(() -> mediaService.createIntent(
                    s1.requestId(), s1.itemId(), s2.diagnosticId(), MediaType.PHOTO, "image/jpeg", 1000L))
                    .isInstanceOf(SecurityException.class);
        }

        @Test
        @DisplayName("3. Пункт из другого шаблона отклоняется")
        void itemFromAnotherTemplate_Rejected() {
            Setup s = setUpStandard(true, false);
            Long otherTemplateId = createTemplate();
            Long otherItemId = createItem(otherTemplateId, true, false);

            assertThatThrownBy(() -> mediaService.createIntent(
                    s.requestId(), otherItemId, s.diagnosticId(), MediaType.PHOTO, "image/jpeg", 1000L))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("не принадлежит шаблону");
        }

        @Test
        @DisplayName("4. Видео отклоняется, если item.hasVideo == false")
        void videoWhenNotAllowed_Rejected() {
            Setup s = setUpStandard(true, false); // hasVideo=false
            assertThatThrownBy(() -> mediaService.createIntent(
                    s.requestId(), s.itemId(), s.diagnosticId(), MediaType.VIDEO, "video/mp4", 1000L))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("5. Фото отклоняется, если item.hasPhoto == false")
        void photoWhenNotAllowed_Rejected() {
            Setup s = setUpStandard(false, true); // hasPhoto=false
            assertThatThrownBy(() -> mediaService.createIntent(
                    s.requestId(), s.itemId(), s.diagnosticId(), MediaType.PHOTO, "image/jpeg", 1000L))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("6. Слишком большой файл отклоняется")
        void oversized_Rejected() {
            Setup s = setUpStandard(true, false);
            assertThatThrownBy(() -> mediaService.createIntent(
                    s.requestId(), s.itemId(), s.diagnosticId(), MediaType.PHOTO, "image/jpeg", 500L * 1024 * 1024))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("7. Неподдерживаемый MIME-тип отклоняется")
        void unsupportedMime_Rejected() {
            Setup s = setUpStandard(true, false);
            assertThatThrownBy(() -> mediaService.createIntent(
                    s.requestId(), s.itemId(), s.diagnosticId(), MediaType.PHOTO, "application/pdf", 1000L))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("WebM: intent для VIDEO + video/webm создаётся успешно, ключ хранилища получает .webm")
        void webmVideoIntent_Succeeds_WithWebmStorageKeySuffix() {
            Setup s = setUpStandard(false, true);
            MediaIntentResponse response = mediaService.createIntent(
                    s.requestId(), s.itemId(), s.diagnosticId(), MediaType.VIDEO, "video/webm", VALID_WEBM.length);

            assertThat(response.uploadStatus()).isEqualTo(UploadStatus.PENDING_UPLOAD);
            DiagnosticMedia saved = mediaRepository.findById(response.mediaId()).orElseThrow();
            assertThat(saved.getStorageKey()).endsWith(".webm");
        }

        @Test
        @DisplayName("Существующий MP4 intent по-прежнему получает .mp4 (регрессия)")
        void mp4VideoIntent_StillReceivesMp4StorageKeySuffix() {
            Setup s = setUpStandard(false, true);
            MediaIntentResponse response = mediaService.createIntent(
                    s.requestId(), s.itemId(), s.diagnosticId(), MediaType.VIDEO, "video/mp4", VALID_MP4.length);

            DiagnosticMedia saved = mediaRepository.findById(response.mediaId()).orElseThrow();
            assertThat(saved.getStorageKey()).endsWith(".mp4");
        }

        @Test
        @DisplayName("Неподдерживаемый видео-MIME по-прежнему отклоняется (video/avi)")
        void unsupportedVideoMime_StillRejected() {
            Setup s = setUpStandard(false, true);
            assertThatThrownBy(() -> mediaService.createIntent(
                    s.requestId(), s.itemId(), s.diagnosticId(), MediaType.VIDEO, "video/avi", 1000L))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("8/9. Ключ хранилища и uploaderId полностью определяются сервером " +
                "(нет способа для клиента их передать - метод createIntent не принимает такие параметры)")
        void storageKeyAndUploaderId_AlwaysServerDerived() {
            Setup s = setUpStandard(true, false);
            MediaIntentResponse response = mediaService.createIntent(
                    s.requestId(), s.itemId(), s.diagnosticId(), MediaType.PHOTO, "image/jpeg", 1000L);
            DiagnosticMedia saved = mediaRepository.findById(response.mediaId()).orElseThrow();
            assertThat(saved.getUploaderId()).isEqualTo(s.diagnosticId());
            assertThat(saved.getStorageKey()).startsWith("diagnostic-media/" + s.requestId() + "/");
        }

        @Test
        @DisplayName("Заявка без привязанного шаблона отклоняется")
        void noTemplateBound_Rejected() {
            Long ownerId = createUser("owner");
            Long diagnosticId = createUser("diag");
            DiagnosticRequest request = new DiagnosticRequest();
            request.setUserId(ownerId);
            request.setBrand("BMW");
            request.setModel("X5");
            request.setYear(2022);
            request.setVin("N" + (System.nanoTime() % 100000000L));
            request.setDepositFrozen(new BigDecimal("10000"));
            request.setStatus(DiagnosticRequest.DiagnosticRequestStatus.IN_PROGRESS);
            request.setAssignedTo(diagnosticId);
            request.setCreatedAt(OffsetDateTime.now());
            request.setUpdatedAt(OffsetDateTime.now());
            Long requestId = requestRepository.save(request).getId();
            // deliberately not bound

            assertThatThrownBy(() -> mediaService.createIntent(
                    requestId, 1L, diagnosticId, MediaType.PHOTO, "image/jpeg", 1000L))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    // ==================== Finalize (Phase 20 items 10-16, Phase 21 A-E) ====================

    @Nested
    @DisplayName("Finalize")
    class FinalizeTests {

        @Test
        @DisplayName("A: intent -> fake upload -> finalize -> READY")
        void intentUploadFinalize_Ready() {
            Setup s = setUpStandard(true, false);
            MediaIntentResponse intent = mediaService.createIntent(
                    s.requestId(), s.itemId(), s.diagnosticId(), MediaType.PHOTO, "image/jpeg", VALID_JPEG.length);
            DiagnosticMedia media = mediaRepository.findById(intent.mediaId()).orElseThrow();
            storageClient.simulateUpload(media.getStorageKey(), VALID_JPEG, "image/jpeg");

            MediaResponse result = mediaService.finalize(intent.mediaId(), s.diagnosticId());

            assertThat(result.uploadStatus()).isEqualTo(UploadStatus.READY);
            assertThat(result.mimeType()).isEqualTo("image/jpeg"); // server-detected, matches
            DiagnosticMedia finalMedia = mediaRepository.findById(intent.mediaId()).orElseThrow();
            assertThat(finalMedia.getSizeBytes()).isEqualTo((long) VALID_JPEG.length);
        }

        @Test
        @DisplayName("B: HEAD не находит объект -> FAILED")
        void missingObject_Failed() {
            Setup s = setUpStandard(true, false);
            MediaIntentResponse intent = mediaService.createIntent(
                    s.requestId(), s.itemId(), s.diagnosticId(), MediaType.PHOTO, "image/jpeg", 1000L);
            // never uploaded

            assertThatThrownBy(() -> mediaService.finalize(intent.mediaId(), s.diagnosticId()))
                    .isInstanceOf(IllegalStateException.class);
            assertThat(mediaRepository.findById(intent.mediaId()).orElseThrow().getUploadStatus())
                    .isEqualTo(UploadStatus.FAILED);
        }

        @Test
        @DisplayName("C/14: содержимое не проходит magic-byte проверку -> FAILED")
        void invalidContent_Failed() {
            Setup s = setUpStandard(true, false);
            MediaIntentResponse intent = mediaService.createIntent(
                    s.requestId(), s.itemId(), s.diagnosticId(), MediaType.PHOTO, "image/jpeg", INVALID_CONTENT.length);
            DiagnosticMedia media = mediaRepository.findById(intent.mediaId()).orElseThrow();
            storageClient.simulateUpload(media.getStorageKey(), INVALID_CONTENT, "image/jpeg");

            assertThatThrownBy(() -> mediaService.finalize(intent.mediaId(), s.diagnosticId()))
                    .isInstanceOf(IllegalStateException.class);
            assertThat(mediaRepository.findById(intent.mediaId()).orElseThrow().getUploadStatus())
                    .isEqualTo(UploadStatus.FAILED);
        }

        @Test
        @DisplayName("D: диагност отвязан от заявки между intent и finalize -> finalize отклонён")
        void unassignedBetweenIntentAndFinalize_Rejected() {
            Setup s = setUpStandard(true, false);
            MediaIntentResponse intent = mediaService.createIntent(
                    s.requestId(), s.itemId(), s.diagnosticId(), MediaType.PHOTO, "image/jpeg", VALID_JPEG.length);
            DiagnosticMedia media = mediaRepository.findById(intent.mediaId()).orElseThrow();
            storageClient.simulateUpload(media.getStorageKey(), VALID_JPEG, "image/jpeg");

            // reassign the request to someone else (simulates unassignment)
            Long otherDiagnostic = createUser("other");
            DiagnosticRequest request = requestRepository.findById(s.requestId()).orElseThrow();
            request.setAssignedTo(otherDiagnostic);
            requestRepository.save(request);

            assertThatThrownBy(() -> mediaService.finalize(intent.mediaId(), s.diagnosticId()))
                    .isInstanceOf(SecurityException.class);
        }

        @Test
        @DisplayName("E: статус заявки меняется между intent и finalize -> finalize отклонён")
        void statusChangedBetweenIntentAndFinalize_Rejected() {
            Setup s = setUpStandard(true, false);
            MediaIntentResponse intent = mediaService.createIntent(
                    s.requestId(), s.itemId(), s.diagnosticId(), MediaType.PHOTO, "image/jpeg", VALID_JPEG.length);
            DiagnosticMedia media = mediaRepository.findById(intent.mediaId()).orElseThrow();
            storageClient.simulateUpload(media.getStorageKey(), VALID_JPEG, "image/jpeg");

            DiagnosticRequest request = requestRepository.findById(s.requestId()).orElseThrow();
            request.setStatus(DiagnosticRequest.DiagnosticRequestStatus.COMPLETED);
            requestRepository.save(request);

            assertThatThrownBy(() -> mediaService.finalize(intent.mediaId(), s.diagnosticId()))
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("10. Finalize другим пользователем отклонён")
        void finalizeByDifferentUser_Rejected() {
            Setup s = setUpStandard(true, false);
            MediaIntentResponse intent = mediaService.createIntent(
                    s.requestId(), s.itemId(), s.diagnosticId(), MediaType.PHOTO, "image/jpeg", VALID_JPEG.length);
            DiagnosticMedia media = mediaRepository.findById(intent.mediaId()).orElseThrow();
            storageClient.simulateUpload(media.getStorageKey(), VALID_JPEG, "image/jpeg");

            Long otherDiagnostic = createUser("other2");
            // make otherDiagnostic assigned to a DIFFERENT request so it passes the assignment
            // check structurally but must still fail on media.uploaderId mismatch if it were
            // somehow assigned to the SAME request - here we simply confirm a non-assigned
            // caller is rejected (covered structurally by loadRequestAssignedTo).
            assertThatThrownBy(() -> mediaService.finalize(intent.mediaId(), otherDiagnostic))
                    .isInstanceOf(SecurityException.class);
        }

        @Test
        @DisplayName("16/26. Повторный finalize идемпотентен - не дублирует состояние")
        void duplicateFinalize_Idempotent() {
            Setup s = setUpStandard(true, false);
            MediaIntentResponse intent = mediaService.createIntent(
                    s.requestId(), s.itemId(), s.diagnosticId(), MediaType.PHOTO, "image/jpeg", VALID_JPEG.length);
            DiagnosticMedia media = mediaRepository.findById(intent.mediaId()).orElseThrow();
            storageClient.simulateUpload(media.getStorageKey(), VALID_JPEG, "image/jpeg");

            MediaResponse first = mediaService.finalize(intent.mediaId(), s.diagnosticId());
            MediaResponse second = mediaService.finalize(intent.mediaId(), s.diagnosticId());

            assertThat(first.uploadStatus()).isEqualTo(UploadStatus.READY);
            assertThat(second.uploadStatus()).isEqualTo(UploadStatus.READY);
        }

        @Test
        @DisplayName("18. Размер проверяется по ФАКТИЧЕСКОМУ объекту (HEAD), а не по " +
                "заявленному клиентом значению при intent")
        void sizeCheckedFromActualObject_NotClientDeclaration() {
            Setup s = setUpStandard(true, false);
            // client declares a tiny size...
            MediaIntentResponse intent = mediaService.createIntent(
                    s.requestId(), s.itemId(), s.diagnosticId(), MediaType.PHOTO, "image/jpeg", 10L);
            DiagnosticMedia media = mediaRepository.findById(intent.mediaId()).orElseThrow();
            // ...but the actual uploaded content's real size is what finalize checks (via HEAD),
            // proven by the fact the server-persisted sizeBytes below equals the REAL content
            // length, not the client's declared 10L.
            storageClient.simulateUpload(media.getStorageKey(), VALID_JPEG, "image/jpeg");

            mediaService.finalize(intent.mediaId(), s.diagnosticId());

            assertThat(mediaRepository.findById(intent.mediaId()).orElseThrow().getSizeBytes())
                    .as("persisted size must be the actual object size, not the client's original declaration")
                    .isEqualTo((long) VALID_JPEG.length)
                    .isNotEqualTo(10L);
        }

        @Test
        @DisplayName("Видео с валидными magic bytes (ftyp) -> READY, processingStatus остаётся PENDING")
        void validVideo_ReadyButProcessingPending() {
            Setup s = setUpStandard(false, true);
            MediaIntentResponse intent = mediaService.createIntent(
                    s.requestId(), s.itemId(), s.diagnosticId(), MediaType.VIDEO, "video/mp4", VALID_MP4.length);
            DiagnosticMedia media = mediaRepository.findById(intent.mediaId()).orElseThrow();
            assertThat(media.getProcessingStatus()).isEqualTo(ProcessingStatus.PENDING);
            storageClient.simulateUpload(media.getStorageKey(), VALID_MP4, "video/mp4");

            MediaResponse result = mediaService.finalize(intent.mediaId(), s.diagnosticId());

            assertThat(result.uploadStatus()).isEqualTo(UploadStatus.READY);
            assertThat(result.processingStatus()).isEqualTo(ProcessingStatus.PENDING);
            assertThat(result.durationSeconds()).isNull();
            assertThat(result.width()).isNull();
            assertThat(result.height()).isNull();
        }

        @Test
        @DisplayName("WebM: валидные magic bytes (EBML) -> READY, processingStatus остаётся PENDING")
        void validWebmVideo_ReadyButProcessingPending() {
            Setup s = setUpStandard(false, true);
            MediaIntentResponse intent = mediaService.createIntent(
                    s.requestId(), s.itemId(), s.diagnosticId(), MediaType.VIDEO, "video/webm", VALID_WEBM.length);
            DiagnosticMedia media = mediaRepository.findById(intent.mediaId()).orElseThrow();
            storageClient.simulateUpload(media.getStorageKey(), VALID_WEBM, "video/webm");

            MediaResponse result = mediaService.finalize(intent.mediaId(), s.diagnosticId());

            assertThat(result.uploadStatus()).isEqualTo(UploadStatus.READY);
            assertThat(result.mimeType()).isEqualTo("video/webm");
            assertThat(result.processingStatus()).isEqualTo(ProcessingStatus.PENDING);
        }

        // NOTE: finalize() validates detected content against the media CATEGORY allow-list
        // (PHOTO vs VIDEO), never against the client's originally-declared MIME string - this
        // is a pre-existing characteristic (already true for PHOTO_MIME_TYPES's two members
        // since this class's original design), unrelated to and unchanged by WebM support. The
        // tests below therefore cover cross-CATEGORY rejection (photo content in a video slot),
        // which is the actual, real content-validation boundary this class enforces - not
        // cross-type-within-category equality, which was never part of this contract.

        @Test
        @DisplayName("VIDEO intent, но фактическое содержимое - JPEG -> FAILED (другая категория медиа)")
        void videoIntentActualJpegContent_Failed_CrossCategoryRejected() {
            Setup s = setUpStandard(false, true);
            MediaIntentResponse intent = mediaService.createIntent(
                    s.requestId(), s.itemId(), s.diagnosticId(), MediaType.VIDEO, "video/webm", VALID_JPEG.length);
            DiagnosticMedia media = mediaRepository.findById(intent.mediaId()).orElseThrow();
            storageClient.simulateUpload(media.getStorageKey(), VALID_JPEG, "video/webm");

            assertThatThrownBy(() -> mediaService.finalize(intent.mediaId(), s.diagnosticId()))
                    .isInstanceOf(IllegalStateException.class);
            assertThat(mediaRepository.findById(intent.mediaId()).orElseThrow().getUploadStatus())
                    .isEqualTo(UploadStatus.FAILED);
        }

        @Test
        @DisplayName("VIDEO intent, но фактическое содержимое - PNG -> FAILED (другая категория медиа)")
        void videoIntentActualPngContent_Failed_CrossCategoryRejected() {
            byte[] validPng = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
            Setup s = setUpStandard(false, true);
            MediaIntentResponse intent = mediaService.createIntent(
                    s.requestId(), s.itemId(), s.diagnosticId(), MediaType.VIDEO, "video/mp4", validPng.length);
            DiagnosticMedia media = mediaRepository.findById(intent.mediaId()).orElseThrow();
            storageClient.simulateUpload(media.getStorageKey(), validPng, "video/mp4");

            assertThatThrownBy(() -> mediaService.finalize(intent.mediaId(), s.diagnosticId()))
                    .isInstanceOf(IllegalStateException.class);
            assertThat(mediaRepository.findById(intent.mediaId()).orElseThrow().getUploadStatus())
                    .isEqualTo(UploadStatus.FAILED);
        }

        @Test
        @DisplayName("Заявлен video/webm, случайные байты -> FAILED (не распознано)")
        void declaredWebmRandomBytes_Failed() {
            Setup s = setUpStandard(false, true);
            MediaIntentResponse intent = mediaService.createIntent(
                    s.requestId(), s.itemId(), s.diagnosticId(), MediaType.VIDEO, "video/webm", INVALID_CONTENT.length);
            DiagnosticMedia media = mediaRepository.findById(intent.mediaId()).orElseThrow();
            storageClient.simulateUpload(media.getStorageKey(), INVALID_CONTENT, "video/webm");

            assertThatThrownBy(() -> mediaService.finalize(intent.mediaId(), s.diagnosticId()))
                    .isInstanceOf(IllegalStateException.class);
            assertThat(mediaRepository.findById(intent.mediaId()).orElseThrow().getUploadStatus())
                    .isEqualTo(UploadStatus.FAILED);
        }
    }

    // ==================== Read URL (Phase 20 items 17-19, 22) ====================

    @Nested
    @DisplayName("Read URL")
    class ReadUrlTests {

        private Long finalizedMedia(Setup s) {
            MediaIntentResponse intent = mediaService.createIntent(
                    s.requestId(), s.itemId(), s.diagnosticId(), MediaType.PHOTO, "image/jpeg", VALID_JPEG.length);
            DiagnosticMedia media = mediaRepository.findById(intent.mediaId()).orElseThrow();
            storageClient.simulateUpload(media.getStorageKey(), VALID_JPEG, "image/jpeg");
            mediaService.finalize(intent.mediaId(), s.diagnosticId());
            return intent.mediaId();
        }

        @Test
        @DisplayName("Владелец, назначенный диагност и админ получают ссылку")
        void ownerDiagnosticianAdmin_Succeed() {
            Setup s = setUpStandard(true, false);
            Long mediaId = finalizedMedia(s);

            MediaReadUrlResponse asOwner = mediaService.getReadUrl(mediaId, s.ownerId(), false);
            MediaReadUrlResponse asDiagnostician = mediaService.getReadUrl(mediaId, s.diagnosticId(), false);
            MediaReadUrlResponse asAdmin = mediaService.getReadUrl(mediaId, 999_999L, true);

            assertThat(asOwner.url()).isNotBlank();
            assertThat(asDiagnostician.url()).isNotBlank();
            assertThat(asAdmin.url()).isNotBlank();
        }

        @Test
        @DisplayName("17. Другой пользователь без отношения к заявке отклонён")
        void unrelatedUser_Rejected() {
            Setup s = setUpStandard(true, false);
            Long mediaId = finalizedMedia(s);
            Long strangerId = createUser("stranger");

            assertThatThrownBy(() -> mediaService.getReadUrl(mediaId, strangerId, false))
                    .isInstanceOf(SecurityException.class);
        }

        @Test
        @DisplayName("18/19. Публичный доступ: успех для APPROVED, отклонён для не-APPROVED")
        void publicAccess_OnlyForApproved() {
            Setup s = setUpStandard(true, false);
            Long mediaId = finalizedMedia(s);

            assertThatThrownBy(() -> mediaService.getReadUrlForApprovedReport(mediaId))
                    .isInstanceOf(IllegalStateException.class); // still IN_PROGRESS

            DiagnosticRequest request = requestRepository.findById(s.requestId()).orElseThrow();
            request.setStatus(DiagnosticRequest.DiagnosticRequestStatus.APPROVED);
            requestRepository.save(request);

            MediaReadUrlResponse response = mediaService.getReadUrlForApprovedReport(mediaId);
            assertThat(response.url()).isNotBlank();
        }

        @Test
        @DisplayName("22. Удалённый медиафайл не возвращает обычную ссылку на чтение")
        void deletedMedia_NoNormalReadUrl() {
            Setup s = setUpStandard(true, false);
            Long mediaId = finalizedMedia(s);
            mediaService.delete(mediaId, s.diagnosticId(), false);

            assertThatThrownBy(() -> mediaService.getReadUrl(mediaId, s.ownerId(), false))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    // ==================== Thumbnail Read URL (MEDIA-3.4 Phase 11) ====================

    @Nested
    @DisplayName("Thumbnail Read URL")
    class ThumbnailReadUrlTests {

        /** Directly constructs a VIDEO row in the desired state - claim/process/finalize is Phase 6's concern, not this test's. */
        private Long videoMedia(Setup s, ProcessingStatus processingStatus, String thumbnailStorageKey) {
            DiagnosticMedia media = new DiagnosticMedia();
            media.setDiagnosticRequestId(s.requestId());
            media.setInspectionItemId(s.itemId());
            media.setUploaderId(s.diagnosticId());
            media.setStorageKey("diagnostic-media/" + s.requestId() + "/" + UUID.randomUUID() + ".mp4");
            media.setMediaType(MediaType.VIDEO);
            media.setMimeType("video/mp4");
            media.setSizeBytes((long) VALID_MP4.length);
            media.setSortOrder(0);
            media.setUploadStatus(UploadStatus.READY);
            media.setProcessingStatus(processingStatus);
            media.setThumbnailStorageKey(thumbnailStorageKey);
            return mediaRepository.save(media).getId();
        }

        private String thumbnailKey(Long requestId) {
            return "diagnostic-media-thumbnail/" + requestId + "/" + UUID.randomUUID() + ".jpg";
        }

        @Test
        @DisplayName("MEDIA-PROD-01: the public report serves only a processed video - never PENDING / PROCESSING / FAILED")
        void publicReport_OnlyProcessedVideo() {
            Setup s = setUpStandard(false, true);
            Long pending = videoMedia(s, ProcessingStatus.PENDING, null);
            Long processing = videoMedia(s, ProcessingStatus.PROCESSING, null);
            Long failed = videoMedia(s, ProcessingStatus.FAILED, null);
            Long ready = videoMedia(s, ProcessingStatus.READY, thumbnailKey(s.requestId()));
            DiagnosticRequest request = requestRepository.findById(s.requestId()).orElseThrow();
            request.setStatus(DiagnosticRequest.DiagnosticRequestStatus.APPROVED);
            requestRepository.save(request);

            for (Long id : List.of(pending, processing, failed)) {
                assertThatThrownBy(() -> mediaService.getReadUrlForApprovedReport(id)).as("media %s", id)
                        .isInstanceOf(IllegalStateException.class);
            }
            assertThat(mediaService.getReadUrlForApprovedReport(ready).url()).isNotBlank();
            // the assigned diagnostician / admin still reach a failed video (to see why it failed)
            assertThat(mediaService.getReadUrl(failed, s.diagnosticId(), false).url()).isNotBlank();
        }

        @Test
        @DisplayName("Владелец, назначенный диагност и админ получают ссылку на превью")
        void ownerDiagnosticianAdmin_Succeed() {
            Setup s = setUpStandard(false, true);
            String key = thumbnailKey(s.requestId());
            Long mediaId = videoMedia(s, ProcessingStatus.READY, key);

            MediaReadUrlResponse asOwner = mediaService.getThumbnailReadUrl(mediaId, s.ownerId(), false);
            MediaReadUrlResponse asDiagnostician = mediaService.getThumbnailReadUrl(mediaId, s.diagnosticId(), false);
            MediaReadUrlResponse asAdmin = mediaService.getThumbnailReadUrl(mediaId, 999_999L, true);

            assertThat(asOwner.url()).contains(key);
            assertThat(asDiagnostician.url()).contains(key);
            assertThat(asAdmin.url()).contains(key);
        }

        @Test
        @DisplayName("Посторонний пользователь отклонён (SecurityException, как у getReadUrl)")
        void unrelatedUser_Rejected() {
            Setup s = setUpStandard(false, true);
            Long mediaId = videoMedia(s, ProcessingStatus.READY, thumbnailKey(s.requestId()));
            Long strangerId = createUser("stranger-thumb");

            assertThatThrownBy(() -> mediaService.getThumbnailReadUrl(mediaId, strangerId, false))
                    .isInstanceOf(SecurityException.class);
        }

        @Test
        @DisplayName("thumbnailStorageKey == null -> IllegalStateException (409), не NPE")
        void nullThumbnailKey_ThrowsIllegalState_NotNpe() {
            Setup s = setUpStandard(false, true);
            Long mediaId = videoMedia(s, ProcessingStatus.PENDING, null);

            assertThatThrownBy(() -> mediaService.getThumbnailReadUrl(mediaId, s.ownerId(), false))
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("Копипаст-защита: URL построен из thumbnailStorageKey, а не storageKey")
        void copyPasteGuard_UsesThumbnailKeyNotOriginalKey() {
            Setup s = setUpStandard(false, true);
            String thumbKey = thumbnailKey(s.requestId());
            Long mediaId = videoMedia(s, ProcessingStatus.READY, thumbKey);
            DiagnosticMedia saved = mediaRepository.findById(mediaId).orElseThrow();

            MediaReadUrlResponse response = mediaService.getThumbnailReadUrl(mediaId, s.ownerId(), false);

            assertThat(response.url()).contains(thumbKey);
            assertThat(response.url()).doesNotContain(saved.getStorageKey());
        }

        @Test
        @DisplayName("Используется существующий diagnostic.media.read-url-ttl-minutes (то же значение, что и у getReadUrl)")
        void usesExistingReadUrlTtl_SameAsOriginalMediaEndpoint() {
            Setup s = setUpStandard(true, true);
            MediaIntentResponse intent = mediaService.createIntent(
                    s.requestId(), s.itemId(), s.diagnosticId(), MediaType.PHOTO, "image/jpeg", VALID_JPEG.length);
            DiagnosticMedia photoMedia = mediaRepository.findById(intent.mediaId()).orElseThrow();
            storageClient.simulateUpload(photoMedia.getStorageKey(), VALID_JPEG, "image/jpeg");
            mediaService.finalize(intent.mediaId(), s.diagnosticId());
            Long photoMediaId = intent.mediaId();
            String thumbKey = thumbnailKey(s.requestId());
            Long videoMediaId = videoMedia(s, ProcessingStatus.READY, thumbKey);

            MediaReadUrlResponse originalUrlResponse = mediaService.getReadUrl(photoMediaId, s.ownerId(), false);
            MediaReadUrlResponse thumbnailUrlResponse = mediaService.getThumbnailReadUrl(videoMediaId, s.ownerId(), false);

            // No new/separate TTL was introduced for the thumbnail path (Phase 11 scope
            // control forbids a new configuration property) - both calls compute
            // Instant.now().plus(readUrlTtl) from the SAME injected field, back-to-back, so
            // their expiresAt values must be within a couple of seconds of each other. If the
            // thumbnail path ever used a different TTL, this gap would be minutes, not seconds.
            java.time.Duration gap = java.time.Duration.between(originalUrlResponse.expiresAt(), thumbnailUrlResponse.expiresAt()).abs();
            assertThat(gap).isLessThan(java.time.Duration.ofSeconds(5));
        }

        @Test
        @DisplayName("Чтение превью не мутирует uploadStatus/processingStatus/thumbnailStorageKey")
        void readingThumbnailUrl_DoesNotMutateState() {
            Setup s = setUpStandard(false, true);
            String key = thumbnailKey(s.requestId());
            Long mediaId = videoMedia(s, ProcessingStatus.READY, key);

            mediaService.getThumbnailReadUrl(mediaId, s.ownerId(), false);

            DiagnosticMedia after = mediaRepository.findById(mediaId).orElseThrow();
            assertThat(after.getUploadStatus()).isEqualTo(UploadStatus.READY);
            assertThat(after.getProcessingStatus()).isEqualTo(ProcessingStatus.READY);
            assertThat(after.getThumbnailStorageKey()).isEqualTo(key);
        }

        @Test
        @DisplayName("23. Гонка с retryFailedProcessing: чтение превью само по себе не меняет processingStatus")
        void raceWithRetry_ThumbnailReadDoesNotMutateProcessingStatus() {
            Setup s = setUpStandard(false, true);
            Long mediaId = videoMedia(s, ProcessingStatus.FAILED, null);

            // A FAILED->PENDING retry happens "concurrently" (sequential here, per the phase's
            // lightweight-concurrency allowance) - the read call itself must never resurrect or
            // otherwise change processingStatus, regardless of ordering.
            boolean retried = mediaRepository.retryFailedProcessing(mediaId);
            assertThat(retried).isTrue();

            assertThatThrownBy(() -> mediaService.getThumbnailReadUrl(mediaId, s.ownerId(), false))
                    .isInstanceOf(IllegalStateException.class); // still no thumbnail - PENDING now, not READY+thumbnail

            DiagnosticMedia after = mediaRepository.findById(mediaId).orElseThrow();
            assertThat(after.getProcessingStatus()).isEqualTo(ProcessingStatus.PENDING); // unchanged by the read attempt
        }

        @Test
        @DisplayName("24. Гонка с восстановлением зависшего PROCESSING: чтение превью не меняет processingStatus")
        void raceWithStaleRecovery_ThumbnailReadDoesNotMutateProcessingStatus() {
            Setup s = setUpStandard(false, true);
            Long mediaId = videoMedia(s, ProcessingStatus.PENDING, null);
            boolean claimed = mediaRepository.claimForProcessing(mediaId);
            assertThat(claimed).isTrue();

            // Thumbnail read attempted while the row is genuinely PROCESSING (pre-stale-recovery).
            assertThatThrownBy(() -> mediaService.getThumbnailReadUrl(mediaId, s.ownerId(), false))
                    .isInstanceOf(IllegalStateException.class);

            // Stale recovery then resets it (simulating the scheduler's reconcileStale()).
            boolean reset = mediaRepository.resetStaleProcessing(mediaId);
            assertThat(reset).isTrue();

            DiagnosticMedia after = mediaRepository.findById(mediaId).orElseThrow();
            assertThat(after.getProcessingStatus()).isEqualTo(ProcessingStatus.PENDING); // reset succeeded, untouched by the earlier read
        }
    }

    // ==================== Delete (Phase 20 items 20-21, adversarial 9) ====================

    @Nested
    @DisplayName("Delete")
    class DeleteTests {

        @Test
        @DisplayName("20. Удаление другим пользователем отклонено")
        void deleteByOtherUser_Rejected() {
            Setup s = setUpStandard(true, false);
            MediaIntentResponse intent = mediaService.createIntent(
                    s.requestId(), s.itemId(), s.diagnosticId(), MediaType.PHOTO, "image/jpeg", 1000L);
            Long stranger = createUser("stranger2");

            assertThatThrownBy(() -> mediaService.delete(intent.mediaId(), stranger, false))
                    .isInstanceOf(SecurityException.class);
        }

        @Test
        @DisplayName("21. Удаление авторизованным пользователем успешно")
        void deleteByAuthorizedUser_Succeeds() {
            Setup s = setUpStandard(true, false);
            MediaIntentResponse intent = mediaService.createIntent(
                    s.requestId(), s.itemId(), s.diagnosticId(), MediaType.PHOTO, "image/jpeg", 1000L);

            mediaService.delete(intent.mediaId(), s.diagnosticId(), false);

            assertThat(mediaRepository.findById(intent.mediaId()).orElseThrow().getUploadStatus())
                    .isEqualTo(UploadStatus.DELETED);
        }

        @Test
        @DisplayName("Adversarial 9: повторное удаление безопасно (идемпотентно), не является ошибкой")
        void doubleDelete_Idempotent() {
            Setup s = setUpStandard(true, false);
            MediaIntentResponse intent = mediaService.createIntent(
                    s.requestId(), s.itemId(), s.diagnosticId(), MediaType.PHOTO, "image/jpeg", 1000L);

            mediaService.delete(intent.mediaId(), s.diagnosticId(), false);
            mediaService.delete(intent.mediaId(), s.diagnosticId(), false); // must not throw

            assertThat(mediaRepository.findById(intent.mediaId()).orElseThrow().getUploadStatus())
                    .isEqualTo(UploadStatus.DELETED);
        }
    }

    // ==================== Ordering (Phase 20 items 23-25) ====================

    @Nested
    @DisplayName("Ordering")
    class OrderingTests {

        @Test
        @DisplayName("H: полная замена порядка")
        void reorder_FullReplacement() {
            Setup s = setUpStandard(true, false);
            MediaIntentResponse m1 = mediaService.createIntent(s.requestId(), s.itemId(), s.diagnosticId(), MediaType.PHOTO, "image/jpeg", 1000L);
            MediaIntentResponse m2 = mediaService.createIntent(s.requestId(), s.itemId(), s.diagnosticId(), MediaType.PHOTO, "image/jpeg", 1000L);
            MediaIntentResponse m3 = mediaService.createIntent(s.requestId(), s.itemId(), s.diagnosticId(), MediaType.PHOTO, "image/jpeg", 1000L);

            mediaService.reorder(s.requestId(), s.itemId(), s.diagnosticId(), List.of(m3.mediaId(), m1.mediaId(), m2.mediaId()));

            List<DiagnosticMedia> ordered = mediaRepository.findByRequestAndItem(s.requestId(), s.itemId());
            assertThat(ordered).extracting(DiagnosticMedia::getId)
                    .containsExactly(m3.mediaId(), m1.mediaId(), m2.mediaId());
        }

        @Test
        @DisplayName("23. Нельзя включить медиафайл с другой заявки")
        void mediaFromAnotherRequest_Rejected() {
            Setup s1 = setUpStandard(true, false);
            Setup s2 = setUpStandard(true, false);
            MediaIntentResponse m1 = mediaService.createIntent(s1.requestId(), s1.itemId(), s1.diagnosticId(), MediaType.PHOTO, "image/jpeg", 1000L);
            MediaIntentResponse mOther = mediaService.createIntent(s2.requestId(), s2.itemId(), s2.diagnosticId(), MediaType.PHOTO, "image/jpeg", 1000L);

            assertThatThrownBy(() -> mediaService.reorder(
                    s1.requestId(), s1.itemId(), s1.diagnosticId(), List.of(m1.mediaId(), mOther.mediaId())))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("24. Нельзя включить медиафайл с другого пункта")
        void mediaFromAnotherItem_Rejected() {
            Setup s = setUpStandard(true, false);
            Long otherItemId = createItem(s.templateId(), true, false);
            MediaIntentResponse m1 = mediaService.createIntent(s.requestId(), s.itemId(), s.diagnosticId(), MediaType.PHOTO, "image/jpeg", 1000L);
            MediaIntentResponse mOtherItem = mediaService.createIntent(s.requestId(), otherItemId, s.diagnosticId(), MediaType.PHOTO, "image/jpeg", 1000L);

            assertThatThrownBy(() -> mediaService.reorder(
                    s.requestId(), s.itemId(), s.diagnosticId(), List.of(m1.mediaId(), mOtherItem.mediaId())))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("25. Дубликаты ID в списке отклоняются")
        void duplicateIds_Rejected() {
            Setup s = setUpStandard(true, false);
            MediaIntentResponse m1 = mediaService.createIntent(s.requestId(), s.itemId(), s.diagnosticId(), MediaType.PHOTO, "image/jpeg", 1000L);

            assertThatThrownBy(() -> mediaService.reorder(
                    s.requestId(), s.itemId(), s.diagnosticId(), List.of(m1.mediaId(), m1.mediaId())))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    // ==================== Adversarial: template deactivation ====================

    @Nested
    @DisplayName("Adversarial: деактивация шаблона")
    class TemplateDeactivationTests {

        @Test
        @DisplayName("F/11: деактивация привязанного шаблона не ломает авторизацию медиа")
        void deactivatedBoundTemplate_MediaAuthorizationUnaffected() {
            Setup s = setUpStandard(true, false);
            MediaIntentResponse intent = mediaService.createIntent(
                    s.requestId(), s.itemId(), s.diagnosticId(), MediaType.PHOTO, "image/jpeg", VALID_JPEG.length);
            DiagnosticMedia media = mediaRepository.findById(intent.mediaId()).orElseThrow();
            storageClient.simulateUpload(media.getStorageKey(), VALID_JPEG, "image/jpeg");

            InspectionTemplate template = templateRepository.findTemplateById(s.templateId()).orElseThrow();
            template.setActive(false);
            templateRepository.saveTemplate(template);

            // finalize still works - authorization never checks template.isActive, only the
            // item->group->templateId chain against the request's immutable binding.
            MediaResponse result = mediaService.finalize(intent.mediaId(), s.diagnosticId());
            assertThat(result.uploadStatus()).isEqualTo(UploadStatus.READY);
        }

        @Test
        @DisplayName("12: заявка с templateId=NULL (нерешённая историческая) не может создавать DiagnosticMedia")
        void unresolvedHistoricalRequest_CannotCreateMedia() {
            Long ownerId = createUser("owner3");
            Long diagnosticId = createUser("diag3");
            DiagnosticRequest request = new DiagnosticRequest();
            request.setUserId(ownerId);
            request.setBrand("BMW");
            request.setModel("X5");
            request.setYear(2022);
            request.setVin("U" + (System.nanoTime() % 100000000L));
            request.setDepositFrozen(new BigDecimal("10000"));
            request.setStatus(DiagnosticRequest.DiagnosticRequestStatus.IN_PROGRESS);
            request.setAssignedTo(diagnosticId);
            request.setCreatedAt(OffsetDateTime.now());
            request.setUpdatedAt(OffsetDateTime.now());
            Long requestId = requestRepository.save(request).getId();
            // templateId deliberately left NULL

            assertThatThrownBy(() -> mediaService.createIntent(
                    requestId, 1L, diagnosticId, MediaType.PHOTO, "image/jpeg", 1000L))
                    .isInstanceOf(IllegalStateException.class);
        }
    }
}

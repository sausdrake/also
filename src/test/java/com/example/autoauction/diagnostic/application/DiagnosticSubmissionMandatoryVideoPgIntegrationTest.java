package com.example.autoauction.diagnostic.application;

import com.example.autoauction.diagnostic.domain.DiagnosticRequest;
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
import com.example.autoauction.diagnostic.web.dto.InspectionAnswerDto;
import com.example.autoauction.diagnostic.web.dto.SubmitInspectionRequest;
import com.example.autoauction.support.AbstractPostgresIntegrationTest;
import com.example.autoauction.user.domain.Role;
import com.example.autoauction.user.domain.User;
import com.example.autoauction.user.domain.port.RoleRepository;
import com.example.autoauction.user.domain.port.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Mandatory diagnostic video completion gate: real PostgreSQL proof that
 * DiagnosticMediaRepository.existsQualifyingVideo's SQL semantics (exact
 * request+item+mediaType+uploadStatus+processingStatus scoping) and the
 * completion transaction's rollback behavior hold against a real database -
 * not a Mockito simulation of a boolean return value. Complements
 * DiagnosticRequestServiceTest's mocked coverage of the service-level logic.
 */
@DisplayName("Обязательное видео при завершении заявки - реальный PostgreSQL")
class DiagnosticSubmissionMandatoryVideoPgIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private DiagnosticRequestService requestService;

    @Autowired
    private DiagnosticMediaRepository mediaRepository;

    @Autowired
    private DiagnosticRequestRepository requestRepository;

    @Autowired
    private InspectionTemplateRepository templateRepository;

    @Autowired
    private InspectionAnswerRepository answerRepository;

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

    private Long createTemplate() {
        InspectionTemplate t = new InspectionTemplate();
        t.setName("T-" + System.nanoTime());
        t.setActive(true);
        return templateRepository.saveTemplate(t).getId();
    }

    private Long createGroup(Long templateId) {
        InspectionGroup g = new InspectionGroup();
        g.setTemplateId(templateId);
        g.setName("G-" + System.nanoTime());
        g.setSortOrder(1);
        return templateRepository.saveGroup(g).getId();
    }

    private Long createItem(Long groupId, boolean hasVideo) {
        InspectionItem i = new InspectionItem();
        i.setGroupId(groupId);
        i.setTitle("I-" + System.nanoTime());
        i.setFieldType("TEXT");
        i.setHasPhoto(false);
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

    /** Directly constructs a VIDEO row in the desired state - claim/process/finalize is a separate, unmodified concern. */
    private Long videoMedia(Long requestId, Long itemId, Long uploaderId, UploadStatus uploadStatus, ProcessingStatus processingStatus) {
        DiagnosticMedia media = new DiagnosticMedia();
        media.setDiagnosticRequestId(requestId);
        media.setInspectionItemId(itemId);
        media.setUploaderId(uploaderId);
        media.setStorageKey("diagnostic-media/" + requestId + "/" + UUID.randomUUID() + ".mp4");
        media.setMediaType(MediaType.VIDEO);
        media.setMimeType("video/mp4");
        media.setSizeBytes(1024L);
        media.setSortOrder(0);
        media.setUploadStatus(uploadStatus);
        media.setProcessingStatus(processingStatus);
        return mediaRepository.save(media).getId();
    }

    private SubmitInspectionRequest submit() {
        SubmitInspectionRequest dto = new SubmitInspectionRequest();
        dto.setSubmit(true);
        dto.setAnswers(List.of());
        return dto;
    }

    private record Fixture(Long ownerId, Long diagnosticId, Long templateId, Long itemId, Long requestId) {}

    private Fixture setUp(boolean hasVideo) {
        Long ownerId = createUser("owner");
        Long diagnosticId = createUser("diag");
        Long templateId = createTemplate();
        Long groupId = createGroup(templateId);
        Long itemId = createItem(groupId, hasVideo);
        Long requestId = createBoundRequest(ownerId, diagnosticId, templateId);
        return new Fixture(ownerId, diagnosticId, templateId, itemId, requestId);
    }

    // ==================== A: no required video ====================

    @Test
    @DisplayName("Требуемое видео отсутствует полностью - завершение отклонено, статус остаётся IN_PROGRESS")
    void noVideoAtAll_SubmissionRejected() {
        Fixture f = setUp(true);

        assertThatThrownBy(() -> requestService.saveInspectionAnswers(f.requestId(), f.diagnosticId(), submit()))
                .isInstanceOf(IllegalStateException.class);

        assertThat(requestRepository.findById(f.requestId()).orElseThrow().getStatus())
                .isEqualTo(DiagnosticRequest.DiagnosticRequestStatus.IN_PROGRESS);
    }

    // ==================== B/C: upload/processing state combinations ====================

    @Test
    @DisplayName("VIDEO не полностью загружено (PENDING_UPLOAD) - отклонено")
    void uploadNotReady_Rejected() {
        Fixture f = setUp(true);
        Long itemId = f.itemId();
        videoMedia(f.requestId(), itemId, f.diagnosticId(), UploadStatus.PENDING_UPLOAD, ProcessingStatus.PENDING);

        assertThatThrownBy(() -> requestService.saveInspectionAnswers(f.requestId(), f.diagnosticId(), submit()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("uploadStatus=READY, processingStatus=PENDING - отклонено")
    void uploadReadyProcessingPending_Rejected() {
        Fixture f = setUp(true);
        Long itemId = f.itemId();
        videoMedia(f.requestId(), itemId, f.diagnosticId(), UploadStatus.READY, ProcessingStatus.PENDING);

        assertThatThrownBy(() -> requestService.saveInspectionAnswers(f.requestId(), f.diagnosticId(), submit()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("uploadStatus=READY, processingStatus=PROCESSING - отклонено")
    void uploadReadyProcessingProcessing_Rejected() {
        Fixture f = setUp(true);
        Long itemId = f.itemId();
        videoMedia(f.requestId(), itemId, f.diagnosticId(), UploadStatus.READY, ProcessingStatus.PROCESSING);

        assertThatThrownBy(() -> requestService.saveInspectionAnswers(f.requestId(), f.diagnosticId(), submit()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("uploadStatus=READY, processingStatus=FAILED - отклонено")
    void uploadReadyProcessingFailed_Rejected() {
        Fixture f = setUp(true);
        Long itemId = f.itemId();
        videoMedia(f.requestId(), itemId, f.diagnosticId(), UploadStatus.READY, ProcessingStatus.FAILED);

        assertThatThrownBy(() -> requestService.saveInspectionAnswers(f.requestId(), f.diagnosticId(), submit()))
                .isInstanceOf(IllegalStateException.class);
    }

    // ==================== D: fully ready ====================

    @Test
    @DisplayName("uploadStatus=READY, processingStatus=READY - завершение проходит (COMPLETED)")
    void uploadReadyProcessingReady_Succeeds() {
        Fixture f = setUp(true);
        Long itemId = f.itemId();
        videoMedia(f.requestId(), itemId, f.diagnosticId(), UploadStatus.READY, ProcessingStatus.READY);

        requestService.saveInspectionAnswers(f.requestId(), f.diagnosticId(), submit());

        assertThat(requestRepository.findById(f.requestId()).orElseThrow().getStatus())
                .isEqualTo(DiagnosticRequest.DiagnosticRequestStatus.COMPLETED);
    }

    // ==================== F: no video requirement - unchanged behavior ====================

    @Test
    @DisplayName("Шаблон без hasVideo=true - завершение проходит без какого-либо видео")
    void noVideoRequirement_ExistingBehaviorUnchanged() {
        Fixture f = setUp(false);

        requestService.saveInspectionAnswers(f.requestId(), f.diagnosticId(), submit());

        assertThat(requestRepository.findById(f.requestId()).orElseThrow().getStatus())
                .isEqualTo(DiagnosticRequest.DiagnosticRequestStatus.COMPLETED);
    }

    // ==================== G: scoping - real cross-request / cross-item rows ====================

    @Test
    @DisplayName("Видео другой заявки (тот же шаблон/пункт) не удовлетворяет требование этой заявки")
    void mediaForAnotherRequest_DoesNotSatisfy() {
        Long ownerA = createUser("ownerA");
        Long ownerB = createUser("ownerB");
        Long diagnosticId = createUser("diag");
        Long templateId = createTemplate();
        Long groupId = createGroup(templateId);
        Long itemId = createItem(groupId, true);
        Long requestA = createBoundRequest(ownerA, diagnosticId, templateId);
        Long requestB = createBoundRequest(ownerB, diagnosticId, templateId);

        // Qualifying video exists only for requestB's copy of the same item id.
        videoMedia(requestB, itemId, diagnosticId, UploadStatus.READY, ProcessingStatus.READY);

        assertThatThrownBy(() -> requestService.saveInspectionAnswers(requestA, diagnosticId, submit()))
                .isInstanceOf(IllegalStateException.class);

        // requestB's own submission, backed by its own qualifying video, must still succeed.
        requestService.saveInspectionAnswers(requestB, diagnosticId, submit());
        assertThat(requestRepository.findById(requestB).orElseThrow().getStatus())
                .isEqualTo(DiagnosticRequest.DiagnosticRequestStatus.COMPLETED);
    }

    @Test
    @DisplayName("Видео другого пункта той же заявки не удовлетворяет требование нужного пункта")
    void mediaForAnotherItem_DoesNotSatisfy() {
        Long ownerId = createUser("owner");
        Long diagnosticId = createUser("diag");
        Long templateId = createTemplate();
        Long groupId = createGroup(templateId);
        Long requiredItemId = createItem(groupId, true);
        Long otherItemId = createItem(groupId, false);
        Long requestId = createBoundRequest(ownerId, diagnosticId, templateId);

        // Qualifying video exists, but for the OTHER (non-required) item, not the required one.
        videoMedia(requestId, otherItemId, diagnosticId, UploadStatus.READY, ProcessingStatus.READY);

        assertThatThrownBy(() -> requestService.saveInspectionAnswers(requestId, diagnosticId, submit()))
                .isInstanceOf(IllegalStateException.class);
    }

    // ==================== E: multiple required items ====================

    @Test
    @DisplayName("Два требуемых пункта, удовлетворён только один - отклонено")
    void multipleRequiredItems_OnlyOneSatisfied_Rejected() {
        Long ownerId = createUser("owner");
        Long diagnosticId = createUser("diag");
        Long templateId = createTemplate();
        Long groupId = createGroup(templateId);
        Long itemA = createItem(groupId, true);
        Long itemB = createItem(groupId, true);
        Long requestId = createBoundRequest(ownerId, diagnosticId, templateId);
        videoMedia(requestId, itemA, diagnosticId, UploadStatus.READY, ProcessingStatus.READY);
        // itemB has no qualifying video at all.

        assertThatThrownBy(() -> requestService.saveInspectionAnswers(requestId, diagnosticId, submit()))
                .isInstanceOf(IllegalStateException.class);
        assertThat(requestRepository.findById(requestId).orElseThrow().getStatus())
                .isEqualTo(DiagnosticRequest.DiagnosticRequestStatus.IN_PROGRESS);
    }

    @Test
    @DisplayName("Два требуемых пункта, оба удовлетворены - завершение проходит")
    void multipleRequiredItems_BothSatisfied_Succeeds() {
        Long ownerId = createUser("owner");
        Long diagnosticId = createUser("diag");
        Long templateId = createTemplate();
        Long groupId = createGroup(templateId);
        Long itemA = createItem(groupId, true);
        Long itemB = createItem(groupId, true);
        Long requestId = createBoundRequest(ownerId, diagnosticId, templateId);
        videoMedia(requestId, itemA, diagnosticId, UploadStatus.READY, ProcessingStatus.READY);
        videoMedia(requestId, itemB, diagnosticId, UploadStatus.READY, ProcessingStatus.READY);

        requestService.saveInspectionAnswers(requestId, diagnosticId, submit());

        assertThat(requestRepository.findById(requestId).orElseThrow().getStatus())
                .isEqualTo(DiagnosticRequest.DiagnosticRequestStatus.COMPLETED);
    }

    // ==================== duplicate qualifying videos tolerated ====================

    @Test
    @DisplayName("Несколько подходящих видео для одного пункта допустимы (EXISTS, не COUNT=1)")
    void duplicateQualifyingVideos_StillSucceeds() {
        Fixture f = setUp(true);
        Long itemId = f.itemId();
        videoMedia(f.requestId(), itemId, f.diagnosticId(), UploadStatus.READY, ProcessingStatus.READY);
        videoMedia(f.requestId(), itemId, f.diagnosticId(), UploadStatus.READY, ProcessingStatus.READY);

        requestService.saveInspectionAnswers(f.requestId(), f.diagnosticId(), submit());

        assertThat(requestRepository.findById(f.requestId()).orElseThrow().getStatus())
                .isEqualTo(DiagnosticRequest.DiagnosticRequestStatus.COMPLETED);
    }

    // ==================== adversarial: spoofed client-submitted videoUrls text ====================

    @Test
    @DisplayName("Подделанная строка videoUrls в ответе клиента не заменяет реальную запись DiagnosticMedia")
    void clientSuppliedVideoUrlsText_DoesNotSatisfyGate() {
        Fixture f = setUp(true);
        // No real DiagnosticMedia row exists at all - only a client-submitted answer
        // claiming a video URL, exactly the kind of spoofed input the gate must ignore.
        InspectionAnswerDto answerDto = new InspectionAnswerDto();
        answerDto.setItemId(f.itemId());
        answerDto.setVideoUrls(List.of("https://attacker-controlled.example/fake-video.mp4"));
        SubmitInspectionRequest dto = new SubmitInspectionRequest();
        dto.setSubmit(true);
        dto.setAnswers(List.of(answerDto));

        assertThatThrownBy(() -> requestService.saveInspectionAnswers(f.requestId(), f.diagnosticId(), dto))
                .isInstanceOf(IllegalStateException.class);

        assertThat(requestRepository.findById(f.requestId()).orElseThrow().getStatus())
                .isEqualTo(DiagnosticRequest.DiagnosticRequestStatus.IN_PROGRESS);
    }

    // ==================== transaction rollback proof ====================

    @Test
    @DisplayName("Отказ гейта откатывает уже сохранённые в этой же транзакции ответы")
    void gateFailure_RollsBackAnswerPersistence() {
        Fixture f = setUp(true);
        // No video at all for the required item - the gate will reject.

        InspectionAnswerDto answerDto = new InspectionAnswerDto();
        answerDto.setItemId(f.itemId());
        answerDto.setComment("Комментарий, который не должен сохраниться");
        SubmitInspectionRequest dto = new SubmitInspectionRequest();
        dto.setSubmit(true);
        dto.setAnswers(List.of(answerDto));

        assertThatThrownBy(() -> requestService.saveInspectionAnswers(f.requestId(), f.diagnosticId(), dto))
                .isInstanceOf(IllegalStateException.class);

        assertThat(answerRepository.findByDiagnosticRequestId(f.requestId())).isEmpty();
        assertThat(requestRepository.findById(f.requestId()).orElseThrow().getStatus())
                .isEqualTo(DiagnosticRequest.DiagnosticRequestStatus.IN_PROGRESS);
    }

    // ==================== H: processing lifecycle - fail closed, then succeed once READY ====================

    @Test
    @DisplayName("Не готово к отправке -> отклонено; после перехода в READY+READY повторная отправка проходит")
    void notReadyThenBecomesReady_SubsequentSubmissionSucceeds() {
        Fixture f = setUp(true);
        Long itemId = f.itemId();
        Long mediaId = videoMedia(f.requestId(), itemId, f.diagnosticId(), UploadStatus.READY, ProcessingStatus.PENDING);

        assertThatThrownBy(() -> requestService.saveInspectionAnswers(f.requestId(), f.diagnosticId(), submit()))
                .isInstanceOf(IllegalStateException.class);

        // Simulates VideoProcessingService's own eventual PROCESSING -> READY transition -
        // not re-testing that scheduler/pipeline here, only the gate's reaction to the
        // resulting state, per this slice's locked scope (no media-pipeline changes).
        DiagnosticMedia media = mediaRepository.findById(mediaId).orElseThrow();
        media.setProcessingStatus(ProcessingStatus.READY);
        mediaRepository.save(media);

        requestService.saveInspectionAnswers(f.requestId(), f.diagnosticId(), submit());

        assertThat(requestRepository.findById(f.requestId()).orElseThrow().getStatus())
                .isEqualTo(DiagnosticRequest.DiagnosticRequestStatus.COMPLETED);
    }

}

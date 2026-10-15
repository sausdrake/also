package com.example.autoauction.diagnostic.application;

import com.example.autoauction.admin.application.SystemSettingsService;
import com.example.autoauction.diagnostic.commitment.application.SellerDiagnosticCommitmentService;
import com.example.autoauction.diagnostic.commitment.domain.CommitmentStatus;
import com.example.autoauction.diagnostic.commitment.domain.SellerViolationResolution;
import com.example.autoauction.diagnostic.domain.DiagnosticRejectionCategory;
import com.example.autoauction.diagnostic.domain.DiagnosticRequest;
import com.example.autoauction.vehicle.application.VehicleService;
import com.example.autoauction.diagnostic.domain.InspectionAnswer;
import com.example.autoauction.diagnostic.domain.InspectionGroup;
import com.example.autoauction.diagnostic.domain.InspectionItem;
import com.example.autoauction.diagnostic.domain.InspectionTemplate;
import com.example.autoauction.diagnostic.domain.port.DiagnosticRequestRepository;
import com.example.autoauction.diagnostic.domain.port.InspectionAnswerRepository;
import com.example.autoauction.diagnostic.domain.port.InspectionTemplateRepository;
import com.example.autoauction.diagnostic.web.dto.CreateDiagnosticRequest;
import com.example.autoauction.diagnostic.web.dto.DiagnosticRequestResponse;
import com.example.autoauction.diagnostic.web.dto.InspectionAnswerDto;
import com.example.autoauction.diagnostic.web.dto.SellerViolationResolutionResponse;
import com.example.autoauction.diagnostic.web.dto.SubmitInspectionRequest;
import com.example.autoauction.diagnostic.web.dto.TemplateBackfillResult;
import com.example.autoauction.notification.application.NotificationService;
import com.example.autoauction.user.domain.User;
import com.example.autoauction.user.domain.port.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class DiagnosticRequestService {

    private final DiagnosticRequestRepository requestRepository;
    private final InspectionTemplateRepository templateRepository;
    private final InspectionAnswerRepository answerRepository;
    private final SellerDiagnosticCommitmentService sellerDiagnosticCommitmentService;
    private final SystemSettingsService systemSettingsService;
    private final UserRepository userRepository;
    private final VehicleService vehicleService;
    private final NotificationService notificationService;
    // MEDIA-3.3: report dual-read only - never used to write/migrate here, see
    // LegacyMediaMigrationService for the migration itself.
    private final com.example.autoauction.diagnostic.media.domain.port.DiagnosticMediaRepository mediaRepository;
    private final com.example.autoauction.diagnostic.media.domain.port.StorageClient storageClient;

    private static final String LEGACY_MEDIA_KEY_PREFIX = "diagnostic-media-legacy/";

    @org.springframework.beans.factory.annotation.Value("${diagnostic.media.read-url-ttl-minutes}")
    private long readUrlTtlMinutes;

    private static final BigDecimal DEPOSIT_PERCENT = new BigDecimal("0.01");

    /**
     * Пользователь создаёт заявку на диагностику
     */
    @Transactional
    public DiagnosticRequestResponse createRequest(CreateDiagnosticRequest dto, Long userId) {
        log.info("Creating diagnostic request for user: {}, vin: {}", userId, dto.getVin());
        // Проверка на существующую активную заявку. An existence query over ALL of the user's
        // requests - inspecting only an unordered first page could see an old APPROVED/REJECTED
        // row and miss the active one, letting a second active request (and a second deposit
        // freeze) through.
        boolean hasPending = requestRepository.existsByUserIdAndStatusIn(userId, java.util.EnumSet.of(
                DiagnosticRequest.DiagnosticRequestStatus.PENDING,
                DiagnosticRequest.DiagnosticRequestStatus.IN_PROGRESS));

        if (hasPending) {
            throw new IllegalStateException("У вас уже есть активная заявка на диагностику. Дождитесь её завершения.");
        }

        // Scenario 1 step 2: VIN uniqueness. vehicles.vin is unique and approving a report for an
        // already registered VIN reuses that vehicle (createFromDiagnosticRequest), so such a
        // request could never lead to a new listing - yet it would freeze a deposit and charge
        // the diagnostic fee. A VIN that is already being diagnosed by any user is refused too.
        // A REJECTED request does not block: it may be reworked, but it may also be final.
        if (vehicleService.isVinRegistered(dto.getVin())) {
            throw new IllegalStateException("Автомобиль с таким VIN уже зарегистрирован на платформе");
        }
        if (requestRepository.existsByVinAndStatusIn(dto.getVin(), java.util.EnumSet.of(
                DiagnosticRequest.DiagnosticRequestStatus.PENDING,
                DiagnosticRequest.DiagnosticRequestStatus.IN_PROGRESS,
                DiagnosticRequest.DiagnosticRequestStatus.COMPLETED))) {
            throw new IllegalStateException("По этому VIN уже идёт диагностика");
        }
        // PHASE 2: both amounts are snapshotted at creation time from SystemSettingsService -
        // never re-read later in the lifecycle (see SellerDiagnosticCommitment Javadoc).
        // diagnosticFeeAmount has no authorized default (see Phase 2 resolution) - it must be
        // explicitly configured by an administrator; getRequiredBigDecimal fails closed
        // (missing/malformed/negative) BEFORE the request row or the commitment/Deposit
        // ledger are touched, so an unconfigured fee never leaves behind a half-created
        // request or freezes funds it cannot account for.
        BigDecimal depositAmount = systemSettingsService.getBigDecimal("diagnostic_deposit_amount", new BigDecimal("10000"));
        BigDecimal diagnosticFeeAmount = systemSettingsService.getRequiredBigDecimal("diagnostic_fee_amount");

        // Создаём заявку
        DiagnosticRequest request = new DiagnosticRequest();
        request.setUserId(userId);
        request.setBrand(dto.getBrand());
        request.setModel(dto.getModel());
        request.setYear(dto.getYear());
        request.setVin(dto.getVin());
        request.setDepositFrozen(depositAmount);
        request.setStatus(DiagnosticRequest.DiagnosticRequestStatus.PENDING);
        request.setCreatedAt(OffsetDateTime.now());
        request.setUpdatedAt(OffsetDateTime.now());

        // Сохраняем заявку, чтобы получить ID
        DiagnosticRequest savedRequest = requestRepository.save(request);

        // Замораживаем депозит с ID заявки
        sellerDiagnosticCommitmentService.freezeCommitment(userId, savedRequest.getId(), depositAmount, diagnosticFeeAmount);

        log.info("Created diagnostic request: id={}, userId={}", savedRequest.getId(), userId);
        return DiagnosticRequestResponse.fromDomain(savedRequest);
    }

    /**
     * Получить заявки пользователя
     */
    @Transactional(readOnly = true)
    public Page<DiagnosticRequestResponse> getUserRequests(Long userId, Pageable pageable) {
        return requestRepository.findByUserId(userId, pageable)
                .map(r -> {
                    DiagnosticRequestResponse response = DiagnosticRequestResponse.fromDomain(r);
                    response.setVehicleId(vehicleService.findVehicleIdByDiagnosticRequestId(r.getId()));
                    return response;
                });
    }

    /**
     * Получить доступные заявки для диагноста (PENDING, без назначенного диагноста)
     */
    @Transactional(readOnly = true)
    public List<DiagnosticRequestResponse> getPendingRequests() {
        return requestRepository.findPendingRequests()
                .stream()
                .map(DiagnosticRequestResponse::fromDomain)
                .toList();
    }

    /**
     * Диагностик берёт заявку в работу
     */
    @Transactional
    public DiagnosticRequestResponse assignToDiagnostic(Long requestId, Long diagnosticId) {
        log.info("Assigning request {} to diagnostic {}", requestId, diagnosticId);

        // Проверка что диагност существует
        User diagnostic = userRepository.findById(diagnosticId)
                .orElseThrow(() -> new IllegalArgumentException("Диагност не найден"));

        if (!diagnostic.isActive()) {
            throw new IllegalStateException("Ваш аккаунт заблокирован. Обратитесь к администратору.");
        }

        boolean assigned = requestRepository.assignToDiagnostic(requestId, diagnosticId);
        if (!assigned) {
            throw new IllegalStateException("Не удалось назначить заявку. Возможно, она уже взята в работу.");
        }

        DiagnosticRequest request = requestRepository.findById(requestId)
                .orElseThrow(() -> new IllegalArgumentException("Заявка не найдена: " + requestId));

        // Notify owner
        try {
            notificationService.send(request.getUserId(), "DIAG_ACCEPTED",
                    "Заявка принята диагностом",
                    String.format("Диагност принял вашу заявку на осмотр автомобиля %s %s (%d).",
                            request.getBrand(), request.getModel(), request.getYear()),
                    requestId, "DIAGNOSTIC");
        } catch (Exception e) {
            log.warn("Failed to send DIAG_ACCEPTED notification: {}", e.getMessage());
        }

        return DiagnosticRequestResponse.fromDomain(request);
    }

    /**
     * Получить заявку с шаблоном для заполнения.
     *
     * MEDIA-3.1A: purely read-only. The template is never resolved or chosen
     * here - it must already be bound via bindTemplate(). No "first active
     * template" fallback, no answer-based inference: both were proven unsafe
     * (MEDIA-3 Phase 1.5/1.6) because they re-evaluate mutable state (which
     * templates are currently active) instead of reading the one persisted,
     * immutable decision. findFullTemplateById is used deliberately instead
     * of an active-only lookup: a template deactivated after a request was
     * bound to it must not change that request's meaning.
     */
    @Transactional(readOnly = true)
    public DiagnosticRequestResponse getRequestWithTemplate(Long requestId, Long diagnosticId) {
        DiagnosticRequest request = requestRepository.findById(requestId)
                .orElseThrow(() -> new IllegalArgumentException("Заявка не найдена: " + requestId));

        if (request.getAssignedTo() == null || !request.getAssignedTo().equals(diagnosticId)) {
            throw new SecurityException("Вы не назначены на эту заявку");
        }

        if (request.getTemplateId() == null) {
            throw new IllegalStateException("Для этой заявки ещё не выбран шаблон анкеты");
        }

        InspectionTemplate template = templateRepository.findFullTemplateById(request.getTemplateId())
                .orElseThrow(() -> new IllegalStateException("Шаблон, привязанный к заявке, не найден: " + request.getTemplateId()));

        List<InspectionAnswer> existingAnswers = answerRepository.findByDiagnosticRequestId(requestId);
        DiagnosticRequestResponse response = DiagnosticRequestResponse.fromDomain(request);
        response.setTemplate(template);
        response.setExistingAnswers(existingAnswers);
        return response;
    }

    /**
     * MEDIA-3.1A: explicit, one-time template binding. Authorization and the
     * IN_PROGRESS/unbound preconditions are checked here for a precise error
     * message on the common path, but the atomic conditional UPDATE (see
     * DiagnosticRequestRepository.bindTemplate) is the actual concurrency/
     * immutability guard - the checks below can be stale by the time the
     * UPDATE runs, and that is fine because the UPDATE itself re-verifies
     * every condition atomically.
     */
    @Transactional
    public DiagnosticRequestResponse bindTemplate(Long requestId, Long diagnosticId, Long templateId) {
        DiagnosticRequest request = requestRepository.findById(requestId)
                .orElseThrow(() -> new IllegalArgumentException("Заявка не найдена: " + requestId));

        if (request.getAssignedTo() == null || !request.getAssignedTo().equals(diagnosticId)) {
            throw new SecurityException("Вы не назначены на эту заявку");
        }
        if (request.getStatus() != DiagnosticRequest.DiagnosticRequestStatus.IN_PROGRESS) {
            throw new IllegalStateException("Шаблон можно выбрать только для заявки в статусе IN_PROGRESS. Текущий статус: " + request.getStatus());
        }

        InspectionTemplate template = templateRepository.findTemplateById(templateId)
                .orElseThrow(() -> new IllegalArgumentException("Шаблон не найден: " + templateId));
        if (!template.isActive()) {
            throw new IllegalStateException("Шаблон неактивен и не может быть выбран: " + templateId);
        }

        boolean bound = requestRepository.bindTemplate(requestId, diagnosticId, templateId);
        if (!bound) {
            // Lost the race, or the request moved out of a bindable state between
            // our checks above and the atomic UPDATE. Re-read to find out which.
            DiagnosticRequest current = requestRepository.findById(requestId)
                    .orElseThrow(() -> new IllegalArgumentException("Заявка не найдена: " + requestId));

            if (current.getTemplateId() != null) {
                if (current.getTemplateId().equals(templateId)) {
                    // Same template already bound (by this call racing itself, a
                    // retry, or a concurrent identical request) - idempotent success.
                    log.info("Request {} already bound to template {} - idempotent", requestId, templateId);
                } else {
                    throw new IllegalStateException(
                            "Заявка уже привязана к другому шаблону: " + current.getTemplateId());
                }
            } else {
                throw new IllegalStateException(
                        "Не удалось привязать шаблон: заявка более не в статусе IN_PROGRESS или переназначена");
            }
        }

        DiagnosticRequest finalRequest = requestRepository.findById(requestId)
                .orElseThrow(() -> new IllegalArgumentException("Заявка не найдена: " + requestId));
        InspectionTemplate boundTemplate = templateRepository.findFullTemplateById(finalRequest.getTemplateId())
                .orElseThrow(() -> new IllegalStateException("Шаблон, привязанный к заявке, не найден: " + finalRequest.getTemplateId()));

        log.info("Request {} bound to template {} by diagnostic {}", requestId, finalRequest.getTemplateId(), diagnosticId);
        DiagnosticRequestResponse response = DiagnosticRequestResponse.fromDomain(finalRequest);
        response.setTemplate(boundTemplate);
        return response;
    }

    /**
     * Сохранить ответы на анкету
     */
    @Transactional
    public void saveInspectionAnswers(Long requestId, Long diagnosticId, SubmitInspectionRequest dto) {
        log.info("Saving inspection answers for request: {}, diagnostic: {}", requestId, diagnosticId);

        DiagnosticRequest request = requestRepository.findById(requestId)
                .orElseThrow(() -> new IllegalArgumentException("Заявка не найдена: " + requestId));

        if (!request.getAssignedTo().equals(diagnosticId)) {
            throw new SecurityException("Вы не назначены на эту заявку");
        }

        // MEDIA-3.3: the only status in which answers may be written. Confirmed from the
        // actual state machine: PENDING never has assignedTo set (already blocked above);
        // COMPLETED/APPROVED are reached only via markAsCompleted/approveReport, neither of
        // which has a path back to IN_PROGRESS; REJECTED's only way back to editable is the
        // explicit resetForRework() action, which itself transitions status to IN_PROGRESS -
        // so IN_PROGRESS is the complete and only legitimate editable window. Without this
        // check, a still-assigned diagnostician could silently mutate InspectionAnswer
        // (including photoUrls/videoUrls) for an already-COMPLETED/APPROVED/REJECTED request,
        // which MEDIA-3.3's legacy-media migration must be able to treat as impossible.
        if (request.getStatus() != DiagnosticRequest.DiagnosticRequestStatus.IN_PROGRESS) {
            throw new IllegalStateException(
                    "Ответы можно сохранять только для заявки в статусе IN_PROGRESS. Текущий статус: " + request.getStatus());
        }

        // MEDIA-3.1A: a request must have a bound template before any answer can
        // be saved against it - without one, there is no authoritative template
        // to validate submitted itemIds against, and inferring one is exactly
        // the unsafe behavior this phase replaces.
        if (request.getTemplateId() == null) {
            throw new IllegalStateException("Для этой заявки ещё не выбран шаблон анкеты");
        }

        // Проверка на дубликаты пунктов и принадлежность пунктов привязанному шаблону
        Set<Long> uniqueItemIds = new HashSet<>();
        for (InspectionAnswerDto answerDto : dto.getAnswers()) {
            if (!uniqueItemIds.add(answerDto.getItemId())) {
                throw new IllegalArgumentException("Обнаружен дубликат пункта анкеты: " + answerDto.getItemId());
            }
            InspectionItem item = templateRepository.findItemById(answerDto.getItemId())
                    .orElseThrow(() -> new IllegalArgumentException("Пункт анкеты не найден: " + answerDto.getItemId()));
            InspectionGroup group = templateRepository.findGroupById(item.getGroupId())
                    .orElseThrow(() -> new IllegalArgumentException("Группа анкеты не найдена: " + item.getGroupId()));
            if (!group.getTemplateId().equals(request.getTemplateId())) {
                throw new IllegalArgumentException(
                        "Пункт анкеты " + answerDto.getItemId() + " не принадлежит шаблону, привязанному к заявке");
            }
        }

        // Удаляем старые ответы
        answerRepository.deleteByDiagnosticRequestId(requestId);

        // Сохраняем новые
        List<InspectionAnswer> answers = new ArrayList<>();
        for (InspectionAnswerDto answerDto : dto.getAnswers()) {
            InspectionAnswer answer = new InspectionAnswer();
            answer.setDiagnosticRequestId(requestId);
            answer.setInspectionItemId(answerDto.getItemId());
            answer.setRating(answerDto.getRating());
            answer.setComment(answerDto.getComment());
            answer.setPhotoUrls(answerDto.getPhotoUrls());
            answer.setVideoUrls(answerDto.getVideoUrls());
            answer.setCreatedAt(OffsetDateTime.now());
            answer.setUpdatedAt(OffsetDateTime.now());
            answers.add(answer);
        }

        answerRepository.saveAll(answers);

        // Если это финальная отправка, меняем статус
        if (dto.isSubmit()) {
            validateMandatoryVideos(request);
            requestRepository.markAsCompleted(requestId, diagnosticId);
            log.info("Request {} marked as completed", requestId);

            // Notify all admins about new report awaiting review
            try {
                java.util.List<com.example.autoauction.user.domain.User> admins = userRepository.findByRoleName("ROLE_ADMIN");
                for (com.example.autoauction.user.domain.User admin : admins) {
                    notificationService.send(admin.getId(), "DIAG_REPORT_SUBMITTED",
                            "Новый отчёт на проверке",
                            String.format("Отчёт по заявке #%d (%s %s) отправлен на проверку.",
                                    requestId, request.getBrand(), request.getModel()),
                            requestId, "DIAGNOSTIC");
                }
            } catch (Exception e) {
                log.warn("Failed to send DIAG_REPORT_SUBMITTED notifications: {}", e.getMessage());
            }
        }
    }

    /**
     * Mandatory-video completion gate: for every InspectionItem on the request's bound
     * template where hasVideo=true, at least one VIDEO DiagnosticMedia row with
     * uploadStatus=READY and processingStatus=READY must exist for that (request, item)
     * pair. Required item IDs are enumerated from the template itself, never from the
     * client-supplied answer DTO, so a client cannot satisfy this gate by omitting a
     * required item or supplying an unrelated item ID. Runs inside the caller's existing
     * transaction, before markAsCompleted - a failure here throws and rolls back the
     * answers already persisted earlier in this same method.
     */
    private void validateMandatoryVideos(DiagnosticRequest request) {
        InspectionTemplate template = templateRepository.findFullTemplateById(request.getTemplateId())
                .orElseThrow(() -> new IllegalStateException("Шаблон анкеты не найден: " + request.getTemplateId()));

        for (InspectionGroup group : template.getGroups()) {
            for (InspectionItem item : group.getItems()) {
                if (item.isHasVideo() && !mediaRepository.existsQualifyingVideo(request.getId(), item.getId())) {
                    throw new IllegalStateException(
                            "Обязательное видео для пункта анкеты " + item.getId()
                                    + " отсутствует или ещё не полностью обработано");
                }
            }
        }
    }

    /**
     * Получить заявки диагноста (в работе и завершённые)
     */
    @Transactional(readOnly = true)
    public Page<DiagnosticRequestResponse> getDiagnosticRequests(Long diagnosticId, Pageable pageable) {
        return requestRepository.findByAssignedTo(diagnosticId, pageable)
                .map(DiagnosticRequestResponse::fromDomain);
    }

    /**
     * Пользователь отзывает заявку (только если PENDING)
     */
    @Transactional
    public void cancelRequest(Long requestId, Long userId) {
        DiagnosticRequest request = requestRepository.findById(requestId)
                .orElseThrow(() -> new IllegalArgumentException("Заявка не найдена: " + requestId));

        if (!request.getUserId().equals(userId)) {
            throw new SecurityException("Нет доступа к этой заявке");
        }
        if (request.getStatus() != DiagnosticRequest.DiagnosticRequestStatus.PENDING) {
            throw new IllegalStateException("Можно отозвать только заявку в статусе PENDING. Текущий статус: " + request.getStatus());
        }

        sellerDiagnosticCommitmentService.releaseCommitment(userId, requestId);

        request.setStatus(DiagnosticRequest.DiagnosticRequestStatus.REJECTED);
        request.setRejectedReason("Отозвана пользователем");
        request.setUpdatedAt(OffsetDateTime.now());
        requestRepository.save(request);
        log.info("Request {} cancelled by user {}", requestId, userId);
    }

    /**
     * Получить все заявки (все статусы) — для администратора
     */
    @Transactional(readOnly = true)
    public Page<DiagnosticRequestResponse> getAllRequests(Pageable pageable) {
        return requestRepository.findAll(pageable)
                .map(r -> {
                    DiagnosticRequestResponse response = DiagnosticRequestResponse.fromDomain(r);
                    if (r.getAssignedTo() != null) {
                        userRepository.findById(r.getAssignedTo())
                                .ifPresent(u -> response.setDiagnosticName(u.getUsername()));
                    }
                    userRepository.findById(r.getUserId())
                            .ifPresent(u -> response.setUserName(u.getUsername()));
                    return response;
                });
    }

    /**
     * Диагност исправляет отклонённый отчёт — сбрасывает статус обратно в IN_PROGRESS
     */
    @Transactional
    public DiagnosticRequestResponse resetForRework(Long requestId, Long diagnosticId) {
        DiagnosticRequest request = requestRepository.findById(requestId)
                .orElseThrow(() -> new IllegalArgumentException("Заявка не найдена: " + requestId));

        if (request.getAssignedTo() == null || !request.getAssignedTo().equals(diagnosticId)) {
            throw new SecurityException("Вы не назначены на эту заявку");
        }
        if (request.getStatus() != DiagnosticRequest.DiagnosticRequestStatus.REJECTED) {
            throw new IllegalStateException("Исправить можно только отклонённый отчёт. Текущий статус: " + request.getStatus());
        }

        boolean ok = requestRepository.resetForRework(requestId, diagnosticId);
        if (!ok) {
            throw new IllegalStateException("Не удалось сбросить статус заявки");
        }

        DiagnosticRequest updated = requestRepository.findById(requestId)
                .orElseThrow(() -> new IllegalArgumentException("Заявка не найдена после обновления: " + requestId));
        log.info("Request {} reset for rework by diagnostic {}", requestId, diagnosticId);
        return DiagnosticRequestResponse.fromDomain(updated);
    }

    /**
     * Получить отчёты на проверке (статус COMPLETED) — для администратора
     */
    @Transactional(readOnly = true)
    public Page<DiagnosticRequestResponse> getCompletedReports(Pageable pageable) {
        return requestRepository.findByStatus(DiagnosticRequest.DiagnosticRequestStatus.COMPLETED, pageable)
                .map(DiagnosticRequestResponse::fromDomain);
    }

    @Transactional(readOnly = true)
    public long countApprovedReports() {
        return requestRepository.findByStatus(DiagnosticRequest.DiagnosticRequestStatus.APPROVED, PageRequest.of(0, 1))
                .getTotalElements();
    }

    /**
     * Администратор одобряет отчёт
     */
    @Transactional
    public DiagnosticRequestResponse approveReport(Long requestId, Long adminId) {
        DiagnosticRequest request = requestRepository.findById(requestId)
                .orElseThrow(() -> new IllegalArgumentException("Заявка не найдена: " + requestId));

        if (request.getStatus() != DiagnosticRequest.DiagnosticRequestStatus.COMPLETED) {
            throw new IllegalStateException("Можно одобрить только отчёт со статусом COMPLETED. Текущий: " + request.getStatus());
        }

        request.setStatus(DiagnosticRequest.DiagnosticRequestStatus.APPROVED);
        request.setUpdatedAt(OffsetDateTime.now());
        DiagnosticRequest saved = requestRepository.save(request);

        try {
            notificationService.send(request.getUserId(), "DIAG_REPORT_APPROVED",
                    "Диагностический отчёт одобрен",
                    String.format("Отчёт по вашему автомобилю %s %s (%d) одобрен. Автомобиль готов к созданию аукциона.",
                            request.getBrand(), request.getModel(), request.getYear()),
                    requestId, "DIAGNOSTIC");
        } catch (Exception e) {
            log.warn("Failed to send DIAG_REPORT_APPROVED notification: {}", e.getMessage());
        }

        // PHASE 2: одобрение отчёта переводит commitment в AWAITING_FINALIZATION (не
        // освобождает депозит - фактический исход A/B/C и разморозка/списание вычисляются
        // отдельной, ещё не реализованной фазой финализации).
        sellerDiagnosticCommitmentService.markAwaitingFinalization(requestId);

        // Автоматически создать Vehicle из данных заявки
        String diagnosticUsername = userRepository.findById(request.getAssignedTo() != null ? request.getAssignedTo() : request.getUserId())
                .map(u -> u.getUsername()).orElse("unknown");
        String adminUsername = userRepository.findById(adminId).map(u -> u.getUsername()).orElse("admin");
        try {
            vehicleService.createFromDiagnosticRequest(
                    requestId,
                    request.getBrand(), request.getModel(), request.getYear(), request.getVin(),
                    request.getUserId(), diagnosticUsername,
                    adminId, adminUsername
            );
        } catch (Exception e) {
            log.warn("Could not auto-create vehicle for request {}: {}", requestId, e.getMessage());
        }

        log.info("Report {} approved by admin {}", requestId, adminId);
        return DiagnosticRequestResponse.fromDomain(saved);
    }

    /**
     * Администратор отклоняет отчёт с причиной
     */
    @Transactional
    public DiagnosticRequestResponse rejectReport(Long requestId, Long adminId, String reason,
                                                   DiagnosticRejectionCategory category) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("Причина отклонения обязательна");
        }
        // PHASE 2: fail-closed - the eventual finalization outcome (B vs C) depends on this
        // category, so an unrecorded rejection must never be allowed to happen silently.
        if (category == null) {
            throw new IllegalArgumentException("Категория отклонения обязательна");
        }
        DiagnosticRequest request = requestRepository.findById(requestId)
                .orElseThrow(() -> new IllegalArgumentException("Заявка не найдена: " + requestId));

        if (request.getStatus() != DiagnosticRequest.DiagnosticRequestStatus.COMPLETED) {
            throw new IllegalStateException("Можно отклонить только отчёт со статусом COMPLETED. Текущий: " + request.getStatus());
        }

        request.setStatus(DiagnosticRequest.DiagnosticRequestStatus.REJECTED);
        request.setRejectedReason(reason);
        request.setRejectionCategory(category);
        request.setUpdatedAt(OffsetDateTime.now());
        DiagnosticRequest saved = requestRepository.save(request);

        // PHASE 2: отклонение отчёта (как и одобрение) переводит commitment в
        // AWAITING_FINALIZATION - депозит остаётся замороженным до вычисления фактического
        // исхода (B или C) отдельной фазой финализации. R-1.2 (гарантия наличия пути
        // разморозки) теперь обеспечивается этой будущей фазой, а не здесь.
        sellerDiagnosticCommitmentService.markAwaitingFinalization(requestId);

        if (request.getAssignedTo() != null) {
            try {
                notificationService.send(request.getAssignedTo(), "DIAG_REPORT_REJECTED",
                        "Отчёт отклонён",
                        String.format("Ваш отчёт по заявке #%d отклонён. Причина: %s", requestId, reason),
                        requestId, "DIAGNOSTIC");
            } catch (Exception e) {
                log.warn("Failed to send DIAG_REPORT_REJECTED notification: {}", e.getMessage());
            }
        }

        log.info("Report {} rejected by admin {} with reason: {}", requestId, adminId, reason);
        return DiagnosticRequestResponse.fromDomain(saved);
    }

    /**
     * Получить детали заявки (для админа)
     */
    @Transactional(readOnly = true)
    public DiagnosticRequestResponse getRequestDetails(Long requestId) {
        DiagnosticRequest request = requestRepository.findById(requestId)
                .orElseThrow(() -> new IllegalArgumentException("Заявка не найдена: " + requestId));

        List<InspectionAnswer> answers = answerRepository.findByDiagnosticRequestId(requestId);
        answers.forEach(a -> resolveMediaUrlsForReport(requestId, a));

        DiagnosticRequestResponse response = DiagnosticRequestResponse.fromDomain(request);
        response.setAnswers(answers);
        response.setExistingAnswers(answers);

        if (request.getAssignedTo() != null) {
            userRepository.findById(request.getAssignedTo())
                    .ifPresent(diagnostic -> response.setDiagnosticName(diagnostic.getUsername()));
        }

        userRepository.findById(request.getUserId())
                .ifPresent(user -> response.setUserName(user.getUsername()));

        response.setTemplate(resolveTemplateForReport(request));
        response.setVehicleId(vehicleService.findVehicleIdByDiagnosticRequestId(request.getId()));
        enrichWithCommitmentAndViolationResolution(response, requestId);

        return response;
    }

    /**
     * Seller-violation-resolution UI: enriches with the commitment's current status and, if
     * finalized, the existing resolution's audit summary - read-only, no financial logic,
     * mirrors the vehicleId enrichment pattern exactly. No commitment ever frozen -> both
     * fields stay null, not an error.
     */
    private void enrichWithCommitmentAndViolationResolution(DiagnosticRequestResponse response, Long requestId) {
        sellerDiagnosticCommitmentService.findByDiagnosticRequestId(requestId).ifPresent(commitment -> {
            response.setCommitmentStatus(commitment.getStatus().name());
            if (commitment.getStatus() == CommitmentStatus.FINALIZED) {
                sellerDiagnosticCommitmentService.findViolationResolution(commitment.getId())
                        .ifPresent(resolution -> response.setViolationResolution(toResolutionResponse(resolution)));
            }
        });
    }

    private SellerViolationResolutionResponse toResolutionResponse(SellerViolationResolution resolution) {
        List<Long> violationIds = sellerDiagnosticCommitmentService.findViolationIdsForResolution(resolution.getId());
        return new SellerViolationResolutionResponse(
                resolution.getSellerDiagnosticCommitmentId(), violationIds,
                resolution.getRawPenaltyAmount(), resolution.getAppliedPenaltyAmount(),
                resolution.getRefundAmount(), resolution.getResolvedAt());
    }

    @Transactional(readOnly = true)
    public DiagnosticRequestResponse getApprovedReportForVehicle(Long vehicleId) {
        var vehicle = vehicleService.getVehicleDetails(vehicleId);
        DiagnosticRequest request = requestRepository.findApprovedByVin(vehicle.vin())
                .orElseThrow(() -> new IllegalStateException("Одобренный отчёт не найден"));

        List<InspectionAnswer> answers = answerRepository.findByDiagnosticRequestId(request.getId());
        answers.forEach(a -> resolveMediaUrlsForReport(request.getId(), a));

        DiagnosticRequestResponse response = DiagnosticRequestResponse.fromDomain(request);
        response.setTemplate(resolveTemplateForReport(request));
        response.setExistingAnswers(answers);
        return response;
    }

    /**
     * MEDIA-3.3: narrowly-scoped media dual-read for report paths only - does not touch
     * resolveTemplateForReport()'s semantics. For one InspectionAnswer: if EVERY legacy
     * photo/video URL for it has a corresponding READY, migrated DiagnosticMedia row
     * (identified by the diagnostic-media-legacy/ key prefix), replace photoUrls/videoUrls
     * in-place with freshly-generated signed URLs from the canonical DiagnosticMedia set.
     * Otherwise (zero, partial, or shape-mismatched migration) leaves the raw legacy URLs
     * completely untouched - never merges the two representations, never duplicates an item,
     * never silently drops a file that hasn't been migrated yet.
     *
     * Completeness requires ALL of: (1) total migrated row count == total legacy URL count;
     * (2) migrated PHOTO count == legacy photoUrls count AND migrated VIDEO count == legacy
     * videoUrls count (catches a wrong-type migration even if the total happens to match);
     * (3) the set of migrated sortOrder positions is exactly {0 .. legacyCount-1} with no
     * gaps or duplicates (catches a position collision/hole even if both counts above match).
     *
     * KNOWN RESIDUAL RISK: LegacyMediaMigrationService only ever migrates COMPLETED/APPROVED
     * requests (REJECTED is deliberately excluded - see its class Javadoc), which removes the
     * direct case of migrating a not-yet-stable snapshot. It does NOT remove every possibility
     * of staleness: COMPLETED can still be sent to REJECTED via rejectReport(), reworked back
     * to IN_PROGRESS via resetForRework(), and resubmitted to COMPLETED again. None of the
     * checks above can detect migrated rows left over from a PRIOR migrate() run if that full
     * reject/rework/recomplete cycle produces new photoUrls/videoUrls of the exact same
     * per-type counts as before - there is no persisted link from a DiagnosticMedia row back
     * to the specific legacy URL it was created from, so shape-identical stale data cannot be
     * told apart from a genuine complete migration by count/position alone. See
     * LegacyMediaMigrationService's class Javadoc for why this narrower residual case is
     * accepted for this phase.
     */
    private void resolveMediaUrlsForReport(Long requestId, InspectionAnswer answer) {
        int legacyPhotoCount = answer.getPhotoUrls() == null ? 0 : answer.getPhotoUrls().size();
        int legacyVideoCount = answer.getVideoUrls() == null ? 0 : answer.getVideoUrls().size();
        int legacyCount = legacyPhotoCount + legacyVideoCount;
        if (legacyCount == 0) {
            return;
        }

        List<com.example.autoauction.diagnostic.media.domain.DiagnosticMedia> migratedLegacy =
                mediaRepository.findByRequestAndItem(requestId, answer.getInspectionItemId()).stream()
                        .filter(m -> m.getStorageKey().startsWith(LEGACY_MEDIA_KEY_PREFIX))
                        .filter(m -> m.getUploadStatus() == com.example.autoauction.diagnostic.media.domain.UploadStatus.READY)
                        .sorted(java.util.Comparator.comparing(com.example.autoauction.diagnostic.media.domain.DiagnosticMedia::getSortOrder))
                        .toList();

        if (migratedLegacy.size() != legacyCount) {
            return; // incomplete or zero migration - preserve the complete legacy representation as-is
        }

        long migratedPhotoCount = migratedLegacy.stream()
                .filter(m -> m.getMediaType() == com.example.autoauction.diagnostic.media.domain.MediaType.PHOTO).count();
        long migratedVideoCount = migratedLegacy.stream()
                .filter(m -> m.getMediaType() == com.example.autoauction.diagnostic.media.domain.MediaType.VIDEO).count();
        if (migratedPhotoCount != legacyPhotoCount || migratedVideoCount != legacyVideoCount) {
            return; // right total, wrong photo/video split - do not trust it
        }

        java.util.Set<Integer> expectedPositions = java.util.stream.IntStream.range(0, legacyCount)
                .boxed().collect(java.util.stream.Collectors.toSet());
        java.util.Set<Integer> actualPositions = migratedLegacy.stream()
                .map(com.example.autoauction.diagnostic.media.domain.DiagnosticMedia::getSortOrder)
                .collect(java.util.stream.Collectors.toSet());
        if (!actualPositions.equals(expectedPositions)) {
            return; // gap or duplicate position - do not trust it
        }

        java.time.Duration ttl = java.time.Duration.ofMinutes(readUrlTtlMinutes);
        List<String> photoUrls = new ArrayList<>();
        List<String> videoUrls = new ArrayList<>();
        for (var media : migratedLegacy) {
            String url = storageClient.createPresignedReadUrl(media.getStorageKey(), ttl).toString();
            if (media.getMediaType() == com.example.autoauction.diagnostic.media.domain.MediaType.PHOTO) {
                photoUrls.add(url);
            } else {
                videoUrls.add(url);
            }
        }
        answer.setPhotoUrls(photoUrls);
        answer.setVideoUrls(videoUrls);
    }

    /**
     * MEDIA-3.1B: the ONE canonical template-resolution path for report methods.
     * templateId is read directly - never inferred from answers, never a
     * fallback to any active template. A null return means the request is an
     * unresolved historical state (see backfillHistoricalTemplateIds); callers
     * must render that as "no template" and MUST NOT invent one.
     * findFullTemplateById is used deliberately (not active-only): a template
     * deactivated after being bound must still resolve for an already-bound
     * request (same reasoning as getRequestWithTemplate, MEDIA-3.1A).
     */
    private InspectionTemplate resolveTemplateForReport(DiagnosticRequest request) {
        if (request.getTemplateId() == null) {
            return null;
        }
        return templateRepository.findFullTemplateById(request.getTemplateId()).orElse(null);
    }

    /**
     * MEDIA-3.1B: one-off, idempotent historical backfill. For every request
     * whose templateId is still unset (excluding PENDING, which never needed
     * one), classifies it deterministically from its InspectionAnswers and
     * binds ONLY when every answer resolves to the exact same template.
     * Ambiguous, answer-less, or broken-reference requests are left NULL and
     * logged with an explicit reason - never guessed. Safe to run repeatedly:
     * already-bound requests are excluded up front, and the atomic
     * bindTemplateIfUnset() write can never overwrite a binding that appeared
     * concurrently (e.g. via a live diagnostician bindTemplate() call).
     */
    @Transactional
    public TemplateBackfillResult backfillHistoricalTemplateIds() {
        int backfilled = 0;
        int alreadyBound = 0;
        Map<String, Integer> unresolvedByReason = new HashMap<>();

        List<DiagnosticRequest> candidates = requestRepository.findAll().stream()
                .filter(r -> r.getTemplateId() == null)
                .filter(r -> r.getStatus() != DiagnosticRequest.DiagnosticRequestStatus.PENDING)
                .toList();

        for (DiagnosticRequest request : candidates) {
            List<InspectionAnswer> answers = answerRepository.findByDiagnosticRequestId(request.getId());

            if (answers.isEmpty()) {
                log.warn("MEDIA-3.1B backfill: request {} unresolved - reason=NO_ANSWERS", request.getId());
                unresolvedByReason.merge("NO_ANSWERS", 1, Integer::sum);
                continue;
            }

            Set<Long> resolvedTemplateIds = new HashSet<>();
            String brokenReason = null;
            for (InspectionAnswer answer : answers) {
                InspectionItem item = templateRepository.findItemById(answer.getInspectionItemId()).orElse(null);
                if (item == null) {
                    brokenReason = "BROKEN_ITEM_REFERENCE";
                    break;
                }
                InspectionGroup group = templateRepository.findGroupById(item.getGroupId()).orElse(null);
                if (group == null) {
                    brokenReason = "BROKEN_GROUP_REFERENCE";
                    break;
                }
                InspectionTemplate template = templateRepository.findTemplateById(group.getTemplateId()).orElse(null);
                if (template == null) {
                    brokenReason = "BROKEN_TEMPLATE_REFERENCE";
                    break;
                }
                resolvedTemplateIds.add(group.getTemplateId());
            }

            if (brokenReason != null) {
                log.warn("MEDIA-3.1B backfill: request {} unresolved - reason={}", request.getId(), brokenReason);
                unresolvedByReason.merge(brokenReason, 1, Integer::sum);
                continue;
            }
            if (resolvedTemplateIds.size() > 1) {
                log.warn("MEDIA-3.1B backfill: request {} unresolved - reason=MULTIPLE_TEMPLATES {}",
                        request.getId(), resolvedTemplateIds);
                unresolvedByReason.merge("MULTIPLE_TEMPLATES", 1, Integer::sum);
                continue;
            }

            Long resolvedTemplateId = resolvedTemplateIds.iterator().next();
            boolean applied = requestRepository.bindTemplateIfUnset(request.getId(), resolvedTemplateId);
            if (applied) {
                backfilled++;
                log.info("MEDIA-3.1B backfill: request {} -> template {}", request.getId(), resolvedTemplateId);
            } else {
                // Bound concurrently (e.g. a live diagnostician bind) between our read and
                // write - not an error, and definitely not overwritten.
                alreadyBound++;
            }
        }

        log.info("MEDIA-3.1B backfill complete: backfilled={}, alreadyBound={}, unresolved={}",
                backfilled, alreadyBound, unresolvedByReason);
        return new TemplateBackfillResult(backfilled, alreadyBound, unresolvedByReason);
    }
}
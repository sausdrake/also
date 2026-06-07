package com.example.autoauction.diagnostic.application;

import com.example.autoauction.deposit.application.DepositService;
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
import com.example.autoauction.diagnostic.web.dto.SubmitInspectionRequest;
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
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class DiagnosticRequestService {

    private final DiagnosticRequestRepository requestRepository;
    private final InspectionTemplateRepository templateRepository;
    private final InspectionAnswerRepository answerRepository;
    private final DepositService depositService;
    private final UserRepository userRepository;
    private final VehicleService vehicleService;
    private final NotificationService notificationService;

    private static final BigDecimal DEPOSIT_PERCENT = new BigDecimal("0.01");

    /**
     * Пользователь создаёт заявку на диагностику
     */
    @Transactional
    public DiagnosticRequestResponse createRequest(CreateDiagnosticRequest dto, Long userId) {
        log.info("Creating diagnostic request for user: {}, vin: {}", userId, dto.getVin());
        // Проверка на существующую активную заявку
        Page<DiagnosticRequest> activeRequests = requestRepository.findByUserId(userId, PageRequest.of(0, 1));
        boolean hasPending = activeRequests.getContent().stream()
                .anyMatch(r -> r.getStatus() == DiagnosticRequest.DiagnosticRequestStatus.PENDING ||
                        r.getStatus() == DiagnosticRequest.DiagnosticRequestStatus.IN_PROGRESS);

        if (hasPending) {
            throw new IllegalStateException("У вас уже есть активная заявка на диагностику. Дождитесь её завершения.");
        }
        // Рассчитываем сумму заморозки (1% от оценочной стоимости)
        // Пока используем фиксированную сумму, потом можно привязать к марке/модели
        BigDecimal depositAmount = new BigDecimal("10000"); // 10 000 сом

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
        depositService.freezeForDiagnosticRequest(userId, savedRequest.getId(), depositAmount);

        log.info("Created diagnostic request: id={}, userId={}", savedRequest.getId(), userId);
        return DiagnosticRequestResponse.fromDomain(savedRequest);
    }

    /**
     * Получить заявки пользователя
     */
    @Transactional(readOnly = true)
    public Page<DiagnosticRequestResponse> getUserRequests(Long userId, Pageable pageable) {
        return requestRepository.findByUserId(userId, pageable)
                .map(DiagnosticRequestResponse::fromDomain);
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
     * Получить заявку с шаблоном для заполнения
     */
    @Transactional(readOnly = true)
    public DiagnosticRequestResponse getRequestWithTemplate(Long requestId, Long diagnosticId) {
        return getRequestWithTemplate(requestId, diagnosticId, null);
    }

    @Transactional(readOnly = true)
    public DiagnosticRequestResponse getRequestWithTemplate(Long requestId, Long diagnosticId, Long templateId) {
        DiagnosticRequest request = requestRepository.findById(requestId)
                .orElseThrow(() -> new IllegalArgumentException("Заявка не найдена: " + requestId));

        if (request.getAssignedTo() == null || !request.getAssignedTo().equals(diagnosticId)) {
            throw new SecurityException("Вы не назначены на эту заявку");
        }

        InspectionTemplate template;
        if (templateId != null) {
            template = templateRepository.findFullTemplateById(templateId)
                    .orElseThrow(() -> new IllegalArgumentException("Шаблон не найден: " + templateId));
        } else {
            template = templateRepository.findActiveTemplates(Pageable.ofSize(1))
                    .stream().findFirst()
                    .orElseThrow(() -> new IllegalStateException("Нет активных шаблонов анкет"));
            template = templateRepository.findFullTemplateById(template.getId())
                    .orElseThrow(() -> new IllegalStateException("Ошибка загрузки шаблона"));
        }

        List<InspectionAnswer> existingAnswers = answerRepository.findByDiagnosticRequestId(requestId);
        DiagnosticRequestResponse response = DiagnosticRequestResponse.fromDomain(request);
        response.setTemplate(template);
        response.setExistingAnswers(existingAnswers);
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
        // Проверка на дубликаты пунктов
        Set<Long> uniqueItemIds = new HashSet<>();
        for (InspectionAnswerDto answerDto : dto.getAnswers()) {
            if (!uniqueItemIds.add(answerDto.getItemId())) {
                throw new IllegalArgumentException("Обнаружен дубликат пункта анкеты: " + answerDto.getItemId());
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

        depositService.unfreezeForDiagnosticRequest(userId, requestId);

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

        // Разморозить депозит заявителя после одобрения
        depositService.unfreezeForDiagnosticRequest(request.getUserId(), requestId);

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
    public DiagnosticRequestResponse rejectReport(Long requestId, Long adminId, String reason) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("Причина отклонения обязательна");
        }
        DiagnosticRequest request = requestRepository.findById(requestId)
                .orElseThrow(() -> new IllegalArgumentException("Заявка не найдена: " + requestId));

        if (request.getStatus() != DiagnosticRequest.DiagnosticRequestStatus.COMPLETED) {
            throw new IllegalStateException("Можно отклонить только отчёт со статусом COMPLETED. Текущий: " + request.getStatus());
        }

        request.setStatus(DiagnosticRequest.DiagnosticRequestStatus.REJECTED);
        request.setRejectedReason(reason);
        request.setUpdatedAt(OffsetDateTime.now());
        DiagnosticRequest saved = requestRepository.save(request);

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

        DiagnosticRequestResponse response = DiagnosticRequestResponse.fromDomain(request);
        response.setAnswers(answers);
        response.setExistingAnswers(answers);

        if (request.getAssignedTo() != null) {
            userRepository.findById(request.getAssignedTo())
                    .ifPresent(diagnostic -> response.setDiagnosticName(diagnostic.getUsername()));
        }

        userRepository.findById(request.getUserId())
                .ifPresent(user -> response.setUserName(user.getUsername()));

        try {
            InspectionTemplate template = null;
            // Determine the exact template used by tracing: answer.itemId → item.groupId → group.templateId
            if (!answers.isEmpty()) {
                Long sampleItemId = answers.get(0).getInspectionItemId();
                InspectionItem item = templateRepository.findItemById(sampleItemId).orElse(null);
                if (item != null) {
                    InspectionGroup group = templateRepository.findGroupById(item.getGroupId()).orElse(null);
                    if (group != null) {
                        template = templateRepository.findFullTemplateById(group.getTemplateId()).orElse(null);
                    }
                }
            }
            // Fallback: load first active template (e.g. report has no answers yet)
            if (template == null) {
                Page<InspectionTemplate> activePage = templateRepository.findActiveTemplates(Pageable.ofSize(1));
                if (!activePage.isEmpty()) {
                    template = templateRepository.findFullTemplateById(activePage.getContent().get(0).getId()).orElse(null);
                }
            }
            response.setTemplate(template);
        } catch (Exception e) {
            log.warn("Could not load template for report {}: {}", requestId, e.getMessage());
        }

        return response;
    }

    @Transactional(readOnly = true)
    public DiagnosticRequestResponse getApprovedReportForVehicle(Long vehicleId) {
        var vehicle = vehicleService.getVehicleDetails(vehicleId);
        DiagnosticRequest request = requestRepository.findApprovedByVin(vehicle.vin())
                .orElseThrow(() -> new IllegalStateException("Одобренный отчёт не найден"));

        List<InspectionAnswer> answers = answerRepository.findByDiagnosticRequestId(request.getId());

        InspectionTemplate template = null;
        if (!answers.isEmpty()) {
            Long sampleItemId = answers.get(0).getInspectionItemId();
            InspectionItem item = templateRepository.findItemById(sampleItemId).orElse(null);
            if (item != null) {
                InspectionGroup group = templateRepository.findGroupById(item.getGroupId()).orElse(null);
                if (group != null) {
                    template = templateRepository.findFullTemplateById(group.getTemplateId()).orElse(null);
                }
            }
        }
        if (template == null) {
            Page<InspectionTemplate> activePage = templateRepository.findActiveTemplates(Pageable.ofSize(1));
            if (!activePage.isEmpty()) {
                template = templateRepository.findFullTemplateById(activePage.getContent().get(0).getId()).orElse(null);
            }
        }

        DiagnosticRequestResponse response = DiagnosticRequestResponse.fromDomain(request);
        response.setTemplate(template);
        response.setExistingAnswers(answers);
        return response;
    }
}
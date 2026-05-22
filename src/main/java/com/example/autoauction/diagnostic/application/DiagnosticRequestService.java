package com.example.autoauction.diagnostic.application;

import com.example.autoauction.deposit.application.DepositService;
import com.example.autoauction.diagnostic.domain.DiagnosticRequest;
import com.example.autoauction.diagnostic.domain.InspectionAnswer;
import com.example.autoauction.diagnostic.domain.InspectionTemplate;
import com.example.autoauction.diagnostic.domain.port.DiagnosticRequestRepository;
import com.example.autoauction.diagnostic.domain.port.InspectionAnswerRepository;
import com.example.autoauction.diagnostic.domain.port.InspectionTemplateRepository;
import com.example.autoauction.diagnostic.web.dto.CreateDiagnosticRequest;
import com.example.autoauction.diagnostic.web.dto.DiagnosticRequestResponse;
import com.example.autoauction.diagnostic.web.dto.InspectionAnswerDto;
import com.example.autoauction.diagnostic.web.dto.SubmitInspectionRequest;
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

    private static final BigDecimal DEPOSIT_PERCENT = new BigDecimal("0.01"); // 1%

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

        return DiagnosticRequestResponse.fromDomain(request);
    }

    /**
     * Получить заявку с шаблоном для заполнения
     */
    @Transactional(readOnly = true)
    public DiagnosticRequestResponse getRequestWithTemplate(Long requestId, Long diagnosticId) {
        DiagnosticRequest request = requestRepository.findById(requestId)
                .orElseThrow(() -> new IllegalArgumentException("Заявка не найдена: " + requestId));

        // Проверяем, что диагност назначен на эту заявку
        if (!request.getAssignedTo().equals(diagnosticId)) {
            throw new SecurityException("Вы не назначены на эту заявку");
        }

        // Получаем шаблон по типу автомобиля (пока используем дефолтный)
        InspectionTemplate template = templateRepository.findActiveTemplates(Pageable.ofSize(1))
                .stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("Нет активных шаблонов анкет"));

        // Получаем уже сохранённые ответы (если есть)
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
     * Получить детали заявки (для админа)
     */
    @Transactional(readOnly = true)
    public DiagnosticRequestResponse getRequestDetails(Long requestId) {
        DiagnosticRequest request = requestRepository.findById(requestId)
                .orElseThrow(() -> new IllegalArgumentException("Заявка не найдена: " + requestId));

        List<InspectionAnswer> answers = answerRepository.findByDiagnosticRequestId(requestId);

        DiagnosticRequestResponse response = DiagnosticRequestResponse.fromDomain(request);
        response.setAnswers(answers);

        // Добавляем информацию о диагносте
        if (request.getAssignedTo() != null) {
            userRepository.findById(request.getAssignedTo())
                    .ifPresent(diagnostic -> response.setDiagnosticName(diagnostic.getUsername()));
        }

        // Добавляем информацию о заявителе
        userRepository.findById(request.getUserId())
                .ifPresent(user -> response.setUserName(user.getUsername()));

        return response;
    }
}
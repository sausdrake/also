package com.example.autoauction.diagnostic.application;

import com.example.autoauction.admin.application.SystemSettingsService;
import com.example.autoauction.diagnostic.commitment.application.SellerDiagnosticCommitmentService;
import com.example.autoauction.diagnostic.commitment.domain.CommitmentStatus;
import com.example.autoauction.diagnostic.commitment.domain.SellerDiagnosticCommitment;
import com.example.autoauction.diagnostic.commitment.domain.SellerViolationResolution;
import com.example.autoauction.diagnostic.commitment.domain.TriggerSource;
import com.example.autoauction.diagnostic.domain.DiagnosticRejectionCategory;
import com.example.autoauction.diagnostic.domain.DiagnosticRequest;
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
import com.example.autoauction.user.domain.User;
import com.example.autoauction.user.domain.port.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("Тесты сервиса заявок на диагностику")
class DiagnosticRequestServiceTest {

    @Mock
    private DiagnosticRequestRepository requestRepository;

    @Mock
    private InspectionTemplateRepository templateRepository;

    @Mock
    private InspectionAnswerRepository answerRepository;

    @Mock
    private SellerDiagnosticCommitmentService sellerDiagnosticCommitmentService;

    @Mock
    private SystemSettingsService systemSettingsService;

    @Mock
    private UserRepository userRepository;

    @Mock
    private com.example.autoauction.vehicle.application.VehicleService vehicleService;

    @Mock
    private com.example.autoauction.diagnostic.media.domain.port.DiagnosticMediaRepository mediaRepository;

    @InjectMocks
    private DiagnosticRequestService requestService;

    private CreateDiagnosticRequest createRequest;
    private DiagnosticRequest request;
    private DiagnosticRequest savedRequest;
    private InspectionTemplate template;
    private final Long userId = 1L;
    private final Long diagnosticId = 2L;
    private final Long requestId = 100L;

    @BeforeEach
    void setUp() {
        createRequest = new CreateDiagnosticRequest();
        createRequest.setBrand("BMW");
        createRequest.setModel("X5");
        createRequest.setYear(2022);
        createRequest.setVin("WBSDE91070CZ12345");

        request = new DiagnosticRequest();
        request.setUserId(userId);
        request.setBrand("BMW");
        request.setModel("X5");
        request.setYear(2022);
        request.setVin("WBSDE91070CZ12345");
        request.setDepositFrozen(new BigDecimal("10000"));
        request.setStatus(DiagnosticRequest.DiagnosticRequestStatus.PENDING);
        request.setCreatedAt(OffsetDateTime.now());
        request.setUpdatedAt(OffsetDateTime.now());

        savedRequest = new DiagnosticRequest();
        savedRequest.setId(requestId);
        savedRequest.setUserId(userId);
        savedRequest.setBrand("BMW");
        savedRequest.setModel("X5");
        savedRequest.setYear(2022);
        savedRequest.setVin("WBSDE91070CZ12345");
        savedRequest.setDepositFrozen(new BigDecimal("10000"));
        savedRequest.setStatus(DiagnosticRequest.DiagnosticRequestStatus.PENDING);
        savedRequest.setCreatedAt(OffsetDateTime.now());
        savedRequest.setUpdatedAt(OffsetDateTime.now());

        template = new InspectionTemplate();
        template.setId(1L);
        template.setName("Стандартный шаблон");
        template.setActive(true);
    }

    @Nested
    @DisplayName("Создание заявки")
    class CreateRequestTests {

        @Test
        @DisplayName("Успешное создание заявки")
        void createRequest_Success() {
            // Мокаем что нет активных заявок
            when(requestRepository.existsByUserIdAndStatusIn(eq(userId), any())).thenReturn(false);

            when(requestRepository.save(any(DiagnosticRequest.class))).thenReturn(savedRequest);
            when(systemSettingsService.getBigDecimal(eq("diagnostic_deposit_amount"), any(BigDecimal.class)))
                    .thenReturn(new BigDecimal("10000"));
            when(systemSettingsService.getRequiredBigDecimal(eq("diagnostic_fee_amount")))
                    .thenReturn(new BigDecimal("4000"));
            doNothing().when(sellerDiagnosticCommitmentService)
                    .freezeCommitment(eq(userId), eq(requestId), any(BigDecimal.class), any(BigDecimal.class));

            DiagnosticRequestResponse response = requestService.createRequest(createRequest, userId);

            assertThat(response).isNotNull();
            assertThat(response.getId()).isEqualTo(requestId);
            assertThat(response.getBrand()).isEqualTo("BMW");
            assertThat(response.getStatus()).isEqualTo("PENDING");

            verify(requestRepository).save(any(DiagnosticRequest.class));
            verify(sellerDiagnosticCommitmentService)
                    .freezeCommitment(eq(userId), eq(requestId), any(BigDecimal.class), eq(new BigDecimal("4000")));
        }

        @Test
        @DisplayName("Создание заявки с null VIN - должно падать")
        void createRequest_NullVin_ThrowsException() {
            createRequest.setVin(null);
            assertThatThrownBy(() -> requestService.createRequest(createRequest, userId))
                    .isInstanceOf(Exception.class);
        }

        // PHASE 2 (fail-closed resolution): diagnostic_fee_amount has no authorized default -
        // getRequiredBigDecimal must propagate its failure BEFORE the request row is saved and
        // BEFORE the commitment/Deposit ledger is touched. DiagnosticRequestService doesn't
        // care WHY getRequiredBigDecimal threw (missing/malformed/negative) - that distinction
        // is unit-tested directly against SystemSettingsService itself.
        @Test
        @DisplayName("Настройка diagnostic_fee_amount не сконфигурирована - создание падает ДО " +
                "сохранения заявки и ДО заморозки commitment")
        void createRequest_FeeSettingNotConfigured_FailsClosedBeforeAnyMutation() {
            when(requestRepository.existsByUserIdAndStatusIn(eq(userId), any())).thenReturn(false);
            when(systemSettingsService.getBigDecimal(eq("diagnostic_deposit_amount"), any(BigDecimal.class)))
                    .thenReturn(new BigDecimal("10000"));
            when(systemSettingsService.getRequiredBigDecimal(eq("diagnostic_fee_amount")))
                    .thenThrow(new IllegalStateException(
                            "Обязательная настройка 'diagnostic_fee_amount' не задана администратором"));

            assertThatThrownBy(() -> requestService.createRequest(createRequest, userId))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("diagnostic_fee_amount");

            verify(requestRepository, never()).save(any());
            verifyNoInteractions(sellerDiagnosticCommitmentService);
        }
    }

    @Nested
    @DisplayName("Назначение заявки диагносту")
    class AssignRequestTests {

        private User activeDiagnostician;

        @BeforeEach
        void setUpDiagnostician() {
            activeDiagnostician = new User(diagnosticId, "diag_user", "diag@test.com", "pass", true, Set.of());
        }

        @Test
        @DisplayName("Успешное назначение заявки")
        void assignToDiagnostic_Success() {
            when(userRepository.findById(diagnosticId)).thenReturn(Optional.of(activeDiagnostician));
            when(requestRepository.assignToDiagnostic(requestId, diagnosticId)).thenReturn(true);
            when(requestRepository.findById(requestId)).thenReturn(Optional.of(savedRequest));

            DiagnosticRequestResponse response = requestService.assignToDiagnostic(requestId, diagnosticId);

            assertThat(response).isNotNull();
            assertThat(response.getId()).isEqualTo(requestId);
            verify(requestRepository).assignToDiagnostic(requestId, diagnosticId);
        }

        @Test
        @DisplayName("Диагностик не найден — IllegalArgumentException")
        void assignToDiagnostic_DiagnosticianNotFound_ThrowsException() {
            when(userRepository.findById(diagnosticId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> requestService.assignToDiagnostic(requestId, diagnosticId))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Диагност не найден");
        }

        @Test
        @DisplayName("Диагностик заблокирован — IllegalStateException")
        void assignToDiagnostic_BlockedDiagnostician_ThrowsException() {
            User blocked = new User(diagnosticId, "diag_user", "diag@test.com", "pass", false, Set.of());
            when(userRepository.findById(diagnosticId)).thenReturn(Optional.of(blocked));

            assertThatThrownBy(() -> requestService.assignToDiagnostic(requestId, diagnosticId))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("заблокирован");
        }

        @Test
        @DisplayName("Назначение уже занятой заявки — IllegalStateException")
        void assignToDiagnostic_AlreadyAssigned_ThrowsException() {
            when(userRepository.findById(diagnosticId)).thenReturn(Optional.of(activeDiagnostician));
            when(requestRepository.assignToDiagnostic(requestId, diagnosticId)).thenReturn(false);

            assertThatThrownBy(() -> requestService.assignToDiagnostic(requestId, diagnosticId))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Не удалось назначить заявку");
        }

        @Test
        @DisplayName("Назначение несуществующей заявки — IllegalArgumentException")
        void assignToDiagnostic_NotFound_ThrowsException() {
            when(userRepository.findById(diagnosticId)).thenReturn(Optional.of(activeDiagnostician));
            when(requestRepository.assignToDiagnostic(requestId, diagnosticId)).thenReturn(true);
            when(requestRepository.findById(requestId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> requestService.assignToDiagnostic(requestId, diagnosticId))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Заявка не найдена");
        }
    }

    @Nested
    @DisplayName("Получение заявки с привязанным шаблоном (MEDIA-3.1A)")
    class GetRequestWithTemplateTests {

        @Test
        @DisplayName("Успешное получение заявки с привязанным шаблоном")
        void getRequestWithTemplate_Success() {
            savedRequest.setAssignedTo(diagnosticId);
            savedRequest.setTemplateId(1L);
            when(requestRepository.findById(requestId)).thenReturn(Optional.of(savedRequest));
            when(templateRepository.findFullTemplateById(1L)).thenReturn(Optional.of(template));
            when(answerRepository.findByDiagnosticRequestId(requestId)).thenReturn(List.of());

            DiagnosticRequestResponse response = requestService.getRequestWithTemplate(requestId, diagnosticId);

            assertThat(response).isNotNull();
            assertThat(response.getTemplate()).isNotNull();
            assertThat(response.getTemplate().getName()).isEqualTo("Стандартный шаблон");
            verify(templateRepository, never()).findActiveTemplates(any());
            verify(templateRepository, never()).findActiveTemplateById(any());
        }

        @Test
        @DisplayName("Доступ к чужой заявке - SecurityException")
        void getRequestWithTemplate_WrongDiagnostic_ThrowsException() {
            savedRequest.setAssignedTo(999L); // другой диагност
            when(requestRepository.findById(requestId)).thenReturn(Optional.of(savedRequest));

            assertThatThrownBy(() -> requestService.getRequestWithTemplate(requestId, diagnosticId))
                    .isInstanceOf(SecurityException.class)
                    .hasMessageContaining("Вы не назначены на эту заявку");
        }

        @Test
        @DisplayName("Шаблон ещё не привязан - IllegalStateException, без фолбэка на активный шаблон")
        void getRequestWithTemplate_NoTemplateBound_ThrowsException() {
            savedRequest.setAssignedTo(diagnosticId);
            savedRequest.setTemplateId(null);
            when(requestRepository.findById(requestId)).thenReturn(Optional.of(savedRequest));

            assertThatThrownBy(() -> requestService.getRequestWithTemplate(requestId, diagnosticId))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("не выбран шаблон");

            verify(templateRepository, never()).findActiveTemplates(any());
            verify(templateRepository, never()).findFullTemplateById(any());
        }

        @Test
        @DisplayName("Деактивация шаблона после привязки не влияет на уже привязанную заявку")
        void getRequestWithTemplate_TemplateDeactivatedAfterBinding_StillResolves() {
            savedRequest.setAssignedTo(diagnosticId);
            savedRequest.setTemplateId(1L);
            InspectionTemplate deactivated = new InspectionTemplate();
            deactivated.setId(1L);
            deactivated.setName("Стандартный шаблон");
            deactivated.setActive(false); // деактивирован ПОСЛЕ привязки
            when(requestRepository.findById(requestId)).thenReturn(Optional.of(savedRequest));
            when(templateRepository.findFullTemplateById(1L)).thenReturn(Optional.of(deactivated));
            when(answerRepository.findByDiagnosticRequestId(requestId)).thenReturn(List.of());

            DiagnosticRequestResponse response = requestService.getRequestWithTemplate(requestId, diagnosticId);

            assertThat(response.getTemplate()).isNotNull();
            assertThat(response.getTemplate().getId()).isEqualTo(1L);
        }
    }

    @Nested
    @DisplayName("Привязка шаблона к заявке (MEDIA-3.1A)")
    class BindTemplateTests {

        @BeforeEach
        void setUpInProgress() {
            savedRequest.setAssignedTo(diagnosticId);
            savedRequest.setStatus(DiagnosticRequest.DiagnosticRequestStatus.IN_PROGRESS);
            savedRequest.setTemplateId(null);
        }

        @Test
        @DisplayName("Успешная первая привязка")
        void bindTemplate_Success() {
            when(requestRepository.findById(requestId)).thenReturn(Optional.of(savedRequest));
            when(templateRepository.findTemplateById(1L)).thenReturn(Optional.of(template));
            when(requestRepository.bindTemplate(requestId, diagnosticId, 1L)).thenReturn(true);
            DiagnosticRequest bound = clone(savedRequest);
            bound.setTemplateId(1L);
            when(requestRepository.findById(requestId))
                    .thenReturn(Optional.of(savedRequest))
                    .thenReturn(Optional.of(bound));
            when(templateRepository.findFullTemplateById(1L)).thenReturn(Optional.of(template));

            DiagnosticRequestResponse response = requestService.bindTemplate(requestId, diagnosticId, 1L);

            assertThat(response.getTemplate()).isNotNull();
            assertThat(response.getTemplate().getId()).isEqualTo(1L);
            verify(requestRepository).bindTemplate(requestId, diagnosticId, 1L);
        }

        @Test
        @DisplayName("Требуется, чтобы вызывающий был назначенным диагностом")
        void bindTemplate_RequiresAssignedDiagnostician() {
            savedRequest.setAssignedTo(999L);
            when(requestRepository.findById(requestId)).thenReturn(Optional.of(savedRequest));

            assertThatThrownBy(() -> requestService.bindTemplate(requestId, diagnosticId, 1L))
                    .isInstanceOf(SecurityException.class);

            verify(requestRepository, never()).bindTemplate(anyLong(), anyLong(), anyLong());
        }

        @Test
        @DisplayName("Требуется статус IN_PROGRESS")
        void bindTemplate_RequiresInProgress() {
            savedRequest.setStatus(DiagnosticRequest.DiagnosticRequestStatus.PENDING);
            when(requestRepository.findById(requestId)).thenReturn(Optional.of(savedRequest));

            assertThatThrownBy(() -> requestService.bindTemplate(requestId, diagnosticId, 1L))
                    .isInstanceOf(IllegalStateException.class);

            verify(requestRepository, never()).bindTemplate(anyLong(), anyLong(), anyLong());
        }

        @Test
        @DisplayName("Несуществующий шаблон отклоняется")
        void bindTemplate_TemplateNotFound_ThrowsException() {
            when(requestRepository.findById(requestId)).thenReturn(Optional.of(savedRequest));
            when(templateRepository.findTemplateById(999L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> requestService.bindTemplate(requestId, diagnosticId, 999L))
                    .isInstanceOf(IllegalArgumentException.class);

            verify(requestRepository, never()).bindTemplate(anyLong(), anyLong(), anyLong());
        }

        @Test
        @DisplayName("Неактивный шаблон отклоняется")
        void bindTemplate_InactiveTemplate_ThrowsException() {
            InspectionTemplate inactive = new InspectionTemplate();
            inactive.setId(2L);
            inactive.setActive(false);
            when(requestRepository.findById(requestId)).thenReturn(Optional.of(savedRequest));
            when(templateRepository.findTemplateById(2L)).thenReturn(Optional.of(inactive));

            assertThatThrownBy(() -> requestService.bindTemplate(requestId, diagnosticId, 2L))
                    .isInstanceOf(IllegalStateException.class);

            verify(requestRepository, never()).bindTemplate(anyLong(), anyLong(), anyLong());
        }

        @Test
        @DisplayName("Повторная привязка того же шаблона - идемпотентный успех")
        void bindTemplate_SameTemplateResubmitted_IdempotentSuccess() {
            when(templateRepository.findTemplateById(1L)).thenReturn(Optional.of(template));
            when(requestRepository.bindTemplate(requestId, diagnosticId, 1L)).thenReturn(false);
            DiagnosticRequest alreadyBound = clone(savedRequest);
            alreadyBound.setTemplateId(1L);
            when(requestRepository.findById(requestId))
                    .thenReturn(Optional.of(savedRequest))
                    .thenReturn(Optional.of(alreadyBound))
                    .thenReturn(Optional.of(alreadyBound));
            when(templateRepository.findFullTemplateById(1L)).thenReturn(Optional.of(template));

            DiagnosticRequestResponse response = requestService.bindTemplate(requestId, diagnosticId, 1L);

            assertThat(response.getTemplate().getId()).isEqualTo(1L);
        }

        @Test
        @DisplayName("Другой шаблон после привязки - конфликт (409)")
        void bindTemplate_DifferentTemplateAfterBinding_ThrowsConflict() {
            InspectionTemplate other = new InspectionTemplate();
            other.setId(2L);
            other.setActive(true);
            when(templateRepository.findTemplateById(2L)).thenReturn(Optional.of(other));
            when(requestRepository.bindTemplate(requestId, diagnosticId, 2L)).thenReturn(false);
            DiagnosticRequest alreadyBoundToOther = clone(savedRequest);
            alreadyBoundToOther.setTemplateId(1L);
            when(requestRepository.findById(requestId))
                    .thenReturn(Optional.of(savedRequest))
                    .thenReturn(Optional.of(alreadyBoundToOther));

            assertThatThrownBy(() -> requestService.bindTemplate(requestId, diagnosticId, 2L))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("другому шаблону");
        }

        private DiagnosticRequest clone(DiagnosticRequest source) {
            DiagnosticRequest copy = new DiagnosticRequest();
            copy.setId(source.getId());
            copy.setUserId(source.getUserId());
            copy.setBrand(source.getBrand());
            copy.setModel(source.getModel());
            copy.setYear(source.getYear());
            copy.setVin(source.getVin());
            copy.setDepositFrozen(source.getDepositFrozen());
            copy.setStatus(source.getStatus());
            copy.setAssignedTo(source.getAssignedTo());
            copy.setTemplateId(source.getTemplateId());
            copy.setCreatedAt(source.getCreatedAt());
            copy.setUpdatedAt(source.getUpdatedAt());
            return copy;
        }
    }

    @Nested
    @DisplayName("Сохранение ответов на анкету")
    class SaveInspectionAnswersTests {

        @Test
        @DisplayName("Успешное сохранение черновика")
        void saveInspectionAnswers_Draft_Success() {
            savedRequest.setAssignedTo(diagnosticId);
            savedRequest.setStatus(DiagnosticRequest.DiagnosticRequestStatus.IN_PROGRESS);
            savedRequest.setTemplateId(1L);
            when(requestRepository.findById(requestId)).thenReturn(Optional.of(savedRequest));
            doNothing().when(answerRepository).deleteByDiagnosticRequestId(requestId);
            when(answerRepository.saveAll(anyList())).thenReturn(List.of());

            SubmitInspectionRequest submitRequest = new SubmitInspectionRequest();
            submitRequest.setSubmit(false);
            submitRequest.setAnswers(List.of());

            requestService.saveInspectionAnswers(requestId, diagnosticId, submitRequest);

            verify(answerRepository).deleteByDiagnosticRequestId(requestId);
            verify(answerRepository).saveAll(anyList());
            verify(requestRepository, never()).markAsCompleted(anyLong(), anyLong());
        }

        @Test
        @DisplayName("Успешная отправка на проверку")
        void saveInspectionAnswers_Submit_Success() {
            savedRequest.setAssignedTo(diagnosticId);
            savedRequest.setStatus(DiagnosticRequest.DiagnosticRequestStatus.IN_PROGRESS);
            savedRequest.setTemplateId(1L);
            when(requestRepository.findById(requestId)).thenReturn(Optional.of(savedRequest));
            doNothing().when(answerRepository).deleteByDiagnosticRequestId(requestId);
            when(answerRepository.saveAll(anyList())).thenReturn(List.of());
            // template fixture has zero groups/items (see setUp) - the mandatory-video gate
            // enumerates zero required items and passes trivially without touching mediaRepository.
            when(templateRepository.findFullTemplateById(1L)).thenReturn(Optional.of(template));
            when(requestRepository.markAsCompleted(requestId, diagnosticId)).thenReturn(true);

            SubmitInspectionRequest submitRequest = new SubmitInspectionRequest();
            submitRequest.setSubmit(true);
            submitRequest.setAnswers(List.of());

            requestService.saveInspectionAnswers(requestId, diagnosticId, submitRequest);

            verify(requestRepository).markAsCompleted(requestId, diagnosticId);
        }

        @Test
        @DisplayName("Сохранение ответов для чужой заявки - SecurityException")
        void saveInspectionAnswers_WrongDiagnostic_ThrowsException() {
            savedRequest.setAssignedTo(999L);
            when(requestRepository.findById(requestId)).thenReturn(Optional.of(savedRequest));

            SubmitInspectionRequest submitRequest = new SubmitInspectionRequest();
            submitRequest.setSubmit(false);
            submitRequest.setAnswers(List.of());

            assertThatThrownBy(() -> requestService.saveInspectionAnswers(requestId, diagnosticId, submitRequest))
                    .isInstanceOf(SecurityException.class);
        }

        @Test
        @DisplayName("MEDIA-3.1A: без привязанного шаблона - явный отказ, без вывода")
        void saveInspectionAnswers_NoTemplateBound_ThrowsException() {
            savedRequest.setAssignedTo(diagnosticId);
            savedRequest.setStatus(DiagnosticRequest.DiagnosticRequestStatus.IN_PROGRESS);
            savedRequest.setTemplateId(null);
            when(requestRepository.findById(requestId)).thenReturn(Optional.of(savedRequest));

            SubmitInspectionRequest submitRequest = new SubmitInspectionRequest();
            submitRequest.setSubmit(false);
            submitRequest.setAnswers(List.of());

            assertThatThrownBy(() -> requestService.saveInspectionAnswers(requestId, diagnosticId, submitRequest))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("не выбран шаблон");

            verify(answerRepository, never()).saveAll(anyList());
        }

        @Test
        @DisplayName("MEDIA-3.1A: пункт из привязанного шаблона принимается")
        void saveInspectionAnswers_ItemFromBoundTemplate_Accepted() {
            savedRequest.setAssignedTo(diagnosticId);
            savedRequest.setStatus(DiagnosticRequest.DiagnosticRequestStatus.IN_PROGRESS);
            savedRequest.setTemplateId(1L);
            when(requestRepository.findById(requestId)).thenReturn(Optional.of(savedRequest));

            InspectionItem item = new InspectionItem();
            item.setId(10L);
            item.setGroupId(20L);
            InspectionGroup group = new InspectionGroup();
            group.setId(20L);
            group.setTemplateId(1L); // тот же шаблон, что привязан к заявке
            when(templateRepository.findItemById(10L)).thenReturn(Optional.of(item));
            when(templateRepository.findGroupById(20L)).thenReturn(Optional.of(group));
            when(answerRepository.saveAll(anyList())).thenReturn(List.of());

            InspectionAnswerDto answerDto = new InspectionAnswerDto();
            answerDto.setItemId(10L);
            SubmitInspectionRequest submitRequest = new SubmitInspectionRequest();
            submitRequest.setSubmit(false);
            submitRequest.setAnswers(List.of(answerDto));

            requestService.saveInspectionAnswers(requestId, diagnosticId, submitRequest);

            verify(answerRepository).saveAll(anyList());
        }

        @Test
        @DisplayName("MEDIA-3.1A: пункт из другого шаблона отклоняется")
        void saveInspectionAnswers_ItemFromAnotherTemplate_Rejected() {
            savedRequest.setAssignedTo(diagnosticId);
            savedRequest.setStatus(DiagnosticRequest.DiagnosticRequestStatus.IN_PROGRESS);
            savedRequest.setTemplateId(1L);
            when(requestRepository.findById(requestId)).thenReturn(Optional.of(savedRequest));

            InspectionItem item = new InspectionItem();
            item.setId(10L);
            item.setGroupId(20L);
            InspectionGroup group = new InspectionGroup();
            group.setId(20L);
            group.setTemplateId(2L); // ДРУГОЙ шаблон, не привязанный к заявке
            when(templateRepository.findItemById(10L)).thenReturn(Optional.of(item));
            when(templateRepository.findGroupById(20L)).thenReturn(Optional.of(group));

            InspectionAnswerDto answerDto = new InspectionAnswerDto();
            answerDto.setItemId(10L);
            SubmitInspectionRequest submitRequest = new SubmitInspectionRequest();
            submitRequest.setSubmit(false);
            submitRequest.setAnswers(List.of(answerDto));

            assertThatThrownBy(() -> requestService.saveInspectionAnswers(requestId, diagnosticId, submitRequest))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("не принадлежит шаблону");

            verify(answerRepository, never()).saveAll(anyList());
        }

        @Test
        @DisplayName("MEDIA-3.1A: несуществующий пункт отклоняется существующей семантикой ошибок")
        void saveInspectionAnswers_NonexistentItem_Rejected() {
            savedRequest.setAssignedTo(diagnosticId);
            savedRequest.setStatus(DiagnosticRequest.DiagnosticRequestStatus.IN_PROGRESS);
            savedRequest.setTemplateId(1L);
            when(requestRepository.findById(requestId)).thenReturn(Optional.of(savedRequest));
            when(templateRepository.findItemById(999L)).thenReturn(Optional.empty());

            InspectionAnswerDto answerDto = new InspectionAnswerDto();
            answerDto.setItemId(999L);
            SubmitInspectionRequest submitRequest = new SubmitInspectionRequest();
            submitRequest.setSubmit(false);
            submitRequest.setAnswers(List.of(answerDto));

            assertThatThrownBy(() -> requestService.saveInspectionAnswers(requestId, diagnosticId, submitRequest))
                    .isInstanceOf(IllegalArgumentException.class);

            verify(answerRepository, never()).saveAll(anyList());
        }

        @Test
        @DisplayName("MEDIA-3.3: COMPLETED - сохранение ответов запрещено")
        void saveInspectionAnswers_Completed_ThrowsException() {
            savedRequest.setAssignedTo(diagnosticId);
            savedRequest.setStatus(DiagnosticRequest.DiagnosticRequestStatus.COMPLETED);
            savedRequest.setTemplateId(1L);
            when(requestRepository.findById(requestId)).thenReturn(Optional.of(savedRequest));

            SubmitInspectionRequest submitRequest = new SubmitInspectionRequest();
            submitRequest.setSubmit(false);
            submitRequest.setAnswers(List.of());

            assertThatThrownBy(() -> requestService.saveInspectionAnswers(requestId, diagnosticId, submitRequest))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("IN_PROGRESS");

            verify(answerRepository, never()).saveAll(anyList());
            verify(answerRepository, never()).deleteByDiagnosticRequestId(anyLong());
        }

        @Test
        @DisplayName("MEDIA-3.3: APPROVED - сохранение ответов запрещено (терминальный статус)")
        void saveInspectionAnswers_Approved_ThrowsException() {
            savedRequest.setAssignedTo(diagnosticId);
            savedRequest.setStatus(DiagnosticRequest.DiagnosticRequestStatus.APPROVED);
            savedRequest.setTemplateId(1L);
            when(requestRepository.findById(requestId)).thenReturn(Optional.of(savedRequest));

            SubmitInspectionRequest submitRequest = new SubmitInspectionRequest();
            submitRequest.setSubmit(false);
            submitRequest.setAnswers(List.of());

            assertThatThrownBy(() -> requestService.saveInspectionAnswers(requestId, diagnosticId, submitRequest))
                    .isInstanceOf(IllegalStateException.class);

            verify(answerRepository, never()).saveAll(anyList());
            verify(answerRepository, never()).deleteByDiagnosticRequestId(anyLong());
        }

        @Test
        @DisplayName("MEDIA-3.3: REJECTED - сохранение ответов запрещено до resetForRework()")
        void saveInspectionAnswers_Rejected_ThrowsException() {
            savedRequest.setAssignedTo(diagnosticId);
            savedRequest.setStatus(DiagnosticRequest.DiagnosticRequestStatus.REJECTED);
            savedRequest.setTemplateId(1L);
            when(requestRepository.findById(requestId)).thenReturn(Optional.of(savedRequest));

            SubmitInspectionRequest submitRequest = new SubmitInspectionRequest();
            submitRequest.setSubmit(false);
            submitRequest.setAnswers(List.of());

            assertThatThrownBy(() -> requestService.saveInspectionAnswers(requestId, diagnosticId, submitRequest))
                    .isInstanceOf(IllegalStateException.class);

            verify(answerRepository, never()).saveAll(anyList());
            verify(answerRepository, never()).deleteByDiagnosticRequestId(anyLong());
        }

        @Test
        @DisplayName("MEDIA-3.3: PENDING - сохранение ответов запрещено (нет назначенного диагноста)")
        void saveInspectionAnswers_Pending_ThrowsException() {
            savedRequest.setAssignedTo(diagnosticId);
            savedRequest.setStatus(DiagnosticRequest.DiagnosticRequestStatus.PENDING);
            savedRequest.setTemplateId(1L);
            when(requestRepository.findById(requestId)).thenReturn(Optional.of(savedRequest));

            SubmitInspectionRequest submitRequest = new SubmitInspectionRequest();
            submitRequest.setSubmit(false);
            submitRequest.setAnswers(List.of());

            assertThatThrownBy(() -> requestService.saveInspectionAnswers(requestId, diagnosticId, submitRequest))
                    .isInstanceOf(IllegalStateException.class);

            verify(answerRepository, never()).saveAll(anyList());
        }

        @Test
        @DisplayName("MEDIA-3.3: легитимный rework (REJECTED -> IN_PROGRESS через resetForRework) " +
                "восстанавливает возможность сохранять ответы")
        void saveInspectionAnswers_AfterLegitimateRework_Succeeds() {
            savedRequest.setAssignedTo(diagnosticId);
            // simulates the state AFTER resetForRework() has already transitioned the request
            savedRequest.setStatus(DiagnosticRequest.DiagnosticRequestStatus.IN_PROGRESS);
            savedRequest.setTemplateId(1L);
            when(requestRepository.findById(requestId)).thenReturn(Optional.of(savedRequest));
            doNothing().when(answerRepository).deleteByDiagnosticRequestId(requestId);
            when(answerRepository.saveAll(anyList())).thenReturn(List.of());

            SubmitInspectionRequest submitRequest = new SubmitInspectionRequest();
            submitRequest.setSubmit(false);
            submitRequest.setAnswers(List.of());

            requestService.saveInspectionAnswers(requestId, diagnosticId, submitRequest);

            verify(answerRepository).saveAll(anyList());
        }
    }

    @Nested
    @DisplayName("Обязательное видео при завершении заявки (mandatory video completion gate)")
    class MandatoryVideoGateTests {

        private InspectionItem videoItem;
        private InspectionTemplate templateWithVideoItem;

        @BeforeEach
        void setUpBoundInProgress() {
            savedRequest.setAssignedTo(diagnosticId);
            savedRequest.setStatus(DiagnosticRequest.DiagnosticRequestStatus.IN_PROGRESS);
            savedRequest.setTemplateId(1L);
            when(requestRepository.findById(requestId)).thenReturn(Optional.of(savedRequest));
            doNothing().when(answerRepository).deleteByDiagnosticRequestId(requestId);
            when(answerRepository.saveAll(anyList())).thenReturn(List.of());

            videoItem = new InspectionItem();
            videoItem.setId(10L);
            videoItem.setGroupId(20L);
            videoItem.setHasVideo(true);

            InspectionGroup group = new InspectionGroup();
            group.setId(20L);
            group.setTemplateId(1L);
            group.setItems(List.of(videoItem));

            templateWithVideoItem = new InspectionTemplate();
            templateWithVideoItem.setId(1L);
            templateWithVideoItem.setGroups(List.of(group));
        }

        private SubmitInspectionRequest submitRequest(InspectionAnswerDto... answers) {
            SubmitInspectionRequest dto = new SubmitInspectionRequest();
            dto.setSubmit(true);
            dto.setAnswers(List.of(answers));
            return dto;
        }

        @Test
        @DisplayName("Требуемое видео отсутствует - IllegalStateException, завершение не выполняется")
        void submit_RequiredVideoMissing_ThrowsAndDoesNotComplete() {
            when(templateRepository.findFullTemplateById(1L)).thenReturn(Optional.of(templateWithVideoItem));
            when(mediaRepository.existsQualifyingVideo(requestId, 10L)).thenReturn(false);

            assertThatThrownBy(() -> requestService.saveInspectionAnswers(requestId, diagnosticId, submitRequest()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("видео");

            verify(requestRepository, never()).markAsCompleted(anyLong(), anyLong());
        }

        @Test
        @DisplayName("Требуемое видео полностью готово - завершение проходит существующим путём")
        void submit_RequiredVideoQualifies_Completes() {
            when(templateRepository.findFullTemplateById(1L)).thenReturn(Optional.of(templateWithVideoItem));
            when(mediaRepository.existsQualifyingVideo(requestId, 10L)).thenReturn(true);
            when(requestRepository.markAsCompleted(requestId, diagnosticId)).thenReturn(true);

            requestService.saveInspectionAnswers(requestId, diagnosticId, submitRequest());

            verify(requestRepository).markAsCompleted(requestId, diagnosticId);
        }

        @Test
        @DisplayName("Два требуемых пункта, удовлетворён только один - отклонено")
        void submit_MultipleRequiredItems_OnlyOneSatisfied_Rejected() {
            InspectionItem secondVideoItem = new InspectionItem();
            secondVideoItem.setId(11L);
            secondVideoItem.setGroupId(20L);
            secondVideoItem.setHasVideo(true);
            templateWithVideoItem.getGroups().get(0).setItems(List.of(videoItem, secondVideoItem));

            when(templateRepository.findFullTemplateById(1L)).thenReturn(Optional.of(templateWithVideoItem));
            when(mediaRepository.existsQualifyingVideo(requestId, 10L)).thenReturn(true);
            when(mediaRepository.existsQualifyingVideo(requestId, 11L)).thenReturn(false);

            assertThatThrownBy(() -> requestService.saveInspectionAnswers(requestId, diagnosticId, submitRequest()))
                    .isInstanceOf(IllegalStateException.class);

            verify(requestRepository, never()).markAsCompleted(anyLong(), anyLong());
        }

        @Test
        @DisplayName("Два требуемых пункта, оба удовлетворены - завершение проходит")
        void submit_MultipleRequiredItems_BothSatisfied_Completes() {
            InspectionItem secondVideoItem = new InspectionItem();
            secondVideoItem.setId(11L);
            secondVideoItem.setGroupId(20L);
            secondVideoItem.setHasVideo(true);
            templateWithVideoItem.getGroups().get(0).setItems(List.of(videoItem, secondVideoItem));

            when(templateRepository.findFullTemplateById(1L)).thenReturn(Optional.of(templateWithVideoItem));
            when(mediaRepository.existsQualifyingVideo(requestId, 10L)).thenReturn(true);
            when(mediaRepository.existsQualifyingVideo(requestId, 11L)).thenReturn(true);
            when(requestRepository.markAsCompleted(requestId, diagnosticId)).thenReturn(true);

            requestService.saveInspectionAnswers(requestId, diagnosticId, submitRequest());

            verify(requestRepository).markAsCompleted(requestId, diagnosticId);
        }

        @Test
        @DisplayName("Требуемые пункты берутся из шаблона, а не из dto.answers - " +
                "пустой список ответов не позволяет обойти проверку")
        void submit_RequiredItemsComeFromTemplate_NotFromClientAnswers() {
            when(templateRepository.findFullTemplateById(1L)).thenReturn(Optional.of(templateWithVideoItem));
            when(mediaRepository.existsQualifyingVideo(requestId, 10L)).thenReturn(false);

            // dto carries zero answers - the gate must still find and enforce the
            // template's own required item, never relying on the (empty) answer list.
            assertThatThrownBy(() -> requestService.saveInspectionAnswers(requestId, diagnosticId, submitRequest()))
                    .isInstanceOf(IllegalStateException.class);

            verify(mediaRepository).existsQualifyingVideo(requestId, 10L);
            verify(requestRepository, never()).markAsCompleted(anyLong(), anyLong());
        }

        @Test
        @DisplayName("Шаблон без требуемых видео - существующее поведение не меняется, " +
                "mediaRepository не опрашивается")
        void submit_NoVideoRequirements_UnaffectedByGate() {
            InspectionItem photoOnlyItem = new InspectionItem();
            photoOnlyItem.setId(12L);
            photoOnlyItem.setGroupId(20L);
            photoOnlyItem.setHasVideo(false);
            templateWithVideoItem.getGroups().get(0).setItems(List.of(photoOnlyItem));

            when(templateRepository.findFullTemplateById(1L)).thenReturn(Optional.of(templateWithVideoItem));
            when(requestRepository.markAsCompleted(requestId, diagnosticId)).thenReturn(true);

            requestService.saveInspectionAnswers(requestId, diagnosticId, submitRequest());

            verify(requestRepository).markAsCompleted(requestId, diagnosticId);
            verifyNoInteractions(mediaRepository);
        }
    }

    @Nested
    @DisplayName("Исправление отклонённого отчёта сохраняет привязанный шаблон (MEDIA-3.1A)")
    class ResetForReworkPreservesTemplateTests {

        @Test
        @DisplayName("resetForRework не трогает templateId")
        void resetForRework_DoesNotTouchTemplateId() {
            savedRequest.setAssignedTo(diagnosticId);
            savedRequest.setStatus(DiagnosticRequest.DiagnosticRequestStatus.REJECTED);
            savedRequest.setTemplateId(1L);
            when(requestRepository.findById(requestId)).thenReturn(Optional.of(savedRequest));
            when(requestRepository.resetForRework(requestId, diagnosticId)).thenReturn(true);

            requestService.resetForRework(requestId, diagnosticId);

            // resetForRework() must never call bindTemplate() or otherwise mutate
            // template_id - the repository method it does call has no templateId
            // parameter at all, so this is enforced structurally, not just by
            // this assertion; verifying here as an explicit regression guard.
            verify(requestRepository, never()).bindTemplate(anyLong(), anyLong(), anyLong());
            verify(requestRepository).resetForRework(requestId, diagnosticId);
        }
    }

    // PHASE 2: cancelRequest is explicitly out of scope and must remain unchanged - it still
    // calls releaseCommitment (full release), never markAwaitingFinalization. Regression guard.
    @Nested
    @DisplayName("Отзыв заявки пользователем (PHASE 2: должен остаться без изменений)")
    class CancelRequestTests {

        @Test
        @DisplayName("cancelRequest вызывает releaseCommitment, а не markAwaitingFinalization")
        void cancelRequest_StillCallsReleaseCommitment_NotMarkAwaitingFinalization() {
            savedRequest.setStatus(DiagnosticRequest.DiagnosticRequestStatus.PENDING);
            when(requestRepository.findById(requestId)).thenReturn(Optional.of(savedRequest));
            when(requestRepository.save(any(DiagnosticRequest.class))).thenAnswer(i -> i.getArgument(0));

            requestService.cancelRequest(requestId, userId);

            verify(sellerDiagnosticCommitmentService, times(1)).releaseCommitment(userId, requestId);
            verify(sellerDiagnosticCommitmentService, never()).markAwaitingFinalization(anyLong());
        }

        @Test
        @DisplayName("Отзыв чужой заявки - SecurityException, commitment не трогается")
        void cancelRequest_WrongUser_ThrowsException() {
            savedRequest.setUserId(999L);
            savedRequest.setStatus(DiagnosticRequest.DiagnosticRequestStatus.PENDING);
            when(requestRepository.findById(requestId)).thenReturn(Optional.of(savedRequest));

            assertThatThrownBy(() -> requestService.cancelRequest(requestId, userId))
                    .isInstanceOf(SecurityException.class);

            verifyNoInteractions(sellerDiagnosticCommitmentService);
        }

        @Test
        @DisplayName("Отзыв заявки не в статусе PENDING - IllegalStateException, commitment не трогается")
        void cancelRequest_WrongStatus_ThrowsException() {
            savedRequest.setStatus(DiagnosticRequest.DiagnosticRequestStatus.IN_PROGRESS);
            when(requestRepository.findById(requestId)).thenReturn(Optional.of(savedRequest));

            assertThatThrownBy(() -> requestService.cancelRequest(requestId, userId))
                    .isInstanceOf(IllegalStateException.class);

            verifyNoInteractions(sellerDiagnosticCommitmentService);
        }
    }

    @Nested
    @DisplayName("Получение деталей заявки для админа")
    class GetRequestDetailsTests {

        @Test
        @DisplayName("Успешное получение деталей заявки")
        void getRequestDetails_Success() {
            savedRequest.setAssignedTo(diagnosticId);
            when(requestRepository.findById(requestId)).thenReturn(Optional.of(savedRequest));
            when(answerRepository.findByDiagnosticRequestId(requestId)).thenReturn(List.of());

            User diagnosticUser = new User(diagnosticId, "diagnostic", "diag@test.com", "pass", true, Set.of());
            User requesterUser = new User(userId, "requester", "user@test.com", "pass", true, Set.of());
            when(userRepository.findById(diagnosticId)).thenReturn(Optional.of(diagnosticUser));
            when(userRepository.findById(userId)).thenReturn(Optional.of(requesterUser));

            DiagnosticRequestResponse response = requestService.getRequestDetails(requestId);

            assertThat(response).isNotNull();
            assertThat(response.getDiagnosticName()).isEqualTo("diagnostic");
            assertThat(response.getUserName()).isEqualTo("requester");
        }

        @Test
        @DisplayName("Sale-UI Gap #1: vehicleId проставляется из VehicleService, если Vehicle уже создан")
        void getRequestDetails_VehicleExists_PopulatesVehicleId() {
            when(requestRepository.findById(requestId)).thenReturn(Optional.of(savedRequest));
            when(answerRepository.findByDiagnosticRequestId(requestId)).thenReturn(List.of());
            when(vehicleService.findVehicleIdByDiagnosticRequestId(requestId)).thenReturn(42L);

            DiagnosticRequestResponse response = requestService.getRequestDetails(requestId);

            assertThat(response.getVehicleId()).isEqualTo(42L);
        }

        @Test
        @DisplayName("Sale-UI Gap #1: vehicleId остаётся null, если Vehicle ещё не создан")
        void getRequestDetails_NoVehicleYet_VehicleIdNull() {
            when(requestRepository.findById(requestId)).thenReturn(Optional.of(savedRequest));
            when(answerRepository.findByDiagnosticRequestId(requestId)).thenReturn(List.of());
            when(vehicleService.findVehicleIdByDiagnosticRequestId(requestId)).thenReturn(null);

            DiagnosticRequestResponse response = requestService.getRequestDetails(requestId);

            assertThat(response.getVehicleId()).isNull();
        }

        @Test
        @DisplayName("Seller-violation-resolution UI: commitmentStatus/violationResolution остаются null, если commitment никогда не замораживался")
        void getRequestDetails_NoCommitment_StatusAndResolutionNull() {
            when(requestRepository.findById(requestId)).thenReturn(Optional.of(savedRequest));
            when(answerRepository.findByDiagnosticRequestId(requestId)).thenReturn(List.of());
            when(sellerDiagnosticCommitmentService.findByDiagnosticRequestId(requestId)).thenReturn(Optional.empty());

            DiagnosticRequestResponse response = requestService.getRequestDetails(requestId);

            assertThat(response.getCommitmentStatus()).isNull();
            assertThat(response.getViolationResolution()).isNull();
        }

        @Test
        @DisplayName("Seller-violation-resolution UI: commitmentStatus проставляется, violationResolution остаётся null, пока commitment не FINALIZED")
        void getRequestDetails_CommitmentAwaitingFinalization_StatusSetResolutionNull() {
            when(requestRepository.findById(requestId)).thenReturn(Optional.of(savedRequest));
            when(answerRepository.findByDiagnosticRequestId(requestId)).thenReturn(List.of());

            SellerDiagnosticCommitment commitment = new SellerDiagnosticCommitment();
            commitment.setId(77L);
            commitment.setStatus(CommitmentStatus.AWAITING_FINALIZATION);
            when(sellerDiagnosticCommitmentService.findByDiagnosticRequestId(requestId)).thenReturn(Optional.of(commitment));

            DiagnosticRequestResponse response = requestService.getRequestDetails(requestId);

            assertThat(response.getCommitmentStatus()).isEqualTo("AWAITING_FINALIZATION");
            assertThat(response.getViolationResolution()).isNull();
            verify(sellerDiagnosticCommitmentService, never()).findViolationResolution(anyLong());
        }

        @Test
        @DisplayName("Seller-violation-resolution UI: violationResolution проставляется целиком из существующего разрешения, если commitment FINALIZED")
        void getRequestDetails_CommitmentFinalized_PopulatesViolationResolution() {
            when(requestRepository.findById(requestId)).thenReturn(Optional.of(savedRequest));
            when(answerRepository.findByDiagnosticRequestId(requestId)).thenReturn(List.of());

            SellerDiagnosticCommitment commitment = new SellerDiagnosticCommitment();
            commitment.setId(77L);
            commitment.setStatus(CommitmentStatus.FINALIZED);
            when(sellerDiagnosticCommitmentService.findByDiagnosticRequestId(requestId)).thenReturn(Optional.of(commitment));

            OffsetDateTime resolvedAt = OffsetDateTime.now();
            SellerViolationResolution resolution = new SellerViolationResolution(77L, TriggerSource.DIAGNOSTIC_REJECTION,
                    null, 9L, new BigDecimal("5000.00"), new BigDecimal("4000.00"), new BigDecimal("1000.00"),
                    5L, resolvedAt, resolvedAt);
            when(sellerDiagnosticCommitmentService.findViolationResolution(77L)).thenReturn(Optional.of(resolution));
            when(sellerDiagnosticCommitmentService.findViolationIdsForResolution(resolution.getId())).thenReturn(List.of(3L, 4L));

            DiagnosticRequestResponse response = requestService.getRequestDetails(requestId);

            assertThat(response.getCommitmentStatus()).isEqualTo("FINALIZED");
            assertThat(response.getViolationResolution()).isNotNull();
            assertThat(response.getViolationResolution().violationDefinitionIds()).containsExactly(3L, 4L);
            assertThat(response.getViolationResolution().appliedPenaltyAmount()).isEqualByComparingTo("4000.00");
            assertThat(response.getViolationResolution().refundAmount()).isEqualByComparingTo("1000.00");
        }

        @Test
        @DisplayName("MEDIA-3.1B: templateId привязан - резолвится напрямую по ID, без обращения к ответам")
        void getRequestDetails_TemplateBound_ResolvesDirectly() {
            savedRequest.setAssignedTo(diagnosticId);
            savedRequest.setTemplateId(1L);
            when(requestRepository.findById(requestId)).thenReturn(Optional.of(savedRequest));
            when(answerRepository.findByDiagnosticRequestId(requestId)).thenReturn(List.of());
            when(templateRepository.findFullTemplateById(1L)).thenReturn(Optional.of(template));

            DiagnosticRequestResponse response = requestService.getRequestDetails(requestId);

            assertThat(response.getTemplate()).isNotNull();
            assertThat(response.getTemplate().getId()).isEqualTo(1L);
            verify(templateRepository, never()).findItemById(any());
            verify(templateRepository, never()).findActiveTemplates(any());
        }

        @Test
        @DisplayName("MEDIA-3.1B: templateId не привязан (историческая заявка) - template=null, " +
                "БЕЗ инференса из первого ответа и БЕЗ фолбэка на активный шаблон")
        void getRequestDetails_TemplateUnbound_NullWithoutInference() {
            savedRequest.setAssignedTo(diagnosticId);
            savedRequest.setTemplateId(null);
            when(requestRepository.findById(requestId)).thenReturn(Optional.of(savedRequest));

            InspectionAnswer answer = new InspectionAnswer();
            answer.setInspectionItemId(10L);
            when(answerRepository.findByDiagnosticRequestId(requestId)).thenReturn(List.of(answer));

            DiagnosticRequestResponse response = requestService.getRequestDetails(requestId);

            assertThat(response.getTemplate()).isNull();
            assertThat(response.getExistingAnswers()).hasSize(1); // raw answer data still readable
            verify(templateRepository, never()).findItemById(any());
            verify(templateRepository, never()).findGroupById(any());
            verify(templateRepository, never()).findActiveTemplates(any());
        }
    }

    @Nested
    @DisplayName("Получение заявок пользователя (мои заявки)")
    class GetUserRequestsTests {

        @Test
        @DisplayName("Sale-UI Gap #1: каждая строка обогащается своим vehicleId")
        void getUserRequests_PopulatesVehicleIdPerRow() {
            when(requestRepository.findByUserId(eq(userId), any(Pageable.class)))
                    .thenReturn(new PageImpl<>(List.of(savedRequest)));
            when(vehicleService.findVehicleIdByDiagnosticRequestId(requestId)).thenReturn(7L);

            Page<DiagnosticRequestResponse> page = requestService.getUserRequests(userId, PageRequest.of(0, 20));

            assertThat(page.getContent()).hasSize(1);
            assertThat(page.getContent().get(0).getVehicleId()).isEqualTo(7L);
        }
    }

    @Nested
    @DisplayName("Публичный отчёт по автомобилю (MEDIA-3.1B)")
    class GetApprovedReportForVehicleTests {

        private com.example.autoauction.vehicle.web.dto.VehicleResponse vehicleResponse(String vin) {
            return new com.example.autoauction.vehicle.web.dto.VehicleResponse(
                    1L, "BMW", "X5", 2022, vin, null, null, null, null, null, null, null, null, null,
                    null, null, null, null, null, null, null, null, null);
        }

        @Test
        @DisplayName("templateId привязан - резолвится напрямую по ID")
        void getApprovedReportForVehicle_TemplateBound_ResolvesDirectly() {
            savedRequest.setStatus(DiagnosticRequest.DiagnosticRequestStatus.APPROVED);
            savedRequest.setTemplateId(1L);
            when(vehicleService.getVehicleDetails(5L)).thenReturn(vehicleResponse("WBSDE91070CZ12345"));
            when(requestRepository.findApprovedByVin("WBSDE91070CZ12345")).thenReturn(Optional.of(savedRequest));
            when(answerRepository.findByDiagnosticRequestId(requestId)).thenReturn(List.of());
            when(templateRepository.findFullTemplateById(1L)).thenReturn(Optional.of(template));

            DiagnosticRequestResponse response = requestService.getApprovedReportForVehicle(5L);

            assertThat(response.getTemplate()).isNotNull();
            assertThat(response.getTemplate().getId()).isEqualTo(1L);
            verify(templateRepository, never()).findItemById(any());
            verify(templateRepository, never()).findActiveTemplates(any());
        }

        @Test
        @DisplayName("templateId не привязан - template=null, без инференса из первого ответа, " +
                "публичный отчёт остаётся читаемым (raw ответы сохраняются)")
        void getApprovedReportForVehicle_TemplateUnbound_NullWithoutInference() {
            savedRequest.setStatus(DiagnosticRequest.DiagnosticRequestStatus.APPROVED);
            savedRequest.setTemplateId(null);
            when(vehicleService.getVehicleDetails(5L)).thenReturn(vehicleResponse("WBSDE91070CZ12345"));
            when(requestRepository.findApprovedByVin("WBSDE91070CZ12345")).thenReturn(Optional.of(savedRequest));

            InspectionAnswer answerFromSomeTemplate = new InspectionAnswer();
            answerFromSomeTemplate.setInspectionItemId(10L);
            when(answerRepository.findByDiagnosticRequestId(requestId)).thenReturn(List.of(answerFromSomeTemplate));

            DiagnosticRequestResponse response = requestService.getApprovedReportForVehicle(5L);

            assertThat(response.getTemplate()).isNull();
            assertThat(response.getExistingAnswers()).hasSize(1);
            verify(templateRepository, never()).findItemById(any());
            verify(templateRepository, never()).findActiveTemplates(any());
        }
    }

    // PHASE 2: rejectReport() no longer releases the applicant's deposit (R-1.2's original
    // "release" mechanism) - it now transitions the commitment FROZEN -> AWAITING_FINALIZATION,
    // exactly like approveReport(). The deposit stays frozen until a future finalization phase
    // computes the actual outcome (B or C) and only then releases/charges it.
    @Nested
    @DisplayName("Отклонение отчёта (PHASE 2)")
    class RejectReportTests {

        private final Long adminId = 999L;

        private DiagnosticRequest completedRequest() {
            DiagnosticRequest r = new DiagnosticRequest();
            r.setId(requestId);
            r.setUserId(userId);
            r.setBrand("BMW");
            r.setModel("X5");
            r.setYear(2022);
            r.setVin("WBSDE91070CZ12345");
            r.setDepositFrozen(new BigDecimal("10000"));
            r.setStatus(DiagnosticRequest.DiagnosticRequestStatus.COMPLETED);
            r.setCreatedAt(OffsetDateTime.now());
            r.setUpdatedAt(OffsetDateTime.now());
            return r;
        }

        @Test
        @DisplayName("1. rejectReport переводит commitment в AWAITING_FINALIZATION, не освобождает депозит")
        void rejectReport_TransitionsCommitmentToAwaitingFinalization() {
            DiagnosticRequest completed = completedRequest();
            when(requestRepository.findById(requestId)).thenReturn(Optional.of(completed));
            when(requestRepository.save(any(DiagnosticRequest.class))).thenAnswer(i -> i.getArgument(0));

            requestService.rejectReport(requestId, adminId, "Недостаточно фотографий", DiagnosticRejectionCategory.VEHICLE_CONDITION);

            verify(sellerDiagnosticCommitmentService, times(1)).markAwaitingFinalization(requestId);
            verify(sellerDiagnosticCommitmentService, never()).releaseCommitment(anyLong(), anyLong());
        }

        @Test
        @DisplayName("2. Категория отклонения сохраняется на заявке")
        void rejectReport_PersistsRejectionCategory() {
            DiagnosticRequest completed = completedRequest();
            when(requestRepository.findById(requestId)).thenReturn(Optional.of(completed));
            when(requestRepository.save(any(DiagnosticRequest.class))).thenAnswer(i -> i.getArgument(0));

            DiagnosticRequestResponse response = requestService.rejectReport(
                    requestId, adminId, "Продавец не явился", DiagnosticRejectionCategory.SELLER_VIOLATION);

            assertThat(response.getRejectionCategory()).isEqualTo("SELLER_VIOLATION");
            ArgumentCaptor<DiagnosticRequest> captor = ArgumentCaptor.forClass(DiagnosticRequest.class);
            verify(requestRepository).save(captor.capture());
            assertThat(captor.getValue().getRejectionCategory()).isEqualTo(DiagnosticRejectionCategory.SELLER_VIOLATION);
        }

        @Test
        @DisplayName("3. Отсутствие категории — IllegalArgumentException, до любых мутаций (fail-closed)")
        void rejectReport_MissingCategory_ThrowsBeforeAnyMutation() {
            assertThatThrownBy(() -> requestService.rejectReport(requestId, adminId, "причина", null))
                    .isInstanceOf(IllegalArgumentException.class);

            verifyNoInteractions(sellerDiagnosticCommitmentService);
            verify(requestRepository, never()).save(any());
        }

        @Test
        @DisplayName("4. Повторное отклонение уже отклонённого отчёта — блокируется существующим " +
                "guard'ом жизненного цикла (статус уже не COMPLETED)")
        void rejectReport_AlreadyRejected_BlockedByLifecycleGuard() {
            DiagnosticRequest alreadyRejected = completedRequest();
            alreadyRejected.setStatus(DiagnosticRequest.DiagnosticRequestStatus.REJECTED);
            when(requestRepository.findById(requestId)).thenReturn(Optional.of(alreadyRejected));

            assertThatThrownBy(() -> requestService.rejectReport(requestId, adminId, "повтор", DiagnosticRejectionCategory.VEHICLE_CONDITION))
                    .isInstanceOf(IllegalStateException.class);

            verifyNoInteractions(sellerDiagnosticCommitmentService);
        }

        @Test
        @DisplayName("5. Сбой при переходе commitment — исключение пробрасывается наружу (откатывает всю " +
                "транзакцию rejectReport целиком)")
        void rejectReport_TransitionFailure_Propagates() {
            DiagnosticRequest completed = completedRequest();
            when(requestRepository.findById(requestId)).thenReturn(Optional.of(completed));
            when(requestRepository.save(any(DiagnosticRequest.class))).thenAnswer(i -> i.getArgument(0));
            doThrow(new IllegalStateException("DB unavailable"))
                    .when(sellerDiagnosticCommitmentService).markAwaitingFinalization(requestId);

            assertThatThrownBy(() -> requestService.rejectReport(requestId, adminId, "причина", DiagnosticRejectionCategory.VEHICLE_CONDITION))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("DB unavailable");
        }

        @Test
        @DisplayName("Пустая причина отклонения — IllegalArgumentException, депозит не трогается")
        void rejectReport_BlankReason_ThrowsBeforeAnyMutation() {
            assertThatThrownBy(() -> requestService.rejectReport(requestId, adminId, "  ", DiagnosticRejectionCategory.VEHICLE_CONDITION))
                    .isInstanceOf(IllegalArgumentException.class);

            verifyNoInteractions(sellerDiagnosticCommitmentService);
        }

        @Test
        @DisplayName("Можно отклонить только отчёт в статусе COMPLETED")
        void rejectReport_WrongStatus_ThrowsBeforeTransition() {
            DiagnosticRequest pending = completedRequest();
            pending.setStatus(DiagnosticRequest.DiagnosticRequestStatus.PENDING);
            when(requestRepository.findById(requestId)).thenReturn(Optional.of(pending));

            assertThatThrownBy(() -> requestService.rejectReport(requestId, adminId, "причина", DiagnosticRejectionCategory.VEHICLE_CONDITION))
                    .isInstanceOf(IllegalStateException.class);

            verifyNoInteractions(sellerDiagnosticCommitmentService);
        }
    }
}
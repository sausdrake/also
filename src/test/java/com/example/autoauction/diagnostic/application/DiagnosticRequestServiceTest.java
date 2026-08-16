package com.example.autoauction.diagnostic.application;

import com.example.autoauction.deposit.application.DepositService;
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
    private DepositService depositService;

    @Mock
    private UserRepository userRepository;

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
            Page<DiagnosticRequest> emptyPage = Page.empty();
            when(requestRepository.findByUserId(eq(userId), any(Pageable.class))).thenReturn(emptyPage);

            when(requestRepository.save(any(DiagnosticRequest.class))).thenReturn(savedRequest);
            doNothing().when(depositService).freezeForDiagnosticRequest(eq(userId), eq(requestId), any(BigDecimal.class));

            DiagnosticRequestResponse response = requestService.createRequest(createRequest, userId);

            assertThat(response).isNotNull();
            assertThat(response.getId()).isEqualTo(requestId);
            assertThat(response.getBrand()).isEqualTo("BMW");
            assertThat(response.getStatus()).isEqualTo("PENDING");

            verify(requestRepository).save(any(DiagnosticRequest.class));
            verify(depositService).freezeForDiagnosticRequest(eq(userId), eq(requestId), any(BigDecimal.class));
        }

        @Test
        @DisplayName("Создание заявки с null VIN - должно падать")
        void createRequest_NullVin_ThrowsException() {
            createRequest.setVin(null);
            assertThatThrownBy(() -> requestService.createRequest(createRequest, userId))
                    .isInstanceOf(Exception.class);
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
            savedRequest.setTemplateId(1L);
            when(requestRepository.findById(requestId)).thenReturn(Optional.of(savedRequest));
            doNothing().when(answerRepository).deleteByDiagnosticRequestId(requestId);
            when(answerRepository.saveAll(anyList())).thenReturn(List.of());
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
    }

    // R-1.2: rejectReport() must unfreeze the applicant's deposit exactly like
    // approveReport() already does - otherwise the deposit is permanently stranded.
    @Nested
    @DisplayName("Отклонение отчёта (R-1.2)")
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
        @DisplayName("1. rejectReport размораживает депозит заявителя")
        void rejectReport_UnfreezesDeposit() {
            DiagnosticRequest completed = completedRequest();
            when(requestRepository.findById(requestId)).thenReturn(Optional.of(completed));
            when(requestRepository.save(any(DiagnosticRequest.class))).thenAnswer(i -> i.getArgument(0));

            requestService.rejectReport(requestId, adminId, "Недостаточно фотографий");

            verify(depositService, times(1)).unfreezeForDiagnosticRequest(userId, requestId);
        }

        @Test
        @DisplayName("2/3. Разморозка выполняется через DepositService.unfreezeForDiagnosticRequest " +
                "(единственный легитимный механизм создания UNBLOCK-транзакции и удаления FrozenDeposit " +
                "- см. DepositServiceTest/DepositServiceWithFrozenStorageTest, где эти инварианты уже " +
                "проверены для самого unfreezeForDiagnosticRequest; здесь проверяется, что rejectReport " +
                "действительно его вызывает и не мутирует депозит напрямую)")
        void rejectReport_DelegatesToUnfreezeMechanism_DoesNotMutateDepositDirectly() {
            DiagnosticRequest completed = completedRequest();
            when(requestRepository.findById(requestId)).thenReturn(Optional.of(completed));
            when(requestRepository.save(any(DiagnosticRequest.class))).thenAnswer(i -> i.getArgument(0));

            requestService.rejectReport(requestId, adminId, "Недостаточно фотографий");

            // DiagnosticRequestService must never touch Deposit/DepositRepository directly -
            // all financial mutation stays inside DepositService.
            verify(depositService, times(1)).unfreezeForDiagnosticRequest(anyLong(), anyLong());
            verifyNoMoreInteractions(depositService);
        }

        @Test
        @DisplayName("4. Повторное отклонение уже отклонённого отчёта — блокируется существующим " +
                "guard'ом жизненного цикла (статус уже не COMPLETED), депозит не размораживается повторно")
        void rejectReport_AlreadyRejected_BlockedByLifecycleGuard_NoDoubleUnfreeze() {
            DiagnosticRequest alreadyRejected = completedRequest();
            alreadyRejected.setStatus(DiagnosticRequest.DiagnosticRequestStatus.REJECTED);
            when(requestRepository.findById(requestId)).thenReturn(Optional.of(alreadyRejected));

            assertThatThrownBy(() -> requestService.rejectReport(requestId, adminId, "повтор"))
                    .isInstanceOf(IllegalStateException.class);

            verifyNoInteractions(depositService);
        }

        @Test
        @DisplayName("5. Сбой при размораживании — исключение пробрасывается наружу (откатывает всю " +
                "транзакцию rejectReport целиком), не оставляя отчёт REJECTED с фактически ещё " +
                "замороженным депозитом")
        void rejectReport_UnfreezeFailure_PropagatesAndDoesNotSilentlyMarkRejected() {
            DiagnosticRequest completed = completedRequest();
            when(requestRepository.findById(requestId)).thenReturn(Optional.of(completed));
            when(requestRepository.save(any(DiagnosticRequest.class))).thenAnswer(i -> i.getArgument(0));
            doThrow(new IllegalStateException("DB unavailable"))
                    .when(depositService).unfreezeForDiagnosticRequest(userId, requestId);

            assertThatThrownBy(() -> requestService.rejectReport(requestId, adminId, "причина"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("DB unavailable");
        }

        @Test
        @DisplayName("Пустая причина отклонения — IllegalArgumentException, депозит не трогается")
        void rejectReport_BlankReason_ThrowsBeforeAnyMutation() {
            assertThatThrownBy(() -> requestService.rejectReport(requestId, adminId, "  "))
                    .isInstanceOf(IllegalArgumentException.class);

            verifyNoInteractions(depositService);
        }

        @Test
        @DisplayName("Можно отклонить только отчёт в статусе COMPLETED")
        void rejectReport_WrongStatus_ThrowsBeforeUnfreeze() {
            DiagnosticRequest pending = completedRequest();
            pending.setStatus(DiagnosticRequest.DiagnosticRequestStatus.PENDING);
            when(requestRepository.findById(requestId)).thenReturn(Optional.of(pending));

            assertThatThrownBy(() -> requestService.rejectReport(requestId, adminId, "причина"))
                    .isInstanceOf(IllegalStateException.class);

            verifyNoInteractions(depositService);
        }
    }
}
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
    @DisplayName("Получение заявки с шаблоном")
    class GetRequestWithTemplateTests {

        @Test
        @DisplayName("Успешное получение заявки с шаблоном")
        void getRequestWithTemplate_Success() {
            savedRequest.setAssignedTo(diagnosticId);
            when(requestRepository.findById(requestId)).thenReturn(Optional.of(savedRequest));
            when(templateRepository.findActiveTemplates(any())).thenReturn(new PageImpl<>(List.of(template)));
            when(answerRepository.findByDiagnosticRequestId(requestId)).thenReturn(List.of());

            DiagnosticRequestResponse response = requestService.getRequestWithTemplate(requestId, diagnosticId);

            assertThat(response).isNotNull();
            assertThat(response.getTemplate()).isNotNull();
            assertThat(response.getTemplate().getName()).isEqualTo("Стандартный шаблон");
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
        @DisplayName("Нет активных шаблонов - исключение")
        void getRequestWithTemplate_NoActiveTemplates_ThrowsException() {
            savedRequest.setAssignedTo(diagnosticId);
            when(requestRepository.findById(requestId)).thenReturn(Optional.of(savedRequest));
            when(templateRepository.findActiveTemplates(any())).thenReturn(Page.empty());

            assertThatThrownBy(() -> requestService.getRequestWithTemplate(requestId, diagnosticId))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Нет активных шаблонов анкет");
        }
    }

    @Nested
    @DisplayName("Сохранение ответов на анкету")
    class SaveInspectionAnswersTests {

        @Test
        @DisplayName("Успешное сохранение черновика")
        void saveInspectionAnswers_Draft_Success() {
            savedRequest.setAssignedTo(diagnosticId);
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
}
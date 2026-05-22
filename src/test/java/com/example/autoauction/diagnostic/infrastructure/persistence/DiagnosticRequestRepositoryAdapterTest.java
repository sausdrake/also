package com.example.autoauction.diagnostic.infrastructure.persistence;

import com.example.autoauction.diagnostic.domain.DiagnosticRequest;
import com.example.autoauction.diagnostic.domain.port.DiagnosticRequestRepository;
import com.example.autoauction.user.infrastructure.persistence.JpaUserEntity;
import com.example.autoauction.user.infrastructure.persistence.SpringDataUserJpaRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ActiveProfiles("test")
@Import({DiagnosticRequestRepositoryAdapter.class, DiagnosticRequestMapper.class})
@DisplayName("Интеграционные тесты репозитория заявок")
class DiagnosticRequestRepositoryAdapterTest {

    @Autowired
    private DiagnosticRequestRepository requestRepository;

    @Autowired
    private SpringDataUserJpaRepository userJpaRepository;

    @Autowired
    private SpringDataDiagnosticRequestJpaRepository jpaRepository;

    private Long testUserId;
    private Long testRequestId;

    @BeforeEach
    void setUp() {
        JpaUserEntity user = new JpaUserEntity("testuser", "test@test.com", "password", true);
        user = userJpaRepository.save(user);
        testUserId = user.getId();

        JpaDiagnosticRequestEntity entity = new JpaDiagnosticRequestEntity();
        entity.setUserId(testUserId);
        entity.setBrand("BMW");
        entity.setModel("X5");
        entity.setYear(2022);
        entity.setVin("WBSDE91070CZ12345");
        entity.setDepositFrozen(new BigDecimal("10000"));
        entity.setStatus(DiagnosticRequest.DiagnosticRequestStatus.PENDING);
        entity.setCreatedAt(OffsetDateTime.now());
        entity.setUpdatedAt(OffsetDateTime.now());
        entity = jpaRepository.save(entity);
        testRequestId = entity.getId();
    }

    @Test
    @DisplayName("Сохранение заявки")
    void save_Success() {
        DiagnosticRequest request = new DiagnosticRequest();
        request.setUserId(testUserId);
        request.setBrand("Audi");
        request.setModel("Q7");
        request.setYear(2023);
        request.setVin("WAUZZZ4G9NN123456");
        request.setDepositFrozen(new BigDecimal("15000"));
        request.setStatus(DiagnosticRequest.DiagnosticRequestStatus.PENDING);
        request.setCreatedAt(OffsetDateTime.now());
        request.setUpdatedAt(OffsetDateTime.now());

        DiagnosticRequest saved = requestRepository.save(request);

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getBrand()).isEqualTo("Audi");
    }

    @Test
    @DisplayName("Поиск заявки по ID")
    void findById_Success() {
        var found = requestRepository.findById(testRequestId);

        assertThat(found).isPresent();
        assertThat(found.get().getVin()).isEqualTo("WBSDE91070CZ12345");
    }

    @Test
    @DisplayName("Назначение заявки диагносту")
    void assignToDiagnostic_Success() {
        Long diagnosticId = 999L;
        boolean assigned = requestRepository.assignToDiagnostic(testRequestId, diagnosticId);

        assertThat(assigned).isTrue();

        var updated = requestRepository.findById(testRequestId);
        assertThat(updated).isPresent();
        assertThat(updated.get().getAssignedTo()).isEqualTo(diagnosticId);
        assertThat(updated.get().getStatus()).isEqualTo(DiagnosticRequest.DiagnosticRequestStatus.IN_PROGRESS);
    }

    @Test
    @DisplayName("Повторное назначение той же заявки - false")
    void assignToDiagnostic_AlreadyAssigned_ReturnsFalse() {
        requestRepository.assignToDiagnostic(testRequestId, 111L);
        boolean secondAssign = requestRepository.assignToDiagnostic(testRequestId, 222L);

        assertThat(secondAssign).isFalse();
    }

    @Test
    @DisplayName("Отметка заявки как завершённой")
    void markAsCompleted_Success() {
        requestRepository.assignToDiagnostic(testRequestId, 888L);
        boolean completed = requestRepository.markAsCompleted(testRequestId, 888L);

        assertThat(completed).isTrue();

        var updated = requestRepository.findById(testRequestId);
        assertThat(updated).isPresent();
        assertThat(updated.get().getStatus()).isEqualTo(DiagnosticRequest.DiagnosticRequestStatus.COMPLETED);
    }

    @Test
    @DisplayName("Отметка чужой заявки - false")
    void markAsCompleted_WrongDiagnostic_ReturnsFalse() {
        requestRepository.assignToDiagnostic(testRequestId, 888L);
        boolean completed = requestRepository.markAsCompleted(testRequestId, 999L);

        assertThat(completed).isFalse();
    }
}
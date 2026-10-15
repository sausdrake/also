package com.example.autoauction.diagnostic.application;

import com.example.autoauction.admin.application.SystemSettingsService;
import com.example.autoauction.deposit.application.DepositService;
import com.example.autoauction.diagnostic.domain.DiagnosticRequest;
import com.example.autoauction.diagnostic.domain.DiagnosticRequest.DiagnosticRequestStatus;
import com.example.autoauction.diagnostic.domain.port.DiagnosticRequestRepository;
import com.example.autoauction.diagnostic.web.dto.CreateDiagnosticRequest;
import com.example.autoauction.diagnostic.web.dto.DiagnosticRequestResponse;
import com.example.autoauction.policy.application.PolicyVersionService;
import com.example.autoauction.user.domain.Role;
import com.example.autoauction.user.domain.User;
import com.example.autoauction.user.domain.port.RoleRepository;
import com.example.autoauction.user.domain.port.UserRepository;
import com.example.autoauction.vehicle.application.VehicleService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Scenario 1 step 2: creating a diagnostic request checks VIN uniqueness - a VIN already
 * registered as a vehicle, or already being diagnosed in another request, is refused before any
 * deposit freeze; a VIN whose only earlier request was rejected may be submitted again.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@DisplayName("Scenario 1: VIN uniqueness on diagnostic request creation")
class DiagnosticRequestVinUniquenessIntegrationTest {

    @Autowired private DiagnosticRequestService diagnosticRequestService;
    @Autowired private DiagnosticRequestRepository diagnosticRequestRepository;
    @Autowired private VehicleService vehicleService;
    @Autowired private DepositService depositService;
    // system_settings is a JdbcTemplate table absent from the H2 test schema - the two values
    // createRequest snapshots are supplied here instead.
    @MockBean private SystemSettingsService systemSettingsService;
    @Autowired private PolicyVersionService policyVersionService;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;

    @BeforeEach
    void configure() {
        policyVersionService.publish(policyVersionService.create(new BigDecimal("0.02"), 1L).getId(), 2L, 0L);
        when(systemSettingsService.getBigDecimal(eq("diagnostic_deposit_amount"), any())).thenReturn(new BigDecimal("10000"));
        when(systemSettingsService.getRequiredBigDecimal("diagnostic_fee_amount")).thenReturn(new BigDecimal("500"));
    }

    private Long newUser() {
        Role role = roleRepository.findByName("ROLE_USER")
                .orElseGet(() -> roleRepository.save(new Role(null, "ROLE_USER", "Обычный пользователь")));
        String name = "vin" + System.nanoTime();
        Long id = userRepository.save(new User(null, name, name + "@test.com", "encoded", true, Set.of(role))).getId();
        depositService.createDeposit(id, new BigDecimal("50000"));
        return id;
    }

    private static String vin() {
        String n = String.valueOf(System.nanoTime());
        return ("WBA" + n + "00000000000000").substring(0, 17);
    }

    private DiagnosticRequest request(Long userId, DiagnosticRequestStatus status, String vin) {
        DiagnosticRequest r = new DiagnosticRequest();
        r.setUserId(userId);
        r.setBrand("BMW");
        r.setModel("X5");
        r.setYear(2020);
        r.setVin(vin);
        r.setStatus(status);
        r.setDepositFrozen(new BigDecimal("10000"));
        r.setCreatedAt(OffsetDateTime.now());
        r.setUpdatedAt(OffsetDateTime.now());
        return diagnosticRequestRepository.save(r);
    }

    private static CreateDiagnosticRequest dto(String vin) {
        CreateDiagnosticRequest dto = new CreateDiagnosticRequest();
        dto.setBrand("Toyota");
        dto.setModel("Camry");
        dto.setYear(2021);
        dto.setVin(vin);
        return dto;
    }

    private long requestsOf(Long userId) {
        return diagnosticRequestRepository.findByUserId(userId, PageRequest.of(0, 50)).getTotalElements();
    }

    private BigDecimal available(Long userId) {
        return depositService.getDepositByUserId(userId).availableBalance();
    }

    @Test
    @DisplayName("a VIN already registered as a vehicle is refused before any freeze")
    void registeredVehicleVin_isRefused() {
        String vin = vin();
        DiagnosticRequest approved = request(newUser(), DiagnosticRequestStatus.APPROVED, vin);
        vehicleService.createFromDiagnosticRequest(approved.getId(), "BMW", "X5", 2020, vin, 1L, "diag", 2L, "admin");
        Long applicant = newUser();
        BigDecimal before = available(applicant);

        assertThatThrownBy(() -> diagnosticRequestService.createRequest(dto(vin), applicant))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("уже зарегистрирован");
        assertThat(requestsOf(applicant)).isZero();
        assertThat(available(applicant)).isEqualByComparingTo(before);
    }

    @ParameterizedTest(name = "another user''s {0} request for the VIN blocks a new one")
    @EnumSource(value = DiagnosticRequestStatus.class, names = {"PENDING", "IN_PROGRESS", "COMPLETED"})
    void vinUnderDiagnosis_isRefused(DiagnosticRequestStatus status) {
        String vin = vin();
        request(newUser(), status, vin);
        Long applicant = newUser();
        BigDecimal before = available(applicant);

        assertThatThrownBy(() -> diagnosticRequestService.createRequest(dto(vin), applicant))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("уже идёт диагностика");
        assertThat(requestsOf(applicant)).isZero();
        assertThat(available(applicant)).isEqualByComparingTo(before);
    }

    @Test
    @DisplayName("a VIN whose only earlier request was rejected can be submitted again")
    void rejectedEarlierRequest_doesNotBlock() {
        String vin = vin();
        request(newUser(), DiagnosticRequestStatus.REJECTED, vin);
        Long applicant = newUser();

        DiagnosticRequestResponse created = diagnosticRequestService.createRequest(dto(vin), applicant);

        assertThat(created.getStatus()).isEqualTo("PENDING");
        assertThat(requestsOf(applicant)).isEqualTo(1);
    }

    @Test
    @DisplayName("a fresh VIN is accepted")
    void freshVin_isAccepted() {
        Long applicant = newUser();

        DiagnosticRequestResponse created = diagnosticRequestService.createRequest(dto(vin()), applicant);

        assertThat(created.getStatus()).isEqualTo("PENDING");
    }
}

package com.example.autoauction.diagnostic.application;

import com.example.autoauction.diagnostic.domain.DiagnosticRequest;
import com.example.autoauction.diagnostic.domain.DiagnosticRequest.DiagnosticRequestStatus;
import com.example.autoauction.diagnostic.domain.port.DiagnosticRequestRepository;
import com.example.autoauction.diagnostic.web.dto.CreateDiagnosticRequest;
import com.example.autoauction.user.domain.Role;
import com.example.autoauction.user.domain.User;
import com.example.autoauction.user.domain.port.RoleRepository;
import com.example.autoauction.user.domain.port.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Scenario 1: a user may have only one active (PENDING / IN_PROGRESS) diagnostic request. The
 * check must look at ALL of the user's requests - earlier it inspected one unordered row, so a
 * user whose first-ever request was already finished could open a second active request (and
 * freeze a second deposit). Real H2 persistence.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@DisplayName("Scenario 1: one active diagnostic request per user, regardless of request history")
class DiagnosticRequestActiveLimitIntegrationTest {

    @Autowired private DiagnosticRequestService diagnosticRequestService;
    @Autowired private DiagnosticRequestRepository diagnosticRequestRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;

    private Long newUser() {
        Role role = roleRepository.findByName("ROLE_USER")
                .orElseGet(() -> roleRepository.save(new Role(null, "ROLE_USER", "Обычный пользователь")));
        String name = "act" + System.nanoTime();
        return userRepository.save(new User(null, name, name + "@test.com", "encoded", true, Set.of(role))).getId();
    }

    private void request(Long userId, DiagnosticRequestStatus status, String vin) {
        DiagnosticRequest r = new DiagnosticRequest();
        r.setUserId(userId);
        r.setBrand("BMW");
        r.setModel("X5");
        r.setYear(2020);
        r.setVin(vin);
        r.setStatus(status);
        r.setDepositFrozen(new java.math.BigDecimal("10000"));
        r.setCreatedAt(OffsetDateTime.now());
        r.setUpdatedAt(OffsetDateTime.now());
        diagnosticRequestRepository.save(r);
    }

    private static CreateDiagnosticRequest dto(String vin) {
        CreateDiagnosticRequest dto = new CreateDiagnosticRequest();
        dto.setBrand("Toyota");
        dto.setModel("Camry");
        dto.setYear(2021);
        dto.setVin(vin);
        return dto;
    }

    @ParameterizedTest(name = "older {0} request first, then an active one -> a new request is refused")
    @EnumSource(value = DiagnosticRequestStatus.class, names = {"APPROVED", "REJECTED", "COMPLETED"})
    void activeRequestIsFoundBehindOlderFinishedRequests(DiagnosticRequestStatus older) {
        Long userId = newUser();
        request(userId, older, "WBA00000000000001");
        request(userId, DiagnosticRequestStatus.PENDING, "WBA00000000000002");
        long before = diagnosticRequestRepository.findByUserId(userId, PageRequest.of(0, 50)).getTotalElements();

        assertThatThrownBy(() -> diagnosticRequestService.createRequest(dto("JTNB11HK103456789"), userId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("активная заявка");
        assertThat(diagnosticRequestRepository.findByUserId(userId, PageRequest.of(0, 50)).getTotalElements())
                .isEqualTo(before);
    }

    @ParameterizedTest(name = "an {0} request blocks a new one")
    @EnumSource(value = DiagnosticRequestStatus.class, names = {"PENDING", "IN_PROGRESS"})
    void eachActiveStatusBlocks(DiagnosticRequestStatus active) {
        Long userId = newUser();
        request(userId, DiagnosticRequestStatus.REJECTED, "WBA00000000000003");
        request(userId, DiagnosticRequestStatus.APPROVED, "WBA00000000000004");
        request(userId, active, "WBA00000000000005");

        assertThatThrownBy(() -> diagnosticRequestService.createRequest(dto("JTNB11HK103456780"), userId))
                .isInstanceOf(IllegalStateException.class);
    }
}

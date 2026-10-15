package com.example.autoauction.diagnostic.infrastructure.persistence;

import com.example.autoauction.diagnostic.domain.DiagnosticRequest;
import com.example.autoauction.diagnostic.domain.DiagnosticRequest.DiagnosticRequestStatus;
import com.example.autoauction.diagnostic.domain.RediagnosticReason;
import com.example.autoauction.diagnostic.domain.port.DiagnosticRequestRepository;
import com.example.autoauction.support.AbstractPostgresIntegrationTest;
import com.example.autoauction.user.domain.Role;
import com.example.autoauction.user.domain.User;
import com.example.autoauction.user.domain.port.RoleRepository;
import com.example.autoauction.user.domain.port.UserRepository;
import com.example.autoauction.vehicle.application.VehicleService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * MVP-005 (V54) on real PostgreSQL: a request is superseded by at most one re-diagnostic (the
 * UNIQUE constraint makes a concurrent double order impossible), the re-diagnostic columns are
 * all-or-nothing, and ordinary requests are unaffected.
 */
@DisplayName("MVP-005: diagnostic_requests re-diagnostic schema (PostgreSQL)")
// Skipped (not failed) where no Docker daemon is reachable, e.g. the network-less verification
// container; runs wherever Testcontainers can start PostgreSQL.
@org.testcontainers.junit.jupiter.Testcontainers(disabledWithoutDocker = true)
class DiagnosticRequestRediagnosticPgIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired private DiagnosticRequestRepository requestRepository;
    @Autowired private VehicleService vehicleService;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;

    private Long user() {
        Role role = roleRepository.findByName("ROLE_USER")
                .orElseGet(() -> roleRepository.save(new Role(null, "ROLE_USER", "Обычный пользователь")));
        String name = "rdpg" + System.nanoTime();
        return userRepository.save(new User(null, name, name + "@test.com", "encoded", true, Set.of(role))).getId();
    }

    private DiagnosticRequest request(Long userId, String vin) {
        DiagnosticRequest r = new DiagnosticRequest();
        r.setUserId(userId);
        r.setBrand("BMW");
        r.setModel("X5");
        r.setYear(2020);
        r.setVin(vin);
        r.setStatus(DiagnosticRequestStatus.APPROVED);
        r.setDepositFrozen(new BigDecimal("10000"));
        r.setCreatedAt(OffsetDateTime.now());
        r.setUpdatedAt(OffsetDateTime.now());
        return r;
    }

    private DiagnosticRequest rediagnostic(Long userId, String vin, Long previousId, Long vehicleId) {
        DiagnosticRequest r = request(userId, vin);
        r.setStatus(DiagnosticRequestStatus.PENDING);
        r.setPreviousDiagnosticRequestId(previousId);
        r.setVehicleId(vehicleId);
        r.setRediagnosticReason(RediagnosticReason.SELLER_FAULT);
        r.setRediagnosticComment("repeat");
        r.setRediagnosticOrderedBy(userId);
        r.setRediagnosticOrderedAt(OffsetDateTime.now());
        return r;
    }

    @Test
    @DisplayName("round trip, one re-diagnostic per superseded request, all-or-nothing columns")
    void schemaInvariants() {
        Long userId = user();
        String vin = ("WBA" + System.nanoTime() + "00000000000000").substring(0, 17);
        Long originalId = requestRepository.save(request(userId, vin)).getId();
        Long vehicleId = vehicleService.createFromDiagnosticRequest(originalId, "BMW", "X5", 2020, vin, userId, "d", userId, "a").id();

        DiagnosticRequest saved = requestRepository.save(rediagnostic(userId, vin, originalId, vehicleId));
        DiagnosticRequest reloaded = requestRepository.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getPreviousDiagnosticRequestId()).isEqualTo(originalId);
        assertThat(reloaded.getVehicleId()).isEqualTo(vehicleId);
        assertThat(reloaded.getRediagnosticReason()).isEqualTo(RediagnosticReason.SELLER_FAULT);
        assertThat(requestRepository.findById(originalId).orElseThrow().isRediagnostic()).isFalse();

        assertThatThrownBy(() -> requestRepository.save(rediagnostic(userId, vin, originalId, vehicleId)))
                .isInstanceOf(DataIntegrityViolationException.class);   // second re-diagnostic of the same request

        DiagnosticRequest noReason = rediagnostic(userId, vin, saved.getId(), vehicleId);
        noReason.setRediagnosticReason(null);
        assertThatThrownBy(() -> requestRepository.save(noReason))
                .isInstanceOf(DataIntegrityViolationException.class);   // link without a reason
    }
}

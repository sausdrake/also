// diagnostic/application/DiagnosticRejectUnfreezeIntegrationTest.java
package com.example.autoauction.diagnostic.application;

import com.example.autoauction.deposit.application.DepositService;
import com.example.autoauction.deposit.domain.DepositTransaction;
import com.example.autoauction.deposit.domain.port.DepositRepository;
import com.example.autoauction.diagnostic.domain.DiagnosticRequest;
import com.example.autoauction.diagnostic.domain.port.DiagnosticRequestRepository;
import com.example.autoauction.user.domain.Role;
import com.example.autoauction.user.domain.User;
import com.example.autoauction.user.domain.port.RoleRepository;
import com.example.autoauction.user.domain.port.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R-1.2: proves against a real (H2, test-profile) database that rejecting a completed
 * diagnostic report actually releases the applicant's frozen deposit - a Mockito unit
 * test can only prove the service *called* unfreezeForDiagnosticRequest, not that the
 * FrozenDeposit row is genuinely gone and the ledger/balance are correct afterward.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@DisplayName("R-1.2: интеграционный тест разморозки при отклонении диагностического отчёта")
class DiagnosticRejectUnfreezeIntegrationTest {

    @Autowired
    private DiagnosticRequestService diagnosticRequestService;

    @Autowired
    private DiagnosticRequestRepository diagnosticRequestRepository;

    @Autowired
    private DepositService depositService;

    @Autowired
    private DepositRepository depositRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RoleRepository roleRepository;

    @Test
    @DisplayName("create -> freeze -> reject: депозит разморожен, UNBLOCK-транзакция создана, " +
            "FrozenDeposit больше не существует, blockedAmount вернулся к исходному значению")
    void createFreezeReject_UnfreezesDepositAndRemovesFrozenRecord() {
        Role userRole = roleRepository.findByName("ROLE_USER")
                .orElseGet(() -> roleRepository.save(new Role(null, "ROLE_USER", "Обычный пользователь")));
        User applicant = new User(null, "r12applicant", "r12applicant@test.com", "encoded", true, Set.of(userRole));
        Long userId = userRepository.save(applicant).getId();

        depositService.createDeposit(userId, new BigDecimal("50000"));
        BigDecimal availableBeforeFreeze = depositService.getDepositByUserId(userId).availableBalance();

        BigDecimal depositAmount = new BigDecimal("10000");

        DiagnosticRequest request = new DiagnosticRequest();
        request.setUserId(userId);
        request.setBrand("BMW");
        request.setModel("X5");
        request.setYear(2022);
        request.setVin("WBSDE91070CZ" + System.nanoTime() % 100000);
        request.setDepositFrozen(depositAmount);
        request.setStatus(DiagnosticRequest.DiagnosticRequestStatus.PENDING);
        request.setCreatedAt(OffsetDateTime.now());
        request.setUpdatedAt(OffsetDateTime.now());
        DiagnosticRequest saved = diagnosticRequestRepository.save(request);
        Long requestId = saved.getId();

        depositService.freezeForDiagnosticRequest(userId, requestId, depositAmount);

        // Sanity check the freeze actually happened before testing the reject/unfreeze.
        assertThat(depositService.getDepositByUserId(userId).availableBalance())
                .isEqualByComparingTo(availableBeforeFreeze.subtract(depositAmount));

        // Move the request to COMPLETED (the only status rejectReport accepts) directly via
        // the repository, mirroring how the integration test suite elsewhere sets up
        // mid-lifecycle state without driving the full assignment/submission workflow.
        saved.setStatus(DiagnosticRequest.DiagnosticRequestStatus.COMPLETED);
        diagnosticRequestRepository.save(saved);

        diagnosticRequestService.rejectReport(requestId, 999L, "Недостаточно фотографий");

        // 1) Deposit unfrozen: available balance restored.
        assertThat(depositService.getDepositByUserId(userId).availableBalance())
                .as("blockedAmount must return to its pre-freeze value")
                .isEqualByComparingTo(availableBeforeFreeze);

        // 2) UNBLOCK transaction created.
        List<DepositTransaction> transactions = depositRepository.findTransactionsByUserId(userId);
        boolean hasUnblock = transactions.stream()
                .anyMatch(t -> t.getType() == DepositTransaction.TransactionType.UNBLOCK
                        && t.getAmount().compareTo(depositAmount) == 0);
        assertThat(hasUnblock).as("an UNBLOCK DepositTransaction must exist for the released amount").isTrue();

        // 3) No FrozenDeposit remains for this request.
        Long depositId = depositRepository.findByUserId(userId).orElseThrow().getId();
        assertThat(depositRepository.isFrozenForAuction(depositId, requestId))
                .as("no FrozenDeposit row should remain for the rejected request")
                .isFalse();

        // 4) Report status actually persisted as REJECTED.
        DiagnosticRequest reloaded = diagnosticRequestRepository.findById(requestId).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(DiagnosticRequest.DiagnosticRequestStatus.REJECTED);
    }
}

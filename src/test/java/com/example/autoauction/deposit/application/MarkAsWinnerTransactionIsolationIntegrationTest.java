// deposit/application/MarkAsWinnerTransactionIsolationIntegrationTest.java
package com.example.autoauction.deposit.application;

import com.example.autoauction.common.domain.FailedOperation;
import com.example.autoauction.common.domain.port.FailedOperationRepository;
import com.example.autoauction.user.domain.Role;
import com.example.autoauction.user.domain.User;
import com.example.autoauction.user.domain.port.RoleRepository;
import com.example.autoauction.user.domain.port.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Follow-up item 5: DepositService.markAsWinner catches, records via
 * FailedOperationService, and re-throws; its caller AuctionFinalizer.markWinnerSafely
 * swallows that exception without re-throwing further, sharing the outer transaction
 * under default REQUIRED propagation - the same shared-transaction-poisoning pattern
 * R-1.6 fixed in unfreezeForAuction. Proves the REQUIRES_NEW fix applied to markAsWinner
 * isolates its failure the same way, against a real database.
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("Follow-up #5: изоляция транзакции markAsWinner")
class MarkAsWinnerTransactionIsolationIntegrationTest {

    @Autowired
    private DepositService depositService;

    @Autowired
    private FailedOperationRepository failedOperationRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    @DisplayName("Сбой внутри markAsWinner (депозит не найден) не отменяет остальную работу " +
            "в той же внешней транзакции, и FailedOperation сохраняется независимо от исхода")
    void markAsWinnerFailure_DoesNotPoisonCallerTransaction_AndFailedOperationSurvives() {
        Role userRole = roleRepository.findByName("ROLE_USER")
                .orElseGet(() -> roleRepository.save(new Role(null, "ROLE_USER", "Обычный пользователь")));

        // Deliberately no deposit created for this user - markAsWinner's own
        // findByUserId(...).orElseThrow() will deterministically fail.
        Long userWithNoDeposit = userRepository.save(
                new User(null, "nodeposit" + System.nanoTime(), "nodeposit" + System.nanoTime() + "@test.com",
                        "encoded", true, Set.of(userRole))).getId();

        Long otherUserId = userRepository.save(
                new User(null, "other" + System.nanoTime(), "other" + System.nanoTime() + "@test.com",
                        "encoded", true, Set.of(userRole))).getId();

        Long fakeAuctionId = 999_002L;

        TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);
        txTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);

        // Simulate AuctionFinalizer.markWinnerSafely's own catch-and-swallow behavior here,
        // exactly like AuctionFinalizer does, while performing unrelated outer-transaction
        // work (creating a second deposit) that must still commit regardless.
        boolean committedCleanly = Boolean.TRUE.equals(txTemplate.execute(status -> {
            try {
                depositService.markAsWinner(userWithNoDeposit, fakeAuctionId);
            } catch (Exception e) {
                // mirrors AuctionFinalizer.markWinnerSafely exactly
            }
            depositService.createDeposit(otherUserId, java.math.BigDecimal.ZERO);
            return !status.isRollbackOnly();
        }));

        assertThat(committedCleanly)
                .as("outer transaction must not be silently marked rollback-only by the isolated markAsWinner failure")
                .isTrue();
        assertThat(depositService.getDepositByUserId(otherUserId))
                .as("unrelated work in the same outer transaction must have actually committed")
                .isNotNull();

        List<FailedOperation> failedOps = failedOperationRepository.findByStatus("PENDING");
        boolean recorded = failedOps.stream().anyMatch(op ->
                "MARK_WINNER".equals(op.getOperationType())
                        && userWithNoDeposit.equals(op.getUserId())
                        && fakeAuctionId.equals(op.getAuctionId()));
        assertThat(recorded)
                .as("FailedOperationService must durably record the markAsWinner failure even though " +
                        "it ran inside markAsWinner's own (REQUIRES_NEW) transaction")
                .isTrue();
    }
}

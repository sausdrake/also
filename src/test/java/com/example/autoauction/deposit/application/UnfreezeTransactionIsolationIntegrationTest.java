// deposit/application/UnfreezeTransactionIsolationIntegrationTest.java
package com.example.autoauction.deposit.application;

import com.example.autoauction.common.domain.FailedOperation;
import com.example.autoauction.common.domain.port.FailedOperationRepository;
import com.example.autoauction.deposit.domain.Deposit;
import com.example.autoauction.deposit.domain.port.DepositRepository;
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

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R-1.6: proves DepositService.unfreezeForAuction's REQUIRES_NEW propagation actually
 * isolates a failure from the caller's transaction, using a real (H2, test-profile)
 * database and real Spring transaction management rather than mocks - a Mockito mock
 * cannot exercise transaction propagation/commit semantics at all, since @Transactional
 * is interpreted by the Spring AOP proxy around the real bean, not by direct method calls
 * on a mock.
 *
 * Not run against the authoritative DB - this uses the ordinary test-profile datasource
 * (H2 in-memory), exactly like the other @SpringBootTest integration tests in this suite.
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("R-1.6: изоляция транзакции unfreezeForAuction")
class UnfreezeTransactionIsolationIntegrationTest {

    @Autowired
    private DepositService depositService;

    @Autowired
    private DepositRepository depositRepository;

    @Autowired
    private FailedOperationRepository failedOperationRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    @DisplayName("Сбой внутри unfreezeForAuction (недостаточно blockedAmount) не отменяет " +
            "остальную работу, выполненную в той же внешней транзакции, и FailedOperation " +
            "сохраняется независимо от исхода внешней транзакции")
    void unfreezeFailure_DoesNotPoisonCallerTransaction_AndFailedOperationSurvives() {
        Role userRole = roleRepository.findByName("ROLE_USER")
                .orElseGet(() -> roleRepository.save(new Role(null, "ROLE_USER", "Обычный пользователь")));
        User user = new User(null, "r16user", "r16user@test.com", "encoded", true, Set.of(userRole));
        Long userId = userRepository.save(user).getId();

        TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);
        txTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);

        Long auctionId = 999_001L;
        BigDecimal frozenAmount = new BigDecimal("10000.00");

        // Set up: create deposit, freeze against a fake auctionId, all inside one
        // transaction (mirrors how BidService/AuctionFinalizer normally operate).
        txTemplate.execute(status -> {
            depositService.createDeposit(userId, new BigDecimal("50000"));
            depositService.freezeForAuction(userId, auctionId, frozenAmount);
            return null;
        });

        // Corrupt state out-of-band so unfreezeForAuction's own domain guard
        // (blockedAmount < frozenAmount) deterministically throws IllegalStateException -
        // a reproducible way to exercise the catch-and-record path without needing real
        // thread concurrency to trigger an optimistic-lock failure.
        txTemplate.execute(status -> {
            Deposit deposit = depositRepository.findByUserId(userId).orElseThrow();
            deposit.setBlockedAmount(new BigDecimal("1.00")); // less than frozenAmount
            depositRepository.save(deposit);
            return null;
        });

        // Simulate the caller's transaction: unfreezeForAuction fails and swallows
        // internally, but ALSO does other unrelated work in the SAME outer transaction
        // (here: creating a second, independent deposit) that must still commit.
        Long otherUserId = userRepository.save(
                new User(null, "r16other", "r16other@test.com", "encoded", true, Set.of(userRole))
        ).getId();

        boolean committedCleanly = Boolean.TRUE.equals(txTemplate.execute(status -> {
            depositService.unfreezeForAuction(userId, auctionId); // fails internally, swallowed
            depositService.createDeposit(otherUserId, BigDecimal.ZERO); // unrelated outer-tx work
            return !status.isRollbackOnly();
        }));

        assertThat(committedCleanly)
                .as("outer transaction must not be silently marked rollback-only by the isolated unfreeze failure")
                .isTrue();
        assertThat(depositRepository.findByUserId(otherUserId))
                .as("unrelated work in the same outer transaction must have actually committed")
                .isPresent();

        List<FailedOperation> failedOps = failedOperationRepository.findByStatus("PENDING");
        boolean recorded = failedOps.stream().anyMatch(op ->
                "UNFREEZE_AUCTION".equals(op.getOperationType())
                        && userId.equals(op.getUserId())
                        && auctionId.equals(op.getAuctionId()));
        assertThat(recorded)
                .as("FailedOperationService must durably record the unfreeze failure even though " +
                        "it ran inside unfreezeForAuction's own (REQUIRES_NEW) transaction")
                .isTrue();
    }
}

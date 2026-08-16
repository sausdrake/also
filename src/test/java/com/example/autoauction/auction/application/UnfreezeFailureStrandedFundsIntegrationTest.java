// auction/application/UnfreezeFailureStrandedFundsIntegrationTest.java
package com.example.autoauction.auction.application;

import com.example.autoauction.auction.domain.Auction;
import com.example.autoauction.auction.domain.AuctionStatus;
import com.example.autoauction.auction.domain.port.AuctionParticipantRepository;
import com.example.autoauction.auction.domain.port.AuctionRepository;
import com.example.autoauction.common.domain.FailedOperation;
import com.example.autoauction.common.domain.port.FailedOperationRepository;
import com.example.autoauction.deposit.application.DepositService;
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
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Follow-up item 4 (the most important one): proves, against a real database, whether
 * DepositService.unfreezeForAuction's REQUIRES_NEW propagation can leave an auction
 * COMPLETED/CANCELLED while one participant's deposit remains frozen.
 *
 * Answer proven below: YES, it can. REQUIRES_NEW isolates the failure so it does not
 * poison the caller's transaction (the R-1.6 fix correctly closed THAT hole), but the
 * outer AuctionFinalizer transaction has no knowledge that a participant's unfreeze
 * failed and proceeds to commit the auction's own status change regardless. This is
 * "Model B" (auction may complete while unfreeze is temporarily failed) - but Model B is
 * only safe if a durable recovery mechanism guarantees eventual unfreeze, and (see
 * FailedOperationService inspection) no such mechanism exists.
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("Follow-up #4: REQUIRES_NEW и застрявшие средства при отмене аукциона")
class UnfreezeFailureStrandedFundsIntegrationTest {

    @Autowired
    private AuctionFinalizer auctionFinalizer;

    @Autowired
    private AuctionRepository auctionRepository;

    @Autowired
    private AuctionParticipantRepository participantRepository;

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
    @DisplayName("Отмена аукциона с 2 участниками, у одного unfreeze детерминированно проваливается: " +
            "аукцион всё равно становится CANCELLED, а его депозит остаётся замороженным")
    void cancelAuction_OneParticipantUnfreezeFails_AuctionStillCancelled_DepositStaysFrozen() {
        TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);
        txTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);

        Role userRole = roleRepository.findByName("ROLE_USER")
                .orElseGet(() -> roleRepository.save(new Role(null, "ROLE_USER", "Обычный пользователь")));
        Long sellerId = userRepository.save(
                new User(null, "sfseller" + System.nanoTime(), "sfseller" + System.nanoTime() + "@test.com",
                        "encoded", true, Set.of(userRole))).getId();
        Long healthyUserId = userRepository.save(
                new User(null, "sfhealthy" + System.nanoTime(), "sfhealthy" + System.nanoTime() + "@test.com",
                        "encoded", true, Set.of(userRole))).getId();
        Long brokenUserId = userRepository.save(
                new User(null, "sfbroken" + System.nanoTime(), "sfbroken" + System.nanoTime() + "@test.com",
                        "encoded", true, Set.of(userRole))).getId();

        BigDecimal requiredDeposit = new BigDecimal("10000.00");
        OffsetDateTime now = OffsetDateTime.now();

        Long auctionId = txTemplate.execute(status -> {
            Auction auction = new Auction(
                    1L, new BigDecimal("1000000"), null, null, new BigDecimal("10000"),
                    now.minusHours(1), now.plusHours(1), sellerId
            );
            auction.setRequiredDepositAmount(requiredDeposit);
            auction.start();
            Auction saved = auctionRepository.save(auction);

            depositService.createDeposit(healthyUserId, new BigDecimal("50000"));
            depositService.createDeposit(brokenUserId, new BigDecimal("50000"));
            depositService.freezeForAuction(healthyUserId, saved.getId(), requiredDeposit);
            depositService.freezeForAuction(brokenUserId, saved.getId(), requiredDeposit);
            participantRepository.registerParticipant(saved.getId(), healthyUserId, requiredDeposit);
            participantRepository.registerParticipant(saved.getId(), brokenUserId, requiredDeposit);
            return saved.getId();
        });

        // Deterministically force brokenUserId's future unfreeze to fail: corrupt their
        // deposit's blockedAmount below the frozen amount so Deposit.unfreezeForAuction's
        // own guard throws IllegalStateException. healthyUserId is left untouched.
        txTemplate.execute(status -> {
            Deposit brokenDeposit = depositRepository.findByUserId(brokenUserId).orElseThrow();
            brokenDeposit.setBlockedAmount(new BigDecimal("1.00"));
            depositRepository.save(brokenDeposit);
            return null;
        });

        // This mirrors exactly what AdminAuctionService.cancelAuction() does: load with
        // lock, then delegate to AuctionFinalizer.cancelAuction() in one transaction.
        Boolean cancelResult = txTemplate.execute(status -> {
            Auction auction = auctionRepository.findByIdWithLock(auctionId).orElseThrow();
            return auctionFinalizer.cancelAuction(auction);
        });

        assertThat(cancelResult).as("cancelAuction() itself must not throw/fail overall").isTrue();

        Auction finalAuction = auctionRepository.findById(auctionId).orElseThrow();
        assertThat(finalAuction.getStatus())
                .as("PROVEN: the auction reaches a terminal status even though one participant's unfreeze failed")
                .isEqualTo(AuctionStatus.CANCELLED);

        Deposit healthyDeposit = depositRepository.findByUserId(healthyUserId).orElseThrow();
        assertThat(depositRepository.isFrozenForAuction(healthyDeposit.getId(), auctionId))
                .as("the OTHER participant's deposit is correctly unfrozen - REQUIRES_NEW isolation " +
                        "genuinely protects unrelated participants from one failure")
                .isFalse();

        Deposit brokenDeposit = depositRepository.findByUserId(brokenUserId).orElseThrow();
        assertThat(depositRepository.isFrozenForAuction(brokenDeposit.getId(), auctionId))
                .as("PROVEN: the broken participant's FrozenDeposit row STILL EXISTS - money is " +
                        "genuinely stranded, not eventually recovered")
                .isTrue();
        assertThat(brokenDeposit.getBlockedAmount())
                .as("the broken participant's blockedAmount remains at its corrupted/frozen value - " +
                        "never released")
                .isEqualByComparingTo(new BigDecimal("1.00"));

        List<FailedOperation> pending = failedOperationRepository.findByStatus("PENDING");
        boolean recorded = pending.stream().anyMatch(op ->
                "UNFREEZE_AUCTION".equals(op.getOperationType())
                        && brokenUserId.equals(op.getUserId())
                        && auctionId.equals(op.getAuctionId()));
        assertThat(recorded)
                .as("a FailedOperation record exists (durably, thanks to R-1.6's REQUIRES_NEW on " +
                        "FailedOperationService) - but nothing in this codebase ever reads status=PENDING " +
                        "records to retry them (verified separately: no @Scheduled consumer, no admin " +
                        "endpoint) - so this record is written but permanently unactioned")
                .isTrue();
    }
}

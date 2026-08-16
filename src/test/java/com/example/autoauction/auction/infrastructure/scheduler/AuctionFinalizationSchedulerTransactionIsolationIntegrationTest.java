// auction/infrastructure/scheduler/AuctionFinalizationSchedulerTransactionIsolationIntegrationTest.java
package com.example.autoauction.auction.infrastructure.scheduler;

import com.example.autoauction.auction.application.AuctionFinalizer;
import com.example.autoauction.auction.domain.Auction;
import com.example.autoauction.auction.domain.AuctionStatus;
import com.example.autoauction.auction.domain.port.AuctionParticipantRepository;
import com.example.autoauction.auction.domain.port.AuctionRepository;
import com.example.autoauction.common.domain.port.FailedOperationRepository;
import com.example.autoauction.deposit.application.DepositService;
import com.example.autoauction.deposit.domain.Deposit;
import com.example.autoauction.deposit.domain.DepositTransaction;
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
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R-3 REMEDIATION, Phase 4: same cross-auction isolation proof as
 * AuctionCompletionSchedulerTransactionIsolationIntegrationTest, but for
 * AuctionCompletionScheduler.completeExpiredAuctions() / AuctionFinalizer.finalizeAuction(),
 * which the R-3 audit flagged as sharing the identical (now fixed) architecture. This test
 * additionally proves a related, but distinct, per-auction (not cross-auction) property:
 * finalizeAuction() marks the winner and unfreezes the loser via REQUIRES_NEW BEFORE its own
 * final auction-status save() - so if THAT LAST save() fails (e.g. its own optimistic-lock
 * conflict), the winner/loser deposit side effects have already committed even though the
 * auction's own status update has not. This was already true before the R-3 fix (it is not a
 * cross-auction leak); what matters for the Phase 5 FailedOperation decision is whether the
 * scheduler's natural retry converges safely - proven below by re-invoking finalizeAuction()
 * on the same (fresh) auction a second time and confirming no double winner-mark, no double
 * unfreeze, and eventual correct COMPLETED status.
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("R-3 remediation: изоляция транзакций completeExpiredAuctions()/finalizeAuction()")
class AuctionFinalizationSchedulerTransactionIsolationIntegrationTest {

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

    private Long sellerId;
    private TransactionTemplate txTemplate;

    private Long createExpiredAuctionWithWinnerAndLoser(Long winnerId, Long loserId, BigDecimal requiredDeposit) {
        return txTemplate.execute(status -> {
            OffsetDateTime now = OffsetDateTime.now();
            Auction auction = new Auction(
                    1L, new BigDecimal("1000000"), null, null, new BigDecimal("10000"),
                    now.minusHours(3), now.minusMinutes(5), sellerId
            );
            auction.setRequiredDepositAmount(requiredDeposit);
            auction.setStatus(AuctionStatus.ACTIVE);
            auction.setCurrentPrice(new BigDecimal("1050000"));
            auction.setTotalBids(3);
            auction.setWinnerId(winnerId);
            auction.setWinningBid(new BigDecimal("1050000"));
            Auction saved = auctionRepository.save(auction);

            depositService.freezeForAuction(winnerId, saved.getId(), requiredDeposit);
            depositService.freezeForAuction(loserId, saved.getId(), requiredDeposit);
            participantRepository.registerParticipant(saved.getId(), winnerId, requiredDeposit);
            participantRepository.registerParticipant(saved.getId(), loserId, requiredDeposit);
            return saved.getId();
        });
    }

    @Test
    @DisplayName("A и C финализируются корректно несмотря на version-конфликт у B; " +
            "у B денежные операции идемпотентно сходятся при повторной попытке")
    void oneAuctionVersionConflict_DoesNotAffectSiblings_AndSelfHealsOnRetry() {
        txTemplate = new TransactionTemplate(transactionManager);
        txTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);

        Role userRole = roleRepository.findByName("ROLE_USER")
                .orElseGet(() -> roleRepository.save(new Role(null, "ROLE_USER", "Обычный пользователь")));
        sellerId = userRepository.save(
                new User(null, "r3fzseller" + System.nanoTime(), "r3fzseller" + System.nanoTime() + "@test.com",
                        "encoded", true, Set.of(userRole))).getId();

        Long winnerA = userRepository.save(new User(null, "r3fzwA" + System.nanoTime(),
                "r3fzwA" + System.nanoTime() + "@test.com", "encoded", true, Set.of(userRole))).getId();
        Long loserA = userRepository.save(new User(null, "r3fzlA" + System.nanoTime(),
                "r3fzlA" + System.nanoTime() + "@test.com", "encoded", true, Set.of(userRole))).getId();
        Long winnerB = userRepository.save(new User(null, "r3fzwB" + System.nanoTime(),
                "r3fzwB" + System.nanoTime() + "@test.com", "encoded", true, Set.of(userRole))).getId();
        Long loserB = userRepository.save(new User(null, "r3fzlB" + System.nanoTime(),
                "r3fzlB" + System.nanoTime() + "@test.com", "encoded", true, Set.of(userRole))).getId();
        Long winnerC = userRepository.save(new User(null, "r3fzwC" + System.nanoTime(),
                "r3fzwC" + System.nanoTime() + "@test.com", "encoded", true, Set.of(userRole))).getId();
        Long loserC = userRepository.save(new User(null, "r3fzlC" + System.nanoTime(),
                "r3fzlC" + System.nanoTime() + "@test.com", "encoded", true, Set.of(userRole))).getId();

        BigDecimal requiredDeposit = new BigDecimal("5000.00");
        txTemplate.execute(status -> {
            for (Long u : new Long[]{winnerA, loserA, winnerB, loserB, winnerC, loserC}) {
                depositService.createDeposit(u, new BigDecimal("20000"));
            }
            return null;
        });

        Long auctionAId = createExpiredAuctionWithWinnerAndLoser(winnerA, loserA, requiredDeposit);
        Long auctionBId = createExpiredAuctionWithWinnerAndLoser(winnerB, loserB, requiredDeposit);
        Long auctionCId = createExpiredAuctionWithWinnerAndLoser(winnerC, loserC, requiredDeposit);

        // Stale in-memory copy of B, then an independent, already-committed concurrent write
        // bumping B's @Version - same technique as the cancellation test.
        Auction staleB = txTemplate.execute(status -> auctionRepository.findById(auctionBId).orElseThrow());
        txTemplate.execute(status -> {
            Auction fresh = auctionRepository.findById(auctionBId).orElseThrow();
            fresh.setConfirmedStart(true);
            return auctionRepository.save(fresh);
        });

        // Fixed architecture: no ambient transaction wraps this loop, mirroring
        // completeExpiredAuctions() after the R-3 fix.
        try {
            auctionFinalizer.finalizeAuction(auctionRepository.findById(auctionAId).orElseThrow());
        } catch (Exception ignored) { }
        try {
            auctionFinalizer.finalizeAuction(staleB); // stale version -> genuine conflict on final save()
        } catch (Exception ignored) { }
        try {
            auctionFinalizer.finalizeAuction(auctionRepository.findById(auctionCId).orElseThrow());
        } catch (Exception ignored) { }

        // --- A: fully, correctly completed ---
        assertCompletedCorrectly(auctionAId, winnerA, loserA, requiredDeposit);

        // --- C: fully, correctly completed, unaffected by B ---
        assertCompletedCorrectly(auctionCId, winnerC, loserC, requiredDeposit);

        // --- B: its own status save failed, but verify exactly what state it's left in ---
        Auction bAfterFirstAttempt = auctionRepository.findById(auctionBId).orElseThrow();
        Deposit winnerBDeposit = depositRepository.findByUserId(winnerB).orElseThrow();
        Deposit loserBDeposit = depositRepository.findByUserId(loserB).orElseThrow();
        boolean winnerBAlreadyMarked = !depositRepository.needsWinnerMark(winnerBDeposit.getId(), auctionBId)
                && depositRepository.isFrozenForAuction(winnerBDeposit.getId(), auctionBId);
        boolean loserBAlreadyUnfrozen = !depositRepository.isFrozenForAuction(loserBDeposit.getId(), auctionBId);

        System.out.println("R-3 PHASE4 EVIDENCE: B status after 1st attempt=" + bAfterFirstAttempt.getStatus() +
                " winnerBAlreadyMarked=" + winnerBAlreadyMarked +
                " loserBAlreadyUnfrozen=" + loserBAlreadyUnfrozen);

        assertThat(bAfterFirstAttempt.getStatus())
                .as("B's own status save must not have applied (lost the version race)")
                .isNotEqualTo(AuctionStatus.COMPLETED);
        boolean anyFailedOpForB = failedOperationRepository.findByStatus("PENDING").stream()
                .anyMatch(op -> auctionBId.equals(op.getAuctionId()));
        assertThat(anyFailedOpForB)
                .as("B's failure is not a deposit-layer failure and correctly creates no FailedOperation")
                .isFalse();

        // --- Retry B a second time, exactly as the next scheduler tick would (fresh read,
        // status still ACTIVE and endTime still in the past, so it's naturally re-selected) ---
        Auction bRetry = auctionRepository.findById(auctionBId).orElseThrow();
        boolean retrySucceeded = auctionFinalizer.finalizeAuction(bRetry);
        assertThat(retrySucceeded).as("retry must succeed once no concurrent writer is racing it").isTrue();

        // Convergence: B ends up in exactly the same correct final state as A/C, with no
        // double winner-mark and no double unfreeze/duplicate DepositTransaction.
        assertCompletedCorrectly(auctionBId, winnerB, loserB, requiredDeposit);
    }

    private void assertCompletedCorrectly(Long auctionId, Long winnerId, Long loserId, BigDecimal requiredDeposit) {
        Auction auction = auctionRepository.findById(auctionId).orElseThrow();
        assertThat(auction.getStatus()).as("auction " + auctionId + " must be COMPLETED")
                .isEqualTo(AuctionStatus.COMPLETED);
        assertThat(auction.getWinnerId()).isEqualTo(winnerId);

        Deposit winnerDeposit = depositRepository.findByUserId(winnerId).orElseThrow();
        assertThat(depositRepository.isFrozenForAuction(winnerDeposit.getId(), auctionId))
                .as("winner's FrozenDeposit must remain (marked as winner, not unfrozen)").isTrue();
        assertThat(depositRepository.needsWinnerMark(winnerDeposit.getId(), auctionId))
                .as("winner must be marked exactly once, no longer needing a mark").isFalse();

        Deposit loserDeposit = depositRepository.findByUserId(loserId).orElseThrow();
        assertThat(depositRepository.isFrozenForAuction(loserDeposit.getId(), auctionId))
                .as("loser's FrozenDeposit must be absent (unfrozen)").isFalse();
        assertThat(loserDeposit.getBlockedAmount())
                .as("loser's blockedAmount must be back to zero").isEqualByComparingTo(BigDecimal.ZERO);

        long loserUnblockCount = depositRepository.findTransactionsByAuctionId(auctionId).stream()
                .filter(t -> t.getType() == DepositTransaction.TransactionType.UNBLOCK
                        && t.getUserId().equals(loserId))
                .count();
        assertThat(loserUnblockCount)
                .as("loser must have EXACTLY ONE UNBLOCK transaction - no duplicate from a retry").isEqualTo(1);

        assertThat(participantRepository.getParticipants(auctionId))
                .as("participant list must be cleared").isEmpty();
    }
}

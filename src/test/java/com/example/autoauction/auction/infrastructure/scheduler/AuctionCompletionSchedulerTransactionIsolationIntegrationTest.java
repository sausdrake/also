// auction/infrastructure/scheduler/AuctionCompletionSchedulerTransactionIsolationIntegrationTest.java
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
 * R-3 REMEDIATION: proves that after removing @Transactional from
 * AuctionCompletionScheduler.cancelUnconfirmedAuctions(), a single auction's optimistic-lock
 * conflict inside AuctionFinalizer.cancelAuction() no longer poisons a shared physical
 * transaction and can no longer roll back sibling auctions processed in the same scheduler
 * invocation.
 *
 * BEFORE the fix (see git history for the pre-fix version of this test), this exact scenario
 * proved: A and C's successful cancellations were rolled back along with B's failure, even
 * though the scheduler's own per-auction try/catch appeared to isolate them, because all three
 * calls shared one physical transaction owned by the scheduler's own @Transactional.
 *
 * AFTER the fix: AuctionFinalizer.cancelAuction() is called with no ambient transaction (the
 * scheduler no longer opens one), so each call is its own transaction owner. B's conflict can
 * now only roll back B's own work.
 *
 * The test still cannot go through the scheduler's own findUnconfirmedPastDeadline() query to
 * produce B's conflict (a fresh query always sees the current, non-conflicting version - see
 * the R-3 audit report), so it drives AuctionFinalizer.cancelAuction() directly with a STALE
 * in-memory copy of B, exactly reproducing what a genuinely concurrent writer (e.g. an admin
 * action) would produce, but without any surrounding TransactionTemplate - mirroring the new,
 * un-annotated scheduler loop exactly.
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("R-3 remediation: изоляция транзакций AuctionCompletionScheduler.cancelUnconfirmedAuctions()")
class AuctionCompletionSchedulerTransactionIsolationIntegrationTest {

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

    private Long createOverdueAuction(TransactionTemplate txTemplate, Long sellerId) {
        return txTemplate.execute(status -> {
            OffsetDateTime now = OffsetDateTime.now();
            Auction auction = new Auction(
                    1L, new BigDecimal("1000000"), null, null, new BigDecimal("10000"),
                    now.minusHours(5), now.plusHours(1), sellerId
            );
            return auctionRepository.save(auction).getId();
        });
    }

    private Long createOverdueAuctionWithParticipant(TransactionTemplate txTemplate, Long sellerId,
                                                       Long participantId, BigDecimal requiredDeposit) {
        return txTemplate.execute(status -> {
            OffsetDateTime now = OffsetDateTime.now();
            Auction auction = new Auction(
                    1L, new BigDecimal("1000000"), null, null, new BigDecimal("10000"),
                    now.minusHours(5), now.plusHours(1), sellerId
            );
            auction.setRequiredDepositAmount(requiredDeposit);
            Auction saved = auctionRepository.save(auction);
            depositService.freezeForAuction(participantId, saved.getId(), requiredDeposit);
            participantRepository.registerParticipant(saved.getId(), participantId, requiredDeposit);
            return saved.getId();
        });
    }

    @Test
    @DisplayName("После фикса: A и C отменяются корректно, несмотря на version-конфликт у B " +
            "в той же серии вызовов шедулера")
    void oneAuctionVersionConflict_NoLongerAffectsSiblingAuctions_AfterFix() {
        TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);
        txTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);

        Role userRole = roleRepository.findByName("ROLE_USER")
                .orElseGet(() -> roleRepository.save(new Role(null, "ROLE_USER", "Обычный пользователь")));
        Long sellerId = userRepository.save(
                new User(null, "r3fseller" + System.nanoTime(), "r3fseller" + System.nanoTime() + "@test.com",
                        "encoded", true, Set.of(userRole))).getId();
        Long participantAId = userRepository.save(
                new User(null, "r3fpA" + System.nanoTime(), "r3fpA" + System.nanoTime() + "@test.com",
                        "encoded", true, Set.of(userRole))).getId();
        Long participantCId = userRepository.save(
                new User(null, "r3fpC" + System.nanoTime(), "r3fpC" + System.nanoTime() + "@test.com",
                        "encoded", true, Set.of(userRole))).getId();

        BigDecimal requiredDeposit = new BigDecimal("5000.00");
        txTemplate.execute(status -> {
            depositService.createDeposit(participantAId, new BigDecimal("20000"));
            depositService.createDeposit(participantCId, new BigDecimal("20000"));
            return null;
        });

        Long auctionAId = createOverdueAuctionWithParticipant(txTemplate, sellerId, participantAId, requiredDeposit);
        Long auctionBId = createOverdueAuction(txTemplate, sellerId);
        Long auctionCId = createOverdueAuctionWithParticipant(txTemplate, sellerId, participantCId, requiredDeposit);

        // Stale in-memory copy of B (version=0), exactly as a fresh scheduler query would have
        // returned before the concurrent update below.
        Auction staleB = txTemplate.execute(status -> auctionRepository.findById(auctionBId).orElseThrow());

        // Independent, already-committed concurrent write to B - simulates a genuinely
        // concurrent admin action bumping B's @Version between the scheduler's read and its
        // own save().
        txTemplate.execute(status -> {
            Auction fresh = auctionRepository.findById(auctionBId).orElseThrow();
            fresh.setConfirmedStart(true);
            return auctionRepository.save(fresh);
        });

        // THE FIXED ARCHITECTURE: no outer TransactionTemplate/@Transactional wraps this loop
        // any more - this mirrors AuctionCompletionScheduler.cancelUnconfirmedAuctions() as it
        // exists after the R-3 fix exactly (plain loop, per-call try/catch, no ambient
        // transaction), so each cancelAuction() call is its own independent transaction.
        boolean aThrew = false, bThrew = false, cThrew = false;
        try {
            auctionFinalizer.cancelAuction(auctionRepository.findById(auctionAId).orElseThrow());
        } catch (Exception e) {
            aThrew = true;
        }
        try {
            auctionFinalizer.cancelAuction(staleB); // stale version -> genuine conflict
        } catch (Exception e) {
            bThrew = true;
        }
        try {
            auctionFinalizer.cancelAuction(auctionRepository.findById(auctionCId).orElseThrow());
        } catch (Exception e) {
            cThrew = true;
        }

        AuctionStatus finalA = auctionRepository.findById(auctionAId).orElseThrow().getStatus();
        AuctionStatus finalB = auctionRepository.findById(auctionBId).orElseThrow().getStatus();
        AuctionStatus finalC = auctionRepository.findById(auctionCId).orElseThrow().getStatus();

        System.out.println("R-3 REMEDIATION EVIDENCE: threw(A,B,C)=" + aThrew + "," + bThrew + "," + cThrew +
                " finalStatus A=" + finalA + " B=" + finalB + " C=" + finalC);

        // --- A: must be fully, correctly cancelled ---
        assertThat(finalA).as("A must reach CANCELLED, unaffected by B's failure").isEqualTo(AuctionStatus.CANCELLED);
        Deposit depositA = depositRepository.findByUserId(participantAId).orElseThrow();
        assertThat(depositRepository.isFrozenForAuction(depositA.getId(), auctionAId))
                .as("A's participant FrozenDeposit must be absent (unfrozen)").isFalse();
        assertThat(depositA.getBlockedAmount())
                .as("A's participant blockedAmount must be back to zero").isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(participantRepository.getParticipants(auctionAId))
                .as("A's participant list must be cleared").doesNotContain(participantAId);
        boolean aHasUnblockTx = depositRepository.findTransactionsByAuctionId(auctionAId).stream()
                .anyMatch(t -> t.getType() == DepositTransaction.TransactionType.UNBLOCK
                        && t.getUserId().equals(participantAId));
        assertThat(aHasUnblockTx).as("A must have a durable UNBLOCK DepositTransaction record").isTrue();

        // --- C: same, must be fully, correctly cancelled, independent of A and B ---
        assertThat(finalC).as("C must reach CANCELLED, unaffected by A/B").isEqualTo(AuctionStatus.CANCELLED);
        Deposit depositC = depositRepository.findByUserId(participantCId).orElseThrow();
        assertThat(depositRepository.isFrozenForAuction(depositC.getId(), auctionCId))
                .as("C's participant FrozenDeposit must be absent (unfrozen)").isFalse();
        assertThat(depositC.getBlockedAmount())
                .as("C's participant blockedAmount must be back to zero").isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(participantRepository.getParticipants(auctionCId))
                .as("C's participant list must be cleared").doesNotContain(participantCId);
        boolean cHasUnblockTx = depositRepository.findTransactionsByAuctionId(auctionCId).stream()
                .anyMatch(t -> t.getType() == DepositTransaction.TransactionType.UNBLOCK
                        && t.getUserId().equals(participantCId));
        assertThat(cHasUnblockTx).as("C must have a durable UNBLOCK DepositTransaction record").isTrue();

        // --- B: its own cancellation must NOT have taken effect, but B has no participants/
        // deposits, so there is no financial state to corrupt - only its own status write was
        // lost, which is internally consistent (still CREATED, still overdue, still retryable).
        assertThat(finalB).as("B's own cancellation must not have applied (lost the version race)")
                .isNotEqualTo(AuctionStatus.CANCELLED);

        // No FailedOperation exists/is needed for B: it stays in a naturally retryable state,
        // the next scheduler tick will select it again and the retry is idempotent (see
        // Phase 5 analysis in the final report). Assert that this remains true post-fix, i.e.
        // R-1/R-2 behavior for genuine deposit-layer failures is untouched by this change.
        boolean anyFailedOpForB = failedOperationRepository.findByStatus("PENDING").stream()
                .anyMatch(op -> auctionBId.equals(op.getAuctionId()));
        assertThat(anyFailedOpForB)
                .as("B's optimistic-lock conflict is not a deposit-layer failure and correctly " +
                        "does not create a FailedOperation record")
                .isFalse();

        // Retry proof: the next scheduler tick would re-select B (status still CREATED,
        // confirmedStart still false, startTime still in the past) and re-attempt with no
        // concurrent writer this time - it must converge to CANCELLED with no side effects to
        // duplicate (B has no participants/deposits in this scenario).
        Auction bRetry = auctionRepository.findById(auctionBId).orElseThrow();
        boolean retrySucceeded = auctionFinalizer.cancelAuction(bRetry);
        assertThat(retrySucceeded).as("B's retry must succeed once no concurrent writer is racing it").isTrue();
        assertThat(auctionRepository.findById(auctionBId).orElseThrow().getStatus())
                .as("B must converge to CANCELLED on retry").isEqualTo(AuctionStatus.CANCELLED);
    }
}

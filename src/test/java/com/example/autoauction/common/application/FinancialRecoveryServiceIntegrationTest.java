// common/application/FinancialRecoveryServiceIntegrationTest.java
package com.example.autoauction.common.application;

import com.example.autoauction.auction.application.AuctionFinalizer;
import com.example.autoauction.auction.domain.Auction;
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
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R-2 Phases 6 & 8: proves the FinancialRecoveryService's safety invariants against a
 * real (H2, test-profile) database with real transactions and, where concurrency matters,
 * real threads - not Mockito.
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("R-2: FinancialRecoveryService — concurrency и reconciliation инварианты")
class FinancialRecoveryServiceIntegrationTest {

    @Autowired
    private FinancialRecoveryService recoveryService;

    @Autowired
    private DepositService depositService;

    @Autowired
    private DepositRepository depositRepository;

    @Autowired
    private FailedOperationRepository failedOperationRepository;

    @Autowired
    private AuctionFinalizer auctionFinalizer;

    @Autowired
    private AuctionRepository auctionRepository;

    @Autowired
    private AuctionParticipantRepository participantRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private TransactionTemplate tx() {
        TransactionTemplate t = new TransactionTemplate(transactionManager);
        t.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
        return t;
    }

    private Long createUser(String prefix) {
        Role userRole = roleRepository.findByName("ROLE_USER")
                .orElseGet(() -> roleRepository.save(new Role(null, "ROLE_USER", "Обычный пользователь")));
        String unique = prefix + System.nanoTime();
        return userRepository.save(new User(null, unique, unique + "@test.com", "encoded", true, Set.of(userRole))).getId();
    }

    /** Sets up a deposit with a frozen amount for auctionId and a matching PENDING FailedOperation row. */
    private record Fixture(Long userId, Long depositId, Long auctionId, BigDecimal amount, FailedOperation failedOp) {}

    private Fixture setUpFrozenDepositWithFailedUnfreeze() {
        return tx().execute(status -> {
            Long userId = createUser("recov");
            depositService.createDeposit(userId, new BigDecimal("50000"));
            Long auctionId = System.nanoTime() % 1_000_000_000L;
            BigDecimal amount = new BigDecimal("10000.00");
            depositService.freezeForAuction(userId, auctionId, amount);
            Long depositId = depositRepository.findByUserId(userId).orElseThrow().getId();

            FailedOperation op = new FailedOperation("UNFREEZE_AUCTION", userId, auctionId,
                    "simulated failure", "simulated stack", OffsetDateTime.now());
            FailedOperation saved = failedOperationRepository.save(op);
            return new Fixture(userId, depositId, auctionId, amount, saved);
        });
    }

    // ==================== Phase 8: reconciliation invariants ====================

    @Test
    @DisplayName("1. completed/cancelled аукцион с провалившимся unfreeze -> recoverPending в итоге размораживает")
    void completedAuction_FailedUnfreeze_EventuallyUnfreezes() {
        Fixture f = setUpFrozenDepositWithFailedUnfreeze();

        recoveryService.recoverPending();

        Deposit deposit = depositRepository.findByUserId(f.userId()).orElseThrow();
        assertThat(depositRepository.isFrozenForAuction(f.depositId(), f.auctionId())).isFalse();
        assertThat(deposit.getAvailableBalance()).isEqualByComparingTo(new BigDecimal("50000.00"));

        FailedOperation reloaded = failedOperationRepository.findById(f.failedOp().getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(FailedOperation.STATUS_RESOLVED);
        assertThat(reloaded.getResolvedAt()).isNotNull();
    }

    @Test
    @DisplayName("3. провалившийся markAsWinner -> recoverPending в итоге отмечает правильного победителя")
    void failedMarkWinner_EventuallyReachesCorrectWinnerState() {
        Fixture f = tx().execute(status -> {
            Long userId = createUser("winner");
            depositService.createDeposit(userId, new BigDecimal("50000"));
            Long auctionId = System.nanoTime() % 1_000_000_000L;
            BigDecimal amount = new BigDecimal("10000.00");
            depositService.freezeForAuction(userId, auctionId, amount);
            Long depositId = depositRepository.findByUserId(userId).orElseThrow().getId();

            FailedOperation op = new FailedOperation("MARK_WINNER", userId, auctionId,
                    "simulated failure", "simulated stack", OffsetDateTime.now());
            FailedOperation saved = failedOperationRepository.save(op);
            return new Fixture(userId, depositId, auctionId, amount, saved);
        });

        // needsWinnerMark (boolean/exists-style, no entity hydration) rather than
        // getFrozenDepositsByDepositId(...).stream()...isWinner() - the latter was
        // confirmed, by direct reproduction, to return a stale already-loaded
        // JpaDepositFrozenEntity instance instead of freshly-committed state; see
        // DepositService.needsWinnerMark's javadoc for the full explanation.
        assertThat(depositRepository.needsWinnerMark(f.depositId(), f.auctionId()))
                .as("winner flag must not be set yet before recovery runs").isTrue();

        recoveryService.recoverPending();

        assertThat(depositRepository.needsWinnerMark(f.depositId(), f.auctionId()))
                .as("winner flag must be set after recovery resolves the failed markAsWinner").isFalse();

        FailedOperation reloaded = failedOperationRepository.findById(f.failedOp().getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(FailedOperation.STATUS_RESOLVED);
    }

    @Test
    @DisplayName("4/9. Уже разрешённая операция: повторный recoverOne не выполняет вторую мутацию и не переоткрывает запись")
    void alreadyResolvedOperation_RetryDoesNotDoubleProcess() {
        Fixture f = setUpFrozenDepositWithFailedUnfreeze();

        recoveryService.recoverOne(f.failedOp().getId());
        FailedOperation afterFirst = failedOperationRepository.findById(f.failedOp().getId()).orElseThrow();
        assertThat(afterFirst.getStatus()).isEqualTo(FailedOperation.STATUS_RESOLVED);
        OffsetDateTime firstResolvedAt = afterFirst.getResolvedAt();

        // Second call: status is RESOLVED, not PENDING, so claim() must fail and no processing happens.
        recoveryService.recoverOne(f.failedOp().getId());

        FailedOperation afterSecond = failedOperationRepository.findById(f.failedOp().getId()).orElseThrow();
        assertThat(afterSecond.getStatus()).isEqualTo(FailedOperation.STATUS_RESOLVED);
        assertThat(afterSecond.getResolvedAt()).isEqualTo(firstResolvedAt);

        Deposit deposit = depositRepository.findByUserId(f.userId()).orElseThrow();
        assertThat(deposit.getAvailableBalance()).isEqualByComparingTo(new BigDecimal("50000.00"));
        List<com.example.autoauction.deposit.domain.DepositTransaction> transactions =
                depositRepository.findTransactionsByUserId(f.userId());
        long unblockCount = transactions.stream()
                .filter(t -> t.getType() == com.example.autoauction.deposit.domain.DepositTransaction.TransactionType.UNBLOCK)
                .count();
        assertThat(unblockCount).as("exactly one UNBLOCK, never two, across both recoverOne calls").isEqualTo(1L);
    }

    @Test
    @DisplayName("6/A. Два конкурентных воркера обрабатывают одну и ту же FailedOperation -> ровно одна мутация")
    void concurrentRecoveryWorkers_SameFailedOperation_ExactlyOneMutation() throws Exception {
        Fixture f = setUpFrozenDepositWithFailedUnfreeze();

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CyclicBarrier barrier = new CyclicBarrier(2);
        AtomicReference<Throwable> errA = new AtomicReference<>();
        AtomicReference<Throwable> errB = new AtomicReference<>();

        Runnable op = () -> recoveryService.recoverOne(f.failedOp().getId());
        executor.submit(() -> {
            try { barrier.await(5, TimeUnit.SECONDS); op.run(); } catch (Throwable t) { errA.set(t); }
        });
        executor.submit(() -> {
            try { barrier.await(5, TimeUnit.SECONDS); op.run(); } catch (Throwable t) { errB.set(t); }
        });
        executor.shutdown();
        assertThat(executor.awaitTermination(15, TimeUnit.SECONDS)).isTrue();

        assertThat(errA.get()).as("recoverOne must not throw even if it loses the claim race").isNull();
        assertThat(errB.get()).as("recoverOne must not throw even if it loses the claim race").isNull();

        Deposit deposit = depositRepository.findByUserId(f.userId()).orElseThrow();
        assertThat(deposit.getAvailableBalance()).isEqualByComparingTo(new BigDecimal("50000.00"));
        List<com.example.autoauction.deposit.domain.DepositTransaction> transactions =
                depositRepository.findTransactionsByUserId(f.userId());
        long unblockCount = transactions.stream()
                .filter(t -> t.getType() == com.example.autoauction.deposit.domain.DepositTransaction.TransactionType.UNBLOCK)
                .count();
        assertThat(unblockCount).as("exactly one UNBLOCK regardless of which worker won the claim").isEqualTo(1L);

        FailedOperation reloaded = failedOperationRepository.findById(f.failedOp().getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(FailedOperation.STATUS_RESOLVED);
    }

    @Test
    @DisplayName("C. Оригинальная мутация уже закоммичена, но FailedOperation ещё не resolved (симулированный краш recoverOne) - " +
            "повторный запуск находит состояние уже верным и просто помечает resolved, без второй мутации")
    void recoveryCommittedButNotMarkedResolved_NextRunResolvesWithoutDoubleMutation() {
        Fixture f = setUpFrozenDepositWithFailedUnfreeze();

        // Simulate "recovery performed the real mutation (its own REQUIRES_NEW transaction
        // committed) but crashed before recoverOne's own transaction reached markResolved":
        // perform the underlying unfreeze directly (durably, its own transaction), WITHOUT
        // going through recoverOne / claim / markResolved at all.
        depositService.unfreezeForAuction(f.userId(), f.auctionId());
        assertThat(depositRepository.isFrozenForAuction(f.depositId(), f.auctionId())).isFalse();

        // The FailedOperation record is still PENDING (recoverOne never ran to mark it).
        FailedOperation stillPending = failedOperationRepository.findById(f.failedOp().getId()).orElseThrow();
        assertThat(stillPending.getStatus()).isEqualTo(FailedOperation.STATUS_PENDING);

        // Now the scheduled recovery runs (as if for the first time after the "crash").
        recoveryService.recoverOne(f.failedOp().getId());

        FailedOperation resolved = failedOperationRepository.findById(f.failedOp().getId()).orElseThrow();
        assertThat(resolved.getStatus()).isEqualTo(FailedOperation.STATUS_RESOLVED);

        List<com.example.autoauction.deposit.domain.DepositTransaction> transactions =
                depositRepository.findTransactionsByUserId(f.userId());
        long unblockCount = transactions.stream()
                .filter(t -> t.getType() == com.example.autoauction.deposit.domain.DepositTransaction.TransactionType.UNBLOCK)
                .count();
        assertThat(unblockCount)
                .as("the real unfreeze happened once (directly); recoverOne must not have performed a second one")
                .isEqualTo(1L);
    }

    @Test
    @DisplayName("7/8/E. Постоянно проваливающаяся операция: остаётся видимой, retryCount растёт, эскалируется в FAILED, не удаляется")
    void permanentlyFailingOperation_RemainsVisibleAndEscalates() {
        // A FailedOperation targeting a user with NO deposit at all - isStillRequired()
        // always throws, so every retry attempt deterministically fails.
        Long ghostUserId = 987_654_321L;
        Long auctionId = System.nanoTime() % 1_000_000_000L;

        FailedOperation op = tx().execute(status ->
                failedOperationRepository.save(new FailedOperation("UNFREEZE_AUCTION", ghostUserId, auctionId,
                        "no deposit", "stack", OffsetDateTime.now())));

        for (int attempt = 0; attempt <= FinancialRecoveryService.MAX_RETRY_ATTEMPTS; attempt++) {
            // Force each attempt to be immediately eligible regardless of backoff.
            tx().executeWithoutResult(status -> {
                FailedOperation current = failedOperationRepository.findById(op.getId()).orElseThrow();
                current.setNextRetryAt(null);
                current.setStatus(FailedOperation.STATUS_PENDING);
                failedOperationRepository.save(current);
            });
            recoveryService.recoverOne(op.getId());
        }

        FailedOperation finalState = failedOperationRepository.findById(op.getId()).orElseThrow();
        assertThat(finalState.getStatus())
                .as("after exceeding MAX_RETRY_ATTEMPTS the operation must be escalated to FAILED, not deleted")
                .isEqualTo(FailedOperation.STATUS_FAILED);
        assertThat(finalState.getRetryCount())
                .as("retryCount must reflect every attempt")
                .isGreaterThanOrEqualTo(FinancialRecoveryService.MAX_RETRY_ATTEMPTS + 1);
        assertThat(finalState.getErrorMessage()).isNotBlank();

        // Still visible/queryable - never silently lost.
        assertThat(failedOperationRepository.findById(op.getId())).isPresent();
        assertThat(failedOperationRepository.findByStatus(FailedOperation.STATUS_FAILED))
                .extracting(FailedOperation::getId)
                .contains(op.getId());

        // A FAILED (not PENDING) row must never be picked up by the retry sweep again.
        assertThat(failedOperationRepository.findRetryablePending(OffsetDateTime.now().plusYears(1)))
                .extracting(FailedOperation::getId)
                .doesNotContain(op.getId());
    }

    @Test
    @DisplayName("B: recovery выполняется, пока то же самое действие выполняется независимо (например, admin.forceUnfreezeAll) — не приводит к отрицательному blockedAmount")
    void recoveryRunsWhileSameOperationAttemptedIndependently_NoDoubleMutationOrNegativeBlocked() throws Exception {
        Fixture f = setUpFrozenDepositWithFailedUnfreeze();

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CyclicBarrier barrier = new CyclicBarrier(2);
        AtomicReference<Throwable> errA = new AtomicReference<>();
        AtomicReference<Throwable> errB = new AtomicReference<>();

        executor.submit(() -> {
            try { barrier.await(5, TimeUnit.SECONDS); recoveryService.recoverOne(f.failedOp().getId()); }
            catch (Throwable t) { errA.set(t); }
        });
        executor.submit(() -> {
            try { barrier.await(5, TimeUnit.SECONDS); depositService.unfreezeForAuction(f.userId(), f.auctionId()); }
            catch (Throwable t) { errB.set(t); }
        });
        executor.shutdown();
        assertThat(executor.awaitTermination(15, TimeUnit.SECONDS)).isTrue();

        Deposit deposit = depositRepository.findByUserId(f.userId()).orElseThrow();
        assertThat(deposit.getBlockedAmount())
                .as("blockedAmount must never go negative regardless of how the two independent unfreeze attempts interleaved")
                .isGreaterThanOrEqualTo(BigDecimal.ZERO);
        assertThat(deposit.getAvailableBalance()).isEqualByComparingTo(new BigDecimal("50000.00"));

        List<com.example.autoauction.deposit.domain.DepositTransaction> transactions =
                depositRepository.findTransactionsByUserId(f.userId());
        long unblockCount = transactions.stream()
                .filter(t -> t.getType() == com.example.autoauction.deposit.domain.DepositTransaction.TransactionType.UNBLOCK)
                .count();
        assertThat(unblockCount).as("exactly one UNBLOCK regardless of interleaving").isEqualTo(1L);
    }

    @Test
    @DisplayName("Аукцион отменён с 2 участниками, у одного unfreeze проваливается: FinancialRecoveryService в итоге размораживает и его")
    void cancelledAuctionWithStrandedParticipant_EventuallyRecoveredByService() {
        TransactionTemplate txTemplate = tx();
        Role userRole = roleRepository.findByName("ROLE_USER")
                .orElseGet(() -> roleRepository.save(new Role(null, "ROLE_USER", "Обычный пользователь")));
        Long sellerId = userRepository.save(new User(null, "r2seller" + System.nanoTime(),
                "r2seller" + System.nanoTime() + "@test.com", "encoded", true, Set.of(userRole))).getId();
        Long brokenUserId = userRepository.save(new User(null, "r2broken" + System.nanoTime(),
                "r2broken" + System.nanoTime() + "@test.com", "encoded", true, Set.of(userRole))).getId();

        BigDecimal requiredDeposit = new BigDecimal("10000.00");
        OffsetDateTime now = OffsetDateTime.now();

        Long auctionId = txTemplate.execute(status -> {
            Auction auction = new Auction(1L, new BigDecimal("1000000"), null, null, new BigDecimal("10000"),
                    now.minusHours(1), now.plusHours(1), sellerId);
            auction.setRequiredDepositAmount(requiredDeposit);
            auction.start();
            Auction saved = auctionRepository.save(auction);

            depositService.createDeposit(brokenUserId, new BigDecimal("50000"));
            depositService.freezeForAuction(brokenUserId, saved.getId(), requiredDeposit);
            participantRepository.registerParticipant(saved.getId(), brokenUserId, requiredDeposit);
            return saved.getId();
        });

        // Force the participant's unfreeze to fail deterministically during cancellation.
        txTemplate.executeWithoutResult(status -> {
            Deposit brokenDeposit = depositRepository.findByUserId(brokenUserId).orElseThrow();
            brokenDeposit.setBlockedAmount(new BigDecimal("1.00"));
            depositRepository.save(brokenDeposit);
        });

        txTemplate.executeWithoutResult(status -> {
            Auction auction = auctionRepository.findByIdWithLock(auctionId).orElseThrow();
            auctionFinalizer.cancelAuction(auction);
        });

        // Confirmed still stranded right after cancellation (mirrors the R-1.6 follow-up proof).
        Deposit brokenBefore = depositRepository.findByUserId(brokenUserId).orElseThrow();
        assertThat(depositRepository.isFrozenForAuction(brokenBefore.getId(), auctionId)).isTrue();

        // Un-corrupt the state the same way a real recovery would find it: the deposit's
        // blockedAmount is whatever it genuinely is; here we restore it to reflect reality
        // (the corruption was only to force the ORIGINAL failure deterministically) so the
        // recovery's own retry can succeed against a consistent deposit.
        txTemplate.executeWithoutResult(status -> {
            Deposit d = depositRepository.findByUserId(brokenUserId).orElseThrow();
            d.setBlockedAmount(requiredDeposit);
            depositRepository.save(d);
        });

        recoveryService.recoverPending();

        Deposit brokenAfter = depositRepository.findByUserId(brokenUserId).orElseThrow();
        assertThat(depositRepository.isFrozenForAuction(brokenAfter.getId(), auctionId))
                .as("PROVEN: the previously-stranded participant is eventually unfrozen by the recovery service")
                .isFalse();
        assertThat(brokenAfter.getAvailableBalance()).isEqualByComparingTo(new BigDecimal("50000.00"));
    }
}

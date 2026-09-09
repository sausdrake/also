// auction/application/SaleFailureResolutionIntegrationTest.java
package com.example.autoauction.auction.application;

import com.example.autoauction.auction.domain.Auction;
import com.example.autoauction.auction.domain.AuctionStatus;
import com.example.autoauction.auction.domain.port.AuctionRepository;
import com.example.autoauction.deposit.application.DepositService;
import com.example.autoauction.deposit.domain.Deposit;
import com.example.autoauction.deposit.domain.DepositTransaction;
import com.example.autoauction.deposit.domain.port.DepositRepository;
import com.example.autoauction.sale.domain.FaultParty;
import com.example.autoauction.sale.domain.SaleResolutionResult;
import com.example.autoauction.sale.domain.port.SaleResolutionRepository;
import com.example.autoauction.user.domain.Role;
import com.example.autoauction.user.domain.User;
import com.example.autoauction.user.domain.port.RoleRepository;
import com.example.autoauction.user.domain.port.UserRepository;
import com.example.autoauction.vehicle.domain.Vehicle;
import com.example.autoauction.vehicle.domain.VehicleStatus;
import com.example.autoauction.vehicle.domain.VehicleType;
import com.example.autoauction.vehicle.domain.port.VehicleRepository;
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
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * X3-A (SALE_FAILED outcomes): real-transaction proof of the atomic
 * WAITING_FOR_PAYMENT -> SALE_FAILED transition performed by
 * SaleFailureResolutionOrchestrator.resolveSaleFailure(), covering all four outcomes (B,
 * BUYER_FAULT, PLATFORM_FAULT, C), the required caller shape (a pessimistic
 * findByIdWithLock() fetch, then resolveSaleFailure() on the already-locked Auction, both
 * inside one physical transaction), the atomic-rollback proof for both a Vehicle-transition
 * failure and a Deposit-operation failure, repeat resolution, and genuine multi-threaded
 * concurrent resolution. Deliberately NOT class-level {@code @Transactional} - matches the
 * TransactionTemplate-based convention already established in
 * AuctionSaleCompletionIntegrationTest, since several tests need real, separately-committed
 * transactions to observe persisted state and rollbacks the way genuinely concurrent callers
 * would.
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("X3-A: атомарность Auction.SALE_FAILED + Vehicle + Deposit при разрешении неудачной продажи")
class SaleFailureResolutionIntegrationTest {

    @Autowired
    private SaleFailureResolutionOrchestrator orchestrator;

    @Autowired
    private AuctionRepository auctionRepository;

    @Autowired
    private VehicleRepository vehicleRepository;

    @Autowired
    private DepositService depositService;

    @Autowired
    private DepositRepository depositRepository;

    @Autowired
    private SaleResolutionRepository saleResolutionRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private final Long resolvedBy = 1L;
    private final BigDecimal winningBid = new BigDecimal("1020000.00");
    private final BigDecimal requiredDeposit = new BigDecimal("50000.00");

    private TransactionTemplate txTemplate() {
        TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);
        txTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
        return txTemplate;
    }

    private String shortVin(String prefix) {
        return prefix + (System.nanoTime() % 1_000_000_000L);
    }

    private Long createWinnerUser(TransactionTemplate txTemplate) {
        return txTemplate.execute(status -> {
            Role userRole = roleRepository.findByName("ROLE_USER")
                    .orElseGet(() -> roleRepository.save(new Role(null, "ROLE_USER", "Обычный пользователь")));
            User winner = new User(null, "x3a-winner" + System.nanoTime(),
                    "x3a-winner" + System.nanoTime() + "@test.com", "encoded", true, Set.of(userRole));
            return userRepository.save(winner).getId();
        });
    }

    /** Mirrors the real production order (bid placed -> freeze -> auction ends -> winner
     *  marked), via the existing, committed DepositService methods directly - not a new
     *  freeze/mark mechanism invented for this test. */
    private void freezeAndMarkWinner(TransactionTemplate txTemplate, Long winnerId, Long auctionId) {
        txTemplate.execute(status -> {
            depositService.createDeposit(winnerId, new BigDecimal("100000"));
            return null;
        });
        txTemplate.execute(status -> {
            depositService.freezeForAuction(winnerId, auctionId, requiredDeposit);
            return null;
        });
        txTemplate.execute(status -> {
            depositService.markAsWinner(winnerId, auctionId);
            return null;
        });
    }

    private Long createInAuctionVehicle(TransactionTemplate txTemplate) {
        return txTemplate.execute(status -> {
            Vehicle vehicle = new Vehicle("Toyota", "Camry", 2020, shortVin("X3A"),
                    VehicleType.SEDAN, 1L, "owner");
            vehicle.submitForReview();
            vehicle.approve(1L, "admin");
            vehicle.markInAuction();
            return vehicleRepository.save(vehicle).getId();
        });
    }

    private Long createVehicleWithStatus(TransactionTemplate txTemplate, VehicleStatus status) {
        return txTemplate.execute(txStatus -> {
            Vehicle vehicle = new Vehicle("Honda", "Civic", 2019, shortVin("X3A"),
                    VehicleType.SEDAN, 1L, "owner");
            vehicle.setStatus(status);
            return vehicleRepository.save(vehicle).getId();
        });
    }

    private Long createWaitingForPaymentAuction(TransactionTemplate txTemplate, Long vehicleId, Long winnerId) {
        return txTemplate.execute(status -> {
            OffsetDateTime now = OffsetDateTime.now();
            Auction auction = new Auction(vehicleId, new BigDecimal("1000000"), null, null,
                    new BigDecimal("10000"), now.minusDays(7), now.minusHours(1), 10L);
            auction.setStatus(AuctionStatus.WAITING_FOR_PAYMENT);
            auction.setWinnerId(winnerId);
            auction.setWinningBid(winningBid);
            return auctionRepository.save(auction).getId();
        });
    }

    /** Mirrors the locked caller contract: obtain the pessimistic lock, then call
     *  resolveSaleFailure() on the already-locked Auction, both inside the same transaction. */
    private void resolveViaLockedCaller(TransactionTemplate txTemplate, Long auctionId,
                                         SaleResolutionResult outcome, FaultParty faultParty) {
        txTemplate.execute(status -> {
            Auction locked = auctionRepository.findByIdWithLock(auctionId).orElseThrow();
            orchestrator.resolveSaleFailure(locked, outcome, faultParty, null, resolvedBy);
            return null;
        });
    }

    private record Setup(Long auctionId, Long vehicleId, Long winnerId) {}

    private Setup fullSetup(TransactionTemplate txTemplate) {
        Long vehicleId = createInAuctionVehicle(txTemplate);
        Long winnerId = createWinnerUser(txTemplate);
        Long auctionId = createWaitingForPaymentAuction(txTemplate, vehicleId, winnerId);
        freezeAndMarkWinner(txTemplate, winnerId, auctionId);
        return new Setup(auctionId, vehicleId, winnerId);
    }

    // ==================== Happy path x4 ====================

    @Test
    @DisplayName("B) Happy path: Auction SALE_FAILED, SaleResolution B, Vehicle APPROVED, Deposit UNBLOCK")
    void resolveSaleFailure_OutcomeB_PersistsExpectedState() {
        TransactionTemplate txTemplate = txTemplate();
        Setup setup = fullSetup(txTemplate);

        resolveViaLockedCaller(txTemplate, setup.auctionId(), SaleResolutionResult.B, null);

        Auction auction = txTemplate.execute(status -> auctionRepository.findById(setup.auctionId()).orElseThrow());
        Vehicle vehicle = txTemplate.execute(status -> vehicleRepository.findById(setup.vehicleId()).orElseThrow());
        Deposit deposit = txTemplate.execute(status -> depositRepository.findByUserId(setup.winnerId()).orElseThrow());
        var resolution = txTemplate.execute(status -> saleResolutionRepository.findByAuctionId(setup.auctionId()));

        assertThat(auction.getStatus()).isEqualTo(AuctionStatus.SALE_FAILED);
        assertThat(vehicle.getStatus()).isEqualTo(VehicleStatus.APPROVED);
        assertThat(deposit.getBlockedAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(resolution).isPresent();
        assertThat(resolution.orElseThrow().getResult()).isEqualTo(SaleResolutionResult.B);
        assertThat(resolution.orElseThrow().getFaultParty()).isNull();
    }

    @Test
    @DisplayName("BUYER_FAULT) Happy path: Auction SALE_FAILED, SaleResolution BUYER_FAULT, Vehicle APPROVED, Deposit CONFISCATE")
    void resolveSaleFailure_OutcomeBuyerFault_PersistsExpectedState() {
        TransactionTemplate txTemplate = txTemplate();
        Setup setup = fullSetup(txTemplate);
        BigDecimal balanceBefore = txTemplate.execute(status ->
                depositRepository.findByUserId(setup.winnerId()).orElseThrow().getBalance());

        resolveViaLockedCaller(txTemplate, setup.auctionId(), SaleResolutionResult.BUYER_FAULT, FaultParty.BUYER);

        Auction auction = txTemplate.execute(status -> auctionRepository.findById(setup.auctionId()).orElseThrow());
        Vehicle vehicle = txTemplate.execute(status -> vehicleRepository.findById(setup.vehicleId()).orElseThrow());
        Deposit deposit = txTemplate.execute(status -> depositRepository.findByUserId(setup.winnerId()).orElseThrow());
        var resolution = txTemplate.execute(status -> saleResolutionRepository.findByAuctionId(setup.auctionId()));

        assertThat(auction.getStatus()).isEqualTo(AuctionStatus.SALE_FAILED);
        assertThat(vehicle.getStatus()).isEqualTo(VehicleStatus.APPROVED);
        assertThat(deposit.getBlockedAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(deposit.getBalance()).isEqualByComparingTo(balanceBefore.subtract(requiredDeposit));
        assertThat(resolution.orElseThrow().getResult()).isEqualTo(SaleResolutionResult.BUYER_FAULT);
        assertThat(resolution.orElseThrow().getFaultParty()).isEqualTo(FaultParty.BUYER);

        var transactions = txTemplate.execute(status -> depositRepository.findTransactionsByUserId(setup.winnerId()));
        assertThat(transactions).anyMatch(t -> t.getType() == DepositTransaction.TransactionType.CHARGE);
    }

    @Test
    @DisplayName("PLATFORM_FAULT) Happy path: Auction SALE_FAILED, SaleResolution PLATFORM_FAULT, Vehicle NEEDS_REDIAGNOSTIC, Deposit UNBLOCK")
    void resolveSaleFailure_OutcomePlatformFault_PersistsExpectedState() {
        TransactionTemplate txTemplate = txTemplate();
        Setup setup = fullSetup(txTemplate);

        resolveViaLockedCaller(txTemplate, setup.auctionId(), SaleResolutionResult.PLATFORM_FAULT, FaultParty.PLATFORM);

        Auction auction = txTemplate.execute(status -> auctionRepository.findById(setup.auctionId()).orElseThrow());
        Vehicle vehicle = txTemplate.execute(status -> vehicleRepository.findById(setup.vehicleId()).orElseThrow());
        Deposit deposit = txTemplate.execute(status -> depositRepository.findByUserId(setup.winnerId()).orElseThrow());
        var resolution = txTemplate.execute(status -> saleResolutionRepository.findByAuctionId(setup.auctionId()));

        assertThat(auction.getStatus()).isEqualTo(AuctionStatus.SALE_FAILED);
        assertThat(vehicle.getStatus()).isEqualTo(VehicleStatus.NEEDS_REDIAGNOSTIC);
        assertThat(deposit.getBlockedAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(resolution.orElseThrow().getResult()).isEqualTo(SaleResolutionResult.PLATFORM_FAULT);
        assertThat(resolution.orElseThrow().getFaultParty()).isEqualTo(FaultParty.PLATFORM);
    }

    @Test
    @DisplayName("C) Happy path: Auction SALE_FAILED, SaleResolution C, Vehicle NEEDS_REDIAGNOSTIC, Deposit UNBLOCK")
    void resolveSaleFailure_OutcomeC_PersistsExpectedState() {
        TransactionTemplate txTemplate = txTemplate();
        Setup setup = fullSetup(txTemplate);

        resolveViaLockedCaller(txTemplate, setup.auctionId(), SaleResolutionResult.C, FaultParty.SELLER);

        Auction auction = txTemplate.execute(status -> auctionRepository.findById(setup.auctionId()).orElseThrow());
        Vehicle vehicle = txTemplate.execute(status -> vehicleRepository.findById(setup.vehicleId()).orElseThrow());
        Deposit deposit = txTemplate.execute(status -> depositRepository.findByUserId(setup.winnerId()).orElseThrow());
        var resolution = txTemplate.execute(status -> saleResolutionRepository.findByAuctionId(setup.auctionId()));

        assertThat(auction.getStatus()).isEqualTo(AuctionStatus.SALE_FAILED);
        assertThat(vehicle.getStatus()).isEqualTo(VehicleStatus.NEEDS_REDIAGNOSTIC);
        assertThat(deposit.getBlockedAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(resolution.orElseThrow().getResult()).isEqualTo(SaleResolutionResult.C);
        assertThat(resolution.orElseThrow().getFaultParty()).isEqualTo(FaultParty.SELLER);
    }

    // ==================== Vehicle failure rollback ====================

    @Test
    @DisplayName("Vehicle rollback: Vehicle не IN_AUCTION -> ВСЯ транзакция откатывается")
    void resolveSaleFailure_VehicleNotInAuction_WholeTransactionRollsBack() {
        TransactionTemplate txTemplate = txTemplate();
        // Exceptional state constructed directly: a Vehicle that is APPROVED (not IN_AUCTION)
        // despite its auction already being WAITING_FOR_PAYMENT - cannot arise through the
        // normal production call graph, but must still be handled safely if it ever does.
        Long vehicleId = createVehicleWithStatus(txTemplate, VehicleStatus.APPROVED);
        Long winnerId = createWinnerUser(txTemplate);
        Long auctionId = createWaitingForPaymentAuction(txTemplate, vehicleId, winnerId);
        freezeAndMarkWinner(txTemplate, winnerId, auctionId);

        assertThatThrownBy(() -> resolveViaLockedCaller(txTemplate, auctionId, SaleResolutionResult.B, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Only IN_AUCTION can be returned to APPROVED");

        Auction auction = txTemplate.execute(status -> auctionRepository.findById(auctionId).orElseThrow());
        Vehicle vehicle = txTemplate.execute(status -> vehicleRepository.findById(vehicleId).orElseThrow());
        Deposit deposit = txTemplate.execute(status -> depositRepository.findByUserId(winnerId).orElseThrow());
        var resolution = txTemplate.execute(status -> saleResolutionRepository.existsByAuctionId(auctionId));

        assertThat(auction.getStatus()).isEqualTo(AuctionStatus.WAITING_FOR_PAYMENT);
        assertThat(vehicle.getStatus()).isEqualTo(VehicleStatus.APPROVED);
        assertThat(deposit.getBlockedAmount()).isEqualByComparingTo(requiredDeposit);
        assertThat(resolution).isFalse();
    }

    // ==================== Deposit failure rollback ====================

    @Test
    @DisplayName("Deposit rollback: нет замороженного депозита победителя -> ВСЯ транзакция откатывается")
    void resolveSaleFailure_NoFrozenWinnerDeposit_WholeTransactionRollsBack() {
        TransactionTemplate txTemplate = txTemplate();
        Long vehicleId = createInAuctionVehicle(txTemplate);
        Long winnerId = txTemplate.execute(status -> {
            Role userRole = roleRepository.findByName("ROLE_USER")
                    .orElseGet(() -> roleRepository.save(new Role(null, "ROLE_USER", "Обычный пользователь")));
            User winner = new User(null, "x3a-nofrz" + System.nanoTime(),
                    "x3a-nofrz" + System.nanoTime() + "@test.com", "encoded", true, Set.of(userRole));
            Long id = userRepository.save(winner).getId();
            depositRepository.save(new Deposit(id, new BigDecimal("100000"))); // no freeze at all
            return id;
        });
        Long auctionId = createWaitingForPaymentAuction(txTemplate, vehicleId, winnerId);

        assertThatThrownBy(() -> resolveViaLockedCaller(txTemplate, auctionId, SaleResolutionResult.B, null))
                .isInstanceOf(IllegalStateException.class);

        Auction auction = txTemplate.execute(status -> auctionRepository.findById(auctionId).orElseThrow());
        Vehicle vehicle = txTemplate.execute(status -> vehicleRepository.findById(vehicleId).orElseThrow());
        boolean resolutionExists = txTemplate.execute(status -> saleResolutionRepository.existsByAuctionId(auctionId));

        assertThat(auction.getStatus()).isEqualTo(AuctionStatus.WAITING_FOR_PAYMENT);
        assertThat(vehicle.getStatus()).isEqualTo(VehicleStatus.IN_AUCTION);
        assertThat(resolutionExists).isFalse();
    }

    // ==================== Repeat resolution ====================

    @Test
    @DisplayName("Repeat: второе разрешение после уже SALE_FAILED отклоняется, состояние не меняется")
    void resolveSaleFailure_RepeatResolution_SecondAttemptRejectedStateUnchanged() {
        TransactionTemplate txTemplate = txTemplate();
        Setup setup = fullSetup(txTemplate);

        resolveViaLockedCaller(txTemplate, setup.auctionId(), SaleResolutionResult.B, null);

        assertThatThrownBy(() -> resolveViaLockedCaller(txTemplate, setup.auctionId(), SaleResolutionResult.B, null))
                .isInstanceOf(IllegalStateException.class);

        Auction auction = txTemplate.execute(status -> auctionRepository.findById(setup.auctionId()).orElseThrow());
        Vehicle vehicle = txTemplate.execute(status -> vehicleRepository.findById(setup.vehicleId()).orElseThrow());
        boolean resolutionExists = txTemplate.execute(status -> saleResolutionRepository.existsByAuctionId(setup.auctionId()));

        assertThat(auction.getStatus()).isEqualTo(AuctionStatus.SALE_FAILED);
        assertThat(vehicle.getStatus()).isEqualTo(VehicleStatus.APPROVED);
        assertThat(resolutionExists).isTrue();
    }

    // ==================== Concurrency ====================

    @Test
    @DisplayName("Concurrency: два одновременных разрешения одного аукциона -> ровно одно успешно, ровно один SaleResolution, без дубликата транзакции")
    void resolveSaleFailure_ConcurrentAttempts_ExactlyOneSucceeds() {
        TransactionTemplate setupTx = txTemplate();
        Setup setup = fullSetup(setupTx);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CyclicBarrier barrier = new CyclicBarrier(2);
        AtomicReference<Throwable> errA = new AtomicReference<>();
        AtomicReference<Throwable> errB = new AtomicReference<>();

        Runnable attempt = () -> resolveViaLockedCaller(txTemplate(), setup.auctionId(), SaleResolutionResult.B, null);
        Runnable wrappedA = () -> {
            try {
                barrier.await(5, TimeUnit.SECONDS);
                attempt.run();
            } catch (Throwable t) {
                errA.set(t);
            }
        };
        Runnable wrappedB = () -> {
            try {
                barrier.await(5, TimeUnit.SECONDS);
                attempt.run();
            } catch (Throwable t) {
                errB.set(t);
            }
        };

        executor.submit(wrappedA);
        executor.submit(wrappedB);
        executor.shutdown();
        try {
            assertThat(executor.awaitTermination(15, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }

        boolean aFailed = errA.get() != null;
        boolean bFailed = errB.get() != null;
        assertThat(aFailed ^ bFailed)
                .as("Exactly one of the two concurrent resolution attempts must fail, errA=%s, errB=%s",
                        errA.get(), errB.get())
                .isTrue();

        TransactionTemplate readTx = txTemplate();
        Auction auction = readTx.execute(status -> auctionRepository.findById(setup.auctionId()).orElseThrow());
        Vehicle vehicle = readTx.execute(status -> vehicleRepository.findById(setup.vehicleId()).orElseThrow());
        long resolutionCount = readTx.execute(status ->
                saleResolutionRepository.existsByAuctionId(setup.auctionId()) ? 1L : 0L);
        var transactions = readTx.execute(status -> depositRepository.findTransactionsByUserId(setup.winnerId()));
        long unblockCount = transactions.stream()
                .filter(t -> t.getType() == DepositTransaction.TransactionType.UNBLOCK)
                .filter(t -> requiredDeposit.compareTo(t.getAmount()) == 0)
                .count();

        assertThat(auction.getStatus()).isEqualTo(AuctionStatus.SALE_FAILED);
        assertThat(vehicle.getStatus()).isEqualTo(VehicleStatus.APPROVED);
        assertThat(resolutionCount).isEqualTo(1L);
        assertThat(unblockCount).isEqualTo(1L);
    }
}

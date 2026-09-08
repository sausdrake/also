// auction/application/AuctionVehicleStartIntegrationTest.java
package com.example.autoauction.auction.application;

import com.example.autoauction.admin.application.AdminAuctionService;
import com.example.autoauction.auction.domain.Auction;
import com.example.autoauction.auction.domain.AuctionStatus;
import com.example.autoauction.auction.domain.port.AuctionRepository;
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
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Vehicle Auction-State Wiring: real-transaction proof of the atomic Auction+Vehicle start
 * transition (AuctionFinalizer.startAuction), covering both production entry points
 * (AdminAuctionService.startAuction and the shape AuctionCompletionScheduler.startConfirmedAuctions
 * uses - an unlocked, possibly-detached Auction object passed directly into
 * AuctionFinalizer.startAuction), plus both directions of atomicity failure. Deliberately NOT
 * class-level @Transactional - each test needs real, separately-committed transactions to
 * observe state changes and rollbacks the way genuinely concurrent callers would, matching the
 * TransactionTemplate-based convention already established in
 * AuctionCompletionSchedulerTransactionIsolationIntegrationTest.
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("Vehicle Auction-State Wiring: атомарность Auction+Vehicle при старте (интеграционный тест)")
class AuctionVehicleStartIntegrationTest {

    @Autowired
    private AuctionFinalizer auctionFinalizer;

    @Autowired
    private AdminAuctionService adminAuctionService;

    @Autowired
    private AuctionRepository auctionRepository;

    @Autowired
    private VehicleRepository vehicleRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private TransactionTemplate txTemplate() {
        TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);
        txTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
        return txTemplate;
    }

    private Long createSeller(TransactionTemplate txTemplate) {
        return txTemplate.execute(status -> {
            Role userRole = roleRepository.findByName("ROLE_USER")
                    .orElseGet(() -> roleRepository.save(new Role(null, "ROLE_USER", "Обычный пользователь")));
            User seller = new User(null, "vasw-seller" + System.nanoTime(),
                    "vasw-seller" + System.nanoTime() + "@test.com", "encoded", true, Set.of(userRole));
            return userRepository.save(seller).getId();
        });
    }

    private String shortVin(String prefix) {
        // VIN column is VARCHAR(17) - keep well under that with a short unique numeric suffix.
        return prefix + (System.nanoTime() % 1_000_000_000L);
    }

    private Long createApprovedVehicle(TransactionTemplate txTemplate, Long ownerId) {
        return txTemplate.execute(status -> {
            Vehicle vehicle = new Vehicle("Toyota", "Camry", 2020, shortVin("A"),
                    VehicleType.SEDAN, ownerId, "owner" + ownerId);
            vehicle.submitForReview();
            vehicle.approve(1L, "admin");
            return vehicleRepository.save(vehicle).getId();
        });
    }

    private Long createDraftVehicle(TransactionTemplate txTemplate, Long ownerId) {
        return txTemplate.execute(status ->
                vehicleRepository.save(new Vehicle("Honda", "Civic", 2019, shortVin("D"),
                        VehicleType.SEDAN, ownerId, "owner" + ownerId)).getId());
    }

    private Long createCreatedAuction(TransactionTemplate txTemplate, Long vehicleId, Long sellerId) {
        return txTemplate.execute(status -> {
            OffsetDateTime now = OffsetDateTime.now();
            Auction auction = new Auction(
                    vehicleId, new BigDecimal("1000000"), null, null, new BigDecimal("10000"),
                    now.minusMinutes(1), now.plusDays(7), sellerId
            );
            return auctionRepository.save(auction).getId();
        });
    }

    @Test
    @DisplayName("A) Admin-путь: AdminAuctionService.startAuction реально переводит Vehicle APPROVED -> IN_AUCTION")
    void adminPath_StartAuction_RealVehicleTransitionsToInAuction() {
        TransactionTemplate txTemplate = txTemplate();
        Long sellerId = createSeller(txTemplate);
        Long vehicleId = createApprovedVehicle(txTemplate, sellerId);
        Long auctionId = createCreatedAuction(txTemplate, vehicleId, sellerId);

        txTemplate.execute(status -> adminAuctionService.startAuction(auctionId, 1L));

        Auction persistedAuction = txTemplate.execute(status -> auctionRepository.findById(auctionId).orElseThrow());
        Vehicle persistedVehicle = txTemplate.execute(status -> vehicleRepository.findById(vehicleId).orElseThrow());

        assertThat(persistedAuction.getStatus()).isEqualTo(AuctionStatus.ACTIVE);
        assertThat(persistedVehicle.getStatus()).isEqualTo(VehicleStatus.IN_AUCTION);
    }

    @Test
    @DisplayName("B) Scheduler-путь: AuctionFinalizer.startAuction (вызван напрямую с detached-объектом, как в scheduler) реально переводит Vehicle APPROVED -> IN_AUCTION")
    void schedulerPathShape_StartAuction_RealVehicleTransitionsToInAuction() {
        TransactionTemplate txTemplate = txTemplate();
        Long sellerId = createSeller(txTemplate);
        Long vehicleId = createApprovedVehicle(txTemplate, sellerId);
        Long auctionId = createCreatedAuction(txTemplate, vehicleId, sellerId);

        // Detached read, exactly matching AuctionCompletionScheduler.startConfirmedAuctions's
        // own unlocked bulk query - the object is fetched in one (already-closed) transaction
        // and passed into AuctionFinalizer.startAuction in a separate call.
        Auction detached = txTemplate.execute(status -> auctionRepository.findById(auctionId).orElseThrow());

        auctionFinalizer.startAuction(detached);

        Auction persistedAuction = txTemplate.execute(status -> auctionRepository.findById(auctionId).orElseThrow());
        Vehicle persistedVehicle = txTemplate.execute(status -> vehicleRepository.findById(vehicleId).orElseThrow());

        assertThat(persistedAuction.getStatus()).isEqualTo(AuctionStatus.ACTIVE);
        assertThat(persistedVehicle.getStatus()).isEqualTo(VehicleStatus.IN_AUCTION);
    }

    @Test
    @DisplayName("C) Non-idempotency: повторный вызов startAuction на уже ACTIVE аукционе падает ДО повторного обращения к Vehicle; персистентное состояние не меняется")
    void startAuction_SecondAttemptOnActiveAuction_FailsWithoutTouchingVehicleAgain() {
        TransactionTemplate txTemplate = txTemplate();
        Long sellerId = createSeller(txTemplate);
        Long vehicleId = createApprovedVehicle(txTemplate, sellerId);
        Long auctionId = createCreatedAuction(txTemplate, vehicleId, sellerId);

        Auction firstAttempt = txTemplate.execute(status -> auctionRepository.findById(auctionId).orElseThrow());
        auctionFinalizer.startAuction(firstAttempt);

        // Second attempt: fresh read, now ACTIVE.
        Auction secondAttempt = txTemplate.execute(status -> auctionRepository.findById(auctionId).orElseThrow());
        assertThat(secondAttempt.getStatus()).isEqualTo(AuctionStatus.ACTIVE);

        assertThatThrownBy(() -> auctionFinalizer.startAuction(secondAttempt))
                .isInstanceOf(IllegalStateException.class);

        Auction persistedAuction = txTemplate.execute(status -> auctionRepository.findById(auctionId).orElseThrow());
        Vehicle persistedVehicle = txTemplate.execute(status -> vehicleRepository.findById(vehicleId).orElseThrow());

        // Vehicle.markInAuction() is deliberately non-idempotent - had the second attempt
        // reached it, it would have thrown ("Only APPROVED can be moved to auction"), not
        // silently succeeded. Proving it stays IN_AUCTION (not reverted, not double-mutated,
        // no exception bubbling from a second markInAuction() call reaching persistence) shows
        // the Auction guard alone prevented the second attempt from ever reaching Vehicle.
        assertThat(persistedAuction.getStatus()).isEqualTo(AuctionStatus.ACTIVE);
        assertThat(persistedVehicle.getStatus()).isEqualTo(VehicleStatus.IN_AUCTION);
    }

    @Test
    @DisplayName("D1) Atomicity: Vehicle не APPROVED -> markInAuction бросает -> Auction transition откатывается (остаётся CREATED)")
    void startAuction_VehicleTransitionFails_AuctionRollsBackToCreated() {
        TransactionTemplate txTemplate = txTemplate();
        Long sellerId = createSeller(txTemplate);
        Long vehicleId = createDraftVehicle(txTemplate, sellerId); // NOT approved
        Long auctionId = createCreatedAuction(txTemplate, vehicleId, sellerId);

        Auction auction = txTemplate.execute(status -> auctionRepository.findById(auctionId).orElseThrow());

        assertThatThrownBy(() -> auctionFinalizer.startAuction(auction))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Only APPROVED can be moved to auction");

        Auction persistedAuction = txTemplate.execute(status -> auctionRepository.findById(auctionId).orElseThrow());
        Vehicle persistedVehicle = txTemplate.execute(status -> vehicleRepository.findById(vehicleId).orElseThrow());

        // The whole transaction (Auction.start() + the failed Vehicle mutation attempt) rolled
        // back together - Auction must still be CREATED, never persisted as ACTIVE.
        assertThat(persistedAuction.getStatus()).isEqualTo(AuctionStatus.CREATED);
        assertThat(persistedVehicle.getStatus()).isEqualTo(VehicleStatus.DRAFT);
    }

    @Test
    @DisplayName("D2) Atomicity: Auction optimistic-lock conflict на save() -> уже выполненная Vehicle-транзакция откатывается (остаётся APPROVED)")
    void startAuction_AuctionOptimisticLockConflict_VehicleRollsBackToApproved() {
        TransactionTemplate txTemplate = txTemplate();
        Long sellerId = createSeller(txTemplate);
        Long vehicleId = createApprovedVehicle(txTemplate, sellerId);
        Long auctionId = createCreatedAuction(txTemplate, vehicleId, sellerId);

        // Stale in-memory copy (version=0), exactly as a fresh scheduler query would have
        // returned before the concurrent update below.
        Auction stale = txTemplate.execute(status -> auctionRepository.findById(auctionId).orElseThrow());

        // Independent, already-committed concurrent write bumping the row's @Version without
        // changing status - simulates a genuinely concurrent writer racing this auction.
        txTemplate.execute(status -> {
            Auction fresh = auctionRepository.findById(auctionId).orElseThrow();
            fresh.setConfirmedStart(true);
            return auctionRepository.save(fresh);
        });

        assertThatThrownBy(() -> auctionFinalizer.startAuction(stale))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);

        Auction persistedAuction = txTemplate.execute(status -> auctionRepository.findById(auctionId).orElseThrow());
        Vehicle persistedVehicle = txTemplate.execute(status -> vehicleRepository.findById(vehicleId).orElseThrow());

        // vehicleService.markInAuction() runs BEFORE auctionRepository.save() inside
        // startAuction, so by the time the optimistic-lock conflict is detected, the Vehicle
        // mutation has already been flushed within this same (not-yet-committed) transaction.
        // The whole transaction rolling back must undo it too - Vehicle must still be
        // APPROVED, not left stranded at IN_AUCTION.
        assertThat(persistedAuction.getStatus()).isEqualTo(AuctionStatus.CREATED);
        assertThat(persistedVehicle.getStatus()).isEqualTo(VehicleStatus.APPROVED);
    }

    @Test
    @DisplayName("F) Concurrent admin/scheduler: проигравшая (stale) попытка не может повредить уже закоммиченную пару Auction ACTIVE + Vehicle IN_AUCTION")
    void concurrentAdminAndScheduler_LosingStaleAttempt_CannotCorruptCommittedPair() {
        TransactionTemplate txTemplate = txTemplate();
        Long sellerId = createSeller(txTemplate);
        Long vehicleId = createApprovedVehicle(txTemplate, sellerId);
        Long auctionId = createCreatedAuction(txTemplate, vehicleId, sellerId);

        // "Scheduler" read: a stale, detached snapshot taken before the "admin" wins the race.
        Auction staleSchedulerCopy = txTemplate.execute(status -> auctionRepository.findById(auctionId).orElseThrow());

        // "Admin" path wins first: fully commits Auction ACTIVE + Vehicle IN_AUCTION together.
        Auction winner = txTemplate.execute(status -> auctionRepository.findById(auctionId).orElseThrow());
        auctionFinalizer.startAuction(winner);

        Auction afterWinner = txTemplate.execute(status -> auctionRepository.findById(auctionId).orElseThrow());
        Vehicle vehicleAfterWinner = txTemplate.execute(status -> vehicleRepository.findById(vehicleId).orElseThrow());
        assertThat(afterWinner.getStatus()).isEqualTo(AuctionStatus.ACTIVE);
        assertThat(vehicleAfterWinner.getStatus()).isEqualTo(VehicleStatus.IN_AUCTION);

        // "Scheduler" now attempts its own (losing) start with the stale, pre-race snapshot.
        // Vehicle is already IN_AUCTION (from the winner's already-committed pair), so
        // Vehicle.markInAuction()'s own guard rejects this attempt before it can touch
        // anything - the loser never even reaches Auction's own @Version check in this
        // ordering, and correctly so: there is nothing left for it to legitimately do.
        assertThatThrownBy(() -> auctionFinalizer.startAuction(staleSchedulerCopy))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Only APPROVED can be moved to auction");

        Auction finalAuction = txTemplate.execute(status -> auctionRepository.findById(auctionId).orElseThrow());
        Vehicle finalVehicle = txTemplate.execute(status -> vehicleRepository.findById(vehicleId).orElseThrow());

        // The winner's already-committed pair must remain completely intact.
        assertThat(finalAuction.getStatus()).isEqualTo(AuctionStatus.ACTIVE);
        assertThat(finalVehicle.getStatus()).isEqualTo(VehicleStatus.IN_AUCTION);
    }
}

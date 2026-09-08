// auction/infrastructure/scheduler/AuctionStartSchedulerTransactionIsolationIntegrationTest.java
package com.example.autoauction.auction.infrastructure.scheduler;

import com.example.autoauction.auction.application.AuctionFinalizer;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Vehicle Auction-State Wiring: same cross-auction isolation proof as
 * AuctionCompletionSchedulerTransactionIsolationIntegrationTest /
 * AuctionFinalizationSchedulerTransactionIsolationIntegrationTest, now for
 * AuctionCompletionScheduler.startConfirmedAuctions() / AuctionFinalizer.startAuction(), which
 * carried the identical (now fixed) shared-batch-transaction defect those two methods were
 * already fixed for - startConfirmedAuctions was the one scheduler method the original R-3
 * remediation (commit c25435d) never touched.
 *
 * Proves: with @Transactional removed from startConfirmedAuctions(), one auction's optimistic-
 * lock conflict inside AuctionFinalizer.startAuction() no longer poisons a shared physical
 * transaction and can no longer roll back sibling auctions (and their own atomic Vehicle
 * transitions) processed in the same scheduler invocation.
 *
 * Mirrors the sibling tests' technique exactly: cannot go through the scheduler's own
 * findConfirmedAuctionsReadyToStart() to produce B's conflict (a fresh query always sees the
 * current, non-conflicting version), so this drives AuctionFinalizer.startAuction() directly
 * with a STALE in-memory copy of B, exactly reproducing what a genuinely concurrent writer
 * (e.g. an admin action) would produce, but without any surrounding TransactionTemplate -
 * mirroring the un-annotated scheduler loop exactly.
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("Vehicle Auction-State Wiring: изоляция транзакций AuctionCompletionScheduler.startConfirmedAuctions()")
class AuctionStartSchedulerTransactionIsolationIntegrationTest {

    @Autowired
    private AuctionFinalizer auctionFinalizer;

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

    private String shortVin(String prefix) {
        return prefix + (System.nanoTime() % 1_000_000_000L);
    }

    private Long createConfirmedReadyAuctionWithApprovedVehicle(TransactionTemplate txTemplate, Long sellerId) {
        return txTemplate.execute(status -> {
            Vehicle vehicle = new Vehicle("Toyota", "Corolla", 2021, shortVin("S"),
                    VehicleType.SEDAN, sellerId, "owner" + sellerId);
            vehicle.submitForReview();
            vehicle.approve(1L, "admin");
            Long vehicleId = vehicleRepository.save(vehicle).getId();

            OffsetDateTime now = OffsetDateTime.now();
            Auction auction = new Auction(
                    vehicleId, new BigDecimal("1000000"), null, null, new BigDecimal("10000"),
                    now.minusMinutes(5), now.plusDays(7), sellerId
            );
            auction.setConfirmedStart(true);
            return auctionRepository.save(auction).getId();
        });
    }

    @Test
    @DisplayName("Один auction (B) получает version-конфликт при старте, но A и C успешно стартуют и коммитятся, включая свои Vehicle-транзакции")
    void oneAuctionVersionConflictDuringStart_DoesNotAffectSiblingAuctionsOrTheirVehicles() {
        TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);
        txTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);

        Role userRole = roleRepository.findByName("ROLE_USER")
                .orElseGet(() -> roleRepository.save(new Role(null, "ROLE_USER", "Обычный пользователь")));
        Long sellerId = userRepository.save(
                new User(null, "assti-seller" + System.nanoTime(), "assti-seller" + System.nanoTime() + "@test.com",
                        "encoded", true, Set.of(userRole))).getId();

        Long auctionAId = createConfirmedReadyAuctionWithApprovedVehicle(txTemplate, sellerId);
        Long auctionBId = createConfirmedReadyAuctionWithApprovedVehicle(txTemplate, sellerId);
        Long auctionCId = createConfirmedReadyAuctionWithApprovedVehicle(txTemplate, sellerId);

        Long vehicleAId = txTemplate.execute(status -> auctionRepository.findById(auctionAId).orElseThrow().getVehicleId());
        Long vehicleBId = txTemplate.execute(status -> auctionRepository.findById(auctionBId).orElseThrow().getVehicleId());
        Long vehicleCId = txTemplate.execute(status -> auctionRepository.findById(auctionCId).orElseThrow().getVehicleId());

        // Stale in-memory copy of B (version=0), exactly as a fresh scheduler query would have
        // returned before the concurrent update below.
        Auction staleB = txTemplate.execute(status -> auctionRepository.findById(auctionBId).orElseThrow());

        // Independent, already-committed concurrent write to B - simulates a genuinely
        // concurrent admin action bumping B's @Version between the scheduler's read and its
        // own save(), without touching B's status or its Vehicle.
        txTemplate.execute(status -> {
            Auction fresh = auctionRepository.findById(auctionBId).orElseThrow();
            fresh.setRequiredDepositAmount(new BigDecimal("5000"));
            return auctionRepository.save(fresh);
        });

        // THE FIXED ARCHITECTURE: no outer TransactionTemplate/@Transactional wraps this loop -
        // this mirrors AuctionCompletionScheduler.startConfirmedAuctions() as it exists after
        // this slice's fix exactly (plain loop, per-call try/catch, no ambient transaction), so
        // each startAuction() call is its own independent transaction.
        boolean aThrew = false, bThrew = false, cThrew = false;
        try {
            auctionFinalizer.startAuction(auctionRepository.findById(auctionAId).orElseThrow());
        } catch (Exception e) {
            aThrew = true;
        }
        try {
            auctionFinalizer.startAuction(staleB); // stale version -> genuine conflict
        } catch (Exception e) {
            bThrew = true;
        }
        try {
            auctionFinalizer.startAuction(auctionRepository.findById(auctionCId).orElseThrow());
        } catch (Exception e) {
            cThrew = true;
        }

        AuctionStatus finalA = auctionRepository.findById(auctionAId).orElseThrow().getStatus();
        AuctionStatus finalB = auctionRepository.findById(auctionBId).orElseThrow().getStatus();
        AuctionStatus finalC = auctionRepository.findById(auctionCId).orElseThrow().getStatus();
        VehicleStatus vehicleAStatus = vehicleRepository.findById(vehicleAId).orElseThrow().getStatus();
        VehicleStatus vehicleBStatus = vehicleRepository.findById(vehicleBId).orElseThrow().getStatus();
        VehicleStatus vehicleCStatus = vehicleRepository.findById(vehicleCId).orElseThrow().getStatus();

        System.out.println("SCHEDULER START ISOLATION EVIDENCE: threw(A,B,C)=" + aThrew + "," + bThrew + "," + cThrew +
                " auctionStatus A=" + finalA + " B=" + finalB + " C=" + finalC +
                " vehicleStatus A=" + vehicleAStatus + " B=" + vehicleBStatus + " C=" + vehicleCStatus);

        // --- A: must be fully, correctly started, Auction+Vehicle together ---
        assertThat(finalA).as("A must reach ACTIVE, unaffected by B's failure").isEqualTo(AuctionStatus.ACTIVE);
        assertThat(vehicleAStatus).as("A's vehicle must reach IN_AUCTION").isEqualTo(VehicleStatus.IN_AUCTION);

        // --- C: same, must be fully, correctly started, independent of A and B ---
        assertThat(finalC).as("C must reach ACTIVE, unaffected by A/B").isEqualTo(AuctionStatus.ACTIVE);
        assertThat(vehicleCStatus).as("C's vehicle must reach IN_AUCTION").isEqualTo(VehicleStatus.IN_AUCTION);

        // --- B: its own start must NOT have taken effect - lost the version race - and,
        // critically, its Vehicle must NOT have been left stranded at IN_AUCTION either (the
        // whole failed transaction, including the already-flushed Vehicle mutation, must have
        // rolled back together).
        assertThat(bThrew).as("B's stale start attempt must throw").isTrue();
        assertThat(finalB).as("B's own start must not have applied (lost the version race)")
                .isNotEqualTo(AuctionStatus.ACTIVE);
        assertThat(vehicleBStatus).as("B's vehicle must remain APPROVED, not stranded IN_AUCTION")
                .isEqualTo(VehicleStatus.APPROVED);

        // Retry proof: the next scheduler tick would re-select B (status still CREATED,
        // confirmedStart still true, startTime still in the past) and re-attempt with no
        // concurrent writer this time - it must converge to ACTIVE with its Vehicle correctly
        // transitioning too.
        Auction bRetry = auctionRepository.findById(auctionBId).orElseThrow();
        auctionFinalizer.startAuction(bRetry);
        assertThat(auctionRepository.findById(auctionBId).orElseThrow().getStatus())
                .as("B must converge to ACTIVE on retry").isEqualTo(AuctionStatus.ACTIVE);
        assertThat(vehicleRepository.findById(vehicleBId).orElseThrow().getStatus())
                .as("B's vehicle must converge to IN_AUCTION on retry").isEqualTo(VehicleStatus.IN_AUCTION);
    }
}

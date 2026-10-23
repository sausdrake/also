// admin/application/AuctionCommitmentLinkageIntegrationTest.java
package com.example.autoauction.admin.application;

import com.example.autoauction.auction.domain.Auction;
import com.example.autoauction.auction.domain.port.AuctionRepository;
import com.example.autoauction.auction.web.dto.AuctionCreateRequest;
import com.example.autoauction.auction.web.dto.AuctionResponse;
import com.example.autoauction.deposit.application.DepositService;
import com.example.autoauction.diagnostic.application.DiagnosticRequestService;
import com.example.autoauction.diagnostic.commitment.application.SellerDiagnosticCommitmentService;
import com.example.autoauction.diagnostic.commitment.domain.CommitmentStatus;
import com.example.autoauction.diagnostic.commitment.domain.SellerDiagnosticCommitment;
import com.example.autoauction.diagnostic.commitment.domain.port.SellerDiagnosticCommitmentRepository;
import com.example.autoauction.diagnostic.domain.DiagnosticRequest;
import com.example.autoauction.diagnostic.domain.port.DiagnosticRequestRepository;
import com.example.autoauction.policy.application.PolicyVersionService;
import com.example.autoauction.user.domain.Role;
import com.example.autoauction.user.domain.User;
import com.example.autoauction.user.domain.port.RoleRepository;
import com.example.autoauction.user.domain.port.UserRepository;
import com.example.autoauction.vehicle.domain.Vehicle;
import com.example.autoauction.vehicle.domain.port.VehicleRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * X3-C: real-database proof of the Auction<->SellerDiagnosticCommitment linkage - the
 * migration's FK/UNIQUE constraints, the deterministic resolution chain end-to-end through
 * AdminAuctionService, historical NULL compatibility, re-auction isolation, and the
 * concurrent-creation race.
 *
 * Deliberately NOT @Transactional at the class level, matching
 * SellerDiagnosticCommitmentFinalizationIntegrationTest's own precedent - the concurrency test
 * needs two genuinely independent, concurrently-committing transactions, and every other test
 * uses unique fixture data (nanoTime-based VIN/username) rather than relying on rollback.
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("X3-C: Auction <-> SellerDiagnosticCommitment linkage - интеграционные тесты")
class AuctionCommitmentLinkageIntegrationTest {

    @Autowired
    private AdminAuctionService adminAuctionService;
    @Autowired
    private DiagnosticRequestService diagnosticRequestService;
    @Autowired
    private SellerDiagnosticCommitmentService sellerDiagnosticCommitmentService;
    @Autowired
    private SellerDiagnosticCommitmentRepository sellerDiagnosticCommitmentRepository;
    @Autowired
    private DiagnosticRequestRepository diagnosticRequestRepository;
    @Autowired
    private VehicleRepository vehicleRepository;
    @Autowired
    private AuctionRepository auctionRepository;
    @Autowired
    private DepositService depositService;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private RoleRepository roleRepository;
    @Autowired
    private PolicyVersionService policyVersionService;
    // DEPOSIT-01: system_settings is a JdbcTemplate table absent from the H2 test schema (see
    // DiagnosticRequestVinUniquenessIntegrationTest) - only the global participation deposit is stubbed
    @org.springframework.boot.test.mock.mockito.MockBean
    private SystemSettingsService systemSettingsService;

    private record Setup(Long vehicleId, Long commitmentId, Long requestId) {}

    /**
     * create -> freeze -> COMPLETED -> approve, mirroring
     * SellerDiagnosticCommitmentFinalizationIntegrationTest's own reject-path setup shape, but
     * driven through approveReport so both the Vehicle (APPROVED, diagnosticRequestId set) and
     * the commitment (AWAITING_FINALIZATION) actually exist afterward.
     */
    private Setup approveAndGetVehicleWithCommitment() {
        // X3-E: freezeCommitment() (called below) now snapshots the currently published
        // PolicyVersion's id and fails closed if none exists - publish one unconditionally
        // here so every caller of this helper (not only those that go on to create an
        // Auction) satisfies that precondition. Calling publishPolicyVersion() again later in
        // a given test (several already do, for the Auction-creation policy snapshot) is
        // harmless - it just publishes another version, and findCurrentPublished() always
        // resolves the latest.
        publishPolicyVersion();

        Role userRole = roleRepository.findByName("ROLE_USER")
                .orElseGet(() -> roleRepository.save(new Role(null, "ROLE_USER", "Обычный пользователь")));
        User seller = new User(null, "x3c" + System.nanoTime(), "x3c" + System.nanoTime() + "@test.com",
                "encoded", true, Set.of(userRole));
        Long userId = userRepository.save(seller).getId();

        depositService.createDeposit(userId, new BigDecimal("50000"));

        String vin = "X3C" + (System.nanoTime() % 100000000L);
        DiagnosticRequest request = new DiagnosticRequest();
        request.setUserId(userId);
        request.setBrand("BMW");
        request.setModel("X5");
        request.setYear(2022);
        request.setVin(vin);
        request.setDepositFrozen(new BigDecimal("10000"));
        request.setStatus(DiagnosticRequest.DiagnosticRequestStatus.PENDING);
        request.setCreatedAt(OffsetDateTime.now());
        request.setUpdatedAt(OffsetDateTime.now());
        Long requestId = diagnosticRequestRepository.save(request).getId();

        sellerDiagnosticCommitmentService.freezeCommitment(userId, requestId,
                new BigDecimal("10000"), new BigDecimal("1000"));

        DiagnosticRequest completed = diagnosticRequestRepository.findById(requestId).orElseThrow();
        completed.setStatus(DiagnosticRequest.DiagnosticRequestStatus.COMPLETED);
        diagnosticRequestRepository.save(completed);

        diagnosticRequestService.approveReport(requestId, 999L);

        Vehicle vehicle = vehicleRepository.findByVin(vin).orElseThrow();
        assertThat(vehicle.getDiagnosticRequestId())
                .as("setup precondition: Vehicle must carry the originating diagnosticRequestId")
                .isEqualTo(requestId);

        SellerDiagnosticCommitment commitment = sellerDiagnosticCommitmentRepository
                .findByDiagnosticRequestId(requestId).orElseThrow();
        assertThat(commitment.getStatus())
                .as("setup precondition: commitment must be AWAITING_FINALIZATION")
                .isEqualTo(CommitmentStatus.AWAITING_FINALIZATION);

        return new Setup(vehicle.getId(), commitment.getId(), requestId);
    }

    private Long publishPolicyVersion() {
        // DEPOSIT-01: auction creation also snapshots the global participation deposit (fail-closed without it)
        org.mockito.Mockito.when(systemSettingsService.getRequiredBigDecimal(SystemSettingsService.PARTICIPATION_DEPOSIT_KEY))
                .thenReturn(new BigDecimal("10000.00"));
        return policyVersionService.publish(
                policyVersionService.create(new BigDecimal("0.05"), 999L).getId(), 998L, 0L).getId();
    }

    private AuctionCreateRequest requestFor(Long vehicleId) {
        OffsetDateTime start = OffsetDateTime.now().plusDays(1);
        return new AuctionCreateRequest(
                vehicleId, new BigDecimal("1000000"), new BigDecimal("1200000"),
                new BigDecimal("1500000"),
                start, start.plusDays(7), null, null);
    }

    @Test
    @DisplayName("Happy path: новый Auction резолвит и сохраняет реальный commitment id, round-trip через репозиторий")
    void createAuction_ResolvesAndPersistsRealCommitment() {
        Setup setup = approveAndGetVehicleWithCommitment();
        publishPolicyVersion();

        AuctionResponse response = adminAuctionService.createAuction(requestFor(setup.vehicleId()), 999L);

        Auction persisted = auctionRepository.findById(response.id()).orElseThrow();
        assertThat(persisted.getSellerDiagnosticCommitmentId()).isEqualTo(setup.commitmentId());
    }

    @Test
    @DisplayName("Без диагностического цикла (Vehicle.diagnosticRequestId == NULL) -> Auction не создаётся")
    void createAuction_VehicleWithoutDiagnosticRequestId_Rejected() {
        // a manually-created, directly-approved vehicle - no diagnosticRequestId ever set
        Vehicle manual = new Vehicle("Lada", "Vesta", 2022, "MAN" + (System.nanoTime() % 100000000L),
                com.example.autoauction.vehicle.domain.VehicleType.SEDAN, 1L, "diagnostic");
        manual.submitForReview();
        manual.approve(999L, "admin");
        Long vehicleId = vehicleRepository.save(manual).getId();
        publishPolicyVersion();

        assertThrows(IllegalArgumentException.class, () ->
                adminAuctionService.createAuction(requestFor(vehicleId), 999L));

        assertThat(auctionRepository.findByVehicleId(vehicleId)).isEmpty();
    }

    @Test
    @DisplayName("Историческая строка с NULL commitment остаётся валидной и читаемой")
    void historicalAuctionWithNullCommitment_RemainsReadable() {
        OffsetDateTime start = OffsetDateTime.now().plusDays(1);
        Auction legacy = new Auction(777777L, new BigDecimal("100"), null, null,
                new BigDecimal("10"), start, start.plusDays(1), 999L);
        Auction saved = auctionRepository.save(legacy);

        Auction reloaded = auctionRepository.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getSellerDiagnosticCommitmentId()).isNull();
    }

    @Test
    @DisplayName("DB UNIQUE: второй Auction не может сохраниться с уже использованным commitment id")
    void uniqueConstraint_PreventsSecondAuctionReusingSameCommitment() {
        Setup setup = approveAndGetVehicleWithCommitment();
        OffsetDateTime start = OffsetDateTime.now().plusDays(1);

        Auction first = new Auction(setup.vehicleId(), new BigDecimal("100"), null, null,
                new BigDecimal("10"), start, start.plusDays(1), 999L);
        first.setSellerDiagnosticCommitmentId(setup.commitmentId());
        auctionRepository.save(first);

        Auction second = new Auction(setup.vehicleId(), new BigDecimal("200"), null, null,
                new BigDecimal("10"), start, start.plusDays(1), 999L);
        second.setSellerDiagnosticCommitmentId(setup.commitmentId());

        assertThatThrownBy(() -> auctionRepository.save(second))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // NOTE: no automated test asserts the FK itself (seller_diagnostic_commitment_id ->
    // seller_diagnostic_commitment(id)) rejects a non-existent id. This project's H2 test
    // profile runs with Flyway disabled and ddl-auto=create-drop, which generates its schema
    // from JPA annotations only - and, matching this codebase's own established, disclosed
    // precedent (see JpaSellerDiagnosticCommitmentEntity's Javadoc on its own diagnostic_request_id
    // FK), a plain @Column Long field deliberately carries no @ManyToOne/@JoinColumn mapping
    // (to avoid JPA-level cross-module coupling), so Hibernate never generates the FK under H2
    // either way. The UNIQUE constraint above IS exercised here because it is expressible via
    // a column-level @Table(uniqueConstraints=...) with no relationship mapping involved - the
    // FK itself is verified by migration review only (see V36's own header comment), the same
    // level of proof this project already accepts for the precedented seller_diagnostic_commitment
    // FK.

    @Test
    @DisplayName("Re-auction isolation: commitment уже привязанный к (даже отменённому) Auction никогда не переиспользуется")
    void reAuction_CannotReuseAlreadyLinkedCommitment() {
        Setup setup = approveAndGetVehicleWithCommitment();
        publishPolicyVersion();

        AuctionResponse firstAuction = adminAuctionService.createAuction(requestFor(setup.vehicleId()), 999L);
        Auction persistedFirst = auctionRepository.findById(firstAuction.id()).orElseThrow();
        assertThat(persistedFirst.getSellerDiagnosticCommitmentId()).isEqualTo(setup.commitmentId());

        // AUC-NOSALE-01: the cancellation returns the guarantee (commitment FINALIZED as A) and
        // sends the vehicle to re-diagnostic, so a second auction is refused at the vehicle-status
        // guard already.
        adminAuctionService.cancelAuction(firstAuction.id(), 999L);
        publishPolicyVersion();

        assertThrows(IllegalArgumentException.class, () ->
                adminAuctionService.createAuction(requestFor(setup.vehicleId()), 999L));
        assertThat(sellerDiagnosticCommitmentRepository.findById(setup.commitmentId()).orElseThrow().getStatus())
                .isEqualTo(CommitmentStatus.FINALIZED);

        // Even if the vehicle were APPROVED again on its OLD diagnostic pointer, the consumed
        // commitment is never reused - AuctionCommitmentResolver's own rejection.
        Vehicle vehicle = vehicleRepository.findById(setup.vehicleId()).orElseThrow();
        vehicle.setStatus(com.example.autoauction.vehicle.domain.VehicleStatus.APPROVED);
        vehicleRepository.save(vehicle);
        assertThrows(IllegalStateException.class, () ->
                adminAuctionService.createAuction(requestFor(setup.vehicleId()), 999L));

        assertThat(auctionRepository.findByVehicleId(setup.vehicleId())).hasSize(1);
    }

    @Test
    @DisplayName("Concurrency: два одновременных createAuction на одном Vehicle -> ровно один успех, второй fails closed")
    void concurrentAuctionCreation_ExactlyOneSucceeds() throws Exception {
        Setup setup = approveAndGetVehicleWithCommitment();
        publishPolicyVersion();

        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failureCount = new AtomicInteger(0);

        Runnable task = () -> {
            try {
                barrier.await(10, TimeUnit.SECONDS);
                adminAuctionService.createAuction(requestFor(setup.vehicleId()), 999L);
                successCount.incrementAndGet();
            } catch (Exception e) {
                failureCount.incrementAndGet();
            }
        };

        executor.submit(task);
        executor.submit(task);
        executor.shutdown();
        assertThat(executor.awaitTermination(15, TimeUnit.SECONDS)).isTrue();

        assertThat(successCount.get()).isEqualTo(1);
        assertThat(failureCount.get()).isEqualTo(1);

        List<Auction> auctionsForVehicle = auctionRepository.findByVehicleId(setup.vehicleId());
        assertThat(auctionsForVehicle).hasSize(1);
        assertThat(auctionsForVehicle.get(0).getSellerDiagnosticCommitmentId()).isEqualTo(setup.commitmentId());
    }
}

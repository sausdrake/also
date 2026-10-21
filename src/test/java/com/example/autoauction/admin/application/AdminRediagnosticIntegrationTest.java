package com.example.autoauction.admin.application;

import com.example.autoauction.auction.application.AuctionCommitmentResolver;
import com.example.autoauction.auction.domain.Auction;
import com.example.autoauction.auction.domain.AuctionStatus;
import com.example.autoauction.auction.domain.port.AuctionRepository;
import com.example.autoauction.deposit.application.DepositService;
import com.example.autoauction.deposit.domain.DepositTransaction;
import com.example.autoauction.deposit.domain.port.DepositRepository;
import com.example.autoauction.diagnostic.application.DiagnosticRequestService;
import com.example.autoauction.diagnostic.commitment.domain.CommitmentStatus;
import com.example.autoauction.diagnostic.commitment.domain.SellerDiagnosticCommitment;
import com.example.autoauction.diagnostic.commitment.domain.port.SellerDiagnosticCommitmentRepository;
import com.example.autoauction.diagnostic.domain.DiagnosticRequest;
import com.example.autoauction.diagnostic.domain.DiagnosticRequest.DiagnosticRequestStatus;
import com.example.autoauction.diagnostic.domain.RediagnosticReason;
import com.example.autoauction.diagnostic.domain.port.DiagnosticRequestRepository;
import com.example.autoauction.identity.application.IdentityVerificationRequiredException;
import com.example.autoauction.identity.domain.DocumentType;
import com.example.autoauction.identity.domain.IdentityDocument;
import com.example.autoauction.identity.domain.IdentityVerification;
import com.example.autoauction.identity.domain.port.IdentityDocumentRepository;
import com.example.autoauction.identity.domain.port.IdentityVerificationRepository;
import com.example.autoauction.policy.application.PolicyVersionService;
import com.example.autoauction.sale.domain.FaultParty;
import com.example.autoauction.sale.domain.SaleResolution;
import com.example.autoauction.sale.domain.SaleResolutionResult;
import com.example.autoauction.sale.domain.port.SaleResolutionRepository;
import com.example.autoauction.user.domain.Role;
import com.example.autoauction.user.domain.User;
import com.example.autoauction.user.domain.port.RoleRepository;
import com.example.autoauction.user.domain.port.UserRepository;
import com.example.autoauction.vehicle.application.VehicleService;
import com.example.autoauction.vehicle.domain.Vehicle;
import com.example.autoauction.vehicle.domain.VehicleStatus;
import com.example.autoauction.vehicle.domain.port.VehicleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * MVP-005 (approved policy) against real persistence (H2): ordering a re-diagnostic creates a
 * NEW request for the SAME vehicle, linked to the superseded one with a mandatory reason; who
 * pays follows the reason; nothing historical is changed; approving the new report returns the
 * vehicle to APPROVED on the new request; every guard holds before any write.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@DisplayName("MVP-005: re-diagnostic workflow")
class AdminRediagnosticIntegrationTest {

    private static final BigDecimal GUARANTEE = new BigDecimal("10000");
    private static final BigDecimal FEE = new BigDecimal("4000");
    private static final BigDecimal START_BALANCE = new BigDecimal("50000");
    private static final Long ADMIN = 1L;

    @Autowired private AdminRediagnosticService rediagnosticService;
    @Autowired private DiagnosticRequestService diagnosticRequestService;
    @Autowired private DiagnosticRequestRepository requestRepository;
    @Autowired private VehicleService vehicleService;
    @Autowired private VehicleRepository vehicleRepository;
    @Autowired private AuctionRepository auctionRepository;
    @Autowired private SaleResolutionRepository saleResolutionRepository;
    @Autowired private DepositService depositService;
    @Autowired private DepositRepository depositRepository;
    @Autowired private SellerDiagnosticCommitmentRepository commitmentRepository;
    @Autowired private AuctionCommitmentResolver commitmentResolver;
    @Autowired private PolicyVersionService policyVersionService;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private IdentityDocumentRepository identityDocumentRepository;
    @Autowired private IdentityVerificationRepository identityVerificationRepository;
    // system_settings is a JdbcTemplate table absent from the H2 test schema.
    @MockBean private SystemSettingsService systemSettingsService;

    private Long seller;
    private Long original;
    private Long vehicleId;

    @BeforeEach
    void setUp() {
        policyVersionService.publish(policyVersionService.create(new BigDecimal("0.02"), 1L).getId(), 2L, 0L);
        when(systemSettingsService.getBigDecimal(eq("diagnostic_deposit_amount"), any())).thenReturn(GUARANTEE);
        when(systemSettingsService.getRequiredBigDecimal("diagnostic_fee_amount")).thenReturn(FEE);
        seller = user(true);
    }

    // ------------------------------------------------------------------ fixtures

    private Long user(boolean verified) {
        Role role = roleRepository.findByName("ROLE_USER")
                .orElseGet(() -> roleRepository.save(new Role(null, "ROLE_USER", "Обычный пользователь")));
        String name = "rd" + System.nanoTime();
        Long id = userRepository.save(new User(null, name, name + "@test.com", "encoded", true, Set.of(role))).getId();
        depositService.createDeposit(id, START_BALANCE);
        if (verified) {
            OffsetDateTime now = OffsetDateTime.now();
            Long doc = identityDocumentRepository.save(new IdentityDocument("identity-documents/rd" + System.nanoTime(), id, "image/jpeg", 1L, now)).getId();
            IdentityVerification iv = IdentityVerification.firstSubmission(id, "KG", DocumentType.NATIONAL_ID, "RD" + System.nanoTime(), doc, doc, doc, now);
            iv.approve(now);
            identityVerificationRepository.save(iv);
        }
        return id;
    }

    /** An approved original diagnosis whose vehicle failed to sell with the given fault. */
    private void vehicleNeedingRediagnostic(Long sellerId, SaleResolutionResult result, FaultParty fault) {
        String vin = ("WBA" + System.nanoTime() + "00000000000000").substring(0, 17);
        DiagnosticRequest r = new DiagnosticRequest();
        r.setUserId(sellerId);
        r.setBrand("BMW");
        r.setModel("X5");
        r.setYear(2020);
        r.setVin(vin);
        r.setStatus(DiagnosticRequestStatus.APPROVED);
        r.setDepositFrozen(GUARANTEE);
        r.setCreatedAt(OffsetDateTime.now().minusDays(30));
        r.setUpdatedAt(OffsetDateTime.now().minusDays(29));
        r.setRejectedReason(null);
        original = requestRepository.save(r).getId();
        vehicleId = vehicleService.createFromDiagnosticRequest(original, "BMW", "X5", 2020, vin, 9L, "diag", ADMIN, "admin").id();
        Vehicle v = vehicleRepository.findById(vehicleId).orElseThrow();
        v.setStatus(VehicleStatus.NEEDS_REDIAGNOSTIC);
        vehicleRepository.save(v);
        OffsetDateTime now = OffsetDateTime.now();
        Auction auction = new Auction(vehicleId, new BigDecimal("1000000"), null, null, new BigDecimal("10000"),
                now.minusDays(5), now.minusDays(1), ADMIN);
        auction.setStatus(AuctionStatus.SALE_FAILED);
        Long auctionId = auctionRepository.save(auction).getId();
        saleResolutionRepository.save(new SaleResolution(auctionId, result, fault, "sale failed", ADMIN, now, now));
    }

    private BigDecimal balance(Long userId) { return depositService.getDepositByUserId(userId).balance(); }
    private BigDecimal available(Long userId) { return depositService.getDepositByUserId(userId).availableBalance(); }

    private List<DepositTransaction> charges(Long userId) {
        return depositRepository.findTransactionsByUserId(userId).stream()
                .filter(t -> t.getType() == DepositTransaction.TransactionType.CHARGE).toList();
    }

    private long requestsForVin() {
        return requestRepository.findByVinOrderByCreatedAtAsc(vehicleRepository.findById(vehicleId).orElseThrow().getVin()).size();
    }

    private DiagnosticRequest snapshot(Long id) { return requestRepository.findById(id).orElseThrow(); }

    /** Field-by-field equality at storage precision (timestamps within the DB's microsecond rounding, decimals by value). */
    private static void assertUnchanged(DiagnosticRequest before, DiagnosticRequest after) {
        assertThat(after).usingRecursiveComparison()
                .withComparatorForType(BigDecimal::compareTo, BigDecimal.class)
                .withComparatorForType((OffsetDateTime a, OffsetDateTime b) ->
                        Math.abs(java.time.Duration.between(a, b).toNanos()) < 1_000 ? 0 : 1,
                        OffsetDateTime.class)
                .isEqualTo(before);
    }

    // ------------------------------------------------------------------ creation

    @Test
    @DisplayName("diagnostic error: new linked request, AlSo pays (no charge), guarantee frozen, fee 0, history intact")
    void diagnosticError_alsoPays() {
        vehicleNeedingRediagnostic(seller, SaleResolutionResult.PLATFORM_FAULT, FaultParty.PLATFORM);
        DiagnosticRequest before = snapshot(original);

        DiagnosticRequest created = rediagnosticService.orderRediagnostic(vehicleId, RediagnosticReason.DIAGNOSTIC_ERROR, "Диагност пропустил дефект", ADMIN);

        assertThat(created.getStatus()).isEqualTo(DiagnosticRequestStatus.PENDING);
        assertThat(created.getPreviousDiagnosticRequestId()).isEqualTo(original);
        assertThat(created.getVehicleId()).isEqualTo(vehicleId);
        assertThat(created.getRediagnosticReason()).isEqualTo(RediagnosticReason.DIAGNOSTIC_ERROR);
        assertThat(created.getRediagnosticComment()).isEqualTo("Диагност пропустил дефект");
        assertThat(created.getRediagnosticOrderedBy()).isEqualTo(ADMIN);
        assertThat(created.getRediagnosticOrderedAt()).isNotNull();
        assertThat(created.getUserId()).isEqualTo(seller);
        assertThat(created.getVin()).isEqualTo(before.getVin());

        assertThat(balance(seller)).isEqualByComparingTo(START_BALANCE);           // nothing paid by the seller
        assertThat(charges(seller)).isEmpty();
        assertThat(available(seller)).isEqualByComparingTo(START_BALANCE.subtract(GUARANTEE)); // only the guarantee freeze
        SellerDiagnosticCommitment commitment = commitmentRepository.findByDiagnosticRequestId(created.getId()).orElseThrow();
        assertThat(commitment.getDiagnosticFeeAmount()).isEqualByComparingTo("0");
        assertThat(commitment.getStatus()).isEqualTo(CommitmentStatus.FROZEN);

        assertUnchanged(before, snapshot(original));  // old request untouched
        Vehicle vehicle = vehicleRepository.findById(vehicleId).orElseThrow();
        assertThat(vehicle.getStatus()).isEqualTo(VehicleStatus.NEEDS_REDIAGNOSTIC);
        assertThat(vehicle.getDiagnosticRequestId()).isEqualTo(original);
        assertThat(vehicleRepository.findByVin(vehicle.getVin()).orElseThrow().getId()).isEqualTo(vehicleId);
    }

    @Test
    @DisplayName("seller fault: the seller pays the fee exactly once, no extra penalty, commitment fee 0")
    void sellerFault_sellerPaysOnce() {
        vehicleNeedingRediagnostic(seller, SaleResolutionResult.C, FaultParty.SELLER);

        DiagnosticRequest created = rediagnosticService.orderRediagnostic(vehicleId, RediagnosticReason.SELLER_FAULT, null, ADMIN);

        assertThat(balance(seller)).isEqualByComparingTo(START_BALANCE.subtract(FEE));
        assertThat(available(seller)).isEqualByComparingTo(START_BALANCE.subtract(FEE).subtract(GUARANTEE));
        List<DepositTransaction> charges = charges(seller);
        assertThat(charges).hasSize(1);
        assertThat(charges.get(0).getAmount()).isEqualByComparingTo(FEE);
        assertThat(charges.get(0).getReference()).isEqualTo("REDIAGNOSTIC_" + created.getId());
        assertThat(commitmentRepository.findByDiagnosticRequestId(created.getId()).orElseThrow().getDiagnosticFeeAmount())
                .isEqualByComparingTo("0");
        assertThat(created.getRediagnosticComment()).isNull();
    }

    // ------------------------------------------------------------------ guards (all before any write)

    private void assertNothingHappened(long requestsBefore) {
        assertThat(requestsForVin()).isEqualTo(requestsBefore);
        assertThat(balance(seller)).isEqualByComparingTo(START_BALANCE);
        assertThat(available(seller)).isEqualByComparingTo(START_BALANCE);
        assertThat(vehicleRepository.findById(vehicleId).orElseThrow().getStatus()).isEqualTo(VehicleStatus.NEEDS_REDIAGNOSTIC);
    }

    @Test
    @DisplayName("no reason -> refused, nothing created or charged")
    void missingReason_isRefused() {
        vehicleNeedingRediagnostic(seller, SaleResolutionResult.C, FaultParty.SELLER);
        long before = requestsForVin();

        assertThatThrownBy(() -> rediagnosticService.orderRediagnostic(vehicleId, null, "x", ADMIN))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Причина");
        assertNothingHappened(before);
    }

    @Test
    @DisplayName("reason contradicting the recorded fault -> refused (a platform fault cannot be billed to the seller)")
    void reasonMismatch_isRefused() {
        vehicleNeedingRediagnostic(seller, SaleResolutionResult.PLATFORM_FAULT, FaultParty.PLATFORM);
        long before = requestsForVin();

        assertThatThrownBy(() -> rediagnosticService.orderRediagnostic(vehicleId, RediagnosticReason.SELLER_FAULT, null, ADMIN))
                .isInstanceOf(IllegalArgumentException.class);
        assertNothingHappened(before);
    }

    @Test
    @DisplayName("seller without a verified identity -> refused (KYC is not bypassed)")
    void unverifiedSeller_isRefused() {
        seller = user(false);
        vehicleNeedingRediagnostic(seller, SaleResolutionResult.PLATFORM_FAULT, FaultParty.PLATFORM);
        long before = requestsForVin();

        assertThatThrownBy(() -> rediagnosticService.orderRediagnostic(vehicleId, RediagnosticReason.DIAGNOSTIC_ERROR, null, ADMIN))
                .isInstanceOf(IdentityVerificationRequiredException.class);
        assertNothingHappened(before);
    }

    @Test
    @DisplayName("seller unable to cover fee + guarantee -> refused before any write (deposit guard)")
    void insufficientFunds_isRefusedBeforeAnyWrite() {
        vehicleNeedingRediagnostic(seller, SaleResolutionResult.C, FaultParty.SELLER);
        depositService.freezeForDiagnosticRequest(seller, 999_999L, START_BALANCE.subtract(new BigDecimal("12000"))); // 12000 < 4000 + 10000
        long before = requestsForVin();
        BigDecimal balanceBefore = balance(seller);

        assertThatThrownBy(() -> rediagnosticService.orderRediagnostic(vehicleId, RediagnosticReason.SELLER_FAULT, null, ADMIN))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("Недостаточно средств");
        assertThat(requestsForVin()).isEqualTo(before);
        assertThat(balance(seller)).isEqualByComparingTo(balanceBefore);
        assertThat(charges(seller)).isEmpty();
    }

    @Test
    @DisplayName("a second order while the re-diagnostic is running -> refused, no second charge")
    void secondOrder_isRefused_noDoubleCharge() {
        vehicleNeedingRediagnostic(seller, SaleResolutionResult.C, FaultParty.SELLER);
        rediagnosticService.orderRediagnostic(vehicleId, RediagnosticReason.SELLER_FAULT, null, ADMIN);

        assertThatThrownBy(() -> rediagnosticService.orderRediagnostic(vehicleId, RediagnosticReason.SELLER_FAULT, null, ADMIN))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("уже назначена");
        assertThat(charges(seller)).hasSize(1);
        assertThat(balance(seller)).isEqualByComparingTo(START_BALANCE.subtract(FEE));
    }

    @Test
    @DisplayName("a vehicle that does not need re-diagnostic -> refused")
    void wrongVehicleStatus_isRefused() {
        vehicleNeedingRediagnostic(seller, SaleResolutionResult.PLATFORM_FAULT, FaultParty.PLATFORM);
        Vehicle v = vehicleRepository.findById(vehicleId).orElseThrow();
        v.setStatus(VehicleStatus.APPROVED);
        vehicleRepository.save(v);

        assertThatThrownBy(() -> rediagnosticService.orderRediagnostic(vehicleId, RediagnosticReason.DIAGNOSTIC_ERROR, null, ADMIN))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("NEEDS_REDIAGNOSTIC");
        assertThat(charges(seller)).isEmpty();
    }

    @Test
    @DisplayName("the seller cannot withdraw an admin-ordered re-diagnostic")
    void sellerCannotCancel() {
        vehicleNeedingRediagnostic(seller, SaleResolutionResult.PLATFORM_FAULT, FaultParty.PLATFORM);
        Long created = rediagnosticService.orderRediagnostic(vehicleId, RediagnosticReason.DIAGNOSTIC_ERROR, null, ADMIN).getId();

        assertThatThrownBy(() -> diagnosticRequestService.cancelRequest(created, seller))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("нельзя отозвать");
        assertThat(snapshot(created).getStatus()).isEqualTo(DiagnosticRequestStatus.PENDING);
    }

    // ------------------------------------------------------------------ completion and history

    @Test
    @DisplayName("approving the new report: same vehicle APPROVED on the new request, new commitment drives the next auction, history intact")
    void approval_returnsSameVehicleToApproved() {
        vehicleNeedingRediagnostic(seller, SaleResolutionResult.PLATFORM_FAULT, FaultParty.PLATFORM);
        DiagnosticRequest before = snapshot(original);
        Long created = rediagnosticService.orderRediagnostic(vehicleId, RediagnosticReason.DIAGNOSTIC_ERROR, "повтор", ADMIN).getId();
        DiagnosticRequest r = snapshot(created);
        r.setStatus(DiagnosticRequestStatus.COMPLETED);
        requestRepository.save(r);

        diagnosticRequestService.approveReport(created, ADMIN);

        Vehicle vehicle = vehicleRepository.findById(vehicleId).orElseThrow();
        assertThat(vehicle.getStatus()).isEqualTo(VehicleStatus.APPROVED);
        assertThat(vehicle.getDiagnosticRequestId()).isEqualTo(created);
        assertThat(vehicleRepository.findByVin(vehicle.getVin()).orElseThrow().getId()).isEqualTo(vehicleId);
        Long newCommitment = commitmentRepository.findByDiagnosticRequestId(created).orElseThrow().getId();
        assertThat(commitmentResolver.resolveEligibleCommitment(vehicleId)).isEqualTo(newCommitment);
        assertUnchanged(before, snapshot(original));

        List<DiagnosticRequest> history = rediagnosticService.diagnosticHistory(vehicleId);
        assertThat(history).extracting(DiagnosticRequest::getId).containsExactly(original, created);
        assertThat(history.get(1).getPreviousDiagnosticRequestId()).isEqualTo(original);
        assertThat(history.get(1).getRediagnosticReason()).isEqualTo(RediagnosticReason.DIAGNOSTIC_ERROR);
        assertThat(history).allMatch(h -> h.getStatus() == DiagnosticRequestStatus.APPROVED);
    }

    @Test
    @DisplayName("after a rejected re-diagnostic a new one may be ordered; the superseded request can no longer be reworked")
    void rejectedRediagnostic_canBeSuperseded() {
        vehicleNeedingRediagnostic(seller, SaleResolutionResult.PLATFORM_FAULT, FaultParty.PLATFORM);
        Long first = rediagnosticService.orderRediagnostic(vehicleId, RediagnosticReason.DIAGNOSTIC_ERROR, null, ADMIN).getId();
        DiagnosticRequest r = snapshot(first);
        r.setStatus(DiagnosticRequestStatus.REJECTED);
        r.setAssignedTo(77L);
        requestRepository.save(r);

        Long second = rediagnosticService.orderRediagnostic(vehicleId, RediagnosticReason.DIAGNOSTIC_ERROR, null, ADMIN).getId();

        assertThat(snapshot(second).getPreviousDiagnosticRequestId()).isEqualTo(first);
        assertThatThrownBy(() -> diagnosticRequestService.resetForRework(first, 77L))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("заменена");
        assertThat(rediagnosticService.diagnosticHistory(vehicleId)).extracting(DiagnosticRequest::getId)
                .containsExactly(original, first, second);
    }
}

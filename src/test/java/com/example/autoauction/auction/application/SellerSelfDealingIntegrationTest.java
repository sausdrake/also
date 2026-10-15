package com.example.autoauction.auction.application;

import com.example.autoauction.auction.domain.Auction;
import com.example.autoauction.auction.domain.AuctionStatus;
import com.example.autoauction.auction.domain.port.AuctionRepository;
import com.example.autoauction.deposit.application.DepositService;
import com.example.autoauction.deposit.domain.port.DepositRepository;
import com.example.autoauction.diagnostic.application.DiagnosticRequestService;
import com.example.autoauction.diagnostic.commitment.application.SellerDiagnosticCommitmentService;
import com.example.autoauction.diagnostic.commitment.domain.port.SellerDiagnosticCommitmentRepository;
import com.example.autoauction.diagnostic.domain.DiagnosticRequest;
import com.example.autoauction.diagnostic.domain.port.DiagnosticRequestRepository;
import com.example.autoauction.policy.application.PolicyVersionService;
import com.example.autoauction.user.domain.Role;
import com.example.autoauction.user.domain.User;
import com.example.autoauction.user.domain.port.RoleRepository;
import com.example.autoauction.user.domain.port.UserRepository;
import com.example.autoauction.vehicle.domain.port.VehicleRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SEC-09: the seller - the owner of the vehicle, not the administrator who created the auction -
 * can neither bid on nor Buy Now their own lot. Before the fix only Auction.createdBy (an admin)
 * was excluded, so a seller could bid up their own car.
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("SEC-09: a seller cannot bid on or buy their own vehicle")
class SellerSelfDealingIntegrationTest {

    private static final Long ADMIN_ID = 9801L;

    @Autowired private BidService bidService;
    @Autowired private AuctionSellerResolver auctionSellerResolver;
    @Autowired private AuctionRepository auctionRepository;
    @Autowired private DiagnosticRequestService diagnosticRequestService;
    @Autowired private SellerDiagnosticCommitmentService commitmentService;
    @Autowired private SellerDiagnosticCommitmentRepository commitmentRepository;
    @Autowired private DiagnosticRequestRepository diagnosticRequestRepository;
    @Autowired private VehicleRepository vehicleRepository;
    @Autowired private DepositService depositService;
    @Autowired private DepositRepository depositRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private PolicyVersionService policyVersionService;

    private Long trustedUser(String prefix) {
        Role role = roleRepository.findByName("ROLE_USER")
                .orElseGet(() -> roleRepository.save(new Role(null, "ROLE_USER", "Обычный пользователь")));
        String name = prefix + System.nanoTime();
        Long id = userRepository.save(new User(null, name, name + "@test.com", "x", true, Set.of(role),
                true, ADMIN_ID, OffsetDateTime.now())).getId();
        depositService.createDeposit(id, new BigDecimal("500000"));
        return id;
    }

    /** A seller's approved vehicle with its commitment, auctioned by an administrator. */
    private Auction auctionOfSeller(Long sellerId, AuctionStatus status) {
        policyVersionService.publish(policyVersionService.create(new BigDecimal("0.05"), ADMIN_ID).getId(), ADMIN_ID + 1, 0L);
        String vin = ("S09" + System.nanoTime() + "00000000000000").substring(0, 17);
        DiagnosticRequest request = new DiagnosticRequest();
        request.setUserId(sellerId);
        request.setBrand("BMW");
        request.setModel("X5");
        request.setYear(2022);
        request.setVin(vin);
        request.setDepositFrozen(new BigDecimal("10000"));
        request.setStatus(DiagnosticRequest.DiagnosticRequestStatus.PENDING);
        request.setCreatedAt(OffsetDateTime.now());
        request.setUpdatedAt(OffsetDateTime.now());
        Long requestId = diagnosticRequestRepository.save(request).getId();
        commitmentService.freezeCommitment(sellerId, requestId, new BigDecimal("10000"), new BigDecimal("1000"));
        DiagnosticRequest completed = diagnosticRequestRepository.findById(requestId).orElseThrow();
        completed.setStatus(DiagnosticRequest.DiagnosticRequestStatus.COMPLETED);
        diagnosticRequestRepository.save(completed);
        diagnosticRequestService.approveReport(requestId, ADMIN_ID);

        Long vehicleId = vehicleRepository.findByVin(vin).orElseThrow().getId();
        Long commitmentId = commitmentRepository.findByDiagnosticRequestId(requestId).orElseThrow().getId();
        OffsetDateTime now = OffsetDateTime.now();
        Auction auction = new Auction(vehicleId, new BigDecimal("100000"), null, new BigDecimal("300000"),
                new BigDecimal("1000"), now.minusHours(1), now.plusDays(3), ADMIN_ID);
        auction.setRequiredDepositAmount(new BigDecimal("10000"));
        auction.setSellerDiagnosticCommitmentId(commitmentId);
        auction.setStatus(status);
        return auctionRepository.save(auction);
    }

    @Test
    @DisplayName("the resolver finds the vehicle owner, not the administrator who created the auction")
    void resolver_returnsVehicleOwner() {
        Long seller = trustedUser("s09seller");
        Auction auction = auctionOfSeller(seller, AuctionStatus.ACTIVE);

        assertThat(auction.getCreatedBy()).isEqualTo(ADMIN_ID);
        assertThat(auctionSellerResolver.resolveSellerId(auction)).contains(seller);
        assertThat(auctionSellerResolver.isSeller(auction, seller)).isTrue();
        assertThat(auctionSellerResolver.isSeller(auction, ADMIN_ID)).isFalse();
    }

    @Test
    @DisplayName("the seller's bid is refused and nothing is frozen; another trusted buyer can bid")
    void sellerBid_refused_otherBuyerAccepted() {
        Long seller = trustedUser("s09seller");
        Long buyer = trustedUser("s09buyer");
        Auction auction = auctionOfSeller(seller, AuctionStatus.ACTIVE);
        BigDecimal blockedBefore = depositRepository.findByUserId(seller).orElseThrow().getBlockedAmount();

        assertThatThrownBy(() -> bidService.placeBid(auction.getId(), new BigDecimal("101000"), seller))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("собственный");
        assertThat(depositRepository.findByUserId(seller).orElseThrow().getBlockedAmount())
                .as("the refused bid froze no auction deposit (only the diagnostic commitment stays)")
                .isEqualByComparingTo(blockedBefore);

        bidService.placeBid(auction.getId(), new BigDecimal("101000"), buyer);
        assertThat(auctionRepository.findById(auction.getId()).orElseThrow().getCurrentPrice()).isEqualByComparingTo("101000");
    }

    @Test
    @DisplayName("the seller cannot Buy Now their own vehicle")
    void sellerBuyNow_refused() {
        Long seller = trustedUser("s09seller");
        Auction auction = auctionOfSeller(seller, AuctionStatus.CREATED);

        assertThatThrownBy(() -> bidService.buyNow(auction.getId(), seller))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("собственный");
        assertThat(auctionRepository.findById(auction.getId()).orElseThrow().getStatus()).isEqualTo(AuctionStatus.CREATED);
    }
}

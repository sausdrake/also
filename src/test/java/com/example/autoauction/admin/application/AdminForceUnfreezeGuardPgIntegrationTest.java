package com.example.autoauction.admin.application;

import com.example.autoauction.auction.application.AuctionFinalizer;
import com.example.autoauction.auction.application.BidService;
import com.example.autoauction.auction.domain.Auction;
import com.example.autoauction.auction.domain.AuctionStatus;
import com.example.autoauction.auction.domain.port.AuctionParticipantRepository;
import com.example.autoauction.auction.domain.port.AuctionRepository;
import com.example.autoauction.auth.infrastructure.security.WithMockCustomUser;
import com.example.autoauction.deposit.application.DepositService;
import com.example.autoauction.deposit.domain.Deposit;
import com.example.autoauction.deposit.domain.DepositTransaction;
import com.example.autoauction.deposit.domain.port.DepositRepository;
import com.example.autoauction.support.AbstractPostgresIntegrationTest;
import com.example.autoauction.user.domain.Role;
import com.example.autoauction.user.domain.User;
import com.example.autoauction.user.domain.port.RoleRepository;
import com.example.autoauction.user.domain.port.UserRepository;
import com.example.autoauction.vehicle.domain.Vehicle;
import com.example.autoauction.vehicle.domain.VehicleStatus;
import com.example.autoauction.vehicle.domain.VehicleType;
import com.example.autoauction.vehicle.domain.port.VehicleRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * BALANCE-01 on real PostgreSQL: force-unfreeze-all can no longer strip a live lot's deposits (which let an
 * existing participant keep bidding with nothing frozen); finished lots keep the old behaviour, each
 * participant is unfrozen exactly once even when two admins run it at the same time.
 */
@AutoConfigureMockMvc
@DisplayName("BALANCE-01: force-unfreeze-all vs live auctions (real PostgreSQL)")
class AdminForceUnfreezeGuardPgIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final BigDecimal BALANCE = new BigDecimal("50000.00");
    private static final BigDecimal DEPOSIT = new BigDecimal("10000.00");

    @Autowired private AdminAuctionService adminAuctionService;
    @Autowired private BidService bidService;
    @Autowired private AuctionFinalizer auctionFinalizer;
    @Autowired private DepositService depositService;
    @Autowired private DepositRepository depositRepository;
    @Autowired private AuctionRepository auctionRepository;
    @Autowired private AuctionParticipantRepository participantRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private MockMvc mockMvc;
    @Autowired private VehicleRepository vehicleRepository;

    private Long user(boolean withDeposit) {
        Role role = roleRepository.findByName("ROLE_USER")
                .orElseGet(() -> roleRepository.save(new Role(null, "ROLE_USER", "Обычный пользователь")));
        String name = "bal01" + System.nanoTime();
        Long id = userRepository.save(new User(null, name, name + "@test.com", "encoded", true, Set.of(role),
                true, null, null)).getId();
        if (withDeposit) {
            depositService.createDeposit(id, BALANCE);
        }
        return id;
    }

    private Long activeAuctionWithTwoBids(Long seller, Long a, Long b) {
        OffsetDateTime now = OffsetDateTime.now();
        // AUC-NOSALE-01: a cancellation/expiry moves the real vehicle to re-diagnostic
        Vehicle vehicle = new Vehicle("Toyota", "Camry", 2020, ("BAL" + System.nanoTime() + "0000000000").substring(0, 17),
                VehicleType.SEDAN, 9L, "diag");
        vehicle.setStatus(VehicleStatus.IN_AUCTION);
        Long vehicleId = vehicleRepository.save(vehicle).getId();
        Auction auction = new Auction(vehicleId, new BigDecimal("1000000"), null, null, new BigDecimal("10000"),
                now.minusHours(1), now.plusDays(1), seller);
        auction.setRequiredDepositAmount(DEPOSIT);
        auction.start();
        Long id = auctionRepository.save(auction).getId();
        bidService.placeBid(id, new BigDecimal("1010000"), a);
        bidService.placeBid(id, new BigDecimal("1020000"), b);    // b leads
        return id;
    }

    private Deposit deposit(Long userId) {
        return depositRepository.findByUserId(userId).orElseThrow();
    }

    private long ledger(Long userId, DepositTransaction.TransactionType type) {
        return depositRepository.findTransactionsByUserId(userId).stream().filter(t -> t.getType() == type).count();
    }

    private BigDecimal frozenFor(Long userId, Long auctionId) {
        return depositService.getFrozenAmountForAuction(userId, auctionId);
    }

    @Test
    @DisplayName("ADVERSARIAL: ACTIVE -> force-unfreeze refused -> nothing unfrozen -> the participant's next bid still rests on its frozen deposit")
    void activeAuctionKeepsEveryDeposit() {
        Long seller = user(false), a = user(true), b = user(true);
        Long auctionId = activeAuctionWithTwoBids(seller, a, b);

        assertThatThrownBy(() -> adminAuctionService.forceUnfreezeAll(auctionId))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("ACTIVE");

        assertThat(frozenFor(a, auctionId)).isEqualByComparingTo(DEPOSIT);
        assertThat(frozenFor(b, auctionId)).isEqualByComparingTo(DEPOSIT);
        assertThat(ledger(a, DepositTransaction.TransactionType.UNBLOCK)).isZero();
        assertThat(participantRepository.getParticipants(auctionId)).containsExactlyInAnyOrder(a, b);

        bidService.placeBid(auctionId, new BigDecimal("1030000"), a);     // a outbids b again
        assertThat(frozenFor(a, auctionId)).as("still exactly one deposit frozen for a").isEqualByComparingTo(DEPOSIT);
        assertThat(ledger(a, DepositTransaction.TransactionType.BLOCK)).isEqualTo(1);
        Deposit da = deposit(a);
        assertThat(da.getBalance()).isEqualByComparingTo(BALANCE);
        assertThat(da.getBlockedAmount()).isEqualByComparingTo(DEPOSIT);
    }

    @Test
    @DisplayName("CREATED (Buy Now still possible) is refused")
    void createdAuctionRefused() {
        Long seller = user(false);
        OffsetDateTime now = OffsetDateTime.now();
        Auction auction = new Auction(1L, new BigDecimal("1000000"), null, null, new BigDecimal("10000"),
                now.plusHours(3), now.plusDays(2), seller);
        Long id = auctionRepository.save(auction).getId();

        assertThatThrownBy(() -> adminAuctionService.forceUnfreezeAll(id))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("CREATED");
    }

    @RepeatedTest(3)
    @DisplayName("finished lot, two admins at once, then once more: each non-winner unfrozen exactly once, the winner kept")
    void finishedLotConcurrentAndRepeated() throws Exception {
        Long seller = user(false), a = user(true), b = user(true), c = user(true);
        Long auctionId = activeAuctionWithTwoBids(seller, a, b);
        bidService.placeBid(auctionId, new BigDecimal("1030000"), c);   // c leads; a and b are non-winners
        Auction auction = auctionRepository.findById(auctionId).orElseThrow();
        auction.setStatus(AuctionStatus.COMPLETED);                      // legacy finished state, participants kept
        auctionRepository.save(auction);

        CountDownLatch go = new CountDownLatch(1);
        List<Thread> threads = new ArrayList<>();
        List<Throwable> errors = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            Thread t = new Thread(() -> {
                try { go.await(); adminAuctionService.forceUnfreezeAll(auctionId); }
                catch (Throwable e) { synchronized (errors) { errors.add(e); } }
            });
            t.start(); threads.add(t);
        }
        go.countDown();
        for (Thread t : threads) t.join(20_000);
        assertThat(errors).isEmpty();
        adminAuctionService.forceUnfreezeAll(auctionId);                  // repeated invocation

        for (Long loser : List.of(a, b)) {
            assertThat(ledger(loser, DepositTransaction.TransactionType.UNBLOCK)).as("one UNBLOCK for " + loser).isEqualTo(1);
            assertThat(frozenFor(loser, auctionId)).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(deposit(loser).getBlockedAmount()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(deposit(loser).getBalance()).isEqualByComparingTo(BALANCE);
        }
        assertThat(frozenFor(c, auctionId)).isEqualByComparingTo(DEPOSIT);
        assertThat(ledger(c, DepositTransaction.TransactionType.UNBLOCK)).isZero();
    }

    @Test
    @DisplayName("WAITING_FOR_PAYMENT and CANCELLED keep the existing policy (allowed; the winner's deposit is never touched)")
    void waitingForPaymentAndCancelledAllowed() {
        Long seller = user(false), a = user(true), b = user(true);
        Long waiting = activeAuctionWithTwoBids(seller, a, b);
        auctionFinalizer.finalizeAuctionById(waiting);
        assertThat(auctionRepository.findById(waiting).orElseThrow().getStatus()).isEqualTo(AuctionStatus.WAITING_FOR_PAYMENT);
        adminAuctionService.forceUnfreezeAll(waiting);
        assertThat(frozenFor(b, waiting)).as("winner keeps the deposit").isEqualByComparingTo(DEPOSIT);
        assertThat(ledger(a, DepositTransaction.TransactionType.UNBLOCK)).as("loser unfrozen once, by the finalizer").isEqualTo(1);

        Long a2 = user(true), b2 = user(true);
        Long cancelled = activeAuctionWithTwoBids(seller, a2, b2);
        adminAuctionService.cancelAuction(cancelled, 1L);
        assertThat(auctionRepository.findById(cancelled).orElseThrow().getStatus()).isEqualTo(AuctionStatus.CANCELLED);
        adminAuctionService.forceUnfreezeAll(cancelled);
        for (Long u : List.of(a2, b2)) {
            assertThat(ledger(u, DepositTransaction.TransactionType.UNBLOCK)).isEqualTo(1);
            assertThat(deposit(u).getBlockedAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        }
    }

    @Test
    @WithMockCustomUser(userId = 1L, username = "admin", roles = {"ADMIN"})
    @DisplayName("SECURITY: direct admin API call on an ACTIVE lot -> 409, deposits intact")
    void adminApiOnActiveLotIs409() throws Exception {
        Long seller = user(false), a = user(true), b = user(true);
        Long auctionId = activeAuctionWithTwoBids(seller, a, b);

        mockMvc.perform(post("/api/v1/admin/auctions/" + auctionId + "/force-unfreeze-all")).andExpect(status().isConflict());

        assertThat(frozenFor(a, auctionId)).isEqualByComparingTo(DEPOSIT);
        assertThat(ledger(a, DepositTransaction.TransactionType.UNBLOCK)).isZero();
    }

    @Test
    @WithMockCustomUser(userId = 2L, username = "user", roles = {"USER"})
    @DisplayName("SECURITY: a non-admin cannot call it at all (403)")
    void nonAdminIs403() throws Exception {
        mockMvc.perform(post("/api/v1/admin/auctions/1/force-unfreeze-all")).andExpect(status().isForbidden());
    }
}

package com.example.autoauction.auction.application;

import com.example.autoauction.support.TestVehicles;
import com.example.autoauction.vehicle.domain.port.VehicleRepository;
import com.example.autoauction.auction.domain.Auction;
import com.example.autoauction.auction.domain.AuctionStatus;
import com.example.autoauction.auction.domain.port.AuctionRepository;
import com.example.autoauction.auction.domain.port.PaymentDeadlineHistoryRepository;
import com.example.autoauction.support.AbstractPostgresIntegrationTest;
import com.example.autoauction.user.domain.Role;
import com.example.autoauction.user.domain.User;
import com.example.autoauction.user.domain.port.RoleRepository;
import com.example.autoauction.user.domain.port.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.SpyBean;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

/**
 * Slice 1B: proves, against real PostgreSQL, that the WAITING_FOR_PAYMENT transition +
 * paymentDeadline assignment + INITIAL history row are a genuine single physical transaction -
 * not merely a Mockito call-order proxy. {@code @SpyBean PaymentDeadlineHistoryRepository}
 * wraps the real bean and is stubbed to fail on {@code save}, forcing the exception AuctionFinalizer
 * itself never catches; the test then re-reads the Auction from the database (not the in-memory
 * object) to prove nothing committed.
 */
@DisplayName("Slice 1B: WAITING_FOR_PAYMENT + paymentDeadline + INITIAL history atomicity (real PostgreSQL rollback proof)")
class PaymentDeadlineAtomicityPgIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired private VehicleRepository vehicleRepository;

    @Autowired private AuctionFinalizer auctionFinalizer;
    @Autowired private AuctionRepository auctionRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private com.example.autoauction.deposit.application.DepositService depositService;

    @SpyBean private PaymentDeadlineHistoryRepository paymentDeadlineHistoryRepository;

    @BeforeEach
    void resetSpy() {
        reset(paymentDeadlineHistoryRepository);
    }

    @AfterEach
    void resetSpyAfter() {
        reset(paymentDeadlineHistoryRepository);
    }

    /**
     * A real, persisted winner - required so {@code AuctionFinalizer}'s own win-notification
     * call (a genuine, unrelated DB insert with a real FK to {@code users}) succeeds cleanly.
     * Without this, that insert fails first on a synthetic id, which PostgreSQL then treats as
     * an aborted transaction for every subsequent statement - masking the one failure this test
     * actually intends to inject (the history save) behind an unrelated FK violation.
     */
    private Long newRealUserId() {
        Role role = roleRepository.findByName("ROLE_USER")
                .orElseGet(() -> roleRepository.save(new Role(null, "ROLE_USER", "Обычный пользователь")));
        User user = new User(null, "atomic" + System.nanoTime(), "atomic" + System.nanoTime() + "@test.com",
                "encoded", true, Set.of(role));
        return userRepository.save(user).getId();
    }

    /**
     * A real, persisted winner WITH a Deposit row - required so both
     * {@code AuctionFinalizer.markWinnerSafely} (needs an existing Deposit to mark as winner) and
     * its win-notification insert (a genuine FK to {@code users}) succeed cleanly. Without this,
     * an incidental failure on a synthetic id would abort the PostgreSQL transaction for every
     * subsequent statement, masking the one failure this test actually intends to inject (the
     * history save) behind an unrelated error.
     */
    private Long newRealWinnerId() {
        Long id = newRealUserId();
        depositService.createDeposit(id, new BigDecimal("1000000"));
        return id;
    }

    private Long newActiveAuctionWithWinner(Long winnerId, Long sellerId) {
        OffsetDateTime now = OffsetDateTime.now();
        Auction auction = new Auction(1L, new BigDecimal("1000000"), null, null,
                new BigDecimal("10000"), now.minusHours(2), now.plusHours(2), sellerId);
        auction.setStatus(AuctionStatus.ACTIVE);
        auction.setTotalBids(1);
        auction.setCurrentPrice(new BigDecimal("1010000"));
        auction.setWinnerId(winnerId);
        auction.setWinningBid(new BigDecimal("1010000"));
        return auctionRepository.save(auction).getId();
    }

    @Test
    @DisplayName("finalizeAuction: если сохранение INITIAL history падает, откатывается и WAITING_FOR_PAYMENT, и paymentDeadline")
    void finalizeAuction_HistorySaveFails_RollsBackAuctionUpdateToo() {
        Long winnerId = newRealWinnerId();
        Long sellerId = newRealUserId();
        Long auctionId = newActiveAuctionWithWinner(winnerId, sellerId);
        Auction auction = auctionRepository.findById(auctionId).orElseThrow();

        doThrow(new RuntimeException("injected: history insert failed"))
                .when(paymentDeadlineHistoryRepository).save(any());

        assertThatThrownBy(() -> auctionFinalizer.finalizeAuction(auction))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("injected: history insert failed");

        // Fresh read from the database, NOT the in-memory `auction` object - proves the
        // transaction genuinely rolled back, not just that the in-memory mutation was discarded.
        Auction reloaded = auctionRepository.findById(auctionId).orElseThrow();
        assertThat(reloaded.getStatus())
                .as("status must remain ACTIVE - the WAITING_FOR_PAYMENT transition must not have committed")
                .isEqualTo(AuctionStatus.ACTIVE);
        assertThat(reloaded.getPaymentDeadline())
                .as("paymentDeadline must remain null - its assignment must not have committed either")
                .isNull();
        assertThat(reloaded.getWinnerId())
                .as("winnerId must remain at its pre-finalization value - unchanged by the rolled-back attempt")
                .isEqualTo(winnerId);
    }

    @Test
    @DisplayName("finalizeWithBuyNow: если сохранение INITIAL history падает, откатывается вся транзакция")
    void finalizeWithBuyNow_HistorySaveFails_RollsBackAuctionUpdateToo() {
        Long winnerId = newRealWinnerId();
        OffsetDateTime now = OffsetDateTime.now();
        Auction auction = new Auction(TestVehicles.approved(vehicleRepository), new BigDecimal("1000000"), null, new BigDecimal("1500000"),
                new BigDecimal("10000"), now.minusHours(2), now.plusHours(2), 10L);
        auction.setStatus(AuctionStatus.ACTIVE);
        Long auctionId = auctionRepository.save(auction).getId();
        Auction reloadedBefore = auctionRepository.findById(auctionId).orElseThrow();

        doThrow(new RuntimeException("injected: history insert failed"))
                .when(paymentDeadlineHistoryRepository).save(any());

        assertThatThrownBy(() -> auctionFinalizer.finalizeWithBuyNow(reloadedBefore, winnerId, new BigDecimal("1500000")))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("injected: history insert failed");

        Auction reloaded = auctionRepository.findById(auctionId).orElseThrow();
        assertThat(reloaded.getStatus())
                .as("status must remain ACTIVE - the Buy Now WAITING_FOR_PAYMENT transition must not have committed")
                .isEqualTo(AuctionStatus.ACTIVE);
        assertThat(reloaded.getPaymentDeadline()).isNull();
        assertThat(reloaded.getWinnerId()).isNull();
    }
}

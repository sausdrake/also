package com.example.autoauction.auction.application;

import com.example.autoauction.auction.domain.Auction;
import com.example.autoauction.auction.domain.AuctionStatus;
import com.example.autoauction.auction.domain.port.AuctionRepository;
import com.example.autoauction.deposit.application.DepositService;
import com.example.autoauction.deposit.domain.Deposit;
import com.example.autoauction.deposit.domain.DepositTransaction;
import com.example.autoauction.deposit.domain.DepositTransaction.TransactionType;
import com.example.autoauction.deposit.domain.port.DepositRepository;
import com.example.autoauction.support.AbstractPostgresIntegrationTest;
import com.example.autoauction.user.domain.Role;
import com.example.autoauction.user.domain.User;
import com.example.autoauction.user.domain.port.RoleRepository;
import com.example.autoauction.user.domain.port.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WAVE 04 financial invariants, real PostgreSQL: an auction that ends WITHOUT a winner (no bids, or the reserve not
 * met) releases every participant's participation deposit exactly once, a repeated finalization changes nothing, the
 * balance never moves, and the ledger reconciles with the deposit: blocked = sum(BLOCK) - sum(UNBLOCK).
 */
@DisplayName("WAVE 04: no-winner auctions release every deposit exactly once (real PostgreSQL)")
class NoWinnerAuctionFinancialPgIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final BigDecimal BALANCE = new BigDecimal("50000.00");
    private static final BigDecimal DEPOSIT = new BigDecimal("10000.00");

    @Autowired private BidService bidService;
    @Autowired private AuctionFinalizer auctionFinalizer;
    @Autowired private DepositService depositService;
    @Autowired private DepositRepository depositRepository;
    @Autowired private AuctionRepository auctionRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;

    private Long user(boolean withDeposit) {
        Role role = roleRepository.findByName("ROLE_USER")
                .orElseGet(() -> roleRepository.save(new Role(null, "ROLE_USER", "Обычный пользователь")));
        String name = "nowin" + System.nanoTime();
        Long id = userRepository.save(new User(null, name, name + "@test.com", "encoded", true, Set.of(role),
                true, null, null)).getId();
        if (withDeposit) {
            depositService.createDeposit(id, BALANCE);
        }
        return id;
    }

    private Long activeAuction(Long seller, BigDecimal reserve) {
        OffsetDateTime now = OffsetDateTime.now();
        Auction auction = new Auction(1L, new BigDecimal("1000000"), reserve, null, new BigDecimal("10000"),
                now.minusHours(1), now.plusDays(1), seller);
        auction.setRequiredDepositAmount(DEPOSIT);
        auction.start();
        return auctionRepository.save(auction).getId();
    }

    private Deposit deposit(Long userId) {
        return depositRepository.findByUserId(userId).orElseThrow();
    }

    private List<DepositTransaction> ledger(Long userId, TransactionType type) {
        return depositRepository.findTransactionsByUserId(userId).stream().filter(t -> t.getType() == type).toList();
    }

    private static BigDecimal sum(List<DepositTransaction> txs) {
        return txs.stream().map(t -> t.getAmount().abs()).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** Balance untouched, nothing blocked, the ledger agrees: one BLOCK, one UNBLOCK of the same amount. */
    private void assertReleasedOnce(Long u, Long auctionId) {
        Deposit d = deposit(u);
        assertThat(d.getBalance()).as("balance of %s never moves", u).isEqualByComparingTo(BALANCE);
        assertThat(d.getBlockedAmount()).as("nothing left blocked for %s", u).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(ledger(u, TransactionType.BLOCK)).as("one BLOCK for %s", u).hasSize(1);
        assertThat(ledger(u, TransactionType.UNBLOCK)).as("one UNBLOCK for %s", u).hasSize(1);
        assertThat(sum(ledger(u, TransactionType.BLOCK)).subtract(sum(ledger(u, TransactionType.UNBLOCK))))
                .as("ledger reconciles with blocked amount for %s", u).isEqualByComparingTo(d.getBlockedAmount());
        assertThat(depositService.getFrozenAmountForAuction(u, auctionId)).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(ledger(u, TransactionType.CHARGE)).as("no charge for %s", u).isEmpty();
    }

    @Test
    @DisplayName("reserve not met: EXPIRED, both bidders released exactly once, a repeated finalization changes nothing")
    void reserveNotMet() {
        Long seller = user(false), a = user(true), b = user(true);
        Long auctionId = activeAuction(seller, new BigDecimal("2000000"));
        bidService.placeBid(auctionId, new BigDecimal("1010000"), a);
        bidService.placeBid(auctionId, new BigDecimal("1020000"), b);
        assertThat(deposit(a).getBlockedAmount()).isEqualByComparingTo(DEPOSIT);

        auctionFinalizer.finalizeAuctionById(auctionId);
        auctionFinalizer.finalizeAuctionById(auctionId);

        assertThat(auctionRepository.findById(auctionId).orElseThrow().getStatus()).isEqualTo(AuctionStatus.EXPIRED);
        for (Long u : List.of(a, b)) {
            assertReleasedOnce(u, auctionId);
        }
    }

    @Test
    @DisplayName("no bids: EXPIRED, no deposit was ever frozen, the ledger holds only the initial credit")
    void noBids() {
        Long seller = user(false), watcher = user(true);
        Long auctionId = activeAuction(seller, null);

        auctionFinalizer.finalizeAuctionById(auctionId);

        assertThat(auctionRepository.findById(auctionId).orElseThrow().getStatus()).isEqualTo(AuctionStatus.EXPIRED);
        assertThat(deposit(watcher).getBalance()).isEqualByComparingTo(BALANCE);
        assertThat(deposit(watcher).getBlockedAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(ledger(watcher, TransactionType.BLOCK)).isEmpty();
        assertThat(ledger(watcher, TransactionType.UNBLOCK)).isEmpty();
    }
}

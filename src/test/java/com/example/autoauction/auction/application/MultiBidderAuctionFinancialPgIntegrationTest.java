package com.example.autoauction.auction.application;

import com.example.autoauction.auction.domain.Auction;
import com.example.autoauction.auction.domain.AuctionStatus;
import com.example.autoauction.auction.domain.BidStepPolicy;
import com.example.autoauction.auction.domain.port.AuctionRepository;
import com.example.autoauction.deposit.application.DepositService;
import com.example.autoauction.deposit.domain.Deposit;
import com.example.autoauction.deposit.domain.DepositTransaction;
import com.example.autoauction.deposit.domain.DepositTransaction.TransactionType;
import com.example.autoauction.deposit.domain.port.DepositRepository;
import com.example.autoauction.identity.domain.DocumentType;
import com.example.autoauction.identity.domain.IdentityDocument;
import com.example.autoauction.identity.domain.IdentityVerification;
import com.example.autoauction.identity.domain.port.IdentityDocumentRepository;
import com.example.autoauction.identity.domain.port.IdentityVerificationRepository;
import com.example.autoauction.support.AbstractPostgresIntegrationTest;
import com.example.autoauction.user.domain.Role;
import com.example.autoauction.user.domain.User;
import com.example.autoauction.user.domain.port.RoleRepository;
import com.example.autoauction.user.domain.port.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * WAVE D (MVP certification), real PostgreSQL: six bidders race the same auction - first all at the same minimum,
 * then each re-bidding from the price it last read (most of those bids are stale by the time they land). Then the
 * auction is finalized (a second finalization is refused). Money invariants checked at the end:
 * <ul>
 *   <li>exactly one bid accepted at the shared minimum; the auction price is the highest accepted bid and its leader
 *       is the bidder who placed it;</li>
 *   <li>every participant was frozen exactly once (one BLOCK, however many bids), a refused-only bidder never;</li>
 *   <li>WAITING_FOR_PAYMENT with a payment deadline; the winner keeps one deposit frozen, every other participant
 *       is released exactly once (one UNBLOCK), a refused second finalization changes nothing;</li>
 *   <li>no balance moves (nothing is charged here), blocked = sum(BLOCK) - sum(UNBLOCK) = sum(deposit_frozen).</li>
 * </ul>
 */
@DisplayName("WAVE D: concurrent bidders, stale bids, winner -> WAITING_FOR_PAYMENT, losers released once (real PostgreSQL)")
class MultiBidderAuctionFinancialPgIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final int BIDDERS = 6;
    private static final int ROUNDS = 4;
    private static final BigDecimal BALANCE = new BigDecimal("50000.00");
    private static final BigDecimal DEPOSIT = new BigDecimal("10000.00");
    private static final BigDecimal START = new BigDecimal("1000000");

    @Autowired private BidService bidService;
    @Autowired private AuctionFinalizer auctionFinalizer;
    @Autowired private AuctionRepository auctionRepository;
    @Autowired private DepositService depositService;
    @Autowired private DepositRepository depositRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private IdentityDocumentRepository identityDocumentRepository;
    @Autowired private IdentityVerificationRepository identityVerificationRepository;
    @Autowired private JdbcTemplate jdbc;

    private record Accepted(Long bidder, BigDecimal amount) {}

    private Role role() {
        return roleRepository.findByName("ROLE_USER")
                .orElseGet(() -> roleRepository.save(new Role(null, "ROLE_USER", "Обычный пользователь")));
    }

    /** A bidder who passes every gate: trusted, KYC verified, enough balance for the deposit. */
    private Long bidder() {
        String name = "multi" + System.nanoTime();
        Long id = userRepository.save(new User(null, name, name + "@test.com", "encoded", true, Set.of(role()),
                true, 1L, OffsetDateTime.now())).getId();
        depositService.createDeposit(id, BALANCE);
        OffsetDateTime now = OffsetDateTime.now();
        Long doc = identityDocumentRepository.save(new IdentityDocument(
                "identity-documents/" + UUID.randomUUID(), id, "image/jpeg", 1000L, now)).getId();
        IdentityVerification iv = IdentityVerification.firstSubmission(id, "KG", DocumentType.NATIONAL_ID,
                "K" + System.nanoTime(), doc, doc, doc, now);
        iv.approve(now);
        identityVerificationRepository.save(iv);
        return id;
    }

    private Long activeAuction() {
        String name = "multis" + System.nanoTime();
        Long seller = userRepository.save(new User(null, name, name + "@test.com", "encoded", true, Set.of(role()))).getId();
        OffsetDateTime now = OffsetDateTime.now();
        Auction a = new Auction(1L, START, null, null, BidStepPolicy.minimumBidStep(START),
                now.minusHours(1), now.plusDays(1), seller);
        a.setRequiredDepositAmount(DEPOSIT);
        a.start();
        return auctionRepository.save(a).getId();
    }

    private BigDecimal price(Long auctionId) {
        return jdbc.queryForObject("select current_price from auctions where id = ?", BigDecimal.class, auctionId);
    }

    private List<DepositTransaction> ledger(Long userId, TransactionType type) {
        return depositRepository.findTransactionsByUserId(userId).stream().filter(t -> t.getType() == type).toList();
    }

    private static BigDecimal sum(List<DepositTransaction> txs) {
        return txs.stream().map(t -> t.getAmount().abs()).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** Runs one task per bidder, all released at once; returns the bids that were accepted. */
    private List<Accepted> race(List<Long> bidders, java.util.function.Function<Long, List<BigDecimal>> plan,
                                Long auctionId) throws InterruptedException {
        List<Accepted> accepted = Collections.synchronizedList(new ArrayList<>());
        Map<Long, Throwable> unexpected = new ConcurrentHashMap<>();
        CountDownLatch go = new CountDownLatch(1);
        List<Thread> threads = new ArrayList<>();
        for (Long u : bidders) {
            Thread t = new Thread(() -> {
                try {
                    go.await();
                    for (BigDecimal amount : plan.apply(u)) {
                        BigDecimal toSend = amount != null ? amount
                                : price(auctionId).add(BidStepPolicy.minimumBidStep(START)).setScale(2, java.math.RoundingMode.UP);
                        try {
                            bidService.placeBid(auctionId, toSend, u);
                            accepted.add(new Accepted(u, toSend));
                        } catch (IllegalArgumentException | IllegalStateException refused) {
                            // stale / outbid / already leading - an ordinary refusal
                        }
                    }
                } catch (Throwable e) {
                    unexpected.put(u, e);
                }
            });
            t.start();
            threads.add(t);
        }
        go.countDown();
        for (Thread t : threads) {
            t.join(60_000);
        }
        assertThat(unexpected).as("no bid ends in an unexpected error").isEmpty();
        return accepted;
    }

    @RepeatedTest(2)
    @DisplayName("6 bidders: one winner at the shared minimum, stale re-bids refused, each frozen once, losers released once")
    void concurrentBiddersThenFinalize() throws Exception {
        Long auctionId = activeAuction();
        List<Long> bidders = new ArrayList<>();
        for (int i = 0; i < BIDDERS; i++) {
            bidders.add(bidder());
        }
        BigDecimal minimum = START.add(BidStepPolicy.minimumBidStep(START)).setScale(2, java.math.RoundingMode.UP);

        List<Accepted> first = race(bidders, u -> List.of(minimum), auctionId);
        assertThat(first).as("exactly one bid at the shared minimum").hasSize(1);

        List<BigDecimal> fromLastSeenPrice = new ArrayList<>(Collections.nCopies(ROUNDS, (BigDecimal) null));
        List<Accepted> later = race(bidders, u -> fromLastSeenPrice, auctionId);

        List<Accepted> all = new ArrayList<>(first);
        all.addAll(later);
        Accepted top = all.stream().max(Comparator.comparing(Accepted::amount)).orElseThrow();
        Auction live = auctionRepository.findById(auctionId).orElseThrow();
        assertThat(live.getTotalBids()).as("every accepted bid counted, no refused one").isEqualTo(all.size());
        assertThat(live.getCurrentPrice()).isEqualByComparingTo(top.amount());
        assertThat(live.getWinnerId()).as("the leader placed the highest bid").isEqualTo(top.bidder());
        assertThat(all.stream().map(Accepted::amount).distinct().count()).as("no two bids accepted at one price")
                .isEqualTo(all.size());

        Set<Long> participants = all.stream().map(Accepted::bidder).collect(java.util.stream.Collectors.toSet());
        for (Long u : bidders) {
            int blocks = participants.contains(u) ? 1 : 0;
            assertThat(ledger(u, TransactionType.BLOCK)).as("BLOCK rows of %s", u).hasSize(blocks);
            assertThat(depositService.getFrozenAmountForAuction(u, auctionId))
                    .isEqualByComparingTo(participants.contains(u) ? DEPOSIT : BigDecimal.ZERO);
        }

        assertThat(auctionFinalizer.finalizeAuctionById(auctionId)).isTrue();
        // WAITING_FOR_PAYMENT is not "finished"; a second finalization is refused by the immutable sale-method
        // snapshot and rolls back (production callers only pass ACTIVE auctions) - the checks below prove it moved nothing
        assertThatThrownBy(() -> auctionFinalizer.finalizeAuctionById(auctionId)).isInstanceOf(IllegalStateException.class);

        Auction done = auctionRepository.findById(auctionId).orElseThrow();
        assertThat(done.getStatus()).isEqualTo(AuctionStatus.WAITING_FOR_PAYMENT);
        assertThat(done.getWinnerId()).isEqualTo(top.bidder());
        assertThat(done.getWinningBid()).isEqualByComparingTo(top.amount());
        assertThat(done.getPaymentDeadline()).isNotNull();

        for (Long u : bidders) {
            boolean winner = u.equals(top.bidder());
            boolean participated = participants.contains(u);
            Deposit d = depositRepository.findByUserId(u).orElseThrow();
            assertThat(d.getBalance()).as("balance of %s never moves", u).isEqualByComparingTo(BALANCE);
            assertThat(d.getBlockedAmount()).as("blocked of %s", u).isEqualByComparingTo(winner ? DEPOSIT : BigDecimal.ZERO);
            assertThat(ledger(u, TransactionType.UNBLOCK)).as("UNBLOCK rows of %s", u)
                    .hasSize(participated && !winner ? 1 : 0);
            assertThat(ledger(u, TransactionType.CHARGE)).isEmpty();
            assertThat(sum(ledger(u, TransactionType.BLOCK)).subtract(sum(ledger(u, TransactionType.UNBLOCK))))
                    .as("ledger of %s reconciles with blocked amount", u).isEqualByComparingTo(d.getBlockedAmount());
            BigDecimal frozenRows = jdbc.queryForObject(
                    "select coalesce(sum(f.amount), 0) from deposit_frozen f join deposits d on d.id = f.deposit_id where d.user_id = ?",
                    BigDecimal.class, u);
            assertThat(frozenRows).as("deposit_frozen of %s agrees", u).isEqualByComparingTo(d.getBlockedAmount());
        }
    }
}

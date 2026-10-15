package com.example.autoauction.deposit.application;

import com.example.autoauction.auction.domain.Auction;
import com.example.autoauction.auction.domain.port.AuctionRepository;
import com.example.autoauction.deposit.domain.Deposit;
import com.example.autoauction.deposit.domain.DepositTransaction;
import com.example.autoauction.deposit.domain.WithdrawalRequest;
import com.example.autoauction.deposit.domain.WithdrawalRequestStatus;
import com.example.autoauction.deposit.domain.port.DepositRepository;
import com.example.autoauction.deposit.domain.port.WithdrawalRequestRepository;
import com.example.autoauction.support.AbstractPostgresIntegrationTest;
import com.example.autoauction.user.domain.Role;
import com.example.autoauction.user.domain.User;
import com.example.autoauction.user.domain.port.RoleRepository;
import com.example.autoauction.user.domain.port.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F6 on real PostgreSQL: two racing operations on the same money never both take effect.
 * Each scenario starts both threads from one barrier and then checks, with fresh reads only, the
 * balance, the blocked amount, the ledger and the request status. Repeated, because a race is
 * only convincing when it survives many interleavings.
 */
@DisplayName("F6: withdrawal requests under concurrency (PostgreSQL)")
// Skipped (not failed) where no Docker daemon is reachable, e.g. the network-less verification
// container; runs wherever Testcontainers can start PostgreSQL.
@org.testcontainers.junit.jupiter.Testcontainers(disabledWithoutDocker = true)
class WithdrawalRequestConcurrencyPgIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final long WAIT_SECONDS = 20;

    @Autowired private WithdrawalRequestService withdrawalRequestService;
    @Autowired private WithdrawalRequestRepository withdrawalRequestRepository;
    @Autowired private DepositService depositService;
    @Autowired private DepositRepository depositRepository;
    @Autowired private AuctionRepository auctionRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;

    private Long user(String balance) {
        Role role = roleRepository.findByName("ROLE_USER")
                .orElseGet(() -> roleRepository.save(new Role(null, "ROLE_USER", "Обычный пользователь")));
        String name = "wdpg" + System.nanoTime();
        Long id = userRepository.save(new User(null, name, name + "@test.com", "encoded", true, Set.of(role))).getId();
        depositService.createDeposit(id, new BigDecimal(balance));
        return id;
    }

    private Long admin() {
        return user("0");
    }

    private Long auction() {
        OffsetDateTime now = OffsetDateTime.now();
        Auction auction = new Auction(Math.abs(System.nanoTime() % 1_000_000L), new BigDecimal("100000.00"), null,
                new BigDecimal("500000.00"), new BigDecimal("1000.00"), now.minusHours(1), now.plusDays(7),
                900_000L + (System.nanoTime() % 100_000L));
        return auctionRepository.save(auction).getId();
    }

    private Deposit deposit(Long userId) {
        return depositRepository.findByUserId(userId).orElseThrow();
    }

    private long ledgerCount(Long userId, DepositTransaction.TransactionType type) {
        return depositRepository.findTransactionsByUserId(userId).stream().filter(t -> t.getType() == type).count();
    }

    private static void race(Runnable a, Runnable b) throws InterruptedException {
        CyclicBarrier barrier = new CyclicBarrier(2);
        Thread ta = new Thread(() -> { try { barrier.await(WAIT_SECONDS, TimeUnit.SECONDS); a.run(); } catch (Exception e) { throw new RuntimeException(e); } });
        Thread tb = new Thread(() -> { try { barrier.await(WAIT_SECONDS, TimeUnit.SECONDS); b.run(); } catch (Exception e) { throw new RuntimeException(e); } });
        ta.start();
        tb.start();
        ta.join(WAIT_SECONDS * 1000);
        tb.join(WAIT_SECONDS * 1000);
        assertThat(ta.isAlive() || tb.isAlive()).as("no deadlock").isFalse();
    }

    private static Runnable capture(Runnable action, AtomicReference<Throwable> error) {
        return () -> { try { action.run(); } catch (Throwable t) { error.set(t); } };
    }

    @RepeatedTest(5)
    @DisplayName("two withdrawals of the whole balance: exactly one request, one debit, balance 0")
    void twoWithdrawalsOfWholeBalance() throws InterruptedException {
        Long userId = user("1000");
        AtomicReference<Throwable> e1 = new AtomicReference<>();
        AtomicReference<Throwable> e2 = new AtomicReference<>();

        race(capture(() -> withdrawalRequestService.create(userId, new BigDecimal("1000"), "pg-a-" + System.nanoTime()), e1),
             capture(() -> withdrawalRequestService.create(userId, new BigDecimal("1000"), "pg-b-" + System.nanoTime()), e2));

        assertThat(e1.get() == null ^ e2.get() == null).as("exactly one succeeds: %s / %s", e1.get(), e2.get()).isTrue();
        assertThat(deposit(userId).getBalance()).isEqualByComparingTo("0");
        assertThat(deposit(userId).getBlockedAmount()).isEqualByComparingTo("0");
        assertThat(ledgerCount(userId, DepositTransaction.TransactionType.WITHDRAWAL)).isEqualTo(1);
        assertThat(withdrawalRequestService.listMine(userId)).hasSize(1);
    }

    @RepeatedTest(5)
    @DisplayName("the same request sent twice at once: one request, one debit")
    void sameKeyTwiceAtOnce() throws InterruptedException {
        Long userId = user("1000");
        String key = "pg-same-" + System.nanoTime();
        AtomicReference<WithdrawalRequest> r1 = new AtomicReference<>();
        AtomicReference<WithdrawalRequest> r2 = new AtomicReference<>();

        race(() -> r1.set(withdrawalRequestService.create(userId, new BigDecimal("300"), key)),
             () -> r2.set(withdrawalRequestService.create(userId, new BigDecimal("300"), key)));

        assertThat(r1.get().getId()).isEqualTo(r2.get().getId());
        assertThat(deposit(userId).getBalance()).isEqualByComparingTo("700");
        assertThat(ledgerCount(userId, DepositTransaction.TransactionType.WITHDRAWAL)).isEqualTo(1);
    }

    @RepeatedTest(5)
    @DisplayName("withdrawal vs auction freeze over the same money: never both, available never negative")
    void withdrawalVsFreeze() throws InterruptedException {
        Long userId = user("1000");
        Long auctionId = auction();
        AtomicReference<Throwable> withdrawErr = new AtomicReference<>();
        AtomicReference<Throwable> freezeErr = new AtomicReference<>();

        race(capture(() -> withdrawalRequestService.create(userId, new BigDecimal("800"), "pg-wf-" + System.nanoTime()), withdrawErr),
             capture(() -> depositService.freezeForAuction(userId, auctionId, new BigDecimal("800")), freezeErr));

        assertThat(withdrawErr.get() == null && freezeErr.get() == null).as("both cannot take the same money").isFalse();
        Deposit d = deposit(userId);
        assertThat(d.getAvailableBalance()).isNotNegative();
        if (withdrawErr.get() == null) {
            assertThat(d.getBalance()).isEqualByComparingTo("200");
            assertThat(withdrawalRequestService.listMine(userId)).hasSize(1);
        } else {
            assertThat(d.getBalance()).isEqualByComparingTo("1000");
            assertThat(withdrawalRequestService.listMine(userId)).isEmpty();
            assertThat(ledgerCount(userId, DepositTransaction.TransactionType.WITHDRAWAL)).isZero();
        }
        assertThat(d.getBlockedAmount()).isEqualByComparingTo(freezeErr.get() == null ? "800" : "0");
    }

    @RepeatedTest(5)
    @DisplayName("withdrawal vs seller charge over the same money: never both")
    void withdrawalVsCharge() throws InterruptedException {
        Long userId = user("1000");
        AtomicReference<Throwable> withdrawErr = new AtomicReference<>();
        AtomicReference<Throwable> chargeErr = new AtomicReference<>();

        race(capture(() -> withdrawalRequestService.create(userId, new BigDecimal("800"), "pg-wc-" + System.nanoTime()), withdrawErr),
             capture(() -> depositService.chargeRediagnosticFee(userId, 1L, new BigDecimal("800")), chargeErr));

        assertThat(withdrawErr.get() == null ^ chargeErr.get() == null).as("exactly one: %s / %s", withdrawErr.get(), chargeErr.get()).isTrue();
        assertThat(deposit(userId).getBalance()).isEqualByComparingTo("200");
        assertThat(withdrawalRequestService.listMine(userId)).hasSize(withdrawErr.get() == null ? 1 : 0);
    }

    @RepeatedTest(5)
    @DisplayName("two admins reject the same request at once: one rejection, one return")
    void doubleRejectAtOnce() throws InterruptedException {
        Long userId = user("1000");
        Long adminId = admin();
        Long id = withdrawalRequestService.create(userId, new BigDecimal("300"), "pg-rr-" + System.nanoTime()).getId();
        AtomicReference<Throwable> e1 = new AtomicReference<>();
        AtomicReference<Throwable> e2 = new AtomicReference<>();

        race(capture(() -> withdrawalRequestService.reject(id, adminId, "first"), e1),
             capture(() -> withdrawalRequestService.reject(id, adminId, "second"), e2));

        assertThat(e1.get() == null ^ e2.get() == null).as("exactly one: %s / %s", e1.get(), e2.get()).isTrue();
        assertThat(deposit(userId).getBalance()).isEqualByComparingTo("1000");
        assertThat(ledgerCount(userId, DepositTransaction.TransactionType.WITHDRAWAL_RETURN)).isEqualTo(1);
        assertThat(withdrawalRequestRepository.findById(id).orElseThrow().getStatus()).isEqualTo(WithdrawalRequestStatus.REJECTED);
    }

    @RepeatedTest(5)
    @DisplayName("reject vs take-into-work at once: one wins, the money matches the winner")
    void rejectVsProcessing() throws InterruptedException {
        Long userId = user("1000");
        Long adminId = admin();
        Long id = withdrawalRequestService.create(userId, new BigDecimal("300"), "pg-rp-" + System.nanoTime()).getId();
        AtomicReference<Throwable> rejectErr = new AtomicReference<>();
        AtomicReference<Throwable> processErr = new AtomicReference<>();

        race(capture(() -> withdrawalRequestService.reject(id, adminId, "no"), rejectErr),
             capture(() -> withdrawalRequestService.startProcessing(id, adminId), processErr));

        assertThat(rejectErr.get() == null ^ processErr.get() == null).as("exactly one: %s / %s", rejectErr.get(), processErr.get()).isTrue();
        WithdrawalRequestStatus status = withdrawalRequestRepository.findById(id).orElseThrow().getStatus();
        if (rejectErr.get() == null) {
            assertThat(status).isEqualTo(WithdrawalRequestStatus.REJECTED);
            assertThat(deposit(userId).getBalance()).isEqualByComparingTo("1000");
            assertThat(ledgerCount(userId, DepositTransaction.TransactionType.WITHDRAWAL_RETURN)).isEqualTo(1);
        } else {
            assertThat(status).isEqualTo(WithdrawalRequestStatus.PROCESSING);
            assertThat(deposit(userId).getBalance()).isEqualByComparingTo("700");
            assertThat(ledgerCount(userId, DepositTransaction.TransactionType.WITHDRAWAL_RETURN)).isZero();
        }
    }

    @RepeatedTest(3)
    @DisplayName("V55 audit CHECK: a REJECTED row without a reason cannot be stored")
    void stateAuditCheck() {
        Long userId = user("1000");
        WithdrawalRequest saved = withdrawalRequestService.create(userId, new BigDecimal("10"), "pg-ck-" + System.nanoTime());
        WithdrawalRequest broken = new WithdrawalRequest(saved.getId(), userId, saved.getAmount(), WithdrawalRequestStatus.REJECTED,
                saved.getIdempotencyKey(), saved.getCreatedAt(), null, null, OffsetDateTime.now(), 1L, null, saved.getVersion());
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> withdrawalRequestRepository.save(broken))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        List<WithdrawalRequest> mine = withdrawalRequestService.listMine(userId);
        assertThat(mine.get(0).getStatus()).isEqualTo(WithdrawalRequestStatus.PENDING);
    }
}

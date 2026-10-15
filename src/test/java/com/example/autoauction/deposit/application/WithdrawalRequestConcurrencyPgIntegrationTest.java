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

        race(capture(() -> withdrawalRequestService.reject(id, adminId, "first", true, WithdrawalRequestStatus.PENDING), e1),
             capture(() -> withdrawalRequestService.reject(id, adminId, "second", true, WithdrawalRequestStatus.PENDING), e2));

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

        race(capture(() -> withdrawalRequestService.reject(id, adminId, "no", true, WithdrawalRequestStatus.PENDING), rejectErr),
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
                saved.getIdempotencyKey(), saved.getCreatedAt(), null, null, OffsetDateTime.now(), 1L, null, null, saved.getVersion());
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> withdrawalRequestRepository.save(broken))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        List<WithdrawalRequest> mine = withdrawalRequestService.listMine(userId);
        assertThat(mine.get(0).getStatus()).isEqualTo(WithdrawalRequestStatus.PENDING);
    }

    // ------------------------------------------------------------------ F6.1

    private Long processingRequest(Long userId, Long adminId, String key) {
        Long id = withdrawalRequestService.create(userId, new BigDecimal("300"), key + System.nanoTime()).getId();
        withdrawalRequestService.startProcessing(id, adminId);
        return id;
    }

    @RepeatedTest(5)
    @DisplayName("F6.1: PROCESSING -> REJECTED vs PROCESSING -> PAID at once: exactly one terminal state, never PAID + refund")
    void rejectFromProcessingVsPaid() throws InterruptedException {
        Long userId = user("1000");
        Long adminId = admin();
        Long id = processingRequest(userId, adminId, "pg-f61-rp-");
        AtomicReference<Throwable> rejectErr = new AtomicReference<>();
        AtomicReference<Throwable> paidErr = new AtomicReference<>();

        race(capture(() -> withdrawalRequestService.reject(id, adminId, "не выплачено", true, WithdrawalRequestStatus.PROCESSING), rejectErr),
             capture(() -> withdrawalRequestService.markPaid(id, adminId, "выплачено наличными"), paidErr));

        assertThat(rejectErr.get() == null ^ paidErr.get() == null).as("exactly one: %s / %s", rejectErr.get(), paidErr.get()).isTrue();
        WithdrawalRequest r = withdrawalRequestRepository.findById(id).orElseThrow();
        long returns = ledgerCount(userId, DepositTransaction.TransactionType.WITHDRAWAL_RETURN);
        if (rejectErr.get() == null) {
            assertThat(r.getStatus()).isEqualTo(WithdrawalRequestStatus.REJECTED);
            assertThat(r.getPayoutNote()).as("no payout confirmation on a rejected request").isNull();
            assertThat(r.getProcessingStartedBy()).isEqualTo(adminId);
            assertThat(returns).isEqualTo(1);
            assertThat(deposit(userId).getBalance()).isEqualByComparingTo("1000");
        } else {
            assertThat(r.getStatus()).isEqualTo(WithdrawalRequestStatus.PAID);
            assertThat(r.getRejectionReason()).isNull();
            assertThat(returns).as("no refund on a paid request").isZero();
            assertThat(deposit(userId).getBalance()).isEqualByComparingTo("700");
        }
    }

    @RepeatedTest(5)
    @DisplayName("F6.1: two rejections of one PROCESSING request at once: one rejection, one return")
    void doubleRejectFromProcessing() throws InterruptedException {
        Long userId = user("1000");
        Long adminId = admin();
        Long id = processingRequest(userId, adminId, "pg-f61-rr-");
        AtomicReference<Throwable> e1 = new AtomicReference<>();
        AtomicReference<Throwable> e2 = new AtomicReference<>();

        race(capture(() -> withdrawalRequestService.reject(id, adminId, "a", true, WithdrawalRequestStatus.PROCESSING), e1),
             capture(() -> withdrawalRequestService.reject(id, adminId, "b", true, WithdrawalRequestStatus.PROCESSING), e2));

        assertThat(e1.get() == null ^ e2.get() == null).as("exactly one: %s / %s", e1.get(), e2.get()).isTrue();
        assertThat(ledgerCount(userId, DepositTransaction.TransactionType.WITHDRAWAL_RETURN)).isEqualTo(1);
        assertThat(deposit(userId).getBalance()).isEqualByComparingTo("1000");
    }

    @RepeatedTest(5)
    @DisplayName("F6.1: take-into-work vs PAID on a PENDING request: only the two legal outcomes, no money moves")
    void startVsPaidOnPending() throws InterruptedException {
        Long userId = user("1000");
        Long adminId = admin();
        Long id = withdrawalRequestService.create(userId, new BigDecimal("300"), "pg-f61-sp-" + System.nanoTime()).getId();
        AtomicReference<Throwable> startErr = new AtomicReference<>();
        AtomicReference<Throwable> paidErr = new AtomicReference<>();

        race(capture(() -> withdrawalRequestService.startProcessing(id, adminId), startErr),
             capture(() -> withdrawalRequestService.markPaid(id, adminId, "выплачено"), paidErr));

        assertThat(startErr.get()).as("taking into work always succeeds here").isNull();
        WithdrawalRequestStatus status = withdrawalRequestRepository.findById(id).orElseThrow().getStatus();
        assertThat(status).isEqualTo(paidErr.get() == null ? WithdrawalRequestStatus.PAID : WithdrawalRequestStatus.PROCESSING);
        assertThat(deposit(userId).getBalance()).isEqualByComparingTo("700");
        assertThat(ledgerCount(userId, DepositTransaction.TransactionType.WITHDRAWAL_RETURN)).isZero();
    }

    @RepeatedTest(5)
    @DisplayName("F6.1: a stale PENDING rejection racing take-into-work never rejects a PROCESSING request")
    void staleRejectVsProcessing() throws InterruptedException {
        Long userId = user("1000");
        Long adminId = admin();
        Long id = withdrawalRequestService.create(userId, new BigDecimal("300"), "pg-f61-st-" + System.nanoTime()).getId();
        AtomicReference<Throwable> rejectErr = new AtomicReference<>();
        AtomicReference<Throwable> processErr = new AtomicReference<>();

        race(capture(() -> withdrawalRequestService.reject(id, adminId, "x", true, WithdrawalRequestStatus.PENDING), rejectErr),
             capture(() -> withdrawalRequestService.startProcessing(id, adminId), processErr));

        WithdrawalRequest r = withdrawalRequestRepository.findById(id).orElseThrow();
        assertThat(rejectErr.get() == null ^ processErr.get() == null).isTrue();
        if (rejectErr.get() == null) {
            assertThat(r.getProcessingStartedAt()).as("rejected while still PENDING").isNull();
        } else {
            assertThat(r.getStatus()).isEqualTo(WithdrawalRequestStatus.PROCESSING);
            assertThat(ledgerCount(userId, DepositTransaction.TransactionType.WITHDRAWAL_RETURN)).isZero();
        }
    }

    @org.junit.jupiter.api.Test
    @DisplayName("F6.1 V57 CHECK: PAID without note, half-filled processing audit on REJECTED, a note outside PAID are refused")
    void v57StateAuditCheck() {
        Long userId = user("1000");
        Long adminId = admin();
        Long id = processingRequest(userId, adminId, "pg-f61-ck-");
        WithdrawalRequest p = withdrawalRequestRepository.findById(id).orElseThrow();
        OffsetDateTime now = OffsetDateTime.now();

        java.util.function.Function<WithdrawalRequest, Throwable> save = w -> {
            try { withdrawalRequestRepository.save(w); return null; } catch (Throwable t) { return t; }
        };
        WithdrawalRequest paidNoNote = new WithdrawalRequest(id, userId, p.getAmount(), WithdrawalRequestStatus.PAID, p.getIdempotencyKey(),
                p.getCreatedAt(), p.getProcessingStartedAt(), p.getProcessingStartedBy(), now, adminId, null, null, p.getVersion());
        WithdrawalRequest paidBlankNote = new WithdrawalRequest(id, userId, p.getAmount(), WithdrawalRequestStatus.PAID, p.getIdempotencyKey(),
                p.getCreatedAt(), p.getProcessingStartedAt(), p.getProcessingStartedBy(), now, adminId, null, "   ", p.getVersion());
        WithdrawalRequest rejectedHalfAudit = new WithdrawalRequest(id, userId, p.getAmount(), WithdrawalRequestStatus.REJECTED, p.getIdempotencyKey(),
                p.getCreatedAt(), p.getProcessingStartedAt(), null, now, adminId, "r", null, p.getVersion());
        WithdrawalRequest rejectedWithNote = new WithdrawalRequest(id, userId, p.getAmount(), WithdrawalRequestStatus.REJECTED, p.getIdempotencyKey(),
                p.getCreatedAt(), p.getProcessingStartedAt(), p.getProcessingStartedBy(), now, adminId, "r", "paid?", p.getVersion());
        WithdrawalRequest processingWithNote = new WithdrawalRequest(id, userId, p.getAmount(), WithdrawalRequestStatus.PROCESSING, p.getIdempotencyKey(),
                p.getCreatedAt(), p.getProcessingStartedAt(), p.getProcessingStartedBy(), null, null, null, "note", p.getVersion());

        WithdrawalRequest paidNbspNote = new WithdrawalRequest(id, userId, p.getAmount(), WithdrawalRequestStatus.PAID, p.getIdempotencyKey(),
                p.getCreatedAt(), p.getProcessingStartedAt(), p.getProcessingStartedBy(), now, adminId, null, "\u00a0\u00a0", p.getVersion());
        WithdrawalRequest paidInvisibleNote = new WithdrawalRequest(id, userId, p.getAmount(), WithdrawalRequestStatus.PAID, p.getIdempotencyKey(),
                p.getCreatedAt(), p.getProcessingStartedAt(), p.getProcessingStartedBy(), now, adminId, null,
                " \t\n\r\u000b\f\u0085\u00a0\u1680\u2007\u200b\u2028\u2029\u202f\u205f\u3000\u2060\ufeff", p.getVersion());

        for (WithdrawalRequest broken : List.of(paidNoNote, paidBlankNote, paidNbspNote, paidInvisibleNote,
                rejectedHalfAudit, rejectedWithNote, processingWithNote)) {
            assertThat(save.apply(broken)).as("%s / note=%s", broken.getStatus(), broken.getPayoutNote())
                    .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        }
        assertThat(withdrawalRequestRepository.findById(id).orElseThrow().getStatus()).isEqualTo(WithdrawalRequestStatus.PROCESSING);

        // a note with visible text around invisible characters is a valid note for the database too
        Long other = processingRequest(userId, adminId, "pg-f61-ck2-");
        WithdrawalRequest o = withdrawalRequestRepository.findById(other).orElseThrow();
        withdrawalRequestRepository.save(new WithdrawalRequest(other, userId, o.getAmount(), WithdrawalRequestStatus.PAID, o.getIdempotencyKey(),
                o.getCreatedAt(), o.getProcessingStartedAt(), o.getProcessingStartedBy(), now, adminId, null, "\u00a0чек 77\u00a0", o.getVersion()));
        assertThat(withdrawalRequestRepository.findById(other).orElseThrow().getStatus()).isEqualTo(WithdrawalRequestStatus.PAID);

        // the legal REJECTED-from-PROCESSING shape is accepted
        withdrawalRequestService.reject(id, adminId, "не выплачено", true, WithdrawalRequestStatus.PROCESSING);
        WithdrawalRequest r = withdrawalRequestRepository.findById(id).orElseThrow();
        assertThat(r.getStatus()).isEqualTo(WithdrawalRequestStatus.REJECTED);
        assertThat(r.getProcessingStartedAt()).isNotNull();
        assertThat(r.getProcessingStartedBy()).isEqualTo(adminId);
    }
}

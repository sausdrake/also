// deposit/application/IdempotencyConcurrencyIntegrationTest.java
package com.example.autoauction.deposit.application;

import com.example.autoauction.deposit.domain.Deposit;
import com.example.autoauction.deposit.domain.DepositTransaction;
import com.example.autoauction.deposit.domain.port.DepositRepository;
import com.example.autoauction.user.domain.Role;
import com.example.autoauction.user.domain.User;
import com.example.autoauction.user.domain.port.RoleRepository;
import com.example.autoauction.user.domain.port.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Follow-up item 3: proves the idempotency-key DB race for real, against a real database
 * with real concurrent threads and real connections - not a Mockito-simulated
 * DataIntegrityViolationException. Two threads race to INSERT a deposit_transactions row
 * with the SAME idempotency_key; the DB's unique index (V21) must allow only one to
 * succeed.
 *
 * Same caveat as the diagnostic concurrency test: this is H2 (test-profile), not
 * PostgreSQL/Testcontainers - neither is available on this machine (no Testcontainers
 * dependency, Docker not running) and adding either is out of scope for a prove-first
 * follow-up. NULL/duplicate handling on a UNIQUE index is standard SQL behavior shared by
 * both engines, so this is a genuine proof of the mechanism, not of PostgreSQL specifically.
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("Реальная конкурентность идемпотентности: adminAdjust / withdraw")
class IdempotencyConcurrencyIntegrationTest {

    @Autowired
    private DepositService depositService;

    @Autowired
    private DepositRepository depositRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RoleRepository roleRepository;

    private Long createUserWithDeposit(BigDecimal balance) {
        Role userRole = roleRepository.findByName("ROLE_USER")
                .orElseGet(() -> roleRepository.save(new Role(null, "ROLE_USER", "Обычный пользователь")));
        User user = new User(null, "idem" + System.nanoTime(), "idem" + System.nanoTime() + "@test.com",
                "encoded", true, Set.of(userRole));
        Long userId = userRepository.save(user).getId();
        depositService.createDeposit(userId, balance);
        return userId;
    }

    private void runConcurrently(Runnable a, Runnable b, AtomicReference<Throwable> errA, AtomicReference<Throwable> errB)
            throws InterruptedException {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CyclicBarrier barrier = new CyclicBarrier(2);
        executor.submit(() -> {
            try {
                barrier.await(5, TimeUnit.SECONDS);
                a.run();
            } catch (Throwable t) {
                errA.set(t);
            }
        });
        executor.submit(() -> {
            try {
                barrier.await(5, TimeUnit.SECONDS);
                b.run();
            } catch (Throwable t) {
                errB.set(t);
            }
        });
        executor.shutdown();
        assertThat(executor.awaitTermination(15, TimeUnit.SECONDS)).isTrue();
    }

    @RepeatedTest(5)
    @DisplayName("adminAdjust: два конкурентных запроса с ОДНИМ ключом идемпотентности -> ровно одна мутация баланса")
    void adminAdjust_ConcurrentSameKey_ExactlyOneMutation() throws InterruptedException {
        Long userId = createUserWithDeposit(new BigDecimal("50000"));
        String key = "real-race-adjust-" + System.nanoTime();
        BigDecimal amount = new BigDecimal("1000");

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicReference<Throwable> errA = new AtomicReference<>();
        AtomicReference<Throwable> errB = new AtomicReference<>();

        Runnable op = () -> {
            depositService.adminAdjust(userId, amount, "конкурентная корректировка", key);
            successCount.incrementAndGet();
        };
        runConcurrently(op, op, errA, errB);

        assertRaceInvariants(userId, new BigDecimal("50000"), amount, DepositTransaction.TransactionType.ADMIN_ADJUSTMENT,
                successCount, errA, errB, "adminAdjust");
    }

    @RepeatedTest(5)
    @DisplayName("withdraw: два конкурентных запроса с ОДНИМ ключом идемпотентности -> ровно одна мутация баланса")
    void withdraw_ConcurrentSameKey_ExactlyOneMutation() throws InterruptedException {
        Long userId = createUserWithDeposit(new BigDecimal("50000"));
        String key = "real-race-withdraw-" + System.nanoTime();
        BigDecimal amount = new BigDecimal("1000");

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicReference<Throwable> errA = new AtomicReference<>();
        AtomicReference<Throwable> errB = new AtomicReference<>();

        Runnable op = () -> {
            depositService.withdraw(userId, amount, key);
            successCount.incrementAndGet();
        };
        runConcurrently(op, op, errA, errB);

        assertRaceInvariants(userId, new BigDecimal("50000"), amount.negate(), DepositTransaction.TransactionType.WITHDRAWAL,
                successCount, errA, errB, "withdraw");
    }

    private void assertRaceInvariants(Long userId, BigDecimal startingBalance, BigDecimal signedDelta,
                                       DepositTransaction.TransactionType expectedType,
                                       AtomicInteger successCount, AtomicReference<Throwable> errA,
                                       AtomicReference<Throwable> errB, String label) {
        boolean aFailed = errA.get() != null;
        boolean bFailed = errB.get() != null;

        List<DepositTransaction> transactions = depositRepository.findTransactionsByUserId(userId);
        long matchingCount = transactions.stream()
                .filter(t -> t.getType() == expectedType)
                .count();

        Deposit finalDeposit = depositRepository.findByUserId(userId).orElseThrow();

        System.out.printf("[%s] successCount=%d aFailed=%s bFailed=%s matchingTxCount=%d finalBalance=%s errA=%s errB=%s%n",
                label, successCount.get(), aFailed, bFailed, matchingCount, finalDeposit.getBalance(),
                errA.get() == null ? "-" : errA.get().getClass().getSimpleName(),
                errB.get() == null ? "-" : errB.get().getClass().getSimpleName());

        // Exactly one transaction row of this type/key must exist - no double mutation, no
        // lost mutation (idempotent replay of the loser still returns success, so
        // successCount can legitimately be 2 - what must NEVER happen is 2 persisted rows).
        assertThat(matchingCount)
                .as("[%s] exactly one %s transaction row must persist for this idempotency key", label, expectedType)
                .isEqualTo(1L);

        // Exactly one real mutation was applied to the balance - not a partial/double one.
        assertThat(finalDeposit.getBalance())
                .as("[%s] balance must reflect exactly one application of the operation, not zero or two", label)
                .isEqualByComparingTo(startingBalance.add(signedDelta));

        // At least one side must have observed a clean, deterministic outcome (either a
        // successful mutation or a clean DataIntegrityViolationException on the DB race, or
        // - if serialized cleanly - an idempotent replay). What must never happen is both
        // silently believing they each independently mutated the balance while only one
        // actually did (already covered by matchingCount==1 and the balance check above).
        assertThat(successCount.get() + (aFailed ? 1 : 0) + (bFailed ? 1 : 0))
                .as("[%s] every attempt must resolve to either success or a captured failure - no silent loss", label)
                .isEqualTo(2);
    }
}

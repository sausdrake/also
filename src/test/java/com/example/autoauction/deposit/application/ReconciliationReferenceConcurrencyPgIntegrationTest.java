package com.example.autoauction.deposit.application;

import com.example.autoauction.deposit.domain.AdminAdjustmentReason;
import com.example.autoauction.deposit.domain.DepositTransaction;
import com.example.autoauction.deposit.domain.port.DepositRepository;
import com.example.autoauction.support.AbstractPostgresIntegrationTest;
import com.example.autoauction.user.domain.Role;
import com.example.autoauction.user.domain.User;
import com.example.autoauction.user.domain.port.RoleRepository;
import com.example.autoauction.user.domain.port.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.util.Set;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * F7 on real PostgreSQL: two RECONCILIATION credits with the same external reference racing
 * past the application check - V56's partial unique index lets at most one of them commit.
 */
@DisplayName("F7: one RECONCILIATION credit per reference under concurrency (PostgreSQL)")
// Skipped (not failed) where no Docker daemon is reachable, e.g. the network-less verification
// container; runs wherever Testcontainers can start PostgreSQL.
@org.testcontainers.junit.jupiter.Testcontainers(disabledWithoutDocker = true)
class ReconciliationReferenceConcurrencyPgIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final Long ADMIN_ID = 42L;

    @Autowired private DepositService depositService;
    @Autowired private DepositRepository depositRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;

    private Long user() {
        Role role = roleRepository.findByName("ROLE_USER")
                .orElseGet(() -> roleRepository.save(new Role(null, "ROLE_USER", "Обычный пользователь")));
        String name = "recpg" + System.nanoTime();
        Long id = userRepository.save(new User(null, name, name + "@test.com", "encoded", true, Set.of(role))).getId();
        depositService.createDeposit(id, BigDecimal.ZERO);
        return id;
    }

    private long reconciliationCredits(Long userId) {
        return depositRepository.findTransactionsByUserId(userId).stream()
                .filter(t -> t.getType() == DepositTransaction.TransactionType.ADMIN_ADJUSTMENT)
                .count();
    }

    private BigDecimal balance(Long userId) {
        return depositRepository.findByUserId(userId).orElseThrow().getBalance();
    }

    private Runnable credit(Long userId, String reference) {
        return () -> {
            try {
                depositService.adminAdjust(userId, new BigDecimal("5000"), AdminAdjustmentReason.RECONCILIATION,
                        reference, "k-" + System.nanoTime() + "-" + Thread.currentThread().getId(), ADMIN_ID);
            } catch (RuntimeException expectedForTheLoser) {
                // the loser is refused (unique index or optimistic lock) - what matters is the outcome below
            }
        };
    }

    private static void race(Runnable a, Runnable b) throws InterruptedException {
        CyclicBarrier barrier = new CyclicBarrier(2);
        Thread ta = new Thread(() -> { try { barrier.await(20, TimeUnit.SECONDS); a.run(); } catch (Exception e) { throw new RuntimeException(e); } });
        Thread tb = new Thread(() -> { try { barrier.await(20, TimeUnit.SECONDS); b.run(); } catch (Exception e) { throw new RuntimeException(e); } });
        ta.start();
        tb.start();
        ta.join(20_000);
        tb.join(20_000);
        assertThat(ta.isAlive() || tb.isAlive()).as("no deadlock").isFalse();
    }

    @RepeatedTest(5)
    @DisplayName("5: same user, same reference, two keys at once -> exactly one credit")
    void sameUser_atMostOneCredit() throws InterruptedException {
        Long userId = user();
        String reference = "EXT-PG-" + System.nanoTime();

        race(credit(userId, reference), credit(userId, reference));

        assertThat(reconciliationCredits(userId)).as("exactly one credit - the other is a replay or refused").isEqualTo(1);
        assertThat(balance(userId)).isEqualByComparingTo("5000");
    }

    @RepeatedTest(5)
    @DisplayName("5: two users, same reference at once -> exactly one of them is credited")
    void twoUsers_atMostOneCredit() throws InterruptedException {
        Long a = user();
        Long b = user();
        String reference = "EXT-PG-" + System.nanoTime();

        race(credit(a, reference), credit(b, reference));

        assertThat(reconciliationCredits(a) + reconciliationCredits(b)).as("exactly one user credited").isEqualTo(1);
        assertThat(balance(a).add(balance(b))).isEqualByComparingTo("5000");
    }

    @Test
    @DisplayName("V56 index: a second RECONCILIATION row with the same reference cannot be stored at all")
    void indexRejectsSecondRow() {
        Long a = user();
        Long b = user();
        String reference = "EXT-PG-" + System.nanoTime();
        depositService.adminAdjust(a, new BigDecimal("5000"), AdminAdjustmentReason.RECONCILIATION, reference,
                "k-" + System.nanoTime(), ADMIN_ID);
        Long depositB = depositRepository.findByUserId(b).orElseThrow().getId();

        DepositTransaction duplicate = new DepositTransaction(depositB, b, DepositTransaction.TransactionType.ADMIN_ADJUSTMENT,
                new BigDecimal("5000"), BigDecimal.ZERO, new BigDecimal("5000"), reference, "RECONCILIATION");
        duplicate.setIdempotencyKey("k-raw-" + System.nanoTime());
        assertThatThrownBy(() -> depositRepository.saveTransaction(duplicate))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}

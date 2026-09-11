package com.example.autoauction.deposit.application;

import com.example.autoauction.deposit.domain.Deposit;
import com.example.autoauction.deposit.domain.port.DepositRepository;
import com.example.autoauction.support.AbstractPostgresIntegrationTest;
import com.example.autoauction.user.domain.Role;
import com.example.autoauction.user.domain.User;
import com.example.autoauction.user.domain.port.RoleRepository;
import com.example.autoauction.user.domain.port.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.SpyBean;

import java.math.BigDecimal;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;

/**
 * Slice 2 · Step 2A: real-PostgreSQL (Testcontainers) proof that
 * {@link DepositService#creditFromPayment(DepositCreditCommand)}'s ledger write and balance write
 * are atomic from PostgreSQL's own perspective - a failure injected AFTER the
 * {@code deposit_transactions} INSERT has genuinely executed, but BEFORE the {@code deposits}
 * balance UPDATE commits, must roll back BOTH: no orphan ledger row, no partial balance change.
 *
 * <p>Failure injection: a Mockito spy wrapping the REAL, Testcontainers-backed
 * {@link DepositRepository} bean (Spring Boot's {@code @SpyBean} wraps the existing bean rather
 * than replacing it, per its own documented contract) - {@code saveTransaction(...)} runs for
 * real (a genuine INSERT against real PostgreSQL); only {@code save(Deposit)} for the credited
 * user is made to throw. This mechanism exists ONLY inside this test class; no production code is
 * changed, and no production transaction is weakened to make this injectable.
 *
 * <p>Not a Mockito-interaction-only proof: the assertions re-read both the deposit balance and the
 * ledger row from FRESH repository calls after the failed transaction has already rolled back,
 * exactly mirroring {@code IdempotencyConcurrencyIntegrationTest}'s own "assert from outside the
 * transaction" discipline, but against real PostgreSQL rather than H2.
 */
@DisplayName("Slice 2 · Step 2A: creditFromPayment real-PostgreSQL atomicity/rollback proof")
class DepositCreditPrimitivePgRollbackIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private DepositService depositService;

    @SpyBean
    private DepositRepository depositRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RoleRepository roleRepository;

    private Long createUserWithDeposit(BigDecimal balance) {
        Role userRole = roleRepository.findByName("ROLE_USER")
                .orElseGet(() -> roleRepository.save(new Role(null, "ROLE_USER", "Обычный пользователь")));
        User user = new User(null, "pgrollback" + System.nanoTime(), "pgrollback" + System.nanoTime() + "@test.com",
                "encoded", true, Set.of(userRole));
        Long id = userRepository.save(user).getId();
        depositService.createDeposit(id, balance);
        return id;
    }

    @Test
    @DisplayName("Сбой save(Deposit) ПОСЛЕ успешной вставки DepositTransaction откатывает ОБА изменения атомарно")
    void creditFromPayment_FailureAfterLedgerInsert_RollsBackBothChanges() {
        Long userId = createUserWithDeposit(new BigDecimal("10000.00"));
        String key = "PAYMENT:pg-rollback-" + System.nanoTime();

        doThrow(new RuntimeException("Step 2A test-only injected failure - not production behavior"))
                .when(depositRepository)
                .save(argThat(d -> d != null && userId.equals(d.getUserId())));

        DepositCreditCommand command = new DepositCreditCommand(userId, new BigDecimal("2500.00"), key, key);
        assertThatThrownBy(() -> depositService.creditFromPayment(command))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("injected failure");

        Deposit afterRollback = depositRepository.findByUserId(userId).orElseThrow();
        assertThat(afterRollback.getBalance())
                .as("balance must be unchanged - the whole physical transaction rolled back")
                .isEqualByComparingTo("10000.00");
        assertThat(depositRepository.findTransactionByIdempotencyKey(key))
                .as("the ledger row inserted before the injected failure must have been rolled back too - "
                        + "no orphan deposit_transactions row")
                .isEmpty();
    }
}

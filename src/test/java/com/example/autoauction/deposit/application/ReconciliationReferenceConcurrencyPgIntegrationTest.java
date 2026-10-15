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
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

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
    @Autowired private JdbcTemplate jdbcTemplate;

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

    // ------------------------------------------------------------------ V56 normalisation (btrim)

    private DepositTransaction rawRow(Long userId, String reference, String description) {
        Long depositId = depositRepository.findByUserId(userId).orElseThrow().getId();
        DepositTransaction row = new DepositTransaction(depositId, userId, DepositTransaction.TransactionType.ADMIN_ADJUSTMENT,
                new BigDecimal("1"), BigDecimal.ZERO, new BigDecimal("1"), reference, description);
        row.setIdempotencyKey("k-raw-" + System.nanoTime());
        return row;
    }

    @Test
    @DisplayName("V56: \" ABC \" and \"ABC\" are one external operation; distinct references, NULLs and other reasons are not constrained")
    void indexUsesTrimmedReference() {
        Long a = user();
        Long b = user();
        String abc = "EXT-NORM-" + System.nanoTime();

        depositRepository.saveTransaction(rawRow(a, abc, "RECONCILIATION"));
        assertThatThrownBy(() -> depositRepository.saveTransaction(rawRow(b, "  " + abc + " ", "RECONCILIATION")))
                .isInstanceOf(DataIntegrityViolationException.class);

        depositRepository.saveTransaction(rawRow(b, abc + "-2", "RECONCILIATION"));          // a different reference
        depositRepository.saveTransaction(rawRow(a, null, "RECONCILIATION"));               // NULLs are never duplicates
        depositRepository.saveTransaction(rawRow(b, null, "RECONCILIATION"));
        depositRepository.saveTransaction(rawRow(a, abc, "SUPPORT_CORRECTION"));            // other reasons: not unique
        depositRepository.saveTransaction(rawRow(b, abc, "SUPPORT_CORRECTION"));
    }

    @RepeatedTest(5)
    @DisplayName("V56: \"X\" and \" X \" credited at once -> exactly one credit")
    void trimmedVariantsRace_exactlyOneCredit() throws InterruptedException {
        Long a = user();
        Long b = user();
        String reference = "EXT-PG-TRIM-" + System.nanoTime();

        race(credit(a, reference), credit(b, "  " + reference + " "));

        assertThat(reconciliationCredits(a) + reconciliationCredits(b)).isEqualTo(1);
        assertThat(balance(a).add(balance(b))).isEqualByComparingTo("5000");
    }

    @Test
    @DisplayName("V56: a legacy untrimmed row is found by the trimmed lookup - a repeat is a no-op, not a second credit")
    void legacyUntrimmedRow_isFoundByTrimmedLookup() {
        Long userId = user();
        String reference = "EXT-LEGACY-" + System.nanoTime();
        Long depositId = depositRepository.findByUserId(userId).orElseThrow().getId();
        DepositTransaction legacy = new DepositTransaction(depositId, userId, DepositTransaction.TransactionType.ADMIN_ADJUSTMENT,
                new BigDecimal("5000"), BigDecimal.ZERO, new BigDecimal("5000"), " " + reference + " ", "RECONCILIATION");
        legacy.setIdempotencyKey("k-legacy-" + System.nanoTime());
        depositRepository.saveTransaction(legacy);

        depositService.adminAdjust(userId, new BigDecimal("5000"), AdminAdjustmentReason.RECONCILIATION, reference,
                "k-" + System.nanoTime(), ADMIN_ID);

        assertThat(reconciliationCredits(userId)).isEqualTo(1);
        assertThat(balance(userId)).as("the replay did not touch the balance").isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("D-F7-1: TAB/LF/CR legacy rows - the same text again is no second credit; REF<TAB> and REF stay distinct")
    void controlCharactersFollowBtrim() {
        String[][] same = {{"R\t", "R\t"}, {"R\n", "R\n"}, {"R\r", "R\r"}, {"R\t", " R\t "}, {"R", " R "}};
        for (String[] c : same) {
            Long userId = user();
            String s = "-" + System.nanoTime();
            DepositTransaction legacy = rawRow(userId, c[0].replace("R", "R" + s), "RECONCILIATION");
            depositRepository.saveTransaction(legacy);
            depositService.adminAdjust(userId, new BigDecimal("1"), AdminAdjustmentReason.RECONCILIATION,
                    c[1].replace("R", "R" + s), "k-" + System.nanoTime(), ADMIN_ID);
            assertThat(reconciliationCredits(userId)).as("[%s] vs [%s]", c[0], c[1]).isEqualTo(1);
            assertThat(balance(userId)).as("[%s] vs [%s]: balance untouched", c[0], c[1]).isEqualByComparingTo("0");
        }
        // under btrim "R<TAB>" and "R" are different external identifiers - both may be credited
        Long userId = user();
        String r = "R-" + System.nanoTime();
        depositRepository.saveTransaction(rawRow(userId, r + "\t", "RECONCILIATION"));
        depositService.adminAdjust(userId, new BigDecimal("1"), AdminAdjustmentReason.RECONCILIATION, r,
                "k-" + System.nanoTime(), ADMIN_ID);
        assertThat(reconciliationCredits(userId)).isEqualTo(2);
        // and the index agrees: " R<TAB> " collides with "R<TAB>"
        Long other = user();
        assertThatThrownBy(() -> depositRepository.saveTransaction(rawRow(other, " " + r + "\t ", "RECONCILIATION")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @RepeatedTest(5)
    @DisplayName("D-F7-1: \"R<TAB>\" and \" R<TAB> \" credited at once -> exactly one credit")
    void controlCharacterVariantsRace_exactlyOneCredit() throws InterruptedException {
        Long a = user();
        Long b = user();
        String reference = "EXT-PG-TAB-" + System.nanoTime() + "\t";

        race(credit(a, reference), credit(b, " " + reference + " "));

        assertThat(reconciliationCredits(a) + reconciliationCredits(b)).isEqualTo(1);
        assertThat(balance(a).add(balance(b))).isEqualByComparingTo("5000");
    }

    @Test
    @DisplayName("V56 pre-check uses the index's semantics: NULLs pass, trimmed duplicates are refused")
    void preCheckMatchesIndexSemantics() throws Exception {
        String migration = Files.readString(Path.of("src/main/resources/db/migration/V56__reconciliation_reference_unique.sql"),
                StandardCharsets.UTF_8);
        String doBlock = migration.substring(migration.indexOf("DO $$"), migration.indexOf("END $$;") + "END $$;".length());
        String table = "v56_precheck_" + System.nanoTime();
        jdbcTemplate.execute("CREATE TABLE " + table + " (LIKE deposit_transactions INCLUDING DEFAULTS)");
        try {
            String check = doBlock.replace("deposit_transactions", table);
            String insert = "INSERT INTO " + table + " (deposit_id, user_id, type, amount, balance_before, balance_after, reference, description, created_at) "
                    + "VALUES (1, 1, 'ADMIN_ADJUSTMENT', 1, 0, 1, ?, ?, now())";

            jdbcTemplate.update(insert, null, "RECONCILIATION");
            jdbcTemplate.update(insert, null, "RECONCILIATION");
            jdbcTemplate.update(insert, "ABC", "SUPPORT_CORRECTION");
            jdbcTemplate.update(insert, "ABC", "SUPPORT_CORRECTION");
            jdbcTemplate.update(insert, "ABC", "RECONCILIATION");
            jdbcTemplate.update(insert, "ABD", "RECONCILIATION");
            jdbcTemplate.execute(check);   // passes: NULLs, other reasons and distinct references are fine

            jdbcTemplate.update(insert, " ABC ", "RECONCILIATION");
            assertThatThrownBy(() -> jdbcTemplate.execute(check))
                    .hasMessageContaining("more than one RECONCILIATION credit");
        } finally {
            jdbcTemplate.execute("DROP TABLE " + table);
        }
    }
}

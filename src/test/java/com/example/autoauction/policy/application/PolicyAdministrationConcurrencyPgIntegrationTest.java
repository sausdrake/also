package com.example.autoauction.policy.application;

import com.example.autoauction.policy.domain.PenaltyType;
import com.example.autoauction.policy.domain.PolicyVersion;
import com.example.autoauction.policy.domain.port.PolicyVersionRepository;
import com.example.autoauction.policy.domain.port.PolicyVersionViolationRepository;
import com.example.autoauction.policy.infrastructure.persistence.JpaViolationDefinitionEntity;
import com.example.autoauction.policy.infrastructure.persistence.SpringDataViolationDefinitionJpaRepository;
import com.example.autoauction.support.AbstractPostgresIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Policy Administration concurrency lock: real-PostgreSQL, real-thread proofs of the locked
 * concurrency invariants (Decisions 5/6, Boundaries A/B/C) and of the existing immutability
 * triggers (V32/V38) - mirrors this project's own proven {@code
 * PaymentCreationConcurrencyPgIntegrationTest} two-thread/CyclicBarrier convention and {@code
 * PaymentDeadlinePersistenceSmokePgIntegrationTest}'s raw-{@code JdbcTemplate} trigger-proof
 * convention. H2 cannot faithfully exercise PostgreSQL's real row-locking/READ COMMITTED
 * semantics or the Flyway-only triggers - this is why the concurrency-correction report required
 * these specific scenarios be proven here, against the real database, not merely reasoned about.
 *
 * <p>Shares the class-scoped container/context with every other {@code *PgIntegrationTest} (see
 * {@link AbstractPostgresIntegrationTest}), and - because the {@code "pg"} profile runs the REAL
 * Flyway chain, including V37's real catalogue seed - {@code PolicyBootstrap} genuinely seeds and
 * publishes PolicyVersion #1 at context startup here (unlike the H2 profile, where it is a
 * verified no-op). Every test below is therefore written defensively against a shared,
 * non-test-scoped database exactly like this project's other Pg concurrency suites: unique,
 * {@code System.nanoTime()}-suffixed fixtures, and assertions that never assume a pristine table.
 */
@DisplayName("Policy Administration: real-PostgreSQL concurrency and trigger proofs")
class PolicyAdministrationConcurrencyPgIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired private PolicyVersionService policyVersionService;
    @Autowired private PolicyVersionRepository policyVersionRepository;
    @Autowired private PolicyVersionViolationRepository policyVersionViolationRepository;
    @Autowired private SpringDataViolationDefinitionJpaRepository violationDefinitionJpaRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    private static final Long ADMIN = 1L;
    private static final Long CHECKER = 2L;

    private void runConcurrently(Runnable a, Runnable b) throws InterruptedException {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CyclicBarrier barrier = new CyclicBarrier(2);
        executor.submit(() -> {
            try {
                barrier.await(5, TimeUnit.SECONDS);
                a.run();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        executor.submit(() -> {
            try {
                barrier.await(5, TimeUnit.SECONDS);
                b.run();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        executor.shutdown();
        assertThat(executor.awaitTermination(15, TimeUnit.SECONDS)).isTrue();
    }

    private Long newCatalogueEntry(String prefix) {
        return violationDefinitionJpaRepository.save(new JpaViolationDefinitionEntity(
                prefix + "_" + System.nanoTime(), "pg test fixture", OffsetDateTime.now())).getId();
    }

    /** Creates a draft and configures every currently-existing catalogue definition on it. */
    private PolicyVersion publishableDraft(BigDecimal commissionRate, BigDecimal value, Long createdBy) {
        newCatalogueEntry("PGTEST");
        PolicyVersion draft = policyVersionService.create(commissionRate, createdBy);
        for (var d : violationDefinitionJpaRepository.findAll()) {
            policyVersionService.updateDraftViolation(draft.getId(), d.getId(), PenaltyType.PERCENTAGE, value, 0L);
        }
        return draft;
    }

    private PolicyVersion publishAs(Long draftId, Long callerAdminId) {
        PolicyVersion fresh = policyVersionService.getById(draftId);
        return policyVersionService.publish(draftId, callerAdminId, fresh.getConfigurationRevision());
    }

    // ── 1. Same-base concurrent publication ─────────────────────────────────────

    @Test
    @DisplayName("Real PostgreSQL: two drafts based on the SAME current published policy, concurrent publish -> " +
            "exactly one succeeds, the other fails STALE_POLICY_BASE; exactly one new current; loser stays unpublished")
    void sameBaseConcurrentPublication_ExactlyOneWins() throws InterruptedException {
        PolicyVersion base = publishableDraft(new BigDecimal("0.0200"), new BigDecimal("100"), ADMIN);
        PolicyVersion v1 = publishAs(base.getId(), CHECKER);

        PolicyVersion d2 = policyVersionService.create(new BigDecimal("0.0300"), ADMIN);
        PolicyVersion d3 = policyVersionService.create(new BigDecimal("0.0400"), ADMIN);
        for (var d : violationDefinitionJpaRepository.findAll()) {
            policyVersionService.updateDraftViolation(d2.getId(), d.getId(), PenaltyType.PERCENTAGE, new BigDecimal("100"), 0L);
            policyVersionService.updateDraftViolation(d3.getId(), d.getId(), PenaltyType.PERCENTAGE, new BigDecimal("100"), 0L);
        }
        assertThat(d2.getBasedOnPublishedVersionId()).isEqualTo(v1.getId());
        assertThat(d3.getBasedOnPublishedVersionId()).isEqualTo(v1.getId());

        Long revD2 = policyVersionService.getById(d2.getId()).getConfigurationRevision();
        Long revD3 = policyVersionService.getById(d3.getId()).getConfigurationRevision();

        AtomicReference<Throwable> errD2 = new AtomicReference<>();
        AtomicReference<Throwable> errD3 = new AtomicReference<>();

        runConcurrently(
                () -> { try { policyVersionService.publish(d2.getId(), CHECKER, revD2); } catch (Throwable t) { errD2.set(t); } },
                () -> { try { policyVersionService.publish(d3.getId(), CHECKER, revD3); } catch (Throwable t) { errD3.set(t); } });

        boolean d2Won = errD2.get() == null;
        boolean d3Won = errD3.get() == null;
        assertThat(d2Won ^ d3Won).as("exactly one of the two same-base drafts must publish").isTrue();

        Throwable loserError = d2Won ? errD3.get() : errD2.get();
        assertThat(loserError).isInstanceOf(StalePolicyBaseException.class);

        Long winnerId = d2Won ? d2.getId() : d3.getId();
        Long loserId = d2Won ? d3.getId() : d2.getId();
        assertThat(policyVersionService.getCurrentPublished().getId()).isEqualTo(winnerId);
        assertThat(policyVersionService.getById(loserId).isPublished())
                .as("the losing draft must remain unpublished - no partial/side-effect mutation").isFalse();
    }

    // ── 2. First-time PolicyVersionViolation insert race ────────────────────────

    @Test
    @DisplayName("Real PostgreSQL: two concurrent first-time configurations of the SAME (draft, violationDefinition) " +
            "pair -> exactly one succeeds, the other gets VIOLATION_INSERT_CONFLICT, no overwrite")
    void concurrentFirstTimeViolationInsert_ExactlyOneWins() throws InterruptedException {
        Long defId = newCatalogueEntry("PGRACE");
        PolicyVersion draft = policyVersionService.create(new BigDecimal("0.02"), ADMIN);

        AtomicReference<Throwable> errA = new AtomicReference<>();
        AtomicReference<Throwable> errB = new AtomicReference<>();

        runConcurrently(
                () -> { try { policyVersionService.updateDraftViolation(draft.getId(), defId, PenaltyType.PERCENTAGE, new BigDecimal("11"), 0L); }
                        catch (Throwable t) { errA.set(t); } },
                () -> { try { policyVersionService.updateDraftViolation(draft.getId(), defId, PenaltyType.PERCENTAGE, new BigDecimal("22"), 0L); }
                        catch (Throwable t) { errB.set(t); } });

        boolean aWon = errA.get() == null;
        boolean bWon = errB.get() == null;
        assertThat(aWon ^ bWon).as("exactly one of the two concurrent first-time inserts must succeed").isTrue();

        Throwable loserError = aWon ? errB.get() : errA.get();
        assertThat(loserError).isInstanceOf(ViolationInsertConflictException.class);

        var rows = policyVersionViolationRepository.findByPolicyVersionId(draft.getId()).stream()
                .filter(r -> r.getViolationDefinitionId().equals(defId)).toList();
        assertThat(rows).as("no overwrite - exactly one row exists for this pair").hasSize(1);
        BigDecimal persisted = rows.get(0).getPenaltyValue();
        assertThat(persisted.stripTrailingZeros())
                .as("the persisted value must be exactly the winner's submitted value, never a corrupted hybrid")
                .isIn(new BigDecimal("11").stripTrailingZeros(), new BigDecimal("22").stripTrailingZeros());
    }

    // ── 3. Publish vs violation edit ────────────────────────────────────────────

    @Test
    @DisplayName("Real PostgreSQL: edit commits first -> checker's stale observedConfigurationRevision is rejected " +
            "(STALE_POLICY_CONFIGURATION), draft remains unpublished")
    void publishVsEdit_EditCommitsFirst_PublishRejectedStaleConfiguration() {
        PolicyVersion draft = publishableDraft(new BigDecimal("0.02"), new BigDecimal("50"), ADMIN);
        Long observedByChecker = policyVersionService.getById(draft.getId()).getConfigurationRevision();

        Long anyDefId = violationDefinitionJpaRepository.findAll().get(0).getId();
        var existing = policyVersionViolationRepository
                .findByPolicyVersionIdAndViolationDefinitionId(draft.getId(), anyDefId).orElseThrow();
        policyVersionService.updateDraftViolation(draft.getId(), anyDefId, PenaltyType.PERCENTAGE,
                new BigDecimal("75"), existing.getVersion());

        assertThatThrownBy(() -> policyVersionService.publish(draft.getId(), CHECKER, observedByChecker))
                .isInstanceOf(StalePolicyConfigurationException.class);

        assertThat(policyVersionService.getById(draft.getId()).isPublished())
                .as("a rejected publish must leave the draft unpublished, no partial commit").isFalse();
    }

    @Test
    @DisplayName("Real PostgreSQL: publish commits first -> a subsequent violation edit is rejected by BOTH the " +
            "application check AND the real DB trigger (bypassing the application entirely); configuration unchanged")
    void publishVsEdit_PublishCommitsFirst_EditRejectedByAppAndTrigger() {
        PolicyVersion draft = publishableDraft(new BigDecimal("0.02"), new BigDecimal("60"), ADMIN);
        PolicyVersion published = publishAs(draft.getId(), CHECKER);
        Long anyDefId = violationDefinitionViolationIdOfFirstRow(published.getId());

        // Application-level rejection (fails before ever reaching the database).
        assertThatThrownBy(() -> policyVersionService.updateDraftViolation(
                published.getId(), anyDefId, PenaltyType.PERCENTAGE, new BigDecimal("99"), 0L))
                .isInstanceOf(IllegalStateException.class);

        // Real DB trigger, bypassing the application entirely via raw SQL.
        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE policy_version_violation SET penalty_value = ? WHERE policy_version_id = ? AND violation_definition_id = ?",
                new BigDecimal("99"), published.getId(), anyDefId))
                .isInstanceOf(DataAccessException.class);

        BigDecimal unchanged = policyVersionViolationRepository
                .findByPolicyVersionIdAndViolationDefinitionId(published.getId(), anyDefId).orElseThrow().getPenaltyValue();
        assertThat(unchanged.stripTrailingZeros())
                .as("published configuration must remain exactly as published, no partial mutation survived the rejected statement")
                .isEqualTo(new BigDecimal("60").stripTrailingZeros());
    }

    private Long violationDefinitionViolationIdOfFirstRow(Long policyVersionId) {
        return policyVersionViolationRepository.findByPolicyVersionId(policyVersionId).get(0).getViolationDefinitionId();
    }

    // ── 4. Published-row trigger protection (every new field, real Postgres) ───

    @Test
    @DisplayName("Real PostgreSQL: the existing immutability triggers reject ANY mutation of a published " +
            "PolicyVersion's fields (including every newly-added field) and of a published row's " +
            "PolicyVersionViolation fields - unmodified, unweakened triggers")
    void publishedRowTriggers_RejectMutationOfEveryNewField() {
        PolicyVersion draft = publishableDraft(new BigDecimal("0.02"), new BigDecimal("70"), ADMIN);
        PolicyVersion published = publishAs(draft.getId(), CHECKER);
        Long anyDefId = violationDefinitionViolationIdOfFirstRow(published.getId());

        assertTriggerRejects("UPDATE policy_version SET published = false WHERE id = ?", published.getId());
        assertTriggerRejects("UPDATE policy_version SET published_by = 999999 WHERE id = ?", published.getId());
        assertTriggerRejects("UPDATE policy_version SET published_at = now() WHERE id = ?", published.getId());
        assertTriggerRejects("UPDATE policy_version SET based_on_published_version_id = NULL WHERE id = ?", published.getId());
        assertTriggerRejects("UPDATE policy_version SET version = version + 1 WHERE id = ?", published.getId());
        assertTriggerRejects("UPDATE policy_version SET configuration_revision = configuration_revision + 1 WHERE id = ?", published.getId());
        assertTriggerRejects("UPDATE policy_version SET commission_rate = 0.9999 WHERE id = ?", published.getId());
        assertTriggerRejects("DELETE FROM policy_version WHERE id = ?", published.getId());

        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE policy_version_violation SET penalty_value = 1 WHERE policy_version_id = ? AND violation_definition_id = ?",
                published.getId(), anyDefId)).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE policy_version_violation SET version = version + 1 WHERE policy_version_id = ? AND violation_definition_id = ?",
                published.getId(), anyDefId)).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbcTemplate.update(
                "DELETE FROM policy_version_violation WHERE policy_version_id = ? AND violation_definition_id = ?",
                published.getId(), anyDefId)).isInstanceOf(DataAccessException.class);

        PolicyVersion reloaded = policyVersionService.getById(published.getId());
        assertThat(reloaded.isPublished()).isTrue();
        assertThat(reloaded.getPublishedBy()).isEqualTo(CHECKER);
        assertThat(reloaded.getCommissionRate()).isEqualByComparingTo("0.02");
    }

    private void assertTriggerRejects(String sql, Long id) {
        assertThatThrownBy(() -> jdbcTemplate.update(sql, id)).isInstanceOf(DataAccessException.class);
    }

    // ── 5. Concurrent configurationRevision increments (no lost update) ────────

    @Test
    @DisplayName("Real PostgreSQL: two independent transactions each mutate a DIFFERENT existing violation row on " +
            "the SAME draft concurrently -> configurationRevision increases by exactly 2, no increment lost")
    void concurrentConfigurationRevisionIncrements_NoLostUpdate() throws InterruptedException {
        Long defIdA = newCatalogueEntry("PGREVA");
        Long defIdB = newCatalogueEntry("PGREVB");
        PolicyVersion draft = policyVersionService.create(new BigDecimal("0.02"), ADMIN);
        // Establish both rows sequentially first (first-time inserts), so the concurrent phase
        // below exercises ONLY the revision-increment race, not Boundary A/C's own insert race.
        policyVersionService.updateDraftViolation(draft.getId(), defIdA, PenaltyType.PERCENTAGE, new BigDecimal("1"), 0L);
        policyVersionService.updateDraftViolation(draft.getId(), defIdB, PenaltyType.PERCENTAGE, new BigDecimal("1"), 0L);

        Long initialRevision = policyVersionService.getById(draft.getId()).getConfigurationRevision();
        Long versionA = policyVersionViolationRepository
                .findByPolicyVersionIdAndViolationDefinitionId(draft.getId(), defIdA).orElseThrow().getVersion();
        Long versionB = policyVersionViolationRepository
                .findByPolicyVersionIdAndViolationDefinitionId(draft.getId(), defIdB).orElseThrow().getVersion();

        AtomicReference<Throwable> errA = new AtomicReference<>();
        AtomicReference<Throwable> errB = new AtomicReference<>();
        runConcurrently(
                () -> { try { policyVersionService.updateDraftViolation(draft.getId(), defIdA, PenaltyType.PERCENTAGE, new BigDecimal("2"), versionA); }
                        catch (Throwable t) { errA.set(t); } },
                () -> { try { policyVersionService.updateDraftViolation(draft.getId(), defIdB, PenaltyType.PERCENTAGE, new BigDecimal("2"), versionB); }
                        catch (Throwable t) { errB.set(t); } });

        assertThat(errA.get()).as("errA=%s", errA.get()).isNull();
        assertThat(errB.get()).as("errB=%s", errB.get()).isNull();

        Long finalRevision = policyVersionService.getById(draft.getId()).getConfigurationRevision();
        assertThat(finalRevision).as("two genuine concurrent mutations must never lose an increment")
                .isEqualTo(initialRevision + 2);
    }
}

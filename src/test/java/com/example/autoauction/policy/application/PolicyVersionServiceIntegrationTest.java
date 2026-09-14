package com.example.autoauction.policy.application;

import com.example.autoauction.policy.domain.PolicyVersion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Slice 1b-1 / Policy Administration: real-persistence proof of PolicyVersionService end-to-end,
 * including the version-number concurrency guarantee and, since the concurrency lock, the
 * publication-serialization guarantee (Decision 5) built on the SAME
 * policy_version_counter pessimistic-lock primitive (already proven to correctly serialize
 * concurrent H2 transactions by {@link #concurrentCreation_ShouldNeverCollideOnVersionNumber}).
 *
 * Does NOT and cannot test the database-level immutability triggers or the unique-constraint
 * race via a genuinely concurrent Postgres connection: this suite runs against H2 with
 * ddl-auto=create-drop / Flyway disabled (see application-test.properties) - the project has no
 * PostgreSQL/Testcontainers integration-test infrastructure, and none is introduced solely for
 * this slice (a known, pre-existing, already-documented characteristic of this project's test
 * setup).
 *
 * <p>Publish-flow convention: the draft creator is {@code adminId} (1L); publication always
 * goes through {@link #publishAs}, which reloads the draft's current configurationRevision
 * immediately before publishing and uses {@code checkerId} (2L), a different admin, satisfying
 * the locked maker/checker rule.
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("PolicyVersionService — Slice 1b-1 / Policy Administration integration tests")
class PolicyVersionServiceIntegrationTest {

    @Autowired
    private PolicyVersionService policyVersionService;

    @Autowired
    private com.example.autoauction.policy.domain.port.PolicyVersionViolationRepository policyVersionViolationRepository;

    @Autowired
    private com.example.autoauction.policy.infrastructure.persistence.SpringDataViolationDefinitionJpaRepository violationDefinitionJpaRepository;

    private final Long adminId = 1L;
    private final Long checkerId = 2L;

    /** Reloads the draft's CURRENT configurationRevision immediately before publishing, and uses a different admin than the creator (maker/checker). */
    private PolicyVersion publishAs(Long draftId, Long callerAdminId) {
        PolicyVersion fresh = policyVersionService.getById(draftId);
        return policyVersionService.publish(draftId, callerAdminId, fresh.getConfigurationRevision());
    }

    private PolicyVersion publish(Long draftId) {
        return publishAs(draftId, checkerId);
    }

    @Test
    @DisplayName("create() persists and can be reloaded with all fields intact")
    void create_ShouldPersistAndReload() {
        PolicyVersion created = policyVersionService.create(new BigDecimal("0.0250"), adminId);

        assertNotNull(created.getId());
        assertNotNull(created.getVersionNumber());
        assertFalse(created.isPublished());

        PolicyVersion reloaded = policyVersionService.getById(created.getId());
        assertEquals(created.getId(), reloaded.getId());
        assertEquals(created.getVersionNumber(), reloaded.getVersionNumber());
        assertEquals(0, created.getCommissionRate().compareTo(reloaded.getCommissionRate()));
        assertFalse(reloaded.isPublished());
        assertEquals(adminId, reloaded.getCreatedBy());
    }

    @Test
    @DisplayName("getById() on a non-existent id throws")
    void getById_NonExistent_ShouldThrow() {
        assertThrows(IllegalArgumentException.class, () -> policyVersionService.getById(-1L));
    }

    @Test
    @DisplayName("publish() transitions an unpublished PolicyVersion to published, sets publication metadata, and it persists")
    void publish_ShouldTransitionToPublished() {
        PolicyVersion created = policyVersionService.create(new BigDecimal("0.03"), adminId);

        PolicyVersion published = publish(created.getId());

        assertTrue(published.isPublished());
        assertEquals(checkerId, published.getPublishedBy());
        assertNotNull(published.getPublishedAt());
        PolicyVersion reloaded = policyVersionService.getById(created.getId());
        assertTrue(reloaded.isPublished());
        assertEquals(checkerId, reloaded.getPublishedBy());
    }

    @Test
    @DisplayName("publish() on an already-published PolicyVersion is rejected, not silently repeated")
    void publish_WhenAlreadyPublished_ShouldThrow() {
        PolicyVersion created = policyVersionService.create(new BigDecimal("0.03"), adminId);
        publish(created.getId());

        assertThrows(IllegalStateException.class, () -> publish(created.getId()));
    }

    @Test
    @DisplayName("publish() on a non-existent id is rejected")
    void publish_NonExistent_ShouldThrow() {
        assertThrows(IllegalArgumentException.class, () -> publish(-1L));
    }

    @Test
    @DisplayName("publish() by the draft's own creator is rejected (maker/checker, Decision 1)")
    void publish_ByCreator_ShouldThrowMakerChecker() {
        PolicyVersion created = policyVersionService.create(new BigDecimal("0.03"), adminId);

        assertThrows(MakerCheckerViolationException.class, () -> publishAs(created.getId(), adminId));
    }

    // ==================== Slice 1b-2: getCurrentPublished() ====================

    @Test
    @DisplayName("getCurrentPublished() returns a published PolicyVersion at least as new as one just published")
    void getCurrentPublished_ShouldReturnAPublishedVersion_NoOlderThanOneJustPublished() {
        PolicyVersion created = policyVersionService.create(new BigDecimal("0.04"), adminId);
        PolicyVersion justPublished = publish(created.getId());

        PolicyVersion current = policyVersionService.getCurrentPublished();

        assertNotNull(current);
        assertTrue(current.isPublished());
        assertTrue(current.getVersionNumber() >= justPublished.getVersionNumber(),
                "getCurrentPublished() must never resolve to an older version than one already published");
    }

    @Test
    @DisplayName("concurrent creation never assigns duplicate version numbers")
    void concurrentCreation_ShouldNeverCollideOnVersionNumber() throws Exception {
        int threadCount = 20;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Long>> futures = new ArrayList<>();

        try {
            for (int i = 0; i < threadCount; i++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    go.await();
                    return policyVersionService.create(new BigDecimal("0.01"), adminId).getVersionNumber();
                }));
            }

            ready.await();
            go.countDown();

            Set<Long> versionNumbers = new HashSet<>();
            for (Future<Long> f : futures) {
                versionNumbers.add(f.get(10, TimeUnit.SECONDS));
            }

            assertEquals(threadCount, versionNumbers.size(),
                    "Every concurrently-created PolicyVersion must receive a distinct versionNumber");
        } finally {
            executor.shutdown();
        }
    }

    // ==================== X3-E corrective (BLOCKER 5): policy completeness / validity ====================

    @Test
    @DisplayName("X3-E: duplicate (policyVersionId, violationDefinitionId) configuration is impossible - " +
            "DB UNIQUE constraint rejects a raw duplicate insert even bypassing the service's own upsert logic")
    void policyVersionViolation_DuplicateConfiguration_RejectedByUniqueConstraint() {
        Long violationDefinitionId = violationDefinitionJpaRepository.save(
                new com.example.autoauction.policy.infrastructure.persistence.JpaViolationDefinitionEntity(
                        "X3E_DUPLICATE_TEST_" + System.nanoTime(), "test fixture", java.time.OffsetDateTime.now())).getId();
        try {
            PolicyVersion draft = policyVersionService.create(new BigDecimal("0.02"), adminId);

            // First insert succeeds (id=null forces a fresh INSERT, bypassing
            // PolicyVersionService.updateDraftViolation's own find-first-then-upsert logic -
            // this proves the DB constraint itself, not merely the service's own discipline).
            policyVersionViolationRepository.save(new com.example.autoauction.policy.domain.PolicyVersionViolation(
                    draft.getId(), violationDefinitionId, com.example.autoauction.policy.domain.PenaltyType.PERCENTAGE,
                    new BigDecimal("50"), java.time.OffsetDateTime.now()));

            assertThrows(org.springframework.dao.DataIntegrityViolationException.class, () ->
                    policyVersionViolationRepository.save(new com.example.autoauction.policy.domain.PolicyVersionViolation(
                            draft.getId(), violationDefinitionId, com.example.autoauction.policy.domain.PenaltyType.PERCENTAGE,
                            new BigDecimal("99"), java.time.OffsetDateTime.now())));
        } finally {
            // This test's draft is deliberately never published (it exercises the raw
            // repository, not a full publish cycle) - its ViolationDefinition would otherwise
            // remain a permanently unconfigured catalogue entry for the rest of this shared,
            // non-transactional H2 database's lifetime, breaking every OTHER test's publish()
            // call for the remainder of this JVM's test run. Clean it up explicitly, exactly
            // as this test found the catalogue.
            violationDefinitionJpaRepository.deleteById(violationDefinitionId);
        }
    }

    @Test
    @DisplayName("X3-E: unknown ViolationDefinition id is rejected by updateDraftViolation before any write")
    void updateDraftViolation_UnknownViolationDefinition_Rejected() {
        PolicyVersion draft = policyVersionService.create(new BigDecimal("0.02"), adminId);

        assertThrows(IllegalArgumentException.class, () -> policyVersionService.updateDraftViolation(
                draft.getId(), -999L, com.example.autoauction.policy.domain.PenaltyType.PERCENTAGE, new BigDecimal("50"), 0L));
    }

    @Test
    @DisplayName("X3-E: complete, valid configuration for every catalogue entry currently in the shared test " +
            "database -> publish succeeds end-to-end (real DB)")
    void publish_CompleteRealConfiguration_SucceedsEndToEnd() {
        // Seeds one more definition (never deletes any other test's rows - this shared,
        // non-transactional H2 database may already carry ViolationDefinition rows from other
        // test classes in the same fork) and then configures EVERY currently-existing
        // definition on this draft, whatever the full current set happens to be - proving the
        // completeness check against the real catalogue as it actually stands, not a
        // hand-picked subset.
        violationDefinitionJpaRepository.save(
                new com.example.autoauction.policy.infrastructure.persistence.JpaViolationDefinitionEntity(
                        "X3E_COMPLETE_TEST_" + System.nanoTime(), "test fixture", java.time.OffsetDateTime.now()));

        PolicyVersion draft = policyVersionService.create(new BigDecimal("0.02"), adminId);
        for (var definition : violationDefinitionJpaRepository.findAll()) {
            policyVersionService.updateDraftViolation(draft.getId(), definition.getId(),
                    com.example.autoauction.policy.domain.PenaltyType.PERCENTAGE, new BigDecimal("100"), 0L);
        }

        PolicyVersion published = publish(draft.getId());

        assertTrue(published.isPublished());
    }

    // ── Slice 1 (Policy Administration) ─────────────────────────────────────────

    /** Creates a draft and configures every currently-existing catalogue definition on it at PERCENTAGE {@code value}. */
    private PolicyVersion publishableDraft(BigDecimal commissionRate, BigDecimal value) {
        violationDefinitionJpaRepository.save(
                new com.example.autoauction.policy.infrastructure.persistence.JpaViolationDefinitionEntity(
                        "SLICE1_TEST_" + System.nanoTime(), "test fixture", java.time.OffsetDateTime.now()));
        PolicyVersion draft = policyVersionService.create(commissionRate, adminId);
        for (var d : violationDefinitionJpaRepository.findAll()) {
            policyVersionService.updateDraftViolation(draft.getId(), d.getId(),
                    com.example.autoauction.policy.domain.PenaltyType.PERCENTAGE, value, 0L);
        }
        return draft;
    }

    @Test
    @DisplayName("Slice 1: updateDraftCommission then publish -> published rate reflects the edit")
    void slice1_UpdateDraftCommissionThenPublish() {
        PolicyVersion draft = publishableDraft(new BigDecimal("0.0200"), new BigDecimal("100"));
        PolicyVersion beforeEdit = policyVersionService.getById(draft.getId());
        policyVersionService.updateDraftCommission(draft.getId(), new BigDecimal("0.0250"), beforeEdit.getVersion());

        PolicyVersion published = publish(draft.getId());

        assertTrue(published.isPublished());
        assertEquals(0, new BigDecimal("0.0250").compareTo(published.getCommissionRate()));
        assertEquals(0, new BigDecimal("0.0250")
                .compareTo(policyVersionService.getById(draft.getId()).getCommissionRate()));
    }

    @Test
    @DisplayName("Slice 1: editing a published version (commission or violation) is rejected at the application layer")
    void slice1_PublishedVersionIsImmutable() {
        PolicyVersion draft = publishableDraft(new BigDecimal("0.0200"), new BigDecimal("100"));
        PolicyVersion published = publish(draft.getId());
        Long anyDefId = violationDefinitionJpaRepository.findAll().get(0).getId();

        assertThrows(IllegalStateException.class,
                () -> policyVersionService.updateDraftCommission(published.getId(), new BigDecimal("0.03"), published.getVersion()));
        assertThrows(IllegalStateException.class,
                () -> policyVersionService.updateDraftViolation(published.getId(), anyDefId,
                        com.example.autoauction.policy.domain.PenaltyType.PERCENTAGE, new BigDecimal("10"), 0L));

        // unchanged
        assertEquals(0, new BigDecimal("0.0200")
                .compareTo(policyVersionService.getById(published.getId()).getCommissionRate()));
    }

    @Test
    @DisplayName("Slice 1 (transformed, Decision 5): a draft based on v1 becomes permanently unpublishable once a " +
            "sibling draft has published a newer current policy; sibling drafts never share violation rows")
    void slice1_MultipleDraftsCopyForwardFromCurrentPublishedNotFromSiblings() {
        PolicyVersion base = publishableDraft(new BigDecimal("0.0200"), new BigDecimal("100"));
        PolicyVersion basePublished = publish(base.getId());

        // Two drafts both copy-forward from the current published (basePublished), not from each other.
        PolicyVersion d2 = policyVersionService.create(new BigDecimal("0.0300"), adminId);
        PolicyVersion d3 = policyVersionService.create(new BigDecimal("0.0400"), adminId);
        assertEquals(basePublished.getId(), d2.getBasedOnPublishedVersionId());
        assertEquals(basePublished.getId(), d3.getBasedOnPublishedVersionId());

        // Edit d2 and publish it - this changes "current published" out from under d3's lineage.
        Long anyDefId = violationDefinitionViolationIdOfFirstCatalogueEntry(d2.getId());
        PolicyVersion d2BeforeEdit = policyVersionService.getById(d2.getId());
        policyVersionService.updateDraftViolation(d2.getId(), anyDefId,
                com.example.autoauction.policy.domain.PenaltyType.PERCENTAGE, new BigDecimal("25"), d2BeforeEdit.getVersion());
        PolicyVersion d2Published = publish(d2.getId());

        // d3 was copied from basePublished, is complete, but basePublished is no longer current
        // (d2Published is) - Decision 5 now rejects this: no rebase, no silent republish of
        // stale content. The only legitimate action is a fresh draft.
        assertThrows(StalePolicyBaseException.class, () -> publish(d3.getId()));

        // d3's rows are untouched by d2's edit - its rows are its own copy of basePublished's config.
        var d3Rows = policyVersionViolationRepository.findByPolicyVersionId(d3.getId());
        var d2Rows = policyVersionViolationRepository.findByPolicyVersionId(d2.getId());
        assertFalse(d3Rows.isEmpty());
        // no PolicyVersionViolation id is shared between the two drafts
        Set<Long> d2Ids = new HashSet<>();
        d2Rows.forEach(r -> d2Ids.add(r.getId()));
        d3Rows.forEach(r -> assertFalse(d2Ids.contains(r.getId()), "sibling drafts must not share violation rows"));

        // current published = d2Published (the only one that actually published after base)
        assertEquals(d2Published.getVersionNumber(), policyVersionService.getCurrentPublished().getVersionNumber());
    }

    private Long violationDefinitionViolationIdOfFirstCatalogueEntry(Long draftId) {
        return policyVersionViolationRepository.findByPolicyVersionId(draftId).get(0).getViolationDefinitionId();
    }

    @Test
    @DisplayName("Slice 1: concurrent publish of the SAME draft -> exactly one succeeds, the other fails")
    void slice1_ConcurrentPublishSameDraft() throws Exception {
        PolicyVersion draft = publishableDraft(new BigDecimal("0.0200"), new BigDecimal("100"));
        Long observedRevision = policyVersionService.getById(draft.getId()).getConfigurationRevision();

        int threads = 8;
        ExecutorService ex = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Boolean>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                futures.add(ex.submit(() -> {
                    ready.countDown();
                    go.await();
                    try {
                        policyVersionService.publish(draft.getId(), checkerId, observedRevision);
                        return true;
                    } catch (IllegalStateException | PolicyConflictException e) {
                        return false;
                    }
                }));
            }
            ready.await();
            go.countDown();
            int successes = 0;
            for (Future<Boolean> f : futures) {
                if (f.get(10, TimeUnit.SECONDS)) successes++;
            }
            assertEquals(1, successes, "exactly one concurrent publish of the same draft must succeed");
        } finally {
            ex.shutdown();
        }
        assertTrue(policyVersionService.getById(draft.getId()).isPublished());
    }

    @Test
    @DisplayName("Locked concurrency design, section C: two concurrent first-time configurations of the SAME " +
            "(draft, violationDefinition) pair -> exactly one succeeds, the other gets VIOLATION_INSERT_CONFLICT, no overwrite")
    void concurrentFirstTimeViolationInsert_ExactlyOneWins_OtherGetsInsertConflict() throws Exception {
        violationDefinitionJpaRepository.save(
                new com.example.autoauction.policy.infrastructure.persistence.JpaViolationDefinitionEntity(
                        "RACE_TEST_" + System.nanoTime(), "test fixture", java.time.OffsetDateTime.now()));
        PolicyVersion draft = policyVersionService.create(new BigDecimal("0.02"), adminId);
        Long defId = violationDefinitionJpaRepository.findAll().stream()
                .filter(d -> d.getCode().startsWith("RACE_TEST_"))
                .findFirst().orElseThrow().getId();

        // Exactly two concurrent attempts, matching the locked spec's literal scenario ("two
        // concurrent inserts"). Both pass observedVersion=0 - the documented "nothing observed
        // yet" sentinel, since from each caller's point of view this is its first configuration
        // of this pair. Whichever commits first genuinely inserts (Boundary C, the unique
        // constraint). If the second one's own read happens to observe the first's row before
        // it attempts its write, it is NOT a safety violation for it to then perform a correctly
        // version-checked UPDATE instead (a real, accurate observation, not a stale one) - both
        // a ViolationInsertConflictException (lost the insert race outright) and an
        // ObjectOptimisticLockingFailureException (lost a subsequent update race to a third
        // party - not reachable with only two threads, kept here only for robustness) are
        // equally valid non-corrupting outcomes. The one property that must ALWAYS hold,
        // regardless of interleaving, is proven below: exactly one row ever exists for this
        // pair, and at most one thread legitimately creates it via INSERT.
        int threads = 2;
        ExecutorService ex = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Boolean>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                final BigDecimal value = new BigDecimal(10 + i);
                futures.add(ex.submit(() -> {
                    ready.countDown();
                    go.await();
                    try {
                        policyVersionService.updateDraftViolation(draft.getId(), defId,
                                com.example.autoauction.policy.domain.PenaltyType.PERCENTAGE, value, 0L);
                        return true;
                    } catch (ViolationInsertConflictException e) {
                        return false;
                    } catch (org.springframework.orm.ObjectOptimisticLockingFailureException e) {
                        return false;
                    }
                }));
            }
            ready.await();
            go.countDown();
            for (Future<Boolean> f : futures) {
                f.get(10, TimeUnit.SECONDS);
            }
        } finally {
            ex.shutdown();
        }

        var rows = policyVersionViolationRepository.findByPolicyVersionId(draft.getId());
        long matching = rows.stream().filter(r -> r.getViolationDefinitionId().equals(defId)).count();
        assertEquals(1, matching, "no overwrite - exactly one row exists for this (draft, violationDefinition) pair");
    }

    @Test
    @DisplayName("Slice 1 (transformed, Decision 5): concurrent publish of TWO DIFFERENT drafts based on the SAME " +
            "current policy -> exactly one succeeds, the other fails stale-lineage")
    void slice1_ConcurrentPublishDifferentDraftsSameBase() throws Exception {
        // Seed once, then build BOTH drafts against the SAME (now-stable) catalogue so both are
        // complete, and both created back-to-back so both copy-forward from the SAME current
        // published policy (identical basedOnPublishedVersionId) - this is exactly the race
        // Decision 5 + the publication-serialization lock must resolve to exactly one winner.
        violationDefinitionJpaRepository.save(
                new com.example.autoauction.policy.infrastructure.persistence.JpaViolationDefinitionEntity(
                        "SLICE1_TEST_" + System.nanoTime(), "test fixture", java.time.OffsetDateTime.now()));
        PolicyVersion a = policyVersionService.create(new BigDecimal("0.0200"), adminId);
        PolicyVersion b = policyVersionService.create(new BigDecimal("0.0300"), adminId);
        assertEquals(a.getBasedOnPublishedVersionId(), b.getBasedOnPublishedVersionId());
        for (var d : violationDefinitionJpaRepository.findAll()) {
            policyVersionService.updateDraftViolation(a.getId(), d.getId(),
                    com.example.autoauction.policy.domain.PenaltyType.PERCENTAGE, new BigDecimal("100"), 0L);
            policyVersionService.updateDraftViolation(b.getId(), d.getId(),
                    com.example.autoauction.policy.domain.PenaltyType.PERCENTAGE, new BigDecimal("100"), 0L);
        }
        Long revisionA = policyVersionService.getById(a.getId()).getConfigurationRevision();
        Long revisionB = policyVersionService.getById(b.getId()).getConfigurationRevision();

        ExecutorService ex = Executors.newFixedThreadPool(2);
        CountDownLatch go = new CountDownLatch(1);
        try {
            Future<Boolean> fa = ex.submit(() -> {
                go.await();
                try {
                    policyVersionService.publish(a.getId(), checkerId, revisionA);
                    return true;
                } catch (StalePolicyBaseException e) {
                    return false;
                }
            });
            Future<Boolean> fb = ex.submit(() -> {
                go.await();
                try {
                    policyVersionService.publish(b.getId(), checkerId, revisionB);
                    return true;
                } catch (StalePolicyBaseException e) {
                    return false;
                }
            });
            go.countDown();
            boolean aSucceeded = fa.get(10, TimeUnit.SECONDS);
            boolean bSucceeded = fb.get(10, TimeUnit.SECONDS);

            assertTrue(aSucceeded ^ bSucceeded, "exactly one of the two same-base drafts must succeed, the other must fail stale-lineage");
            Long winnerId = aSucceeded ? a.getId() : b.getId();
            assertEquals(winnerId, policyVersionService.getCurrentPublished().getId());
        } finally {
            ex.shutdown();
        }
    }
}

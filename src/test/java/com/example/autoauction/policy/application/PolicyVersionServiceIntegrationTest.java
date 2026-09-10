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
 * Slice 1b-1: real-persistence proof of PolicyVersionService end-to-end, including the
 * version-number concurrency guarantee. Deliberately NOT wrapped in a class-level
 * {@code @Transactional} - the concurrency test specifically needs each concurrent call to run
 * in its own independent transaction, not share the test method's.
 *
 * Does NOT and cannot test the database-level immutability trigger
 * (policy_version_prevent_published_mutation): this test suite runs against H2 with
 * spring.jpa.hibernate.ddl-auto=create-drop and spring.flyway.enabled=false (see
 * application-test.properties), so Flyway migrations - including the trigger created in
 * V32__policy_version_foundation.sql - are never executed against the test database. The
 * project currently has no PostgreSQL/Testcontainers integration-test infrastructure, and per
 * the Slice 1b-1 scope, no such framework is introduced solely for this sub-slice. The trigger
 * is exercised only against the real, Flyway-migrated PostgreSQL database - this is a known,
 * pre-existing characteristic of this project's test setup (it already could not exercise any
 * Postgres-specific DDL in V1-V31 either), not a gap introduced by this slice.
 *
 * X3-E corrective (BLOCKER 5): the same limitation applies identically to V38's sibling
 * trigger (policy_version_violation_prevent_published_mutation) - it too is pure Flyway SQL
 * with no JPA-annotation equivalent, so it cannot be exercised here either. What CAN be, and
 * is, proven here: (1) PolicyVersionViolation's own domain-constructor validation (percentage/
 * fixed-amount bounds - see PolicyVersionViolationTest), (2) the UNIQUE(policy_version_id,
 * violation_definition_id) constraint, which IS mirrored at the JPA-annotation level on
 * JpaPolicyVersionViolationEntity (same deliberate double-declaration precedent as
 * JpaSellerDiagnosticCommitmentEntity's own uniqueConstraints - see that class's Javadoc) and
 * therefore DOES apply under ddl-auto=create-drop, and (3) PolicyVersionService's own
 * application-level published-check in updateDraftViolation (see PolicyVersionServiceTest).
 * Only the DB trigger's enforcement of published-row immutability itself is untestable here -
 * defense-in-depth beyond the already-tested application-level check, exactly mirroring
 * policy_version's own accepted, pre-existing gap.
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("PolicyVersionService — Slice 1b-1 integration tests")
class PolicyVersionServiceIntegrationTest {

    @Autowired
    private PolicyVersionService policyVersionService;

    @Autowired
    private com.example.autoauction.policy.domain.port.PolicyVersionViolationRepository policyVersionViolationRepository;

    @Autowired
    private com.example.autoauction.policy.infrastructure.persistence.SpringDataViolationDefinitionJpaRepository violationDefinitionJpaRepository;

    private final Long adminId = 1L;

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
    @DisplayName("publish() transitions an unpublished PolicyVersion to published, and it persists")
    void publish_ShouldTransitionToPublished() {
        PolicyVersion created = policyVersionService.create(new BigDecimal("0.03"), adminId);

        PolicyVersion published = policyVersionService.publish(created.getId());

        assertTrue(published.isPublished());
        assertTrue(policyVersionService.getById(created.getId()).isPublished());
    }

    @Test
    @DisplayName("publish() on an already-published PolicyVersion is rejected, not silently repeated")
    void publish_WhenAlreadyPublished_ShouldThrow() {
        PolicyVersion created = policyVersionService.create(new BigDecimal("0.03"), adminId);
        policyVersionService.publish(created.getId());

        assertThrows(IllegalStateException.class, () -> policyVersionService.publish(created.getId()));
    }

    @Test
    @DisplayName("publish() on a non-existent id is rejected the same way as already-published")
    void publish_NonExistent_ShouldThrow() {
        assertThrows(IllegalStateException.class, () -> policyVersionService.publish(-1L));
    }

    // ==================== Slice 1b-2: getCurrentPublished() ====================
    //
    // These run against the same shared, non-transactional H2 database as the rest of this test
    // class (see the class Javadoc), so other test methods may have already published policies
    // before or after this one runs. Assertions are therefore deliberately order-independent:
    // they never assert an exact identity/versionNumber against "nothing else exists", only
    // properties that hold regardless of what else has been published (isPublished()==true, and
    // versionNumber being at least as high as the one this test itself just published, since
    // getCurrentPublished() is defined as the highest published versionNumber).

    @Test
    @DisplayName("getCurrentPublished() returns a published PolicyVersion at least as new as one just published")
    void getCurrentPublished_ShouldReturnAPublishedVersion_NoOlderThanOneJustPublished() {
        PolicyVersion created = policyVersionService.create(new BigDecimal("0.04"), adminId);
        PolicyVersion justPublished = policyVersionService.publish(created.getId());

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
                draft.getId(), -999L, com.example.autoauction.policy.domain.PenaltyType.PERCENTAGE, new BigDecimal("50")));
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
                    com.example.autoauction.policy.domain.PenaltyType.PERCENTAGE, new BigDecimal("100"));
        }

        PolicyVersion published = policyVersionService.publish(draft.getId());

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
                    com.example.autoauction.policy.domain.PenaltyType.PERCENTAGE, value);
        }
        return draft;
    }

    @Test
    @DisplayName("Slice 1: updateDraftCommission then publish -> published rate reflects the edit")
    void slice1_UpdateDraftCommissionThenPublish() {
        PolicyVersion draft = publishableDraft(new BigDecimal("0.0200"), new BigDecimal("100"));
        policyVersionService.updateDraftCommission(draft.getId(), new BigDecimal("0.0250"));

        PolicyVersion published = policyVersionService.publish(draft.getId());

        assertTrue(published.isPublished());
        assertEquals(0, new BigDecimal("0.0250").compareTo(published.getCommissionRate()));
        assertEquals(0, new BigDecimal("0.0250")
                .compareTo(policyVersionService.getById(draft.getId()).getCommissionRate()));
    }

    @Test
    @DisplayName("Slice 1: editing a published version (commission or violation) is rejected at the application layer")
    void slice1_PublishedVersionIsImmutable() {
        PolicyVersion draft = publishableDraft(new BigDecimal("0.0200"), new BigDecimal("100"));
        PolicyVersion published = policyVersionService.publish(draft.getId());
        Long anyDefId = violationDefinitionJpaRepository.findAll().get(0).getId();

        assertThrows(IllegalStateException.class,
                () -> policyVersionService.updateDraftCommission(published.getId(), new BigDecimal("0.03")));
        assertThrows(IllegalStateException.class,
                () -> policyVersionService.updateDraftViolation(published.getId(), anyDefId,
                        com.example.autoauction.policy.domain.PenaltyType.PERCENTAGE, new BigDecimal("10")));

        // unchanged
        assertEquals(0, new BigDecimal("0.0200")
                .compareTo(policyVersionService.getById(published.getId()).getCommissionRate()));
    }

    @Test
    @DisplayName("Slice 1: a draft based on v1 stays publishable after a sibling draft was published; " +
            "sibling drafts never share violation rows")
    void slice1_MultipleDraftsCopyForwardFromCurrentPublishedNotFromSiblings() {
        PolicyVersion base = publishableDraft(new BigDecimal("0.0200"), new BigDecimal("100"));
        PolicyVersion basePublished = policyVersionService.publish(base.getId());

        // Two drafts both copy-forward from the current published (basePublished), not from each other.
        PolicyVersion d2 = policyVersionService.create(new BigDecimal("0.0300"), adminId);
        PolicyVersion d3 = policyVersionService.create(new BigDecimal("0.0400"), adminId);

        // Edit d2 and publish it.
        Long anyDefId = violationDefinitionViolationIdOfFirstCatalogueEntry(d2.getId());
        policyVersionService.updateDraftViolation(d2.getId(), anyDefId,
                com.example.autoauction.policy.domain.PenaltyType.PERCENTAGE, new BigDecimal("25"));
        PolicyVersion d2Published = policyVersionService.publish(d2.getId());

        // d3 was copied from basePublished, is complete, and is still publishable (stale base is allowed).
        PolicyVersion d3Published = policyVersionService.publish(d3.getId());
        assertTrue(d3Published.isPublished());

        // d3 did NOT pick up d2's edit - its rows are its own copy of basePublished's config.
        var d3Rows = policyVersionViolationRepository.findByPolicyVersionId(d3.getId());
        var d2Rows = policyVersionViolationRepository.findByPolicyVersionId(d2.getId());
        assertFalse(d3Rows.isEmpty());
        // no PolicyVersionViolation id is shared between the two drafts
        Set<Long> d2Ids = new HashSet<>();
        d2Rows.forEach(r -> d2Ids.add(r.getId()));
        d3Rows.forEach(r -> assertFalse(d2Ids.contains(r.getId()), "sibling drafts must not share violation rows"));

        // current published = the highest versionNumber among {basePublished, d2Published, d3Published}
        long maxVn = Math.max(basePublished.getVersionNumber(),
                Math.max(d2Published.getVersionNumber(), d3Published.getVersionNumber()));
        assertEquals(maxVn, policyVersionService.getCurrentPublished().getVersionNumber());
    }

    private Long violationDefinitionViolationIdOfFirstCatalogueEntry(Long draftId) {
        return policyVersionViolationRepository.findByPolicyVersionId(draftId).get(0).getViolationDefinitionId();
    }

    @Test
    @DisplayName("Slice 1: concurrent publish of the SAME draft -> exactly one succeeds, the other fails")
    void slice1_ConcurrentPublishSameDraft() throws Exception {
        PolicyVersion draft = publishableDraft(new BigDecimal("0.0200"), new BigDecimal("100"));

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
                        policyVersionService.publish(draft.getId());
                        return true;
                    } catch (IllegalStateException e) {
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
    @DisplayName("Slice 1: concurrent publish of DIFFERENT complete drafts -> both succeed, distinct versionNumbers")
    void slice1_ConcurrentPublishDifferentDrafts() throws Exception {
        // Seed once, then build BOTH drafts against the SAME (now-stable) catalogue so both are complete.
        violationDefinitionJpaRepository.save(
                new com.example.autoauction.policy.infrastructure.persistence.JpaViolationDefinitionEntity(
                        "SLICE1_TEST_" + System.nanoTime(), "test fixture", java.time.OffsetDateTime.now()));
        PolicyVersion a = policyVersionService.create(new BigDecimal("0.0200"), adminId);
        PolicyVersion b = policyVersionService.create(new BigDecimal("0.0300"), adminId);
        for (var d : violationDefinitionJpaRepository.findAll()) {
            policyVersionService.updateDraftViolation(a.getId(), d.getId(),
                    com.example.autoauction.policy.domain.PenaltyType.PERCENTAGE, new BigDecimal("100"));
            policyVersionService.updateDraftViolation(b.getId(), d.getId(),
                    com.example.autoauction.policy.domain.PenaltyType.PERCENTAGE, new BigDecimal("100"));
        }

        ExecutorService ex = Executors.newFixedThreadPool(2);
        CountDownLatch go = new CountDownLatch(1);
        try {
            Future<PolicyVersion> fa = ex.submit(() -> { go.await(); return policyVersionService.publish(a.getId()); });
            Future<PolicyVersion> fb = ex.submit(() -> { go.await(); return policyVersionService.publish(b.getId()); });
            go.countDown();
            PolicyVersion pa = fa.get(10, TimeUnit.SECONDS);
            PolicyVersion pb = fb.get(10, TimeUnit.SECONDS);
            assertTrue(pa.isPublished());
            assertTrue(pb.isPublished());
            assertFalse(pa.getVersionNumber().equals(pb.getVersionNumber()));
            long higher = Math.max(pa.getVersionNumber(), pb.getVersionNumber());
            assertEquals(higher, policyVersionService.getCurrentPublished().getVersionNumber());
        } finally {
            ex.shutdown();
        }
    }
}

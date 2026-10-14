package com.example.autoauction.identity.application;

import com.example.autoauction.identity.domain.DocumentType;
import com.example.autoauction.identity.domain.IdentityDocument;
import com.example.autoauction.identity.domain.IdentityEventType;
import com.example.autoauction.identity.domain.IdentityVerification;
import com.example.autoauction.identity.domain.IdentityVerificationHistory;
import com.example.autoauction.identity.domain.IdentityVerificationStatus;
import com.example.autoauction.identity.domain.port.IdentityDocumentRepository;
import com.example.autoauction.identity.domain.port.IdentityVerificationHistoryRepository;
import com.example.autoauction.identity.domain.port.IdentityVerificationRepository;
import com.example.autoauction.support.AbstractPostgresIntegrationTest;
import com.example.autoauction.user.domain.Role;
import com.example.autoauction.user.domain.User;
import com.example.autoauction.user.domain.port.RoleRepository;
import com.example.autoauction.user.domain.port.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 4 §15 (the CRITICAL concurrency category) / Phase 3.1 §1 / Phase 4.2: real-PostgreSQL,
 * real-thread proof of the approval-conflict pattern, calling exclusively through
 * {@link AdminIdentityApprovalOrchestrator#approveWithConflictHandling} - the separate bean that
 * makes {@link AdminIdentityVerificationService#approve}'s {@code @Transactional} and
 * {@link AdminIdentityVerificationService#recordFailedApprovalAttempt}'s {@code REQUIRES_NEW}
 * actually apply (Phase 4.1's trace audit proved same-bean self-invocation silently bypassed
 * both). Mirrors {@code SettlementConcurrencyPgIntegrationTest}'s own proven
 * two-thread/CyclicBarrier convention against real PostgreSQL - the partial unique index race
 * cannot be faithfully exercised on H2.
 */
@DisplayName("Phase 3.1 §1 / Phase 4.2: identity approval real-PostgreSQL concurrency proof")
class AdminIdentityApprovalConcurrencyPgIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired private AdminIdentityApprovalOrchestrator adminIdentityApprovalOrchestrator;
    @Autowired private IdentityVerificationRepository identityVerificationRepository;
    @Autowired private IdentityVerificationHistoryRepository historyRepository;
    @Autowired private IdentityDocumentRepository documentRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;

    private Long newUser() {
        Role role = roleRepository.findByName("ROLE_USER")
                .orElseGet(() -> roleRepository.save(new Role(null, "ROLE_USER", "Обычный пользователь")));
        User u = new User(null, "idconc" + System.nanoTime(), "idconc" + System.nanoTime() + "@test.com",
                "encoded", true, Set.of(role));
        return userRepository.save(u).getId();
    }

    private Long newPendingVerification(Long userId, String canonicalDocumentNumber) {
        Long docId = documentRepository.save(new IdentityDocument(
                "identity-documents/" + System.nanoTime(), userId, "image/jpeg", 1000L, OffsetDateTime.now())).getId();
        IdentityVerification iv = identityVerificationRepository.save(IdentityVerification.firstSubmission(
                userId, "KG", DocumentType.NATIONAL_ID, canonicalDocumentNumber, docId, docId, OffsetDateTime.now()));
        historyRepository.save(IdentityVerificationHistory.submission(iv.getId(), canonicalDocumentNumber, docId, OffsetDateTime.now()));
        return iv.getId();
    }

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

    @RepeatedTest(5)
    @DisplayName("two admins approve two DIFFERENT accounts carrying the SAME canonical identity concurrently: exactly one VERIFIED, exactly one failed-attempt audit row, loser gets handled 409, winner untouched")
    void approve_ConcurrentCrossAccountSameIdentity_ExactlyOneWinsOtherFailsClosedWithAudit() throws InterruptedException {
        String sharedIdentity = "CONC" + System.nanoTime() % 1_000_000;
        Long userA = newUser();
        Long userB = newUser();
        Long ivIdA = newPendingVerification(userA, sharedIdentity);
        Long ivIdB = newPendingVerification(userB, sharedIdentity);
        Long adminX = 11L;
        Long adminY = 22L;

        AtomicReference<IdentityVerification> resultX = new AtomicReference<>();
        AtomicReference<IdentityVerification> resultY = new AtomicReference<>();
        AtomicReference<Throwable> errX = new AtomicReference<>();
        AtomicReference<Throwable> errY = new AtomicReference<>();

        runConcurrently(
                () -> { try { resultX.set(adminIdentityApprovalOrchestrator.approveWithConflictHandling(ivIdA, adminX)); }
                        catch (Throwable t) { errX.set(t); } },
                () -> { try { resultY.set(adminIdentityApprovalOrchestrator.approveWithConflictHandling(ivIdB, adminY)); }
                        catch (Throwable t) { errY.set(t); } });

        boolean xFailed = errX.get() != null;
        boolean yFailed = errY.get() != null;
        assertThat(xFailed ^ yFailed)
                .as("exactly one of the two cross-account attempts must fail closed, errX=%s, errY=%s", errX.get(), errY.get())
                .isTrue();
        Throwable loserError = xFailed ? errX.get() : errY.get();
        assertThat(loserError).isInstanceOf(IllegalStateException.class);

        IdentityVerification freshA = identityVerificationRepository.findById(ivIdA).orElseThrow();
        IdentityVerification freshB = identityVerificationRepository.findById(ivIdB).orElseThrow();
        long verifiedCount = List.of(freshA, freshB).stream()
                .filter(iv -> iv.getStatus() == IdentityVerificationStatus.VERIFIED).count();
        assertThat(verifiedCount).as("exactly one of the two rows is VERIFIED").isEqualTo(1);
        IdentityVerification loserRow = freshA.getStatus() == IdentityVerificationStatus.VERIFIED ? freshB : freshA;
        assertThat(loserRow.getStatus())
                .as("the loser's row must remain untouched (still PENDING_REVIEW) - no partial state change")
                .isEqualTo(IdentityVerificationStatus.PENDING_REVIEW);

        Long winnerIvId = freshA.getStatus() == IdentityVerificationStatus.VERIFIED ? ivIdA : ivIdB;
        Long loserIvId = freshA.getStatus() == IdentityVerificationStatus.VERIFIED ? ivIdB : ivIdA;

        List<IdentityVerificationHistory> winnerHistory =
                historyRepository.findByIdentityVerificationIdOrderByOccurredAtAscIdAsc(winnerIvId);
        assertThat(winnerHistory).as("winner's history: SUBMISSION + exactly one APPROVAL, nothing else").hasSize(2);
        assertThat(winnerHistory.get(1).getEventType()).isEqualTo(IdentityEventType.APPROVAL);

        List<IdentityVerificationHistory> loserHistory =
                historyRepository.findByIdentityVerificationIdOrderByOccurredAtAscIdAsc(loserIvId);
        assertThat(loserHistory)
                .as("loser's history: SUBMISSION + exactly one FAILED_APPROVAL_ATTEMPT audit row")
                .hasSize(2);
        assertThat(loserHistory.get(1).getEventType()).isEqualTo(IdentityEventType.FAILED_APPROVAL_ATTEMPT);
    }

    @RepeatedTest(5)
    @DisplayName("two admins approve the SAME row concurrently (same-row pessimistic lock): exactly one succeeds, the other fails closed on the now-non-PENDING status")
    void approve_ConcurrentSameRow_ExactlyOneSucceedsOtherFailsClosed() throws InterruptedException {
        Long userId = newUser();
        Long ivId = newPendingVerification(userId, "SAMEROW" + System.nanoTime() % 1_000_000);
        Long adminX = 33L;
        Long adminY = 44L;

        AtomicReference<IdentityVerification> resultX = new AtomicReference<>();
        AtomicReference<IdentityVerification> resultY = new AtomicReference<>();
        AtomicReference<Throwable> errX = new AtomicReference<>();
        AtomicReference<Throwable> errY = new AtomicReference<>();

        runConcurrently(
                () -> { try { resultX.set(adminIdentityApprovalOrchestrator.approveWithConflictHandling(ivId, adminX)); }
                        catch (Throwable t) { errX.set(t); } },
                () -> { try { resultY.set(adminIdentityApprovalOrchestrator.approveWithConflictHandling(ivId, adminY)); }
                        catch (Throwable t) { errY.set(t); } });

        boolean xFailed = errX.get() != null;
        boolean yFailed = errY.get() != null;
        assertThat(xFailed ^ yFailed)
                .as("exactly one of the two same-row attempts must fail closed, errX=%s, errY=%s", errX.get(), errY.get())
                .isTrue();
        // Phase 4.2 strengthening: the loser must receive the SAME handled conflict type as the
        // cross-account scenario - IllegalStateException wrapping the real cause (either
        // DataIntegrityViolationException or, as Phase 4.1's trace audit proved is the actual
        // mechanism for a same-row race, ObjectOptimisticLockingFailureException) - never a raw,
        // unhandled exception leaking past the orchestrator's catch clause.
        Throwable loserError = xFailed ? errX.get() : errY.get();
        assertThat(loserError)
                .as("the loser must receive a handled conflict (IllegalStateException), not a raw/unhandled exception")
                .isInstanceOf(IllegalStateException.class);

        IdentityVerification fresh = identityVerificationRepository.findById(ivId).orElseThrow();
        assertThat(fresh.getStatus()).isEqualTo(IdentityVerificationStatus.VERIFIED);

        List<IdentityVerificationHistory> history =
                historyRepository.findByIdentityVerificationIdOrderByOccurredAtAscIdAsc(ivId);
        long approvals = history.stream().filter(h -> h.getEventType() == IdentityEventType.APPROVAL).count();
        assertThat(approvals).as("exactly one APPROVAL event was ever recorded for this row").isEqualTo(1);
        // Phase 4.2 strengthening: the loser's failed attempt must be durably audited, exactly
        // like the cross-account scenario - this is precisely the guarantee Phase 4.1 proved was
        // silently broken (the audit row was never written because the exception type mismatch
        // meant recordFailedApprovalAttempt() was never reached).
        long failedAttempts = history.stream().filter(h -> h.getEventType() == IdentityEventType.FAILED_APPROVAL_ATTEMPT).count();
        assertThat(failedAttempts).as("exactly one FAILED_APPROVAL_ATTEMPT audit row must exist for the loser").isEqualTo(1);
    }
}

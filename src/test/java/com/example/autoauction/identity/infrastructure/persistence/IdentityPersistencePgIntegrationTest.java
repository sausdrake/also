package com.example.autoauction.identity.infrastructure.persistence;

import com.example.autoauction.identity.domain.DocumentType;
import com.example.autoauction.identity.domain.IdentityDocument;
import com.example.autoauction.identity.domain.IdentityVerification;
import com.example.autoauction.identity.domain.IdentityVerificationHistory;
import com.example.autoauction.identity.domain.RevocationReason;
import com.example.autoauction.identity.domain.port.IdentityDocumentRepository;
import com.example.autoauction.identity.domain.port.IdentityVerificationHistoryRepository;
import com.example.autoauction.identity.domain.port.IdentityVerificationRepository;
import com.example.autoauction.support.AbstractPostgresIntegrationTest;
import com.example.autoauction.user.domain.Role;
import com.example.autoauction.user.domain.User;
import com.example.autoauction.user.domain.port.RoleRepository;
import com.example.autoauction.user.domain.port.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 4 §15 persistence tests: real-PostgreSQL proof of the schema-level invariants that
 * application code never substitutes for (Phase 3.1 §1) - {@code UNIQUE(user_id)}, the partial
 * unique index on {@code (canonical_document_number, issuing_country, document_type) WHERE
 * status = 'VERIFIED'}, the history shape CHECK constraints, and {@code identity_document}'s
 * unique {@code storage_key}. H2 cannot faithfully exercise a partial unique index, hence
 * {@link AbstractPostgresIntegrationTest}.
 */
@DisplayName("Identity persistence - real-PostgreSQL schema invariants")
class IdentityPersistencePgIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired private IdentityVerificationRepository identityVerificationRepository;
    @Autowired private IdentityVerificationHistoryRepository historyRepository;
    @Autowired private IdentityDocumentRepository documentRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private SpringDataIdentityVerificationHistoryJpaRepository rawHistoryRepository;

    private Long newUser() {
        Role role = roleRepository.findByName("ROLE_USER")
                .orElseGet(() -> roleRepository.save(new Role(null, "ROLE_USER", "Обычный пользователь")));
        User u = new User(null, "idpg" + System.nanoTime(), "idpg" + System.nanoTime() + "@test.com",
                "encoded", true, Set.of(role));
        return userRepository.save(u).getId();
    }

    private Long newDocument(Long uploaderUserId) {
        return documentRepository.save(new IdentityDocument(
                "identity-documents/" + System.nanoTime(), uploaderUserId, "image/jpeg", 1000L, OffsetDateTime.now())).getId();
    }

    @Test
    @DisplayName("UNIQUE(user_id): a second row for the same user is rejected")
    void identityVerification_UniqueUserId_RejectsSecondRow() {
        Long userId = newUser();
        Long docId = newDocument(userId);
        OffsetDateTime now = OffsetDateTime.now();
        identityVerificationRepository.save(
                IdentityVerification.firstSubmission(userId, "KG", DocumentType.NATIONAL_ID, "AA1111", docId, docId, now));

        assertThatThrownBy(() -> identityVerificationRepository.save(
                IdentityVerification.firstSubmission(userId, "KG", DocumentType.NATIONAL_ID, "BB2222", docId, docId, now)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("partial unique index: two VERIFIED rows with the same identity triple are rejected")
    void identityVerification_PartialUniqueIndex_RejectsSecondVerifiedWithSameIdentity() {
        Long userA = newUser();
        Long userB = newUser();
        OffsetDateTime now = OffsetDateTime.now();

        IdentityVerification ivA = IdentityVerification.firstSubmission(
                userA, "KG", DocumentType.NATIONAL_ID, "SAMEID123", newDocument(userA), newDocument(userA), now);
        ivA.approve(now.plusMinutes(1));
        identityVerificationRepository.save(ivA);

        IdentityVerification ivB = IdentityVerification.firstSubmission(
                userB, "KG", DocumentType.NATIONAL_ID, "SAMEID123", newDocument(userB), newDocument(userB), now);
        ivB.approve(now.plusMinutes(1));

        assertThatThrownBy(() -> identityVerificationRepository.save(ivB))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("partial unique index does NOT apply to PENDING_REVIEW rows - two pending rows with the same identity are both allowed")
    void identityVerification_PartialUniqueIndex_DoesNotApplyToPendingReview() {
        Long userA = newUser();
        Long userB = newUser();
        OffsetDateTime now = OffsetDateTime.now();

        identityVerificationRepository.save(IdentityVerification.firstSubmission(
                userA, "KG", DocumentType.NATIONAL_ID, "DUPPENDING1", newDocument(userA), newDocument(userA), now));
        IdentityVerification saved = identityVerificationRepository.save(IdentityVerification.firstSubmission(
                userB, "KG", DocumentType.NATIONAL_ID, "DUPPENDING1", newDocument(userB), newDocument(userB), now));

        assertThat(saved.getId()).isNotNull();
    }

    @Test
    @DisplayName("after REVOKE, the canonical identity becomes available again for a different account to VERIFY")
    void identityVerification_RevokedIdentity_CanBeReVerifiedByDifferentAccount() {
        Long userA = newUser();
        Long userB = newUser();
        OffsetDateTime now = OffsetDateTime.now();

        IdentityVerification ivA = IdentityVerification.firstSubmission(
                userA, "KG", DocumentType.NATIONAL_ID, "FREEDAFTER1", newDocument(userA), newDocument(userA), now);
        ivA.approve(now.plusMinutes(1));
        ivA.revoke(now.plusMinutes(2));
        identityVerificationRepository.save(ivA);

        IdentityVerification ivB = IdentityVerification.firstSubmission(
                userB, "KG", DocumentType.NATIONAL_ID, "FREEDAFTER1", newDocument(userB), newDocument(userB), now);
        ivB.approve(now.plusMinutes(3));
        IdentityVerification saved = identityVerificationRepository.save(ivB);

        assertThat(saved.getStatus().name()).isEqualTo("VERIFIED");
    }

    @Test
    @DisplayName("history is append-only: every save() produces a new row, never an update")
    void history_IsAppendOnly() {
        Long userId = newUser();
        Long docId = newDocument(userId);
        OffsetDateTime now = OffsetDateTime.now();
        IdentityVerification iv = identityVerificationRepository.save(
                IdentityVerification.firstSubmission(userId, "KG", DocumentType.NATIONAL_ID, "HIST001", docId, docId, now));

        historyRepository.save(IdentityVerificationHistory.submission(iv.getId(), "HIST001", docId, now));
        historyRepository.save(IdentityVerificationHistory.approval(iv.getId(), "HIST001", 99L, now.plusMinutes(1)));

        List<IdentityVerificationHistory> rows =
                historyRepository.findByIdentityVerificationIdOrderByOccurredAtAscIdAsc(iv.getId());
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).getEventType().name()).isEqualTo("SUBMISSION");
        assertThat(rows.get(1).getEventType().name()).isEqualTo("APPROVAL");
    }

    @Test
    @DisplayName("history CHECK constraint rejects a REJECTION row with null reasonText even if the domain guard were bypassed")
    void history_ShapeCheckConstraint_RejectsInvalidRow() {
        // Exercises the DB CHECK directly via the Spring Data repository below the domain
        // constructor's own guard, proving the DB is a second, independent line of defense
        // (Phase 3.1 §1's "DB constraint remains the final authority" principle applied here too).
        JpaIdentityVerificationHistoryEntity bad = new JpaIdentityVerificationHistoryEntity(
                null, persistBareIdentityVerification(), com.example.autoauction.identity.domain.IdentityEventType.REJECTION,
                "BADROW001", null, 99L, null, null, OffsetDateTime.now());

        assertThatThrownBy(() -> rawHistoryRepository.saveAndFlush(bad))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private Long persistBareIdentityVerification() {
        Long userId = newUser();
        Long docId = newDocument(userId);
        OffsetDateTime now = OffsetDateTime.now();
        return identityVerificationRepository.save(
                IdentityVerification.firstSubmission(userId, "KG", DocumentType.NATIONAL_ID, "BAREROW1", docId, docId, now)).getId();
    }

    @Test
    @DisplayName("identity_document: unique storage_key rejects a second row with the same key")
    void identityDocument_UniqueStorageKey_RejectsDuplicate() {
        Long userId = newUser();
        String key = "identity-documents/dup-" + System.nanoTime();
        documentRepository.save(new IdentityDocument(key, userId, "image/jpeg", 1000L, OffsetDateTime.now()));

        assertThatThrownBy(() -> documentRepository.save(
                new IdentityDocument(key, userId, "image/jpeg", 1000L, OffsetDateTime.now())))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("revocation history row persists with its reasonCode and reasonText")
    void history_RevocationRow_PersistsReasonCodeAndText() {
        Long userId = newUser();
        Long docId = newDocument(userId);
        OffsetDateTime now = OffsetDateTime.now();
        IdentityVerification iv = identityVerificationRepository.save(
                IdentityVerification.firstSubmission(userId, "KG", DocumentType.NATIONAL_ID, "REVREASON1", docId, docId, now));

        historyRepository.save(IdentityVerificationHistory.revocation(
                iv.getId(), "REVREASON1", 55L, RevocationReason.FRAUD_DISCOVERED, null, now.plusMinutes(1)));

        List<IdentityVerificationHistory> rows =
                historyRepository.findByIdentityVerificationIdOrderByOccurredAtAscIdAsc(iv.getId());
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getRevocationReasonCode()).isEqualTo(RevocationReason.FRAUD_DISCOVERED);
    }

    @Test
    @DisplayName("MVP-004 (V52): the live-selfie document id round-trips and must reference a real identity_document")
    void identityVerification_SelfieDocument_RoundTripsAndIsForeignKeyChecked() {
        Long userId = newUser();
        Long docId = newDocument(userId);
        Long selfieId = newDocument(userId);
        OffsetDateTime now = OffsetDateTime.now();

        identityVerificationRepository.save(
                IdentityVerification.firstSubmission(userId, "KG", DocumentType.NATIONAL_ID, "SELFIE01", docId, selfieId, now));
        IdentityVerification reloaded = identityVerificationRepository.findByUserId(userId).orElseThrow();
        assertThat(reloaded.getCurrentDocumentId()).isEqualTo(docId);
        assertThat(reloaded.getCurrentSelfieDocumentId()).isEqualTo(selfieId);

        Long otherUser = newUser();
        Long otherDoc = newDocument(otherUser);
        assertThatThrownBy(() -> identityVerificationRepository.save(IdentityVerification.firstSubmission(
                otherUser, "KG", DocumentType.NATIONAL_ID, "SELFIE02", otherDoc, 987_654_321L, now)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}

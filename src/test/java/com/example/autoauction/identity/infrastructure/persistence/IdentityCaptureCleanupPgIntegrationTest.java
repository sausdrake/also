package com.example.autoauction.identity.infrastructure.persistence;

import com.example.autoauction.diagnostic.media.infrastructure.storage.FakeStorageClient;
import com.example.autoauction.identity.application.IdentityCaptureCleanupService;
import com.example.autoauction.identity.domain.DocumentType;
import com.example.autoauction.identity.domain.IdentityCaptureSide;
import com.example.autoauction.identity.domain.IdentityDocument;
import com.example.autoauction.identity.domain.IdentityVerification;
import com.example.autoauction.identity.domain.port.IdentityDocumentRepository;
import com.example.autoauction.identity.domain.port.IdentityVerificationRepository;
import com.example.autoauction.support.AbstractPostgresIntegrationTest;
import com.example.autoauction.user.domain.Role;
import com.example.autoauction.user.domain.User;
import com.example.autoauction.user.domain.port.RoleRepository;
import com.example.autoauction.user.domain.port.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** KYC-SLOT-01: the cleanup removes only unsubmitted, old, unreferenced capture slots - real PostgreSQL. */
@DisplayName("KYC-SLOT-01 - abandoned capture cleanup on PostgreSQL")
class IdentityCaptureCleanupPgIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired private IdentityCaptureCleanupService cleanup;
    @Autowired private IdentityDocumentRepository documents;
    @Autowired private IdentityVerificationRepository verifications;
    @Autowired private UserRepository users;
    @Autowired private RoleRepository roles;
    @Autowired private FakeStorageClient storage;

    private static final OffsetDateTime OLD = OffsetDateTime.now().minusDays(3);

    private Long newUser() {
        Role role = roles.findByName("ROLE_USER")
                .orElseGet(() -> roles.save(new Role(null, "ROLE_USER", "Обычный пользователь")));
        return users.save(new User(null, "slot" + System.nanoTime(), "slot" + System.nanoTime() + "@test.com",
                "encoded", true, Set.of(role))).getId();
    }

    private IdentityDocument capture(Long owner, IdentityCaptureSide side, OffsetDateTime createdAt, OffsetDateTime consumedAt) {
        String key = "identity-documents/slot-" + System.nanoTime();
        storage.simulateUpload(key, new byte[]{1, 2, 3}, "image/jpeg");
        return documents.save(new IdentityDocument(null, key, owner, "image/jpeg", 3L, createdAt, side,
                side == IdentityCaptureSide.SELFIE ? null : DocumentType.INTERNATIONAL_PASSPORT, consumedAt));
    }

    @Test
    @DisplayName("old unsubmitted capture: object and row removed; fresh, consumed, legacy and referenced rows kept")
    void removesOnlyAbandonedCaptures() {
        Long owner = newUser();
        IdentityDocument abandoned = capture(owner, IdentityCaptureSide.DATA_PAGE, OLD, null);
        IdentityDocument fresh = capture(owner, IdentityCaptureSide.DATA_PAGE, OffsetDateTime.now(), null);
        IdentityDocument consumed = capture(owner, IdentityCaptureSide.SELFIE, OLD, OLD.plusMinutes(5));
        IdentityDocument legacy = documents.save(new IdentityDocument("identity-documents/legacy-" + System.nanoTime(),
                owner, "image/jpeg", 3L, OLD));
        // never consumed, yet a verification points at it: must survive (defence in depth)
        Long other = newUser();
        IdentityDocument referenced = capture(other, IdentityCaptureSide.DATA_PAGE, OLD, null);
        IdentityDocument referencedSelfie = capture(other, IdentityCaptureSide.SELFIE, OLD, null);
        verifications.save(IdentityVerification.firstSubmission(other, "KG", DocumentType.INTERNATIONAL_PASSPORT,
                "SLOT" + System.nanoTime() % 100000, referenced.getId(), null, referencedSelfie.getId(), OLD));

        cleanup.removeAbandonedCaptures();

        assertThat(documents.findById(abandoned.getId())).isEmpty();
        assertThat(storage.hasObject(abandoned.getStorageKey())).isFalse();
        for (IdentityDocument kept : List.of(fresh, consumed, legacy, referenced, referencedSelfie)) {
            assertThat(documents.findById(kept.getId())).as("row %s", kept.getId()).isPresent();
        }
        for (IdentityDocument kept : List.of(fresh, consumed, referenced, referencedSelfie)) {
            assertThat(storage.hasObject(kept.getStorageKey())).as("object of row %s", kept.getId()).isTrue();
        }
    }

    @Test
    @DisplayName("the guarded delete refuses a row that was consumed after it was listed")
    void delete_refusesConsumedRow() {
        IdentityDocument slot = capture(newUser(), IdentityCaptureSide.SELFIE, OLD, null);
        documents.markConsumed(slot.getId(), OffsetDateTime.now());

        assertThat(documents.deleteAbandonedCapture(slot.getId(), OffsetDateTime.now().minusHours(24))).isFalse();
        assertThat(documents.findById(slot.getId())).isPresent();
    }
}

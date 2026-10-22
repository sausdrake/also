package com.example.autoauction.identity.application;

import com.example.autoauction.diagnostic.media.domain.port.StorageClient;
import com.example.autoauction.identity.domain.IdentityDocument;
import com.example.autoauction.identity.domain.port.IdentityDocumentRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * KYC-SLOT-01: removes capture slots that were issued but never submitted - their object in private storage
 * first, then the row. Such a slot can never be used any more: a capture is accepted only within
 * {@link IdentityCaptureVerifier#CAPTURE_VALIDITY} of its creation, so after {@link #RETENTION} nothing can
 * consume it concurrently. Submitted captures (consumed) and legacy rows without a capture side - both referenced
 * by verifications - are never touched. A storage failure leaves the row in place, so the next run retries.
 */
@Slf4j
@Service
public class IdentityCaptureCleanupService {

    static final Duration RETENTION = Duration.ofHours(24);
    static final int BATCH = 200;

    private final IdentityDocumentRepository documents;
    private final StorageClient storage;
    private final Clock clock;

    public IdentityCaptureCleanupService(IdentityDocumentRepository documents, StorageClient storage, Clock clock) {
        this.documents = documents;
        this.storage = storage;
        this.clock = clock;
    }

    /** Returns how many abandoned captures were removed. */
    public int removeAbandonedCaptures() {
        OffsetDateTime cutoff = OffsetDateTime.now(clock).minus(RETENTION);
        List<IdentityDocument> stale = documents.findAbandonedCaptures(cutoff, BATCH);
        int removed = 0;
        for (IdentityDocument capture : stale) {
            try {
                storage.deleteObject(capture.getStorageKey());
            } catch (RuntimeException e) {
                log.warn("Abandoned identity capture {}: storage delete failed ({}), retried next run",
                        capture.getId(), e.getClass().getSimpleName());
                continue;
            }
            if (documents.deleteAbandonedCapture(capture.getId(), cutoff)) {
                removed++;
            }
        }
        if (removed > 0) {
            log.info("Removed {} abandoned identity capture slot(s)", removed);
        }
        return removed;
    }
}

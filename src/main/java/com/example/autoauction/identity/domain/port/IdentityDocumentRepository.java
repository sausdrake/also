package com.example.autoauction.identity.domain.port;

import com.example.autoauction.identity.domain.IdentityDocument;

import java.time.OffsetDateTime;
import java.util.Optional;

public interface IdentityDocumentRepository {

    IdentityDocument save(IdentityDocument document);

    Optional<IdentityDocument> findByStorageKey(String storageKey);

    Optional<IdentityDocument> findById(Long id);

    /**
     * KYC-CAPTURE-01: atomically marks a capture as submitted. Returns false when it already was - the
     * caller must then refuse the submission (replay / concurrent double submit).
     */
    boolean markConsumed(Long id, OffsetDateTime at);
}

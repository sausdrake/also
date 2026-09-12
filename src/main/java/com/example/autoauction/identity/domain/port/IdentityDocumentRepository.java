package com.example.autoauction.identity.domain.port;

import com.example.autoauction.identity.domain.IdentityDocument;

import java.util.Optional;

public interface IdentityDocumentRepository {

    IdentityDocument save(IdentityDocument document);

    Optional<IdentityDocument> findByStorageKey(String storageKey);

    Optional<IdentityDocument> findById(Long id);
}

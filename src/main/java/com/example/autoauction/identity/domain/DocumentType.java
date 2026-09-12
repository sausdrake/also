package com.example.autoauction.identity.domain;

/**
 * Phase 4 §2: the only two Kyrgyz-issued document types accepted for MVP identity evidence.
 * Deliberately not a generalized document-type catalogue - foreign documents are out of scope.
 */
public enum DocumentType {
    NATIONAL_ID,
    INTERNATIONAL_PASSPORT
}

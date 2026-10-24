package com.example.autoauction.legal.domain;

/**
 * LEGAL-DATA-01: DRAFT (text editable) -> PENDING_LEGAL_REVIEW (text frozen) -> APPROVED (by an administrator
 * other than the author, with a note of who reviewed it) -> ACTIVE (the one version users see and accept; the
 * previous ACTIVE of the same type becomes RETIRED). Only DRAFT text can change: an ACTIVE text is never edited
 * silently - a change is a new version.
 */
public enum LegalDocumentStatus {
    DRAFT,
    PENDING_LEGAL_REVIEW,
    APPROVED,
    ACTIVE,
    RETIRED
}

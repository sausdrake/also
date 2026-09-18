package com.example.autoauction.payment.application;

import java.util.List;

/**
 * Phase 35: the pure classification result of {@link InitiationEvidenceClassifier}. Exactly one
 * of these four shapes - never inferred from a generic exception type, so a caller can branch on
 * the actual condition rather than parsing a message string.
 */
public sealed interface InitiationEvidenceClassification {

    /** No {@code INITIATION_ACCEPTED} evidence exists for this payment at all. */
    record None() implements InitiationEvidenceClassification {
    }

    /** Exactly one distinct, well-formed providerReference across all evidence. */
    record Valid(String providerReference) implements InitiationEvidenceClassification {
    }

    /**
     * At least one evidence record is structurally invalid - null/missing-prefix payload, an
     * empty value after the prefix, or a whitespace-only value. A permanent condition: no amount
     * of retrying will change a stored payload's content.
     */
    record Malformed(String reason) implements InitiationEvidenceClassification {
    }

    /**
     * Every evidence record is individually well-formed, but at least two disagree on the
     * providerReference. Also a permanent condition for the same reason as {@link Malformed}.
     */
    record Conflicting(List<String> providerReferences) implements InitiationEvidenceClassification {
    }
}

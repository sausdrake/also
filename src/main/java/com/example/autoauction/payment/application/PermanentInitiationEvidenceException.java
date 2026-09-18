package com.example.autoauction.payment.application;

/**
 * Phase 35: thrown by {@link PaymentCreationOrchestrator#recoverFromInitiationEvidence} only when
 * {@link InitiationEvidenceClassifier} returns {@link InitiationEvidenceClassification.Malformed}
 * or {@link InitiationEvidenceClassification.Conflicting} - i.e. the stored evidence itself is
 * structurally invalid, a condition retrying can never fix. Deliberately a distinct type, not a
 * generic {@code IllegalStateException}, so {@code PaymentInitiationRecoveryScheduler} can
 * distinguish this permanent condition from a transient infrastructure failure without inspecting
 * exception messages or exception-type hierarchies shared with unrelated failures.
 */
public class PermanentInitiationEvidenceException extends RuntimeException {
    public PermanentInitiationEvidenceException(String message) {
        super(message);
    }
}

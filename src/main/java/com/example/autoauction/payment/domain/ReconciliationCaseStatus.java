package com.example.autoauction.payment.domain;

/**
 * Slice 2: lifecycle of a {@link ReconciliationCase}. An anomaly is opened once and resolved
 * once (by an admin). A resolved case is never re-resolved.
 */
public enum ReconciliationCaseStatus {
    OPEN,
    RESOLVED
}

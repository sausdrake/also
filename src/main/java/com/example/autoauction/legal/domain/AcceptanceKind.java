package com.example.autoauction.legal.domain;

/**
 * LEGAL-DATA-01: what the user's action means for a document version. ACCEPTANCE - the user accepts a
 * contract (Terms, rules annexed to it); ACKNOWLEDGEMENT - the user confirms having read a notice (Privacy
 * Policy) - this is not consent; CONSENT - consent where consent is the legal basis, the only kind that can be
 * withdrawn; INFORMATIONAL - published for reading, nothing is recorded.
 */
public enum AcceptanceKind {
    ACCEPTANCE,
    ACKNOWLEDGEMENT,
    CONSENT,
    INFORMATIONAL;

    /** A user must record this kind before using protected operations. CONSENT is always optional. */
    public boolean requiredForAccount() {
        return this == ACCEPTANCE || this == ACKNOWLEDGEMENT;
    }
}

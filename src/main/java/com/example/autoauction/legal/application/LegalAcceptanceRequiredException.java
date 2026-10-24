package com.example.autoauction.legal.application;

/**
 * LEGAL-DATA-01: the user has not accepted the version of a required document that is now in force (e.g. a new
 * edition of the Terms). Mapped to HTTP 403 with {@link #CODE}; the page shows the documents to accept.
 */
public class LegalAcceptanceRequiredException extends RuntimeException {

    public static final String CODE = "LEGAL_ACCEPTANCE_REQUIRED";

    public LegalAcceptanceRequiredException() {
        super("Примите действующую редакцию документов платформы, чтобы продолжить.");
    }

    public String getCode() {
        return CODE;
    }
}

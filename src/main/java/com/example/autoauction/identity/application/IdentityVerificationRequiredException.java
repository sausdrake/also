package com.example.autoauction.identity.application;

/**
 * MVP-004: the caller has no VERIFIED identity verification, so a KYC-protected operation is
 * refused. Mapped to HTTP 403 with {@link #CODE} by GlobalExceptionHandler. Deliberately
 * carries no identity details (status, document data) - only that verification is required.
 */
public class IdentityVerificationRequiredException extends RuntimeException {

    public static final String CODE = "IDENTITY_VERIFICATION_REQUIRED";

    public IdentityVerificationRequiredException() {
        super("Операция доступна только после подтверждения личности (KYC). Пройдите верификацию личности.");
    }

    public String getCode() {
        return CODE;
    }
}

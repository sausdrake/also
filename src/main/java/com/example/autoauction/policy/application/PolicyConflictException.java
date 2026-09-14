package com.example.autoauction.policy.application;

/**
 * Policy Administration concurrency lock: base type for every distinct, structured Policy
 * Administration conflict the locked error-semantics section requires the frontend be able to
 * tell apart (see {@code com.example.autoauction.config.GlobalExceptionHandler}'s single new
 * handler for this family - one handler, one stable {@code code} field per concrete subtype,
 * mapped uniformly to HTTP 409, the project's existing established status for a business-state
 * conflict (mirrors the pre-existing {@code IllegalStateException} -&gt; 409 convention used
 * throughout this codebase). Deliberately not a new, unrelated error framework - it reuses the
 * existing {timestamp, status, error, message} map shape and extends it with exactly one more
 * key, {@code code}, the smallest consistent extension given this project has no pre-existing
 * stable error-code convention.
 */
public abstract class PolicyConflictException extends RuntimeException {

    private final String code;

    protected PolicyConflictException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}

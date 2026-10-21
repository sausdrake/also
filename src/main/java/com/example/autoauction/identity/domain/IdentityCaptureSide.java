package com.example.autoauction.identity.domain;

/**
 * KYC-CAPTURE-01: what a single camera capture shows. Every identity upload slot is issued for exactly one
 * side and can only be submitted in that role - a front image can never be presented as the back or as the
 * selfie.
 */
public enum IdentityCaptureSide {
    /** Front of a card document (ID card, driver licence). */
    FRONT,
    /** Back of a card document (ID card, driver licence). */
    BACK,
    /** The passport page with the photo and the personal data - the only page a passport needs. */
    DATA_PAGE,
    /** The live selfie (MVP-004); not bound to a document type. */
    SELFIE
}

package com.example.autoauction.identity.domain;

/**
 * Phase 4 §2: Kyrgyz-issued document types accepted as identity evidence. KYC-CAPTURE-01 (PO decision)
 * adds DRIVER_LICENSE next to the ID card (NATIONAL_ID) and the international passport. The sides each
 * type must be photographed with are defined by {@link DocumentCapturePolicy}.
 */
public enum DocumentType {
    NATIONAL_ID,
    INTERNATIONAL_PASSPORT,
    DRIVER_LICENSE
}

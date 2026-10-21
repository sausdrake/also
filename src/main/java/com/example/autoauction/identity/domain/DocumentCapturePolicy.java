package com.example.autoauction.identity.domain;

import java.util.List;
import java.util.Objects;

/**
 * KYC-CAPTURE-01 business rule - the sides that must be photographed for each document type:
 * <ul>
 *   <li>NATIONAL_ID (ID card) and DRIVER_LICENSE: FRONT and BACK;</li>
 *   <li>INTERNATIONAL_PASSPORT: only the DATA_PAGE - there is no back side.</li>
 * </ul>
 * The first side of the list is the image kept as the verification's main document
 * ({@code current_document_id}); the second, when present, is the back.
 */
public final class DocumentCapturePolicy {

    private DocumentCapturePolicy() {
    }

    public static List<IdentityCaptureSide> requiredSides(DocumentType documentType) {
        Objects.requireNonNull(documentType, "documentType не может быть null");
        return switch (documentType) {
            case NATIONAL_ID, DRIVER_LICENSE -> List.of(IdentityCaptureSide.FRONT, IdentityCaptureSide.BACK);
            case INTERNATIONAL_PASSPORT -> List.of(IdentityCaptureSide.DATA_PAGE);
        };
    }

    public static IdentityCaptureSide mainSide(DocumentType documentType) {
        return requiredSides(documentType).get(0);
    }

    public static boolean requiresBack(DocumentType documentType) {
        return requiredSides(documentType).contains(IdentityCaptureSide.BACK);
    }

    /** True when {@code side} is one of the sides photographed for {@code documentType}. */
    public static boolean allows(DocumentType documentType, IdentityCaptureSide side) {
        return requiredSides(documentType).contains(side);
    }
}

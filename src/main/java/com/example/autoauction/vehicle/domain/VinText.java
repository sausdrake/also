package com.example.autoauction.vehicle.domain;

import java.util.regex.Pattern;

/**
 * VIN-AUTH-01: the VIN must not travel in free text. Auctions used to carry it inside their display
 * name ({@code "Toyota Camry 2019 (VIN: XXXXXXXXXXXXXXXXX)"}), which then reached the public catalogue,
 * WebSocket events and notification texts - including those of followers without a verified identity.
 * New names are built without it; {@link #withoutVin} removes it from names and texts stored before.
 * Logs keep only the last four characters ({@link #mask}).
 */
public final class VinText {

    /** " (VIN: …)" as the auction display name has always written it. */
    private static final Pattern VIN_SUFFIX = Pattern.compile("\\s*\\(VIN:\\s*[^)]*\\)");

    private VinText() {
    }

    /** {@code text} without any "(VIN: …)" fragment; null stays null. */
    public static String withoutVin(String text) {
        return text == null ? null : VIN_SUFFIX.matcher(text).replaceAll("");
    }

    /** For logs: "***" plus the last four characters (or "***" for a short or missing value). */
    public static String mask(String vin) {
        if (vin == null || vin.length() <= 4) {
            return "***";
        }
        return "***" + vin.substring(vin.length() - 4);
    }
}

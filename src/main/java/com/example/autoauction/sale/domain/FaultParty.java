package com.example.autoauction.sale.domain;

/**
 * X3-A (SALE_FAILED outcomes): which party a failed sale's fault is attributed to, recorded
 * alongside {@link SaleResolutionResult}. Deliberately a separate field rather than inferred
 * solely from {@code result} at read time - see SaleResolution's own validation for the one
 * authoritative result-to-faultParty mapping this enum must always agree with:
 * SUCCESS/B -&gt; null, BUYER_FAULT -&gt; BUYER, PLATFORM_FAULT -&gt; PLATFORM, C -&gt; SELLER.
 *
 * No NONE value: blameless outcomes (SUCCESS, B) are represented by {@code faultParty == null},
 * not by a fourth enum constant - a nullable reference already expresses "no fault" without
 * needing a value that means the same thing as absence.
 */
public enum FaultParty {
    BUYER,
    PLATFORM,
    SELLER
}

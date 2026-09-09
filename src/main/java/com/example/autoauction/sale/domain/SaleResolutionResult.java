package com.example.autoauction.sale.domain;

/**
 * X1/X3-A: the outcome a {@link SaleResolution} records for an auction.
 *
 * SUCCESS: the sale completed (X1/X2 - unchanged by X3-A).
 *
 * X3-A adds the four SALE_FAILED outcomes, preserved as distinct values (never collapsed into
 * a generic FAULT/NO_FAULT) per the locked business model:
 *   B               - legitimate no-sale, no buyer or seller fault.
 *   BUYER_FAULT      - buyer fault, seller blameless.
 *   PLATFORM_FAULT   - platform/diagnostic fault, seller blameless.
 *   C                - seller violation.
 *
 * Each non-SUCCESS, non-B value requires a matching {@link FaultParty} (validated by
 * SaleResolution itself, not here) - BUYER_FAULT&lt;-&gt;BUYER, PLATFORM_FAULT&lt;-&gt;PLATFORM,
 * C&lt;-&gt;SELLER. SUCCESS and B both require faultParty == null.
 *
 * Deliberately excludes seller-violation sub-codes/penalty amounts - violation selection and
 * penalty resolution are out of scope for X3-A (see the X3-A architecture fight) and are not
 * represented anywhere on this enum or on SaleResolution.
 */
public enum SaleResolutionResult {
    SUCCESS,
    B,
    BUYER_FAULT,
    PLATFORM_FAULT,
    C
}

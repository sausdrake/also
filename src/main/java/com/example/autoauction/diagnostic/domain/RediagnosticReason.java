package com.example.autoauction.diagnostic.domain;

/**
 * MVP-005: why a vehicle is sent to re-diagnostic. Decides who bears the cost of the new
 * diagnostic cycle (approved business policy) and must agree with the fault party recorded
 * for the failed sale that put the vehicle into NEEDS_REDIAGNOSTIC:
 * <ul>
 *   <li>{@link #DIAGNOSTIC_ERROR} - platform/diagnostic fault (SaleResolution PLATFORM_FAULT):
 *       AlSo pays; the seller pays no fee and receives no penalty.</li>
 *   <li>{@link #SELLER_FAULT} - seller violation (SaleResolution C): the seller pays the
 *       re-diagnostic fee. The penalty for that violation is the one already charged when the
 *       sale was resolved as C (SellerViolationResolutionEngine) - never charged again here.</li>
 * </ul>
 */
public enum RediagnosticReason {
    DIAGNOSTIC_ERROR,
    SELLER_FAULT
}

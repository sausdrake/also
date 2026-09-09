package com.example.autoauction.diagnostic.commitment.domain;

/**
 * X3-E: which of the two triggers produced a {@link SellerViolationResolution} -
 * post-auction SALE_FAILED/C ({@link com.example.autoauction.auction.application.SaleFailureResolutionOrchestrator})
 * or the pre-auction diagnostic-rejection SELLER_VIOLATION category. Audit-only - both
 * triggers converge on the exact same SellerViolationResolutionEngine calculation/claim/
 * charge logic (locked X3-E §10, one shared engine, never two independent implementations).
 */
public enum TriggerSource {
    SALE_FAILED_C,
    DIAGNOSTIC_REJECTION
}

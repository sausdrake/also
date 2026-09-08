package com.example.autoauction.sale.domain;

/**
 * X1: downstream financial-processing signal carried by {@link SaleResolution}, deliberately
 * NOT an Auction lifecycle state (locked decision - Auction.SOLD does not mean all downstream
 * financial processing is complete).
 *
 * Legal transitions (enforced both by {@link SaleResolution}'s own guarded mutator methods and,
 * independently, by the claim-first atomic UPDATE the repository port exposes for each one):
 *   PENDING -&gt; DONE
 *   PENDING -&gt; FAILED
 *   FAILED  -&gt; PENDING (manual/explicit reset only - mirrors FailedOperation's own
 *              "escalated to FAILED, never auto-retried again, but not deleted" precedent;
 *              nothing in this codebase transitions FAILED -&gt; PENDING automatically)
 *
 * Forbidden, with no method exposed to attempt them:
 *   FAILED -&gt; DONE directly (must go through a fresh PENDING first - one canonical path to
 *             DONE, never a shortcut that skips re-verification)
 *   DONE   -&gt; anything (terminal - no silent state rollback)
 */
public enum FinancialResolutionStatus {
    PENDING,
    DONE,
    FAILED
}

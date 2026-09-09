-- X3-E: the seller-violation resolution audit trail - SellerViolationResolution (the
-- aggregate/capped header, one per commitment, ever) and SellerViolationResolutionItem (the
-- per-violation, uncapped raw audit line - "individual raw calculations remain auditable, do
-- not allocate the cap among individual violations", locked X3-E §6).
--
-- seller_diagnostic_commitment_id is UNIQUE: at most one SellerViolationResolution can ever
-- exist per commitment. This is provably safe (not merely convenient) - see the X3-E
-- pre-implementation consistency check: a SellerViolationResolution row is only ever created
-- in the SAME transaction as a WON claim on seller_diagnostic_commitment's own
-- AWAITING_FINALIZATION -> FINALIZED transition, and that claim-first UPDATE can only ever
-- succeed once for a given commitment (FINALIZED is terminal, no transition back) -
-- regardless of which trigger (SALE_FAILED_C or DIAGNOSTIC_REJECTION) wins that claim, or how
-- many times either trigger is attempted.
--
-- trigger_source/auction_id: audit-only, recording which of the two triggers actually
-- produced this resolution (auction_id populated only for SALE_FAILED_C; NULL for
-- DIAGNOSTIC_REJECTION, where no Auction may exist).
--
-- policy_version_id: the snapshot ACTUALLY used for this resolution - Auction.policy_version_id
-- for SALE_FAILED_C, seller_diagnostic_commitment.policy_version_id for DIAGNOSTIC_REJECTION
-- (locked X3-E §2) - recorded explicitly here rather than requiring a future reader to
-- re-derive which snapshot source applied.
--
-- FK convention: no ON DELETE clause (V34+ convention) - these are durable financial-audit
-- facts.

CREATE TABLE IF NOT EXISTS seller_violation_resolution (
    id BIGSERIAL PRIMARY KEY,
    seller_diagnostic_commitment_id BIGINT NOT NULL REFERENCES seller_diagnostic_commitment(id),
    trigger_source VARCHAR(20) NOT NULL,
    auction_id BIGINT NULL REFERENCES auctions(id),
    policy_version_id BIGINT NOT NULL REFERENCES policy_version(id),
    raw_penalty_amount NUMERIC(19,2) NOT NULL,
    applied_penalty_amount NUMERIC(19,2) NOT NULL,
    refund_amount NUMERIC(19,2) NOT NULL,
    resolved_by BIGINT NOT NULL,
    resolved_at TIMESTAMP WITH TIME ZONE NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uk_seller_violation_resolution_commitment UNIQUE (seller_diagnostic_commitment_id),
    CONSTRAINT seller_violation_resolution_trigger_source_check
        CHECK (trigger_source IN ('SALE_FAILED_C', 'DIAGNOSTIC_REJECTION')),
    CONSTRAINT seller_violation_resolution_auction_consistency_check
        CHECK (
            (trigger_source = 'SALE_FAILED_C' AND auction_id IS NOT NULL)
            OR (trigger_source = 'DIAGNOSTIC_REJECTION' AND auction_id IS NULL)
        )
);

CREATE TABLE IF NOT EXISTS seller_violation_resolution_item (
    id BIGSERIAL PRIMARY KEY,
    seller_violation_resolution_id BIGINT NOT NULL REFERENCES seller_violation_resolution(id),
    violation_definition_id BIGINT NOT NULL REFERENCES violation_definition(id),
    configured_penalty_type VARCHAR(20) NOT NULL,
    configured_penalty_value NUMERIC(19,2) NOT NULL,
    calculated_penalty_amount NUMERIC(19,2) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uk_seller_violation_resolution_item UNIQUE (seller_violation_resolution_id, violation_definition_id)
);

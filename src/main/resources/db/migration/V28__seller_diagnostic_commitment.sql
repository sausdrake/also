-- PHASE 1 (seller commitment redesign, ownership/referential-integrity correction):
-- gives the seller diagnostic commitment its own, correctly-typed table, with a real FK
-- to diagnostic_requests(id) - replacing the previous (defective) storage of a
-- diagnostic-request id inside deposit_frozen.auction_id, a column whose FK is
-- REFERENCES auctions(id). See Phase 0 / F-0.1 audit findings for the full defect
-- analysis. deposit_frozen itself and its auction-side (FreezeReason.FIRST_BID/ADMIN)
-- usage are entirely untouched by this migration.
--
-- Phase 1 scope only: no fee/penalty/refund/status fields. A row's mere existence means
-- "frozen"; release is a physical delete, mirroring deposit_frozen's own established
-- unfreeze convention (see JpaDepositRepositoryAdapter.unfreezeForAuction). Phase 2 will
-- extend or evolve this table with the full SellerDiagnosticCommitment financial-outcome
-- lifecycle (diagnosticFeeAmount, penalty, refund, finalization status) - none of that is
-- introduced here.
--
-- Historical data note: this implementation environment has no access to the production
-- database to verify whether any legacy deposit_frozen rows with
-- freeze_reason='DIAGNOSTIC_REQUEST' exist. This migration is therefore intentionally
-- purely additive - it does not read, modify, or delete any existing deposit_frozen row,
-- and performs no automated data migration out of that table. If such legacy rows exist
-- in production, they were frozen under the old (defective) persistence path and require
-- a separate, manually-verified reconciliation step against the real data - not an
-- automated assumption-based migration step.
CREATE TABLE seller_diagnostic_commitment (
    id BIGSERIAL PRIMARY KEY,
    diagnostic_request_id BIGINT NOT NULL REFERENCES diagnostic_requests(id) ON DELETE CASCADE,
    deposit_id BIGINT NOT NULL REFERENCES deposits(id) ON DELETE CASCADE,
    committed_amount NUMERIC(19,2) NOT NULL,
    frozen_at TIMESTAMP WITH TIME ZONE NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT uk_seller_diagnostic_commitment_request UNIQUE (diagnostic_request_id)
);

CREATE INDEX idx_seller_diagnostic_commitment_deposit_id ON seller_diagnostic_commitment(deposit_id);

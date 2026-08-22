-- PHASE 2 (seller commitment redesign, lifecycle foundation):
--
-- 1) diagnostic_fee_amount: the snapshot of the configured diagnostic fee at the moment
--    a commitment was frozen. Nullable by design - existing Phase 1 rows were frozen
--    before this concept existed and cannot be retroactively assigned a value without
--    evidence of what setting was in effect at their creation time (same principle
--    already established by V21's idempotency_key). Populated only for new commitments
--    going forward.
--
-- 2) status: the commitment's own lifecycle state (FROZEN / AWAITING_FINALIZATION - see
--    CommitmentStatus). NOT NULL DEFAULT 'FROZEN' is safe here, unlike the fee column
--    above: every row that can exist before this migration is, by construction, still
--    FROZEN - Phase 1 never introduced any other state, so this default is derived from
--    already-known application behavior, not invented.
--
-- 3) diagnostic_requests.rejection_category: structured, machine-readable classification
--    of a rejection (VEHICLE_CONDITION / SELLER_VIOLATION - see DiagnosticRejectionCategory),
--    replacing free-text-only rejectedReason as the basis for any future financial
--    decision. Nullable by design - existing REJECTED rows (including Phase-1-era ones)
--    predate this concept and cannot be retroactively categorized.
--
-- No fee/penalty/refund calculation, no finalizedAt, no ViolationPolicy, no Settlement/
-- Payment/Commission table is introduced here - Phase 2 is lifecycle foundation only.
-- deposit_frozen and auctions are untouched.
ALTER TABLE seller_diagnostic_commitment
    ADD COLUMN diagnostic_fee_amount NUMERIC(19,2) NULL;

ALTER TABLE seller_diagnostic_commitment
    ADD COLUMN status VARCHAR(30) NOT NULL DEFAULT 'FROZEN';

ALTER TABLE diagnostic_requests
    ADD COLUMN rejection_category VARCHAR(50) NULL;

-- Deliberately NOT seeding a value for diagnostic_fee_amount here. Unlike
-- diagnostic_deposit_amount (V15, authoritatively seeded at 10000), no business value for the
-- diagnostic fee has ever been approved - the only number that ever appeared for it (4000) was
-- explicitly an illustrative "Example:" in a read-only audit, which itself stated actual values
-- MUST NOT be hardcoded. 'diagnostic_fee_amount' is already in
-- SystemSettingsService.ALLOWED_KEYS, so an administrator can set it at any time via the
-- existing PUT /admin/settings endpoint (SystemSettingsService.set() inserts the row if it
-- doesn't yet exist - no seed row is required for that to work). Until an administrator sets
-- it, SystemSettingsService.getRequiredBigDecimal("diagnostic_fee_amount") fails closed and
-- diagnostic-request creation is rejected - see DiagnosticRequestService.createRequest.

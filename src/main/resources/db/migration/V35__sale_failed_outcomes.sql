-- X3-A (SALE_FAILED outcomes): widens sale_resolution to represent the four failed-sale
-- outcomes (B, BUYER_FAULT, PLATFORM_FAULT, C) alongside the existing SUCCESS, and adds the
-- one new Vehicle status (NEEDS_REDIAGNOSTIC) those outcomes require.
--
-- Purely additive: no existing row, column, or constraint is removed or reinterpreted. This
-- is the exact precedented shape V34's own header comment already announced ("widening this
-- CHECK constraint later... is a small additive migration - the same precedented shape as
-- V31's widening of auctions_status_check").
--
-- result: widened from CHECK (result IN ('SUCCESS')) to admit the four new values. The
-- column itself (VARCHAR(20)) is already wide enough - no type change needed.
--
-- fault_party: new, nullable VARCHAR(20) - which party a failed sale's fault is attributed
-- to. NULL for SUCCESS and B (both blameless); BUYER/PLATFORM/SELLER for
-- BUYER_FAULT/PLATFORM_FAULT/C respectively. A same-row CHECK constraint enforces this
-- result<->fault_party consistency (the one authoritative rule - also mirrored in
-- SaleResolution's own domain constructor, not duplicated in meaning, just enforced a second
-- time for defense-in-depth, same discipline as the existing SUCCESS-requires-evidence check).
--
-- failure_reason: deliberately NOT added as a new column. SaleResolution already has an
-- unused (always-null-until-now) `reason` column/field since X1 - reused here for the
-- audit-only, human-readable, never-parsed failure narrative rather than duplicating an
-- equivalent column under a different name.
--
-- No violation entities, no monetary penalty fields, no seller-commitment linkage, no
-- PolicyVersion changes - all explicitly out of scope for X3-A (see the X3-A architecture
-- fight). Outcome C is recorded purely as a classification; penalty resolution is deferred.
--
-- vehicles.status: widened to admit NEEDS_REDIAGNOSTIC (PLATFORM_FAULT/C Vehicle
-- consequence). WITHDRAWN is untouched and not repurposed.

ALTER TABLE sale_resolution
    DROP CONSTRAINT sale_resolution_result_check;

ALTER TABLE sale_resolution
    ADD CONSTRAINT sale_resolution_result_check
        CHECK (result IN ('SUCCESS', 'B', 'BUYER_FAULT', 'PLATFORM_FAULT', 'C'));

ALTER TABLE sale_resolution
    ADD COLUMN fault_party VARCHAR(20) NULL;

ALTER TABLE sale_resolution
    ADD CONSTRAINT sale_resolution_fault_party_consistency_check
        CHECK (
            (result = 'BUYER_FAULT' AND fault_party = 'BUYER') OR
            (result = 'PLATFORM_FAULT' AND fault_party = 'PLATFORM') OR
            (result = 'C' AND fault_party = 'SELLER') OR
            (result IN ('SUCCESS', 'B') AND fault_party IS NULL)
        );

ALTER TABLE vehicles
    DROP CONSTRAINT vehicles_status_check;

ALTER TABLE vehicles
    ADD CONSTRAINT vehicles_status_check
        CHECK ((status)::text = ANY ((ARRAY[
            'DRAFT',
            'PENDING_REVIEW',
            'NEEDS_FIXES',
            'APPROVED',
            'IN_AUCTION',
            'SOLD',
            'WITHDRAWN',
            'NEEDS_REDIAGNOSTIC'
        ])::text[]));

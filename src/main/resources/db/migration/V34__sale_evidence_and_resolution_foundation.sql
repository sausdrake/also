-- X1 (Sale Evidence + Sale Resolution foundation): the minimum durable, auditable proof that a
-- successful sale's three required facts - buyer payment, vehicle/document handover, admin
-- confirmation - actually occurred, plus the 1:1-with-auction record that ties them together.
--
-- Purely additive: no existing table, column, row, or constraint is touched.
--
-- Scope discipline (see the X1 architecture fight): this migration does NOT implement
-- Auction.SOLD, Vehicle.SOLD, commission calculation, buyer deposit release, seller payout, or
-- seller-commitment finalization - it only makes the underlying facts representable and
-- auditable. A later slice is expected to read these tables as preconditions for the
-- authoritative Auction WAITING_FOR_PAYMENT -> SOLD transition, not the other way around.
--
-- FK convention: this project has two eras of the same convention, not two equally valid
-- current ones. V2's original schema left cross-aggregate references (auctions.vehicle_id,
-- auctions.created_by, auctions.winner_id) without FKs; V28 explicitly corrected that same
-- defect for seller_diagnostic_commitment ("Phase 0 / F-0.1 audit findings"), adding real FKs
-- with ON DELETE CASCADE for genuine parent-aggregate references while still leaving pure
-- actor/audit fields unconstrained. This migration follows V28's corrected convention:
-- auction_id/vehicle_id/payment_evidence_id/handover_evidence_id (this row is meaningless
-- without that parent) get real FKs; buyer_id/received_by_admin_id/confirmed_by_admin_id/
-- resolved_by (who performed an action) stay plain BIGINT with no FK, matching
-- auctions.created_by/winner_id exactly.
--
-- Delete behavior deliberately diverges from V28's CASCADE: evidence rows are durable, once-
-- true-forever business facts, not disposable child records - locked decision requires
-- restrictive delete semantics (database default NO ACTION), so no ON DELETE clause is written
-- below. Deleting an auction/vehicle/evidence row that evidence/resolution still references is
-- blocked, not silently cascaded away. No code path in this codebase currently deletes an
-- Auction or Vehicle row at all, so this is a safety default, not a currently-exercised path.
--
-- Immutability (locked decision 2): enforced through immutable domain design (no setters on
-- PaymentEvidence/HandoverEvidence, no update/delete methods on their repository ports) plus DB
-- constraints (NOT NULL, CHECK, UNIQUE) - deliberately NO database trigger in X1, unlike
-- policy_version's published-row trigger. That trigger solves a different-shaped problem (one
-- boolean flag that, once true, freezes an entire row); PaymentEvidence/HandoverEvidence are
-- unconditionally immutable from birth (no draft phase to allow initial edits), so the simpler,
-- already-precedented mechanism - a repository port that exposes no update/delete method at all
-- - is the correct fit, not a blind copy of policy_version's trigger shape.
--
-- sale_resolution is the one exception: financial_resolution_status is deliberately mutable
-- (locked decision - NOT an Auction lifecycle state), transitioned only via a claim-first atomic
-- UPDATE ... WHERE (mirroring policy_version.claimPublish and
-- seller_diagnostic_commitment.markAwaitingFinalization), never a generic row update. All other
-- sale_resolution columns are immutable from insert, exactly like the evidence tables.
--
-- Amount precision: NUMERIC(19,2), matching this project's ledger/transaction-amount convention
-- (deposits, deposit_transactions, seller_diagnostic_commitment) rather than the narrower
-- NUMERIC(10,2) used for single auction pricing fields - a superset range, never truncating a
-- winningBid value it is compared against.
--
-- SaleResolutionResult (X1): only 'SUCCESS' is a legal value - no BUYER_FAULT/SELLER_FAULT/
-- PLATFORM_FAULT/Outcome-B/C terminology is introduced here (locked decision 5). Widening this
-- CHECK constraint later, when a SALE_FAILED-handling slice actually needs those values, is a
-- small additive migration - the same precedented shape as V31's widening of
-- auctions_status_check for WAITING_FOR_PAYMENT/SALE_FAILED.
--
-- The SUCCESS-requires-both-evidence invariant is a same-row, cross-column CHECK constraint
-- (no trigger needed - it never compares across tables, only within the sale_resolution row
-- itself).
--
-- No index beyond what UNIQUE/FK already provide - no query in this slice needs one; the same
-- discipline already used in V33 (no index added "just in case").

CREATE TABLE payment_evidence (
    id                    BIGSERIAL PRIMARY KEY,
    auction_id            BIGINT NOT NULL UNIQUE REFERENCES auctions(id),
    buyer_id              BIGINT NOT NULL,
    amount                NUMERIC(19,2) NOT NULL,
    received_by_admin_id  BIGINT NOT NULL,
    received_at           TIMESTAMP WITH TIME ZONE NOT NULL,
    created_at            TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT payment_evidence_amount_positive_check CHECK (amount > 0)
);

CREATE TABLE handover_evidence (
    id                     BIGSERIAL PRIMARY KEY,
    auction_id             BIGINT NOT NULL UNIQUE REFERENCES auctions(id),
    vehicle_id             BIGINT NOT NULL REFERENCES vehicles(id),
    buyer_id               BIGINT NOT NULL,
    confirmed_by_admin_id  BIGINT NOT NULL,
    confirmed_at           TIMESTAMP WITH TIME ZONE NOT NULL,
    created_at             TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE sale_resolution (
    id                            BIGSERIAL PRIMARY KEY,
    auction_id                    BIGINT NOT NULL UNIQUE REFERENCES auctions(id),
    result                        VARCHAR(20) NOT NULL,
    reason                        VARCHAR(500),
    resolved_by                   BIGINT NOT NULL,
    resolved_at                   TIMESTAMP WITH TIME ZONE NOT NULL,
    payment_evidence_id           BIGINT REFERENCES payment_evidence(id),
    handover_evidence_id          BIGINT REFERENCES handover_evidence(id),
    financial_resolution_status   VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    created_at                    TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT sale_resolution_result_check
        CHECK (result IN ('SUCCESS')),
    CONSTRAINT sale_resolution_financial_status_check
        CHECK (financial_resolution_status IN ('PENDING', 'DONE', 'FAILED')),
    CONSTRAINT sale_resolution_success_requires_evidence_check
        CHECK (result <> 'SUCCESS' OR (payment_evidence_id IS NOT NULL AND handover_evidence_id IS NOT NULL))
);

-- X3-C (Auction <-> SellerDiagnosticCommitment linkage): adds the deterministic
-- Vehicle.diagnosticRequestId -> DiagnosticRequest -> SellerDiagnosticCommitment chain and the
-- immutable Auction.sellerDiagnosticCommitmentId snapshot that resolves through it, per the
-- X3-C business-decision lock and architecture re-lock.
--
-- Purely additive: no existing table, column, row, or constraint is touched. No backfill, no
-- VIN matching, no historical reconstruction of either new relationship - every existing
-- vehicles/auctions row simply gets NULL in its new column, exactly as the locked contract
-- requires ("historical NULL means this row predates mandatory linkage, never backfilled").
--
-- FK convention: follows V34's corrected convention (see V34's own header comment), not V28's
-- earlier ON DELETE CASCADE - both new references are evidence-like, durable facts (a financial
-- commitment's binding to a vehicle/auction is a once-true-forever fact, not a disposable
-- child record), so deletion is blocked by the database default (NO ACTION), not cascaded
-- away. No ON DELETE clause is written below. No code path in this codebase currently deletes
-- a vehicles, diagnostic_requests, auctions, or seller_diagnostic_commitment row, so this is a
-- safety default, not a currently-exercised path.
--
-- vehicles.diagnostic_request_id: nullable (historical/manual vehicles), UNIQUE (a
-- DiagnosticRequest can trigger the creation of at most one Vehicle - the VIN-dedup branch in
-- VehicleService.createFromDiagnosticRequest returns an existing Vehicle untouched rather than
-- creating a second one, so no DiagnosticRequest id is ever written to more than one Vehicle
-- row). References diagnostic_requests(id), the existing table (see JpaDiagnosticRequestEntity's
-- @Table).
--
-- auctions.seller_diagnostic_commitment_id: nullable (historical-only; application-level
-- validation in AdminAuctionService/AuctionCommitmentResolver - not a DB CHECK constraint -
-- forbids NULL for any newly-created auction, since a DB NOT NULL would break every existing
-- row). UNIQUE (a commitment backs at most one auction, ever - re-auction isolation). References
-- seller_diagnostic_commitment(id).
--
-- No financial columns, no status columns, no trigger - this migration represents an
-- ownership/reference fact only, exactly as locked. Outcome-resolution logic (A/B/BUYER_FAULT/
-- PLATFORM_FAULT/C reading this column) and the manual-vehicle commitment-producing workflow
-- are both explicitly out of scope.

ALTER TABLE vehicles
    ADD COLUMN diagnostic_request_id BIGINT NULL
        REFERENCES diagnostic_requests(id);

ALTER TABLE vehicles
    ADD CONSTRAINT uk_vehicles_diagnostic_request_id UNIQUE (diagnostic_request_id);

ALTER TABLE auctions
    ADD COLUMN seller_diagnostic_commitment_id BIGINT NULL
        REFERENCES seller_diagnostic_commitment(id);

ALTER TABLE auctions
    ADD CONSTRAINT uk_auctions_seller_diagnostic_commitment_id UNIQUE (seller_diagnostic_commitment_id);

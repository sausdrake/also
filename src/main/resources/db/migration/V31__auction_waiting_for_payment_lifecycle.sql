-- SLICE 1a (Auction state machine): introduces the payment-pending sale lifecycle.
--
-- New AuctionStatus values:
--   WAITING_FOR_PAYMENT - bidding concluded with a winner (normal auction or Buy Now), but
--     the sale itself has not yet been confirmed. Transient - always resolves to SOLD or
--     SALE_FAILED through a future confirmed-sale workflow (out of scope for this slice).
--   SOLD               - administrator has confirmed the successful sale as a business fact
--     (payment and vehicle/document handover verified). No production code writes this value
--     yet in this slice; the value is added now so later slices are purely additive.
--   SALE_FAILED        - the sale did not complete after a winner was determined. Detail
--     (fault party, reason) lives in a future SaleResolution aggregate, not on this column -
--     deliberately not one AuctionStatus value per failure reason.
--
-- COMPLETED is explicitly RETAINED, not removed or backfilled. Historical rows already
-- persisted with status='COMPLETED' represent "bidding concluded with a winner" under the
-- process that existed before this feature - they never recorded payment or handover
-- confirmation (that mechanism did not exist), so they must never be reinterpreted as SOLD.
-- New application code must never write COMPLETED again (see AuctionStatus.COMPLETED's own
-- @Deprecated Javadoc) - it remains valid in the database purely for historical rows.
--
-- Purely additive to data: no row is updated, deleted, or reinterpreted by this migration.
-- Only the CHECK constraint's allowed-value set changes, to admit the new statuses.
ALTER TABLE auctions
    DROP CONSTRAINT auctions_status_check;

ALTER TABLE auctions
    ADD CONSTRAINT auctions_status_check
        CHECK ((status)::text = ANY ((ARRAY[
            'CREATED',
            'ACTIVE',
            'WAITING_FOR_PAYMENT',
            'SOLD',
            'SALE_FAILED',
            'EXPIRED',
            'CANCELLED',
            'COMPLETED'
        ])::text[]));

-- AUC-NOSALE-01 (approved policy): an auction that ends without a sale (EXPIRED / CANCELLED)
-- releases the seller's diagnostic guarantee and sends the vehicle to re-diagnostic.
--
-- 1. A cancellation caused by a confirmed seller violation is recorded through the shared
--    seller-violation engine with its own trigger, AUCTION_CANCELLED_C, which (like SALE_FAILED_C)
--    always names the auction.
-- 2. The re-diagnostic that follows such an auction has its own reason, NO_SALE.
-- Only CHECK constraints change: no existing row is touched, every existing value stays valid.

ALTER TABLE seller_violation_resolution DROP CONSTRAINT seller_violation_resolution_trigger_source_check;
ALTER TABLE seller_violation_resolution
    ADD CONSTRAINT seller_violation_resolution_trigger_source_check
        CHECK (trigger_source IN ('SALE_FAILED_C', 'DIAGNOSTIC_REJECTION', 'AUCTION_CANCELLED_C'));

ALTER TABLE seller_violation_resolution DROP CONSTRAINT seller_violation_resolution_auction_consistency_check;
ALTER TABLE seller_violation_resolution
    ADD CONSTRAINT seller_violation_resolution_auction_consistency_check
        CHECK (
            (trigger_source IN ('SALE_FAILED_C', 'AUCTION_CANCELLED_C') AND auction_id IS NOT NULL)
            OR (trigger_source = 'DIAGNOSTIC_REJECTION' AND auction_id IS NULL)
        );

ALTER TABLE diagnostic_requests DROP CONSTRAINT diagnostic_requests_rediagnostic_consistency_check;
ALTER TABLE diagnostic_requests
    ADD CONSTRAINT diagnostic_requests_rediagnostic_consistency_check CHECK (
        (previous_diagnostic_request_id IS NULL AND vehicle_id IS NULL AND rediagnostic_reason IS NULL
            AND rediagnostic_comment IS NULL AND rediagnostic_ordered_by IS NULL AND rediagnostic_ordered_at IS NULL)
        OR
        (previous_diagnostic_request_id IS NOT NULL AND vehicle_id IS NOT NULL
            AND rediagnostic_reason IS NOT NULL
            AND rediagnostic_reason IN ('DIAGNOSTIC_ERROR', 'SELLER_FAULT', 'NO_SALE')
            AND rediagnostic_ordered_by IS NOT NULL AND rediagnostic_ordered_at IS NOT NULL)
    );

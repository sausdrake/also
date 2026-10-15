-- MVP-005: re-diagnostic of a vehicle in NEEDS_REDIAGNOSTIC creates a NEW diagnostic request
-- linked to the request it supersedes; the previous request and its report/media stay
-- untouched. The reason is mandatory, machine-readable, and kept with who ordered it and when.
--   previous_diagnostic_request_id - the request this one re-diagnoses (unique: a request is
--                                    superseded at most once, which also makes a concurrent
--                                    double order impossible)
--   vehicle_id                     - the existing vehicle being re-diagnosed (no new vehicle)
--   rediagnostic_reason            - DIAGNOSTIC_ERROR (platform/diagnostic fault, AlSo pays) or
--                                    SELLER_FAULT (seller violation, seller pays)
-- Ordinary (first) diagnostic requests keep all six columns NULL. (IS NOT NULL checks are explicit:
-- a CHECK whose condition evaluates to UNKNOWN passes, so NULL IN (...) alone would not block.)
ALTER TABLE diagnostic_requests ADD COLUMN previous_diagnostic_request_id BIGINT REFERENCES diagnostic_requests(id);
ALTER TABLE diagnostic_requests ADD COLUMN vehicle_id BIGINT REFERENCES vehicles(id);
ALTER TABLE diagnostic_requests ADD COLUMN rediagnostic_reason VARCHAR(32);
ALTER TABLE diagnostic_requests ADD COLUMN rediagnostic_comment VARCHAR(2000);
ALTER TABLE diagnostic_requests ADD COLUMN rediagnostic_ordered_by BIGINT REFERENCES users(id);
ALTER TABLE diagnostic_requests ADD COLUMN rediagnostic_ordered_at TIMESTAMP WITH TIME ZONE;

ALTER TABLE diagnostic_requests
    ADD CONSTRAINT uk_diagnostic_requests_previous_request UNIQUE (previous_diagnostic_request_id);

ALTER TABLE diagnostic_requests
    ADD CONSTRAINT diagnostic_requests_rediagnostic_consistency_check CHECK (
        (previous_diagnostic_request_id IS NULL AND vehicle_id IS NULL AND rediagnostic_reason IS NULL
            AND rediagnostic_comment IS NULL AND rediagnostic_ordered_by IS NULL AND rediagnostic_ordered_at IS NULL)
        OR
        (previous_diagnostic_request_id IS NOT NULL AND vehicle_id IS NOT NULL
            AND rediagnostic_reason IS NOT NULL
            AND rediagnostic_reason IN ('DIAGNOSTIC_ERROR', 'SELLER_FAULT')
            AND rediagnostic_ordered_by IS NOT NULL AND rediagnostic_ordered_at IS NOT NULL)
    );

CREATE INDEX idx_diagnostic_requests_vehicle_id ON diagnostic_requests(vehicle_id);

-- X3-E (Seller Violation Policy): stable violation-code catalogue (ViolationDefinition).
--
-- Purely additive: no existing table/column/row touched. Seeds exactly the six initial
-- codes locked by the X3-E business decision lock - no more, no fewer (X3-E scope explicitly
-- excludes adding further codes).
--
-- code is the stable, never-renumbered identity referenced by PolicyVersionViolation and by
-- SellerViolationResolutionItem - both by this table's surrogate id, never by the code string
-- itself, so a future (out-of-scope) rename of the code column would not break either
-- reference.
--
-- No FK columns, no policy_version reference here - this table is deliberately
-- policy-version-agnostic (a violation's stable identity, not its penalty configuration -
-- see V38 for the version-specific configuration).

CREATE TABLE IF NOT EXISTS violation_definition (
    id BIGSERIAL PRIMARY KEY,
    code VARCHAR(64) NOT NULL,
    description VARCHAR(500) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uk_violation_definition_code UNIQUE (code)
);

INSERT INTO violation_definition (code, description, created_at) VALUES
    ('SELLER_REFUSED_HANDOVER',
     'Seller refuses to hand over the vehicle/documents after a legitimate sale/handover obligation.',
     now()),
    ('SELLER_ADDITIONAL_PAYMENT_DEMAND',
     'Seller demands payment from buyer above the winning price or other amounts explicitly permitted by contract/rules.',
     now()),
    ('SELLER_HANDOVER_TERMS_CHANGED',
     'Seller unilaterally changes a material agreed handover condition in a way that prevents or materially obstructs completion.',
     now()),
    ('SELLER_UNREACHABLE',
     'Seller fails to remain reachable/provide the agreed handover process without a contractually valid excuse.',
     now()),
    ('VEHICLE_CONDITION_CHANGED_AFTER_DIAGNOSTIC',
     'The vehicle''s material condition was changed after the original diagnostic and before handover/re-resolution. ' ||
     'Financially inert in this slice - see SellerViolationResolutionEngine''s own guard; no evidence mechanism ' ||
     'exists yet to distinguish this from an incorrect original diagnostic (PLATFORM_FAULT).',
     now()),
    ('SELLER_WITHDREW_AFTER_WIN',
     'Seller withdraws/backs out after a winner has been determined.',
     now());

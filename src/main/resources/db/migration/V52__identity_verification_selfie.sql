-- MVP-004: KYC requires a live selfie taken through the platform camera, reviewed by an
-- administrator together with the identity document. The selfie is stored as its own
-- identity_document row (same ownership/upload model as the document image).
-- Nullable: rows submitted before this change carry no selfie; they can no longer be
-- approved (IdentityVerification.approve) and must be rejected and resubmitted with one.
ALTER TABLE identity_verification
    ADD COLUMN current_selfie_document_id BIGINT REFERENCES identity_document(id);

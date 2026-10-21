-- KYC-CAPTURE-01: KYC document images are camera captures, issued per side and submitted once.
-- Additive only, every new column nullable: rows written before this change keep their data, carry no
-- capture side and can therefore never be submitted again (the application requires a side).
--
-- identity_document: the capture slot - which side it was issued for, for which document type, and when it
-- was submitted (single use).
ALTER TABLE identity_document ADD COLUMN capture_side VARCHAR(16);
ALTER TABLE identity_document ADD COLUMN document_type VARCHAR(24);
ALTER TABLE identity_document ADD COLUMN consumed_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE identity_document ADD CONSTRAINT identity_document_capture_side_check
    CHECK (capture_side IS NULL OR capture_side IN ('FRONT', 'BACK', 'DATA_PAGE', 'SELFIE'));

-- ID card and driver licence need the back side too (passport: data page only). The main image stays in
-- current_document_id; history keeps which back image each submission carried.
ALTER TABLE identity_verification
    ADD COLUMN current_document_back_id BIGINT REFERENCES identity_document(id);
ALTER TABLE identity_verification_history
    ADD COLUMN document_back_reference_id BIGINT REFERENCES identity_document(id);

-- PHASE 3B (Outcome B finalization):
--
-- Purely additive, matching V29's own precedent. No CHECK constraint restricts allowed
-- status values (V29's status column is a plain NOT NULL VARCHAR(30)) - FINALIZED is added
-- as a new valid value entirely at the Java enum level (CommitmentStatus), no schema
-- change required for that.
--
-- finalization_outcome: which financial outcome a FINALIZED commitment resolved to,
-- deliberately a separate column from status (see FinalizationOutcome's own Javadoc for
-- why) - only 'B' is ever written by this phase's code, but the column itself imposes no
-- restriction, leaving room for future outcomes without another migration.
--
-- finalized_at / penalty_amount / refund_amount: populated exactly once, together, by the
-- same atomic UPDATE that sets status='FINALIZED' - never independently. Nullable by
-- design: every FROZEN/AWAITING_FINALIZATION row (including all pre-Phase-3B history) has
-- none of these yet.
--
-- No fee/commission/settlement table, no buyer-payment field, no penalty policy, no
-- Outcome A/C support - Phase 3B is Outcome B only. No seed values, no business-value
-- numbers - diagnostic_fee_amount remains administrator-configured and fail-closed exactly
-- as Phase 2 left it.
ALTER TABLE seller_diagnostic_commitment
    ADD COLUMN finalization_outcome VARCHAR(30) NULL;

ALTER TABLE seller_diagnostic_commitment
    ADD COLUMN finalized_at TIMESTAMP WITH TIME ZONE NULL;

ALTER TABLE seller_diagnostic_commitment
    ADD COLUMN penalty_amount NUMERIC(19,2) NULL;

ALTER TABLE seller_diagnostic_commitment
    ADD COLUMN refund_amount NUMERIC(19,2) NULL;

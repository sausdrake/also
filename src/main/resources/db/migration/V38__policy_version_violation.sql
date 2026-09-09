-- X3-E: version-specific penalty configuration for each ViolationDefinition
-- (PolicyVersionViolation) - the "how much" side, kept separate from V37's stable "what" side
-- per the locked violation model (ViolationDefinition + PolicyVersionViolation).
--
-- FK convention: follows V34+'s corrected convention (no ON DELETE clause) - these rows are
-- evidence-like, durable policy facts, never cascaded away.
--
-- penalty_value is NUMERIC(19,2) regardless of penalty_type - large enough for both a
-- 0-100 percentage and a currency amount (this module's percentage scale is 0-100, NOT the
-- 0-1 fraction convention policy_version.commission_rate uses - the two are independent
-- columns on independent tables, deliberately not unified).
--
-- Immutability: mirrors policy_version's own trigger exactly, extended to this child table -
-- a row is immutable (UPDATE and DELETE both blocked) once the PARENT policy_version is
-- published. This blocks the "delete + reinsert" bypass explicitly, not just UPDATE, for the
-- same reason V32's own trigger blocks DELETE on policy_version itself.
--
-- No historical backfill: the currently-published PolicyVersion (predating this feature) gets
-- zero PolicyVersionViolation rows and, being already published, can never gain any - any
-- Outcome-C financial resolution snapshotted against it will fail closed (no valid policy
-- configuration) until a new, violation-complete PolicyVersion is published. This is the
-- correct, intentional fail-closed consequence, not a defect - see the X3-E final scope
-- report's Risk #1.

CREATE TABLE IF NOT EXISTS policy_version_violation (
    id BIGSERIAL PRIMARY KEY,
    policy_version_id BIGINT NOT NULL REFERENCES policy_version(id),
    violation_definition_id BIGINT NOT NULL REFERENCES violation_definition(id),
    penalty_type VARCHAR(20) NOT NULL,
    penalty_value NUMERIC(19,2) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uk_policy_version_violation UNIQUE (policy_version_id, violation_definition_id),
    CONSTRAINT policy_version_violation_type_check
        CHECK (penalty_type IN ('PERCENTAGE', 'FIXED_AMOUNT')),
    CONSTRAINT policy_version_violation_value_check
        CHECK (
            (penalty_type = 'PERCENTAGE' AND penalty_value >= 0 AND penalty_value <= 100)
            OR (penalty_type = 'FIXED_AMOUNT' AND penalty_value >= 0)
        )
);

CREATE OR REPLACE FUNCTION policy_version_violation_prevent_published_mutation()
RETURNS TRIGGER AS $$
DECLARE
    parent_published BOOLEAN;
BEGIN
    IF (TG_OP = 'DELETE') THEN
        SELECT published INTO parent_published FROM policy_version WHERE id = OLD.policy_version_id;
        IF parent_published THEN
            RAISE EXCEPTION 'policy_version_violation % belongs to a published policy_version (%) and cannot be deleted', OLD.id, OLD.policy_version_id;
        END IF;
        RETURN OLD;
    ELSE
        SELECT published INTO parent_published FROM policy_version WHERE id = OLD.policy_version_id;
        IF parent_published THEN
            RAISE EXCEPTION 'policy_version_violation % belongs to a published policy_version (%) and is immutable', OLD.id, OLD.policy_version_id;
        END IF;
        RETURN NEW;
    END IF;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_policy_version_violation_prevent_published_mutation
    BEFORE UPDATE OR DELETE ON policy_version_violation
    FOR EACH ROW
    EXECUTE FUNCTION policy_version_violation_prevent_published_mutation();

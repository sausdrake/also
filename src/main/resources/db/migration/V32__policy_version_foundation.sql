-- SLICE 1b-1: minimal immutable pricing-policy foundation (PolicyVersion).
--
-- Scope: pricing only. Does NOT include a seller-violation catalogue, an auction reference
-- (auctions.policy_version_id), or any pricing calculation - those belong to Slice 1b-2/1b-3.
-- Purely additive: no existing table, column, or row is touched by this migration.
--
-- version_number is allocated via a single-row counter table (policy_version_counter),
-- claimed under a pessimistic row lock (SELECT ... FOR UPDATE, via JPA
-- @Lock(LockModeType.PESSIMISTIC_WRITE)) within the same transaction as the PolicyVersion
-- insert - the identical idiom already established in this codebase
-- (SpringDataAuctionJpaRepository.findByIdWithLock). A raw Postgres SEQUENCE was deliberately
-- NOT used: this project's test suite runs against H2 with
-- spring.jpa.hibernate.ddl-auto=create-drop (Flyway disabled for tests - see
-- application-test.properties), so any mechanism relying on Postgres-specific sequence DDL
-- created only by this Flyway migration would be untestable against the real test database.
-- A plain counter table + standard SQL row-locking works identically in H2 and PostgreSQL.
--
-- commission_rate is a fraction (e.g. 0.0250 = 2.50%), NUMERIC(5,4) - enough precision for a
-- standard percentage rate. The CHECK constraint (>= 0, < 1) is a technical safety boundary
-- only, NOT a business-approved commission cap - the actual rate remains policy data supplied
-- at creation time by the caller.
--
-- created_by is a plain BIGINT with no FK constraint, matching this project's existing
-- convention for actor/audit fields (see auctions.created_by in V2__core_schema.sql, which
-- likewise has no FK to users).
--
-- Immutability is enforced at two layers:
--   1. Application/domain (see policy.domain.PolicyVersion): exposes no setters at all after
--      construction - only publish(), which can only move false -> true, never back.
--   2. Database (this migration): a trigger rejects ANY UPDATE or DELETE where the row being
--      touched was ALREADY published before the statement. An unpublished row may still be
--      updated (this is how publish() itself persists its one legitimate transition) or
--      deleted (not currently exercised by any code path, but not forbidden for an unpublished
--      draft created in error).
--
-- No historical PolicyVersion row is ever deleted or reinterpreted by this or any future
-- migration - published versions remain forever as the authoritative historical record for
-- whichever auctions eventually reference them (that reference itself is Slice 1b-2's job).

CREATE TABLE IF NOT EXISTS policy_version_counter (
    id INT PRIMARY KEY,
    next_value BIGINT NOT NULL,
    CONSTRAINT policy_version_counter_singleton CHECK (id = 1)
);

INSERT INTO policy_version_counter (id, next_value)
VALUES (1, 1)
ON CONFLICT (id) DO NOTHING;

CREATE TABLE IF NOT EXISTS policy_version (
    id BIGSERIAL PRIMARY KEY,
    version_number BIGINT NOT NULL,
    commission_rate NUMERIC(5,4) NOT NULL,
    published BOOLEAN NOT NULL DEFAULT FALSE,
    effective_from TIMESTAMP WITH TIME ZONE NOT NULL,
    created_by BIGINT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uk_policy_version_number UNIQUE (version_number),
    CONSTRAINT policy_version_commission_rate_check CHECK (commission_rate >= 0 AND commission_rate < 1)
);

CREATE OR REPLACE FUNCTION policy_version_prevent_published_mutation()
RETURNS TRIGGER AS $$
BEGIN
    IF (TG_OP = 'DELETE') THEN
        IF OLD.published THEN
            RAISE EXCEPTION 'policy_version % is published and cannot be deleted', OLD.id;
        END IF;
        RETURN OLD;
    ELSE
        IF OLD.published THEN
            RAISE EXCEPTION 'policy_version % is published and is immutable', OLD.id;
        END IF;
        RETURN NEW;
    END IF;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_policy_version_prevent_published_mutation
    BEFORE UPDATE OR DELETE ON policy_version
    FOR EACH ROW
    EXECUTE FUNCTION policy_version_prevent_published_mutation();

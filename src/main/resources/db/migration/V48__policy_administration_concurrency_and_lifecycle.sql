-- Policy Administration (Decisions 1-6, locked concurrency design): publication metadata,
-- immutable draft lineage, entity-level optimistic locking, and the aggregate
-- review-to-publish freshness counter.
--
-- effective_from is removed entirely (Decision 4) - this slice's audit found no evidence of any
-- actual "future-dated policy" requirement; the column was write-only dead weight duplicating
-- created_at.
--
-- published_by / published_at (Decision 2) are established ONLY by a successful publication
-- claim - never backfilled for historical rows (no fabricated historical metadata).
--
-- based_on_published_version_id (Decision 5) is immutable draft lineage, captured once at draft
-- creation. Self-referential FK, no ON DELETE clause (matches this project's V34+ convention for
-- durable, evidence-like audit/lineage references - never cascaded away). Existing rows get
-- NULL - "do not invent lineage" - which, with no special-casing, makes any pre-existing
-- unpublished draft permanently unpublishable under the new rule (the correct, intentional
-- fail-closed consequence, not a gap).
--
-- version (Boundary A) is a standard Hibernate @Version column, same convention already used by
-- JpaDepositEntity/JpaAuctionEntity/JpaIdentityVerificationEntity (`version BIGINT NOT NULL
-- DEFAULT 0`).
--
-- configuration_revision (Boundary C, Decision 6) is a SEPARATE plain counter, deliberately not
-- unified with `version` (the two protect different things - see PolicyVersion's own Javadoc) -
-- incremented only via a dedicated atomic `SET configuration_revision = configuration_revision +
-- 1` statement, never via Hibernate's entity-save dirty-checking.
--
-- No trigger change: policy_version_prevent_published_mutation (V32) and
-- policy_version_violation_prevent_published_mutation (V38) are both already column-agnostic
-- (whole-row `IF OLD.published THEN RAISE EXCEPTION`) - they already protect every column added
-- here with no modification required.

ALTER TABLE policy_version
    DROP COLUMN effective_from;

ALTER TABLE policy_version
    ADD COLUMN published_by BIGINT NULL,
    ADD COLUMN published_at TIMESTAMP WITH TIME ZONE NULL,
    ADD COLUMN based_on_published_version_id BIGINT NULL REFERENCES policy_version(id),
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN configuration_revision BIGINT NOT NULL DEFAULT 0;

ALTER TABLE policy_version_violation
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0;

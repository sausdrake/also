-- Phase 17: optimistic-lock version column for reconciliation_case (V42). Purely additive - no
-- existing column, constraint, index, or row from V1-V48 is touched.
--
-- Rationale: ReconciliationCase.resolve() has no production caller today, but the row has no
-- concurrency protection whatsoever should a future administrative resolve action be added -
-- two concurrent resolutions of the same case would otherwise silently last-write-wins with no
-- conflict detection. Every other mutable financial/audit aggregate in this project that can be
-- reached from more than one code path already carries this exact protection (auctions.version,
-- deposits.version, both BIGINT NOT NULL DEFAULT 0 per V2__core_schema.sql) - this migration
-- brings reconciliation_case to the same standard, matching the established convention exactly
-- rather than inventing a new one.
--
-- DEFAULT 0 makes this safe for any existing row (there are none in a fresh database, but the
-- column must still be well-defined for any row inserted before this migration in an environment
-- that already ran V42-V48).
ALTER TABLE reconciliation_case ADD COLUMN version BIGINT NOT NULL DEFAULT 0;

COMMENT ON COLUMN reconciliation_case.version IS 'Версия для оптимистичной блокировки';

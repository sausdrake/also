-- Scenario 3 step 4: the diagnostician can mark a non-required item as skipped ("Пропустить"),
-- so the report shows "пропущен" instead of an unexplained gap. Existing answers were never
-- skipped, hence the FALSE default.
ALTER TABLE inspection_answers
    ADD COLUMN skipped BOOLEAN NOT NULL DEFAULT FALSE;

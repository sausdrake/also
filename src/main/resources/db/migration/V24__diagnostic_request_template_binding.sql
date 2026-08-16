-- MEDIA-3.1A: bind each diagnostic request to an immutable inspection template.
-- Nullable: historical requests (and any request not yet past the assignment/
-- binding step) legitimately have no template yet - this is not backfilled
-- here (see MEDIA-3.1B). No ON DELETE clause -> defaults to NO ACTION in
-- PostgreSQL, matching the existing inspection_answers.inspection_item_id
-- FK's behavior: a template referenced by a bound request can never be
-- deleted, only deactivated.
ALTER TABLE diagnostic_requests
    ADD COLUMN template_id BIGINT REFERENCES inspection_templates(id);

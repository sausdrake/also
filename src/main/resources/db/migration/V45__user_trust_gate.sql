-- Slice 1A (Minimal Trust Gate): an operational, controlled-beta trust mechanism - an
-- administrator has explicitly decided this user is trusted to perform the protected
-- participant actions of the controlled beta (bidding, Buy Now). This is NOT legal identity
-- verification/KYC and must never be represented as such.
--
-- Purely additive: no existing table, column, row, constraint, or index from V1-V44 is touched.
--
-- trusted: defaults to false for every existing and newly-registered row - "never reviewed" and
-- "explicitly not trusted" both present as trusted=false; the business distinguishes them via
-- verified_by_admin_id/verified_at (both null only for "never reviewed").
--
-- verified_by_admin_id / verified_at: current-state metadata only, recording who made the most
-- recent trust-status decision (grant or revoke) and when - deliberately NOT a historical ledger
-- (no separate history table; that would be a different, not-yet-authorized feature). Nullable -
-- null until the first trust-status decision is ever made for a given user.

ALTER TABLE users
    ADD COLUMN trusted BOOLEAN NOT NULL DEFAULT false;

ALTER TABLE users
    ADD COLUMN verified_by_admin_id BIGINT;

ALTER TABLE users
    ADD COLUMN verified_at TIMESTAMP WITH TIME ZONE;

-- X3-E: binds SellerDiagnosticCommitment to the PolicyVersion published at the moment it was
-- frozen (freezeCommitment()) - the diagnostic/commitment-stage policy snapshot, deliberately
-- independent from auctions.policy_version_id (Auction's own, separately-snapshotted-at-
-- creation-time policy reference - see V33). Both snapshots are intentionally allowed to
-- diverge (locked business decision, X3-E §2) - this migration does not attempt to
-- synchronize them and no code path ever should.
--
-- Nullable, no backfill: every commitment frozen before this slice ships gets NULL here
-- permanently - the diagnostic-stage seller-violation trigger fails closed for those rows
-- (no valid policy snapshot to resolve penalties from), exactly the same "historical NULL is
-- never backfilled" discipline already used by V36's own new columns.

ALTER TABLE seller_diagnostic_commitment
    ADD COLUMN policy_version_id BIGINT NULL
        REFERENCES policy_version(id);

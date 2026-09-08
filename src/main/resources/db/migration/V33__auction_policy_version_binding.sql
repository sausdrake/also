-- SLICE 1b-2: binds Auction to the immutable PolicyVersion it was created under.
--
-- Purely additive: one nullable column, one FK. No existing row is touched, no existing
-- reserve_price/buy_now_price value is rewritten or reinterpreted.
--
-- policy_version_id is NULLABLE by design, not an oversight - NULL is the permanent,
-- intentional marker for a legacy, pre-feature auction (grandfathered under the old
-- reserve/buy-now semantics, see Auction.java's own Javadoc). Every NEW auction created after
-- this slice ships is assigned a non-null value at creation time (AdminAuctionService, fail
-- closed if no PolicyVersion is published) and it is never reassigned afterward.
--
-- No index is added: no query in this slice filters or joins on this column (it is only ever
-- read by id, off an already-loaded Auction row, to resolve that one auction's own policy).
-- An index can be added later if a future slice introduces such a query, without requiring
-- this migration to anticipate it.

ALTER TABLE auctions
    ADD COLUMN policy_version_id BIGINT NULL;

ALTER TABLE auctions
    ADD CONSTRAINT auctions_policy_version_id_fkey
        FOREIGN KEY (policy_version_id) REFERENCES policy_version(id);

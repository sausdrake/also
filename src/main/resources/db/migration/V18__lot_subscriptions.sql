CREATE TABLE lot_subscriptions (
    id         BIGSERIAL PRIMARY KEY,
    user_id    BIGINT NOT NULL REFERENCES users(id)    ON DELETE CASCADE,
    auction_id BIGINT NOT NULL REFERENCES auctions(id) ON DELETE CASCADE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (user_id, auction_id)
);
CREATE INDEX idx_lot_sub_user    ON lot_subscriptions(user_id);
CREATE INDEX idx_lot_sub_auction ON lot_subscriptions(auction_id);

-- AUCTION-01 (BD-02): the minimum bid step is ROUND(starting_price * 0.01, 3) - three decimals.
-- Widening only: the integer part keeps its 8 digits (NUMERIC(10,2) -> NUMERIC(11,3)), every existing
-- value is preserved exactly. Money columns (prices, bids, deposits) keep their 2-decimal scale.
ALTER TABLE auctions ALTER COLUMN min_bid_step TYPE NUMERIC(11, 3);

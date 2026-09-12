package com.example.autoauction.auction.domain.port;

import com.example.autoauction.auction.domain.PaymentDeadlineHistory;

import java.util.List;

/**
 * Slice 1B: persistence port for {@link PaymentDeadlineHistory}. Append-only by design - there
 * is deliberately no update/delete method.
 */
public interface PaymentDeadlineHistoryRepository {

    PaymentDeadlineHistory save(PaymentDeadlineHistory history);

    /** Ordered oldest-first - the INITIAL row (if any) is always first. */
    List<PaymentDeadlineHistory> findByAuctionIdOrderByChangedAtAscIdAsc(Long auctionId);
}

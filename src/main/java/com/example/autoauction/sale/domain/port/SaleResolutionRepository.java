package com.example.autoauction.sale.domain.port;

import com.example.autoauction.sale.domain.SaleResolution;

import java.util.Optional;

/**
 * X1: exposes exactly the operations SaleResolution's own locked shape supports - a single
 * insert (immutable core fields, SUCCESS-only in this slice) plus three narrowly-named,
 * claim-first atomic transitions for the one mutable field (financialResolutionStatus).
 * Deliberately no generic update/patch method - each transition method's own WHERE clause (in
 * the infrastructure implementation) is the sole concurrency guard and the sole enforcement of
 * which transitions are legal; there is no method that could express an illegal one (e.g.
 * FAILED -&gt; DONE, or anything out of DONE).
 */
public interface SaleResolutionRepository {

    SaleResolution save(SaleResolution saleResolution);

    Optional<SaleResolution> findById(Long id);

    Optional<SaleResolution> findByAuctionId(Long auctionId);

    boolean existsByAuctionId(Long auctionId);

    /** Atomic PENDING -&gt; DONE. Returns false if the row wasn't PENDING (already resolved, or FAILED). */
    boolean claimFinancialResolutionDone(Long id);

    /** Atomic PENDING -&gt; FAILED. Returns false if the row wasn't PENDING. */
    boolean claimFinancialResolutionFailed(Long id);

    /** Atomic FAILED -&gt; PENDING (explicit manual reset only). Returns false if the row wasn't FAILED. */
    boolean resetFinancialResolutionToPending(Long id);
}

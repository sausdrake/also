package com.example.autoauction.sale.domain;

/**
 * X1 (Sale Evidence + Sale Resolution foundation): the outcome a {@link SaleResolution} records
 * for an auction. Deliberately contains only SUCCESS in this slice - fault-outcome terminology
 * (buyer/seller/platform fault) has no committed consumer anywhere in this codebase yet and is
 * explicitly out of scope for X1 (locked decision 5). Adding those values is a small, additive,
 * precedented migration later (see auctions_status_check's own history in V31), never a
 * redesign - this enum does not need to anticipate them now.
 */
public enum SaleResolutionResult {
    SUCCESS
}

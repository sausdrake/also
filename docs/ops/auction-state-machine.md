# Auction, vehicle and seller-guarantee state machine

What the code does today. Every transition below runs in one database transaction under the
auction row lock (`findByIdWithLock`); money moves only after the conditional claim of the row it
belongs to, so a repeated or concurrent command cannot move it twice.

## Auction

```
CREATED ──start──▶ ACTIVE ──end, winner, reserve met──▶ WAITING_FOR_PAYMENT ──complete──▶ SOLD
   │                 │  └──end, no bids / reserve not met──▶ EXPIRED
   │                 └──cancel──▶ CANCELLED                       └──fail (B / BUYER_FAULT / PLATFORM_FAULT / C)──▶ SALE_FAILED
   ├──cancel (or no confirmation in time)──▶ CANCELLED
   └──Buy Now──▶ WAITING_FOR_PAYMENT  (see below)
```

`COMPLETED` is legacy only and never assigned.

## Vehicle

| Event | Vehicle |
|---|---|
| report approved | `APPROVED` |
| auction started, or Buy Now | `APPROVED → IN_AUCTION` |
| sale completed | `IN_AUCTION → SOLD` |
| sale failed: B, BUYER_FAULT | `IN_AUCTION → APPROVED` |
| sale failed: PLATFORM_FAULT, C | `IN_AUCTION → NEEDS_REDIAGNOSTIC` |
| auction EXPIRED | `IN_AUCTION → NEEDS_REDIAGNOSTIC` (AUC-NOSALE-01) |
| auction CANCELLED | `IN_AUCTION` or `APPROVED → NEEDS_REDIAGNOSTIC` (AUC-NOSALE-01) |
| re-diagnostic report approved | `NEEDS_REDIAGNOSTIC → APPROVED` on the new request |

## Seller guarantee (diagnostic commitment)

Frozen when the diagnostic request is created; `AWAITING_FINALIZATION` once the report is decided;
`FINALIZED` with one outcome, exactly once.

| Auction result | Outcome | Seller |
|---|---|---|
| SOLD; SALE_FAILED through the buyer or the platform | A | guarantee returned in full |
| SALE_FAILED, outcome B | B | diagnostic fee charged, rest returned |
| SALE_FAILED, outcome C | C | policy penalty withheld, rest returned |
| **EXPIRED** | A | guarantee returned in full (AUC-NOSALE-01) |
| **CANCELLED**, no seller fault | A | guarantee returned in full (AUC-NOSALE-01) |
| **CANCELLED** for confirmed seller violation(s) | C (`AUCTION_CANCELLED_C`) | penalty withheld once, rest returned |

Invariant: `guarantee = penalty withheld + fee charged + returned + still frozen`.

Cancellation, the seller violation, its penalty and the guarantee release are separate records:
the auction status (`auctions`), the violation decision and its per-violation penalties
(`seller_violation_resolution`, `…_item`), the ledger rows (`deposit_transactions`: `CHARGE`,
`UNBLOCK`) and the commitment outcome (`seller_diagnostic_commitment`).

### Admin commands

* `POST /api/v1/admin/auctions/{id}/cancel` — optional body `{"violationDefinitionIds":[…]}`;
  empty or absent means no seller fault.
* `POST /api/v1/admin/auctions/{id}/resolve-no-sale` — repair for an EXPIRED / CANCELLED auction
  that ended before AUC-NOSALE-01: returns a still-frozen guarantee in full and moves the vehicle
  to re-diagnostic; a guarantee already resolved is left untouched. Idempotent; refused for any
  other status and for an auction that is not the vehicle's latest.
* `POST /api/v1/admin/vehicles/{id}/rediagnostic` with reason `NO_SALE` — the new diagnostic
  cycle after an auction without a sale: allowed only when the vehicle's latest auction is
  EXPIRED / CANCELLED and its guarantee is resolved; an ordinary diagnostic request (nothing
  charged when ordered, its commitment carries the configured fee under the usual outcomes).

Tests: `NoSaleCycleE2EPgIntegrationTest` (PostgreSQL, API, concurrent admins, repair, re-list).

## Buy Now (BUYNOW-01)

Offered only while the auction is `CREATED` (before the start) and only when `buyNowPrice` is set.
The buyer pays the buyer-facing price (seller's Buy Now price + the auction's policy commission);
the seller's settlement is the seller's own Buy Now price.

```
CREATED ──Buy Now (verified, trusted, not the seller)──▶ WAITING_FOR_PAYMENT ──payment + handover + complete──▶ SOLD
                                                              └──fail (B / BUYER_FAULT / PLATFORM_FAULT / C)──▶ SALE_FAILED
```

In one transaction under the auction row lock: the buyer's participation deposit is frozen and
flagged as the winner's, the vehicle goes `APPROVED → IN_AUCTION`, the auction becomes
`WAITING_FOR_PAYMENT` with `saleFinalizationMethod = BUY_NOW` and the initial payment deadline.
From there it is exactly the ordinary sale: manual payment and handover evidence recorded by an
admin (no bank integration, BANK-EPIC), completion releases the buyer's deposit and returns the
seller's guarantee (A) and creates the settlement; a failure withholds the deposit once for the
buyer's fault, otherwise releases it.

* Concurrency: two buyers, or a buyer and a bid / start / cancel, serialise on the row lock; the
  first wins, the others get 409 and nothing of theirs is frozen.
* Repeated call / stale screen: any status other than `CREATED` is 409; the page reloads the lot
  after a refusal so the button disappears.
* Timeout: the sale shows `paymentDeadline` / `overdue`; the admin extends the deadline or resolves
  the sale as failed (`BUYER_FAULT` withholds the deposit). Nothing happens automatically on
  expiry — the same rule as for an ordinary win.
* Fixed defects: the vehicle never entered `IN_AUCTION` (completion and failure both threw), and
  the winner flag was set in a separate transaction that could not see the just-created freeze
  (completion and failure: "winner freeze not found").

Tests: `BuyNowCycleE2EPgIntegrationTest` (PostgreSQL, API: sold, concurrent buyers, buyer fault
with two admins, platform fault after payment, gates).

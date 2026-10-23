# Legal, tax and operations - open questions (WAVE G, MVP certification, 2026-10-23)

**LEGAL GATE = OPEN.** No qualified legal or tax opinion has been received. Nothing below is legal or tax advice, and
the code was not changed on any assumption about the answers. Each question is for a qualified professional in the
Kyrgyz Republic (KR) or for the operational owner.

## Business model as given by the product owner

- AlSo does not buy cars and does not sell cars in its own name; it provides an auction / platform service.
- Vehicle diagnostics are performed by a separate company under a cooperation agreement.
- AlSo earns a commission / service revenue.
- Participation deposits, diagnostic fees and penalties exist in the product and must be classified.

## What the system does today (facts the advisers need)

| Area | Current behaviour |
|---|---|
| Deposits | Users top up a balance held on the platform (credited by an administrator against bank evidence); a fixed participation deposit is frozen per auction and released after it, or kept on the winner's account until the sale is resolved |
| Withdrawals | Requested by the user, paid out manually by an administrator outside the system, recorded as PAID |
| Payment for the car | No payment integration (BANK-EPIC blocked by bank documentation); WAITING_FOR_PAYMENT ends with an administrator recording the sale |
| Commission | Computed by the system from the sale price (rate from the configured policy) |
| Diagnostic fee / deposit | Paid / frozen by the seller for a diagnostic request |
| Penalties | Seller violations with fixed or percentage penalties, resolved by an administrator |
| KYC | Selfie + identity document photographed in the browser, stored in Cloudflare R2 (object storage; country of processing per Cloudflare), reviewed manually by an administrator |
| Telegram | Optional sign-in with Telegram (OpenID Connect); the Telegram user id is stored and linked to the account |
| User agreement | **No acceptance is recorded.** Registration takes username, e-mail and password only; there is no offer / terms / privacy-policy acceptance step and no stored version or timestamp of an acceptance |

## Questions for a KR lawyer

1. Legal form of the service: is the agency / intermediary (platform) model, as described above, correct for an online car
   auction in the KR? What must the contracts with sellers, buyers and the diagnostics company say so that AlSo is not
   treated as the seller?
2. Commission: who is the customer of the commission (seller, buyer or both), and what must the offer say about it?
3. Diagnostics: the relationship with the diagnostics company - who is liable to the buyer for a wrong report? What must
   the report disclaimer say?
4. Deposits held on the platform balance: is holding users' money between top-up and withdrawal permitted for AlSo as
   it is, or does it need a payment-institution licence, a nominal (escrow) account or a bank partner?
5. Penalties and forfeiture: can a seller's penalty or a winner's deposit be withheld under the offer, and what notice,
   limits and appeal must the rules contain?
6. Refunds: terms and deadlines for returning deposits and fees; what happens when a sale fails.
7. User agreement / public offer: is electronic acceptance (a checkbox at registration and before the first bid) enough?
   What must be stored as proof (text version, timestamp, account, IP)? **The system records no acceptance today** - the
   answer decides whether this has to be built before launch.
8. KYC: is AlSo obliged to identify users (and to what standard), or is it a voluntary control? How long must identity
   images be kept, and when must they be deleted?
9. Personal data: registration with the KR personal-data authority, a privacy policy, consent wording, processing of
   biometric-like data (selfie), cross-border transfer (Cloudflare R2, Telegram), access and deletion requests.
10. Telegram identity: may a Telegram account be the sole identity of a user who trades and withdraws money?
11. Document storage: retention of auction records, sale confirmations, withdrawal evidence and admin decisions.
12. Public diagnostic reports: an approved report (vehicle data, VIN, ratings, photos, video) is public on the lot
    page. May it be published without the seller's separate consent, and must plates, faces or documents visible in
    the photos be hidden? Who is liable to a buyer who relied on a public report for a car sold elsewhere?
13. Withdrawals: may a payout go only to an account in the name of the verified user, and does paying out a balance
    create any reporting or anti-money-laundering obligation for AlSo?

## Questions for an accountant / tax adviser

1. Classification of each money flow for AlSo: commission, diagnostic fee, participation deposit (frozen, returned),
   withheld deposit, seller penalty, top-ups and withdrawals of the user balance.
2. Which of them are AlSo's revenue, and when (at sale confirmation, at withholding, at payout)?
3. VAT / sales tax and income tax treatment of each revenue type; invoices or receipts required for users.
4. Money of users held on the balance: accounting as liabilities; reconciliation with the bank account.
5. Penalties and withheld deposits: separate tax analysis requested by the product owner.
6. Payments to the diagnostics company: agent scheme or purchase of services.

No tax rate or rule is assumed in the code.

## Questions for the operational owner

1. Who credits top-ups and pays withdrawals, with what evidence, and who checks them (four-eyes)?
2. Who reviews KYC, and what are the review rules (accept / reject reasons, time limits)?
3. Support channel and dispute process for buyers and sellers.
4. Incident response: who is called when `/api/health` is down, a backup fails or R2 errors appear.
5. Retention and deletion of KYC images and logs in practice.

## Owner sign-off checklist

The gate closes only when every line below has a written answer from the named role (attach the document or its
reference). Nothing here is ticked by the development team.

| # | Topic | Role | Questions | Answer / document |
|---|---|---|---|---|
| L1 | Auction / platform model | Lawyer | 1 | |
| L2 | Third-party diagnostics, report disclaimer | Lawyer | 3, 12 | |
| L3 | Commission: customer and offer wording | Lawyer + accountant | 2; tax 1-3 | |
| L4 | Deposits held on the balance | Lawyer + accountant | 4; tax 1, 4 | |
| L5 | Withdrawals / payouts | Lawyer + operations | 6, 13; ops 1 | |
| L6 | Penalties and withheld deposits | Lawyer + accountant | 5; tax 5 | |
| L7 | KYC obligation and image retention | Lawyer + operations | 8; ops 2, 5 | |
| L8 | Personal data, selfie (biometric-like), cross-border | Lawyer | 9, 10 | |
| L9 | Document storage and retention | Lawyer + operations | 11; ops 5 | |
| L10 | User agreement, offer, privacy policy texts | Lawyer | 7 | |
| L11 | Acceptance logging (what to store, before which action) | Lawyer -> product owner | 7 | |
| T1 | Tax treatment of every money flow, invoices | Accountant | tax 1-3, 6 | |
| O1 | Four-eyes for top-ups / payouts, KYC review rules, support, incidents | Operations owner | ops 1-4 | |

If L11 requires recorded acceptance, that is a new code task (an approved text version, timestamp and account per
acceptance); it is not built on an assumption.

## What would close this gate

A written opinion from a KR lawyer and an accountant covering the questions above, the published user agreement /
offer and privacy policy, and - if the lawyer requires it - recorded acceptance of them in the product (a new task).

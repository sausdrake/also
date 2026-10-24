# Legal documents, acceptance and privacy requests (LEGAL-DATA-01, PRIVACY-REQ-01)

## What the system does

* **Versions.** Each document type (`TERMS_OF_USE`, `PRIVACY_POLICY`, `PERSONAL_DATA_PROCESSING_POLICY`,
  `AUCTION_RULES`, `KYC_POLICY`, `DIAGNOSTIC_RULES`, `FINANCIAL_RULES`) has versions:
  `DRAFT → PENDING_LEGAL_REVIEW → APPROVED → ACTIVE → RETIRED`. The text can be edited only as a draft;
  from submission on it is frozen and identified by its SHA-256. At most one version per type is
  `ACTIVE` (unique index). Activating a version retires the previous one.
* **Maker-checker.** The author of a text never approves it (service check and a DB `CHECK`). The
  approval requires a note: who reviewed the text and when (lawyer, owner).
* **What users see.** `/legal/<slug>` and `GET /api/v1/public/legal/documents[/<TYPE>]` show only
  `ACTIVE` versions. With nothing in force the page says the document is being prepared — a draft is
  never shown as valid.
* **Kinds.** `ACCEPTANCE` (contract), `ACKNOWLEDGEMENT` (notice read — not consent), `CONSENT`
  (optional, withdrawable), `INFORMATIONAL` (read only). ACCEPTANCE and ACKNOWLEDGEMENT versions in
  force are **required**.
* **Recording.** `legal_acceptance`: user, version id, type, version number, text hash, kind,
  status, source (`REGISTRATION` / `RE_ACCEPTANCE` / `PROFILE`), time, IP (`request.getRemoteAddr()`),
  user-agent. One row per user and version, never deleted; only a consent can be withdrawn.
* **Gate.** Registration requires ticking every required version in force. A signed-in user without
  the current acceptance (an account made before activation, via Telegram, or a new edition) gets a
  dialog after sign-in, and bids, Buy Now, withdrawals, payments and diagnostic requests answer
  `403 LEGAL_ACCEPTANCE_REQUIRED` until they accept. While nothing is in force nothing is required.
* **Privacy requests.** Profile → «Документы и данные»: a user files a request (access, correction,
  deletion, objection, consent withdrawal, other; at most 5 open). Admin → «Документы и данные» takes
  and answers it; the user is notified. The system changes or deletes nothing by itself.

## Putting the documents in force (after the lawyer)

1. Admin A: «Документы и данные» → new version (type, number, title, kind, text) → «На юридическую проверку».
2. The lawyer checks the frozen text (the hash is shown in the API; compare with the approved file).
3. Admin B (not A): «Утвердить», note e.g. «Юрист Иванов И.И., заключение от 01.11.2026».
4. Admin A or B: «Ввести в действие». From now on registration asks for it and existing users are asked
   at their next sign-in.

The drafts in `.autonomous/legal/` (local, not committed) are **not** approved texts — they are input for
the lawyer.

## Behind a reverse proxy

The recorded IP is the servlet's remote address. Behind nginx / Cloudflare set
`server.forward-headers-strategy=framework` (and only trust the proxy) so the client's address is recorded
instead of the proxy's.

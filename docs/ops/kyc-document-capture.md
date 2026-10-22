# KYC document capture (KYC-CAPTURE-01)

KYC documents are **photographed with the device camera inside AlSo**. There is no file upload for KYC: no
`<input type="file">`, no gallery, no PDF, no reuse of an earlier image. The general `/api/media/upload`
endpoint is unrelated to KYC and cannot be used for it (a KYC submission accepts only identity capture slots).

## Which sides are photographed

| Document type (`documentType`)          | Captures, in order        |
|-----------------------------------------|---------------------------|
| ID card (`NATIONAL_ID`)                 | `FRONT`, then `BACK`      |
| Driver licence (`DRIVER_LICENSE`)       | `FRONT`, then `BACK`      |
| International passport (`INTERNATIONAL_PASSPORT`) | `DATA_PAGE` only (no back side) |

Plus the live selfie (`SELFIE`, MVP-004). The rule lives in one place, `DocumentCapturePolicy`, and is served
to the frontend by `GET /api/v1/identity/capture-requirements`.

## User flow

Choose the document type → for each side: camera → photo → preview → **Переснять** or **Подтвердить** → the
camera opens for the next side → review (each side: **Просмотреть** / **Переснять**) → selfie → submit. A photo
counts only after **Подтвердить**; retaking a side removes the old photo at once. The camera is released after
each photo and when the page is left. When the camera cannot start, the real reason is shown (permission denied,
no camera, camera busy, unsupported browser, page not secure) — never a file-picker fallback.

The camera needs a secure page: `https://…` or `http://localhost`. A phone on the LAN needs the dev HTTPS setup
(`docs/ops/dev-https-lan.md`).

## API contract

1. `POST /api/v1/identity/documents/upload-url` — `{contentType: "image/jpeg", side, documentType}`
   (`documentType` absent for `SELFIE`). The server creates the capture slot for the authenticated user and
   returns a presigned PUT signed for `image/jpeg`. Any other content type, an unknown side or a side the document
   does not have → 400.
2. The browser `PUT`s the JPEG to the presigned URL (private storage).
3. `POST /api/v1/identity/submissions` — `{issuingCountry, documentType, rawDocumentNumber, documentStorageKey,
   documentBackStorageKey?, selfieStorageKey}`. `documentBackStorageKey` is required for an ID card / driver
   licence and refused for a passport.

## What the backend enforces (the frontend is not a security boundary)

For every capture of a submission (`IdentityCaptureVerifier`): it exists (404), belongs to the authenticated user
(403), was issued for exactly this side and document type (400), is unused (409 — each capture is consumed by the
submission that uses it, atomically), is at most 15 minutes old (400), and is a JPEG both in its slot and in
storage (400). Every refusal creates nothing and consumes nothing. An ID card / driver licence without its back
side cannot be submitted or approved; a pending one submitted before this change must be rejected and resubmitted.

## Limits

The browser uploads straight to storage, so the server cannot prove a photo came from a camera. Camera-only
capture, single-use per-side slots and the validity window make re-using an existing file harder; they do not
detect a photo of a screen or an edited printout.

## Anti-spoofing audit (KYC-ANTI-SPOOF-01, 2026-10-22)

In place:

| Control | Where | Stops |
|---|---|---|
| Camera-only capture, no file picker | frontend | casual upload of an existing file (not a security boundary) |
| One slot per side, per document type, per user | `IdentityCaptureVerifier` | re-using another user's or another step's image |
| Single use (consumed atomically) | `IdentityCaptureVerifier` | replaying a capture into a second submission |
| 15-minute validity | `IdentityCaptureVerifier` | submitting an image prepared long before |
| JPEG in slot and in storage | `IdentityCaptureVerifier` | other file types |
| Slot rate limit (30 / hour / user), abandoned slots removed after 24 h | KYC-SLOT-01 | bulk slot minting, stale images in storage |
| Manual review of selfie + document by an administrator | admin KYC queue | the final decision (PO KYC policy) |

Not in place:

- liveness detection (a photo or a video of a face shown to the camera, a virtual camera, a deepfake);
- face match between the selfie and the document photo;
- document authenticity (printout, screen photo, edited image), MRZ / barcode reading, cross-check of the entered
  document number against the image;
- proof that an image came from the device camera (the browser uploads to storage directly, so it cannot be proven).

None of these can be added without a choice the product owner has to make: a liveness / document-verification
vendor (cost, data processing and storage of biometric data, the country where data is processed) or an
in-house approach with its own accuracy targets.

**Decision (PO, MVP certification, 2026-10-23):** for the MVP the security boundary is camera-only capture +
one-time capture slot + JPEG validation + manual administrator verification. No vendor is connected. This is **not**
liveness detection and must not be described as such.

Residual risk accepted for the MVP: a photo / screen / virtual camera shown to the capture, a forged or edited
document, and a selfie of a different person than the document holder pass the technical checks; only the
administrator's review can catch them. Revisit (vendor or in-house liveness) before scaling beyond manual review.

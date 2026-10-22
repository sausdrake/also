# Media and object storage - production readiness (MEDIA-PROD-01, 2026-10-22)

Two stores:

| Store | What | Access |
|---|---|---|
| Cloudflare R2 (`R2_ACCOUNT_ID`, `R2_BUCKET`, keys) - private bucket | KYC captures and selfies; diagnostic photos / videos / thumbnails | presigned PUT (upload, 15 min) and presigned GET (read, 10 min) only; storage keys never leave the server |
| Local disk `/uploads` (`MEDIA_UPLOAD_DIR`, docker volume `media_uploads`) | auction photos, report photos from `/api/media/upload` (ADMIN / DIAGNOSTIC only, JPEG/PNG/WebP, magic bytes checked, max 10 MB) | public, served at `/uploads/**` |

## Fixed in MEDIA-PROD-01

- `spring.servlet.multipart.max-file-size=10MB`: Spring's 1 MB default refused ordinary phone photos before the 10 MB
  check; an oversized file is now 413 "Файл слишком большой" instead of 500.
- The anonymous public report (`/api/v1/public/reports/media/{id}/url`) serves a video only after processing (ffprobe
  duration / size checks) finished - never a PENDING, PROCESSING or FAILED one. Admin, owner and the assigned
  diagnostician still reach a failed video.
- The reconciler promotes a never-finalized upload only when the detected content matches the row's type (PHOTO / VIDEO),
  as finalize does.

## Cleanup that exists

- KYC: abandoned capture slots older than 24 h - object, then row (hourly, KYC-SLOT-01).
- Diagnostic media (every 5 min): PENDING_UPLOAD rows without an object expire after 60 min; DELETED rows' objects
  (main, thumbnail) are removed; orphan thumbnails and stale PROCESSING rows are handled by VideoProcessingService.

## Open before production (owner / later tasks)

1. Bucket configuration is not in the repository: confirm the bucket is private (no public r2.dev URL / custom domain),
   set CORS for the site origin (PUT with `Content-Type`, GET), and add a lifecycle rule as a safety net.
2. FAILED diagnostic uploads (oversized, wrong content) keep their object in R2 - no cleanup yet.
3. The presigned PUT does not sign the content length: the 200 MB cap is checked after upload (HEAD); oversized objects
   stay until item 2 exists.
4. Media of an APPROVED report are public by sequential id (the report itself is public); unlisted media of such a report
   are reachable by enumeration. Decide whether approved reports are fully public (product decision).
5. `/uploads` lives on one disk: single instance only (docs/ops/single-instance.md); include the `media_uploads` volume
   in backups (PROD-BACKUP-01).
6. The R2 endpoint is fixed to `https://<account>.r2.cloudflarestorage.com` (no jurisdiction-specific endpoint).
7. A real end-to-end run against R2 (KYC capture -> admin review; diagnostic video -> buyer report) needs a test bucket or
   the PO's go-ahead to write synthetic objects to `also-dev` (BLK-04).

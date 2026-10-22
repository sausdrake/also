# Production readiness checklist (WAVE F, MVP certification, 2026-10-23)

No production environment exists yet: no server, domain, TLS certificate, frontend hosting or monitoring. This page
lists what the code already provides and what the owner has to set up. It names no cloud provider. "Code" = done and
tested in this repository; "Owner" = needs infrastructure or an account outside the repository.

## Backend

| Requirement | Status | Where / what to do |
|---|---|---|
| Production profile | Code | `SPRING_PROFILES_ACTIVE=prod`; `ProductionProfileGuard` refuses to start with the dev/test profile, a short or publicly known JWT secret, a wildcard / http / localhost CORS origin or a missing media URL (`ProdProfileBootPgIntegrationTest`) |
| Secure JWT secret, DB credentials | Owner | generate fresh values for production (never reuse development ones - SEC-08B); pass them as environment variables / secrets, never in files in the repository |
| CORS allowlist, no wildcard | Code | `ALLOWED_ORIGINS` = the real https site origin(s); the guard refuses `*` |
| Swagger disabled | Code | `springdoc.*.enabled=false` in prod (tested) |
| Security headers | Code | `X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY`, no-cache on API responses; prod adds `Content-Security-Policy: default-src 'none'; frame-ancestors 'none'; ...` (PROD-SEC-01). HSTS is sent only on HTTPS requests - see TLS below |
| HTTPS termination | Owner | a reverse proxy (or load balancer) in front of the app terminates TLS; publish port 8081 only to it (compose currently publishes `8081:8081` on all interfaces - change it on the server); follow docs/ops/single-instance.md "Reverse proxy and client IP" for `X-Forwarded-For` and the rate limits |
| Container runs as non-root | Code | `Dockerfile` - `appuser` |
| Health / readiness | Code | `GET /api/health` - 200 only when the database answers, 503 otherwise (compose healthcheck uses it). Use it as readiness |
| Liveness | Owner | do not restart the app on a database outage: if an orchestrator needs a liveness probe, use a TCP check on 8081, not `/api/health` |
| Graceful shutdown | Code | `server.shutdown=graceful`, 30 s per phase (PROD-OPS-01): in-flight requests finish on SIGTERM; give the container a stop timeout of at least 35 s |
| Single instance | Code + owner | in-memory rate limits, caches and schedulers: run exactly one app instance (docs/ops/single-instance.md) |
| Admin bootstrap | Code | docs/ops/admin-bootstrap.md - first administrator from environment variables, removed afterwards |

## Frontend

| Requirement | Status | Where / what to do |
|---|---|---|
| Production build | Code | `cd client && npm ci && npm run build` -> `client/dist` (static files) |
| Correct API URL | Code | `VITE_API_URL=https://<api origin>` is required at build time - a build without it fails (PROD-FE-01) instead of falling back to `http://localhost:8081` |
| No secrets in the bundle | Code | the bundle reads only `VITE_API_URL` (checked in E2E security runs) |
| HTTPS hosting | Owner | serve `client/dist` over HTTPS (any static host or the same reverse proxy) |
| SPA routing | Owner | the host must answer every unknown path with `index.html` (client-side routes such as `/auction/42`, `/admin`, Telegram callback) while keeping real files (`/assets/*`, `/brand/*`) |
| Frontend CSP | Owner | set on the static host; it must allow the API origin (`connect-src` https + wss), R2 presigned URLs (`connect-src https://<account>.r2.cloudflarestorage.com`, `img-src`/`media-src` the same), `/uploads` images, and Telegram sign-in redirects. Test it in the browser before enforcing (Report-Only first) |
| Camera (KYC) | Owner | camera access needs HTTPS - a plain-http site cannot do KYC capture |

## Database

| Requirement | Status | Where / what to do |
|---|---|---|
| PostgreSQL 16 | Code | `docker-compose.yml` (`postgres:16-alpine`), port bound to 127.0.0.1 only |
| Migrations | Code | Flyway runs at startup, `ddl-auto=validate`; migrations are additive. Take a backup before every deploy that adds a migration |
| Backup | Code + owner | `ops/backup/pg_backup.sh` (verified dump + checksum + retention); owner: cron schedule and an off-server copy (docs/ops/backup-restore.md) |
| Restore | Code | `ops/backup/pg_restore.sh` into an empty database only; restore test passed 2026-10-22 (47/47 tables identical, app booted on the restore) |

## Object storage (R2)

See docs/ops/media-storage.md. Owner: a separate production bucket and keys; private (no public URL); **a CORS rule for
the site origin - currently missing, browser uploads fail without it**; a lifecycle rule as a safety net. Code: presigned
PUT 15 min / GET 10 min, storage keys never leave the server, FAILED and DELETED objects removed by the 5-minute sweep,
abandoned KYC captures removed after 24 h. Include the `media_uploads` volume (`/uploads`) in the backups.

## Observability (minimum)

| Signal | Status | Where / what to do |
|---|---|---|
| Application and error logs | Code | stdout + `logs/autoauction.log` (10 MB files, 30 days, 2 GB cap) on the `app_logs` volume; no secrets / tokens logged (security reviews) |
| Health | Code | `/api/health` (DB check) |
| Scheduler failures | Code | `SchedulerHealthSignal` (admin dashboard) for auction completion, media, KYC cleanup |
| Uptime / DB / storage alerts | Owner | an external uptime check on `/api/health` (alert on non-200); alert on R2 errors in the log (`MEDIA-3.2` / `R2` warnings) |
| Disk alerts | Owner | alert at 80 % on the volumes of PostgreSQL, `/uploads`, logs and backups |
| Backup failure detection | Owner | cron must report a non-zero exit of `pg_backup.sh` (mail / alert) and an alert when the newest backup is older than 26 h |

## Deploy, smoke, rollback

```
1. Backup:    ops/backup/pg_backup.sh /var/backups/also           (always, before every deploy)
2. Build:     docker compose build app ; cd client && VITE_API_URL=https://<api> npm run build
3. Deploy:    docker compose up -d app   (graceful stop of the old container, Flyway runs on start)
4. Health:    wait for /api/health = 200 (compose healthcheck; give up after 3 minutes)
5. Smoke:     log in as the admin; open the auction list; open one auction page; GET /api/v1/notifications;
              a KYC capture and a diagnostic photo upload from the browser (R2 + CORS)
6. Rollback:  if 4 or 5 fails -
              a. no new migration in this release: start the previous image tag (keep it tagged, e.g. also-app:<previous>)
              b. a new migration ran: migrations are additive, the previous version usually still runs on the new schema;
                 if not, restore the step-1 backup into a NEW database (ops/backup/pg_restore.sh), point the previous
                 image at it, verify, then switch - never restore over the live database
              c. frontend: re-publish the previous client/dist
```

The rollback path above is documented, not exercised: there is no production or staging server to run it on. Run it
once on staging before go-live (deploy N, deploy N+1 with a migration, roll back to N).

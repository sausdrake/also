# Security gate before launch (SEC-07)

Status: **checks done, sign-off by the Product Owner pending.** Date of the scan: 2026-10-16.
Re-run the dependency scans before the go-live: advisories appear every week.

## Scope and method

| Area | How it was checked |
|---|---|
| Object-level access (IDOR) on `/api/**` | Every non-admin endpoint with an id in the path, query or body, read in code down to the service check; findings confirmed by a failing test first |
| Anonymous endpoints | Every `/api/v1/public/**` response compared field by field with what the public page renders |
| Uploads | SEC-04 (magic bytes, `nosniff`), media finalize/read/delete authorization |
| WebSocket | SEC-03 (allowed origins), JWT at CONNECT (SEC-01), broadcast payloads |
| Debug / service endpoints | No actuator; API docs off in `prod`; dev seeding only under `@Profile("dev")`, refused in `prod` (CFG-01) |
| Frontend dependencies | `npm audit --omit=dev` |
| Backend dependencies | OSV database, every runtime Maven artifact with its resolved version |

## Found and fixed

| Task | Severity | Finding | Commit |
|---|---|---|---|
| SEC-12 | Medium-High | Anonymous report and vehicle endpoints returned the seller's id and username, the frozen deposit, staff ids and usernames (admin username = half a login), seller-violation decisions, licence plate | `9bdc824` |
| SEC-14 | Low | Any authenticated user could "finalize" someone else's READY diagnostic media and read its metadata (caption) | `0f5f081` |
| SEC-13 | High (bundle: axios) | Frontend: 1 critical, 2 high, 3 moderate in production dependencies | `ab48b2c` |
| SEC-15 | Critical | Backend on end-of-life Spring Boot 3.1.5: 26 vulnerable packages (Tomcat 7 critical, Spring Security 2 critical, pgjdbc SQL injection, ...) | `e86d1fa` |

Earlier fixes in the same area: SEC-01 blocked users lose access at once, SEC-02 second registration
path closed, SEC-03 WebSocket origins, SEC-04 upload content check, SEC-06 admin action journal,
SEC-09 seller cannot bid on their own car, SEC-11 database port not public, ERR-01 no 500 for client errors.

## Residual risks - need a decision

| # | Risk | Assessment | Proposed decision |
|---|---|---|---|
| R1 | `spring-webmvc` 6.2.19: two critical advisories (XsltView path limitation, SSE fragment rendering), fixed only in Spring 7 | Not exploitable: the application uses neither XsltView nor SSE/fragment rendering (checked in code) | Accept until SEC-16 |
| R2 | Spring Boot 3.x has no open-source support since 2026-06-30 - new advisories will not get 3.x fixes | Today clean except R1; risk grows with time | **Decide: SEC-16 (move to Boot 4.x, large migration) or commercial support for 3.5** |
| R3 | `react-router` 6.x: open redirect via backslash; SSR hydration | Not exploitable: every navigation target is built by the app from numeric ids; no SSR | Resolved by FE-03: `react-router-dom` 7.18.4 (both advisories fixed in 7.18.0); `npm audit --omit=dev` finds 0 vulnerabilities |
| R4 | Dev-only build tooling (tailwindcss 3 chain) | Not shipped to users | Accept |
| R5 | Public auction responses and WebSocket messages carry the numeric `winnerId`; the public auction shows the reserve price | No usernames; reserve visibility is BIZ-01 | Decide with BIZ-01 |
| R6 | `/api/v1/public/vehicles/{id}` answers for a vehicle in any status (now only public car data) | Low: no personal or staff data left | Accept, or restrict to listed statuses |
| R7 | Login rate limit behind a reverse proxy (SEC-10) | Depends on the real proxy (INF-05) | Implement with INF-05 |

## Not covered here

- HTTPS, HSTS and security headers on the real server (SEC-05, INF-04/05).
- A test against the running stand (penetration test, E2E-01).
- SEC-08: the public repository and its secrets - the owner's action (see SEC-08B below).

## SEC-08B - secrets in the git history (2026-10-22)

Every blob ever reachable in this repository (4254 blobs, 249 commits, including the archives `src.zip` /
`client.zip`) was scanned for credentials; values were compared in memory only - nothing was printed.

| Credential | In history | Status |
|---|---|---|
| Database password of the early project (`.env`, `docker-compose.yml`, `application.properties`, `src.zip`) | yes | **Rotated.** The current password differs; the old one is rejected by the dev PostgreSQL (checked by a login attempt) |
| `jwt.secret` of the early project | yes - the well-known tutorial example key | **Replaced.** The current `JWT_SECRET` is a different, 88-character value |
| A JWT access token pasted into `task.txt` | yes | Expired 229 days ago and not signed by the current key - unusable |
| Current `JWT_SECRET`, `DB_PASSWORD`, `R2_ACCESS_KEY_ID`, `R2_SECRET_ACCESS_KEY`, Telegram client secret | no | Never committed |
| `ALSO_ADMIN_PASSWORD` in `docs/ops/admin-bootstrap.md` | current tree | A placeholder, not a real password |

Conclusion: no live credential is in the history, so rewriting it is **not needed for security**. Purging the
early commits from the public repository remains optional hygiene and the owner's decision (it rewrites
public history). Production must use freshly generated secrets - never a value from development.

Owner action (development machine, not production): the local PostgreSQL listens on all interfaces and the
development database password is short - restrict `listen_addresses` to `localhost` or set a strong password.

## Sign-off

| Role | Name | Decision on R1-R7 | Date |
|---|---|---|---|
| Product Owner | | | |

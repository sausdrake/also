# Single application instance (OPS-01)

The MVP backend runs as **exactly one** application container. Do not scale `app`
(`docker compose up --scale app=2`), do not run a second copy against the same database, and do
not run a rolling deploy that keeps the old and the new container serving at the same time.
Deploy by stop-then-start: a short downtime is accepted for the MVP.

Balance changes take database row locks (`PESSIMISTIC_WRITE` / `@Version`) and credits are
protected by unique references, so the money paths are designed not to double-move funds even
under concurrency - but running two instances has **not been tested**, and several features keep
state in the memory of the process and silently degrade.

## What breaks with a second instance

| Area | State kept in memory | Effect with two instances |
|---|---|---|
| Live auction updates | STOMP simple broker (`WebSocketConfig.enableSimpleBroker`) | A bid accepted by instance A is pushed only to clients connected to A; clients on B see stale prices until they reload |
| Scheduled jobs | `@Scheduled` without a distributed lock (auction completion/start/cancel, payment confirmation/expiry/recovery, financial recovery, media reconciliation, video processing, token cleanup) | Every job runs on every instance. Row locks are meant to keep the money right, but jobs contend for the same rows, retries and warnings multiply |
| "Ending soon" notifications | `AuctionCompletionScheduler.sentEndingWarnings` | Each instance sends its own 60/15/5-minute warnings - users get duplicates |
| Bid and login rate limits | `RateLimitService`, `AuthRateLimitService` buckets | Each instance counts separately: the effective limit is multiplied by the number of instances |
| System settings | `@Cacheable("systemSettings")`, local cache | A change made through instance A is not seen by instance B until B restarts |
| Scheduler health | `SchedulerHealthSignal` | Each instance reports only its own jobs |
| Uploaded media (local storage) | `uploads/` directory of the container | A file uploaded to A is not served by B (until media move to R2, S3-*) |

## Before running more than one instance

Not part of the MVP. Each item needs a decision and its own task:

- an external STOMP broker relay (RabbitMQ/ActiveMQ) instead of the simple broker;
- a distributed lock for scheduled jobs (for example ShedLock on PostgreSQL);
- the "ending soon" de-duplication and the rate-limit buckets in the database or Redis;
- a shared cache, or no cache, for system settings;
- object storage (R2) for media.

## Reverse proxy and client IP (SEC-10)

The login and registration rate limits use the TCP peer address (`getRemoteAddr()`); forwarded
headers are deliberately ignored. Once the application sits behind the reverse proxy (INF-05),
every request comes from the proxy's address, so all users share one bucket: a single client
can exhaust the login limit for everyone. Before go-live, together with INF-05:

- publish the application port only to the proxy (not `0.0.0.0:8081` on the public interface);
- let the proxy overwrite `X-Forwarded-For` (never append a client-supplied value);
- enable `server.forward-headers-strategy=native` with `server.tomcat.remoteip.internal-proxies`
  set to the proxy's address only, and verify with two clients that each gets its own bucket.

## Check

```
docker compose ps app        # exactly one app container
```

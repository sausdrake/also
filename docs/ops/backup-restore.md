# PostgreSQL backup and restore (PROD-BACKUP-01)

## Backup

`ops/backup/pg_backup.sh [backup_dir]` (default `/var/backups/also`) on the server, next to docker compose:

- `pg_dump -Fc --no-owner` runs inside the `autoauction-postgres` container over the local socket - no password on a
  command line or in the script;
- the archive is written as `.partial`, checked with `pg_restore --list`, then renamed to `also-<UTC stamp>.dump` and
  given a `.sha256` file; a failed dump never looks like a good backup;
- retention: `ALSO_BACKUP_RETENTION_DAYS` (default 14) - only `also-*.dump` files older than that, never the newest.

Schedule it with the host's cron, e.g. daily: `15 3 * * * /opt/also/ops/backup/pg_backup.sh /var/backups/also`.
Copy the backup directory off the server (another machine or object storage) - a backup on the same disk does not
survive the loss of that disk. Overrides: `ALSO_PG_CONTAINER`, `ALSO_PG_DB`, `ALSO_PG_USER`.

Not covered here: the `media_uploads` volume (auction / report photos, docs/ops/media-storage.md) - back it up with the
same schedule (e.g. `tar` of the volume) - and R2 objects (KYC, diagnostic media).

## Restore

`ops/backup/pg_restore.sh <backup.dump> <container> <db> <user>` restores into an **empty** database only: it checks
the checksum, refuses a target that already has tables, and stops on the first error. Never restore over the live
database: restore into a new container / database, verify, then switch the application to it.

## Restore test (2026-10-22, isolated containers, dev database untouched)

1. Throwaway PostgreSQL 16 container, schema by the application (62 Flyway migrations), data through the API: admin,
   3 users, 3 deposit ledger entries (credits of 1000.25 / 2000.25 / 3000.25), a trusted flag, settings.
2. `pg_backup.sh` -> 193 433-byte archive + checksum.
3. `pg_restore.sh` into a second, empty container -> ok; a second restore into the now non-empty target -> refused (exit 4).
4. All 47 tables compared between source and restore (row count + md5 of the ordered rows): identical.
5. The application started on the restored database: Flyway validated 62 migrations, nothing to migrate; admin login ok;
   the user's deposit 2000.25 / 0 and trusted flags as before.

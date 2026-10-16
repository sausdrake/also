# PostgreSQL version (DB-03)

Production runs **PostgreSQL 16** (`postgres:16-alpine` in `docker-compose.yml`) - the version every
migration, boot, concurrency and end-to-end test runs against. `PostgresVersionAlignmentTest` fails
the build when the compose image and a test container image differ. Change both together, never one.

## Fresh database

`docker compose up -d` creates an empty `postgres_data` volume; the application applies every Flyway
migration (V1 to the latest) at start-up and Hibernate validates the schema.

## An existing volume of another major version

A data directory belongs to the major version that created it. PostgreSQL 16 **refuses to start** on a
directory created by 15 - nothing is lost, but the service does not come up. Observed in the DB-03 check:

```
FATAL:  database files are incompatible with server
DETAIL:  The data directory was initialized by PostgreSQL version 15, which is not compatible with this version 16.15.
```

Never point the new image at the old volume, and never delete the old volume to "make it start".

Move the data with a logical dump, keeping the old volume until the new database is verified:

1. Stop the application: `docker compose stop app`.
2. Dump from the **old** server (still the old image):
   `docker compose exec postgres pg_dump -U auto_auction_user -d auto_auction -Fc -f /tmp/also.dump`
   and copy it out: `docker compose cp postgres:/tmp/also.dump ./also.dump`.
   Keep the dump and its checksum with the backups (INF-08).
3. Stop the database and set the volume aside under a new name - do not delete it:
   `docker compose down` (no `-v`), then point `docker-compose.yml` at a new volume name
   (for example `postgres16_data`) so the old one stays untouched.
4. Start the new PostgreSQL 16 on the empty volume: `docker compose up -d postgres`.
5. Restore: copy the dump in and run
   `pg_restore -U auto_auction_user -d auto_auction --no-owner /tmp/also.dump`.
6. Start the application; Flyway must report the schema as up to date (no pending migration) and
   `/api/health` must answer `db: UP`.
7. Verify the money before reopening: per-user balances and blocked amounts, and the ledger row
   counts, equal the values taken from the old database before step 1.
8. Only after a successful check and a fresh backup may the old volume be removed - a separate,
   deliberate decision.

This procedure was rehearsed in DB-03: a PostgreSQL 15 database migrated to V60 was dumped, restored
into an empty PostgreSQL 16, and the application started on it with Flyway reporting "Schema is up to
date", `/api/health` UP and identical row counts.

`pg_upgrade` is an alternative for large databases; it needs both major versions' binaries and is
not part of the MVP procedure.

## Not covered here

- Choosing or provisioning the production server's database (INF-06).
- Applying the migrations to the production database (DB-01).
- The read-only check of an older database before its migration (DB-02).

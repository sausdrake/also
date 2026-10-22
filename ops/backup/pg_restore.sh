#!/bin/sh
# PROD-BACKUP-01: restore an AlSo backup into an EMPTY database - a verification / disaster-recovery target, never the
# live database. Refuses a target that already has tables. Verifies the checksum first.
#
# Usage: ops/backup/pg_restore.sh <backup.dump> <target_container> <target_db> <target_user>
set -eu

DUMP="$1"; CONTAINER="$2"; DB="$3"; DB_USER="$4"

[ -f "$DUMP" ] || { echo "no such backup: $DUMP" >&2; exit 2; }
if [ -f "$DUMP.sha256" ]; then
    ( cd "$(dirname "$DUMP")" && sha256sum -c "$(basename "$DUMP").sha256" ) || { echo "checksum mismatch" >&2; exit 3; }
else
    echo "warning: no checksum file next to $DUMP" >&2
fi

TABLES="$(docker exec "$CONTAINER" psql -U "$DB_USER" -d "$DB" -tAc \
    "SELECT count(*) FROM information_schema.tables WHERE table_schema = 'public'")"
if [ "$TABLES" != "0" ]; then
    echo "refusing: target database $DB in $CONTAINER is not empty ($TABLES tables)" >&2
    exit 4
fi

docker exec -i "$CONTAINER" pg_restore -U "$DB_USER" -d "$DB" --no-owner --exit-on-error < "$DUMP"
echo "restore ok: $DUMP -> $CONTAINER/$DB"

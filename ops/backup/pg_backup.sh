#!/bin/sh
# PROD-BACKUP-01: logical backup of the AlSo PostgreSQL database (custom format) with a SHA-256 checksum and
# retention. Runs on the server next to docker compose; credentials stay inside the container (pg_dump runs there
# as the database owner over the local socket) - no password on the command line or in this script.
#
# Usage: ops/backup/pg_backup.sh [backup_dir]
# Env:   ALSO_PG_CONTAINER (default autoauction-postgres), ALSO_PG_DB (auto_auction), ALSO_PG_USER (auto_auction_user),
#        ALSO_BACKUP_RETENTION_DAYS (default 14)
set -eu

BACKUP_DIR="${1:-/var/backups/also}"
CONTAINER="${ALSO_PG_CONTAINER:-autoauction-postgres}"
DB="${ALSO_PG_DB:-auto_auction}"
DB_USER="${ALSO_PG_USER:-auto_auction_user}"
RETENTION_DAYS="${ALSO_BACKUP_RETENTION_DAYS:-14}"

mkdir -p "$BACKUP_DIR"
umask 077
STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
FILE="$BACKUP_DIR/also-$STAMP.dump"
TMP="$FILE.partial"

# the dump streams out of the container; a failed pg_dump leaves only the .partial file, never a "good" backup
docker exec "$CONTAINER" pg_dump -U "$DB_USER" -d "$DB" -Fc --no-owner > "$TMP"
docker exec -i "$CONTAINER" pg_restore --list > /dev/null < "$TMP"   # the archive is readable
mv "$TMP" "$FILE"
( cd "$BACKUP_DIR" && sha256sum "$(basename "$FILE")" > "$(basename "$FILE").sha256" )

# retention: only our own files, only older than RETENTION_DAYS, never the newest backup
find "$BACKUP_DIR" -maxdepth 1 -name 'also-*.dump' -mtime +"$RETENTION_DAYS" ! -name "$(basename "$FILE")" \
    -exec sh -c 'rm -f "$1" "$1.sha256"' _ {} \;
find "$BACKUP_DIR" -maxdepth 1 -name 'also-*.dump.partial' -mtime +1 -exec rm -f {} \;

echo "backup ok: $FILE ($(wc -c < "$FILE") bytes)"

#!/usr/bin/env bash
set -euo pipefail
cd -- "$(dirname -- "${BASH_SOURCE[0]}")"
backup_root="${1:?Usage: ./backup.sh /absolute/dedicated/backup/directory}"
[[ "$backup_root" = /* ]] || { echo 'Backup directory must be absolute'; exit 1; }
umask 077
mkdir -p -- "$backup_root"
stamp="$(date -u +%Y%m%dT%H%M%SZ)"
sql="$backup_root/yuwatang-$stamp.sql.gz"
docker compose exec -T mysql sh -c 'MYSQL_PWD="$MYSQL_PASSWORD" exec mysqldump --single-transaction --quick --no-tablespaces --set-gtid-purged=OFF -u "$MYSQL_USER" yuwatang_reservation' | gzip > "$sql"
gzip -t "$sql"
docker compose run --rm --no-deps --user root --entrypoint tar api -C /app/data -czf - uploads > "$backup_root/yuwatang-uploads-$stamp.tar.gz"
tar -tzf "$backup_root/yuwatang-uploads-$stamp.tar.gz" > /dev/null
sha256sum "$sql" "$backup_root/yuwatang-uploads-$stamp.tar.gz" > "$backup_root/yuwatang-$stamp.sha256"
echo "Created database and upload backups: $stamp"

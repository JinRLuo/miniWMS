#!/usr/bin/env bash
set -euo pipefail
umask 077

root=/opt/miniwms
directory="$root/backups/mysql"
mkdir -p "$directory"
chmod 700 "$directory"
exec 9>"$root/.backup.lock"
flock -n 9 || exit 0

stamp=$(TZ=Asia/Shanghai date +%Y%m%d-%H%M%S)
target="$directory/miniwms-$stamp.sql.gz"
outgoing=
cleanup() {
    rm -f -- "$target.part"
    if [[ -n "$outgoing" ]]; then rm -rf -- "$outgoing"; fi
}
trap cleanup EXIT
docker exec miniwms-mysql-1 sh -eu -c '
    export MYSQL_PWD="$MYSQL_ROOT_PASSWORD"
    exec mysqldump --protocol=socket --user=root --single-transaction --quick \
        --routines --events --triggers --set-gtid-purged=OFF --no-tablespaces \
        --hex-blob --databases miniwms
' | gzip -1 > "$target.part"
gzip -t "$target.part"
mv "$target.part" "$target"
sha256sum "$target" > "$target.sha256"

tar -czf "$directory/config-$stamp.tar.gz" -C "$root" \
    .env .app.env .mysql.env compose.runtime.yaml redis/redis.conf nginx/site.inc

if [[ -f "$root/mysql/backup_ed25519" ]]; then
    outgoing=$(mktemp -d "$root/backups/.outgoing.XXXXXXXX")
    ln "$target" "$outgoing/database.sql.gz"
    ln "$directory/config-$stamp.tar.gz" "$outgoing/runtime-config.tar.gz"
    tar -cf - -C "$outgoing" database.sql.gz runtime-config.tar.gz |
        timeout 90 ssh -T -o BatchMode=yes -o IdentitiesOnly=yes -o StrictHostKeyChecking=yes \
            -o ConnectTimeout=12 -o ServerAliveInterval=15 -o ServerAliveCountMax=2 \
            -o "UserKnownHostsFile=$root/mysql/backup_known_hosts" \
            -i "$root/mysql/backup_ed25519" miniwms-backup@43.156.245.251
fi

if [[ $(TZ=Asia/Shanghai date +%u) == 7 ]]; then
    cp -p "$target" "$directory/weekly-$stamp.sql.gz"
    cp -p "$directory/config-$stamp.tar.gz" "$directory/weekly-config-$stamp.tar.gz"
fi
find "$directory" -maxdepth 1 -type f -name 'miniwms-*' -mtime +7 -delete
find "$directory" -maxdepth 1 -type f -name 'config-*' -mtime +7 -delete
find "$directory" -maxdepth 1 -type f -name 'weekly-*' -mtime +28 -delete
printf 'Mini WMS backup completed: %s\n' "$target"

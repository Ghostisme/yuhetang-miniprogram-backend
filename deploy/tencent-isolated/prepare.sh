#!/usr/bin/env bash
set -euo pipefail
[[ $EUID -eq 0 ]] || { printf 'Run as root.\n' >&2; exit 1; }
bundle=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
[[ -f "$bundle/SHA256SUMS" && -f "$bundle/reservation.jar" ]] || {
    printf 'Use the complete isolated release bundle.\n' >&2; exit 1;
}
(cd -- "$bundle" && sha256sum --check SHA256SUMS)
bash "$bundle/preflight.sh"
for target in /srv/yuwatang-reservation /var/lib/yuwatang-reservation /etc/yuwatang-reservation.env /etc/systemd/system/yuwatang-reservation.service /usr/local/libexec/yuwatang-dbctl; do
    [[ ! -e $target && ! -L $target ]] || {
        printf 'STOP: existing reservation path found: %s. First-install script will not overwrite it.\n' "$target" >&2; exit 1;
    }
done
# All collision checks and the image download run before installing application files.
bash "$bundle/database.sh" first-install-check
bash "$bundle/database.sh" pull-image "$bundle/mysql-image.txt"
bash "$bundle/prepare-app.sh"
install -o root -g root -m 0755 "$bundle/check-db-target.sh" /srv/yuwatang-reservation/check-db-target.sh
install -d -o root -g root -m 0755 /usr/local/libexec
install -o root -g root -m 0755 "$bundle/database.sh" /usr/local/libexec/yuwatang-dbctl
/usr/local/libexec/yuwatang-dbctl init "$bundle/mysql-image.txt"
systemctl daemon-reload
printf '\nIsolated installation prepared. No application/database service has been started or enabled.\n'
printf 'DB_PASSWORD and JWT_SECRET were generated in /etc/yuwatang-reservation.env (root only).\n'
printf 'Set ADMIN_PASSWORD and WeChat settings there, then follow the isolated README.\n'

#!/usr/bin/env bash
set -euo pipefail
[[ $EUID -eq 0 ]] || { printf 'Run as root.\n' >&2; exit 1; }
bundle=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
[[ -f "$bundle/SHA256SUMS" && -f "$bundle/reservation.jar" ]] || {
    printf 'Use the complete 1Panel Java API release bundle.\n' >&2; exit 1;
}
command -v docker >/dev/null || { printf 'Install Docker through the 1Panel installer first.\n' >&2; exit 1; }
if command -v podman >/dev/null 2>&1; then
    printf 'STOP: this fresh 1Panel procedure expects Docker only. Do not run the older Podman installation procedure.\n' >&2
    exit 1
fi
(cd -- "$bundle" && sha256sum --check SHA256SUMS)
bash "$bundle/preflight.sh"
for target in /srv/yuwatang-reservation /var/lib/yuwatang-reservation /etc/yuwatang-reservation.env /etc/systemd/system/yuwatang-reservation.service /usr/local/libexec/yuwatang-dbctl; do
    [[ ! -e $target && ! -L $target ]] || {
        printf 'STOP: existing project path found: %s. This first-install script will not overwrite it.\n' "$target" >&2; exit 1;
    }
done
bash "$bundle/database.sh" first-install-check
bash "$bundle/database.sh" pull-image "$bundle/mysql-image.txt"
bash "$bundle/prepare-app.sh"
install -o root -g root -m 0755 "$bundle/check-db-target.sh" /srv/yuwatang-reservation/check-db-target.sh
install -d -o root -g root -m 0755 /usr/local/libexec
install -o root -g root -m 0755 "$bundle/database.sh" /usr/local/libexec/yuwatang-dbctl
/usr/local/libexec/yuwatang-dbctl init "$bundle/mysql-image.txt"
systemctl daemon-reload
printf '\nJava API and an empty dedicated MySQL container are prepared; neither service has been started or enabled.\n'
printf 'Set ADMIN_PASSWORD in /etc/yuwatang-reservation.env, then follow README.md.\n'
printf 'The admin frontend is a separate ZIP to deploy using the 1Panel static website.\n'
printf 'No web-server configuration, TLS certificate or frontend website has been installed by this script.\n'

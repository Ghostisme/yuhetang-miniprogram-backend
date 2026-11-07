#!/usr/bin/env bash
set -euo pipefail
[[ $EUID -eq 0 ]] || { printf 'Run this script as root.\n' >&2; exit 1; }
bundle=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
[[ $(uname -m) == x86_64 ]] || { printf 'This JRE package requires x86_64.\n' >&2; exit 1; }
[[ -f "$bundle/reservation.jar" && -f "$bundle/SHA256SUMS" ]] || { printf 'Use the complete release bundle, not the source directory.\n' >&2; exit 1; }
for destination in /srv/yuwatang-reservation/reservation.jar /etc/systemd/system/yuwatang-reservation.service; do
    [[ ! -e "$destination" ]] || { printf 'Existing reservation installation found: %s. Use the documented upgrade process.\n' "$destination" >&2; exit 1; }
done
(cd -- "$bundle" && sha256sum --check SHA256SUMS)
bash "$bundle/preflight.sh"

# Install a private runtime, preserving /usr/bin/java and alternatives.
if [[ ! -e /opt/yuhetang-java17 ]]; then
    staging=$(mktemp -d /opt/yuhetang-jre.XXXXXXXX)
    trap '[[ -n ${staging:-} && "$staging" == /opt/yuhetang-jre.* ]] && rm -rf -- "$staging"' EXIT
    if [[ -f "$bundle/java17-linux-x64.tar.gz" ]]; then
        cp -- "$bundle/java17-linux-x64.tar.gz" "$staging/jre.tar.gz"
    else
        curl --fail --location --retry 2 --connect-timeout 15 --max-time 300 \
            'https://github.com/adoptium/temurin17-binaries/releases/download/jdk-17.0.21%2B9/OpenJDK17U-jre_x64_linux_hotspot_17.0.21_9.tar.gz' \
            --output "$staging/jre.tar.gz"
    fi
    printf '%s  %s\n' '9f41bf88c7900c7097444a1bdeb84146ae692bc08842490ab098063c74bf9a8a' "$staging/jre.tar.gz" | sha256sum --check -
    mkdir "$staging/runtime"
    tar -xzf "$staging/jre.tar.gz" --strip-components=1 -C "$staging/runtime"
    mv -- "$staging/runtime" /opt/yuhetang-java17
fi
private_version=$(/opt/yuhetang-java17/bin/java -version 2>&1)
printf '%s\n' "$private_version"
[[ "$private_version" == *'version "17.'* ]] || { printf 'Private runtime is not Java 17.\n' >&2; exit 1; }

if ! getent passwd yuwatang >/dev/null; then
    useradd --system --home-dir /srv/yuwatang-reservation --shell /sbin/nologin yuwatang
fi
install -d -m 0755 /srv/yuwatang-reservation
install -d -o yuwatang -g yuwatang -m 0750 /var/lib/yuwatang-reservation
install -o root -g root -m 0644 "$bundle/reservation.jar" /srv/yuwatang-reservation/reservation.jar
install -o root -g root -m 0644 "$bundle/yuwatang-reservation.service" /etc/systemd/system/yuwatang-reservation.service
if [[ ! -e /etc/yuwatang-reservation.env ]]; then
    install -o root -g root -m 0600 "$bundle/env.example" /etc/yuwatang-reservation.env
fi
systemctl daemon-reload
printf '\nPrepared only; service has not been started or enabled, and Nginx has not been changed.\n'
printf 'Complete database preparation and /etc/yuwatang-reservation.env using the README for this deployment variant.\n'

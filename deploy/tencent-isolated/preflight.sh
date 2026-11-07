#!/usr/bin/env bash
set -euo pipefail

# Read-only: never start a daemon, install packages, or contact the host database.
[[ $(uname -m) == x86_64 ]] || { printf 'STOP: x86_64 is required.\n' >&2; exit 2; }
for tool in ss df awk free openssl sha256sum gzip tar systemctl flock diff; do
    command -v "$tool" >/dev/null || { printf 'STOP: required command is missing: %s\n' "$tool" >&2; exit 2; }
done
printf 'Available resources (no changes made):\n'
free -m
df -h /
available_mb=$(awk '/MemAvailable:/ {print int($2/1024)}' /proc/meminfo)
free_disk_mb=$(df -Pm / | awk 'NR==2 {print $4}')
[[ $available_mb =~ ^[0-9]+$ && $free_disk_mb =~ ^[0-9]+$ ]] || {
    printf 'STOP: could not determine available resources.\n' >&2; exit 2;
}
(( available_mb >= 3072 )) || {
    printf 'STOP: at least 3 GiB available RAM is required; current %s MiB.\n' "$available_mb" >&2; exit 2;
}
(( free_disk_mb >= 15360 )) || {
    printf 'STOP: at least 15 GiB free on / is required; current %s MiB. Expand storage first.\n' "$free_disk_mb" >&2; exit 2;
}
listeners=$(ss -H -lnt '( sport = :3307 or sport = :8790 or sport = :8791 )')
[[ -z $listeners ]] || { printf 'STOP: port 3307, 8790 or 8791 is occupied.\n' >&2; exit 2; }
if command -v podman >/dev/null 2>&1; then
    engine=$(command -v podman)
elif command -v docker >/dev/null 2>&1; then
    engine=$(command -v docker)
else
    printf 'STOP: no container engine found. Install/configure it separately; this installer does not change an existing engine.\n' >&2
    exit 2
fi
"$engine" info >/dev/null 2>&1 || {
    printf 'STOP: container engine is unavailable; it has not been started or restarted.\n' >&2; exit 2;
}
printf 'Preflight passed. Engine: %s. Host MySQL was not accessed.\n' "$engine"

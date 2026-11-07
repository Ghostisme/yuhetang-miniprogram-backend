#!/usr/bin/env bash
set -euo pipefail

# Read-only inspection. Never display credentials or existing vhost bodies.
printf 'OS: '; . /etc/os-release; printf '%s\n' "$PRETTY_NAME"
printf 'Architecture: '; uname -m
printf 'CPU cores: '; getconf _NPROCESSORS_ONLN
free -m
df -h /
printf '\nReservation ports (must be unused before first install):\n'
ss -lnt '( sport = :8790 or sport = :8791 )'
if command -v mysqld >/dev/null 2>&1; then
    mysqld --version
elif [[ -x /www/server/mysql/bin/mysqld ]]; then
    /www/server/mysql/bin/mysqld --version
elif command -v mysql >/dev/null 2>&1; then
    printf 'MySQL client only (not proof of the server version):\n'
    mysql --version
else
    printf 'MySQL executable not found; verify the database endpoint before startup.\n'
fi
printf '\nCurrent system Java (will not be changed):\n'
if command -v java >/dev/null 2>&1; then
    java -version 2>&1
else
    printf 'Not installed; the bundle supplies its own Java 17 runtime.\n'
fi
printf '\nNginx (install separately if absent):\n'
if command -v nginx >/dev/null 2>&1; then
    nginx -v 2>&1
else
    printf 'Not installed; backend preparation can continue without Nginx.\n'
fi

available_mb=$(awk '/MemAvailable:/ {print int($2/1024)}' /proc/meminfo)
free_disk_mb=$(df -Pm / | awk 'NR==2 {print $4}')
if (( free_disk_mb < 5120 )); then
    printf '\nSTOP: root filesystem needs at least 5 GiB free before deployment (current %s MiB).\n' "$free_disk_mb" >&2
    printf 'Expand storage or inspect usage first; do not delete existing business data.\n' >&2
    exit 2
fi
if (( available_mb < 1536 )); then
    printf '\nSTOP: less than 1.5 GiB available RAM; review shared-host capacity first.\n' >&2
    exit 2
fi
if ss -H -lnt '( sport = :8790 or sport = :8791 )' | read -r _; then
    printf '\nSTOP: port 8790 or 8791 is already in use.\n' >&2
    exit 2
fi
printf '\nRead-only preflight passed. Database account, certificate and ICP status still need verification.\n'

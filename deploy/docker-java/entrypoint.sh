#!/usr/bin/env bash
set -euo pipefail
umask 0027

/bin/bash /app/check-db-target.sh
[[ ${BIND_ADDRESS:-} == 127.0.0.1 && ${PORT:-} == 8790 &&
   ${MANAGEMENT_BIND_ADDRESS:-} == 127.0.0.1 && ${MANAGEMENT_PORT:-} == 8791 &&
   ${APP_DATA_DIR:-} == /var/lib/yuwatang-reservation ]] || {
    printf 'STOP: retain the existing loopback ports and persistent data directory.\n' >&2
    exit 1
}
[[ -d "$APP_DATA_DIR" && -w "$APP_DATA_DIR" ]] || {
    printf 'STOP: the existing application data directory must be mounted and writable by the application user.\n' >&2
    exit 1
}
[[ $# == 0 ]] || {
    printf 'STOP: configure this deployment through its environment file, without JVM or Spring command-line overrides.\n' >&2
    exit 1
}

exec java -Dfile.encoding=UTF-8 -Duser.timezone=Asia/Shanghai \
    -Xms128m -Xmx512m -XX:MaxMetaspaceSize=192m -XX:MaxDirectMemorySize=64m \
    -XX:+ExitOnOutOfMemoryError -jar /app/reservation.jar

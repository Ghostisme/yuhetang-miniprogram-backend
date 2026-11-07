#!/usr/bin/env bash
set -euo pipefail
umask 077

# These paths and container names belong exclusively to this deployment.
db_container=yuwatang-mysql-isolated
db_data=/var/lib/yuwatang-mysql-isolated
db_config=/etc/yuwatang-reservation-db
app_env=/etc/yuwatang-reservation.env
app_files=/var/lib/yuwatang-reservation
backup_root=/var/backups/yuwatang-reservation
restore_container=yuwatang-mysql-restorecheck
restore_data=/var/lib/yuwatang-mysql-restorecheck
component_label=com.yuwatang.component
pinned_image='docker.io/library/mysql@sha256:a81d043930e51745522bd5c430961cf8286f0a23f0a9a2a6e0108f7ea71b0902'

fail() { printf 'STOP: %s\n' "$*" >&2; exit 1; }

choose_engine() {
    if command -v podman >/dev/null 2>&1; then
        engine=$(command -v podman)
    elif command -v docker >/dev/null 2>&1; then
        engine=$(command -v docker)
    else
        fail 'No container engine found. Nothing was installed or restarted.'
    fi
    "$engine" info >/dev/null 2>&1 || fail 'Container engine is unavailable.'
}

load_state() {
    [[ -f $db_config/runtime.conf && ! -L $db_config/runtime.conf ]] || fail 'Dedicated runtime configuration is missing.'
    engine=$(sed -n 's/^engine=//p' "$db_config/runtime.conf")
    image=$(sed -n 's/^image=//p' "$db_config/runtime.conf")
    [[ $engine == /* && -x $engine && $image == "$pinned_image" ]] || fail 'Unexpected dedicated runtime configuration.'
    "$engine" info >/dev/null 2>&1 || fail 'Container engine is unavailable.'
}

assert_owned() {
    local name=$1 role=$2 actual
    actual=$("$engine" inspect --format '{{ index .Config.Labels "com.yuwatang.component" }}' "$name") || fail 'Named container is missing.'
    [[ $actual == "$role" ]] || fail 'Container name exists without the expected ownership label; no operation performed.'
}

first_install_check() {
    choose_engine
    local path name
    for path in "$db_data" "$db_config" "$restore_data" /etc/systemd/system/yuwatang-mysql-isolated.service; do
        [[ ! -e $path && ! -L $path ]] || fail "Dedicated path already exists; it will not be adopted or overwritten: $path"
    done
    for name in "$db_container" "$restore_container"; do
        if "$engine" container inspect "$name" >/dev/null 2>&1; then
            fail "Container name already exists: $name"
        fi
    done
    [[ -z $(ss -H -lnt '( sport = :3307 )') ]] || fail 'Port 3307 is occupied.'
}

read_image() {
    [[ -f ${1:-} ]] || fail 'Pinned image file is missing.'
    image=$(tr -d '\r\n' < "$1")
    [[ $image == "$pinned_image" ]] || fail 'Image does not match the reviewed MySQL 8.4 digest.'
}

write_secrets() {
    local directory=$1
    [[ ! -e $directory && ! -L $directory ]] || fail 'Refusing to overwrite a secret directory.'
    install -d -o root -g root -m 0700 "$directory"
    openssl rand -hex 32 > "$directory/root-password"
    openssl rand -hex 32 > "$directory/app-password"
    {
        printf '[client]\nuser=root\npassword='
        tr -d '\n' < "$directory/root-password"
        printf '\n'
    } > "$directory/client.cnf"
    chmod 0600 "$directory/"*
}

create_container() {
    local name=$1 role=$2 data_dir=$3 secret_dir=$4 port=$5
    local -a logging
    if [[ ${engine##*/} == podman ]]; then
        logging=(--log-driver=k8s-file --log-opt=max-size=10mb)
    else
        logging=(--log-driver=json-file --log-opt=max-size=10m --log-opt=max-file=3)
    fi
    # Never mount a host MySQL path or publish a database port on 0.0.0.0.
    "$engine" create --name "$name" --label "$component_label=$role" \
        --publish "127.0.0.1:$port:3306" \
        --memory=1g --memory-swap=1g --cpus=1 --pids-limit=256 --restart=no \
        "${logging[@]}" \
        --volume "$data_dir:/var/lib/mysql:Z" \
        --volume "$secret_dir:/run/secrets:ro,Z" \
        --env MYSQL_DATABASE=yuwatang_reservation --env MYSQL_USER=yuwatang_app --env MYSQL_ROOT_HOST=localhost \
        --env MYSQL_PASSWORD_FILE=/run/secrets/app-password \
        --env MYSQL_ROOT_PASSWORD_FILE=/run/secrets/root-password --env TZ=Asia/Shanghai \
        "$image" --character-set-server=utf8mb4 --collation-server=utf8mb4_unicode_ci \
        --max-connections=40 --innodb-buffer-pool-size=256M \
        --innodb-redo-log-capacity=134217728 --skip-log-bin --mysqlx=OFF \
        --max-allowed-packet=16M --tmp-table-size=16M --max-heap-table-size=16M \
        --table-open-cache=400 >/dev/null
}

init_database() {
    first_install_check
    read_image "$1"
    [[ -f $app_env && ! -L $app_env ]] || fail 'Prepare the new application environment first.'
    grep -qx 'DB_PASSWORD=' "$app_env" || fail 'DB_PASSWORD already exists; refusing to change it.'
    grep -qx 'JWT_SECRET=' "$app_env" || fail 'JWT_SECRET already exists; refusing to change it.'
    install -d -o root -g root -m 0700 "$db_data" "$db_config"
    write_secrets "$db_config/secrets"
    printf 'engine=%s\nimage=%s\n' "$engine" "$image" > "$db_config/runtime.conf"
    chmod 0600 "$db_config/runtime.conf"
    create_container "$db_container" mysql-isolated "$db_data" "$db_config/secrets" 3307
    # Keep generated credentials out of external command arguments and output.
    local password jwt temporary line
    password=$(cat "$db_config/secrets/app-password")
    jwt=$(openssl rand -hex 32)
    temporary=$(mktemp "${app_env}.XXXXXXXX")
    while IFS= read -r line || [[ -n $line ]]; do
        case $line in
            DB_PASSWORD=) printf 'DB_PASSWORD=%s\n' "$password" ;;
            JWT_SECRET=) printf 'JWT_SECRET=%s\n' "$jwt" ;;
            *) printf '%s\n' "$line" ;;
        esac
    done < "$app_env" > "$temporary"
    mv -- "$temporary" "$app_env"
    chmod 0600 "$app_env"
    unset password jwt
    {
        printf '[Unit]\nDescription=Yuhetang dedicated MySQL container\nAfter=network-online.target\nWants=network-online.target\n'
        if [[ ${engine##*/} == docker ]]; then
            printf 'After=docker.service\nRequires=docker.service\n'
        fi
        cat <<'UNIT'

[Service]
Type=simple
ExecStart=/usr/local/libexec/yuwatang-dbctl foreground
ExecStartPost=/usr/local/libexec/yuwatang-dbctl ready
ExecStop=/usr/local/libexec/yuwatang-dbctl stop
Restart=on-failure
RestartSec=10
TimeoutStartSec=180
TimeoutStopSec=75
UMask=0077

[Install]
WantedBy=multi-user.target
UNIT
    } > /etc/systemd/system/yuwatang-mysql-isolated.service
    chmod 0644 /etc/systemd/system/yuwatang-mysql-isolated.service
    printf 'Created a stopped dedicated MySQL container on 127.0.0.1:3307. No host MySQL connection was made.\n'
}

ready() {
    local name=$1 role=$2 attempt
    assert_owned "$name" "$role"
    for ((attempt=0; attempt<50; attempt++)); do
        if "$engine" exec "$name" mysql --defaults-extra-file=/run/secrets/client.cnf \
            --batch --skip-column-names -e 'SELECT 1;' >/dev/null 2>&1; then
            printf 'Dedicated MySQL is ready.\n'
            return 0
        fi
        sleep 3
    done
    fail 'Dedicated MySQL did not become ready; inspect its service log. Existing MySQL was not accessed.'
}

table_counts() {
    local name=$1 destination=$2 table tables
    tables=$("$engine" exec "$name" mysql --defaults-extra-file=/run/secrets/client.cnf \
        --batch --skip-column-names -e "SELECT TABLE_NAME FROM information_schema.TABLES WHERE TABLE_SCHEMA='yuwatang_reservation' AND TABLE_TYPE='BASE TABLE' ORDER BY TABLE_NAME;")
    [[ -n $tables ]] || fail 'No business tables found; refusing to report an empty backup as verified.'
    : > "$destination"
    while IFS= read -r table; do
        [[ $table =~ ^[A-Za-z0-9_]+$ ]] || fail 'Unexpected table identifier.'
        printf '%s\t' "$table" >> "$destination"
        "$engine" exec "$name" mysql --defaults-extra-file=/run/secrets/client.cnf \
            --batch --skip-column-names yuwatang_reservation -e "SELECT COUNT(*) FROM \`$table\`;" >> "$destination"
    done <<< "$tables"
}

backup_database() (
    assert_owned "$db_container" mysql-isolated
    exec 9> "$db_config/operation.lock"
    flock -n 9 || fail 'Another dedicated backup or restore check is in progress.'
    [[ -d $app_files && ! -L $app_files ]] || fail 'Application file directory is missing or is a link.'
    [[ ! -L $backup_root ]] || fail 'Backup directory must not be a symbolic link.'
    install -d -o root -g root -m 0700 "$backup_root"
    local destination restart_app=0
    destination=$(mktemp -d "$backup_root/$(date -u +%Y%m%dT%H%M%SZ)-XXXXXXXX")
    # Stop only the reservation application for SQL/file consistency, then restore its previous state.
    finish_backup() {
        local result=$?
        trap - EXIT
        if (( restart_app )); then
            if ! systemctl start yuwatang-reservation.service; then
                printf 'Reservation application could not be restarted; check its log.\n' >&2
                result=1
            fi
        fi
        if (( result != 0 )); then
            printf 'Backup incomplete; retained for inspection: %s\n' "$destination" >&2
        fi
        exit "$result"
    }
    trap finish_backup EXIT
    if systemctl is-active --quiet yuwatang-reservation.service; then
        restart_app=1
        systemctl stop yuwatang-reservation.service
    fi
    ready "$db_container" mysql-isolated
    "$engine" exec "$db_container" mysqldump --defaults-extra-file=/run/secrets/client.cnf \
        --single-transaction --quick --no-tablespaces --set-gtid-purged=OFF \
        --databases yuwatang_reservation | gzip > "$destination/database.sql.gz"
    gzip -t "$destination/database.sql.gz"
    table_counts "$db_container" "$destination/table-counts.tsv"
    tar -czf "$destination/files.tar.gz" -C "$app_files" .
    tar -czf "$destination/config-private.tar.gz" -C / \
        "${app_env#/}" "${db_config#/}"
    printf 'image=%s\ncreated_utc=%s\n' "$image" "$(date -u +%FT%TZ)" > "$destination/metadata.txt"
    (cd -- "$destination" && sha256sum --text database.sql.gz files.tar.gz config-private.tar.gz table-counts.tsv metadata.txt > SHA256SUMS)
    printf 'Backup completed: %s\nContains credentials; keep an encrypted copy outside this server.\n' "$destination"
)

restore_check() (
    local source=${1:-} available_mb free_disk_mb
    exec 9> "$db_config/operation.lock"
    flock -n 9 || fail 'Another dedicated backup or restore check is in progress.'
    [[ -d $source && ! -L $source ]] || fail 'Specify an existing backup directory.'
    source=$(cd -- "$source" && pwd -P)
    [[ -f $source/SHA256SUMS ]] || fail 'Backup checksum manifest is missing.'
    # Hash only fixed payload names; do not let a manifest request unrelated files.
    local filename
    for filename in database.sql.gz files.tar.gz config-private.tar.gz table-counts.tsv metadata.txt; do
        [[ -f $source/$filename && ! -L $source/$filename ]] || fail 'Backup payload is missing or is a link.'
    done
    (cd -- "$source" && sha256sum --text database.sql.gz files.tar.gz config-private.tar.gz table-counts.tsv metadata.txt | diff - SHA256SUMS) || fail 'Backup checksums differ.'
    grep -Fxq "image=$image" "$source/metadata.txt" || fail 'Backup MySQL image differs from the installed image.'
    gzip -t "$source/database.sql.gz"
    tar -tzf "$source/files.tar.gz" >/dev/null
    tar -tzf "$source/config-private.tar.gz" >/dev/null
    [[ ! -e $restore_data && ! -L $restore_data && ! -e $db_config/restorecheck-secrets && ! -L $db_config/restorecheck-secrets ]] || fail 'Restore-check paths already exist; they will not be overwritten.'
    if "$engine" container inspect "$restore_container" >/dev/null 2>&1; then
        fail 'Restore-check container already exists; it will not be replaced.'
    fi
    [[ -z $(ss -H -lnt '( sport = :3308 )') ]] || fail 'Restore-check port 3308 is occupied.'
    available_mb=$(awk '/MemAvailable:/ {print int($2/1024)}' /proc/meminfo)
    free_disk_mb=$(df -Pm / | awk 'NR==2 {print $4}')
    (( available_mb >= 2048 && free_disk_mb >= 10240 )) || fail 'Restore check needs 2 GiB available RAM and 10 GiB free disk.'
    install -d -o root -g root -m 0700 "$restore_data"
    write_secrets "$db_config/restorecheck-secrets"
    create_container "$restore_container" mysql-restorecheck "$restore_data" "$db_config/restorecheck-secrets" 3308
    finish_restore() {
        local result=$?
        trap - EXIT
        if ! "$engine" stop --time 60 "$restore_container" >/dev/null; then
            printf 'Could not stop restore-check container; check it explicitly.\n' >&2
            result=1
        fi
        exit "$result"
    }
    trap finish_restore EXIT
    "$engine" start "$restore_container" >/dev/null
    ready "$restore_container" mysql-restorecheck
    gzip -dc "$source/database.sql.gz" | "$engine" exec -i "$restore_container" \
        mysql --defaults-extra-file=/run/secrets/client.cnf
    table_counts "$restore_container" "$restore_data/table-counts.tsv"
    diff -u "$source/table-counts.tsv" "$restore_data/table-counts.tsv" || fail 'Restored table counts differ from the backup.'
    printf 'Restore check passed: SQL imported into a NEW container on 3308; all base-table row counts match.\n'
    printf 'The restore-check container is stopped on exit and retained. Live databases and configuration were not replaced.\n'
)

main() {
    [[ $EUID -eq 0 ]] || fail 'Run this command as root.'
    case ${1:-} in
        first-install-check) first_install_check ;;
        pull-image) choose_engine; read_image "${2:-}"; "$engine" pull "$image" ;;
        init) init_database "${2:-}" ;;
        foreground) load_state; assert_owned "$db_container" mysql-isolated; exec "$engine" start --attach "$db_container" ;;
        stop) load_state; assert_owned "$db_container" mysql-isolated; "$engine" stop --time 60 "$db_container" ;;
        ready) load_state; ready "$db_container" mysql-isolated ;;
        status) load_state; assert_owned "$db_container" mysql-isolated; "$engine" inspect --format 'name={{.Name}} status={{.State.Status}} memory={{.HostConfig.Memory}}' "$db_container" ;;
        backup) load_state; backup_database ;;
        restore-check) load_state; restore_check "${2:-}" ;;
        *) printf 'Usage: yuwatang-dbctl {status|ready|backup|restore-check BACKUP_DIR}\n' >&2; exit 2 ;;
    esac
}

if [[ ${BASH_SOURCE[0]} == "$0" ]]; then main "$@"; fi

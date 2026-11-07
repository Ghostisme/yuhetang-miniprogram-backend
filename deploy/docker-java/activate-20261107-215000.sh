#!/usr/bin/env bash
# Activate this exact, previously released Java image. No database or gateway changes.
set -euo pipefail

YHT_RELEASE=20261107-215000
YHT_IMAGE=yuhetang-java-api:$YHT_RELEASE
YHT_NAME=yuhetang-java-api
YHT_JAR_SHA=d92f7540bf2859fd51a9cdabdfb1cb68760d7b415f9bd0aa6600638b6cc1de1d
YHT_MANIFEST_SHA=c46328429ab51995ddf177ed9a9f6cba5f957d11c0f2179b43e8eec0a61192bb
YHT_ARCHIVE_SHA=801f373b4bf8db9347f1755beabebff3cbfdc664fed2fefa3f3e078512658720
YHT_UPLOAD=/root/yuhetang-upload
YHT_ARCHIVE=/home/ubuntu/yuhetang-java-docker-$YHT_RELEASE.tar.gz
YHT_CONTEXT=$YHT_UPLOAD/yuhetang-java-docker-$YHT_RELEASE
YHT_ENVIRONMENT=/etc/yuwatang-reservation.docker.env
YHT_DATA=/var/lib/yuwatang-reservation
YHT_LOCK=/run/lock/yuhetang-activate-215000.lock
YHT_WAIT=120
YHT_STOPPED=0
YHT_RENAMED=0
YHT_COMPLETE=0
YHT_BACKUP=

yht_fail() { printf 'STOP: %s\n' "$*" >&2; exit 1; }
yht_exists() { docker container inspect "$YHT_NAME" >/dev/null 2>&1; }
yht_current_image() { docker inspect --format '{{.Config.Image}}' "$YHT_NAME"; }
yht_current_hash() { docker exec "$YHT_NAME" sha256sum /app/reservation.jar | awk '{print $1}'; }

yht_ready() {
    local deadline=$((SECONDS + YHT_WAIT)) body health
    while (( SECONDS < deadline )); do
        [[ $(docker inspect --format '{{.State.Running}}' "$YHT_NAME") == true ]] || return 1
        health=$(docker inspect --format '{{.State.Health.Status}}' "$YHT_NAME")
        if [[ $health == healthy ]] && body=$(curl --noproxy '*' --fail --silent \
            --connect-timeout 2 --max-time 3 http://127.0.0.1:8790/api/health); then
            if printf '%s' "$body" | python3 -c \
                'import json,sys; d=json.load(sys.stdin); sys.exit(0 if d.get("status")=="ok" and d.get("database")=="connected" and d.get("mode")=="production" else 1)' \
                >/dev/null 2>&1; then
                return 0
            fi
        fi
        sleep 2
    done
    return 1
}

yht_report() {
    docker inspect --format 'Image={{.Config.Image}} Health={{.State.Health.Status}}' "$YHT_NAME"
    docker exec "$YHT_NAME" sha256sum /app/reservation.jar
    curl --noproxy '*' --fail --silent --show-error --connect-timeout 3 --max-time 5 \
        http://127.0.0.1:8790/api/health
    printf '\n'
}

yht_restore() {
    local failed=${YHT_BACKUP}-failed
    printf '\nActivation failed; restoring the previous Java container.\n' >&2
    if (( YHT_RENAMED )); then
        if yht_exists; then
            [[ $(yht_current_image) == "$YHT_IMAGE" ]] || {
                printf 'STOP: the Java container changed during this operation; inspect it before recovery.\n' >&2
                return 1
            }
            docker stop --time 50 "$YHT_NAME" || return 1
            docker rename "$YHT_NAME" "$failed" || return 1
            printf 'Failed container retained: %s\n' "$failed" >&2
        fi
        docker rename "$YHT_BACKUP" "$YHT_NAME" || return 1
    fi
    docker start "$YHT_NAME" || return 1
    if yht_ready; then
        printf 'Previous Java restored and healthy.\n' >&2
        yht_report
    else
        printf 'Previous Java was restarted; its health still needs inspection.\n' >&2
        return 1
    fi
}

yht_exit() {
    local result=$?
    trap - EXIT INT TERM HUP
    if (( result != 0 && YHT_STOPPED && ! YHT_COMPLETE )); then
        set +e
        if ! yht_restore; then
            printf 'Recovery incomplete. Retained backup name: %s\n' "$YHT_BACKUP" >&2
        fi
    fi
    exit "$result"
}

yht_main() {
    [[ $# == 0 ]] || yht_fail 'Run this script without arguments.'
    [[ $(id -u) == 0 ]] || yht_fail 'Run: sudo bash /home/ubuntu/yuhetang-activate-215000.sh'
    umask 077
    local tool current uid gid manifest_hash image_hash
    for tool in docker curl python3 sha256sum tar flock ss stat awk install; do
        command -v "$tool" >/dev/null || yht_fail "Required command is missing: $tool"
    done
    exec 9>"$YHT_LOCK"
    flock -n 9 || yht_fail 'Another activation is already running.'
    trap yht_exit EXIT
    trap 'exit 130' INT
    trap 'exit 143' TERM HUP
    [[ $(docker info --format '{{.OSType}}') == linux ]] || yht_fail 'Docker must be running on Linux.'
    yht_exists || yht_fail 'The existing Java container is missing; no other container will be selected automatically.'
    current=$(yht_current_image)
    printf '[1/5] Current image: %s\n' "$current"
    if [[ $current == "$YHT_IMAGE" ]]; then
        [[ $(yht_current_hash) == "$YHT_JAR_SHA" ]] || yht_fail 'The running JAR does not match this release.'
        yht_ready || yht_fail 'This release is already running but its health check failed; it was left unchanged.'
        yht_report
        printf 'Already active. No restart was needed.\n'
        return 0
    fi
    [[ $current == yuhetang-java-api:* ]] || yht_fail 'The current image is not a Yuhetang Java release.'
    [[ $(docker inspect --format '{{.HostConfig.NetworkMode}}' "$YHT_NAME") == host ]] || \
        yht_fail 'The existing Java container must use host networking for this update.'
    [[ $(docker inspect --format '{{index .Config.Labels "com.yuwatang.component"}}' "$YHT_NAME") == java-api ]] || \
        yht_fail 'The existing container does not have the expected Java API label.'
    [[ $(docker inspect --format '{{.State.Running}}' "$YHT_NAME") == true ]] || \
        yht_fail 'The previous Java is not running; inspect it before updating.'
    [[ -f $YHT_ENVIRONMENT && ! -L $YHT_ENVIRONMENT && $(stat -c '%u:%a' "$YHT_ENVIRONMENT") == 0:600 ]] || \
        yht_fail 'Keep the existing root-owned 0600 Docker environment file.'
    uid=$(id -u yuwatang)
    gid=$(id -g yuwatang)
    [[ $uid != 0 && -d $YHT_DATA && ! -L $YHT_DATA && $(stat -c '%u:%g' "$YHT_DATA") == "$uid:$gid" ]] || \
        yht_fail 'Keep the existing yuwatang application data directory and its ownership.'

    printf '[2/5] Verifying the release files.\n'
    if [[ ! -e $YHT_CONTEXT ]]; then
        [[ -f $YHT_ARCHIVE && ! -L $YHT_ARCHIVE ]] || \
            yht_fail "Prepared release is missing. Upload yuhetang-java-docker-$YHT_RELEASE.tar.gz to /home/ubuntu, then run this script again."
        image_hash=$(sha256sum "$YHT_ARCHIVE" | awk '{print $1}')
        [[ $image_hash == "$YHT_ARCHIVE_SHA" ]] || yht_fail 'The uploaded archive SHA256 does not match release 215000.'
        install -d -o root -g root -m 0700 "$YHT_UPLOAD"
        tar -xzf "$YHT_ARCHIVE" -C "$YHT_UPLOAD"
    fi
    [[ -d $YHT_CONTEXT && ! -L $YHT_CONTEXT && -f $YHT_CONTEXT/SHA256SUMS && ! -L $YHT_CONTEXT/SHA256SUMS ]] || \
        yht_fail 'The prepared release directory is invalid.'
    manifest_hash=$(sha256sum "$YHT_CONTEXT/SHA256SUMS" | awk '{print $1}')
    [[ $manifest_hash == "$YHT_MANIFEST_SHA" ]] || yht_fail 'The prepared SHA256SUMS does not match release 215000.'
    (cd "$YHT_CONTEXT" && sha256sum --check SHA256SUMS)

    printf '[3/5] Building and checking the new image before stopping the old Java.\n'
    docker build -t "$YHT_IMAGE" "$YHT_CONTEXT"
    image_hash=$(docker run --rm --network none --entrypoint sha256sum "$YHT_IMAGE" /app/reservation.jar | awk '{print $1}')
    [[ $image_hash == "$YHT_JAR_SHA" ]] || yht_fail 'The new image contains an unexpected JAR; the current Java was left running.'
    docker run --rm --network none --env-file "$YHT_ENVIRONMENT" \
        --entrypoint /bin/bash "$YHT_IMAGE" /app/check-db-target.sh
    YHT_BACKUP=yuhetang-java-api-before-215000-$(date +%Y%m%d-%H%M%S)-$$
    printf '[4/5] Switching Java. Backup: %s\n' "$YHT_BACKUP"
    printf 'The Java API will be briefly unavailable during startup.\n'
    YHT_STOPPED=1
    docker stop --time 50 "$YHT_NAME"
    docker rename "$YHT_NAME" "$YHT_BACKUP"
    YHT_RENAMED=1
    bash "$YHT_CONTEXT/run.sh" "$YHT_IMAGE"
    printf '[5/5] Waiting up to %ss for Docker and database health.\n' "$YHT_WAIT"
    yht_ready || yht_fail 'The new Java did not pass its startup health check.'
    [[ $(yht_current_image) == "$YHT_IMAGE" && $(yht_current_hash) == "$YHT_JAR_SHA" ]] || \
        yht_fail 'The running image/JAR does not match release 215000.'
    yht_report
    printf 'SUCCESS: 215000 is active. Previous Java retained as: %s\n' "$YHT_BACKUP"
    printf 'Send a NEW customer message next; service health does not prove WeChat delivery.\n'
    YHT_COMPLETE=1
}

if [[ ${BASH_SOURCE[0]} == "$0" ]]; then
    yht_main "$@"
fi

#!/usr/bin/env bash
set -euo pipefail

[[ $(id -u) == 0 && $# == 1 ]] || {
    printf 'Usage (root): bash run.sh yuhetang-java-api:RELEASE_ID\n' >&2
    exit 1
}
image=$1
name=yuhetang-java-api
environment=/etc/yuwatang-reservation.docker.env
data=/var/lib/yuwatang-reservation
[[ -f "$environment" && ! -L "$environment" && $(stat -c '%u:%a' "$environment") == 0:600 ]] || {
    printf 'STOP: create %s as a regular root-owned 0600 file using export-env.py.\n' "$environment" >&2
    exit 1
}
uid=$(id -u yuwatang)
gid=$(id -g yuwatang)
[[ $uid != 0 && -d "$data" && ! -L "$data" && $(stat -c '%u:%g' "$data") == "$uid:$gid" ]] || {
    printf 'STOP: retain the existing yuwatang user and application data ownership; do not recursively change database ownership.\n' >&2
    exit 1
}
[[ $(docker info --format '{{.OSType}}') == linux ]] || {
    printf 'STOP: this deployment requires Docker on the Linux server.\n' >&2
    exit 1
}
[[ $(docker image inspect --format '{{index .Config.Labels "com.yuwatang.component"}}' "$image") == java-api ]] || {
    printf 'STOP: build the Java API release image using the documented label first.\n' >&2
    exit 1
}
if docker container inspect "$name" >/dev/null 2>&1; then
    printf 'STOP: %s already exists. Follow the update/rollback instructions; it will not be removed automatically.\n' "$name" >&2
    exit 1
fi
for port in 8790 8791; do
    if [[ -n $(ss -H -lnt "sport = :$port") ]]; then
        printf 'STOP: port %s is occupied. Stop only the old Java application after taking a backup.\n' "$port" >&2
        exit 1
    fi
done

docker run --detach --name "$name" \
    --label com.yuwatang.component=java-api \
    --restart unless-stopped --stop-timeout 50 --init --network host \
    --user "$uid:$gid" --env-file "$environment" \
    --mount "type=bind,source=$data,target=$data" \
    --read-only --tmpfs /tmp:rw,nosuid,nodev,noexec,size=64m \
    --cap-drop ALL --security-opt no-new-privileges=true \
    --memory 1g --memory-swap 1g --pids-limit 180 --ulimit nofile=8192:8192 \
    --log-driver json-file --log-opt max-size=10m --log-opt max-file=3 \
    "$image"
printf 'Java container created. Check /api/health and Docker health before disabling the old application service.\n'

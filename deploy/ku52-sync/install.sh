#!/usr/bin/env bash
set -euo pipefail
test "$(id -u)" = 0 || { echo 'Run as root.'; exit 1; }
YHT_SYNC_SOURCE="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
test -s /etc/yuhetang-ku52-sync/config.json
test -s /etc/yuhetang-ku52-sync/browser-state.json || { echo 'STOP: private source browser session is missing. See README.md.'; exit 1; }
install -d -m 0700 /opt/yuhetang-ku52-sync
for YHT_SYNC_FILE in worker.py ku52_export.py ku52_data.py requirements.txt stores.json; do
  install -m 0600 "$YHT_SYNC_SOURCE/$YHT_SYNC_FILE" "/opt/yuhetang-ku52-sync/$YHT_SYNC_FILE"
done
if ! command -v python3 >/dev/null; then
  apt-get update
  apt-get install -y python3 python3-venv
fi
if ! python3 -m venv /opt/yuhetang-ku52-sync/.venv; then
  apt-get update
  apt-get install -y python3-venv
  python3 -m venv /opt/yuhetang-ku52-sync/.venv
fi
/opt/yuhetang-ku52-sync/.venv/bin/python -m pip install -r /opt/yuhetang-ku52-sync/requirements.txt
/opt/yuhetang-ku52-sync/.venv/bin/python -m playwright install --with-deps chromium
install -m 0644 "$YHT_SYNC_SOURCE/yuhetang-ku52-sync.service" /etc/systemd/system/yuhetang-ku52-sync.service
install -m 0644 "$YHT_SYNC_SOURCE/yuhetang-ku52-sync.timer" /etc/systemd/system/yuhetang-ku52-sync.timer
systemctl daemon-reload
echo 'INSTALLED: timer is not enabled yet. Open the wallet/consumption page using a verified customer, then run the one-shot and inspect its result as described in README.md.'

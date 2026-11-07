#!/usr/bin/env bash
set -euo pipefail

# The JRE image already provides Bash. No curl, web server or package install.
exec 3<>/dev/tcp/127.0.0.1/8790
printf 'GET /api/health HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n' >&3
IFS= read -r status <&3
[[ $status == HTTP/1.1\ 200\ * || $status == HTTP/1.0\ 200\ * ]]

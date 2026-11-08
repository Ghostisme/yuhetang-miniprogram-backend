"""Configure only the staff service-account settings in the existing Docker env file.

Run interactively on the server as root. Secrets are entered with getpass, never
passed on the command line. This does not restart Java or connect to a database.
"""
import argparse
import datetime as dt
import getpass
import json
import os
from pathlib import Path
import re
import sys
import tempfile
from urllib.parse import urlsplit

EDITABLE = frozenset({"SERVICE_APP_ID", "SERVICE_APP_SECRET", "SERVICE_TEMPLATE_ID", "SERVICE_TEMPLATE_MAPPING", "PUBLIC_URL"})
STAFF_APP_ID = "wxf9ad4dfd7684097a"


def render(original, changes):
    if not changes or changes.keys() - EDITABLE:
        raise ValueError("Only service-account notification settings may be edited.")
    for key, value in changes.items():
        if not isinstance(value, str) or not value or value != value.strip() or any(c in value for c in "\r\n\0"):
            raise ValueError("Invalid single-line value for " + key)
    output, seen = [], set()
    for line in original.splitlines():
        key = line.partition("=")[0]
        if key in changes:
            if key in seen:
                raise ValueError("Duplicate existing configuration key: " + key)
            output.append(key + "=" + changes[key]); seen.add(key)
        else:
            output.append(line)
    output.extend(key + "=" + changes[key] for key in sorted(changes.keys() - seen))
    return "\n".join(output) + "\n"


def validate(changes):
    if not re.fullmatch(r"wx[a-zA-Z0-9]{16}", changes["SERVICE_APP_ID"]):
        raise ValueError("Service AppID must be a wx-prefixed 18-character AppID.")
    if not re.fullmatch(r"[A-Za-z0-9]{16,128}", changes["SERVICE_APP_SECRET"]):
        raise ValueError("AppSecret format is invalid; no changes were written.")
    if not re.fullmatch(r"[A-Za-z0-9_-]{16,160}", changes["SERVICE_TEMPLATE_ID"]):
        raise ValueError("Use the full private template ID from service account > Template Messages, not Subscription Notifications.")
    url = urlsplit(changes["PUBLIC_URL"])
    if url.scheme != "https" or not url.hostname or url.username or url.password or url.query or url.fragment or url.path not in ("", "/"):
        raise ValueError("PUBLIC_URL must be an HTTPS origin without a path.")
    mapping = json.loads(changes.get("SERVICE_TEMPLATE_MAPPING", "{}"))
    values = {"storeName", "serviceName", "customerName", "time", "status", "appointmentId", "event", "eventLabel", "customerPhone", "staffNotice"}
    if not isinstance(mapping, dict) or not mapping or any(not re.fullmatch(r"(?:[a-z_]+[0-9]+|first|remark)", k) or v not in values for k, v in mapping.items()):
        raise ValueError("Provide the actual template field mapping JSON; fields cannot be inferred from a different template.")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--app-id", default=STAFF_APP_ID)
    parser.add_argument("--template-id", help="Full private ID from the service account's Template Messages module")
    parser.add_argument("--mapping", help="Public JSON mapping of actual template field keys to booking fields")
    args = parser.parse_args()
    if not hasattr(os, "geteuid") or os.geteuid() != 0 or not sys.stdin.isatty():
        parser.exit(1, "Run interactively: sudo python3 configure-staff-wechat.py --app-id SERVICE_APP_ID\n")
    path = Path("/etc/yuwatang-reservation.docker.env")
    if path.is_symlink() or not path.is_file() or path.stat().st_uid != 0 or path.stat().st_mode & 0o777 != 0o600:
        parser.exit(1, "Keep the existing regular root-owned 0600 Docker environment file.\n")
    original = path.read_text(encoding="utf-8")
    current = dict(line.split("=", 1) for line in original.splitlines() if "=" in line and not line.startswith("#"))
    changes = {}
    changes["SERVICE_APP_ID"] = args.app_id or input("Service account AppID: ").strip()
    changes["SERVICE_APP_SECRET"] = getpass.getpass("Service AppSecret (hidden; Enter keeps current): ").strip() or current.get("SERVICE_APP_SECRET", "")
    changes["SERVICE_TEMPLATE_ID"] = (args.template_id if args.template_id is not None else input("Full private ID from Template Messages (required; not Subscription Notifications): ")).strip()
    changes["PUBLIC_URL"] = input("HTTPS admin origin [https://mp-admin.hetuonian.cn]: ").strip() or "https://mp-admin.hetuonian.cn"
    changes["SERVICE_TEMPLATE_MAPPING"] = (args.mapping if args.mapping is not None else input("Actual Template Messages field mapping JSON (required): ")).strip()
    try:
        validate(changes); content = render(original, changes)
    except (ValueError, TypeError) as error:
        parser.exit(1, "STOP: " + str(error) + "\n")
    if content == original:
        print("Settings already match; no file was changed."); return
    stamp = dt.datetime.now().strftime("%Y%m%d-%H%M%S-%f")
    backup = path.with_name(path.name + ".before-staff-" + stamp)
    fd = os.open(backup, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(fd, "w", encoding="utf-8", newline="\n") as stream:
        stream.write(original)
    fd, name = tempfile.mkstemp(prefix=".yht-wechat-", dir=path.parent)
    try:
        with os.fdopen(fd, "w", encoding="utf-8", newline="\n") as stream:
            stream.write(content); stream.flush(); os.fsync(stream.fileno())
        os.replace(name, path)
    finally:
        if os.path.exists(name): os.unlink(name)
    print("Saved root-only settings: " + ", ".join(sorted(changes)))
    print("Previous env backup: " + str(backup))
    print("Recreate Java with the matching release activator --reload-env; docker restart does not load edited env files.")
    print("Then use admin > message center > staff > check WeChat configuration, and bind each store's manager.")


if __name__ == "__main__":
    main()

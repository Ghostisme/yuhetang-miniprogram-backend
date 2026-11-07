#!/usr/bin/env python3
"""Import the reviewed official store dataset through the existing admin API.

Python 3.8+, standard library only. Without --apply this only previews changes.
"""
import argparse
import copy
import datetime as dt
import getpass
import hashlib
import io
import json
import os
from pathlib import Path
import re
import sys
import urllib.error
import urllib.parse
import urllib.request
import uuid
import xml.etree.ElementTree as ET
import zipfile

PUBLIC_FIELDS = ("name", "district", "address", "phone", "hours", "tag", "description")
HEADERS = ("id", "name", "district", "address", "phone", "hours", "image")
NS = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
ROOT = Path(__file__).resolve().parents[1]


def load_dataset(path):
    path = Path(path).resolve()
    manifest = json.loads(path.read_text(encoding="utf-8"))
    if manifest.get("schemaVersion") != 1 or not manifest.get("stores"):
        raise ValueError("Unsupported or empty dataset")
    ids, names = set(), set()
    for store in manifest["stores"]:
        sid, name = store["id"], store["name"]
        if not re.fullmatch(r"[A-Za-z0-9_-]{1,64}", sid) or sid in ids or name in names:
            raise ValueError("Duplicate or invalid store identity: " + sid)
        ids.add(sid)
        names.add(name)
        for key in PUBLIC_FIELDS:
            value = store[key]
            limit = 1000 if key == "description" else 60 if key == "name" else 300
            if not isinstance(value, str) or len(value) > limit:
                raise ValueError("Invalid public field: " + sid + "/" + key)
        if not re.fullmatch(r"[0-9+ -]{6,25}", store["phone"]):
            raise ValueError("Invalid telephone: " + sid)
        photo = (path.parent / store["photo"]["file"]).resolve()
        if path.parent not in photo.parents:
            raise ValueError("Photo must be inside the dataset: " + sid)
        content = photo.read_bytes()
        if not 0 < len(content) <= 5 * 1024 * 1024 or not content.startswith(b"\xff\xd8\xff"):
            raise ValueError("Invalid JPEG: " + sid)
        if len(content) != store["photo"]["bytes"] or hashlib.sha256(content).hexdigest() != store["photo"]["sha256"]:
            raise ValueError("Photo checksum mismatch: " + sid)
    return manifest


def plan_stores(incoming, current):
    """An exact name match keeps an existing noncanonical store ID."""
    plans, matched = [], set()
    for store in incoming:
        by_id = [s for s in current if s["id"] == store["id"]]
        by_name = [s for s in current if s["name"] == store["name"]]
        if len(by_id) > 1 or len(by_name) > 1:
            raise ValueError("Ambiguous existing store: " + store["name"])
        if by_id and (by_id[0]["name"] != store["name"] or (by_name and by_name[0]["id"] != store["id"])):
            raise ValueError("Store ID/name conflict; review manually: " + store["id"])
        old = (by_id or by_name or [None])[0]
        sid = old["id"] if old else store["id"]
        if sid in matched:
            raise ValueError("Two source stores map to one existing store: " + sid)
        matched.add(sid)
        plans.append({"source": store, "id": sid, "before": copy.deepcopy(old)})
    return plans


def merge_profile(current, source, image):
    merged = copy.deepcopy(current)
    merged.update({key: source[key] for key in PUBLIC_FIELDS})
    merged.update(image=image, gallery=[image])
    return merged


def workbook(template, stores):
    """Keep the server's XLSX headers/styles and insert plain text cells."""
    with zipfile.ZipFile(io.BytesIO(template)) as source:
        sheet = ET.fromstring(source.read("xl/worksheets/sheet1.xml"))
        rows = sheet.find("{%s}sheetData" % NS)
        if rows is None or len(rows) != 1:
            raise ValueError("Expected an empty server import template")
        for number, store in enumerate(stores, 2):
            row = ET.SubElement(rows, "{%s}row" % NS, {"r": str(number)})
            for col, key in enumerate(HEADERS):
                cell = ET.SubElement(row, "{%s}c" % NS, {"r": chr(65 + col) + str(number), "t": "inlineStr"})
                inline = ET.SubElement(cell, "{%s}is" % NS)
                ET.SubElement(inline, "{%s}t" % NS).text = str(store.get(key, ""))
        dimension = sheet.find("{%s}dimension" % NS)
        if dimension is not None:
            dimension.set("ref", "A1:G" + str(len(stores) + 1))
        out = io.BytesIO()
        with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as target:
            for item in source.infolist():
                target.writestr(item, ET.tostring(sheet, encoding="utf-8", xml_declaration=True)
                                if item.filename == "xl/worksheets/sheet1.xml" else source.read(item.filename))
        return out.getvalue()


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


class Api:
    def __init__(self, base):
        parsed = urllib.parse.urlsplit(base)
        if parsed.username or parsed.password or parsed.query or parsed.fragment or parsed.path not in ("", "/"):
            raise ValueError("Use the API origin only, without credentials or a path")
        if parsed.scheme != "https" and not (parsed.scheme == "http" and parsed.hostname in ("127.0.0.1", "localhost", "::1")):
            raise ValueError("Use HTTPS, or HTTP on the server's loopback address")
        self.base, self.token = base.rstrip("/"), ""
        self.opener = urllib.request.build_opener(NoRedirect())

    def request(self, method, path, payload=None, content_type=None, binary=False):
        if not path.startswith("/") or path.startswith("//"):
            raise ValueError("Expected a relative API path")
        headers = {"Accept": "application/json"}
        if self.token:
            headers["Authorization"] = "Bearer " + self.token
        if payload is not None and not isinstance(payload, bytes):
            payload = json.dumps(payload, ensure_ascii=False).encode("utf-8")
            content_type = "application/json; charset=utf-8"
        if content_type:
            headers["Content-Type"] = content_type
        try:
            with self.opener.open(urllib.request.Request(self.base + path, payload, headers, method=method), timeout=30) as response:
                body = response.read()
                return body if binary else json.loads(body)
        except urllib.error.HTTPError as error:
            # Never include the Authorization header or login payload in errors.
            message = error.read(2000).decode("utf-8", errors="replace")
            raise RuntimeError("%s %s: HTTP %s %s" % (method, path, error.code, message)) from None

    def get(self, path, binary=False):
        return self.request("GET", path, binary=binary)

    def upload(self, path, name, content, mime):
        boundary = "yht" + uuid.uuid4().hex
        body = ("--%s\r\nContent-Disposition: form-data; name=\"file\"; filename=\"%s\"\r\nContent-Type: %s\r\n\r\n" %
                (boundary, name, mime)).encode("ascii") + content + ("\r\n--%s--\r\n" % boundary).encode("ascii")
        return self.request("POST", path, body, "multipart/form-data; boundary=" + boundary)


def write_json(path, value):
    content = json.dumps(value, ensure_ascii=False, indent=2) + "\n"
    # Backup paths contain no credentials and are kept outside the public website.
    descriptor = os.open(str(path), os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(descriptor, "w", encoding="utf-8") as stream:
        stream.write(content)


def initialize(api, manifest, dataset_dir, apply=False, backup_dir=None):
    me = api.get("/api/admin/me")
    required = {"stores:write", "assets:read", "assets:write", "imports:write", "rules:write"}
    if not me.get("hq") or not required.issubset(me.get("permissions", [])):
        raise ValueError("A headquarters administrator with store, asset, import and rule permissions is required")
    current = api.get("/api/admin/stores")
    plans = plan_stores(manifest["stores"], current)
    rules = {p["id"]: api.get("/api/admin/stores/" + urllib.parse.quote(p["id"], safe="") + "/rules")
             for p in plans if p["before"] is not None}
    assets = api.get("/api/admin/assets")
    template = api.get("/api/admin/imports/stores/template", binary=True)
    # Validate every row with the deployed Java parser before any upload/write.
    rows = [{**p["source"], "id": p["id"], "image": ""} for p in plans]
    preview = api.upload("/api/admin/imports/stores?commit=false", "stores.xlsx", workbook(template, rows),
                         "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
    if not preview.get("valid") or preview.get("total") != len(plans):
        raise ValueError("Server import preflight failed: " + json.dumps(preview, ensure_ascii=False))
    for plan in plans:
        source = plan["source"]
        title = "official-%s-%s.jpg" % (source["id"], source["photo"]["sha256"][:12])
        plan["assetTitle"] = title
        matches = [a for a in assets if a.get("title") == title]
        for asset in matches:
            url = asset.get("url", "")
            if not re.fullmatch(r"/uploads/[A-Za-z0-9_-]+\.jpg", url):
                continue
            try:
                if hashlib.sha256(api.get(url, binary=True)).hexdigest() == source["photo"]["sha256"]:
                    plan["image"] = url
                    break
            except RuntimeError:
                continue  # Missing old file: upload the checked source again.
        action = "CREATE" if plan["before"] is None else "UPDATE"
        if plan["before"] is not None and plan.get("image") and merge_profile(plan["before"], source, plan["image"]) == plan["before"]:
            action = "UNCHANGED"
        print("%s  %s  %s  [%s]" % (action, plan["id"], source["name"], source["address"]))
    if not apply:
        print("Preview complete: %d stores; no assets or store data written. Use --apply to initialize." % len(plans))
        return {"preview": True, "stores": len(plans)}

    stamp = dt.datetime.now(dt.timezone.utc).strftime("%Y%m%dT%H%M%SZ") + "-" + uuid.uuid4().hex[:8]
    backup = Path(backup_dir or ROOT / "backups" / "official-stores").resolve() / stamp
    backup.mkdir(parents=True, mode=0o700)
    write_json(backup / "before.json", {"api": api.base, "sourceCapturedAt": manifest["capturedAt"],
                                     "stores": current, "rules": rules})
    report = {"api": api.base, "status": "running", "created": [], "updated": [], "unchanged": [], "uploaded": []}
    print("Backup: " + str(backup))
    try:
        # Detect changes since preview before making any writes.
        if api.get("/api/admin/stores") != current:
            raise RuntimeError("Store data changed during preview; rerun after other edits finish")
        for plan in plans:
            if not plan.get("image"):
                photo = (Path(dataset_dir) / plan["source"]["photo"]["file"]).read_bytes()
                uploaded = api.upload("/api/admin/assets", plan["assetTitle"], photo, "image/jpeg")
                plan["image"] = uploaded["url"]
                report["uploaded"].append({"id": uploaded["id"], "url": uploaded["url"], "storeId": plan["id"]})
                write_json(backup / "report.json", report)
        missing = [p for p in plans if p["before"] is None]
        if missing:
            live = api.get("/api/admin/stores")
            if any(s["id"] == p["id"] or s["name"] == p["source"]["name"] for p in missing for s in live):
                raise RuntimeError("A target store was created during import; rerun to match its existing ID")
            new_rows = [{**p["source"], "id": p["id"], "image": p["image"]} for p in missing]
            result = api.upload("/api/admin/imports/stores?commit=true", "stores.xlsx", workbook(template, new_rows),
                                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
            report["created"] = [p["id"] for p in missing]
            write_json(backup / "report.json", report)
            if result.get("imported") != len(missing):
                raise RuntimeError("Unexpected store import count; inspect report before rerunning")
        for plan in plans:
            sid = plan["id"]
            live = next(s for s in api.get("/api/admin/stores") if s["id"] == sid)
            if plan["before"] is not None and live != plan["before"]:
                raise RuntimeError("Store changed during import; stopped before overwriting: " + sid)
            desired = merge_profile(live, plan["source"], plan["image"])
            if live == desired:
                report["unchanged"].append(sid)
            else:
                api.request("PUT", "/api/admin/stores/" + urllib.parse.quote(sid, safe=""), desired)
                if sid not in report["created"]:
                    report["updated"].append(sid)
            plan["expected"] = desired
            write_json(backup / "report.json", report)
        actual = {s["id"]: s for s in api.get("/api/admin/stores")}
        for plan in plans:
            if actual[plan["id"]] != plan["expected"]:
                raise RuntimeError("Store verification failed: " + plan["id"])
            image = api.get(plan["image"], binary=True)
            if hashlib.sha256(image).hexdigest() != plan["source"]["photo"]["sha256"]:
                raise RuntimeError("Uploaded photo verification failed: " + plan["id"])
        for sid, before in rules.items():
            if api.get("/api/admin/stores/" + urllib.parse.quote(sid, safe="") + "/rules") != before:
                raise RuntimeError("Booking rules changed concurrently; review store: " + sid)
        report["status"] = "verified"
    except Exception as error:
        report.update(status="incomplete", error=str(error))
        raise
    finally:
        write_json(backup / "report.json", report)
    print("Verified: %d created, %d updated, %d unchanged, %d photos uploaded." %
          tuple(len(report[k]) for k in ("created", "updated", "unchanged", "uploaded")))
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default="http://127.0.0.1:8790")
    parser.add_argument("--username", default="admin")
    parser.add_argument("--dataset", type=Path, default=ROOT / "initial-data/official-stores/stores.json")
    parser.add_argument("--backup-dir", type=Path)
    parser.add_argument("--apply", action="store_true", help="Write store data and photos (default: preview only)")
    args = parser.parse_args()
    manifest = load_dataset(args.dataset)
    api = Api(args.base_url)
    api.token = os.environ.get("YUHETANG_ADMIN_TOKEN", "")
    if not api.token:
        password = getpass.getpass("Headquarters administrator password: ")
        api.token = api.request("POST", "/api/admin/login", {"username": args.username, "password": password})["token"]
    initialize(api, manifest, args.dataset.resolve().parent, args.apply, args.backup_dir)


if __name__ == "__main__":
    try:
        main()
    except (ValueError, RuntimeError, OSError, KeyError, StopIteration, zipfile.BadZipFile) as error:
        print("Initialization stopped: " + str(error), file=sys.stderr)
        sys.exit(1)

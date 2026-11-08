#!/usr/bin/env python3
"""Initialize separate store-owned menus through the admin API (preview by default)."""
import argparse
import copy
import datetime as dt
import getpass
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import sys
import unicodedata
import urllib.parse
import uuid

ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location("store_init_support", Path(__file__).with_name("initialize-official-stores.py"))
SUPPORT = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(SUPPORT)
Api, write_json = SUPPORT.Api, SUPPORT.write_json
FIELDS = ("name", "english", "subtitle", "category", "duration", "image", "tag", "description", "steps", "sortOrder", "enabled")


def normalize(value):
    return re.sub(r"[\s/／·＋+—-]", "", unicodedata.normalize("NFKC", value)).lower()


def load_dataset(path):
    path = Path(path).resolve()
    data = json.loads(path.read_text(encoding="utf-8"))
    if data.get("schemaVersion") != 1 or not data.get("stores") or not data.get("services"):
        raise ValueError("Unsupported or empty service dataset")
    ids = [s["id"] for s in data["services"]]
    if len(ids) != len(set(ids)) or any(not re.fullmatch(r"[A-Za-z0-9_-]{1,64}", sid) for sid in ids):
        raise ValueError("Duplicate or invalid service template identity")
    stores = set()
    for store in data["stores"]:
        sid = store["id"]
        if sid in stores or not re.fullmatch(r"[A-Za-z0-9_-]{1,64}", sid):
            raise ValueError("Duplicate or invalid store identity")
        stores.add(sid)
        seen = set()
        for item in store["items"]:
            if item["serviceId"] not in ids or item["serviceId"] in seen:
                raise ValueError("Unknown or duplicate branch item: " + sid)
            seen.add(item["serviceId"])
            if item["reviewStatus"] != "MENU_VERIFIED" and item["enabled"]:
                raise ValueError("Pending items must remain disabled: " + sid + "/" + item["serviceId"])
            prices = item["price"]
            if prices["currency"] != "CNY" or any(type(v) is not int or v <= 0 for v in prices["values"].values()):
                raise ValueError("Invalid CNY price: " + sid)
        photo = (path.parent / store["menu"]["file"]).resolve()
        if path.parent not in photo.parents:
            raise ValueError("Menu image must be inside the dataset")
        content = photo.read_bytes()
        if not 0 < len(content) <= 5*1024*1024 or not content.startswith(b"\xff\xd8\xff"):
            raise ValueError("Expected a JPEG under 5 MB: " + sid)
        if len(content) != store["menu"]["bytes"] or hashlib.sha256(content).hexdigest() != store["menu"]["sha256"]:
            raise ValueError("Menu checksum mismatch: " + sid)
    templates = {s["id"]:s for s in data["services"]}
    for store in data["stores"]:
        for item in store["items"]:
            project(templates[item["serviceId"]], item)
    return data


def price_text(prices):
    values = prices["values"]
    if list(values) == ["统一"]:
        text = "统一价" + str(values["统一"]) + "元"
    else:
        text = "／".join(k + str(v) + "元" for k,v in values.items()) or "价格待门店确认"
    if prices["unit"] in {"小时", "30分钟", "项", "套餐"}:
        text += "/" + prices["unit"]
    return text


def project(template, item):
    result = {key:copy.deepcopy(template[key]) for key in FIELDS}
    result.update(name=item["name"], duration=item["duration"], enabled=item["enabled"])
    prices = item["price"]
    result["subtitle"] = item["duration"] + " · " + (price_text(prices) if item["enabled"] else "资料待门店确认")
    result["description"] = item["name"] + "。" + ("参考时长：" if item["duration"] != "每项" else "计费方式：") + item["duration"] + "。\n"
    result["description"] += "参考价格：" + price_text(prices) + "。"
    if prices.get("note"):
        result["description"] += prices["note"] + "。"
    if not item["enabled"]:
        result["description"] += "\n英文服务指南补充，本店中文价目图片未列出；项目是否提供、时长和价格待门店确认，暂不上架。"
    if template["id"] in {"yuht-cupping", "guasha", "yuht-pedicure"}:
        result["description"] += "\n按每项计费，资料未列出固定分钟数，服务内容及用时请与门店确认。"
    if template["id"] == "yuht-symptom-care":
        result["description"] += "\n价目表标注特聘专家统一价。"
    if item.get("components"):
        names = {"moxa":"扶阳悬灸", "massage":"中医推拿", "foot":"香薰足疗", "yuht-spa":"精油SPA", "yuht-meridian-care":"经络疏通"}
        result["steps"] = [names[c["serviceId"]] + str(c["minutes"]) + "分钟" for c in item["components"]]
        result["description"] += "\n套餐包含：" + "＋".join(result["steps"]) + "。以上为图片中的活动价。"
    result["description"] += "\n具体收费请以门店确认及现场公示为准。"
    for key in ("name", "english", "subtitle", "category", "duration", "image", "tag", "description"):
        limit = 60 if key == "name" else 3000 if key == "description" else 300 if key == "image" else 100
        if not isinstance(result[key], str) or len(result[key]) > limit:
            raise ValueError("Invalid service field: " + template["id"] + "/" + key)
    return result


def resolve_stores(incoming, current):
    result, seen = [], set()
    for source in incoming:
        names = {normalize(source["name"]), *(normalize(n) for n in source.get("aliases", []))}
        matches = [s for s in current if s["id"] == source["id"] or normalize(s["name"]) in names]
        if len(matches) != 1 or normalize(matches[0]["name"]) not in names or matches[0]["id"] in seen:
            raise ValueError("Missing, conflicting or ambiguous store; initialize stores first: " + source["name"])
        seen.add(matches[0]["id"])
        result.append(dict(source=source, before=copy.deepcopy(matches[0]), id=matches[0]["id"]))
    return result


def match_item(template, item, current):
    names = {normalize(item["name"]), normalize(template["name"]), *(normalize(n) for n in template.get("aliases", []))}
    candidates = [s for s in current if s.get("source") == "STORE" and
                  (normalize(s["name"]) in names or s.get("english") == template["english"])]
    if len(candidates) > 1:
        raise ValueError("Ambiguous existing store-owned item: " + item["name"])
    return candidates[0] if candidates else None


def fields(record):
    return {key:record.get(key) for key in FIELDS}


def profile(record):
    return {key:value for key,value in record.items() if key != "services"}


def store_path(sid):
    return "/api/admin/stores/" + urllib.parse.quote(sid, safe="")


def initialize(api, data, dataset_dir, apply=False, backup_dir=None):
    me = api.get("/api/admin/me")
    required = {"stores:write", "services:read", "services:write", "assets:read", "assets:write", "content:read"}
    if not me.get("hq") or not required.issubset(me.get("permissions", [])):
        raise ValueError("A headquarters account with store, service and asset permissions is required")
    capability = api.get("/api/runtime").get("independentStoreServices") is True
    if apply and not capability:
        raise ValueError("Upgrade Java to the independent-store-catalog release before --apply; nothing was written")
    current_stores = api.get("/api/admin/stores")
    plans = resolve_stores(data["stores"], current_stores)
    templates = {s["id"]:s for s in data["services"]}
    content_before = api.get("/api/admin/content")
    assets = api.get("/api/admin/assets")
    for plan in plans:
        sid, source = plan["id"], plan["source"]
        existing = api.get(store_path(sid) + "/services")
        plan["servicesBefore"] = copy.deepcopy(existing)
        plan["rulesBefore"] = api.get(store_path(sid) + "/rules")
        plan["items"] = []
        used = set()
        for item in source["items"]:
            template = templates[item["serviceId"]]
            old = match_item(template, item, existing)
            if old and old["id"] in used:
                raise ValueError("Two client items map to one existing store record")
            if old:used.add(old["id"])
            desired = project(template, item)
            action = "CREATE" if not old else "UNCHANGED" if fields(old) == desired else "UPDATE"
            plan["items"].append(dict(source=item, desired=desired, before=copy.deepcopy(old), id=old["id"] if old else None))
            print("%s  %s  %s  [%s]" % (action, source["name"], item["name"], "active" if item["enabled"] else "pending/off"))
        menu = source["menu"]
        plan["assetTitle"] = "client-menu-%s-%s.jpg" % (source["id"], menu["sha256"][:12])
        for asset in assets:
            if asset.get("title") != plan["assetTitle"]:continue
            url = asset.get("url", "")
            if not re.fullmatch(r"/uploads/[A-Za-z0-9_-]+\.jpg", url):continue
            if hashlib.sha256(api.get(url, binary=True)).hexdigest() == menu["sha256"]:
                plan["menuAsset"] = asset
                break
    summary = dict(stores=len(plans), items=sum(len(p["items"]) for p in plans),
                   active=sum(bool(i["desired"]["enabled"]) for p in plans for i in p["items"]),
                   pending=sum(not i["desired"]["enabled"] for p in plans for i in p["items"]))
    if not apply:
        print("Preview: " + json.dumps(summary) + "; no service, store or image was written.")
        if not capability:print("Java upgrade required before applying independent catalogs.")
        return dict(status="preview", **summary)

    stamp = dt.datetime.now(dt.timezone.utc).strftime("%Y%m%dT%H%M%SZ") + "-" + uuid.uuid4().hex[:8]
    backup = Path(backup_dir or ROOT / "backups/client-services").resolve() / stamp
    backup.mkdir(parents=True, mode=0o700)
    write_json(backup / "before.json", dict(api=api.base, datasetId=data["datasetId"], content=content_before,
               stores=[dict(id=p["id"], profile=p["before"], services=p["servicesBefore"], rules=p["rulesBefore"]) for p in plans]))
    report = dict(api=api.base, datasetId=data["datasetId"], status="running", created=[], updated=[], uploaded=[], **summary)
    # Store counts separately from per-store completion records.
    report["storeCount"] = summary["stores"]
    report["stores"] = []
    write_json(backup / "report.json", report)
    print("Backup: " + str(backup))
    try:
        if api.get("/api/admin/stores") != current_stores:
            raise RuntimeError("Store profiles changed during preview; rerun before writing")
        for plan in plans:
            sid = plan["id"]
            report["phase"] = "store:" + sid
            if api.get(store_path(sid)+"/services") != plan["servicesBefore"]:
                raise RuntimeError("Store services changed during preview: " + sid)
            if "menuAsset" not in plan:
                menu = plan["source"]["menu"]
                plan["menuAsset"] = api.upload("/api/admin/assets", plan["assetTitle"],
                                               (Path(dataset_dir)/menu["file"]).read_bytes(), "image/jpeg")
                report["uploaded"].append(plan["menuAsset"])
                write_json(backup/"report.json", report)
            # New rows stay off while they are prepared, so incomplete imports do
            # not expose half-created menus or inherit another branch's prices.
            for item in plan["items"]:
                if item["id"]:continue
                created = api.request("POST", store_path(sid)+"/services", {**item["desired"], "enabled":False})
                item["id"] = created["id"]
                report["created"].append(dict(storeId=sid, serviceId=created["id"], sourceId=item["source"]["serviceId"]))
                write_json(backup/"report.json", report)
            live_stores = api.get("/api/admin/stores")
            live_profile = next(s for s in live_stores if s["id"] == sid)
            if profile(live_profile) != profile(plan["before"]):
                raise RuntimeError("Store profile changed during import: " + sid)
            if live_profile.get("catalogMode") != "INDEPENDENT":
                api.request("PUT", store_path(sid), {**profile(live_profile), "catalogMode":"INDEPENDENT"})
            current = {s["id"]:s for s in api.get(store_path(sid)+"/services")}
            changes = []
            for item in plan["items"]:
                old = current[item["id"]]
                if old.get("source") != "STORE":raise RuntimeError("Expected an independent store-owned item")
                if item["before"] is not None and old != item["before"]:
                    raise RuntimeError("Store item changed during import: " + sid + "/" + item["id"])
                if fields(old) != item["desired"]:
                    changes.append({**item["desired"], "id":item["id"], "version":old["version"]})
            if changes:
                api.request("PUT", store_path(sid)+"/services", {"items":changes})
                report["updated"].extend(dict(storeId=sid, serviceId=s["id"]) for s in changes)
            actual = {s["id"]:s for s in api.get(store_path(sid)+"/services")}
            for item in plan["items"]:
                if actual[item["id"]].get("source") != "STORE" or fields(actual[item["id"]]) != item["desired"]:
                    raise RuntimeError("Service verification failed: " + sid)
            unchanged = [s for s in plan["servicesBefore"] if s.get("source") == "STORE" and s["id"] not in {i["id"] for i in plan["items"]}]
            if any(actual.get(s["id"]) != s for s in unchanged):
                raise RuntimeError("An unrelated existing store-owned item changed: " + sid)
            after_profile = next(s for s in api.get("/api/admin/stores") if s["id"] == sid)
            expected_profile = {**profile(plan["before"]), "catalogMode":"INDEPENDENT"}
            if profile(after_profile) != expected_profile or api.get(store_path(sid)+"/rules") != plan["rulesBefore"]:
                raise RuntimeError("Store profile or booking rules changed unexpectedly: " + sid)
            report["stores"].append(dict(id=sid, name=plan["source"]["name"], catalogMode="INDEPENDENT", items=[
                dict(id=i["id"], sourceId=i["source"]["serviceId"], name=i["desired"]["name"], enabled=i["desired"]["enabled"],
                     price=i["source"]["price"]) for i in plan["items"]]))
            write_json(backup/"report.json", report)
        if api.get("/api/admin/content") != content_before:
            raise RuntimeError("Headquarters content changed concurrently; inspect report")
        report.update(status="verified", phase="complete")
    except Exception as error:
        report.update(status="incomplete", error=str(error))
        raise
    finally:
        write_json(backup/"report.json", report)
    print("Verified: %d stores, %d independent items (%d active, %d pending/off); %d new records, %d uploads." %
          (report["storeCount"], report["items"], report["active"], report["pending"], len(report["created"]), len(report["uploaded"])))
    return report


def main():
    if hasattr(sys.stdout, "reconfigure"):sys.stdout.reconfigure(encoding="utf-8")
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default="http://127.0.0.1:8790")
    parser.add_argument("--username", default="admin")
    parser.add_argument("--dataset", type=Path, default=ROOT/"initial-data/client-services-20261108/services.json")
    parser.add_argument("--backup-dir", type=Path)
    parser.add_argument("--validate-only", action="store_true", help="Check files without connecting to any server")
    parser.add_argument("--apply", action="store_true", help="Write independent store items and switch their catalog mode")
    args = parser.parse_args()
    data = load_dataset(args.dataset)
    if args.validate_only:
        print("Dataset files and checksums valid; no server connection made.")
        return
    api = Api(args.base_url)
    api.token = os.environ.get("YUHETANG_ADMIN_TOKEN", "")
    if not api.token:
        password = getpass.getpass("Headquarters administrator password: ")
        api.token = api.request("POST", "/api/admin/login", {"username":args.username, "password":password})["token"]
    initialize(api, data, args.dataset.resolve().parent, args.apply, args.backup_dir)


if __name__ == "__main__":
    try:main()
    except (ValueError, RuntimeError, OSError, KeyError, StopIteration) as error:
        print("Initialization stopped: " + str(error), file=sys.stderr)
        sys.exit(1)

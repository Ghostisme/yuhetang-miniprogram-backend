#!/usr/bin/env python3
"""Add the remaining local sample content via admin APIs; preview is read-only."""
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
SPEC = importlib.util.spec_from_file_location("local_content_support", Path(__file__).with_name("initialize-official-stores.py"))
SUPPORT = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(SUPPORT)
Api, write_json = SUPPORT.Api, SUPPORT.write_json
CONTENT_FIELDS = ("brandName", "brandEnglish", "tagline", "theme", "hero", "brandStory", "contact",
                  "sections", "services", "products", "galleryGroups", "customerServiceEnabled")
SOURCE_FIELDS = set(CONTENT_FIELDS) - {"customerServiceEnabled"}
DEFAULT_DATASET = ROOT / "initial-data/local-content-20261108/content.json"


def normalize(value):
    return re.sub(r"[\s/／·＋+—-]", "", unicodedata.normalize("NFKC", value)).lower()


def asset_file(dataset_dir, asset):
    # The repository already tracks these photos. The standalone ZIP places a
    # checked copy under the dataset, without needing the rest of the repository.
    name = asset["file"]
    if not re.fullmatch(r"media/[a-z0-9_-]+\.webp", name):
        raise ValueError("Invalid bundled asset path")
    bundled = Path(dataset_dir) / name
    return bundled if bundled.is_file() else ROOT / "src/main/resources/static" / name


def image_urls(value):
    if isinstance(value, dict):
        for key, item in value.items():
            if key in {"image", "url"} and isinstance(item, str) and item:
                yield item
            elif key == "images" and isinstance(item, list):
                yield from item
            else:
                yield from image_urls(item)
    elif isinstance(value, list):
        for item in value:
            yield from image_urls(item)


def load_dataset(path):
    path = Path(path).resolve()
    data = json.loads(path.read_text(encoding="utf-8"))
    if data.get("schemaVersion") != 1 or data.get("datasetId") != "local-content-20261108":
        raise ValueError("Unsupported dataset")
    if set(data["content"]) - SOURCE_FIELDS:
        raise ValueError("Only brand editor fields may be imported")
    for key in ("services", "products", "galleryGroups", "sections", "assets", "stores", "therapists"):
        items = data["content"][key] if key in data["content"] else data[key]
        ids = [item["id"] for item in items]
        if len(set(ids)) != len(ids) or any(not re.fullmatch(r"[A-Za-z0-9_-]{1,64}", x) for x in ids):
            raise ValueError("Duplicate or invalid identities: " + key)
    urls = set()
    for asset in data["assets"]:
        if asset["url"] != "/" + asset["file"]:
            raise ValueError("Asset URL and filename differ")
        content = asset_file(path.parent, asset).read_bytes()
        if not (12 <= len(content) <= 5 * 1024 * 1024 and content[:4] == b"RIFF" and content[8:12] == b"WEBP"):
            raise ValueError("Expected a WebP image under 5 MB")
        if len(content) != asset["bytes"] or hashlib.sha256(content).hexdigest() != asset["sha256"]:
            raise ValueError("Asset checksum mismatch: " + asset["id"])
        urls.add(asset["url"])
    stores = {s["id"] for s in data["stores"]}
    for teacher in data["therapists"]:
        if teacher["storeId"] not in stores or teacher.get("sample") is not True or "示例" not in teacher["name"]:
            raise ValueError("Only explicitly labelled sample therapists may be imported")
        if set(teacher["skillKeys"]) - data["skillDefinitions"].keys():
            raise ValueError("Unknown sample therapist skill")
    if (set(image_urls(data["content"])) | set(image_urls(data["therapists"]))) - urls:
        raise ValueError("Every referenced image must be in the reviewed asset bundle")
    return data


def site_fields(site):
    return {k: copy.deepcopy(site.get(k)) for k in CONTENT_FIELDS}


def fill_missing(current, incoming):
    """Preserve populated values, false flags, and existing entries with the same ID."""
    result = copy.deepcopy(current)
    for key, value in incoming.items():
        old = result.get(key)
        if old is None or isinstance(old, str) and not old.strip():
            if value is not None:
                result[key] = copy.deepcopy(value)
        elif isinstance(old, dict) and isinstance(value, dict):
            result[key] = fill_missing(old, value)
        elif isinstance(old, list) and isinstance(value, list):
            if not old:
                result[key] = copy.deepcopy(value)
            elif all(isinstance(item, dict) and "id" in item for item in old + value):
                ids = [item["id"] for item in old]
                if len(ids) != len(set(ids)):
                    raise ValueError("Duplicate existing content identity: " + key)
                result[key].extend(copy.deepcopy(item) for item in value if item["id"] not in ids)
    return result


def rewrite_images(value, mapping):
    if isinstance(value, str):
        return mapping.get(value, value)
    if isinstance(value, list):
        return [rewrite_images(item, mapping) for item in value]
    if isinstance(value, dict):
        return {key: rewrite_images(item, mapping) for key, item in value.items()}
    return value


def resolve_stores(source, current):
    plans, used = [], set()
    for store in source:
        names = {normalize(n) for n in [store["name"], *store.get("aliases", [])]}
        matches = [s for s in current if s["id"] == store["id"] or normalize(s["name"]) in names]
        if len(matches) != 1 or normalize(matches[0]["name"]) not in names or matches[0]["id"] in used:
            raise ValueError("Initialize official stores first; missing/ambiguous store: " + store["name"])
        target = matches[0]
        if target.get("catalogMode") != "INDEPENDENT":
            raise ValueError("Initialize the independent branch catalog first: " + store["name"])
        used.add(target["id"])
        plans.append(dict(source=store, target=target))
    return plans


def match_therapist(source, store_id, current):
    marker = "local-content-20261108:" + source["id"]
    matches = [t for t in current if t.get("sampleSource") == marker or
               t.get("storeId") == store_id and (t.get("id") == source["id"] or
               t.get("sample") is True and t.get("name") == source["name"])]
    if len(matches) > 1 or matches and matches[0].get("storeId") != store_id:
        raise ValueError("Conflicting sample therapist identity: " + source["id"])
    # A sample that was converted into a real staff profile must never be reset.
    return matches[0] if matches else None


def therapist_payload(source, store_id, services, definitions, image):
    skills = []
    for key in source["skillKeys"]:
        definition = definitions[key]
        names = {normalize(n) for n in [definition["name"], *definition.get("aliases", [])]}
        matches = [s for s in services if s.get("source") == "STORE" and s.get("enabled") is True and
                   (normalize(s["name"]) in names or s.get("english") == definition["english"])]
        if len(matches) > 1:
            raise ValueError("Ambiguous branch skill: " + store_id + "/" + key)
        if matches:
            skills.append(matches[0]["id"])
    if not skills:
        raise ValueError("No matching active branch skill for sample: " + source["id"])
    result = {k: copy.deepcopy(source[k]) for k in
              ("name", "sample", "grade", "specialty", "enabled", "showOnClient", "resting")}
    result.update(storeId=store_id, image=image, serviceIds=skills,
                  sampleSource="local-content-20261108:" + source["id"])
    return result


def upload_asset(api, source, content):
    # UTF-8 filenames keep useful Chinese titles in the existing asset browser.
    name = "示例素材-%s-%s-%s.webp" % (source["title"], source["id"], source["sha256"][:10])
    if any(c in name for c in '\r\n"\\/'):
        raise ValueError("Invalid asset title")
    boundary = "yht" + uuid.uuid4().hex
    header = '--%s\r\nContent-Disposition: form-data; name="file"; filename="%s"\r\nContent-Type: image/webp\r\n\r\n' % (boundary, name)
    body = header.encode("utf-8") + content + ("\r\n--%s--\r\n" % boundary).encode("ascii")
    return api.request("POST", "/api/admin/assets", body, "multipart/form-data; boundary=" + boundary)


def initialize(api, data, dataset_dir, apply=False, backup_dir=None):
    required = {"content:read", "content:write", "content:publish", "assets:read", "assets:write",
                "therapists:read", "therapists:write", "services:read", "rules:write"}
    me = api.get("/api/admin/me")
    if not me.get("hq") or not required.issubset(me.get("permissions", [])):
        raise ValueError("Use a headquarters administrator with content, asset and therapist permissions")
    if api.get("/api/runtime").get("independentStoreServices") is not True:
        raise ValueError("Activate Java release 20261108-125710 or later before importing")
    stores = api.get("/api/admin/stores")
    store_plans = resolve_stores(data["stores"], stores)
    store_ids = {p["source"]["id"]: p["target"]["id"] for p in store_plans}
    services, rules = {}, {}
    for plan in store_plans:
        sid = plan["target"]["id"]
        path = "/api/admin/stores/" + urllib.parse.quote(sid, safe="")
        services[sid], rules[sid] = api.get(path + "/services"), api.get(path + "/rules")
        if not services[sid] or any(s.get("source") != "STORE" for s in services[sid]):
            raise ValueError("Branch catalog not ready: " + plan["target"]["name"])
    draft, published = api.get("/api/admin/content"), api.get("/api/site")
    assets, therapists = api.get("/api/admin/assets"), api.get("/api/admin/therapists")
    unpublished = site_fields(draft) != site_fields(published)
    teacher_plans = []
    for source in data["therapists"]:
        sid = store_ids[source["storeId"]]
        existing = match_therapist(source, sid, therapists)
        payload = therapist_payload(source, sid, services[sid], data["skillDefinitions"], source["image"]) if not existing else None
        teacher_plans.append(dict(source=source, existing=existing, payload=payload))

    # Hash registered shared images once. Reuse existing assets by bytes, including
    # images created before this importer; never copy official store/menu records.
    wanted = {a["sha256"] for a in data["assets"]}
    found = {}
    for asset in assets:
        url = asset.get("url", "")
        if asset.get("storeId") or not re.fullmatch(r"/(?:media|uploads)/[A-Za-z0-9_-]+\.(?:webp|jpg|png)", url):
            continue
        try:
            digest = hashlib.sha256(api.get(url, binary=True)).hexdigest()
        except (OSError, RuntimeError):
            continue
        if digest in wanted:
            found.setdefault(digest, asset)
    mapping = {a["url"]: found[a["sha256"]]["url"] for a in data["assets"] if a["sha256"] in found}
    preview_site = fill_missing(site_fields(draft), rewrite_images(data["content"], mapping))
    changed_fields = [k for k in CONTENT_FIELDS if preview_site.get(k) != draft.get(k)]
    if "services" in changed_fields and any(s.get("catalogMode") != "INDEPENDENT" for s in stores):
        raise ValueError("An additional store still inherits brand items; review its catalog before adding brand samples")
    print("Content fields to supplement: " + (", ".join(changed_fields) or "none"))
    print("Assets: %d source, %d reusable, %d to upload" % (len(data["assets"]), len(mapping), len(data["assets"]) - len(mapping)))
    print("Sample therapists: %d source, %d existing, %d to create" %
          (len(teacher_plans), sum(p["existing"] is not None for p in teacher_plans), sum(p["existing"] is None for p in teacher_plans)))
    if unpublished:
        print("Existing unpublished edits detected. Supplemental brand content will stay in the draft for manual review/publish.")
    if not apply:
        print("Preview complete; no content, asset, therapist or branch data was written. Use --apply to supplement.")
        return dict(status="preview", fields=changed_fields, assets=len(data["assets"]), therapists=len(teacher_plans))

    stamp = dt.datetime.now(dt.timezone.utc).strftime("%Y%m%dT%H%M%SZ") + "-" + uuid.uuid4().hex[:8]
    backup = Path(backup_dir or ROOT / "backups/local-content").resolve() / stamp
    backup.mkdir(parents=True, mode=0o700)
    write_json(backup / "before.json", dict(api=api.base, datasetId=data["datasetId"], draft=draft, published=published,
               stores=stores, services=services, rules=rules, assets=assets, therapists=therapists))
    report = dict(api=api.base, datasetId=data["datasetId"], status="running", uploaded=[], createdTherapists=[],
                  existingTherapists=[p["existing"]["id"] for p in teacher_plans if p["existing"]],
                  contentStatus="unchanged", changedFields=[], backup=str(backup))
    write_json(backup / "report.json", report)
    print("Backup: " + str(backup))
    try:
        if api.get("/api/admin/content") != draft or api.get("/api/admin/therapists") != therapists or api.get("/api/admin/stores") != stores:
            raise RuntimeError("Data changed during preview; rerun after other edits finish")
        for asset in data["assets"]:
            if asset["sha256"] not in found:
                content = asset_file(dataset_dir, asset).read_bytes()
                uploaded = upload_asset(api, asset, content)
                if hashlib.sha256(api.get(uploaded["url"], binary=True)).hexdigest() != asset["sha256"]:
                    raise RuntimeError("Uploaded image verification failed: " + asset["id"])
                found[asset["sha256"]] = uploaded
                report["uploaded"].append(dict(sourceId=asset["id"], **uploaded))
                write_json(backup / "report.json", report)
            mapping[asset["url"]] = found[asset["sha256"]]["url"]
        merged = fill_missing(site_fields(draft), rewrite_images(data["content"], mapping))
        report["changedFields"] = [k for k in CONTENT_FIELDS if merged.get(k) != draft.get(k)]
        if report["changedFields"]:
            if api.get("/api/admin/content") != draft:
                raise RuntimeError("Brand draft changed during image upload; no draft was overwritten")
            mode = "draft" if unpublished else "publish"
            expected = api.request("PUT", "/api/admin/content/" + mode, dict(merged, version=draft["version"]))
            report["contentStatus"] = mode
            write_json(backup / "report.json", report)
            if site_fields(api.get("/api/admin/content")) != site_fields(expected):
                raise RuntimeError("Brand content verification failed")
            if not unpublished and site_fields(api.get("/api/site")) != site_fields(expected):
                raise RuntimeError("Published content verification failed")
        for plan in teacher_plans:
            if plan["existing"]:
                continue
            source, payload = plan["source"], copy.deepcopy(plan["payload"])
            payload["image"] = mapping[source["image"]]
            current = api.get("/api/admin/therapists")
            if match_therapist(source, payload["storeId"], current):
                raise RuntimeError("Sample therapist created concurrently; rerun without duplicates")
            api.request("PUT", "/api/admin/therapists", payload)
            actual = match_therapist(source, payload["storeId"], api.get("/api/admin/therapists"))
            if not actual or any(actual.get(k) != v for k, v in payload.items()):
                raise RuntimeError("Sample therapist verification failed: " + source["id"])
            report["createdTherapists"].append(dict(id=actual["id"], name=actual["name"], storeId=actual["storeId"]))
            write_json(backup / "report.json", report)
        # Confirm that this additive import kept earlier work intact.
        if api.get("/api/admin/stores") != stores:
            raise RuntimeError("Store profiles changed concurrently; inspect backup and report")
        for sid in services:
            path = "/api/admin/stores/" + urllib.parse.quote(sid, safe="")
            if api.get(path + "/services") != services[sid] or api.get(path + "/rules") != rules[sid]:
                raise RuntimeError("Branch items/rules changed concurrently: " + sid)
        after = {t["id"]: t for t in api.get("/api/admin/therapists")}
        if any(after.get(t["id"]) != t for t in therapists):
            raise RuntimeError("An existing therapist changed; inspect backup and report")
        report.update(status="verified", sampleCount=len(teacher_plans), sourceAssets=len(data["assets"]))
    except Exception as error:
        report.update(status="incomplete", error=str(error))
        raise
    finally:
        write_json(backup / "report.json", report)
    print("Verified: %d sample therapists created, %d preserved; %d new assets; brand content=%s. Branch data preserved." %
          (len(report["createdTherapists"]), len(report["existingTherapists"]), len(report["uploaded"]), report["contentStatus"]))
    return report


def main():
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default="http://127.0.0.1:8790")
    parser.add_argument("--username", default="admin")
    parser.add_argument("--dataset", type=Path, default=DEFAULT_DATASET)
    parser.add_argument("--backup-dir", type=Path)
    parser.add_argument("--validate-only", action="store_true")
    parser.add_argument("--apply", action="store_true")
    args = parser.parse_args()
    data = load_dataset(args.dataset)
    if args.validate_only:
        print("Content dataset and 33 asset checksums valid; no server connection made.")
        return
    api = Api(args.base_url)
    api.token = os.environ.get("YUHETANG_ADMIN_TOKEN", "")
    if not api.token:
        password = getpass.getpass("Headquarters administrator password: ")
        api.token = api.request("POST", "/api/admin/login", dict(username=args.username, password=password))["token"]
    initialize(api, data, args.dataset.resolve().parent, args.apply, args.backup_dir)


if __name__ == "__main__":
    try:
        main()
    except (ValueError, RuntimeError, OSError, KeyError) as error:
        print("Supplement stopped: " + str(error), file=sys.stderr)
        sys.exit(1)

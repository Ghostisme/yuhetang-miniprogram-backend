#!/usr/bin/env python3
"""Snapshot the current local sample content, excluding business records and store catalogs."""
import argparse
import copy
import datetime as dt
import hashlib
import importlib.util
import json
import os
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location("local_content_initializer", Path(__file__).with_name("initialize-local-content.py"))
MOD = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MOD)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default="http://127.0.0.1:8790")
    parser.add_argument("--output", type=Path, default=MOD.DEFAULT_DATASET)
    args = parser.parse_args()
    api = MOD.Api(args.base_url)
    if api.get("/api/health").get("mode") != "local":
        parser.error("Source must be the local development instance")
    api.token = os.environ.get("YUHETANG_ADMIN_TOKEN", "")
    if not api.token:
        # The documented fixture login is used only after /health confirms local mode.
        api.token = api.request("POST", "/api/admin/login", {"username": "admin", "password": "Yht@9800dev"})["token"]
    site = api.get("/api/admin/content")
    seed = json.loads((ROOT / "src/main/resources/brand-seed.json").read_text(encoding="utf-8"))["site"]
    client = json.loads((ROOT / "initial-data/client-services-20261108/services.json").read_text(encoding="utf-8"))
    content = {key: copy.deepcopy(site[key]) for key in MOD.CONTENT_FIELDS if key in MOD.SOURCE_FIELDS and key in site}
    assets = []
    for source in seed["assetLibrary"]:
        body = api.get(source["url"], binary=True)
        filename = "media/" + Path(source["url"]).name
        if (ROOT / "src/main/resources/static" / filename).read_bytes() != body:
            raise ValueError("Local static image differs from tracked source: " + filename)
        assets.append(dict(source, file=filename, bytes=len(body), sha256=hashlib.sha256(body).hexdigest()))
    definitions = {s["id"]: {key: copy.deepcopy(s[key]) for key in ("name", "english", "aliases")}
                   for s in client["services"] if s["id"] in {"moxa", "massage", "foot", "guasha"}}
    stores = [{key: copy.deepcopy(s[key]) for key in ("id", "name", "aliases")} for s in client["stores"]]
    teachers = []
    for source in api.get("/api/admin/therapists"):
        if source.get("sample") is not True:
            continue
        teacher = {key: copy.deepcopy(source[key]) for key in
                   ("id", "storeId", "name", "sample", "grade", "specialty", "image", "enabled", "showOnClient", "resting")}
        teacher["skillKeys"] = [key for key in source["serviceIds"] if key in definitions]
        if not teacher["skillKeys"]:
            raise ValueError("Review the local example skill mapping: " + teacher["id"])
        teachers.append(teacher)
    data = dict(schemaVersion=1, datasetId="local-content-20261108", capturedAt=dt.datetime.now(dt.timezone.utc).isoformat(),
                source="current-local-admin-api", policy="fill-missing-only; preserve-existing-rows; labelled-sample-therapists",
                excluded=["storeProfiles", "branchServices", "branchMenuImages", "bookingRules", "customers", "appointments",
                          "conversations", "accounts", "roles", "tokens", "notificationConfiguration"],
                content=content, assets=assets, stores=stores, skillDefinitions=definitions, therapists=teachers)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    MOD.load_dataset(args.output)
    print(json.dumps(dict(file=str(args.output), services=len(content["services"]), products=len(content["products"]),
                          albums=len(content["galleryGroups"]), assets=len(assets), sampleTherapists=len(teachers)), ensure_ascii=False))


if __name__ == "__main__":
    main()

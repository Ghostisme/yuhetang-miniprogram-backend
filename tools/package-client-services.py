#!/usr/bin/env python3
"""Package reviewed client menus and the API importer without application data."""
import argparse
import datetime as dt
import hashlib
import importlib.util
import json
from pathlib import Path
import re
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--release-id", default=dt.datetime.now().strftime("%Y%m%d-%H%M%S"))
    parser.add_argument("--output-dir", type=Path, default=ROOT / "releases")
    args = parser.parse_args()
    if not re.fullmatch(r"\d{8}-\d{6}", args.release_id):
        parser.error("release-id must be YYYYMMDD-HHMMSS")
    spec = importlib.util.spec_from_file_location("client_importer", ROOT / "tools/initialize-client-services.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    dataset = ROOT / "initial-data/client-services-20261108"
    data = module.load_dataset(dataset / "services.json")
    payload = {}
    for name in ("initialize-client-services.py", "initialize-official-stores.py"):
        payload["tools/" + name] = (ROOT / "tools" / name).read_text(encoding="utf-8-sig").replace("\r\n", "\n").encode("utf-8")
    for path in dataset.rglob("*"):
        if path.is_file():
            payload["initial-data/client-services-20261108/" + path.relative_to(dataset).as_posix()] = path.read_bytes()
    guide = (ROOT / "docs/客户品项独立初始化.md").read_text(encoding="utf-8").replace("@RELEASE_ID@", args.release_id).replace("@ACTIVATION_ID@", args.release_id.split("-")[1])
    payload["README.md"] = guide.encode("utf-8")
    counts = dict(stores=len(data["stores"]), items=sum(len(s["items"]) for s in data["stores"]),
                  active=sum(i["enabled"] for s in data["stores"] for i in s["items"]),
                  pending=sum(not i["enabled"] for s in data["stores"] for i in s["items"]))
    metadata = dict(releaseId=args.release_id, datasetId=data["datasetId"], **counts,
                    sourceCommit=subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip(),
                    sourceWorkingTreeDirty=bool(subprocess.check_output(["git", "status", "--porcelain"], cwd=ROOT, text=True).strip()),
                    sourcePolicy=data["sourcePolicy"], independentStoreRecords=True,
                    secretsIncluded=False, databaseDataIncluded=False, cloudImportPerformed=False)
    payload["release.json"] = (json.dumps(metadata, ensure_ascii=False, indent=2) + "\n").encode("utf-8")
    digest = lambda content: hashlib.sha256(content).hexdigest()
    payload["SHA256SUMS"] = "".join(digest(content) + "  " + name + "\n" for name, content in sorted(payload.items())).encode("utf-8")
    prefix = "yuhetang-client-services-" + args.release_id
    args.output_dir.mkdir(parents=True, exist_ok=True)
    output = args.output_dir / (prefix + ".zip")
    checksum = output.with_name(output.name + ".sha256")
    external_guide = args.output_dir / (prefix + ".README.md")
    if any(p.exists() for p in (output, checksum, external_guide)):
        parser.error("Immutable release already exists")
    with zipfile.ZipFile(output, "x", zipfile.ZIP_DEFLATED, compresslevel=6) as archive:
        for name, content in sorted(payload.items()):
            member = zipfile.ZipInfo(prefix + "/" + name)
            member.compress_type = zipfile.ZIP_DEFLATED
            member.external_attr = 0o100644 << 16
            archive.writestr(member, content)
    with zipfile.ZipFile(output) as archive:
        assert len(archive.namelist()) == len(payload)
        for name, content in payload.items():
            assert archive.read(prefix + "/" + name) == content, name
    checksum.write_text(digest(output.read_bytes()) + "  " + output.name + "\n", encoding="ascii", newline="\n")
    external_guide.write_text(guide, encoding="utf-8", newline="\n")
    print(json.dumps(dict(status="verified", file=str(output), bytes=output.stat().st_size, **metadata), ensure_ascii=False))


if __name__ == "__main__":
    main()

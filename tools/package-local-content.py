#!/usr/bin/env python3
"""Bundle the checked additive content importer and its images, without application data."""
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
    parser.add_argument("--validation-file", type=Path, help="Optional current real-API verification result to include")
    args = parser.parse_args()
    if not re.fullmatch(r"\d{8}-\d{6}", args.release_id):
        parser.error("release-id must be YYYYMMDD-HHMMSS")
    spec = importlib.util.spec_from_file_location("content_importer", ROOT / "tools/initialize-local-content.py")
    importer = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(importer)
    data = importer.load_dataset(importer.DEFAULT_DATASET)
    payload = {}
    for name in ("initialize-local-content.py", "initialize-official-stores.py"):
        payload["tools/" + name] = (ROOT / "tools" / name).read_text(encoding="utf-8-sig").replace("\r\n", "\n").encode("utf-8")
    dataset_prefix = "initial-data/local-content-20261108/"
    payload[dataset_prefix + "content.json"] = importer.DEFAULT_DATASET.read_bytes()
    for asset in data["assets"]:
        payload[dataset_prefix + asset["file"]] = importer.asset_file(importer.DEFAULT_DATASET.parent, asset).read_bytes()
    guide = (ROOT / "docs/本地示例内容补充.md").read_text(encoding="utf-8").replace("@RELEASE_ID@", args.release_id)
    payload["README.md"] = guide.encode("utf-8")
    metadata = dict(releaseId=args.release_id, datasetId=data["datasetId"], minimumJavaRelease="20261108-125710",
                    sourceCommit=subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip(),
                    sourceWorkingTreeDirty=bool(subprocess.check_output(["git", "status", "--porcelain"], cwd=ROOT, text=True).strip()),
                    counts=dict(brandServices=len(data["content"]["services"]), products=len(data["content"]["products"]),
                                albums=len(data["content"]["galleryGroups"]), assets=len(data["assets"]), sampleTherapists=len(data["therapists"])),
                    fillMissingOnly=True, existingDraftsPreserved=True, includedCustomerRecords=0, includedAppointments=0,
                    secretsIncluded=False, databaseFilesIncluded=False, cloudImportPerformed=False)
    payload["release.json"] = (json.dumps(metadata, ensure_ascii=False, indent=2) + "\n").encode("utf-8")
    if args.validation_file:
        validation = json.loads(args.validation_file.read_text(encoding="utf-8"))
        if validation.get("status") != "verified" or validation.get("sourceCounts") != metadata["counts"]:
            parser.error("Verification counts do not match the dataset")
        payload["validation.json"] = (json.dumps(validation, ensure_ascii=False, indent=2) + "\n").encode("utf-8")
    digest = lambda body: hashlib.sha256(body).hexdigest()
    payload["SHA256SUMS"] = "".join(digest(body) + "  " + name + "\n" for name, body in sorted(payload.items())).encode("utf-8")
    prefix = "yuhetang-local-content-" + args.release_id
    args.output_dir.mkdir(parents=True, exist_ok=True)
    output = args.output_dir / (prefix + ".zip")
    checksum = output.with_name(output.name + ".sha256")
    external_guide = args.output_dir / (prefix + ".README.md")
    if any(path.exists() for path in (output, checksum, external_guide)):
        parser.error("Immutable release already exists")
    with zipfile.ZipFile(output, "x", zipfile.ZIP_DEFLATED, compresslevel=6) as archive:
        for name, body in sorted(payload.items()):
            info = zipfile.ZipInfo(prefix + "/" + name)
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = 0o100644 << 16
            archive.writestr(info, body)
    with zipfile.ZipFile(output) as archive:
        assert len(archive.namelist()) == len(payload)
        for name, body in payload.items():
            assert archive.read(prefix + "/" + name) == body, name
    checksum.write_text(digest(output.read_bytes()) + "  " + output.name + "\n", encoding="ascii", newline="\n")
    external_guide.write_text(guide, encoding="utf-8", newline="\n")
    print(json.dumps(dict(status="verified", file=str(output), bytes=output.stat().st_size, **metadata), ensure_ascii=False))


if __name__ == "__main__":
    main()

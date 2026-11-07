"""Release a standalone Java JAR and a Java-only Docker build context.

Run mvn -B -ntp -Papi-release verify first. No frontend, external runtime
archive, database, server certificate, or real configuration is read.
"""
import argparse
import datetime as dt
import hashlib
import io
import json
from pathlib import Path
import re
import shutil
import subprocess
import tarfile
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parent.parent


def sha(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--release-id", default=dt.datetime.now().strftime("%Y%m%d-%H%M%S"))
    arguments = parser.parse_args()
    release = arguments.release_id
    if not re.fullmatch(r"\d{8}-\d{6}", release):
        parser.error("release-id must be YYYYMMDD-HHMMSS")
    build = ROOT / "target-api"
    jar = build / "reservation-api-1.0.0.jar"
    counts = dict.fromkeys(("tests", "failures", "errors", "skipped"), 0)
    reports = sorted((build / "surefire-reports").glob("TEST-*.xml"))
    if not jar.is_file() or not reports:
        parser.error("Run mvn -B -ntp -Papi-release verify first")
    for path in reports:
        suite = ET.parse(path).getroot()
        for key in counts:
            counts[key] += int(suite.attrib.get(key, 0))
    if counts["tests"] < 100 or any(counts[key] for key in ("failures", "errors", "skipped")):
        parser.error("API verification reports must have at least 100 passing tests: " + str(counts))
    production = ET.parse(build / "surefire-reports/TEST-com.yuwatang.reservation.ProductionBootstrapTest.xml").getroot()
    if int(production.attrib["tests"]) < 7:
        parser.error("Production empty bootstrap/preservation verification is required")
    with zipfile.ZipFile(jar) as archive:
        names = archive.namelist()
        assert "JarLauncher" in archive.read("META-INF/MANIFEST.MF").decode()
        assert "BOOT-INF/classes/static/index.html" not in names
        assert not any(name.startswith("BOOT-INF/classes/static/assets/") for name in names)
        assert "BOOT-INF/classes/application-production.properties" in names
        application = archive.read("BOOT-INF/classes/com/yuwatang/reservation/PublicApi.class")
        assert application[:4] == b"\xca\xfe\xba\xbe" and int.from_bytes(application[6:8], "big") == 61
        migrations = [name for name in names if re.fullmatch(r"BOOT-INF/classes/db/migration/V\d+__.+\.sql", name)]
        assert len(migrations) >= 6
        assert any(re.fullmatch(r"BOOT-INF/lib/mariadb-java-client-.+\.jar", name) for name in names)
        media = ROOT / "src/main/resources/static/media"
        for source in media.rglob("*"):
            if source.is_file():
                name = "BOOT-INF/classes/static/media/" + source.relative_to(media).as_posix()
                assert archive.read(name) == source.read_bytes(), name
    source_files = [ROOT / "pom.xml"] + [path for path in (ROOT / "src").rglob("*") if path.is_file()]
    newest_source = max(path.stat().st_mtime for path in source_files)
    assert jar.stat().st_mtime >= newest_source, "Rebuild the API: source files are newer than the JAR"
    assert all(path.stat().st_mtime >= newest_source for path in reports), "Rerun API verification: source files are newer than the test reports"

    deployment = ROOT / "deploy/docker-java"
    files = {name: deployment / name for name in
             ("Dockerfile", ".dockerignore", "entrypoint.sh", "healthcheck.sh", "run.sh", "export-env.py", "README.md")}
    files["check-db-target.sh"] = ROOT / "deploy/tencent-isolated/check-db-target.sh"
    payload = {name: path.read_text(encoding="utf-8-sig").replace("\r\n", "\n").replace(
        "@RELEASE_ID@", release).encode("utf-8") for name, path in files.items()}
    payload["reservation.jar"] = jar.read_bytes()
    commit = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip()
    dirty = bool(subprocess.check_output(["git", "status", "--porcelain"], cwd=ROOT, text=True).strip())
    validation = {
        "createdAt": dt.datetime.now(dt.timezone.utc).isoformat(),
        "releaseId": release, "sourceCommit": commit, "sourceWorkingTreeDirty": dirty,
        "deployment": "Java-only Docker application, existing MySQL and gateway managed separately",
        "java": 17, "backendTests": counts,
        "productionBootstrapTests": [case.attrib["name"] for case in production.findall("testcase")],
        "migrationFiles": len(migrations), "backendJarSha256": sha(jar),
        "frontendIncluded": False, "openrestyIncluded": False, "nginxIncluded": False,
        "databaseInstallerIncluded": False, "databaseDataIncluded": False,
        "tlsPrivateKeyIncluded": False, "realCredentialsIncluded": False,
        "existingDatabase": "127.0.0.1:3307/yuwatang_reservation; not accessed by packager",
        "dockerImageBuild": "not performed; requires target Docker engine",
        "dockerContainerRuntime": "not performed; requires target Docker engine",
        "cloudDeployment": "not performed", "realWechatDelivery": "not performed",
        "capacity50000ConcurrentUsers": "not validated",
    }
    payload["validation.json"] = (json.dumps(validation, ensure_ascii=False, indent=2) + "\n").encode("utf-8")
    payload["SHA256SUMS"] = "".join(hashlib.sha256(data).hexdigest() + "  " + name + "\n"
                                    for name, data in sorted(payload.items())).encode("ascii")
    releases = ROOT / "releases"
    releases.mkdir(exist_ok=True)
    name = "yuhetang-java-docker-" + release
    archive_path = releases / (name + ".tar.gz")
    jar_path = releases / ("yuhetang-java-api-" + release + ".jar")
    report = releases / (name + ".validation.json")
    guide = releases / (name + ".README.md")
    destinations = [archive_path, jar_path, report, guide] + [Path(str(path) + ".sha256") for path in (archive_path, jar_path)]
    assert not any(path.exists() for path in destinations), "Immutable release already exists"
    with jar.open("rb") as source, jar_path.open("xb") as target:
        shutil.copyfileobj(source, target)
    with tarfile.open(archive_path, "x:gz", compresslevel=6) as archive:
        for filename, data in sorted(payload.items()):
            member = tarfile.TarInfo(name + "/" + filename)
            member.size = len(data)
            member.mode = 0o755 if filename.endswith(".sh") else 0o644
            member.mtime = int(dt.datetime.now(dt.timezone.utc).timestamp())
            archive.addfile(member, io.BytesIO(data))
    # Inspect the delivered archive, including exact bytes and Unix permissions.
    with tarfile.open(archive_path, "r:gz") as archive:
        assert sorted(archive.getnames()) == sorted(name + "/" + filename for filename in payload)
        for filename, data in payload.items():
            member = archive.getmember(name + "/" + filename)
            assert member.isfile() and member.mode == (0o755 if filename.endswith(".sh") else 0o644)
            assert archive.extractfile(member).read() == data, filename
    assert sha(jar_path) == sha(jar)
    artifacts = []
    for path in (jar_path, archive_path):
        digest = sha(path)
        Path(str(path) + ".sha256").write_text(digest + "  " + path.name + "\n", encoding="ascii")
        artifacts.append({"file": str(path), "bytes": path.stat().st_size, "sha256": digest})
    validation["artifacts"] = artifacts
    report.write_text(json.dumps(validation, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    guide.write_bytes(payload["README.md"])
    print(json.dumps({"status": "verified", "artifacts": artifacts, "report": str(report), "guide": str(guide)}, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()

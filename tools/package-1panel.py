"""Package independent static frontend and Java API releases for 1Panel.

Run npm run build, npm run check:mini, mvn -Papi-release verify,
tools/verify-tencent-isolation.py and tools/verify-1panel-split.py first.
Explicit allowlists exclude business data, credentials and TLS private keys.
"""
import argparse
import datetime as dt
import hashlib
import io
import json
from pathlib import Path
import re
import stat
import tarfile
import xml.etree.ElementTree as ET
import zipfile


ROOT = Path(__file__).resolve().parent.parent
ADMIN_ROOT = ROOT.parent / "yuhetang-miniprogram-admin"
JAVA_SHA = "9f41bf88c7900c7097444a1bdeb84146ae692bc08842490ab098063c74bf9a8a"


def sha(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def normalized(path):
    data = path.read_bytes()
    assert data, str(path)
    if path.suffix not in (".jar", ".gz"):
        data = data.decode("utf-8-sig").replace("\r\n", "\n").encode("utf-8")
    return data


def checksums(payload):
    return "".join(hashlib.sha256(data).hexdigest() + "  " + name + "\n"
                   for name, data in sorted(payload.items())).encode("ascii")


def write_frontend_zip(path, payload):
    # writestr(name, data) defaults to mode 0600. Explicit Unix permissions keep
    # root-extracted static files readable by OpenResty's unprivileged workers.
    timestamp = dt.datetime.now().timetuple()[:6]
    with zipfile.ZipFile(path, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=6) as archive:
        for name, data in payload.items():
            info = zipfile.ZipInfo(name, date_time=timestamp)
            info.create_system = 3
            info.external_attr = (stat.S_IFREG | 0o644) << 16
            archive.writestr(info, data, compress_type=zipfile.ZIP_DEFLATED, compresslevel=6)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--release-date", default=dt.date.today().strftime("%Y%m%d"))
    args = parser.parse_args()
    if not re.fullmatch(r"\d{8}", args.release_date):
        parser.error("release-date must be YYYYMMDD")
    panel = ROOT / "deploy/1panel"
    isolated = ROOT / "deploy/tencent-isolated"
    host = ROOT / "deploy/tencent-host"
    build = ROOT / "target-api"
    jar = build / "reservation-api-1.0.0.jar"
    java = ROOT / "backups/tencent-java17-runtime/java17-linux-x64.tar.gz"
    assert sha(java) == JAVA_SHA, "Private Java 17 runtime checksum mismatch"
    with zipfile.ZipFile(jar) as archive:
        names = archive.namelist()
        assert "JarLauncher" in archive.read("META-INF/MANIFEST.MF").decode()
        assert "BOOT-INF/classes/static/index.html" not in names
        assert not any(name.startswith("BOOT-INF/classes/static/assets/") for name in names), "API must not embed the frontend"
        assert "BOOT-INF/classes/com/yuwatang/reservation/PublicApi.class" in names
        assert "BOOT-INF/classes/application-production.properties" in names
        assert len([n for n in names if re.fullmatch(r"BOOT-INF/classes/db/migration/V\d+__.+\.sql", n)]) == 6
        assert any(n.endswith("mariadb-java-client-3.5.8.jar") for n in names)
        for media in (ADMIN_ROOT / "public/media").rglob("*"):
            if media.is_file():
                name = "BOOT-INF/classes/static/media/" + media.relative_to(ADMIN_ROOT / "public/media").as_posix()
                assert archive.read(name) == media.read_bytes(), name

    counts = {name: 0 for name in ("tests", "failures", "errors", "skipped")}
    reports = sorted((build / "surefire-reports").glob("TEST-*.xml"))
    assert reports, "Run api-release verify first"
    for path in reports:
        suite = ET.parse(path).getroot()
        for name in counts:
            counts[name] += int(suite.attrib.get(name, 0))
    assert counts["tests"] >= 100 and not any(counts[n] for n in ("failures", "errors", "skipped")), counts
    fresh = ET.parse(build / "surefire-reports/TEST-com.yuwatang.reservation.ProductionBootstrapTest.xml").getroot()
    assert int(fresh.attrib["tests"]) >= 7
    isolation = json.loads((ROOT / "backups/tencent-isolated-script-verification/result.json").read_text("utf-8"))
    assert isolation["status"] == "passed" and isolation["containerRuntime"] == "not performed"
    split = json.loads((ROOT / "backups/1panel-split-verification/result.json").read_text("utf-8"))
    assert split["status"] == "passed" and split["jarSha256"] == sha(jar)
    assert split["frontendIndexSha256"] == sha(ADMIN_ROOT / "dist/index.html")
    for name in ("admin", "booking"):
        assert split["snippetsSha256"][name] == sha(panel / (name + ".locations.conf"))
    assert '"result": "PASS"' in (ROOT / ("logs/1panel-mini-check-" + args.release_date + ".log")).read_text("utf-8-sig")
    environment = normalized(isolated / "env.example").decode()
    for name in ("DB_PASSWORD", "JWT_SECRET", "ADMIN_PASSWORD", "WECHAT_APP_SECRET", "WECHAT_MESSAGE_TOKEN",
                 "WECHAT_MESSAGE_AES_KEY", "SERVICE_APP_SECRET", "SMS_SECRET_ID", "SMS_SECRET_KEY"):
        assert re.search(r"^" + name + r"=$", environment, re.MULTILINE), "Real credential must not be packaged: " + name

    # The frontend ZIP root is the website's document root, without a dist wrapper.
    frontend = {}
    for path in sorted((ADMIN_ROOT / "dist").rglob("*")):
        if path.is_file():
            name = path.relative_to(ADMIN_ROOT / "dist").as_posix()
            assert name in ("index.html", "favicon.svg") or name.startswith(("assets/", "media/")), name
            assert not path.is_symlink() and not name.endswith((".map", ".env", ".key", ".pem")), name
            frontend[name] = path.read_bytes()
    assert "index.html" in frontend and any(n.startswith("assets/") for n in frontend)
    for asset in re.findall(r'(?:src|href)="(/assets/[^"?]+)', frontend["index.html"].decode()):
        assert asset.lstrip("/") in frontend, "Missing referenced frontend asset: " + asset

    validation = {
        "createdAt": dt.datetime.now(dt.timezone.utc).isoformat(),
        "deployment": "1Panel OpenResty static frontend + standalone systemd Java API + dedicated Docker MySQL",
        "target": {"os": "Ubuntu 26.04 LTS", "arch": "x86_64", "host": "43.142.125.157",
                   "admin": "https://mp-admin.yuhetangtcm.com/admin", "api": "https://booking.yuhetangtcm.com",
                   "database": "MySQL 8.4 dedicated Docker container, loopback 3307", "java": "private Temurin 17.0.21+9"},
        "backendTests": counts,
        "emptyProductionBootstrapTests": [case.attrib["name"] for case in fresh.findall("testcase")],
        "productionData": "system initialization only; no existing local business database or demo seed packaged",
        "backendJarSha256": sha(jar), "frontendIndexSha256": sha(ADMIN_ROOT / "dist/index.html"),
        "javaRuntimeSha256": JAVA_SHA, "miniProgramChecks": "passed; Developer Tools/device test pending",
        "isolationScriptChecks": isolation, "independentFrontendApiVerification": split,
        "tlsPrivateKeyIncluded": False, "cloudDeployment": "not performed", "mysql84Runtime": "not performed",
        "realWechatDelivery": "not performed", "capacity50000ConcurrentUsers": "not validated",
    }
    files = {"reservation.jar": jar, "java17-linux-x64.tar.gz": java, "README.md": panel / "README.md",
             "prepare.sh": panel / "prepare.sh", "prepare-app.sh": host / "prepare.sh",
             "env.example": isolated / "env.example", "preflight.sh": isolated / "preflight.sh",
             "database.sh": isolated / "database.sh", "check-db-target.sh": isolated / "check-db-target.sh",
             "mysql-image.txt": isolated / "mysql-image.txt", "wechat-templates.json": host / "wechat-templates.json",
             "admin.locations.conf": panel / "admin.locations.conf", "booking.locations.conf": panel / "booking.locations.conf"}
    payload = {name: normalized(path) for name, path in files.items()}
    unit = normalized(isolated / "yuwatang-reservation.service").decode().replace(
        "Yuhetang reservation API and admin (isolated database)", "Yuhetang Java API (1Panel website proxy)")
    payload["yuwatang-reservation.service"] = unit.encode()
    payload["validation.json"] = (json.dumps(validation, ensure_ascii=False, indent=2) + "\n").encode()
    payload["SHA256SUMS"] = checksums(payload)
    frontend["SHA256SUMS"] = checksums(frontend)

    releases = ROOT / "releases"
    releases.mkdir(exist_ok=True)
    frontend_path = releases / ("yuhetang-admin-" + args.release_date + ".zip")
    backend_name = "yuhetang-java-api-1panel-" + args.release_date
    backend_path = releases / (backend_name + ".tar.gz")
    report_path = releases / ("yuhetang-1panel-" + args.release_date + ".validation.json")
    for path in (frontend_path, backend_path, report_path):
        assert not path.exists(), "Immutable release already exists: " + str(path)
    write_frontend_zip(frontend_path, frontend)
    with tarfile.open(backend_path, "w:gz", compresslevel=6) as archive:
        for name, data in payload.items():
            info = tarfile.TarInfo(backend_name + "/" + name)
            info.size = len(data)
            info.mode = 0o755 if name.endswith(".sh") else 0o644
            info.mtime = int(dt.datetime.now(dt.timezone.utc).timestamp())
            archive.addfile(info, io.BytesIO(data))
    with zipfile.ZipFile(frontend_path) as archive:
        assert sorted(archive.namelist()) == sorted(frontend)
        for name, data in frontend.items():
            assert archive.read(name) == data
            entry = archive.getinfo(name)
            assert entry.create_system == 3 and entry.external_attr >> 16 == stat.S_IFREG | 0o644, name
    with tarfile.open(backend_path, "r:gz") as archive:
        assert sorted(m.name for m in archive) == sorted(backend_name + "/" + name for name in payload)
        for name, data in payload.items():
            assert archive.extractfile(backend_name + "/" + name).read() == data
    artifacts = []
    for path in (frontend_path, backend_path):
        digest = sha(path)
        path.with_suffix(path.suffix + ".sha256").write_text(digest + "  " + path.name + "\n", encoding="ascii")
        artifacts.append({"file": path.name, "bytes": path.stat().st_size, "sha256": digest})
    validation["artifacts"] = artifacts
    report_path.write_text(json.dumps(validation, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"status": "verified", "artifacts": artifacts, "report": report_path.name}, ensure_ascii=True, indent=2))


if __name__ == "__main__":
    main()

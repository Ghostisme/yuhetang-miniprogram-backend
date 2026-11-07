"""Build only the Java API installation archive for 1Panel.

Run mvn -B -ntp -Papi-release verify first. No frontend source, build,
node_modules, business database or credentials are read by this packager.
"""
import argparse
import datetime as dt
import hashlib
import io
import json
from pathlib import Path
import re
import tarfile
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parent.parent
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


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--release-id", default=dt.datetime.now().strftime("%Y%m%d-%H%M%S"))
    parser.add_argument("--release-date", help="Compatibility alias for YYYYMMDD release identifiers")
    parser.add_argument("--java-runtime", type=Path,
                        default=ROOT / "backups/tencent-java17-runtime/java17-linux-x64.tar.gz")
    args = parser.parse_args()
    release_id = args.release_date or args.release_id
    if not re.fullmatch(r"\d{8}(?:-\d{6})?", release_id):
        parser.error("release-id must be YYYYMMDD or YYYYMMDD-HHMMSS")
    panel, isolated, host = (ROOT / ("deploy/" + name) for name in ("1panel", "tencent-isolated", "tencent-host"))
    build = ROOT / "target-api"
    jar = build / "reservation-api-1.0.0.jar"
    java = args.java_runtime.resolve()
    assert sha(java) == JAVA_SHA, "Private Java 17 runtime checksum mismatch"
    with zipfile.ZipFile(jar) as archive:
        names = archive.namelist()
        assert "JarLauncher" in archive.read("META-INF/MANIFEST.MF").decode()
        assert "BOOT-INF/classes/static/index.html" not in names
        assert not any(n.startswith("BOOT-INF/classes/static/assets/") for n in names), "API must not embed the admin"
        assert "BOOT-INF/classes/com/yuwatang/reservation/PublicApi.class" in names
        assert "BOOT-INF/classes/application-production.properties" in names
        assert len([n for n in names if re.fullmatch(r"BOOT-INF/classes/db/migration/V\d+__.+\.sql", n)]) == 6
        assert any(n.endswith("mariadb-java-client-3.5.8.jar") for n in names)
        media_root = ROOT / "src/main/resources/static/media"
        for media in media_root.rglob("*"):
            if media.is_file():
                name = "BOOT-INF/classes/static/media/" + media.relative_to(media_root).as_posix()
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
    environment = normalized(isolated / "env.example").decode()
    for name in ("DB_PASSWORD", "JWT_SECRET", "ADMIN_PASSWORD", "WECHAT_APP_SECRET", "WECHAT_MESSAGE_TOKEN",
                 "WECHAT_MESSAGE_AES_KEY", "SERVICE_APP_SECRET", "SMS_SECRET_ID", "SMS_SECRET_KEY"):
        assert re.search(r"^" + name + r"=$", environment, re.MULTILINE), "Credential must be blank: " + name

    validation = {
        "createdAt": dt.datetime.now(dt.timezone.utc).isoformat(),
        "deployment": "standalone systemd Java API + dedicated Docker MySQL; frontend released separately",
        "target": {"os": "Ubuntu 26.04 LTS", "arch": "x86_64", "api": "https://booking.yuhetangtcm.com",
                   "callback": "https://booking.hetuonian.cn/api/wechat/customer-service",
                   "database": "MySQL 8.4 dedicated Docker container, loopback 3307",
                   "java": "private Temurin 17.0.21+9"},
        "backendTests": counts,
        "emptyProductionBootstrapTests": [case.attrib["name"] for case in fresh.findall("testcase")],
        "productionData": "system initialization only; no business database or demo seed packaged",
        "backendJarSha256": sha(jar), "javaRuntimeSha256": JAVA_SHA,
        "frontendIncluded": False, "frontendRepositoryRequired": False, "tlsPrivateKeyIncluded": False,
        "cloudDeployment": "not performed", "mysql84Runtime": "not performed",
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
    releases = ROOT / "releases"
    releases.mkdir(exist_ok=True)
    name = "yuhetang-java-api-1panel-" + release_id
    path = releases / (name + ".tar.gz")
    sidecar = path.with_suffix(path.suffix + ".sha256")
    report = releases / (name + ".validation.json")
    for target in (path, sidecar, report):
        assert not target.exists(), "Immutable release already exists: " + str(target)
    with tarfile.open(path, "w:gz", compresslevel=6) as archive:
        for filename, data in payload.items():
            info = tarfile.TarInfo(name + "/" + filename)
            info.size = len(data)
            info.mode = 0o755 if filename.endswith(".sh") else 0o644
            info.mtime = int(dt.datetime.now(dt.timezone.utc).timestamp())
            archive.addfile(info, io.BytesIO(data))
    with tarfile.open(path, "r:gz") as archive:
        assert sorted(m.name for m in archive) == sorted(name + "/" + n for n in payload)
        for filename, data in payload.items():
            member = archive.getmember(name + "/" + filename)
            assert member.mode == (0o755 if filename.endswith(".sh") else 0o644)
            assert archive.extractfile(member).read() == data
    digest = sha(path)
    sidecar.write_text(digest + "  " + path.name + "\n", encoding="ascii")
    validation["artifact"] = {"file": path.name, "bytes": path.stat().st_size, "sha256": digest}
    report.write_text(json.dumps(validation, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"status": "verified", "artifact": validation["artifact"], "report": str(report)}, indent=2))


if __name__ == "__main__":
    main()
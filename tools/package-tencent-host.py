"""Create a Tencent isolated, shared-MySQL terminal, or legacy Baota bundle.

Build: npm run build; cd backend; mvn -Prelease,cloud-release -DskipTests package
Package from the project root:
  python tools/package-tencent-host.py --release-date 20261105 --variant isolated --name-suffix clean
Only explicitly listed deployment files are included. Business data and secrets
are excluded. Every archive member and the archive itself receive SHA256 checks.
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


def sha256(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--release-date", default=dt.date.today().strftime("%Y%m%d"))
    parser.add_argument("--variant", choices=("isolated", "terminal", "baota"), default="isolated")
    parser.add_argument("--name-suffix", default="", help="Distinct immutable release name, e.g. clean")
    args = parser.parse_args()
    if not re.fullmatch(r"\d{8}", args.release_date):
        parser.error("release-date must be YYYYMMDD")
    if args.name_suffix and not re.fullmatch(r"[a-z0-9-]{1,32}", args.name_suffix):
        parser.error("name-suffix must contain 1-32 lowercase letters, digits or hyphens")
    root = Path(__file__).resolve().parent.parent
    admin_root = root.parent / "yuhetang-miniprogram-admin"
    host = root / "deploy/tencent-host"
    isolated = root / "deploy/tencent-isolated"
    configuration = isolated if args.variant == "isolated" else host
    jar = root / "target/reservation-cloud-1.0.0.jar"
    java = root / "backups/tencent-java17-runtime/java17-linux-x64.tar.gz"
    expected_java = "9f41bf88c7900c7097444a1bdeb84146ae692bc08842490ab098063c74bf9a8a"
    assert jar.is_file() and java.is_file(), "Build JAR and download the pinned Java runtime first"
    assert sha256(java) == expected_java, "Java runtime checksum mismatch"
    with zipfile.ZipFile(jar) as archive:
        assert "org.springframework.boot.loader.launch.JarLauncher" in archive.read("META-INF/MANIFEST.MF").decode("utf-8")
        assert archive.read("BOOT-INF/classes/static/index.html") == (admin_root / "dist/index.html").read_bytes()
        assert any(name.endswith("mariadb-java-client-3.5.8.jar") for name in archive.namelist())
        for asset in (admin_root / "dist/assets").iterdir():
            assert archive.read("BOOT-INF/classes/static/assets/" + asset.name) == asset.read_bytes()

    reports = list((root / "target/surefire-reports").glob("TEST-*.xml"))
    counts = {name: 0 for name in ("tests", "failures", "errors", "skipped")}
    for path in reports:
        result = ET.parse(path).getroot()
        for name in counts:
            counts[name] += int(result.attrib.get(name, 0))
    assert counts["tests"] >= 100 and not any(counts[name] for name in ("failures", "errors", "skipped")), counts
    fresh_report = root / "target/surefire-reports/TEST-com.yuwatang.reservation.ProductionBootstrapTest.xml"
    fresh = ET.parse(fresh_report).getroot()
    assert int(fresh.attrib["tests"]) >= 7
    assert all(int(fresh.attrib.get(name, 0)) == 0 for name in ("failures", "errors", "skipped"))
    mysql = json.loads((root / "backups/mysql57-isolated-verification/result.json").read_text("utf-8"))
    assert mysql["status"] == "passed" and mysql["mysqlVersion"] == "5.7.44"
    browser = json.loads((root / "backups/dual-wechat-admin-browser-final.json").read_text("utf-8"))
    assert browser["status"] == "passed" and browser["errors"] == []
    local = json.loads((root / "backups/local-before-wechat-20261105-180246/verification-final.json").read_text("utf-8"))
    assert local["status"] == "passed"
    assert '"result": "PASS"' in (root / "logs/wechat-mini-check-final.log").read_text("utf-8-sig")
    template = (configuration / "env.example").read_text("utf-8")
    for name in ("DB_PASSWORD", "JWT_SECRET", "ADMIN_PASSWORD", "WECHAT_APP_SECRET",
                 "WECHAT_MESSAGE_TOKEN", "WECHAT_MESSAGE_AES_KEY", "SERVICE_APP_SECRET",
                 "SMS_SECRET_ID", "SMS_SECRET_KEY"):
        assert re.search(r"^" + name + r"=$", template, re.MULTILINE), "Do not package real credentials: " + name

    validation = {
        "createdAt": dt.datetime.now(dt.timezone.utc).isoformat(),
        "target": {"host": "43.142.125.157", "os": "TencentOS Server 3.1 / x86_64",
                   "mysql": "8.4 LTS, dedicated container" if args.variant == "isolated" else "5.7.44, shared host instance",
                   "java": "private Eclipse Temurin 17.0.21+9 JRE",
                   "domain": "https://booking.yuhetangtcm.com",
                   "adminDomain": "https://mp-admin.yuhetangtcm.com/admin" if args.variant == "isolated" else None,
                   "ports": [3307, 8790, 8791] if args.variant == "isolated" else [8790, 8791]},
        "backendTests": counts,
        "historicalMysql57": {name: mysql[name] for name in ("status", "mysqlVersion", "jdbcDriver", "checks", "externalMessagesSent")},
        "historicalAdminBrowser": {name: browser[name] for name in ("status", "checks", "errors")},
        "historicalLocalDataPreservation": local,
        "historicalEvidenceScope": "Earlier checks; not a runtime validation of this rebuilt release",
        "productionInitialization": {
            "mode": "empty business database on first production start",
            "systemOnly": ["Flyway schema", "RBAC roles and permissions", "one headquarters administrator",
                           "empty site configuration", "configured WeChat template mappings", "internal locks and leases"],
            "seededBusinessRecords": 0,
            "localDatabaseMigration": "not included; user will import manually",
            "newStoreImports": "supported; online booking defaults to disabled",
            "restart": "keeps existing records and administrator/template edits",
            "tests": [case.attrib["name"] for case in fresh.findall("testcase")],
            "testDatabase": "isolated in-memory H2; not a MySQL 8.4 runtime test",
        },
        "miniProgramChecks": "passed; actual WeChat Developer Tools compilation and device checks pending",
        "frontend": "built assets and index verified byte-for-byte against packaged JAR",
        "jarSha256": sha256(jar),
        "javaRuntimeSha256": expected_java,
        "cloudDeployment": "not performed",
        "realWechatDelivery": "not performed",
        "capacity50000ConcurrentUsers": "not validated",
        "deploymentVariant": args.variant,
        "tlsPrivateKeyIncluded": False,
    }
    files = {
        "reservation.jar": jar,
        "java17-linux-x64.tar.gz": java,
        "README.md": isolated / "README.md" if args.variant == "isolated" else host / ("TERMINAL.md" if args.variant == "terminal" else "README.md"),
        "env.example": configuration / "env.example",
        "preflight.sh": configuration / "preflight.sh",
        "prepare.sh": configuration / "prepare.sh",
        "yuwatang-reservation.service": configuration / "yuwatang-reservation.service",
        "wechat-templates.json": host / "wechat-templates.json",
        "nginx.booking.conf.example": (host / "nginx.booking.conf.example"
                                       if args.variant != "baota"
                                       else root / "deploy/nginx.booking.conf.example"),
    }
    if args.variant == "isolated":
        evidence = root / "backups/tencent-isolated-script-verification/result.json"
        isolation = json.loads(evidence.read_text("utf-8"))
        assert isolation["status"] == "passed" and isolation["containerRuntime"] == "not performed"
        validation["isolationScriptChecks"] = isolation
        validation["mysql8ContainerRuntime"] = "not performed; historical MySQL 5.7 checks do not validate this runtime"
        for filename in ("database.sh", "check-db-target.sh", "mysql-image.txt"):
            files[filename] = isolated / filename
        files["nginx.mp-admin.conf.example"] = host / "nginx.mp-admin.conf.example"
        files["MP-ADMIN-DOMAIN.md"] = isolated / "MP-ADMIN-DOMAIN.md"
        files["CONTINUE-FROM-UPLOAD.md"] = isolated / "CONTINUE-FROM-UPLOAD.md"
        files["prepare-app.sh"] = host / "prepare.sh"
        files["isolation-validation.json"] = evidence
    name = "yuhetang-tencent-host-" + (args.variant + "-" if args.variant != "baota" else "") + args.release_date
    if args.name_suffix:
        name += "-" + args.name_suffix
    output = root / "releases" / (name + ".tar.gz")
    output.parent.mkdir(parents=True, exist_ok=True)
    assert not output.exists(), "Release already exists; choose a distinct release date/name"
    payload = {}
    for filename, path in files.items():
        data = path.read_bytes()
        assert data, str(path)
        if path.suffix not in (".jar", ".gz"):
            data = data.decode("utf-8-sig").replace("\r\n", "\n").encode("utf-8")
        payload[filename] = data
    payload["validation.json"] = (json.dumps(validation, ensure_ascii=False, indent=2) + "\n").encode("utf-8")
    checksums = {filename: hashlib.sha256(data).hexdigest() for filename, data in payload.items()}
    payload["SHA256SUMS"] = "".join(digest + "  " + filename + "\n" for filename, digest in sorted(checksums.items())).encode()
    with tarfile.open(output, "w:gz", compresslevel=6) as archive:
        for filename, data in payload.items():
            info = tarfile.TarInfo(name + "/" + filename)
            info.size = len(data)
            info.mode = 0o755 if filename.endswith(".sh") else 0o644
            info.mtime = int(dt.datetime.now(dt.timezone.utc).timestamp())
            archive.addfile(info, io.BytesIO(data))
    with tarfile.open(output, "r:gz") as archive:
        assert sorted(member.name for member in archive.getmembers()) == sorted(name + "/" + f for f in payload)
        for filename, digest in checksums.items():
            assert hashlib.sha256(archive.extractfile(name + "/" + filename).read()).hexdigest() == digest
    digest = sha256(output)
    output.with_suffix(output.suffix + ".sha256").write_text(digest + "  " + output.name + "\n", encoding="utf-8")
    (output.parent / (name + ".validation.json")).write_text(json.dumps(validation, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"archive": output.name, "bytes": output.stat().st_size,
                      "sha256": digest, "members": sorted(payload), "status": "verified"}))


if __name__ == "__main__":
    main()

"""Smoke-test the release on a private MySQL 5.7 Windows process, without OS services.

Usage: python tools/verify-mysql57.py --mysql-home PATH --java PATH
The supplied MySQL distribution is copied to a new temp directory. Only processes
created here are stopped. Existing database services and project data are unused.
"""
import argparse
import datetime as dt
import json
import os
from pathlib import Path
import secrets
import shutil
import socket
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request


def main():
    sys.stdout.reconfigure(encoding="utf-8", errors="backslashreplace")
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--mysql-home", required=True, type=Path)
    parser.add_argument("--java", required=True, type=Path)
    args = parser.parse_args()
    root = Path(__file__).resolve().parent.parent
    jar = root / "target/reservation-1.0.0.jar"
    assert jar.is_file() and args.java.is_file()
    for port in (13377, 18893, 18894):
        with socket.socket() as probe:
            probe.bind(("127.0.0.1", port))
    work = Path(tempfile.mkdtemp(prefix="yuhetang-mysql57-"))
    mysql_home = work / "mysql"
    (mysql_home / "bin").mkdir(parents=True)
    for name in ("mysqld.exe", "mysql.exe", "mysqladmin.exe"):
        shutil.copy2(args.mysql_home / "bin" / name, mysql_home / "bin" / name)
    for source in (args.mysql_home / "bin").glob("*.dll"):
        shutil.copy2(source, mysql_home / "bin" / source.name)
    shutil.copytree(args.mysql_home / "share", mysql_home / "share")
    data = work / "data"
    data.mkdir()
    common = ["--no-defaults", f"--basedir={mysql_home}", f"--datadir={data}"]
    init_log = (work / "initialize.log").open("wb")
    try:
        subprocess.run([str(mysql_home / "bin/mysqld.exe"), *common,
                        "--initialize-insecure", "--console"], check=True,
                       stdout=init_log, stderr=subprocess.STDOUT, timeout=90)
    finally:
        init_log.close()
    mysql_log = (work / "mysql.log").open("wb")
    app_log = (work / "application.log").open("wb")
    server = app = None
    report = {"workDirectory": str(work), "checks": [], "externalMessagesSent": 0}

    def sql(statement):
        result = subprocess.run(
            [str(mysql_home / "bin/mysql.exe"), "--no-defaults", "--skip-password", "--ssl-mode=DISABLED", "--protocol=TCP",
             "--host=127.0.0.1", "--port=13377", "--user=root",
             "--default-character-set=utf8mb4", "--batch", "--skip-column-names"],
            input=statement.encode("utf-8"), stdout=subprocess.PIPE,
            stderr=subprocess.PIPE, timeout=10)
        if result.returncode:
            raise RuntimeError(result.stderr.decode("utf-8", errors="replace"))
        return result.stdout.decode("utf-8").strip()

    def http(path, body=None, token=None, method=None, port=18893, key=None):
        headers = {"Content-Type": "application/json"}
        if token:
            headers["Authorization"] = "Bearer " + token
        if key:
            headers["Idempotency-Key"] = key
        req = urllib.request.Request(
            f"http://127.0.0.1:{port}" + path,
            data=json.dumps(body, ensure_ascii=False).encode("utf-8") if body is not None else None,
            headers=headers, method=method)
        with urllib.request.urlopen(req, timeout=8) as response:
            return json.load(response)

    def ready(fn, process, label):
        deadline = time.monotonic() + 90
        last_error = None
        while time.monotonic() < deadline:
            if process.poll() is not None:
                raise RuntimeError(label + " exited; inspect logs in " + str(work))
            try:
                return fn()
            except (OSError, RuntimeError, ValueError) as failure:
                last_error = failure
                time.sleep(0.3)
        raise RuntimeError(label + " startup timeout; inspect " + str(work)) from last_error

    try:
        server = subprocess.Popen(
            [str(mysql_home / "bin/mysqld.exe"), *common, "--console",
             "--bind-address=127.0.0.1", "--port=13377",
             "--innodb-buffer-pool-size=64M", "--max-connections=32",
             "--performance-schema=OFF", "--explicit-defaults-for-timestamp=ON"],
            cwd=work, stdout=mysql_log, stderr=subprocess.STDOUT)
        version = ready(lambda: sql("SELECT VERSION();"), server, "MySQL")
        assert version.startswith("5.7.44"), version
        report["mysqlVersion"] = version
        db_password = secrets.token_hex(24)
        sql("CREATE DATABASE yht_mysql57_fixture CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"
            f"CREATE USER 'yht_fixture'@'127.0.0.1' IDENTIFIED BY '{db_password}';"
            "GRANT ALL PRIVILEGES ON yht_mysql57_fixture.* TO 'yht_fixture'@'127.0.0.1';")
        env = os.environ.copy()
        for line in (root / "deploy/tencent-host/env.example").read_text("utf-8").splitlines():
            if line and not line.startswith("#") and "=" in line:
                name, value = line.split("=", 1)
                env[name] = value.strip("'")
        env.update({
            "PORT": "18893", "MANAGEMENT_PORT": "18894",
            "APP_DATA_DIR": str(work / "uploads"),
            "DB_URL": "jdbc:mariadb://127.0.0.1:13377/yht_mysql57_fixture?useUnicode=true&characterEncoding=utf8&connectionTimeZone=Asia/Shanghai&connectTimeout=3000&socketTimeout=10000",
            "DB_USERNAME": "yht_fixture", "DB_PASSWORD": db_password,
            "ADMIN_PASSWORD": secrets.token_urlsafe(24), "JWT_SECRET": secrets.token_urlsafe(48),
        })
        app = subprocess.Popen(
            [str(args.java), "-Dfile.encoding=UTF-8", "-Duser.timezone=Asia/Shanghai",
             "-Xms128m", "-Xmx512m", "-jar", str(jar)], cwd=work,
            env=env, stdout=app_log, stderr=subprocess.STDOUT)
        health = ready(lambda: http("/api/health"), app, "Application")
        assert health["mode"] == "production" and health["database"] == "connected"
        management = ready(lambda: http("/actuator/health", port=18894), app, "Management health")
        assert management["status"] == "UP"
        report["checks"].append("production startup and private management health")
        migrations = sql("SELECT COUNT(*) FROM yht_mysql57_fixture.flyway_schema_history WHERE success=1;")
        assert migrations == "6", migrations
        report["checks"].append("Flyway V1-V6 on actual MySQL 5.7.44")
        token = http("/api/admin/login", {"username": "admin", "password": env["ADMIN_PASSWORD"]})["token"]
        assert len(http("/api/admin/users", token=token)) == 1
        assert http("/api/admin/therapists", token=token) == []
        assert http("/api/admin/me", token=token)["hq"] is True
        report["checks"].append("isolated production admin login, RBAC and no sample store accounts")
        runtime = http("/api/runtime")
        assert runtime["developmentLogin"] is False and runtime["wechatLoginConfigured"] is False
        assert runtime["customerTemplateIds"] == [env["CUSTOMER_TEMPLATE_ID"], env["CUSTOMER_REMINDER_TEMPLATE_ID"]]
        settings = http("/api/admin/settings", token=token)
        for name, variable in (("customerTemplateMapping", "CUSTOMER_TEMPLATE_MAPPING"),
                               ("customerReminderTemplateMapping", "CUSTOMER_REMINDER_TEMPLATE_MAPPING")):
            mapping = settings[name]
            assert (json.loads(mapping) if isinstance(mapping, str) else mapping) == json.loads(env[variable])
        try:
            http("/api/auth/dev-customer", {"deviceId": "isolated-mysql57-client"})
            raise AssertionError("Development login must be disabled in production")
        except urllib.error.HTTPError as failure:
            assert failure.code == 404
        report["checks"].append("two actual template IDs and field mappings, production dev login disabled")
        date = (dt.datetime.now(dt.timezone(dt.timedelta(hours=8))) + dt.timedelta(days=1)).date().isoformat()
        slots = http(f"/api/stores/yuyao/slots?date={date}")["slots"]
        slot = next(s for s in slots if s["available"] and s["remaining"] >= 1)
        booking_input = {"storeId": "yuyao", "serviceId": "moxa", "date": date,
                         "startTime": slot["time"], "headcount": 1, "name": "兼容验证客户",
                         "phone": "13900005744", "source": "PHONE", "memberClaim": "NO", "visitedClaim": "NO"}
        key = secrets.token_hex(12)
        booking = http("/api/admin/appointments", booking_input, token, key=key)
        repeated = http("/api/admin/appointments", booking_input, token, key=key)
        assert booking["id"] == repeated["id"] and booking["status"] == "CONFIRMED"
        assert sql("SELECT COUNT(*) FROM yht_mysql57_fixture.appointment;") == "1"
        updated = http("/api/admin/appointments/" + booking["id"],
                       {"action": "cancel", "version": booking["version"], "note": "隔离验证结束"}, token, "PATCH")
        assert updated["status"] == "CANCELLED" and updated["version"] == booking["version"] + 1
        try:
            http("/api/admin/appointments/" + booking["id"],
                 {"action": "cancel", "version": booking["version"]}, token, "PATCH")
            raise AssertionError("Stale appointment version must be rejected")
        except urllib.error.HTTPError as failure:
            assert failure.code == 409
        report["checks"].append("MySQL booking locks, idempotent create, cancellation and optimistic version conflict")
        time.sleep(1.5)
        assert "org.mariadb.jdbc.Connection" in (work / "application.log").read_text("utf-8", errors="replace")
        report["jdbcDriver"] = "org.mariadb.jdbc:mariadb-java-client:3.5.8"
        report["status"] = "passed"
    finally:
        if app and app.poll() is None:
            app.terminate()
            try:
                app.wait(timeout=20)
            except subprocess.TimeoutExpired:
                app.kill()
                app.wait(timeout=10)
        if server and server.poll() is None:
            try:
                sql("SHUTDOWN;")
                server.wait(timeout=20)
            except (RuntimeError, OSError, subprocess.TimeoutExpired):
                server.terminate()
                server.wait(timeout=10)
        mysql_log.close()
        app_log.close()
        report["onlyOwnedProcessesStopped"] = True
        report["finishedAt"] = dt.datetime.now(dt.timezone.utc).isoformat()
        report_path = root / "backups/mysql57-isolated-verification/result.json"
        report_path.parent.mkdir(parents=True, exist_ok=True)
        report_path.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
        print(json.dumps(report, ensure_ascii=False))


if __name__ == "__main__":
    main()

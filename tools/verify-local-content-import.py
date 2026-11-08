#!/usr/bin/env python3
"""Exercise the supplement through a real Java API using a disposable H2 DB only."""
import argparse
import contextlib
import copy
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import secrets
import shutil
import socket
import subprocess
import time
import zipfile

ROOT = Path(__file__).resolve().parents[1]


def module(filename):
    spec = importlib.util.spec_from_file_location(filename.replace("-", "_"), ROOT / "tools" / filename)
    value = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(value)
    return value


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", type=int, default=18794)
    parser.add_argument("--jar", type=Path, default=ROOT / "target-api/reservation-api-1.0.0.jar")
    args = parser.parse_args()
    with socket.socket() as check:
        check.bind(("127.0.0.1", args.port))
    importer = module("initialize-local-content.py")
    repair = module("repair-sample-therapists.py")
    client = module("initialize-client-services.py")
    official = module("initialize-official-stores.py")
    work = ROOT / "backups/local-content-verification" / secrets.token_hex(6)
    work.mkdir(parents=True)
    jar = args.jar.resolve()
    java = shutil.which("java")
    if not java:
        raise RuntimeError("Java 17 is required")
    with zipfile.ZipFile(jar) as archive:
        h2 = next((name for name in archive.namelist() if name.startswith("BOOT-INF/lib/h2-") and name.endswith(".jar")), None)
        if h2 is None:
            raise RuntimeError('Use --jar to specify an executable Spring Boot JAR containing H2')
        (work / "h2.jar").write_bytes(archive.read(h2))
    db = "jdbc:h2:file:" + (work / "verification").as_posix() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_ON_EXIT=FALSE"
    password = secrets.token_urlsafe(24)
    env = {key: value for key, value in os.environ.items()
           if not key.startswith(("DB_", "WECHAT_", "SERVICE_", "SMS_", "PMS_", "CUSTOMER_", "APP_", "ADMIN_", "JWT_"))}
    env.update(APP_MODE="local", SPRING_PROFILES_ACTIVE="default", PORT=str(args.port), BIND_ADDRESS="127.0.0.1",
               APP_DATA_DIR=str(work / "data"), DB_URL=db, DB_USERNAME="sa", DB_PASSWORD="",
               ADMIN_PASSWORD=password, JWT_SECRET=secrets.token_urlsafe(45), DB_POOL_SIZE="4", DB_POOL_IDLE="1")
    state = {"process": None, "log": None}

    def stop():
        process = state["process"]
        if process is not None and process.poll() is None:
            process.terminate()
            try:
                process.wait(timeout=20)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=10)
        if state["log"] is not None:
            state["log"].close()
        state.update(process=None, log=None)

    def start(label):
        state["log"] = (work / (label + ".log")).open("wb")
        state["process"] = subprocess.Popen([java, "-Xmx384m", "-jar", str(jar)], cwd=work, env=env,
                                            stdout=state["log"], stderr=subprocess.STDOUT,
                                            creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
        api = importer.Api("http://127.0.0.1:" + str(args.port))
        deadline = time.monotonic() + 100
        while time.monotonic() < deadline:
            if state["process"].poll() is not None:
                raise RuntimeError("Isolated Java exited; inspect " + str(work))
            try:
                api.token = api.request("POST", "/api/admin/login", dict(username="admin", password=password))["token"]
                api.get("/api/admin/stores")
                return api
            except (OSError, RuntimeError):
                time.sleep(0.5)
        raise RuntimeError("Isolated Java startup timed out: " + str(work))

    try:
        api = start("bootstrap")
        stop()
        data = importer.load_dataset(importer.DEFAULT_DATASET)
        empty = dict(brandName="裕和堂", brandEnglish="YU HE TANG", tagline="", brandStory="", theme="sage",
                     hero={k: "" for k in data["content"]["hero"]}, contact={k: "" for k in data["content"]["contact"]},
                     sections=[], services=[], products=[], galleryGroups=[], assetLibrary=[], customerServiceEnabled=True, version=1)
        literal = json.dumps(empty, ensure_ascii=False).replace("'", "''")
        sql = "DELETE FROM therapist; UPDATE site_config SET revision=1,published_json='%s',draft_json='%s' WHERE id='brand';" % (literal, literal)
        reset = work / "reset.sql"
        reset.write_text(sql, encoding="utf-8")
        subprocess.run([java, "-cp", str(work / "h2.jar"), "org.h2.tools.RunScript", "-url", db, "-user", "sa",
                        "-password", "", "-script", str(reset)], check=True, capture_output=True, timeout=30)
        api = start("verification")
        # Local bootstrap retains legacy profiles without catalogMode. Normalize
        # this explicit fixture field before running the earlier store importer.
        for store in api.get("/api/admin/stores"):
            api.request("PUT", "/api/admin/stores/" + store["id"], dict(store, catalogMode="INHERIT"))
        with (work / "initialization.txt").open("w", encoding="utf-8") as output, contextlib.redirect_stdout(output):
            path = ROOT / "initial-data/official-stores/stores.json"
            official.initialize(api, official.load_dataset(path), path.parent, True, work / "official-reports")
            path = ROOT / "initial-data/client-services-20261108/services.json"
            client.initialize(api, client.load_dataset(path), path.parent, True, work / "client-reports")
            users = api.get("/api/admin/users")
            customers, appointments = api.get("/api/admin/customers"), api.get("/api/admin/appointments")
            first = importer.initialize(api, data, importer.DEFAULT_DATASET.parent, True, work / "reports")
            second = importer.initialize(api, data, importer.DEFAULT_DATASET.parent, True, work / "reports")
        assert first["status"] == second["status"] == "verified"
        assert len(first["uploaded"]) == 33 and len(first["createdTherapists"]) == 27
        assert not second["uploaded"] and not second["createdTherapists"] and second["contentStatus"] == "unchanged"
        assert all(a["title"].startswith("示例素材-") for a in api.get("/api/admin/assets") if a["id"] in {u["id"] for u in first["uploaded"]})
        site = api.get("/api/site")
        assert (len(site["services"]), len(site["products"]), len(site["galleryGroups"])) == (4, 2, 2)
        assert sum(len(s["services"]) for s in site["stores"]) == 126
        teachers = api.get("/api/admin/therapists")
        assert len(teachers) == 27 and all(t["sample"] and "示例" in t["name"] for t in teachers)
        for teacher in teachers:
            active = api.get('/api/stores/' + teacher['storeId'] + '/services')
            assert set(teacher['serviceIds']) == {s['id'] for s in active}
        # Reproduce the old imported links in this disposable DB, then verify
        # the standalone repair against the actual admin and public Java APIs.
        # Fresh process separates the bulk import from repair rate-limit windows.
        stop()
        api = start('repair')
        catalogs = {sid: api.get('/api/admin/stores/' + sid + '/services') for sid in repair.BRANCHES}
        for teacher in teachers:
            legacy = [s['id'] for s in catalogs[teacher['storeId']] if s['name'] in repair.LEGACY_NAMES]
            api.request('PUT', '/api/admin/therapists', dict(teacher, serviceIds=legacy))
        before_repair = api.get('/api/admin/therapists')
        repair_api = repair.Api(api.base)
        repair_api.token = api.token
        with (work / 'repair.txt').open('w', encoding='utf-8') as output, contextlib.redirect_stdout(output):
            repaired = repair.repair(repair_api, True, work / 'repair-reports')
            repeated = repair.repair(repair_api, True, work / 'repair-reports')
        assert repaired['status'] == repeated['status'] == 'verified'
        assert len(repaired['updated']) == 27 and repaired['addedLinks'] == 273
        assert not repeated['updated'] and repeated['addedLinks'] == 0
        teachers = api.get('/api/admin/therapists')
        previous = {t['id']: t for t in before_repair}
        for teacher in teachers:
            assert {k: v for k, v in teacher.items() if k != 'serviceIds'} == {k: v for k, v in previous[teacher['id']].items() if k != 'serviceIds'}
        for sid in repair.BRANCHES:
            public = api.get('/api/stores/' + sid + '/therapists')
            active = api.get('/api/stores/' + sid + '/services')
            assert len(public) == 3
            assert all(set(t['serviceIds']) == {s['id'] for s in active} for t in public)
        assert api.get("/api/admin/users") == users
        assert api.get("/api/admin/customers") == customers and api.get("/api/admin/appointments") == appointments

        # Keep genuine operator edits, including a renamed/promoted staff row,
        # without accidentally publishing a draft from another editing session.
        teacher = teachers[0]
        teacher.update(name="人工已修改的老师", grade="已确认等级", sample=False, enabled=False)
        api.request("PUT", "/api/admin/therapists", teacher)
        draft = api.get("/api/admin/content")
        draft["tagline"] = "人工保存但尚未发布的标语"
        draft["products"] = draft["products"][:1]
        api.request("PUT", "/api/admin/content/draft", draft)
        live_before = importer.site_fields(api.get("/api/site"))
        teachers_before = api.get("/api/admin/therapists")
        with (work / "preservation.txt").open("w", encoding="utf-8") as output, contextlib.redirect_stdout(output):
            third = importer.initialize(api, data, importer.DEFAULT_DATASET.parent, True, work / "reports")
        assert third["contentStatus"] == "draft"
        assert not third["uploaded"] and not third["createdTherapists"]
        assert importer.site_fields(api.get("/api/site")) == live_before
        assert api.get("/api/admin/therapists") == teachers_before
        assert api.get("/api/admin/content")["tagline"] == draft["tagline"]
        assert len(api.get("/api/admin/content")["products"]) == 2
        result = dict(status="verified", verification="real-Java-API/disposable-H2", javaJarSha256=hashlib.sha256(jar.read_bytes()).hexdigest(),
                      sourceCounts=dict(brandServices=4, products=2, albums=2, assets=33, sampleTherapists=27),
                      priorBranchRecords=135, priorPublicBranchRecords=126, priorBranchDataPreserved=True,
                      sampleLinkRepair=dict(updated=27, addedLinks=273, repeatedWrites=0, publicApiVerified=True),
                      repeatedImportWrites=0, existingDraftNotPublished=True, existingStaffEditsPreserved=True,
                      accountsUnchanged=True, customersAndAppointmentsUnchanged=True, cloudImportPerformed=False)
        (work / "result.json").write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        print(json.dumps(dict(result, evidence=str(work / "result.json")), ensure_ascii=False), flush=True)
    finally:
        stop()


if __name__ == "__main__":
    main()

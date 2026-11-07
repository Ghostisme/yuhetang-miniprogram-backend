"""Run the independently built frontend and API through real Nginx on Windows.

Uses a disposable in-memory LOCAL database and random test credentials; never
opens the existing local database or sends WeChat/SMS. This is not a 1Panel,
Linux, production-MySQL or public-TLS runtime test.
"""
import datetime as dt
import hashlib
import json
import os
from pathlib import Path
import secrets
import shutil
import socket
import subprocess
import tempfile
import time
import urllib.error
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parent.parent
ADMIN_ROOT = ROOT.parent / "yuhetang-miniprogram-admin"
OUTPUT = ROOT / "backups/1panel-split-verification"
NGINX_URL = "https://nginx.org/download/nginx-1.28.0.zip"
CHECKS = []


def port():
    with socket.socket() as listener:
        listener.bind(("127.0.0.1", 0))
        return listener.getsockname()[1]


def request(url, *, data=None, token=None):
    headers = {}
    if data is not None:
        data = json.dumps(data).encode()
        headers["Content-Type"] = "application/json"
    if token:
        headers["Authorization"] = "Bearer " + token
    try:
        with urllib.request.urlopen(urllib.request.Request(url, data=data, headers=headers), timeout=5) as response:
            return response.status, dict(response.headers), response.read()
    except urllib.error.HTTPError as response:
        return response.code, dict(response.headers), response.read()


def main():
    assert os.name == "nt", "This verification fixture uses the official Windows Nginx build"
    OUTPUT.mkdir(parents=True, exist_ok=True)
    nginx_zip = OUTPUT / "nginx-1.28.0.zip"
    if not nginx_zip.exists():
        with urllib.request.urlopen(NGINX_URL, timeout=45) as source:
            nginx_zip.write_bytes(source.read())
    with zipfile.ZipFile(nginx_zip) as archive:
        archive.extractall(OUTPUT / "runtime")
    executable = OUTPUT / "runtime/nginx-1.28.0/nginx.exe"
    jar = ROOT / "target-api/reservation-api-1.0.0.jar"
    api_port, web_port, booking_port = port(), port(), port()
    password = secrets.token_hex(20)
    env = {k: v for k, v in os.environ.items() if not k.startswith((
        "SPRING_", "DB_", "APP_", "JWT_", "ADMIN_", "WECHAT_", "SMS_", "SERVICE_",
        "JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS"))}
    processes = []
    report = None
    with tempfile.TemporaryDirectory(prefix="yuhetang-split-") as temporary:
        # Windows Nginx rejects 8.3 path aliases and does not decode JSON-style
        # Unicode escapes in paths. Use the resolved ASCII temp path throughout.
        fixture = Path(temporary).resolve()
        (fixture / "logs").mkdir()
        (fixture / "conf").mkdir()
        (fixture / "temp").mkdir()
        shutil.copytree(ADMIN_ROOT / "dist", fixture / "html")
        shutil.copyfile(OUTPUT / "runtime/nginx-1.28.0/conf/mime.types", fixture / "conf/mime.types")
        env.update(APP_MODE="local", PORT=str(api_port), BIND_ADDRESS="127.0.0.1",
                   DB_URL="jdbc:h2:mem:split_" + secrets.token_hex(8) + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                   DB_USERNAME="sa", DB_PASSWORD="", JWT_SECRET=secrets.token_hex(32),
                   ADMIN_PASSWORD=password, APP_DATA_DIR=str(fixture / "data"))
        api_log = (OUTPUT / "api.log").open("w", encoding="utf-8")
        nginx_log = (OUTPUT / "nginx.log").open("w", encoding="utf-8")
        try:
            api = subprocess.Popen([shutil.which("java"), "-Xmx384m", "-Dfile.encoding=UTF-8", "-jar", str(jar)],
                                   cwd=fixture, env=env, stdout=api_log, stderr=subprocess.STDOUT)
            processes.append(api)
            api_url = f"http://127.0.0.1:{api_port}"
            for _ in range(100):
                assert api.poll() is None, "API exited; inspect the fixture API log"
                try:
                    if request(api_url + "/api/health")[0] == 200:
                        break
                except (urllib.error.URLError, TimeoutError):
                    pass
                time.sleep(.3)
            else:
                raise AssertionError("API did not become ready")
            CHECKS.append("API JAR boots independently with a disposable local in-memory database")
            for route in ("/", "/admin", "/index.html"):
                assert request(api_url + route)[0] == 404, route
            CHECKS.append("API alone exposes no admin HTML or frontend controller")
            mime = (fixture / "conf/mime.types").as_posix()
            configs = []
            for name, listen in (("admin", web_port), ("booking", booking_port)):
                snippet = (ROOT / f"deploy/1panel/{name}.locations.conf").read_text("utf-8")
                snippet = snippet.replace("127.0.0.1:8790", f"127.0.0.1:{api_port}")
                configs.append(f"server {{ listen 127.0.0.1:{listen}; server_name localhost; root {json.dumps((fixture / 'html').as_posix())};\n{snippet}\n}}")
            configuration = "daemon off;\nmaster_process off;\nworker_processes 1;\nevents { worker_connections 128; }\nhttp {\ninclude " + json.dumps(mime) + ";\n" + "\n".join(configs) + "\n}\n"
            (fixture / "conf/nginx.conf").write_text(configuration, encoding="utf-8")
            prefix = fixture.as_posix() + "/"
            command = [str(executable), "-p", prefix, "-c", "conf/nginx.conf"]
            checked = subprocess.run(command + ["-t"], capture_output=True, timeout=15)
            assert checked.returncode == 0, checked.stderr.decode(errors="replace")
            CHECKS.append("Both supplied server snippets pass real Nginx configuration validation")
            proxy = subprocess.Popen(command, cwd=fixture, stdout=nginx_log, stderr=subprocess.STDOUT)
            processes.append(proxy)
            web_url = f"http://127.0.0.1:{web_port}"
            for _ in range(50):
                assert proxy.poll() is None, "Nginx exited; inspect its fixture log"
                try:
                    if request(web_url + "/admin")[0] == 200:
                        break
                except (urllib.error.URLError, TimeoutError):
                    pass
                time.sleep(.1)
            else:
                raise AssertionError("Frontend did not become ready")
            index = (ADMIN_ROOT / "dist/index.html").read_bytes()
            assert request(web_url + "/admin")[2] == index
            assert request(web_url + "/admin/deep-link")[2] == index
            for asset in (ADMIN_ROOT / "dist/assets").iterdir():
                response = request(web_url + "/assets/" + asset.name)
                assert response[0] == 200 and response[2] == asset.read_bytes()
                assert "immutable" in response[1]["Cache-Control"]
            assert "no-cache" in request(web_url + "/index.html")[1]["Cache-Control"]
            assert request(web_url + "/assets/missing.js")[0] == 404
            CHECKS.append("Static frontend, SPA navigation, cache headers and missing assets behave correctly")
            for origin in (web_url, f"http://127.0.0.1:{booking_port}"):
                assert json.loads(request(origin + "/api/health")[2])["status"] == "ok"
                login = request(origin + "/api/admin/login", data={"username": "admin", "password": password})
                assert login[0] == 200
                token = json.loads(login[2])["token"]
                assert request(origin + "/api/admin/me", token=token)[0] == 200
                assert request(origin + "/api/missing-resource")[0] == 404
                assert request(origin + "/api/missing-resource")[2] != index
                assert request(origin + "/uploads/missing.jpg")[0] == 404
                assert request(origin + "/actuator/health")[0] == 404
                assert request(origin + "/media/ritual.webp")[0] == 200
            CHECKS.append("Both origins proxy login and JWT API calls, preserve media and reject missing API/uploads and public actuator")
            assert request(f"http://127.0.0.1:{booking_port}/")[0] == 404
            browser_script = """
import {chromium} from 'playwright';
const browser=await chromium.launch({headless:true});
try {
 const page=await browser.newPage({viewport:{width:2560,height:1440}});
 const errors=[];page.on('pageerror',e=>errors.push(e.message));
 await page.goto(process.env.SPLIT_WEB+'/admin');
 await page.locator('input').first().fill('admin');
 await page.locator('input[type=password]').fill(process.env.SPLIT_PASSWORD);
 await page.locator('form button.a-primary').click();
 await page.waitForFunction(()=>!!sessionStorage.getItem('yht-admin-token'));
 await page.waitForSelector('input[type=password]',{state:'detached'});
 await page.locator('.dashboard-welcome').waitFor({state:'visible'});
 await page.locator('.stat-grid .stat-card').first().waitFor({state:'visible'});
 await page.screenshot({path:process.env.SPLIT_SCREENSHOT,fullPage:true});
 if(errors.length)throw new Error(JSON.stringify(errors));
 console.log('Browser admin login passed at 2560x1440');
} finally {await browser.close();}
"""
            browser_env = dict(os.environ, SPLIT_WEB=web_url, SPLIT_PASSWORD=password,
                               SPLIT_SCREENSHOT=str(OUTPUT / "admin-2560.png"))
            browser = subprocess.run([shutil.which("node"), "--input-type=module", "-e", browser_script],
                                     cwd=ADMIN_ROOT, env=browser_env, capture_output=True, timeout=45)
            assert browser.returncode == 0, browser.stderr.decode(errors="replace")
            CHECKS.append("Real browser logs into the independently served admin at 2560x1440 without page errors")
            report = {"status": "passed", "createdAt": dt.datetime.now(dt.timezone.utc).isoformat(),
                      "jarSha256": hashlib.sha256(jar.read_bytes()).hexdigest(),
                      "frontendIndexSha256": hashlib.sha256(index).hexdigest(),
                      "snippetsSha256": {name: hashlib.sha256((ROOT / f"deploy/1panel/{name}.locations.conf").read_bytes()).hexdigest()
                                          for name in ("admin", "booking")},
                      "checks": CHECKS, "database": "disposable H2 in memory, local mode only",
                      "externalMessagesSent": 0,
                      "nginx": {"source": NGINX_URL, "zipSha256": hashlib.sha256(nginx_zip.read_bytes()).hexdigest()},
                      "cloud1PanelRuntime": "not performed", "mysql84Runtime": "not performed",
                      "publicHttps": "not performed", "realWechatDelivery": "not performed"}
        finally:
            for process in reversed(processes):
                if process.poll() is None:
                    process.terminate()
                    try:
                        process.wait(timeout=15)
                    except subprocess.TimeoutExpired:
                        process.kill()
                        process.wait(timeout=10)
            api_log.close()
            nginx_log.close()
    if report:
        (OUTPUT / "result.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        print(json.dumps(report, ensure_ascii=True, indent=2))


if __name__ == "__main__":
    main()

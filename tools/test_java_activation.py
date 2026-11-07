"""Exercise the release switch and rollback with fake Docker; no real services used."""
import hashlib
import json
import os
from pathlib import Path
import shlex
import shutil
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parent.parent
SCRIPT = ROOT / "deploy/docker-java/activate-20261107-215000.sh"
BASH = shutil.which("bash") if os.name != "nt" else "D:/Git/bin/bash.exe"
OLD_IMAGE = "yuhetang-java-api:20261107-195806"
NEW_IMAGE = "yuhetang-java-api:20261107-215000"
JAR_SHA = "d92f7540bf2859fd51a9cdabdfb1cb68760d7b415f9bd0aa6600638b6cc1de1d"
NAME = "yuhetang-java-api"

DOCKER = r'''
import json, os, pathlib, sys
root = pathlib.Path(os.environ["YHT_FIXTURE_ROOT"])
state_file = root / "state.json"
state = json.loads(state_file.read_text())
args = sys.argv[1:]
scenario = state["scenario"]
name = "yuhetang-java-api"
image = "yuhetang-java-api:20261107-215000"
code = 0
output = ""
state["calls"].append(args)
def inspect(which, fmt):
    item = state["containers"].get(which)
    if item is None: return "", 1
    if "Config.Image" in fmt: return item["image"], 0
    if "NetworkMode" in fmt: return "host", 0
    if "Config.Labels" in fmt: return "java-api", 0
    if "State.Running" in fmt: return str(item["running"]).lower(), 0
    if "State.Health.Status" in fmt: return item["health"], 0
    return "{}", 0
if args[0] == "info":
    output = "linux"
elif args[0] == "container" and args[1] == "inspect":
    output, code = inspect(args[-1], "")
elif args[0] == "inspect":
    output, code = inspect(args[-1], args[args.index("--format") + 1])
elif args[0] == "exec":
    output = ("bad-hash" if scenario == "runtime_hash" and state["containers"][name]["image"] == image
              else os.environ["YHT_FIXTURE_JAR_SHA"]) + "  /app/reservation.jar"
elif args[0] == "build":
    code = int(scenario == "build_failure")
elif args[0] == "run" and "--rm" in args:
    if "/app/check-db-target.sh" in args:
        code = int(scenario == "database_guard")
    else:
        output = ("bad-hash" if scenario == "image_hash" else os.environ["YHT_FIXTURE_JAR_SHA"]) + "  /app/reservation.jar"
elif args[0] == "run":
    if scenario == "create_failure":
        code = 1
    else:
        state["containers"][name] = {"image": image, "running": scenario != "health_failure", "health": "healthy"}
        code = int(scenario == "partial_create_failure")
        output = "fixture-new-container"
elif args[0] == "stop":
    state["containers"][args[-1]]["running"] = False
    code = int(scenario == "stop_failure" and not state.get("stop_failed"))
    state["stop_failed"] = True
elif args[0] == "rename":
    if scenario == "rename_failure" and not state.get("rename_failed"):
        state["rename_failed"] = True
        code = 1
    elif args[2] in state["containers"]:
        code = 1
    else:
        state["containers"][args[2]] = state["containers"].pop(args[1])
elif args[0] == "start":
    state["containers"][args[-1]]["running"] = True
else:
    print("Unexpected fake Docker operation: " + repr(args), file=sys.stderr)
    code = 2
state_file.write_text(json.dumps(state))
if output: print(output)
sys.exit(code)
'''


class ActivationTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="yuhetang-activation-fixture-")
        self.root = Path(self.temp.name)
        self.context = self.root / "upload/release"
        self.context.mkdir(parents=True)
        # This stub represents Docker returning from the existing run.sh.
        self.context.joinpath("run.sh").write_text(
            '#!/usr/bin/env bash\nset -e\ndocker run --detach --name yuhetang-java-api "$1"\n', encoding="utf-8")
        run_sha = hashlib.sha256(self.context.joinpath("run.sh").read_bytes()).hexdigest()
        manifest = f"{run_sha}  run.sh\n"
        self.context.joinpath("SHA256SUMS").write_bytes(manifest.encode())
        self.manifest_sha = hashlib.sha256(manifest.encode()).hexdigest()
        self.root.joinpath("docker.py").write_text(DOCKER, encoding="utf-8")
        self.root.joinpath("docker.env").write_text("not-a-real-configuration\n")
        self.root.joinpath("data").mkdir()

    def tearDown(self):
        self.temp.cleanup()

    def run_activation(self, scenario="success", current=OLD_IMAGE):
        containers = {
            NAME: dict(image=current, running=True, health="healthy"),
            "yuwatang-mysql-isolated": dict(image="mysql:fixture", running=True, health="healthy"),
            "1Panel-openresty-Iiuu": dict(image="openresty:fixture", running=True, health="healthy"),
            "yuhetang-java-api-before-20261107-195806": dict(image="old:fixture", running=False, health="healthy"),
        }
        self.original = json.loads(json.dumps(containers))
        self.root.joinpath("state.json").write_text(json.dumps(dict(scenario=scenario, calls=[], containers=containers)))
        quote = lambda p: shlex.quote(str(p).replace("\\", "/"))
        driver = f"""source {quote(SCRIPT)}
YHT_UPLOAD={quote(self.root / 'upload')}
YHT_CONTEXT={quote(self.context)}
YHT_ARCHIVE={quote(self.root / 'missing.tar.gz')}
YHT_ENVIRONMENT={quote(self.root / 'docker.env')}
YHT_DATA={quote(self.root / 'data')}
YHT_LOCK={quote(self.root / 'activation.lock')}
YHT_MANIFEST_SHA={shlex.quote(self.manifest_sha)}
YHT_WAIT=1
python3() {{ command {quote(sys.executable)} "$@"; }}
docker() {{ python3 {quote(self.root / 'docker.py')} "$@"; }}
id() {{ if [[ $# == 1 ]]; then printf '0\\n'; else printf '1001\\n'; fi; }}
stat() {{ if [[ $2 == '%u:%a' ]]; then printf '0:600\\n'; else printf '1001:1001\\n'; fi; }}
flock() {{ return 0; }}
ss() {{ return 0; }}
curl() {{ printf '%s\\n' '{{"status":"ok","database":"connected","mode":"production"}}'; }}
export -f docker python3
yht_main
"""
        env = dict(os.environ, YHT_FIXTURE_ROOT=str(self.root), YHT_FIXTURE_JAR_SHA=JAR_SHA,
                   MSYS_NO_PATHCONV="1", MSYS2_ARG_CONV_EXCL="*")
        process = subprocess.run([BASH, "--noprofile", "--norc", "-c", driver],
                                 env=env, capture_output=True, text=True, timeout=90)
        state = json.loads(self.root.joinpath("state.json").read_text())
        for other in self.original.keys() - {NAME}:
            self.assertEqual(state["containers"][other], self.original[other], other)
        self.assertFalse(any(call[0] in {"rm", "rmi", "prune", "update"} for call in state["calls"]))
        return process, state

    def test_switch_checks_jar_and_database_before_stopping_and_preserves_old_java(self):
        process, state = self.run_activation()
        self.assertEqual(process.returncode, 0, process.stdout + process.stderr)
        self.assertIn("SUCCESS: 215000 is active", process.stdout)
        self.assertEqual(state["containers"][NAME]["image"], NEW_IMAGE)
        backups = [c for n, c in state["containers"].items() if n.startswith("yuhetang-java-api-before-215000-")]
        self.assertEqual(backups, [dict(image=OLD_IMAGE, running=False, health="healthy")])
        stop_index = next(i for i, call in enumerate(state["calls"]) if call[0] == "stop")
        self.assertTrue(any("/app/check-db-target.sh" in call for call in state["calls"][:stop_index]))
        self.assertTrue(any("/app/reservation.jar" in call for call in state["calls"][:stop_index]))

    def test_already_active_does_not_restart_or_rebuild(self):
        shutil.rmtree(self.context)
        process, state = self.run_activation(current=NEW_IMAGE)
        self.assertEqual(process.returncode, 0, process.stdout + process.stderr)
        self.assertIn("Already active", process.stdout)
        self.assertFalse(any(call[0] in {"stop", "rename", "start", "run", "build"} for call in state["calls"]))

    def test_missing_or_modified_release_does_not_stop_old_java(self):
        for failure in ("manifest", "payload", "missing"):
            with self.subTest(failure=failure):
                if failure == "manifest":
                    self.manifest_sha = "bad-manifest"
                elif failure == "payload":
                    self.manifest_sha = hashlib.sha256(self.context.joinpath("SHA256SUMS").read_bytes()).hexdigest()
                    self.context.joinpath("run.sh").write_text("modified\n")
                else:
                    shutil.rmtree(self.context)
                process, state = self.run_activation()
                self.assertNotEqual(process.returncode, 0)
                self.assertFalse(any(call[0] == "stop" for call in state["calls"]))
                self.assertTrue(state["containers"][NAME]["running"])

    def test_build_jar_and_database_guard_failures_keep_old_java_running(self):
        for scenario in ("build_failure", "image_hash", "database_guard"):
            with self.subTest(scenario=scenario):
                process, state = self.run_activation(scenario)
                self.assertNotEqual(process.returncode, 0)
                self.assertEqual(state["containers"][NAME], self.original[NAME])
                self.assertFalse(any(call[0] == "stop" for call in state["calls"]))

    def test_failures_during_switch_restore_previous_java(self):
        for scenario in ("stop_failure", "rename_failure", "create_failure", "partial_create_failure", "health_failure", "runtime_hash"):
            with self.subTest(scenario=scenario):
                process, state = self.run_activation(scenario)
                self.assertNotEqual(process.returncode, 0)
                self.assertEqual(state["containers"][NAME], self.original[NAME], process.stdout + process.stderr)
                self.assertIn("Previous Java restored and healthy", process.stderr)
                if scenario in ("partial_create_failure", "health_failure", "runtime_hash"):
                    failed = [c for n, c in state["containers"].items() if n.endswith("-failed")]
                    self.assertEqual(failed, [dict(image=NEW_IMAGE, running=False, health="healthy")])

    def test_unrelated_current_image_is_left_unchanged(self):
        process, state = self.run_activation(current="unrelated:fixture")
        self.assertNotEqual(process.returncode, 0)
        self.assertEqual(state["containers"][NAME], self.original[NAME])
        self.assertFalse(any(call[0] == "stop" for call in state["calls"]))

    def test_incorrect_archive_is_rejected_before_old_java_is_stopped(self):
        shutil.rmtree(self.context)
        self.root.joinpath("missing.tar.gz").write_bytes(b"not-the-real-release")
        process, state = self.run_activation()
        self.assertNotEqual(process.returncode, 0)
        self.assertIn("archive SHA256 does not match", process.stderr)
        self.assertEqual(state["containers"][NAME], self.original[NAME])
        self.assertFalse(any(call[0] == "stop" for call in state["calls"]))


if __name__ == "__main__":
    unittest.main()

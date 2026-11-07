"""Verify deployment safety boundaries without starting an engine or database.

Uses Bash with disposable paths and fake engine/systemctl functions for failure
scenarios. This verifies scripts, not MySQL/Docker/Podman runtime compatibility.
"""
import datetime as dt
import hashlib
import io
import json
import os
from pathlib import Path
import shutil
import subprocess
import tarfile
import tempfile


ROOT = Path(__file__).resolve().parent.parent
SCRIPTS = ROOT / "deploy/tencent-isolated"
BASH = shutil.which("bash") if os.name != "nt" else r"D:\Git\bin\bash.exe"
EXPECTED_URL = (
    "jdbc:mariadb://127.0.0.1:3307/yuwatang_reservation?useUnicode=true&characterEncoding=utf8"
    "&connectionTimeZone=Asia/Shanghai&allowPublicKeyRetrieval=true&sslMode=disable"
    "&connectTimeout=3000&socketTimeout=10000"
)
CHECKS = []


def run(command, *, env=None, expected=0):
    result = subprocess.run(command, cwd=ROOT, env=env, capture_output=True, text=True, encoding="utf-8", timeout=30)
    if result.returncode != expected:
        raise AssertionError(f"Unexpected exit {result.returncode}, expected {expected}: {result.stdout}\n{result.stderr}")
    return result


def guard_checks():
    env = {key: value for key, value in os.environ.items() if not key.startswith(("SPRING_", "DB_", "JAVA_", "JDK_", "_JAVA_"))}
    env.update(SPRING_PROFILES_ACTIVE="production", APP_MODE="production", DB_URL=EXPECTED_URL,
               DB_USERNAME="yuwatang_app", DB_PASSWORD="a" * 64, JWT_SECRET="b" * 64, ADMIN_PASSWORD="test-only-admin-123")
    run([BASH, "--noprofile", "--norc", str(SCRIPTS / "check-db-target.sh")], env=env)
    CHECKS.append("dedicated loopback 3307 target accepted")
    cases = {
        "original host port 3306 rejected": {"DB_URL": EXPECTED_URL.replace(":3307/", ":3306/")},
        "H2 fallback rejected": {"DB_URL": "jdbc:h2:file:./data/reservation"},
        "different schema rejected": {"DB_URL": EXPECTED_URL.replace("/yuwatang_reservation?", "/other_business?")},
        "remote host rejected": {"DB_URL": EXPECTED_URL.replace("127.0.0.1", "43.142.125.157")},
        "root database account rejected": {"DB_USERNAME": "root"},
        "development profile rejected": {"SPRING_PROFILES_ACTIVE": "dev"},
        "development app mode rejected": {"APP_MODE": "local"},
        "Spring datasource override rejected": {"SPRING_DATASOURCE_URL": "jdbc:mariadb://127.0.0.1:3306/other"},
        "Flyway separate URL rejected": {"SPRING_FLYWAY_URL": "jdbc:mariadb://127.0.0.1:3306/other"},
        "Spring JSON override rejected": {"SPRING_APPLICATION_JSON": "{}"},
        "JVM property override rejected": {"JAVA_TOOL_OPTIONS": "-Dspring.datasource.url=invalid"},
        "missing generated password rejected": {"DB_PASSWORD": ""},
        "missing JWT secret rejected": {"JWT_SECRET": ""},
        "weak administrator password rejected": {"ADMIN_PASSWORD": "123"},
    }
    for name, values in cases.items():
        result = run([BASH, "--noprofile", "--norc", str(SCRIPTS / "check-db-target.sh")], env=env | values, expected=1)
        assert "STOP:" in result.stderr and "a" * 64 not in result.stderr
        CHECKS.append(name)


def preflight_checks():
    mocks = r'''
uname() { printf 'x86_64\n'; }
free() { :; }
df() { printf 'fixture\n'; }
ss() { printf '%s' "$fixture_listener"; }
flock() { :; }
systemctl() { printf 'Unexpected service operation\n' >&2; exit 99; }
awk() {
    if [[ ${2:-} == /proc/meminfo ]]; then printf '%s\n' "$fixture_memory";
    else cat >/dev/null; printf '%s\n' "$fixture_disk"; fi
}
podman() {
    [[ $1 == info ]] || { printf 'Unexpected engine mutation\n' >&2; exit 99; }
}
'''
    for name, memory, disk, listener, expected in (
        ("low available RAM blocks installation", 1920, 30000, "", 2),
        ("low available disk blocks installation", 8192, 2500, "", 2),
        ("occupied deployment port blocks installation", 8192, 30000, "LISTEN", 2),
        ("adequate resources pass read-only preflight", 8192, 30000, "", 0),
    ):
        body = mocks + '\nfixture_memory=$2; fixture_disk=$3; fixture_listener=$4; source "$1"\n'
        run([BASH, "--noprofile", "--norc", "-c", body, "fixture", str(SCRIPTS / "preflight.sh"),
             str(memory), str(disk), listener], expected=expected)
        CHECKS.append(name)


HARNESS = r'''
source "$1"
work=$(cd -- "$2" && pwd)
db_data="$work/db"
db_config="$work/config"
app_env="$work/app.env"
app_files="$work/files"
backup_root="$work/backups"
restore_data="$work/restore"
engine=fake_engine
image=$pinned_image
fake_engine() {
    printf '%s\t' "$@" >> "$work/engine.log"; printf '\n' >> "$work/engine.log"
    if [[ $1 == inspect && $2 == --format ]]; then
        printf '%s\n' "${fake_label:-mysql-isolated}"; return 0
    fi
    if [[ $1 == container && $2 == inspect ]]; then return "${fake_exists:-1}"; fi
    if [[ $1 == exec ]]; then return "${fake_exec_exit:-0}"; fi
    return 0
}
choose_engine() { engine=fake_engine; }
systemctl() {
    printf '%s\t' "$@" >> "$work/services.log"; printf '\n' >> "$work/services.log"
    if [[ $1 == is-active ]]; then return "${fake_app_active:-0}"; fi
    return 0
}
ss() { return 0; }
flock() { return "${fake_lock_exit:-0}"; }
install() { command mkdir -p -- "${@: -1}"; }
ready() { return 0; }
write_secrets() { mkdir -p -- "$1"; }
'''


def script_case(name, body, expected, verify):
    with tempfile.TemporaryDirectory(prefix="yht-isolation-") as temporary:
        path = Path(temporary)
        for directory in ("config", "files"):
            (path / directory).mkdir()
        (path / "app.env").write_text("fixture only\n", encoding="utf-8", newline="\n")
        create_backup_fixture(path / "fixture")
        run([BASH, "--noprofile", "--norc", "-c", HARNESS + "\n" + body, "fixture",
             str(SCRIPTS / "database.sh"), str(path)], expected=expected)
        engine = (path / "engine.log").read_text("utf-8") if (path / "engine.log").exists() else ""
        services = (path / "services.log").read_text("utf-8") if (path / "services.log").exists() else ""
        verify(path, engine, services)
        CHECKS.append(name)


def create_backup_fixture(directory):
    import gzip
    directory.mkdir()
    (directory / "database.sql.gz").write_bytes(gzip.compress(b"CREATE DATABASE yuwatang_reservation;\n"))
    for name in ("files.tar.gz", "config-private.tar.gz"):
        with tarfile.open(directory / name, "w:gz") as archive:
            data = b"fixture"
            member = tarfile.TarInfo("fixture.txt")
            member.size = len(data)
            archive.addfile(member, io.BytesIO(data))
    (directory / "table-counts.tsv").write_text("customer\t2\n", encoding="utf-8", newline="\n")
    image = (SCRIPTS / "mysql-image.txt").read_text("utf-8").strip()
    (directory / "metadata.txt").write_text("image=" + image + "\n", encoding="utf-8", newline="\n")
    names = ("database.sql.gz", "files.tar.gz", "config-private.tar.gz", "table-counts.tsv", "metadata.txt")
    (directory / "SHA256SUMS").write_text("".join(hashlib.sha256((directory / name).read_bytes()).hexdigest() + "  " + name + "\n" for name in names), encoding="utf-8", newline="\n")


def no_mutations(path, engine, services):
    assert not services
    assert not any(line.startswith(("create\t", "start\t", "stop\t", "exec\t")) for line in engine.splitlines())


def retained_original_data(path, engine, services):
    no_mutations(path, engine, services)
    assert (path / "db/sentinel").read_text() == "original"


def main():
    assert BASH and Path(BASH).is_file(), "Bash is required"
    for path in [*sorted(SCRIPTS.glob("*.sh")), ROOT / "deploy/tencent-host/prepare.sh"]:
        run([BASH, "--noprofile", "--norc", "-n", str(path)])
    CHECKS.append("all isolated scripts and shared application installer pass Bash syntax checks")
    assert "DB_URL=" + EXPECTED_URL in (SCRIPTS / "env.example").read_text("utf-8")
    guard_checks()
    preflight_checks()
    script_case("existing dedicated data is refused and retained", r'''
rm -d "$db_config"
mkdir -p "$db_data"; printf original > "$db_data/sentinel"
first_install_check
''', 1, retained_original_data)
    script_case("existing dedicated configuration is refused", "first_install_check", 1, no_mutations)
    script_case("existing container name is refused", r'''
rm -d "$db_config"
fake_exists=0
first_install_check
''', 1, no_mutations)
    script_case("unowned container cannot be stopped", r'''
fake_label=other-business
assert_owned "$db_container" mysql-isolated
"$engine" stop "$db_container"
''', 1, no_mutations)
    script_case("unowned container cannot be backed up", "fake_label=other-business; backup_database", 1, no_mutations)

    def verify_container_flags(path, engine, services):
        assert not services
        assert "127.0.0.1:3307:3306" in engine
        assert "--memory=1g" in engine and "--cpus=1" in engine
        assert "MYSQL_ROOT_HOST=localhost" in engine
        assert "--network=host" not in engine and "0.0.0.0" not in engine
        assert "/var/lib/mysql:/var/lib/mysql" not in engine

    script_case("Docker create uses dedicated mount, loopback port, memory and CPU limits", r'''
create_container "$db_container" mysql-isolated "$db_data" "$db_config/secrets" 3307
''', 0, verify_container_flags)
    script_case("Podman create uses the same isolation and resource limits", r'''
podman() { fake_engine "$@"; }
engine=podman
create_container "$db_container" mysql-isolated "$db_data" "$db_config/secrets" 3307
''', 0, verify_container_flags)

    def verify_failed_backup(path, engine, services):
        assert "stop\tyuwatang-reservation.service\t" in services
        assert "start\tyuwatang-reservation.service\t" in services
        assert "mysqldump" in engine and "yuwatang-mysql-isolated" in engine
        assert "mysqld.service" not in services and "docker.service" not in services
        assert not list((path / "backups").glob("*/SHA256SUMS"))

    script_case("failed SQL dump restarts only the previously running reservation application", "fake_exec_exit=42; backup_database", 42, verify_failed_backup)

    def verify_inactive_backup(path, engine, services):
        assert "stop\t" not in services and "start\t" not in services

    script_case("failed backup preserves an application that was stopped", "fake_app_active=3; fake_exec_exit=42; backup_database", 42, verify_inactive_backup)
    script_case("operation lock prevents overlapping backup", "fake_lock_exit=1; backup_database", 1, no_mutations)

    resources = r"awk() { cat >/dev/null; printf '20000\n'; }" + "\n"
    script_case("restore check refuses existing target data", resources + r'''
mkdir -p "$restore_data"; printf original > "$restore_data/sentinel"
restore_check "$work/fixture"
''', 1, no_mutations)
    script_case("restore check refuses corrupted backup before any container mutation", r'''
printf corrupt >> "$work/fixture/database.sql.gz"
restore_check "$work/fixture"
''', 1, no_mutations)

    def verify_failed_restore(path, engine, services):
        assert not services and "yuwatang-mysql-isolated" not in engine
        assert "127.0.0.1:3308:3306" in engine and "--memory=1g" in engine
        assert "create\t--name\tyuwatang-mysql-restorecheck\t" in engine
        assert "stop\t--time\t60\tyuwatang-mysql-restorecheck\t" in engine
        assert str(path / "db") not in engine

    script_case("failed restore import stops only the new restore container and retains the live database", resources + "fake_exec_exit=44; restore_check \"$work/fixture\"", 44, verify_failed_restore)
    result = {"createdAt": dt.datetime.now(dt.timezone.utc).isoformat(), "status": "passed", "checks": CHECKS,
              "containerRuntime": "not performed", "cloudDeployment": "not performed",
              "scope": "Bash syntax, real process environment guard, and failure-path simulations with temporary files and fake engine/systemctl; no database or daemon was started"}
    destination = ROOT / "backups/tencent-isolated-script-verification/result.json"
    destination.parent.mkdir(parents=True, exist_ok=True)
    destination.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"status": result["status"], "checks": len(CHECKS), "containerRuntime": result["containerRuntime"], "report": str(destination)}, ensure_ascii=False))


if __name__ == "__main__":
    main()

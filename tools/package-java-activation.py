"""Render the previously tested activation flow for an immutable Java release."""
import argparse
import hashlib
from pathlib import Path
import re
import tarfile

ROOT = Path(__file__).resolve().parent.parent
BASELINE = ROOT / "deploy/docker-java/activate-20261107-215000.sh"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--release-id", required=True)
    args = parser.parse_args()
    if not re.fullmatch(r"\d{8}-\d{6}", args.release_id):
        parser.error("release-id must be YYYYMMDD-HHMMSS")
    release = args.release_id
    short = release.split("-")[1]
    releases = ROOT / "releases"
    archive = releases / f"yuhetang-java-docker-{release}.tar.gz"
    jar = releases / f"yuhetang-java-api-{release}.jar"
    if not archive.is_file() or not jar.is_file():
        parser.error("Package and verify the Java release first")
    digest = lambda path: hashlib.sha256(path.read_bytes()).hexdigest()
    jar_sha = digest(jar)
    archive_sha = digest(archive)
    with tarfile.open(archive) as bundled:
        prefix = f"yuhetang-java-docker-{release}/"
        manifest = bundled.extractfile(prefix + "SHA256SUMS").read()
        manifest_sha = hashlib.sha256(manifest).hexdigest()
        expected = f"{jar_sha}  reservation.jar\n".encode()
        assert expected in manifest, "Archive and standalone JAR differ"
        runner = (ROOT / "deploy/docker-java/run.sh").read_text(encoding="utf-8-sig").replace("\r\n", "\n").encode()
        assert bundled.extractfile(prefix + "run.sh").read() == runner, "Review changes to the activation flow before releasing"
    # Preserve the tested switch/health/rollback flow; substitute release data only.
    content = BASELINE.read_text(encoding="utf-8").replace("20261107-215000", release).replace("215000", short)
    for key, value in (("YHT_JAR_SHA", jar_sha), ("YHT_MANIFEST_SHA", manifest_sha), ("YHT_ARCHIVE_SHA", archive_sha)):
        content, count = re.subn(r"(?m)^" + key + r"=[0-9a-f]{64}$", key + "=" + value, content)
        assert count == 1, key
    output = releases / f"yuhetang-activate-{short}.sh"
    checksum = output.with_name(output.name + ".sha256")
    if output.exists() or checksum.exists():
        parser.error("Activation artifact already exists")
    output.write_text(content, encoding="utf-8", newline="\n")
    checksum.write_text(digest(output) + "  " + output.name + "\n", encoding="ascii", newline="\n")
    print(output)
    print(checksum)


if __name__ == "__main__":
    main()

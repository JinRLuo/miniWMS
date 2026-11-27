#!/usr/bin/python3 -I
"""Receive one release from the restricted production SSH key."""

import fcntl
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import signal
import subprocess
import sys
import tarfile
import tempfile
import time


ROOT = Path("/opt/miniwms")
RELEASES = ROOT / "releases"
SITE = os.environ.get("WMS_DEPLOY_SITE", "wms.piuiq.com")
MAX_ARCHIVE_BYTES = 256 * 1024 * 1024


def digest(path):
    checksum = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            checksum.update(chunk)
    return checksum.hexdigest()


def valid_member(name):
    if name in ("release.json", "miniwms.jar"):
        return True
    path = PurePosixPath(name)
    return (path.as_posix() == name and len(path.parts) > 1 and path.parts[0] == "public"
            and all(re.fullmatch(r"[A-Za-z0-9_.-]+", part) and part not in (".", "..")
                    for part in path.parts))


def receive(source, stage):
    names = set()
    total = 0
    with tarfile.open(fileobj=source, mode="r|gz") as archive:
        for member in archive:
            total += member.size
            name = member.name
            if (not member.isfile() or not valid_member(name) or name in names
                    or total > MAX_ARCHIVE_BYTES or len(names) >= 4096):
                raise ValueError("Unexpected file or size in release archive")
            if name == "release.json" and member.size > 1024 * 1024:
                raise ValueError("Release manifest is too large")
            names.add(name)
            target = stage / name
            target.parent.mkdir(parents=True, exist_ok=True)
            with archive.extractfile(member) as payload, target.open("xb") as output:
                shutil.copyfileobj(payload, output)
            target.chmod(0o644)
    manifest = json.loads((stage / "release.json").read_text())
    for field in ("revision", "backendTree"):
        if not re.fullmatch(r"[0-9a-f]{40}", manifest[field]):
            raise ValueError("Invalid Git revision in release manifest")
    required = {"miniwms.jar", "public/index.html"}
    if not required.issubset(manifest["files"]) or names != set(manifest["files"]) | {"release.json"}:
        raise ValueError("Release files differ from the manifest")
    for name, checksum in manifest["files"].items():
        if digest(stage / name) != checksum:
            raise ValueError("Release checksum verification failed")
    return manifest


def current_release():
    link = Path(os.readlink(ROOT / "current"))
    if link.parent != Path("releases") or not re.fullmatch(r"[0-9a-f]{40}", link.name):
        raise ValueError("Current production link must point to a versioned release")
    if not (ROOT / link / "miniwms.jar").is_file():
        raise ValueError("Current production release is missing its backend JAR")
    return ROOT / link


def metadata(release):
    manifest = release / "release.json"
    return json.loads(manifest.read_text()) if manifest.is_file() else {}


def preserve_assets(previous, target, old_manifest):
    assets = previous / "public/assets"
    if not assets.is_dir():
        return
    for asset in assets.iterdir():
        name = f"public/assets/{asset.name}"
        if old_manifest and name not in old_manifest["files"]:
            continue
        replacement = target / name
        if asset.is_file() and not asset.is_symlink() and not replacement.exists():
            replacement.parent.mkdir(parents=True, exist_ok=True)
            os.link(asset, replacement)


def switch_to(release):
    with tempfile.TemporaryDirectory(prefix=".switch-", dir=ROOT) as temporary:
        link = Path(temporary) / "current"
        link.symlink_to(release.relative_to(ROOT))
        link.replace(ROOT / "current")


def fetch(url, *options):
    return subprocess.run(
        ["curl", "--noproxy", "*", "--silent", "--show-error", "--fail", "--max-time", "5", *options, url],
        check=True, capture_output=True,
    ).stdout


def wait_for_backend():
    deadline = time.monotonic() + 60
    while time.monotonic() < deadline:
        try:
            fetch("http://127.0.0.1:18089/api/auth/csrf")
            return
        except subprocess.CalledProcessError:
            time.sleep(2)
    raise RuntimeError("The API did not become ready on port 18089")


def start_backend(release):
    env = {
        **os.environ,
        "WMS_REVISION": release.name,
        "WMS_RELEASE_JAR": str(release / "miniwms.jar"),
    }
    subprocess.run(
        ["docker", "compose", "-f", "compose.runtime.yaml", "up", "-d", "--force-recreate", "--no-deps", "api"],
        cwd=ROOT, env=env, check=True, timeout=60,
    )
    wait_for_backend()


def check_public_release(release, manifest):
    def fetch_site(route):
        return fetch(f"https://{SITE}{route}", "--resolve", f"{SITE}:443:127.0.0.1")

    entry = (release / "public/index.html").read_bytes()
    for route in ("/admin", "/m/operations"):
        if fetch_site(route) != entry:
            raise RuntimeError(f"Nginx did not serve the new frontend at {route}")
    assets = re.findall(rb'(?:src|href)="(/assets/[^"]+)"', entry)
    for asset in assets:
        route = asset.decode("ascii")
        checksum = hashlib.sha256(fetch_site(route)).hexdigest()
        if checksum != manifest["files"].get("public" + route):
            raise RuntimeError(f"Nginx returned the wrong asset at {route}")
    fetch_site("/api/auth/csrf")


def main():
    with (ROOT / ".deploy.lock").open("a") as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        previous = current_release()
        old_manifest = metadata(previous)
        with tempfile.TemporaryDirectory(prefix=".incoming-", dir=RELEASES) as temporary:
            stage = Path(temporary) / "release"
            stage.mkdir()
            manifest = receive(sys.stdin.buffer, stage)
            release = RELEASES / manifest["revision"]
            if release.exists():
                published = metadata(release)
                if published.get("backendTree") != manifest["backendTree"]:
                    raise ValueError("An incompatible release already uses this revision")
                manifest = published
            else:
                preserve_assets(previous, stage, old_manifest)
                stage.chmod(0o755)
                for path in stage.rglob("*"):
                    if path.is_dir():
                        path.chmod(0o755)
                stage.rename(release)

        backend_changed = old_manifest.get("backendTree") != manifest["backendTree"]
        try:
            if backend_changed:
                start_backend(release)
            else:
                wait_for_backend()
            switch_to(release)
            check_public_release(release, manifest)
        except BaseException:
            switch_to(previous)
            if backend_changed:
                start_backend(previous)
            print(f"Restored release {previous.name}", flush=True)
            raise
        print(f"Published release {release.name} to https://{SITE}", flush=True)


def interrupted(signum, _frame):
    raise InterruptedError(f"Deployment interrupted by signal {signum}")


if __name__ == "__main__":
    signal.signal(signal.SIGHUP, interrupted)
    signal.signal(signal.SIGTERM, interrupted)
    try:
        main()
    except (OSError, ValueError, KeyError, RuntimeError, subprocess.SubprocessError, tarfile.TarError) as error:
        print(f"Mini WMS deploy failed: {error}", file=sys.stderr)
        sys.exit(1)

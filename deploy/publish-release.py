#!/usr/bin/python3 -I
"""Receive one release from the restricted production SSH key."""

import fcntl
from concurrent.futures import ThreadPoolExecutor
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
import urllib.parse
import urllib.request
import zipfile


ROOT = Path("/opt/miniwms")
RELEASES = ROOT / "releases"
SITE = os.environ.get("WMS_DEPLOY_SITE", "wms.piuiq.com")
MAX_ARCHIVE_BYTES = 256 * 1024 * 1024
MAX_REQUEST_BYTES = 64 * 1024


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, response, code, message, headers, newurl):
        return None


def read_download_request(source):
    payload = source.read(MAX_REQUEST_BYTES + 1)
    if len(payload) > MAX_REQUEST_BYTES:
        raise ValueError("Release download request is too large")
    request = json.loads(payload)
    if not isinstance(request, dict) or request.get("version") != 1:
        raise ValueError("Unsupported release download request")
    if not re.fullmatch(r"[0-9a-f]{40}", str(request.get("revision", ""))):
        raise ValueError("Invalid requested Git revision")
    if not re.fullmatch(r"[0-9a-f]{64}", str(request.get("sha256", ""))):
        raise ValueError("Invalid release archive checksum")
    size = request.get("bytes")
    if type(size) is not int or not 0 < size <= MAX_ARCHIVE_BYTES:
        raise ValueError("Invalid release archive size")
    if not isinstance(request.get("url"), str):
        raise ValueError("Invalid artifact storage URL")
    url = urllib.parse.urlsplit(request["url"])
    if (url.scheme != "https" or url.username or url.password or url.fragment
            or url.port not in (None, 443)
            or not (url.hostname or "").endswith(".blob.core.windows.net")):
        raise ValueError("Only HTTPS GitHub artifact storage URLs are allowed")
    return request


def download_artifact(request, target):
    """Download bounded ranges concurrently; never forward the GitHub token."""
    opener = urllib.request.build_opener(NoRedirect())
    with opener.open(urllib.request.Request(request["url"], method="HEAD"), timeout=20) as response:
        total = int(response.headers["Content-Length"])
    if not 0 < total <= MAX_ARCHIVE_BYTES + 1024 * 1024:
        raise ValueError("Artifact download is too large")
    print(f"Downloading release artifact over HTTPS ({total} bytes)", flush=True)
    workers = min(8, max(1, (total + 1024 * 1024 - 1) // (1024 * 1024)))
    deadline = time.monotonic() + 300

    def part(index):
        start = total * index // workers
        end = total * (index + 1) // workers - 1
        path = target.with_name(f"part-{index}")
        query = urllib.request.Request(request["url"], headers={"Range": f"bytes={start}-{end}"})
        with urllib.request.build_opener(NoRedirect()).open(query, timeout=20) as response:
            if (response.status != 206
                    or response.headers.get("Content-Range") != f"bytes {start}-{end}/{total}"):
                raise ValueError("Artifact storage did not honor the download range")
            received = 0
            with path.open("xb") as output:
                while True:
                    chunk = response.read(128 * 1024)
                    if not chunk:
                        break
                    received += len(chunk)
                    if received > end - start + 1 or time.monotonic() > deadline:
                        raise ValueError("Artifact range exceeded its size or time limit")
                    output.write(chunk)
            if received != end - start + 1:
                raise ValueError("Artifact range was incomplete")
        return path

    with ThreadPoolExecutor(max_workers=workers) as pool:
        parts = list(pool.map(part, range(workers)))
    with target.open("xb") as output:
        for path in parts:
            with path.open("rb") as source:
                shutil.copyfileobj(source, output)


def receive_artifact(artifact, request, stage):
    with zipfile.ZipFile(artifact) as archive:
        entries = archive.infolist()
        if (len(entries) != 1 or entries[0].filename != "release.tar.gz"
                or entries[0].file_size != request["bytes"]):
            raise ValueError("Unexpected files or sizes in release artifact")
        target = artifact.with_name("release.tar.gz")
        with archive.open(entries[0]) as source, target.open("xb") as output:
            shutil.copyfileobj(source, output)
    if digest(target) != request["sha256"]:
        raise ValueError("Downloaded release archive checksum verification failed")
    with target.open("rb") as source:
        manifest = receive(source, stage)
    if manifest["revision"] != request["revision"]:
        raise ValueError("Downloaded release revision differs from the request")
    return manifest


def receive_release(source, stage):
    if not source.peek(1).startswith(b"{"):
        return receive(source, stage)
    request = read_download_request(source)
    with tempfile.TemporaryDirectory(prefix=".download-", dir=RELEASES) as temporary:
        artifact = Path(temporary) / "artifact.zip"
        download_artifact(request, artifact)
        return receive_artifact(artifact, request, stage)


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
            if (not member.isfile() or member.size < 0 or not valid_member(name) or name in names
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
            manifest = receive_release(sys.stdin.buffer, stage)
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
    except (OSError, ValueError, KeyError, RuntimeError, subprocess.SubprocessError,
            tarfile.TarError, zipfile.BadZipFile) as error:
        print(f"Mini WMS deploy failed: {error}", file=sys.stderr)
        sys.exit(1)

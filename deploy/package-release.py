"""Package the built frontend and backend for the production publisher."""

import hashlib
import io
import json
from pathlib import Path
import subprocess
import sys
import tarfile


def digest(path):
    checksum = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            checksum.update(chunk)
    return checksum.hexdigest()


def main(output):
    root = Path(__file__).resolve().parent.parent
    jars = sorted((root / "backend/target").glob("miniwms-*.jar"))
    web = root / "frontend/dist"
    if len(jars) != 1 or not (web / "index.html").is_file():
        raise SystemExit("Build the backend JAR and frontend dist before packaging")

    def git_revision(ref):
        return subprocess.check_output(["git", "rev-parse", ref], cwd=root, text=True).strip()

    files = {"miniwms.jar": jars[0]}
    files.update({
        "public/" + path.relative_to(web).as_posix(): path
        for path in sorted(web.rglob("*")) if path.is_file()
    })
    metadata = {
        "revision": git_revision("HEAD"),
        "backendTree": git_revision("HEAD:backend"),
        "files": {name: digest(path) for name, path in files.items()},
    }
    manifest = json.dumps(metadata, sort_keys=True).encode()
    with tarfile.open(output, "w:gz", compresslevel=5) as archive:
        entry = tarfile.TarInfo("release.json")
        entry.size = len(manifest)
        archive.addfile(entry, io.BytesIO(manifest))
        for name, path in files.items():
            archive.add(path, arcname=name, recursive=False)
    print(f"Packaged release {metadata['revision']}")


if __name__ == "__main__":
    main(Path(sys.argv[1]))

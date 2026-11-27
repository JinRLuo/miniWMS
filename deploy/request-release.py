"""Create a small SSH request with a short-lived GitHub artifact download URL."""
import hashlib
import json
import os
from pathlib import Path
import re
import sys
import urllib.error
import urllib.request


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, response, code, message, headers, newurl):
        return None


def main():
    archive = Path(sys.argv[1])
    artifact = sys.argv[2]
    repository = os.environ["GITHUB_REPOSITORY"]
    if not artifact.isdigit() or not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", repository):
        raise ValueError("Invalid GitHub artifact identity")
    checksum = hashlib.sha256()
    with archive.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            checksum.update(chunk)
    request = urllib.request.Request(
        f"https://api.github.com/repos/{repository}/actions/artifacts/{artifact}/zip",
        headers={"Authorization": "Bearer " + os.environ["GH_TOKEN"],
                 "Accept": "application/vnd.github+json", "X-GitHub-Api-Version": "2026-03-10"},
    )
    try:
        with urllib.request.build_opener(NoRedirect()).open(request, timeout=20):
            raise RuntimeError("GitHub did not return an artifact redirect")
    except urllib.error.HTTPError as response:
        if response.code != 302:
            raise
        url = response.headers["Location"]
        response.close()
    print(json.dumps({"version": 1, "revision": os.environ["GITHUB_SHA"],
                      "bytes": archive.stat().st_size, "sha256": checksum.hexdigest(), "url": url}))


if __name__ == "__main__":
    main()

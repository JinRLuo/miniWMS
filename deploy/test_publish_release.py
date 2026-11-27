import hashlib
import importlib.util
import io
import json
from pathlib import Path
import re
import tarfile
import tempfile
import unittest
from unittest.mock import patch
import zipfile


spec = importlib.util.spec_from_file_location("publisher", Path(__file__).with_name("publish-release.py"))
publisher = importlib.util.module_from_spec(spec)
spec.loader.exec_module(publisher)


class PublisherArtifactTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.stage = self.root / "stage"
        self.stage.mkdir()
        files = {"miniwms.jar": b"test jar", "public/index.html": b"test frontend"}
        self.manifest = {"revision": "a" * 40, "backendTree": "b" * 40,
                         "files": {name: hashlib.sha256(data).hexdigest() for name, data in files.items()}}
        self.payload = self.tar({"release.json": json.dumps(self.manifest).encode(), **files})
        self.request = {"version": 1, "revision": "a" * 40, "bytes": len(self.payload),
                        "sha256": hashlib.sha256(self.payload).hexdigest(),
                        "url": "https://productionresultssa0.blob.core.windows.net/actions/artifact?sig=test"}

    def tar(self, files):
        target = io.BytesIO()
        with tarfile.open(fileobj=target, mode="w:gz") as archive:
            for name, data in files.items():
                entry = tarfile.TarInfo(name)
                entry.size = len(data)
                archive.addfile(entry, io.BytesIO(data))
        return target.getvalue()

    def artifact(self, files=None):
        target = self.root / "artifact.zip"
        with zipfile.ZipFile(target, "w") as archive:
            for name, data in (files or {"release.tar.gz": self.payload}).items():
                archive.writestr(name, data)
        return target

    def parse(self, **changes):
        return publisher.read_download_request(io.BytesIO(json.dumps({**self.request, **changes}).encode()))

    def test_rejects_urls_outside_github_storage_before_network_access(self):
        for url in ["http://x.blob.core.windows.net/a", "https://127.0.0.1/a", "file:///etc/passwd",
                    "https://x.blob.core.windows.net.evil.test/a", "https://user@x.blob.core.windows.net/a",
                    "https://x.blob.core.windows.net:8443/a", "https://x.blob.core.windows.net/a#fragment", None]:
            with self.subTest(url=url), self.assertRaises(ValueError):
                self.parse(url=url)

    def test_rejects_invalid_metadata_and_oversized_requests(self):
        for changes in [{"bytes": True}, {"bytes": 0}, {"bytes": publisher.MAX_ARCHIVE_BYTES + 1},
                        {"sha256": "wrong"}, {"revision": "main"}, {"version": 2}]:
            with self.subTest(changes=changes), self.assertRaises(ValueError):
                self.parse(**changes)
        for payload in [b"[]", b"{" + b" " * publisher.MAX_REQUEST_BYTES]:
            with self.assertRaises(ValueError):
                publisher.read_download_request(io.BytesIO(payload))

    def test_valid_artifact_preserves_manifest_and_backend(self):
        self.assertEqual(publisher.receive_artifact(self.artifact(), self.request, self.stage), self.manifest)
        self.assertEqual((self.stage / "miniwms.jar").read_bytes(), b"test jar")

    def test_archive_checksum_mismatch_never_extracts_release(self):
        with self.assertRaisesRegex(ValueError, "checksum"):
            publisher.receive_artifact(self.artifact(), {**self.request, "sha256": "0" * 64}, self.stage)
        self.assertEqual(list(self.stage.iterdir()), [])

    def test_wrong_revision_is_rejected(self):
        with self.assertRaisesRegex(ValueError, "revision"):
            publisher.receive_artifact(self.artifact(), {**self.request, "revision": "c" * 40}, self.stage)

    def test_extra_zip_paths_and_expanded_size_are_rejected(self):
        for files in [{"../release.tar.gz": self.payload},
                      {"release.tar.gz": self.payload, "unexpected": b"extra"},
                      {"release.tar.gz": b"short"}]:
            with self.subTest(files=list(files)), self.assertRaises(ValueError):
                publisher.receive_artifact(self.artifact(files), self.request, self.stage)
        self.assertEqual(list(self.stage.iterdir()), [])

    def test_tar_traversal_and_modified_files_are_rejected(self):
        with self.assertRaises(ValueError):
            publisher.receive(io.BytesIO(self.tar({"../escape": b"bad"})), self.stage)
        self.assertFalse((self.root / "escape").exists())
        tampered = self.tar({"release.json": json.dumps(self.manifest).encode(),
                             "miniwms.jar": b"modified", "public/index.html": b"test frontend"})
        with self.assertRaisesRegex(ValueError, "checksum"):
            publisher.receive(io.BytesIO(tampered), self.stage)

    def test_range_download_assembles_exact_bytes_and_rejects_ignored_ranges(self):
        data = b"artifact data" * 100_000

        class Response(io.BytesIO):
            def __init__(self, payload, status, headers):
                super().__init__(payload)
                self.status, self.headers = status, headers

        class Opener:
            ignore_range = False

            def open(self, request, timeout):
                if request.get_method() == "HEAD":
                    return Response(b"", 200, {"Content-Length": str(len(data))})
                start, end = map(int, re.fullmatch(r"bytes=(\d+)-(\d+)", request.get_header("Range")).groups())
                return Response(data[start:end + 1], 200 if self.ignore_range else 206,
                                {"Content-Range": f"bytes {start}-{end}/{len(data)}"})

        opener = Opener()
        with patch.object(publisher.urllib.request, "build_opener", return_value=opener):
            target = self.root / "download.zip"
            publisher.download_artifact(self.request, target)
            self.assertEqual(target.read_bytes(), data)
            opener.ignore_range = True
            with self.assertRaisesRegex(ValueError, "range"):
                publisher.download_artifact(self.request, self.root / "rejected.zip")


if __name__ == "__main__":
    unittest.main()

#!/usr/bin/env python3
"""Download and safely extract one exact GitHub Actions artifact by immutable ID."""

from __future__ import annotations

import argparse
import hashlib
import os
import re
import shutil
import stat
import sys
import tempfile
import urllib.error
import urllib.parse
import urllib.request
import zipfile
from pathlib import Path, PurePosixPath

MAX_ARCHIVE_BYTES = 256 * 1024 * 1024
MAX_UNCOMPRESSED_BYTES = 256 * 1024 * 1024
MAX_FILE_BYTES = 16 * 1024 * 1024
MAX_ENTRIES = 512
MAX_COMPRESSION_RATIO = 200
REPOSITORY = re.compile(r"^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$")
DIGEST = re.compile(r"^sha256:[0-9a-f]{64}$")


class ArtifactDownloadError(RuntimeError):
    """Raised before or during bounded artifact extraction."""


def _safe_name(name: str) -> tuple[str, bool]:
    directory = name.endswith("/")
    trimmed = name[:-1] if directory else name
    path = PurePosixPath(trimmed)
    if (
        not trimmed
        or path.is_absolute()
        or trimmed != path.as_posix()
        or any(part in {"", ".", ".."} for part in path.parts)
        or "\\" in name
        or ":" in name
        or "\x00" in name
        or len(name.encode("utf-8")) > 512
    ):
        raise ArtifactDownloadError(f"artifact ZIP has unsafe entry {name!r}")
    return trimmed, directory


def _entry_type(info: zipfile.ZipInfo, directory: bool) -> None:
    if info.flag_bits & 1:
        raise ArtifactDownloadError(f"artifact ZIP entry is encrypted: {info.filename!r}")
    if info.compress_type not in {zipfile.ZIP_STORED, zipfile.ZIP_DEFLATED}:
        raise ArtifactDownloadError(f"artifact ZIP entry uses an unapproved compression method: {info.filename!r}")
    mode = (info.external_attr >> 16) & 0xFFFF
    kind = stat.S_IFMT(mode)
    if kind and not (directory and kind == stat.S_IFDIR) and not (not directory and kind == stat.S_IFREG):
        raise ArtifactDownloadError(f"artifact ZIP contains a symlink/special entry: {info.filename!r}")


def extract_archive(archive: Path, output: Path, *, expected_digest: str) -> dict[str, int]:
    if DIGEST.fullmatch(expected_digest) is None:
        raise ArtifactDownloadError("expected artifact digest is invalid")
    try:
        info = archive.lstat()
    except OSError as exc:
        raise ArtifactDownloadError(f"cannot inspect artifact archive: {exc}") from exc
    if stat.S_ISLNK(info.st_mode) or not stat.S_ISREG(info.st_mode) or not 0 < info.st_size <= MAX_ARCHIVE_BYTES:
        raise ArtifactDownloadError("artifact archive is not a bounded regular file")
    digest = hashlib.sha256()
    with archive.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    if "sha256:" + digest.hexdigest() != expected_digest:
        raise ArtifactDownloadError("artifact archive SHA-256 digest mismatch")
    if output.exists() or output.is_symlink():
        raise ArtifactDownloadError(f"refusing to replace extraction output {output}")
    output.parent.mkdir(parents=True, exist_ok=True)
    stage = Path(tempfile.mkdtemp(prefix=f".{output.name}.extracting-", dir=output.parent))
    files = 0
    total = 0
    try:
        try:
            package = zipfile.ZipFile(archive)
        except (OSError, zipfile.BadZipFile) as exc:
            raise ArtifactDownloadError(f"artifact is not a valid ZIP: {exc}") from exc
        with package:
            entries = package.infolist()
            if not entries or len(entries) > MAX_ENTRIES:
                raise ArtifactDownloadError("artifact ZIP entry count is outside its bound")
            names: set[str] = set()
            validated: list[tuple[zipfile.ZipInfo, str, bool]] = []
            for entry in entries:
                relative, directory = _safe_name(entry.filename)
                if relative in names:
                    raise ArtifactDownloadError(f"artifact ZIP has duplicate path {relative!r}")
                names.add(relative)
                _entry_type(entry, directory)
                if directory:
                    if entry.file_size != 0:
                        raise ArtifactDownloadError("artifact ZIP directory contains data")
                else:
                    files += 1
                    total += entry.file_size
                    if entry.file_size <= 0 or entry.file_size > MAX_FILE_BYTES:
                        raise ArtifactDownloadError(f"artifact ZIP file size is outside its bound: {relative}")
                    if entry.compress_size <= 0 and entry.file_size > 0:
                        raise ArtifactDownloadError(f"artifact ZIP compressed size is invalid: {relative}")
                    if entry.file_size > entry.compress_size * MAX_COMPRESSION_RATIO:
                        raise ArtifactDownloadError(f"artifact ZIP compression ratio is excessive: {relative}")
                validated.append((entry, relative, directory))
            if files <= 0 or total > MAX_UNCOMPRESSED_BYTES:
                raise ArtifactDownloadError("artifact ZIP uncompressed inventory exceeds its bound")
            for entry, relative, directory in validated:
                destination = stage.joinpath(*PurePosixPath(relative).parts)
                if directory:
                    destination.mkdir(parents=True, exist_ok=True)
                    continue
                destination.parent.mkdir(parents=True, exist_ok=True)
                copied = 0
                try:
                    with package.open(entry, "r") as source, destination.open("xb") as target:
                        while True:
                            chunk = source.read(1024 * 1024)
                            if not chunk:
                                break
                            copied += len(chunk)
                            if copied > entry.file_size or copied > MAX_FILE_BYTES:
                                raise ArtifactDownloadError(f"artifact ZIP entry grew while extracting: {relative}")
                            target.write(chunk)
                        target.flush()
                        os.fsync(target.fileno())
                except (OSError, RuntimeError, zipfile.BadZipFile) as exc:
                    raise ArtifactDownloadError(f"cannot extract artifact ZIP entry {relative}: {exc}") from exc
                if copied != entry.file_size:
                    raise ArtifactDownloadError(f"artifact ZIP entry size changed: {relative}")
                os.chmod(destination, 0o644)
        stage.rename(output)
        return {"files": files, "bytes": total, "archive_bytes": info.st_size}
    finally:
        if stage.exists() and not stage.is_symlink():
            shutil.rmtree(stage)


class _CredentialSafeRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, fp, code, msg, headers, new_url):  # type: ignore[no-untyped-def]
        redirected = super().redirect_request(request, fp, code, msg, headers, new_url)
        if redirected is not None and urllib.parse.urlsplit(request.full_url).netloc != urllib.parse.urlsplit(new_url).netloc:
            redirected.remove_header("Authorization")
        return redirected


def download(*, repository: str, artifact_id: int, digest: str, output: Path, token: str, api_url: str) -> dict[str, int]:
    if REPOSITORY.fullmatch(repository) is None:
        raise ArtifactDownloadError("repository must use owner/name form")
    if isinstance(artifact_id, bool) or artifact_id <= 0:
        raise ArtifactDownloadError("artifact id must be positive")
    if DIGEST.fullmatch(digest) is None:
        raise ArtifactDownloadError("artifact digest is invalid")
    parsed_api = urllib.parse.urlsplit(api_url)
    if parsed_api.scheme != "https" or not parsed_api.netloc:
        raise ArtifactDownloadError("GitHub API URL must be absolute HTTPS")
    output = output.absolute()
    output.parent.mkdir(parents=True, exist_ok=True)
    descriptor, temporary_name = tempfile.mkstemp(prefix=".pages-artifact-", suffix=".zip", dir=output.parent)
    os.close(descriptor)
    temporary = Path(temporary_name)
    try:
        request = urllib.request.Request(
            f"{api_url.rstrip('/')}/repos/{repository}/actions/artifacts/{artifact_id}/zip",
            headers={
                "Accept": "application/vnd.github+json",
                "Authorization": f"Bearer {token}",
                "X-GitHub-Api-Version": "2022-11-28",
                "User-Agent": "BlockPops-pages-artifact",
            },
        )
        opener = urllib.request.build_opener(_CredentialSafeRedirect())
        try:
            with opener.open(request, timeout=60) as response, temporary.open("wb") as target:
                copied = 0
                while True:
                    chunk = response.read(1024 * 1024)
                    if not chunk:
                        break
                    copied += len(chunk)
                    if copied > MAX_ARCHIVE_BYTES:
                        raise ArtifactDownloadError("artifact archive download exceeds its byte bound")
                    target.write(chunk)
                target.flush()
                os.fsync(target.fileno())
        except (OSError, urllib.error.HTTPError) as exc:
            raise ArtifactDownloadError(f"artifact download failed: {exc}") from exc
        return extract_archive(temporary, output, expected_digest=digest)
    finally:
        temporary.unlink(missing_ok=True)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repository", required=True)
    parser.add_argument("--artifact-id", type=int, required=True)
    parser.add_argument("--digest", required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args(argv)
    try:
        token = os.environ.get("GH_TOKEN", "")
        if not token:
            raise ArtifactDownloadError("GH_TOKEN is required")
        result = download(
            repository=args.repository,
            artifact_id=args.artifact_id,
            digest=args.digest,
            output=args.output,
            token=token,
            api_url=os.environ.get("GITHUB_API_URL", "https://api.github.com"),
        )
        print(result)
        return 0
    except (ArtifactDownloadError, OSError, ValueError) as exc:
        print(f"Pages artifact error: {exc}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())

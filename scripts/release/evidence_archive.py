"""Extract a byte-bound producer ZIP; source authentication remains external."""

import hashlib
import os
import re
import stat
import struct
import zipfile
from pathlib import Path

from scripts.lib import atomic_directory
from scripts.pages.download_artifact import ArtifactDownloadError, _entry_type, _safe_name
from scripts.release.run_evidence import ARTIFACT_LIMITS

# Independent of Pages' smaller image-cache limits. Content readers subsequently
# enforce exact bundle/report/aggregate inventories, identities and pixel rules.
PROFILES = {"bundle": (64, 256 * 1024 * 1024, 512 * 1024 * 1024, "artifacts.json"),
            "report": (1, 8 * 1024 * 1024, 8 * 1024 * 1024, "build-matrix-report.json"),
            "aggregate": (8192, 32 * 1024 * 1024, 512 * 1024 * 1024, "aggregate.json")}


class EvidenceArchiveError(ValueError):
    """An archive or output does not retain its independent byte binding."""


def _check(condition, message):
    if not condition:
        raise EvidenceArchiveError(message)


def _stamp(info):
    return (info.st_dev, info.st_ino, info.st_mode, info.st_nlink, info.st_size,
            info.st_mtime_ns, info.st_ctime_ns)


def _bounded_directory(source, size, limit):
    """Bound actual central records before ZipFile allocates its entry objects.

    These bounded Actions ZIPs require neither multipart nor ZIP64 containers.
    Reject both explicitly rather than interpreting sentinel sizes as limits.
    """
    source.seek(max(0, size - 65557)); tail = source.read(65557)
    position = tail.rfind(b"PK\x05\x06")
    _check(position >= 0 and len(tail) - position >= 22, "missing ZIP end record")
    _, disk, directory_disk, local_count, count, length, offset, comment = struct.unpack_from("<4s4H2IH", tail, position)
    _check(disk == directory_disk == 0 and local_count == count and 0 < count <= limit
           and position + 22 + comment == len(tail) and offset + length == size - 22 - comment,
           "unsupported or oversized ZIP directory")
    cursor = offset
    for _ in range(count):
        source.seek(cursor); header = source.read(46)
        _check(len(header) == 46 and header[:4] == b"PK\x01\x02", "invalid ZIP directory entry")
        name, extra, annotation = struct.unpack_from("<HHH", header, 28)
        _check(0 < name <= 512, "ZIP directory name exceeds its bound")
        raw_name = source.read(name)
        _check(len(raw_name) == name and b"\0" not in raw_name, "ZIP directory name contains NUL")
        cursor += 46 + name + extra + annotation
        _check(cursor <= offset + length, "ZIP directory exceeds its declared bound")
    _check(cursor == offset + length, "ZIP directory count differs from actual records")
    return count


def _verify_stage(stage, files, directories):
    observed_files, observed_directories = set(), set()
    def walk(descriptor, prefix):
        with os.scandir(descriptor) as entries:
            for entry in entries:
                relative = prefix + entry.name
                before = os.stat(entry.name, dir_fd=descriptor, follow_symlinks=False)
                if stat.S_ISDIR(before.st_mode):
                    _check(relative in directories, "unlisted extracted directory")
                    child = os.open(entry.name, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW, dir_fd=descriptor)
                    try:
                        _check(atomic_directory._directory_identity(before)
                               == atomic_directory._directory_identity(os.fstat(child)), "extracted directory changed")
                        observed_directories.add(relative); walk(child, relative + "/")
                    finally:
                        os.close(child)
                    _check(_stamp(os.stat(entry.name, dir_fd=descriptor, follow_symlinks=False)) == _stamp(before),
                           "extracted directory changed during closure")
                else:
                    _check(relative in files and _stamp(before) == files[relative], "extracted file or inventory changed")
                    observed_files.add(relative)
    walk(stage, "")
    _check(observed_files == set(files) and observed_directories == directories, "extracted inventory is incomplete")


def extract_evidence_archive(archive, output, *, kind, expected_digest, expected_size):
    """Consume the externally authenticated ZIP digest/size, never its manifest's claim.

    The caller must authenticate the immutable artifact ID and producer attempt,
    obtain these bindings from that API record and later recheck current producer
    state. This API neither downloads nor authenticates ownership/freshness. It
    creates an exclusive output only after validating all extracted bytes.
    """
    try:
        _check(kind in PROFILES, "unsupported evidence archive kind")
        _check(type(expected_size) is int and 0 < expected_size <= ARTIFACT_LIMITS[kind],
               "invalid external archive size")
        _check(isinstance(expected_digest, str) and re.fullmatch("sha256:[0-9a-f]{64}", expected_digest),
               "invalid external archive digest")
        archive, output = Path(archive).absolute(), Path(output).absolute()
        entry_limit, file_limit, total_limit, required = PROFILES[kind]
        parent = atomic_directory._directory_fd(archive.parent)
        try:
            descriptor = os.open(archive.name, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK, dir_fd=parent)
            with os.fdopen(descriptor, "rb") as source:
                before = os.fstat(source.fileno())
                _check(stat.S_ISREG(before.st_mode) and before.st_nlink == 1 and before.st_size == expected_size,
                       "archive is not the expected regular unlinked file")

                def unchanged():
                    current = atomic_directory._directory_fd(archive.parent)
                    try:
                        _check(atomic_directory._directory_identity(os.fstat(current))
                               == atomic_directory._directory_identity(os.fstat(parent)), "archive parent changed")
                    finally:
                        os.close(current)
                    _check(_stamp(os.fstat(source.fileno())) == _stamp(before)
                           == _stamp(os.stat(archive.name, dir_fd=parent, follow_symlinks=False)), "archive changed")

                def digest():
                    source.seek(0)
                    return "sha256:" + hashlib.file_digest(source, "sha256").hexdigest()

                _check(digest() == expected_digest, "archive digest mismatch")
                unchanged()
                count = _bounded_directory(source, expected_size, entry_limit)
                with zipfile.ZipFile(source) as package:
                    entries = package.infolist()
                    _check(len(entries) == count, "archive entry count differs from its bounded directory")
                    validated, names, directories, total = [], set(), set(), 0
                    for entry in entries:
                        _check(entry.orig_filename == entry.filename, "archive filename was normalized")
                        relative, directory = _safe_name(entry.filename)
                        directories.update(str(parent) for parent in Path(relative).parents if str(parent) != ".")
                        _check(relative not in names, "duplicate archive path")
                        names.add(relative); _entry_type(entry, directory)
                        if directory:
                            directories.add(relative)
                            _check(entry.file_size == 0, "archive directory contains data")
                        else:
                            empty_log = kind == "aggregate" and "/logs/" in "/" + relative and relative.endswith(".log")
                            _check((entry.file_size > 0 or empty_log) and entry.file_size <= file_limit,
                                   "archive file size exceeds its bound")
                            _check(entry.file_size <= max(1, entry.compress_size) * 200,
                                   "archive compression ratio exceeds its bound")
                            total += entry.file_size
                        _check(len(directories) <= entry_limit, "archive physical inventory exceeds its bound")
                        validated.append((entry, relative, directory))
                    files = {name for _, name, directory in validated if not directory}
                    _check(not files & directories and len(files) + len(directories) <= entry_limit,
                           "archive physical inventory exceeds its bound or has conflicting paths")
                    _check(total <= total_limit and required in files,
                           "archive lacks its required root input or exceeds its expanded bound")

                    def write(stage_path, stage):
                        records, stamps = [], {}
                        for entry, relative, directory in validated:
                            if directory:
                                child = atomic_directory._directory_fd(Path(relative), root_fd=stage, create=True)
                                os.close(child)
                                continue
                            with package.open(entry) as incoming:
                                raw = incoming.read(entry.file_size + 1)
                            _check(len(raw) == entry.file_size, "archive payload size changed")
                            atomic_directory.write_new(stage, relative, raw)
                            records.append({"path": relative, "bytes": len(raw), "sha256": hashlib.sha256(raw).hexdigest()})
                        for record in records:
                            path = Path(record["path"])
                            directory = atomic_directory._directory_fd(path.parent, root_fd=stage)
                            try:
                                descriptor = os.open(path.name, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK, dir_fd=directory)
                                with os.fdopen(descriptor, "rb") as verified:
                                    before_file = os.fstat(verified.fileno())
                                    _check(stat.S_ISREG(before_file.st_mode) and before_file.st_nlink == 1
                                           and before_file.st_size == record["bytes"], "extracted file changed")
                                    _check(hashlib.file_digest(verified, "sha256").hexdigest() == record["sha256"]
                                           and _stamp(os.fstat(verified.fileno())) == _stamp(before_file)
                                           == _stamp(os.stat(path.name, dir_fd=directory, follow_symlinks=False)),
                                           "extracted file bytes or identity changed")
                                    stamps[record["path"]] = _stamp(before_file)
                            finally:
                                os.close(directory)
                        _check(digest() == expected_digest, "archive digest changed during extraction")
                        unchanged()
                        _verify_stage(stage, stamps, directories)
                        return {"kind": kind, "archive_sha256": expected_digest[7:], "archive_bytes": expected_size,
                                "files": sorted(records, key=lambda record: record["path"])}

                    return atomic_directory.atomic_directory(output, write)
        finally:
            os.close(parent)
    except (OSError, ValueError, RuntimeError, KeyError, TypeError, zipfile.BadZipFile, ArtifactDownloadError) as exc:
        raise EvidenceArchiveError(f"invalid evidence archive: {exc}") from exc

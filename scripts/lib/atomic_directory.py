"""Descriptor-bound exclusive directory publication for validated local artifacts."""

import ctypes
import os
import stat
import sys
import uuid
from pathlib import Path
from typing import Any


class AtomicDirectoryError(ValueError):
    """Raised when an owned output cannot be safely written or published."""


def _real_directory(path):
    try:
        info = path.lstat()
    except OSError as exc:
        raise AtomicDirectoryError(f"cannot inspect output parent: {exc}") from exc
    if stat.S_ISLNK(info.st_mode) or not stat.S_ISDIR(info.st_mode):
        raise AtomicDirectoryError("output parent must be a real directory")


def _relative_path(value):
    if not isinstance(value, str) or not value or "\\" in value or "\x00" in value or ":" in value:
        raise AtomicDirectoryError("output path must be a canonical relative path")
    path = Path(value)
    if path.is_absolute() or value != path.as_posix() or any(part in {"", ".", ".."} for part in path.parts):
        raise AtomicDirectoryError("output path must be a canonical relative path")
    return value


def _exclusive_directory_rename():
    required = (os.open, os.mkdir, os.stat, os.unlink, os.rmdir)
    if (not all(call in os.supports_dir_fd for call in required) or os.listdir not in os.supports_fd
            or not hasattr(os, "O_DIRECTORY") or not hasattr(os, "O_NOFOLLOW")):
        raise AtomicDirectoryError("output publication requires descriptor-relative filesystem primitives")
    library = ctypes.CDLL(None, use_errno=True)
    name, flag = {"darwin": ("renameatx_np", 4), "linux": ("renameat2", 1)}.get(sys.platform, (None, None))
    function = getattr(library, name, None) if name else None
    if function is None:
        raise AtomicDirectoryError("output publication requires atomic exclusive directory rename")
    function.argtypes = [ctypes.c_int, ctypes.c_char_p, ctypes.c_int, ctypes.c_char_p, ctypes.c_uint]
    function.restype = ctypes.c_int
    def rename(parent, source, destination):
        if function(parent, os.fsencode(source), parent, os.fsencode(destination), flag):
            error = ctypes.get_errno()
            raise AtomicDirectoryError(f"exclusive output publication failed: {os.strerror(error)}")
    return rename


def _directory_fd(path: Path, *, root_fd=None, create=False):
    flags = os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW
    # An ancestor only has to be passed through, and the CI sandbox makes its
    # boundary search-only (0711) on purpose, so opening it for reading is
    # refused. Linux can hold a directory by path alone, still refusing a
    # symlink at every step; every component but the last is opened that way,
    # and the descriptor handed back stays a readable one.
    through = os.O_PATH | os.O_DIRECTORY | os.O_NOFOLLOW if hasattr(os, "O_PATH") else flags
    parts = path.parts if root_fd is not None else path.parts[1:]
    descriptor = (os.dup(root_fd) if root_fd is not None
                  else os.open(path.anchor, through if parts else flags))
    try:
        for index, part in enumerate(parts):
            if create:
                try: os.mkdir(part, 0o700, dir_fd=descriptor)
                except FileExistsError: pass
            child = os.open(part, flags if index == len(parts) - 1 else through, dir_fd=descriptor)
            os.close(descriptor)
            descriptor = child
        return descriptor
    except BaseException:
        os.close(descriptor)
        raise


def _directory_identity(info):
    return info.st_dev, info.st_ino


def _bound_output_directory(output, parent, name, stage):
    try:
        current = _directory_fd(output.parent)
        try:
            same_parent = _directory_identity(os.fstat(current)) == _directory_identity(os.fstat(parent))
        finally:
            os.close(current)
        entry = os.stat(name, dir_fd=parent, follow_symlinks=False)
        if (not same_parent or not stat.S_ISDIR(entry.st_mode)
                or _directory_identity(entry) != _directory_identity(os.fstat(stage))):
            raise AtomicDirectoryError("output parent or stage identity changed")
    except OSError as exc:
        raise AtomicDirectoryError("output parent or stage binding changed") from exc


def _clear_owned_directory(descriptor):
    for name in os.listdir(descriptor):
        try:
            before = os.stat(name, dir_fd=descriptor, follow_symlinks=False)
            if stat.S_ISDIR(before.st_mode):
                child = os.open(name, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW, dir_fd=descriptor)
                try:
                    if _directory_identity(before) != _directory_identity(os.fstat(child)):
                        raise AtomicDirectoryError("output cleanup directory changed")
                    _clear_owned_directory(child)
                finally:
                    os.close(child)
                if _directory_identity(os.stat(name, dir_fd=descriptor, follow_symlinks=False)) == _directory_identity(before):
                    os.rmdir(name, dir_fd=descriptor)
            else:
                os.unlink(name, dir_fd=descriptor)
        except FileNotFoundError:
            pass


def atomic_directory(output: Path, writer: Any) -> Any:
    rename = _exclusive_directory_rename()  # Fail before creating directories on unsupported hosts.
    output = output.absolute()
    if output.parent.exists() or output.parent.is_symlink():
        _real_directory(output.parent)
    output = output.parent.resolve() / output.name  # Resolve stable host aliases such as macOS /var once.
    parent = _directory_fd(output.parent, create=True)
    stage = None
    name = f".{output.name}.building-{uuid.uuid4().hex}"
    published = False
    try:
        try:
            os.stat(output.name, dir_fd=parent, follow_symlinks=False)
        except FileNotFoundError:
            pass
        else:
            raise AtomicDirectoryError(f"refusing to replace existing output {output}")
        os.mkdir(name, 0o700, dir_fd=parent)
        stage = os.open(name, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW, dir_fd=parent)
        _bound_output_directory(output, parent, name, stage)
        result = writer(output.parent / name, stage)
        _bound_output_directory(output, parent, name, stage)
        rename(parent, name, output.name)
        name = output.name
        _bound_output_directory(output, parent, name, stage)
        published = True
        return result
    finally:
        try:
            if stage is not None and not published:
                _clear_owned_directory(stage)
                try:
                    if _directory_identity(os.stat(name, dir_fd=parent, follow_symlinks=False)) == _directory_identity(os.fstat(stage)):
                        os.rmdir(name, dir_fd=parent)
                except FileNotFoundError:
                    pass
        finally:
            if stage is not None: os.close(stage)
            os.close(parent)


def write_new(stage: int, relative: str, data: bytes) -> None:
    path = Path(_relative_path(relative))
    parent = _directory_fd(path.parent, root_fd=stage, create=True)
    try:
        descriptor = os.open(path.name, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600, dir_fd=parent)
        with os.fdopen(descriptor, "wb") as output:
            output.write(data)
            output.flush()
            os.fchmod(output.fileno(), 0o644)
            os.fsync(output.fileno())
    finally:
        os.close(parent)

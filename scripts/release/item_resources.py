"""Derive modern special-item resources from the authored entity models."""

from __future__ import annotations

import argparse
from contextlib import ExitStack
import os
from pathlib import Path
import re
import stat
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[2]))
from scripts.lib.secure_json import canonical_json, loads  # noqa: E402


def _directory(stack: ExitStack, path: Path, *, create: bool = False) -> int:
    descriptor = os.open(path.anchor, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
    stack.callback(os.close, descriptor)
    for component in path.parts[1:]:
        if create:
            try:
                os.mkdir(component, dir_fd=descriptor)
            except FileExistsError:
                pass
        descriptor = os.open(component, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW, dir_fd=descriptor)
        stack.callback(os.close, descriptor)
    return descriptor


def _json(parent: int, name: str):
    descriptor = os.open(name, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK, dir_fd=parent)
    with os.fdopen(descriptor, "rb") as stream:
        info = os.fstat(stream.fileno())
        if not stat.S_ISREG(info.st_mode) or info.st_size > 65536:
            raise ValueError("resource JSON must be a bounded regular file")
        payload = stream.read(65537)
        after = os.fstat(stream.fileno())
        if (after.st_dev, after.st_ino, after.st_size, after.st_mtime_ns) != (
                info.st_dev, info.st_ino, info.st_size, info.st_mtime_ns):
            raise ValueError("resource JSON changed during read")
        return loads(payload, label="resource JSON", max_bytes=65536)


def resource_plan(source: Path, minecraft: str) -> dict[str, bytes]:
    if not re.fullmatch(r"[0-9]+(?:\.[0-9]+){1,2}", minecraft):
        raise ValueError("invalid Minecraft resource version")
    version = tuple(int(part) for part in minecraft.split("."))
    if version < (1, 21, 4):
        return {}
    result = {}
    for kind in ("item", "block"):
        directory = source / "assets/blockpops/models" / kind
        with ExitStack() as stack:
            descriptor = _directory(stack, directory.absolute())
            names = sorted(os.listdir(descriptor))
            if len(names) > 512:
                raise ValueError("too many authored models")
            for name in names:
                if not re.fullmatch(r"[a-z0-9_]+\.json", name):
                    raise ValueError("invalid authored model filename")
                model = _json(descriptor, name)
                if not isinstance(model, dict):
                    raise ValueError("authored model must be an object")
                if model.get("parent") != "builtin/entity":
                    continue
                base = f"assets/blockpops/models/{kind}/{name}"
                if kind == "block":
                    result[base] = canonical_json({**model, "parent": "minecraft:block/block"})
                    continue
                stem = name[:-5]
                renderer = "box_block" if stem == "box_block" or stem.startswith("box_block_") else stem
                if renderer not in {"box_block", "figure_block", "claw_machine_block"}:
                    raise ValueError("entity item has no registered special renderer")
                result[base] = canonical_json({**model, "parent": "minecraft:item/template_shulker_box"})
                result[f"assets/blockpops/items/{name}"] = canonical_json({"model": {
                    "type": "minecraft:special", "base": f"blockpops:item/{stem}",
                    "model": {"type": f"blockpops:{renderer}"}}})
    return result


def generate(source: Path, output: Path, minecraft: str) -> None:
    plan = resource_plan(source, minecraft)
    with ExitStack() as stack:
        root = _directory(stack, output.absolute(), create=True)
        try:
            previous = _json(root, ".inventory.json")
        except FileNotFoundError:
            previous = []
        if not isinstance(previous, list) or len(previous) > 1024 or any(
                not isinstance(path, str) or not re.fullmatch(
                    r"assets/blockpops/(?:items|models/(?:item|block))/[a-z0-9_]+\.json", path)
                for path in previous):
            raise ValueError("invalid generated resource inventory")
        for relative in sorted(set(previous) - set(plan)):
            with ExitStack() as parents:
                parent = _directory(parents, output.absolute() / Path(relative).parent)
                try:
                    os.unlink(Path(relative).name, dir_fd=parent)
                except FileNotFoundError:
                    pass
        for relative, payload in {**plan, ".inventory.json": canonical_json(sorted(plan))}.items():
            with ExitStack() as parents:
                parent = _directory(parents, output.absolute() / Path(relative).parent, create=True)
                descriptor = os.open(Path(relative).name, os.O_WRONLY | os.O_CREAT | os.O_NOFOLLOW,
                                     0o644, dir_fd=parent)
                with os.fdopen(descriptor, "wb") as stream:
                    info = os.fstat(stream.fileno())
                    if not stat.S_ISREG(info.st_mode) or info.st_nlink != 1:
                        raise ValueError("generated resource is not an owned regular file")
                    stream.truncate(0)
                    stream.write(payload + b"\n")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--minecraft", required=True)
    arguments = parser.parse_args()
    try:
        generate(arguments.source, arguments.output, arguments.minecraft)
    except (OSError, ValueError) as error:
        print(f"item resources: {str(error).splitlines()[0][:240]}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

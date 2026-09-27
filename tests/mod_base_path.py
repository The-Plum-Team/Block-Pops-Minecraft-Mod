"""Put the pinned, verified mod-base kit on ``sys.path`` for Block Pops tests.

Tests that need the kit call :func:`kit_root`. It finds the kit only through the managed bootstrap
``scripts/ci/mod_base_kit.py`` (the sandbox overlay ``out/mod-base-kit``, then the ``setup``
composite's ``MOD_BASE_KIT_PATH``, then the user cache, then the anonymous fetch of the pin), and
it never skips: an unavailable kit raises :class:`RuntimeError`, which fails the run.

The bootstrap refuses a kit tree that holds bytecode, because Python would load a planted
``__pycache__`` file in place of the verified source. The sandbox overlay and the directory named by
``MOD_BASE_KIT_PATH`` are verified again later in the same job, so a test run must never write
bytecode into them. Importing this module therefore turns bytecode writing off for this process
(``sys.dont_write_bytecode``) and, through ``PYTHONDONTWRITEBYTECODE=1`` in the environment, for
every child process started after it: the parallel runner's workers and any ``python3 -m mod_base``
or bootstrap subprocess a test starts.
"""

from __future__ import annotations

import os
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parents[1]


def disable_bytecode() -> None:
    """Never write bytecode, in this process or in a child process it starts from now on."""

    sys.dont_write_bytecode = True
    os.environ["PYTHONDONTWRITEBYTECODE"] = "1"


disable_bytecode()


def kit_root() -> Path:
    """Resolve and verify the pinned kit, put ``<kit>/src`` first on ``sys.path``, return the root.

    Raises :class:`RuntimeError` when the kit is unavailable or when ``mod_base`` was already
    imported from somewhere else, so that a test can never run against an unverified kit.
    """

    disable_bytecode()
    try:
        from scripts.ci import mod_base_kit
    except ImportError as exc:
        raise RuntimeError(f"the managed bootstrap scripts/ci/mod_base_kit.py is unavailable: {exc}") from exc
    try:
        root = Path(mod_base_kit.kit_path(REPO))
    except (mod_base_kit.KitError, OSError) as exc:
        raise RuntimeError(f"the pinned mod-base kit is unavailable: {exc}") from exc
    source = root / "src"
    sys.path[:] = [entry for entry in sys.path if entry != str(source)]
    sys.path.insert(0, str(source))
    loaded = sys.modules.get("mod_base")
    if loaded is not None:
        origin = getattr(loaded, "__file__", None)
        if origin is None or not Path(origin).resolve().is_relative_to(source.resolve()):
            raise RuntimeError(f"mod_base was already imported from {origin!r}, not from the pinned kit {root}")
    return root

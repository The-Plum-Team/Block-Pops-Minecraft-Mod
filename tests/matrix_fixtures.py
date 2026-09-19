"""Frozen schema1 inputs for legacy compatibility tests, never production authority.

The JSON freezes schema1 content from release/release-matrix.json Git blob
14ccfa09c5fd292a79926c3e30309eb55dbb3b6d before the preparing migration.
Its raw bytes follow checkout line endings; receipts hash the actual fixture file,
not the historical Git blob or the current live matrix.
Live checkout validation must continue to read the real release matrix explicitly.
"""

import atexit
import tempfile
from functools import lru_cache
from pathlib import Path

from scripts.ci.tests.matrix_fixtures import SCHEMA1_MATRIX_PATH, schema1_matrix


@lru_cache(maxsize=1)
def schema1_source_matrix() -> Path:
    """Materialize a shared legacy test matrix with its required source directories.

    Readers still validate the source layout; this empty fixture does not represent
    compiled or qualified production sources. Its temporary root lives until exit.
    """
    temporary = tempfile.TemporaryDirectory(prefix="blockpops-schema1-fixture-")
    atexit.register(temporary.cleanup)
    root = Path(temporary.name)
    for route in schema1_matrix()["source_routing"].values():
        for relative in (route["canonical"], *route["overlays"].values()):
            (root / relative).mkdir(parents=True, exist_ok=True)
    matrix_path = root / "release/release-matrix.json"
    matrix_path.parent.mkdir()
    matrix_path.write_bytes(SCHEMA1_MATRIX_PATH.read_bytes())
    return matrix_path

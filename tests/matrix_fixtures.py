"""Frozen schema1 inputs for legacy compatibility tests, never production authority.

The JSON freezes schema1 content from release/release-matrix.json Git blob
14ccfa09c5fd292a79926c3e30309eb55dbb3b6d before the preparing migration.
Its raw bytes follow checkout line endings; receipts hash the actual fixture file,
not the historical Git blob or the current live matrix.
Live checkout validation must continue to read the real release matrix explicitly.
"""

import json
from pathlib import Path

SCHEMA1_MATRIX_PATH = Path(__file__).parent / "fixtures/release-matrix-schema1.json"


def schema1_matrix() -> dict:
    """Return an independent legacy fixture for each mutation test."""
    return json.loads(SCHEMA1_MATRIX_PATH.read_bytes())

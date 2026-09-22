"""Foundation tests for schema-dispatched release-matrix inventory parsing."""

from __future__ import annotations

import copy
import json
import unittest
from pathlib import Path

from scripts.release.matrix import (
    EXPECTED_TARGETS,
    MatrixError,
    _numeric_version,
    normalize_matrix_inventory,
    validate_matrix,
)
from tests.matrix_fixtures import schema1_matrix


REPOSITORY = Path(__file__).resolve().parents[1]
BASE_MATRIX = schema1_matrix()
# The migration scope is declared once, by the validator. Deriving these targets
# from it keeps the controller tests honest as the scope grows.
TARGETS = tuple(
    (loader, minecraft)
    for minecraft, loader in sorted(
        ((minecraft, loader) for loader, minecraft in EXPECTED_TARGETS),
        key=lambda pair: (_numeric_version(pair[0]), pair[1]),
    )
)


def schema2_matrix() -> dict[str, object]:
    """Build an inventory-only schema-2 document without duplicating matrix JSON."""

    matrix = copy.deepcopy(BASE_MATRIX)
    matrix["schema_version"] = 2
    matrix["targets"] = [
        {
            "artifact_node": f"{loader}-{minecraft}",
            "minecraft": minecraft,
            "loader": loader,
        }
        for loader, minecraft in TARGETS
    ]
    matrix["migration"] = {
        "mode": "preparing",
        "legacy_nodes": ["fabric-1.20.1", "forge-1.20.1"],
    }
    for artifact in matrix["artifacts"]:
        artifact.update(
            mod_version=matrix["project"]["mod_version"],
            build_layout="legacy",
            gradle_java=21,
            repository_family=artifact["loader"],
            source_routes=["common", artifact["loader"]],
        )
    for module, route in matrix["source_routing"].items():
        route.update(e2e=f"{module}/src/e2e", overlays=[])
    return matrix


class ReleaseMatrixSchema2FoundationTests(unittest.TestCase):
    def test_schema1_normalizes_without_changing_legacy_execution_support(self) -> None:
        inventory = normalize_matrix_inventory(copy.deepcopy(BASE_MATRIX))

        self.assertEqual(1, inventory.schema_version)
        self.assertEqual(
            ("fabric-1.20.1", "forge-1.20.1"), inventory.target_nodes
        )
        self.assertIsNone(inventory.migration_mode)
        self.assertTrue(inventory.execution_supported)

    def test_schema2_preparing_inventory_normalizes_every_declared_target(self) -> None:
        inventory = normalize_matrix_inventory(schema2_matrix())

        self.assertEqual(2, inventory.schema_version)
        self.assertEqual(len(TARGETS), len(inventory.targets))
        self.assertEqual(
            {f"{loader}-{minecraft}" for loader, minecraft in TARGETS},
            set(inventory.target_nodes),
        )
        self.assertEqual("preparing", inventory.migration_mode)
        self.assertEqual(
            ("fabric-1.20.1", "forge-1.20.1"), inventory.legacy_nodes
        )
        self.assertFalse(inventory.execution_supported)

    def test_schema2_is_not_accepted_by_execution_consumers_yet(self) -> None:
        with self.assertRaisesRegex(MatrixError, "inventory only"):
            validate_matrix(schema2_matrix())

    def test_schema_dispatch_and_target_inventory_fail_closed(self) -> None:
        mutations = {
            "unknown schema": lambda matrix: matrix.__setitem__("schema_version", 3),
            "missing target": lambda matrix: matrix["targets"].pop(),
            "duplicate target": lambda matrix: matrix["targets"].__setitem__(
                -1, copy.deepcopy(matrix["targets"][0])
            ),
            "extra target": lambda matrix: matrix["targets"].append(
                {
                    "artifact_node": "forge-1.21.7",
                    "minecraft": "1.21.7",
                    "loader": "forge",
                }
            ),
            "mixed identity": lambda matrix: matrix["targets"][2].__setitem__(
                "minecraft", "1.20.1"
            ),
        }
        for label, mutate in mutations.items():
            matrix = schema2_matrix()
            mutate(matrix)
            with self.subTest(label=label), self.assertRaises(MatrixError):
                normalize_matrix_inventory(matrix)

    def test_migration_state_rejects_inconsistent_legacy_nodes(self) -> None:
        mutations = {
            "unknown mode": lambda migration: migration.__setitem__("mode", "ready"),
            "preparing node omitted": lambda migration: migration["legacy_nodes"].pop(),
            "shared keeps legacy nodes": lambda migration: migration.__setitem__(
                "mode", "shared"
            ),
        }
        for label, mutate in mutations.items():
            matrix = schema2_matrix()
            mutate(matrix["migration"])
            with self.subTest(label=label), self.assertRaises(MatrixError):
                normalize_matrix_inventory(matrix)

        shared = schema2_matrix()
        shared["migration"] = {"mode": "shared", "legacy_nodes": []}
        with self.assertRaises(MatrixError):
            normalize_matrix_inventory(shared)


if __name__ == "__main__":
    unittest.main()

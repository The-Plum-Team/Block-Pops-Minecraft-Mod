"""Client cache recipes bind to one exact, validated matrix runtime row."""

from __future__ import annotations

import copy
import unittest
from unittest.mock import patch

from e2e.packaged_runtime import RuntimeFailure, client_runtime_recipe
from scripts.ci.tests.matrix_fixtures import schema2_configuration
from tests.test_release_matrix_portability import arbitrary_named_1211_release_matrix
from tests.matrix_fixtures import schema1_matrix


class RuntimeRecipeTests(unittest.TestCase):
    def setUp(self):
        launcher = patch("e2e.packaged_runtime.launcher_library_version", return_value="8.0")
        self.launcher = launcher.start()
        self.addCleanup(launcher.stop)

    def test_schema_one_cache_identities_are_unchanged_for_frozen_and_historical_rows(self):
        # Captured from the previous consumer, before adding matrix validation.
        identities = (
            (schema1_matrix(), "Linux", "x86_64", (
                "b9ee9c5e22cef0902770049ac872a671321dc50965ef08418c076456ebb1c456",
                "cd616d6e986c788c189cfe1645e96b43aec7145e8c18e36da722d77b1707b792",
            )),
            (arbitrary_named_1211_release_matrix(), "Darwin", "arm64", (
                "e7e3e3ddfd39dbc5f9869bfa3cf8bc199e17809bdd45bb89742080d2f3e2929a",
                "f95a5ffa3d42fd9eff24da0c42581125008916c1c4e52772fc40a00253355cf7",
            )),
        )
        for matrix, system, architecture, digests in identities:
            with patch("e2e.runtime_store.platform.system", return_value=system), patch(
                "e2e.runtime_store.platform.machine", return_value=architecture
            ):
                for row, digest in zip(matrix["runtimes"], digests, strict=True):
                    with self.subTest(node=row["artifact_node"], system=system):
                        recipe = client_runtime_recipe(matrix, copy.deepcopy(row))
                        self.assertEqual(digest, recipe.digest())
                        self.assertEqual((1, system.lower(), architecture),
                                         (recipe.schema, recipe.os_name, recipe.architecture))

    def test_configured_schema_two_rows_derive_every_install_input_from_their_lane(self):
        matrix = schema2_configuration()
        before = copy.deepcopy(matrix)
        for row in matrix["runtimes"]:
            with self.subTest(node=row["artifact_node"]):
                recipe = client_runtime_recipe(matrix, copy.deepcopy(row))
                self.assertEqual((row["java"], row["minecraft"], row["loader"], row["loader_version"]),
                                 (recipe.java_major, recipe.minecraft_version,
                                  recipe.loader, recipe.loader_version))
                self.assertEqual(matrix["installers"][row["installer"]]["sha256"],
                                 recipe.installer_sha256)
        self.assertEqual(before, matrix)

    def test_runtime_projection_rejects_overrides_even_if_cache_fields_are_equal(self):
        for matrix in (schema1_matrix(), schema2_configuration()):
            original = matrix["runtimes"][0]
            mutations = (
                ("java", str(original["java"])), ("java", float(original["java"])),
                ("java", True), ("loader", "forge"), ("minecraft", "1.21.7"),
                ("artifact_node", matrix["runtimes"][1]["artifact_node"]),
                ("loader_version", "999"), ("installer", matrix["runtimes"][1]["installer"]),
                ("runtime_dependencies", []), ("pr_anchor", 1), ("scheduled_anchor", 1),
                ("installer_sha256", "a" * 64),
            )
            for key, value in mutations:
                row = copy.deepcopy(original)
                row[key] = value
                with self.subTest(schema=matrix["schema_version"], key=key, value=value):
                    with self.assertRaises(RuntimeFailure):
                        client_runtime_recipe(matrix, row)
            for row in (None, [], {}, {key: value for key, value in original.items() if key != "pr_anchor"}):
                with self.subTest(row=row), self.assertRaises(RuntimeFailure):
                    client_runtime_recipe(matrix, row)
        self.launcher.assert_not_called()

    def test_unknown_and_unresolved_lanes_cannot_borrow_a_configured_recipe(self):
        matrix = schema2_configuration()
        for node in ("fabric-1.21.7", "forge-1.21.7", "missing"):
            row = copy.deepcopy(matrix["runtimes"][0])
            row["artifact_node"] = node
            with self.subTest(node=node), self.assertRaises(RuntimeFailure):
                client_runtime_recipe(matrix, row)
        self.launcher.assert_not_called()

    def test_matrix_validation_rejects_orphans_cross_lane_and_ambiguous_installers(self):
        mutations = (
            lambda m: m["artifacts"].pop(),
            lambda m: m["runtimes"].append(copy.deepcopy(m["runtimes"][0])),
            lambda m: m["runtimes"][-1].update(installer=m["runtimes"][1]["installer"]),
            lambda m: m["runtimes"][0].update(java="17"),
            lambda m: m["installers"][m["runtimes"][0]["installer"]].update(sha256="invalid"),
            lambda m: m["installers"].update(unused=copy.deepcopy(next(iter(m["installers"].values())))),
            lambda m: m.update(schema_version=99),
        )
        for index, mutate in enumerate(mutations):
            matrix = schema2_configuration()
            mutate(matrix)
            with self.subTest(mutation=index), self.assertRaises(RuntimeFailure):
                client_runtime_recipe(matrix, matrix["runtimes"][0])
        self.launcher.assert_not_called()

    def test_installer_hash_changes_in_the_matrix_change_the_cache_identity(self):
        matrix = schema2_configuration()
        row = matrix["runtimes"][-1]
        previous = client_runtime_recipe(matrix, row)
        matrix["installers"][row["installer"]]["sha256"] = "b" * 64
        changed = client_runtime_recipe(matrix, row)
        self.assertEqual("b" * 64, changed.installer_sha256)
        self.assertNotEqual(previous.digest(), changed.digest())


if __name__ == "__main__":
    unittest.main()

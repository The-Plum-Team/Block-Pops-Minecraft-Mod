"""Schema-2 configured lanes remain distinct from the twelve migration targets."""

from __future__ import annotations

import copy
import unittest

from scripts.release.matrix import MatrixError, normalize_matrix_inventory
from tests.test_release_matrix_portability import arbitrary_named_1211_release_matrix
from tests.test_release_matrix_schema2 import schema2_matrix


def mixed_matrix() -> dict:
    matrix = schema2_matrix()
    modern = arbitrary_named_1211_release_matrix()
    matrix["source_routing"]["neoforge"] = modern["source_routing"]["neoforge"]
    matrix["source_routing"]["neoforge"].update(e2e="neoforge/src/e2e", overlays=[])
    matrix["installers"].update(modern["installers"])
    matrix["runtimes"].extend(modern["runtimes"])
    for row in modern["artifacts"]:
        loader, minecraft = row["loader"], row["minecraft"]
        row.update(mod_version="2.3.4", build_layout="stonecutter", gradle_java=21,
                   repository_family=loader, source_routes=["common", loader])
        for key in ("gradle_task", "harness_task"):
            row[key] = row[key].replace(f":{loader}:", f":{loader}:{minecraft}:")
        for key in ("jar", "harness_jar"):
            row[key] = row[key].replace(f"{loader}/build/", f"{loader}/versions/{minecraft}/build/")
        matrix["artifacts"].append(row)
    matrix["lane_count"] = len(matrix["artifacts"])
    return matrix


class Schema2ConfigurationTests(unittest.TestCase):
    def test_mixed_eras_and_isolated_fml_families_validate_without_mutation(self):
        matrix = mixed_matrix()
        before = copy.deepcopy(matrix)
        inventory = normalize_matrix_inventory(matrix)
        self.assertEqual(before, matrix)
        self.assertEqual(12, len(inventory.targets))
        self.assertFalse(inventory.execution_supported)

    def test_lane_inputs_are_explicit_and_consistent(self):
        mutations = [
            ("mod_version", "latest"), ("mod_version", "2.3.4/escape"),
            ("build_layout", "automatic"), ("build_layout", "legacy"),
            ("java", 17), ("java", True), ("gradle_java", 17), ("gradle_java", 25),
            ("repository_family", "forge"), ("repository_family", ["forge", "neoforge"]),
            ("source_routes", ["common", "forge"]),
            ("gradle_task", ":neoforge:1.21.4:remapJar"),
            ("harness_task", ":neoforge:1.21.1:remapJar"),
            ("jar", "neoforge/versions/1.21.4/build/libs/BlockPops - NeoForge - 1.21.1-{mod_version}.jar"),
            ("jar", "neoforge/versions/1.21.1/build/libs/BlockPops - NeoForge - 1.21.1-{other}.jar"),
            ("harness_jar", "neoforge/versions/1.21.1/build/libs/BlockPops - NeoForge - 1.21.1-0.0.0.jar"),
        ]
        for key, value in mutations:
            matrix = mixed_matrix()
            matrix["artifacts"][-1][key] = value
            with self.subTest(key=key, value=value), self.assertRaises(MatrixError):
                normalize_matrix_inventory(matrix)
        for key in ("mod_version", "build_layout", "gradle_java", "repository_family", "source_routes"):
            matrix = mixed_matrix()
            del matrix["artifacts"][-1][key]
            with self.subTest(missing=key), self.assertRaises(MatrixError):
                normalize_matrix_inventory(matrix)

    def test_pairing_and_preparation_cannot_silently_drop_or_borrow_lanes(self):
        mutations = {
            "wrong count": lambda m: m.update(lane_count=12),
            "missing runtime": lambda m: m["runtimes"].pop(),
            "duplicate runtime": lambda m: m["runtimes"].__setitem__(-1, m["runtimes"][0]),
            "wrong runtime era": lambda m: m["runtimes"][-1].update(java=17),
            "float runtime Java": lambda m: m["runtimes"][-1].update(java=21.0),
            "wrong runtime identity": lambda m: m["runtimes"][-1].update(minecraft="1.21.4"),
            "legacy version disagreement": lambda m: m["artifacts"][0].update(mod_version="2.0.0"),
            "legacy layout disagreement": lambda m: m["artifacts"][0].update(build_layout="stonecutter"),
            "unknown project key": lambda m: m["project"].update(qualified=True),
            "unlocked installer": lambda m: m["installers"].clear(),
            "missing legacy pair": lambda m: (m["artifacts"].pop(1), m["runtimes"].pop(1), m.update(lane_count=3)),
            "runtime dependency omitted": lambda m: m["runtimes"][-1]["runtime_dependencies"].pop(),
        }
        for label, mutate in mutations.items():
            matrix = mixed_matrix()
            mutate(matrix)
            with self.subTest(label=label), self.assertRaises(MatrixError):
                normalize_matrix_inventory(matrix)


if __name__ == "__main__":
    unittest.main()

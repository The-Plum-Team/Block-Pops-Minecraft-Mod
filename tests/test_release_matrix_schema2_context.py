"""Schema-2 executable inputs must agree with their declared lane context."""

from __future__ import annotations

import unittest

from scripts.release.matrix import MatrixError, normalize_matrix_inventory
from tests.test_release_matrix_schema2 import schema2_matrix
from tests.test_release_matrix_schema2_configuration import mixed_matrix


def rename_installer(matrix: dict, old: str, new: str) -> None:
    matrix["installers"][new] = matrix["installers"].pop(old)
    for runtime in matrix["runtimes"]:
        if runtime["installer"] == old:
            runtime["installer"] = new


class Schema2RuntimeContextTests(unittest.TestCase):
    def test_legacy_and_mixed_contexts_remain_valid(self):
        for factory in (schema2_matrix, mixed_matrix):
            with self.subTest(factory=factory.__name__):
                self.assertEqual(18, len(normalize_matrix_inventory(factory()).targets))
        for version_range in ("[1.21.1]", "[1.21.1,1.22)"):
            matrix = mixed_matrix()
            matrix["artifacts"][-1]["metadata"]["minecraft"] = version_range
            with self.subTest(version_range=version_range):
                normalize_matrix_inventory(matrix)

    def test_installer_identity_origin_and_loader_version_are_bound(self):
        for old, new in (
            ("fabric-1.1.0", "fabric-latest"),
            ("fabric-1.1.0", "forge-1.1.0"),
            ("forge-1.20.1-47.4.9", "forge-1.20.1-47.4.8"),
            ("neoforge-21.1.77", "neoforge-21.1.76"),
        ):
            matrix = mixed_matrix()
            rename_installer(matrix, old, new)
            with self.subTest(installer=new), self.assertRaises(MatrixError):
                normalize_matrix_inventory(matrix)
        for installer, url in (
            ("fabric-1.1.0", "https://maven.fabricmc.net/net/fabricmc/fabric-installer/1.0.0/fabric-installer-1.0.0.jar"),
            ("forge-1.20.1-47.4.9", "https://maven.neoforged.net/releases/net/minecraftforge/forge/1.20.1-47.4.9/forge-1.20.1-47.4.9-installer.jar"),
            ("neoforge-21.1.77", "https://maven.neoforged.net/releases/net/neoforged/neoforge/21.1.76/neoforge-21.1.76-installer.jar"),
            ("neoforge-21.1.77", "https://example.invalid/neoforge-installer.jar"),
        ):
            matrix = mixed_matrix()
            matrix["installers"][installer]["url"] = url
            with self.subTest(url=url), self.assertRaises(MatrixError):
                normalize_matrix_inventory(matrix)
        matrix = mixed_matrix()
        matrix["runtimes"][-1]["installer"] = "fabric-1.1.0"
        del matrix["installers"]["neoforge-21.1.77"]
        with self.assertRaises(MatrixError):
            normalize_matrix_inventory(matrix)
        matrix = mixed_matrix()
        matrix["runtimes"][0]["loader_version"] = "latest"
        with self.assertRaises(MatrixError):
            normalize_matrix_inventory(matrix)

    def test_consistent_installer_cannot_mask_a_wrong_loader_era(self):
        for index, version, origin, group in (
            (1, "1.21.1-47.4.9", "https://maven.minecraftforge.net", "net/minecraftforge/forge"),
            (3, "21.4.77", "https://maven.neoforged.net/releases", "net/neoforged/neoforge"),
        ):
            matrix = mixed_matrix()
            runtime = matrix["runtimes"][index]
            loader = runtime["loader"]
            installer = f"{loader}-{version}"
            rename_installer(matrix, runtime["installer"], installer)
            runtime["loader_version"] = version
            matrix["installers"][installer]["url"] = (
                f"{origin}/{group}/{version}/{loader}-{version}-installer.jar"
            )
            with self.subTest(loader=loader), self.assertRaises(MatrixError):
                normalize_matrix_inventory(matrix)

    def test_dependency_identity_cannot_borrow_another_loader_or_era(self):
        for index, dependency_id, coordinate in (
            (3, "architectury", "dev.architectury:architectury-forge:13.0.8"),
            (3, "geckolib", "software.bernie.geckolib:geckolib-forge-1.21.1:4.8"),
            (3, "geckolib", "software.bernie.geckolib:geckolib-neoforge-1.21.4:4.8"),
            (2, "fabric-api", "net.fabricmc.fabric-api:fabric-api:0.110.0+1.20.1"),
            (2, "fabric-api", "example.invalid:fabric-api:0.110.0+1.21.1"),
            (1, "mclib", "example.invalid:mclib:20"),
        ):
            matrix = mixed_matrix()
            dependency = next(row for row in matrix["runtimes"][index]["runtime_dependencies"]
                              if row["id"] == dependency_id)
            dependency["coordinate"] = coordinate
            with self.subTest(coordinate=coordinate), self.assertRaises(MatrixError):
                normalize_matrix_inventory(matrix)

    def test_dependency_repositories_are_exact_reviewed_origins(self):
        for index, dependency_id, repository in (
            (3, "architectury", "https://maven.minecraftforge.net/"),
            (3, "geckolib", "https://maven.neoforged.net/releases/"),
            (2, "fabric-api", "https://maven.fabricmc.net/unreviewed/"),
            (1, "mclib", "https://example.invalid/"),
        ):
            matrix = mixed_matrix()
            dependency = next(row for row in matrix["runtimes"][index]["runtime_dependencies"]
                              if row["id"] == dependency_id)
            dependency["repository"] = repository
            with self.subTest(repository=repository), self.assertRaises(MatrixError):
                normalize_matrix_inventory(matrix)

    def test_metadata_minecraft_constraints_match_the_artifact_era(self):
        for index, constraint in (
            (2, "~1.20.1"), (2, ">=1.21.1"),
            (3, "[1.20.1,1.20.2)"), (3, "[1.21.1,1.21.1)"),
            (3, "[1.21.1,1.20.9)"), (3, "[1.21.1,latest)"),
            (3, "[1.21.1,1.21.1.0)"),
            (3, "[1.21.4]"), (3, "[1.21.1,1.21.2]"),
        ):
            matrix = mixed_matrix()
            matrix["artifacts"][index]["metadata"]["minecraft"] = constraint
            with self.subTest(constraint=constraint), self.assertRaises(MatrixError):
                normalize_matrix_inventory(matrix)


if __name__ == "__main__":
    unittest.main()

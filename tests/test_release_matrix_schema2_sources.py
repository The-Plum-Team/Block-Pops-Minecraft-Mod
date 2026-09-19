"""Schema-2 source routes bind canonical inputs and explicit lane overlays."""

from __future__ import annotations

import os
import tempfile
import unittest
from pathlib import Path

from scripts.release.matrix import MatrixError, normalize_matrix_inventory
from tests.test_release_matrix_schema2 import REPOSITORY, schema2_matrix


def overlay(module="common") -> dict:
    return {
        "lanes": ["fabric-1.20.1"], "source_set": "main",
        "path": f"{module}/src/legacy20/main",
        "adds": ["java/demo/Added.java"], "replaces": ["java/demo/Shared.java"],
        "reason": "Legacy API adapter", "historical_source": "a" * 40 + ":common/src/main/java/demo/Shared.java",
        "acceptance": "Packaged lane interaction preserves behavior",
    }


def write(root: Path, relative: str) -> Path:
    path = root / relative
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text("fixture source\n")
    return path


def repository(root: Path, matrix: dict) -> None:
    for route in matrix["source_routing"].values():
        for key in ("canonical", "e2e"):
            (root / route[key]).mkdir(parents=True)
    write(root, "common/src/main/java/demo/Shared.java")


class Schema2SourceTests(unittest.TestCase):
    def test_current_canonical_main_and_harness_sources_exist(self):
        normalize_matrix_inventory(schema2_matrix(), repository=REPOSITORY)

    def test_scoped_addition_and_replacement_are_valid(self):
        matrix = schema2_matrix()
        route = overlay()
        matrix["source_routing"]["common"]["overlays"] = [route]
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            repository(root, matrix)
            for relative in route["adds"] + route["replaces"]:
                write(root, f"{route['path']}/{relative}")
            normalize_matrix_inventory(matrix, repository=root)

    def test_overlay_declarations_reject_unsafe_or_inconsistent_inputs(self):
        for key, value in (
            ("lanes", ["fabric-1.21.1"]), ("lanes", ["fabric-1.20.1"] * 2),
            ("source_set", "test"), ("path", "common/src/legacy20/e2e"),
            ("path", "common/src/legacy20/../main"), ("adds", ["../escape.java"]),
            ("adds", ["java/demo/Added.java"] * 2), ("adds", ["java/demo/Shared.java"]),
            ("adds", ["java\\demo\\Added.java"]), ("reason", ""),
            ("historical_source", "main:common/src/main/Shared.java"),
            ("historical_source", "a" * 40 + ":../escape"),
        ):
            matrix = schema2_matrix()
            route = overlay()
            route[key] = value
            matrix["source_routing"]["common"]["overlays"] = [route]
            with self.subTest(key=key, value=value), self.assertRaises(MatrixError):
                normalize_matrix_inventory(matrix)
        matrix = schema2_matrix()
        route = overlay("fabric")
        route["lanes"] = ["forge-1.20.1"]
        matrix["source_routing"]["fabric"]["overlays"] = [route]
        with self.assertRaises(MatrixError):
            normalize_matrix_inventory(matrix)

    def test_source_roots_are_exact_and_include_harness(self):
        for key, value in (("canonical", "../main"), ("e2e", "forge/src/e2e"), ("e2e", None)):
            matrix = schema2_matrix()
            if value is None:
                del matrix["source_routing"]["fabric"][key]
            else:
                matrix["source_routing"]["fabric"][key] = value
            with self.subTest(key=key, value=value), self.assertRaises(MatrixError):
                normalize_matrix_inventory(matrix)

    def test_routed_files_reject_missing_shadowing_and_unlisted_content(self):
        cases = ("missing e2e", "missing replacement", "undeclared file", "shadowing add",
                 "cross module collision", "unknown overlay", "hidden e2e", "snapshot", "file symlink", "root symlink")
        if hasattr(os, "mkfifo"):
            cases += ("special file",)
        for case in cases:
            with self.subTest(case=case), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                matrix = schema2_matrix()
                route = overlay()
                matrix["source_routing"]["common"]["overlays"] = [route]
                repository(root, matrix)
                for relative in route["adds"] + route["replaces"]:
                    write(root, f"{route['path']}/{relative}")
                if case == "missing e2e":
                    (root / "fabric/src/e2e").rmdir()
                elif case == "missing replacement":
                    (root / "common/src/main/java/demo/Shared.java").unlink()
                elif case == "undeclared file":
                    write(root, f"{route['path']}/resources/undeclared.txt")
                elif case == "shadowing add":
                    write(root, "common/src/main/java/demo/Added.java")
                elif case == "cross module collision":
                    write(root, "fabric/src/main/java/demo/Shared.java")
                elif case == "unknown overlay":
                    write(root, "common/src/legacyOther/main/java/Unknown.java")
                elif case == "hidden e2e":
                    write(root, "common/src/legacy20/e2e/java/Hidden.java")
                elif case == "snapshot":
                    write(root, "common/src/v1_20/main/java/Snapshot.java")
                elif case == "file symlink":
                    path = root / route["path"] / "java/demo/Added.java"
                    path.unlink()
                    path.symlink_to(root / "common/src/main/java/demo/Shared.java")
                elif case == "root symlink":
                    path = root / "fabric/src/e2e"
                    path.rmdir()
                    path.symlink_to(root / "common/src/e2e", target_is_directory=True)
                elif case == "special file":
                    os.mkfifo(root / "common/src/main/named-pipe")
                with self.assertRaises(MatrixError):
                    normalize_matrix_inventory(matrix, repository=root)


if __name__ == "__main__":
    unittest.main()

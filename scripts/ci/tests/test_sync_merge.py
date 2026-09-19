from __future__ import annotations

import ast
import copy
import json
import subprocess
import tempfile
import unittest
from pathlib import Path

from scripts.ci.gate_controller import PROTECTED_PATHS, validate_topology
from scripts.ci.tests import matrix_fixtures
from scripts.ci.sync_merge import (
    MATRIX_PATH,
    SyncMergeError,
    branch_specific_loader_roots,
    create_sync_merge,
)
from scripts.ci.tests.matrix_fixtures import canonical_integration_matrix, schema1_matrix


REPO = Path(__file__).resolve().parents[3]


def git(repository: Path, *args: str) -> str:
    return subprocess.check_output(["git", "-C", str(repository), *args], text=True).strip()


class SyncRepository:
    def __init__(self, root: Path) -> None:
        self.root = root
        subprocess.run(["git", "init", "-q", "-b", "master", root], check=True)
        subprocess.run(["git", "-C", root, "config", "user.name", "Test"], check=True)
        subprocess.run(["git", "-C", root, "config", "user.email", "t@example.test"], check=True)
        (root / "release").mkdir()
        (root / "gradle").mkdir()
        (root / "common/src/e2e/java/com/theplumteam/e2e").mkdir(parents=True)
        (root / "e2e/server-template/datapack").mkdir(parents=True)
        self.release = schema1_matrix()
        self.integration = canonical_integration_matrix(self.release)
        (root / MATRIX_PATH).write_text(json.dumps(self.integration, indent=2) + "\n", encoding="utf-8")
        (root / "gradle/verification-metadata.xml").write_text(
            "<verification-metadata>base</verification-metadata>\n", encoding="utf-8"
        )
        (root / "common/src/e2e/java/com/theplumteam/e2e/VanillaShim.java").write_text(
            "// base shim\n", encoding="utf-8"
        )
        (root / "e2e/server-template/datapack/pack.mcmeta").write_text(
            '{"pack":{"pack_format":15,"description":"base"}}\n', encoding="utf-8"
        )
        (root / "build.gradle").write_text("// base build profile\n", encoding="utf-8")
        subprocess.run(["git", "-C", root, "add", "."], check=True)
        subprocess.run(["git", "-C", root, "commit", "-qm", "base"], check=True)
        subprocess.run(["git", "-C", root, "branch", "release/one"], check=True)

    def release_commit(self, *, conflict: bool = False) -> tuple[str, bytes]:
        subprocess.run(["git", "-C", self.root, "switch", "-q", "release/one"], check=True)
        release = copy.deepcopy(self.release)
        release["branch"] = {
            "role": "release",
            "name": "release/one",
            "canonical": "master",
            "sync": {"enabled": True, "source": "master"},
        }
        matrix = (json.dumps(release, indent=2) + "\n").encode()
        (self.root / MATRIX_PATH).write_bytes(matrix)
        (self.root / "gradle/verification-metadata.xml").write_text(
            "<verification-metadata>release-dependencies</verification-metadata>\n",
            encoding="utf-8",
        )
        (self.root / "common/src/e2e/java/com/theplumteam/e2e/VanillaShim.java").write_text(
            "// release shim\n", encoding="utf-8"
        )
        (self.root / "e2e/server-template/datapack/pack.mcmeta").write_text(
            '{"pack":{"pack_format":48,"description":"release"}}\n', encoding="utf-8"
        )
        (self.root / "target.txt").write_text("target only\n", encoding="utf-8")
        if conflict:
            (self.root / "build.gradle").write_text("// target build profile\n", encoding="utf-8")
        subprocess.run(["git", "-C", self.root, "add", "."], check=True)
        subprocess.run(["git", "-C", self.root, "commit", "-qm", "release identity"], check=True)
        return git(self.root, "rev-parse", "HEAD"), matrix

    def source_commit(self, *, conflict: bool = False) -> str:
        subprocess.run(["git", "-C", self.root, "switch", "-q", "master"], check=True)
        (self.root / "new-controller.txt").write_text("protected\n", encoding="utf-8")
        (self.root / "gradle/verification-metadata.xml").write_text(
            "<verification-metadata>canonical-dependencies</verification-metadata>\n",
            encoding="utf-8",
        )
        (self.root / "common/src/e2e/java/com/theplumteam/e2e/VanillaShim.java").write_text(
            "// canonical shim\n", encoding="utf-8"
        )
        (self.root / "e2e/server-template/datapack/pack.mcmeta").write_text(
            '{"pack":{"pack_format":15,"description":"canonical"}}\n', encoding="utf-8"
        )
        (self.root / "neoforge").mkdir(exist_ok=True)
        (self.root / "neoforge/source-only.txt").write_text(
            "must not enter a branch where this loader is not shared\n", encoding="utf-8"
        )
        if conflict:
            (self.root / "build.gradle").write_text("// source build profile\n", encoding="utf-8")
        subprocess.run(["git", "-C", self.root, "add", "."], check=True)
        subprocess.run(["git", "-C", self.root, "commit", "-qm", "shared architecture"], check=True)
        return git(self.root, "rev-parse", "HEAD")


class SyncMergeTests(unittest.TestCase):
    def test_protected_controller_python_never_imports_candidate_owned_tests(self) -> None:
        for path in sorted((REPO / "scripts/ci").rglob("*.py")):
            tree = ast.parse(path.read_text("utf-8"), filename=str(path))
            for node in ast.walk(tree):
                imported = ()
                if isinstance(node, ast.ImportFrom) and node.module:
                    imported = (node.module,)
                elif isinstance(node, ast.Import):
                    imported = tuple(alias.name for alias in node.names)
                for module in imported:
                    with self.subTest(path=path, module=module):
                        self.assertFalse(
                            module == "tests" or module.startswith("tests."),
                            f"protected {path} imports candidate-owned {module}",
                        )

    def test_branch_portability_fixture_is_inside_the_protected_controller_tree(self) -> None:
        relative = (
            Path(matrix_fixtures.__file__).resolve().relative_to(REPO).as_posix()
        )
        self.assertTrue(
            any(
                relative == protected or relative.startswith(protected + "/")
                for protected in PROTECTED_PATHS
            ),
            relative,
        )
        codeowners = (REPO / ".github/CODEOWNERS").read_text("utf-8")
        self.assertIn("/scripts/ci/ @AkaNebur", codeowners)

    def test_fabric_neoforge_target_retains_both_nonshared_loader_roots(self) -> None:
        self.assertEqual(
            branch_specific_loader_roots(
                {"fabric", "neoforge"}, {"fabric", "forge"}
            ),
            ("forge", "neoforge"),
        )

    def test_merge_retains_exact_target_matrix_and_ordered_parents(self) -> None:
        with tempfile.TemporaryDirectory() as raw:
            fixture = SyncRepository(Path(raw))
            target, matrix = fixture.release_commit()
            source = fixture.source_commit()
            subprocess.run(["git", "-C", raw, "switch", "-q", "--detach", target], check=True)
            evidence = create_sync_merge(
                Path(raw),
                target_sha=target,
                source_sha=source,
                target_branch="release/one",
                source_branch="master",
            )
            self.assertEqual(evidence["status"], "created")
            self.assertEqual((Path(raw) / MATRIX_PATH).read_bytes(), matrix)
            self.assertEqual(
                (Path(raw) / "gradle/verification-metadata.xml").read_text("utf-8"),
                "<verification-metadata>release-dependencies</verification-metadata>\n",
            )
            self.assertFalse((Path(raw) / "neoforge").exists())
            self.assertEqual(
                (Path(raw) / "common/src/e2e/java/com/theplumteam/e2e/VanillaShim.java").read_text("utf-8"),
                "// release shim\n",
            )
            self.assertIn(
                '"pack_format":48',
                (Path(raw) / "e2e/server-template/datapack/pack.mcmeta").read_text("utf-8"),
            )
            authenticated = validate_topology(
                Path(raw),
                protected_sha=source,
                target_sha=target,
                head_sha=str(evidence["merge_sha"]),
                source_branch="master",
                target_branch="release/one",
            )
            self.assertEqual(authenticated["matrix_sha256"], evidence["matrix_sha256"])
            self.assertEqual(git(Path(raw), "show", "-s", "--format=%P"), f"{target} {source}")
            self.assertEqual(git(Path(raw), "status", "--porcelain"), "")

    def test_unknown_conflict_fails_closed_and_aborts_cleanly(self) -> None:
        with tempfile.TemporaryDirectory() as raw:
            fixture = SyncRepository(Path(raw))
            target, _ = fixture.release_commit(conflict=True)
            source = fixture.source_commit(conflict=True)
            subprocess.run(["git", "-C", raw, "switch", "-q", "--detach", target], check=True)
            with self.assertRaisesRegex(SyncMergeError, "build.gradle"):
                create_sync_merge(
                    Path(raw),
                    target_sha=target,
                    source_sha=source,
                    target_branch="release/one",
                    source_branch="master",
                )
            self.assertEqual(git(Path(raw), "rev-parse", "HEAD"), target)
            self.assertEqual(git(Path(raw), "status", "--porcelain"), "")


if __name__ == "__main__":
    unittest.main()

from __future__ import annotations

import copy
import json
import subprocess
import tempfile
import unittest
from pathlib import Path

from scripts.release.matrix import MatrixError, gha_matrix, load_matrix_bytes, validate_matrix
from scripts.release.version_branches import (
    BranchDiscoveryError,
    discover_from_snapshots,
    discover_repository,
    inspect_branch,
)


REPOSITORY = Path(__file__).resolve().parents[1]
BASE_MATRIX_BYTES = (REPOSITORY / "release" / "release-matrix.json").read_bytes()


def _base_matrix() -> dict[str, object]:
    return json.loads(BASE_MATRIX_BYTES)


def _encoded(value: object) -> bytes:
    return (json.dumps(value, sort_keys=True, separators=(",", ":")) + "\n").encode()


def arbitrary_named_1211_release_matrix() -> dict[str, object]:
    """Return a complete release matrix whose branch name encodes no version."""

    matrix = _base_matrix()
    matrix["branch"] = {
        "role": "release",
        "name": "ship/aurora-ui",
        "canonical": "master",
        "sync": {"enabled": True, "source": "master"},
    }
    matrix["unit_test_lane"] = "fabric-1.21.1"
    matrix["source_routing"] = {
        "common": {"canonical": "common/src/main", "overlays": {}},
        "fabric": {"canonical": "fabric/src/main", "overlays": {}},
        "neoforge": {"canonical": "neoforge/src/main", "overlays": {}},
    }
    matrix["installers"] = {
        "fabric-1.1.0": {
            "url": "https://maven.fabricmc.net/net/fabricmc/fabric-installer/1.1.0/fabric-installer-1.1.0.jar",
            "sha256": "c20c407d5ca4b119aae97847eb2fbb1d34c92969b647fbbb1de2fd36ca00738e",
        },
        "neoforge-21.1.77": {
            "url": "https://maven.neoforged.net/releases/net/neoforged/neoforge/21.1.77/neoforge-21.1.77-installer.jar",
            "sha256": "359b4d488e68cf86569c20a4a422511b4299aa2af8accf38f36f436f35e49562",
        },
    }
    matrix["artifacts"] = [
        {
            "artifact_node": "fabric-1.21.1",
            "minecraft": "1.21.1",
            "loader": "fabric",
            "java": 21,
            "no_remap": False,
            "gradle_task": ":fabric:remapJar",
            "harness_task": ":fabric:remapE2EHarnessJar",
            "jar": "fabric/build/libs/BlockPops - Fabric - 1.21.1-{mod_version}.jar",
            "harness_jar": "fabric/build/libs/BlockPops E2E - Fabric - 1.21.1-0.0.0.jar",
            "metadata": {
                "file": "fabric.mod.json",
                "minecraft": "~1.21.1",
                "loader": ">=0.17.0",
                "architectury": ">=13.0.8",
                "geckolib": ">=4.8",
            },
        },
        {
            "artifact_node": "neoforge-1.21.1",
            "minecraft": "1.21.1",
            "loader": "neoforge",
            "java": 21,
            "no_remap": False,
            "gradle_task": ":neoforge:remapJar",
            "harness_task": ":neoforge:remapE2EHarnessJar",
            "jar": "neoforge/build/libs/BlockPops - NeoForge - 1.21.1-{mod_version}.jar",
            "harness_jar": "neoforge/build/libs/BlockPops E2E - NeoForge - 1.21.1-0.0.0.jar",
            "metadata": {
                "file": "META-INF/neoforge.mods.toml",
                "minecraft": "[1.21.1,1.21.2)",
                "loader": "[4,)",
                "architectury": "[13.0.8,)",
                "geckolib": "[4.8,)",
            },
        },
    ]
    matrix["runtimes"] = [
        {
            "artifact_node": "fabric-1.21.1",
            "minecraft": "1.21.1",
            "loader": "fabric",
            "java": 21,
            "loader_version": "0.17.0",
            "installer": "fabric-1.1.0",
            "pr_anchor": True,
            "scheduled_anchor": True,
            "runtime_dependencies": [
                {
                    "id": "fabric-api",
                    "coordinate": "net.fabricmc.fabric-api:fabric-api:0.110.0+1.21.1",
                    "repository": "https://maven.fabricmc.net/",
                    "side": "both",
                },
                {
                    "id": "architectury",
                    "coordinate": "dev.architectury:architectury-fabric:13.0.8",
                    "repository": "https://maven.architectury.dev/",
                    "side": "both",
                },
                {
                    "id": "geckolib",
                    "coordinate": "software.bernie.geckolib:geckolib-fabric-1.21.1:4.8",
                    "repository": "https://dl.cloudsmith.io/public/geckolib3/geckolib/maven/",
                    "side": "both",
                },
            ],
        },
        {
            "artifact_node": "neoforge-1.21.1",
            "minecraft": "1.21.1",
            "loader": "neoforge",
            "java": 21,
            "loader_version": "21.1.77",
            "installer": "neoforge-21.1.77",
            "pr_anchor": True,
            "scheduled_anchor": True,
            "runtime_dependencies": [
                {
                    "id": "architectury",
                    "coordinate": "dev.architectury:architectury-neoforge:13.0.8",
                    "repository": "https://maven.architectury.dev/",
                    "side": "both",
                },
                {
                    "id": "geckolib",
                    "coordinate": "software.bernie.geckolib:geckolib-neoforge-1.21.1:4.8",
                    "repository": "https://dl.cloudsmith.io/public/geckolib3/geckolib/maven/",
                    "side": "both",
                },
            ],
        },
    ]
    return matrix


class ReleaseMatrixPortabilityTests(unittest.TestCase):
    def test_current_branch_matrix_is_valid_and_all_projections_are_derived(self) -> None:
        matrix = load_matrix_bytes(BASE_MATRIX_BYTES)

        self.assertEqual(matrix["lane_count"], len(matrix["artifacts"]))
        self.assertEqual(
            {row["artifact_node"] for row in matrix["artifacts"]},
            {row["artifact_node"] for row in gha_matrix(matrix, "artifacts")["include"]},
        )
        self.assertEqual(
            {row["java"] for row in matrix["artifacts"]},
            {row["java"] for row in gha_matrix(matrix, "java")["include"]},
        )
        self.assertEqual(
            {row["artifact_node"] for row in matrix["runtimes"]},
            {row["artifact_node"] for row in gha_matrix(matrix, "pr-anchors")["include"]},
        )

    def test_arbitrary_named_1211_fabric_neoforge_branch_drives_shared_expectations(self) -> None:
        matrix = load_matrix_bytes(_encoded(arbitrary_named_1211_release_matrix()))

        self.assertEqual(matrix["branch"]["name"], "ship/aurora-ui")
        self.assertEqual(
            ["fabric-1.21.1", "neoforge-1.21.1"],
            [row["artifact_node"] for row in gha_matrix(matrix, "artifacts")["include"]],
        )
        self.assertEqual([{"java": 21}], gha_matrix(matrix, "java")["include"])
        runtime = gha_matrix(matrix, "pr-anchors")["include"]
        self.assertEqual({"fabric", "neoforge"}, {row["loader"] for row in runtime})
        self.assertTrue(all(row["minecraft"] == "1.21.1" for row in runtime))
        self.assertTrue(all(row["scenarios"] == "ui-regression" for row in runtime))

    def test_discovery_enrolls_self_identified_arbitrary_release_and_excludes_copied_feature(self) -> None:
        release = arbitrary_named_1211_release_matrix()
        snapshots = {
            "ship/aurora-ui": _encoded(release),
            # A normal feature branch carries an unchanged branch-local matrix.
            "feature/copied-release-matrix": _encoded(release),
        }

        discovered = discover_from_snapshots(
            snapshots,
            integration_branch="master",
            commits={"ship/aurora-ui": "a" * 40},
        )

        self.assertEqual(["ship/aurora-ui"], [branch.name for branch in discovered])
        self.assertEqual("a" * 40, discovered[0].commit)
        self.assertEqual("1.21.1", discovered[0].minecraft)
        self.assertEqual(("fabric", "neoforge"), discovered[0].loaders)
        self.assertEqual((21,), discovered[0].java)

    def test_exact_release_self_claim_is_fail_closed_when_schema_is_malformed(self) -> None:
        claimed = arbitrary_named_1211_release_matrix()
        claimed["unexpected_security_override"] = True

        with self.assertRaisesRegex(BranchDiscoveryError, "invalid matrix"):
            discover_from_snapshots(
                {"ship/aurora-ui": _encoded(claimed)},
                integration_branch="master",
            )

    def test_canonical_claim_is_fail_closed_but_nonclaim_malformed_json_is_inert(self) -> None:
        with self.assertRaisesRegex(BranchDiscoveryError, "canonical branch"):
            discover_from_snapshots(
                {"master": b'{"branch":'}, integration_branch="master"
            )
        self.assertEqual(
            [],
            discover_from_snapshots(
                {"feature/not-enrolled": b'{"branch":'}, integration_branch="master"
            ),
        )

    def test_branch_identity_policy_mutations_are_rejected(self) -> None:
        mutations = {
            "release sync disabled": lambda matrix: matrix["branch"]["sync"].__setitem__("enabled", False),
            "release source changed": lambda matrix: matrix["branch"]["sync"].__setitem__("source", "develop"),
            "release equals canonical": lambda matrix: matrix["branch"].__setitem__("name", "master"),
            "unsafe branch ref": lambda matrix: matrix["branch"].__setitem__("name", "release/../escape"),
        }
        for label, mutate in mutations.items():
            matrix = arbitrary_named_1211_release_matrix()
            mutate(matrix)
            with self.subTest(label=label), self.assertRaises(MatrixError):
                validate_matrix(matrix)

    def test_lane_and_runtime_mutations_cannot_drift_from_artifact_inventory(self) -> None:
        mutations = {
            "lane count": lambda matrix: matrix.__setitem__("lane_count", 3),
            "unit lane": lambda matrix: matrix.__setitem__("unit_test_lane", "absent-0.0.0"),
            "runtime version": lambda matrix: matrix["runtimes"][0].__setitem__("minecraft", "0.0.0"),
            "task": lambda matrix: matrix["artifacts"][0].__setitem__("gradle_task", ":fabric:jar"),
            "extra dependency": lambda matrix: matrix["runtimes"][1]["runtime_dependencies"].append(
                {
                    "id": "mclib",
                    "coordinate": "com.example:mclib:1",
                    "repository": "https://example.invalid/",
                    "side": "both",
                }
            ),
        }
        for label, mutate in mutations.items():
            matrix = arbitrary_named_1211_release_matrix()
            mutate(matrix)
            with self.subTest(label=label), self.assertRaises(MatrixError):
                validate_matrix(matrix)

    def test_matrix_json_duplicate_and_nonfinite_values_are_rejected(self) -> None:
        for raw in (
            b'{"schema_version":1,"schema_version":1}',
            b'{"schema_version":NaN}',
        ):
            with self.subTest(raw=raw), self.assertRaises(MatrixError):
                load_matrix_bytes(raw)


class RepositoryBranchDiscoveryTests(unittest.TestCase):
    def git(self, repository: Path, *arguments: str) -> str:
        result = subprocess.run(
            ["git", "-C", str(repository), *arguments],
            check=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
        )
        return result.stdout.strip()

    def initialize_repository(self, repository: Path) -> tuple[str, str]:
        self.git(repository, "init", "-q", "-b", "master")
        self.git(repository, "config", "user.name", "BlockPops Tests")
        self.git(repository, "config", "user.email", "tests@blockpops.invalid")
        self.git(repository, "config", "core.filemode", "true")
        marker = repository / "README.md"
        marker.write_text("synthetic canonical branch\n", encoding="utf-8")
        self.git(repository, "add", "README.md")
        self.git(repository, "commit", "-q", "-m", "canonical branch")
        integration_commit = self.git(repository, "rev-parse", "HEAD")

        self.git(repository, "switch", "-q", "-c", "ship/aurora-ui")
        matrix_path = repository / "release" / "release-matrix.json"
        matrix_path.parent.mkdir()
        matrix_path.write_bytes(_encoded(arbitrary_named_1211_release_matrix()))
        self.git(repository, "add", "release/release-matrix.json")
        self.git(repository, "commit", "-q", "-m", "release matrix")
        release_commit = self.git(repository, "rev-parse", "HEAD")
        self.git(
            repository,
            "update-ref",
            "refs/remotes/origin/ship/aurora-ui",
            release_commit,
        )
        # A copied matrix on a feature ref is inert because it identifies the release.
        self.git(
            repository,
            "update-ref",
            "refs/remotes/origin/feature/copied-matrix",
            release_commit,
        )
        return integration_commit, release_commit

    def test_repository_discovery_authenticates_exact_commit_tree_and_matrix_blob(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            repository = Path(temporary)
            _, release_commit = self.initialize_repository(repository)

            discovered = discover_repository(
                repository,
                remote="origin",
                integration_branch="master",
            )

            self.assertEqual(["ship/aurora-ui"], [branch.name for branch in discovered])
            release = discovered[0]
            self.assertEqual(release_commit, release.commit)
            self.assertEqual(
                self.git(repository, "rev-parse", f"{release_commit}^{{tree}}"),
                release.tree,
            )
            self.assertEqual(
                self.git(
                    repository,
                    "rev-parse",
                    f"{release_commit}:release/release-matrix.json",
                ),
                release.matrix_blob,
            )
            self.assertEqual(40, len(release.matrix_blob))
            self.assertEqual(64, len(release.matrix_sha256))

    def test_executable_matrix_blob_is_rejected_even_when_contents_are_valid(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            repository = Path(temporary)
            self.initialize_repository(repository)
            self.git(
                repository,
                "update-index",
                "--chmod=+x",
                "release/release-matrix.json",
            )
            self.git(repository, "commit", "-q", "-m", "unsafe matrix mode")
            commit = self.git(repository, "rev-parse", "HEAD")
            self.git(
                repository,
                "update-ref",
                "refs/remotes/origin/ship/aurora-ui",
                commit,
            )

            with self.assertRaisesRegex(
                BranchDiscoveryError, "no regular release matrix blob"
            ):
                inspect_branch(
                    repository,
                    branch="ship/aurora-ui",
                    ref="refs/remotes/origin/ship/aurora-ui",
                    canonical_branch="master",
                )


if __name__ == "__main__":
    unittest.main()

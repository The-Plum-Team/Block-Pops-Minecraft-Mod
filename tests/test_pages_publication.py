from __future__ import annotations

import copy
import hashlib
import json
import stat
import tempfile
import unittest
import warnings
import zipfile
from datetime import datetime, timedelta, timezone
from pathlib import Path
from unittest import mock

from PIL import Image

from e2e.scenario_contract import default_contract
from scripts.pages import evidence
from scripts.pages.build_site import build
from scripts.pages.authenticate_source import (
    SourceAuthenticationError,
    _expected_jobs,
    authenticate,
)
from scripts.pages.download_artifact import ArtifactDownloadError, extract_archive
from scripts.pages.rotate_artifacts import RotationError, plan_rotation
from scripts.pages.select_artifact import Artifact, SelectionError, select
from scripts.release.matrix import load_matrix


REPOSITORY = Path(__file__).resolve().parents[1]
MATRIX_PATH = REPOSITORY / "release" / "release-matrix.json"
MATRIX = load_matrix(MATRIX_PATH)
ACTIVE_BRANCH = MATRIX["branch"]["name"]
CANONICAL_BRANCH = MATRIX["branch"]["canonical"]
COMMIT = "1" * 40
TREE = "2" * 40
REPO_NAME = "AkaNebur/BlockPops"


def _metrics() -> dict[str, int]:
    return {"width": 1600, "height": 900}


def _comparison() -> dict[str, float]:
    return {"changed_fraction": 0.5}


def _fixture_profiles(root: Path) -> None:
    matrix = load_matrix(MATRIX_PATH)
    contract = default_contract()
    for runtime in matrix["runtimes"]:
        for scenario in contract.scenarios_for_profile("release"):
            profile_name = evidence._profile_name(runtime["artifact_node"], runtime["minecraft"], scenario)
            profile = root / "profiles" / profile_name
            reports = {}
            for role in contract.expected_roles(scenario):
                role_contract = contract.role(scenario, role)
                steps = []
                screenshots = {}
                for index, step in enumerate(role_contract.steps):
                    capture_id = step.capture.capture_id if step.capture else None
                    screenshot = f"{capture_id}.png" if capture_id else None
                    steps.append(
                        {
                            "id": step.id,
                            "status": "pass",
                            "message": "real packaged assertion passed",
                            "capture_id": capture_id,
                            "screenshot": screenshot,
                        }
                    )
                    if screenshot:
                        destination = profile / role / "screenshots" / screenshot
                        destination.parent.mkdir(parents=True, exist_ok=True)
                        Image.new("RGB", (1600, 900), (20 + index * 20, 30, 50)).save(destination, "PNG")
                        screenshots[step.id] = _metrics()
                comparisons = {
                    f"{item.first_step}->{item.second_step}": _comparison()
                    for item in role_contract.comparisons
                }
                reports[role] = {
                    "schema_version": 1,
                    "minecraft": runtime["minecraft"],
                    "role": role,
                    "scenario": scenario,
                    "contract_sha256": contract.sha256,
                    "status": "pass",
                    "steps": steps,
                    "pixel_validation": {"screenshots": screenshots, "comparisons": comparisons},
                }
            result = {
                "schema_version": 1,
                "artifact_node": runtime["artifact_node"],
                "minecraft": runtime["minecraft"],
                "loader": runtime["loader"],
                "scenario": scenario,
                "contract_sha256": contract.sha256,
                "production_jar_sha256": "3" * 64,
                "harness_jar_sha256": "4" * 64,
                "port": 25565,
                "status": "pass",
                "profile": f"profiles/{profile_name}",
                "installed_blockpops": [{"path": "client/mods/blockpops.jar", "sha256": "3" * 64}],
                "reports": reports,
                "elapsed_s": 10.0,
                "error": None,
            }
            profile.mkdir(parents=True, exist_ok=True)
            (profile / "result.json").write_text(json.dumps(result), encoding="utf-8")


def _inventory(branch: str = ACTIVE_BRANCH) -> list[dict[str, object]]:
    matrix = MATRIX
    raw = MATRIX_PATH.read_bytes()
    return [
        {
            "name": branch,
            "commit": COMMIT,
            "tree": TREE,
            "matrix_blob": hashlib.sha1(b"blob " + str(len(raw)).encode() + b"\0" + raw).hexdigest(),
            "matrix_sha256": hashlib.sha256(raw).hexdigest(),
            "minecraft": matrix["artifacts"][0]["minecraft"],
            "loaders": sorted(row["loader"] for row in matrix["artifacts"]),
            "java": sorted({row["java"] for row in matrix["artifacts"]}),
        }
    ]


class EvidenceRoundTripTests(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        self.packaged = self.root / "packaged"
        _fixture_profiles(self.packaged)
        self.raw = self.root / "raw"
        self.compact = self.root / "compact"
        self.expected = {
            "repository": REPO_NAME,
            "branch": ACTIVE_BRANCH,
            "commit": COMMIT,
            "tree": TREE,
            "matrix_sha256": hashlib.sha256(MATRIX_PATH.read_bytes()).hexdigest(),
            "contract_sha256": default_contract().sha256,
            "handoff": {
                "path": evidence.E2E_WORKFLOW,
                "run_id": 101,
                "run_attempt": 2,
            },
        }

    def tearDown(self) -> None:
        self.temporary.cleanup()

    def curate(self) -> dict:
        with mock.patch.object(evidence, "inspect_screenshot_for_step", return_value=_metrics()), mock.patch.object(
            evidence, "compare_screenshots", return_value=_comparison()
        ):
            return evidence.curate(
                input_root=self.packaged,
                output=self.raw,
                matrix_path=MATRIX_PATH,
                repository=REPO_NAME,
                branch=ACTIVE_BRANCH,
                commit=COMMIT,
                tree=TREE,
                run_id=101,
                run_attempt=2,
            )

    def make_compact(self) -> dict:
        self.curate()
        with mock.patch.object(evidence, "inspect_screenshot_for_step", return_value=_metrics()), mock.patch.object(
            evidence, "compare_screenshots", return_value=_comparison()
        ):
            return evidence.compact(
                input_root=self.raw,
                output=self.compact,
                matrix_path=MATRIX_PATH,
                expected=self.expected,
                source_artifact_id=501,
                source_artifact_name=evidence.raw_artifact_name(ACTIVE_BRANCH, 2),
                source_artifact_digest="sha256:" + "5" * 64,
            )

    def test_raw_to_compact_to_atomic_site_preserves_exact_head_provenance(self) -> None:
        compact_manifest = self.make_compact()
        self.assertTrue(compact_manifest["frames"])
        self.assertFalse(list(self.compact.rglob("*.png")))
        self.assertTrue(list(self.compact.rglob("*.webp")))

        collected = self.root / "collected"
        cache = collected / evidence.cache_artifact_name(ACTIVE_BRANCH, COMMIT)
        cache.parent.mkdir()
        self.compact.rename(cache)
        inventory = self.root / "inventory.json"
        inventory.write_text(json.dumps(_inventory()), encoding="utf-8")
        output = self.root / "site"
        with mock.patch.object(evidence, "inspect_screenshot_for_step", return_value=_metrics()):
            summary = build(
                evidence_root=collected,
                inventory_path=inventory,
                output=output,
                repository=REPO_NAME,
                canonical_matrix=MATRIX_PATH,
            )
        self.assertEqual(summary["branches"], 1)
        gallery = json.loads((output / "gallery-data.json").read_text())
        self.assertTrue(gallery["frames"])
        self.assertEqual(gallery["releases"][0]["tree"], TREE)
        self.assertFalse(list(output.rglob("*.png")))
        self.assertTrue(list(output.rglob("*.webp")))

    def test_raw_schema_hash_inventory_and_symlink_mutations_fail_closed(self) -> None:
        self.curate()
        manifest_path = self.raw / "pages-evidence.json"
        original = json.loads(manifest_path.read_text())
        mutations = {
            "unknown key": lambda value: value.__setitem__("trusted", True),
            "stale tree": lambda value: value["provenance"].__setitem__("tree", "9" * 40),
            "missing lane": lambda value: value["lanes"].pop(),
            "wrong capture": lambda value: value["frames"][0].__setitem__("capture_id", "forged.capture.id"),
            "hash mismatch": lambda value: value["files"][0].__setitem__("sha256", "9" * 64),
        }
        for label, mutation in mutations.items():
            value = copy.deepcopy(original)
            mutation(value)
            manifest_path.write_text(json.dumps(value), encoding="utf-8")
            with self.subTest(label=label), mock.patch.object(
                evidence, "inspect_screenshot_for_step", return_value=_metrics()
            ), mock.patch.object(evidence, "compare_screenshots", return_value=_comparison()), self.assertRaises(
                evidence.EvidenceError
            ):
                evidence.validate_raw(self.raw, matrix_path=MATRIX_PATH, expected=self.expected)
        manifest_path.write_text(json.dumps(original), encoding="utf-8")
        first_png = next(self.raw.rglob("*.png"))
        first_png.unlink()
        first_png.symlink_to("/etc/passwd")
        with mock.patch.object(evidence, "inspect_screenshot_for_step", return_value=_metrics()), mock.patch.object(
            evidence, "compare_screenshots", return_value=_comparison()
        ), self.assertRaises(evidence.EvidenceError):
            evidence.validate_raw(self.raw, matrix_path=MATRIX_PATH, expected=self.expected)

    def test_compact_rejects_path_traversal_extra_files_and_missing_branch_coverage(self) -> None:
        self.make_compact()
        manifest_path = self.compact / "manifest.json"
        manifest = json.loads(manifest_path.read_text())
        manifest["files"][0]["path"] = "../escape.webp"
        manifest_path.write_text(json.dumps(manifest), encoding="utf-8")
        with self.assertRaises(evidence.EvidenceError):
            evidence.validate_compact(self.compact, matrix_path=MATRIX_PATH, expected=self.expected)

        # Rebuild a valid compact bundle and prove an unmanifested file is rejected.
        self.compact = self.root / "compact-two"
        with mock.patch.object(evidence, "inspect_screenshot_for_step", return_value=_metrics()), mock.patch.object(
            evidence, "compare_screenshots", return_value=_comparison()
        ):
            evidence.compact(
                input_root=self.raw,
                output=self.compact,
                matrix_path=MATRIX_PATH,
                expected=self.expected,
                source_artifact_id=501,
                source_artifact_name=evidence.raw_artifact_name(ACTIVE_BRANCH, 2),
                source_artifact_digest="sha256:" + "5" * 64,
            )
        (self.compact / "unexpected.txt").write_text("no")
        with self.assertRaises(evidence.EvidenceError):
            evidence.validate_compact(self.compact, matrix_path=MATRIX_PATH, expected=self.expected)

    def test_branch_tokens_do_not_embed_opaque_branch_names(self) -> None:
        branch = "release/weird'$(printf nope);name"
        token = evidence.branch_token(branch)
        self.assertRegex(token, r"^[0-9a-f]{24}$")
        self.assertNotIn("release", evidence.raw_artifact_name(branch, 1))
        self.assertEqual(token, hashlib.sha256(branch.encode()).hexdigest()[:24])


def _artifact(
    artifact_id: int,
    name: str,
    run_id: int,
    head_sha: str,
    *,
    created: datetime,
    branch: str = "master",
) -> Artifact:
    return Artifact(
        id=artifact_id,
        name=name,
        size=100,
        expired=False,
        created_at=created.isoformat().replace("+00:00", "Z"),
        digest="sha256:" + "a" * 64,
        run_id=run_id,
        head_branch=branch,
        head_sha=head_sha,
    )


class RotationPlanTests(unittest.TestCase):
    def test_rotation_waits_for_success_and_deletes_only_exact_older_ids(self) -> None:
        now = datetime.now(timezone.utc)
        cache_name = evidence.cache_artifact_name("master", COMMIT)
        old_name = evidence.cache_artifact_name("master", "0" * 40)
        keep = _artifact(20, cache_name, 200, "a" * 40, created=now)
        collected = _artifact(
            19,
            evidence.collection_artifact_name("master", COMMIT),
            200,
            "a" * 40,
            created=now - timedelta(minutes=1),
        )
        old = _artifact(10, old_name, 100, "9" * 40, created=now - timedelta(days=1))
        deploy = _artifact(21, "github-pages", 200, "a" * 40, created=now)
        promotion = _artifact(22, "pages-promotion", 200, "a" * 40, created=now)
        raw = _artifact(5, evidence.raw_artifact_name("master", 2), 101, COMMIT, created=now - timedelta(hours=1), branch="master")

        class FakeApi:
            def workflow(self, filename):
                return {"id": 77}

            def run(self, run_id):
                if run_id == 101:
                    return {
                        "workflow_id": 77,
                        "path": ".github/workflows/on-demand-e2e.yml",
                        "head_branch": "master",
                        "head_sha": COMMIT,
                        "event": "workflow_dispatch",
                        "head_repository": {"full_name": REPO_NAME},
                        "status": "completed",
                        "conclusion": "success",
                        "run_attempt": 2,
                    }
                sha = "a" * 40 if run_id == 200 else "9" * 40
                return {
                    "workflow_id": 77,
                    "path": ".github/workflows/pages.yml",
                    "head_branch": "master",
                    "head_sha": sha,
                    "event": "repository_dispatch",
                    "head_repository": {"full_name": REPO_NAME},
                    "status": "completed",
                    "conclusion": "success",
                }

            def artifacts_for_run(self, run_id):
                return [collected, keep, deploy, promotion]

            def all_artifacts(self):
                return [keep, old, deploy, promotion, raw]

            def artifacts_named(self, name):
                return [raw] if name == raw.name else []

            def branch_head(self, branch):
                return COMMIT, TREE

        compact_manifest = {
            "source_artifact": {"id": raw.id},
            "provenance": {
                "handoff": {"run_id": raw.run_id, "run_attempt": 2}
            },
        }
        with mock.patch("scripts.pages.rotate_artifacts.validate_compact", return_value=compact_manifest):
            planned = plan_rotation(
                api=FakeApi(),
                repository=REPO_NAME,
                pages_run_id=200,
                pages_run_sha="a" * 40,
                inventory=_inventory("master"),
                caches_root=Path("unused"),
                canonical_branch="master",
            )
        self.assertEqual(planned, [5, 10, 19, 21, 22])

        newer = _artifact(30, old_name, 300, "b" * 40, created=now + timedelta(minutes=1))
        api = FakeApi()
        api.all_artifacts = lambda: [keep, newer]
        with mock.patch("scripts.pages.rotate_artifacts.validate_compact", return_value=compact_manifest), self.assertRaises(
            RotationError
        ):
            plan_rotation(
                api=api,
                repository=REPO_NAME,
                pages_run_id=200,
                pages_run_sha="a" * 40,
                inventory=_inventory("master"),
                caches_root=Path("unused"),
                canonical_branch="master",
            )


class SelectionTests(unittest.TestCase):
    def test_selector_binds_raw_handoff_to_current_commit_tree_run_and_attempt(self) -> None:
        now = datetime.now(timezone.utc)
        handoff = _artifact(
            50,
            evidence.raw_artifact_name("master", 3),
            101,
            COMMIT,
            created=now,
            branch="master",
        )

        class FakeApi:
            def branch_head(self, branch):
                return COMMIT, TREE

            def workflow(self, filename):
                return {"id": 8 if filename == "on-demand-e2e.yml" else 9}

            def runs(self, workflow_id, branch):
                return [
                    {
                        "id": 101,
                        "workflow_id": 8,
                        "path": evidence.E2E_WORKFLOW,
                        "head_branch": "master",
                        "head_sha": COMMIT,
                        "event": "workflow_dispatch",
                        "head_repository": {"full_name": REPO_NAME},
                        "status": "completed",
                        "conclusion": "success",
                        "created_at": now.isoformat().replace("+00:00", "Z"),
                        "run_attempt": 3,
                    }
                ]

            def artifacts_for_run(self, run_id):
                return [handoff]

            def artifacts_named(self, name):
                return []

        kind, selected, attempt, source_run_id, source_run_attempt = select(
            FakeApi(),
            repository=REPO_NAME,
            branch="master",
            commit=COMMIT,
            tree=TREE,
            canonical_branch="master",
        )
        self.assertEqual((kind, selected.id, attempt), ("raw", 50, 3))
        self.assertEqual((source_run_id, source_run_attempt), (101, 3))
        with self.assertRaisesRegex(SelectionError, "tree identity"):
            select(
                FakeApi(),
                repository=REPO_NAME,
                branch=ACTIVE_BRANCH,
                commit=COMMIT,
                tree="f" * 40,
                canonical_branch="master",
            )

    def test_expired_raw_uses_only_a_cache_bound_to_the_newest_source_attempt(self) -> None:
        now = datetime.now(timezone.utc)
        cache = _artifact(
            70,
            evidence.cache_artifact_name("master", COMMIT),
            900,
            "9" * 40,
            created=now,
            branch="master",
        )

        class FakeApi:
            def branch_head(self, branch):
                return COMMIT, TREE

            def workflow(self, filename):
                return {"id": 8 if filename == "on-demand-e2e.yml" else 9}

            def runs(self, workflow_id, branch):
                return [{
                    "id": 101,
                    "workflow_id": 8,
                    "path": evidence.E2E_WORKFLOW,
                    "head_branch": "master",
                    "head_sha": COMMIT,
                    "event": "workflow_dispatch",
                    "head_repository": {"full_name": REPO_NAME},
                    "status": "completed",
                    "conclusion": "success",
                    "created_at": now.isoformat().replace("+00:00", "Z"),
                    "run_attempt": 4,
                }]

            def artifacts_for_run(self, run_id):
                return []

            def artifacts_named(self, name):
                return [cache]

            def run(self, run_id):
                return {
                    "workflow_id": 9,
                    "path": ".github/workflows/pages.yml",
                    "head_branch": "master",
                    "head_sha": "9" * 40,
                    "event": "repository_dispatch",
                    "head_repository": {"full_name": REPO_NAME},
                    "status": "completed",
                    "conclusion": "success",
                }

        kind, selected, artifact_attempt, source_run_id, source_attempt = select(
            FakeApi(),
            repository=REPO_NAME,
            branch="master",
            commit=COMMIT,
            tree=TREE,
            canonical_branch="master",
        )
        self.assertEqual(
            (kind, selected.id, artifact_attempt, source_run_id, source_attempt),
            ("compact", 70, 0, 101, 4),
        )

    def test_selector_never_falls_back_from_newest_exact_head_attempt(self) -> None:
        now = datetime.now(timezone.utc)
        older = _artifact(
            50,
            evidence.raw_artifact_name("master", 1),
            101,
            COMMIT,
            created=now - timedelta(minutes=5),
            branch="master",
        )

        class FakeApi:
            def branch_head(self, branch):
                return COMMIT, TREE

            def workflow(self, filename):
                return {"id": 8 if filename == "on-demand-e2e.yml" else 9}

            def runs(self, workflow_id, branch):
                common = {
                    "workflow_id": 8,
                    "path": evidence.E2E_WORKFLOW,
                    "head_branch": "master",
                    "head_sha": COMMIT,
                    "event": "workflow_dispatch",
                    "head_repository": {"full_name": REPO_NAME},
                }
                return [
                    {
                        **common,
                        "id": 101,
                        "status": "completed",
                        "conclusion": "success",
                        "created_at": (now - timedelta(minutes=5)).isoformat().replace("+00:00", "Z"),
                        "run_attempt": 1,
                    },
                    {
                        **common,
                        "id": 102,
                        "status": "in_progress",
                        "conclusion": None,
                        "created_at": now.isoformat().replace("+00:00", "Z"),
                        "run_attempt": 1,
                    },
                ]

            def artifacts_for_run(self, run_id):
                return [older]

            def artifacts_named(self, name):
                return []

        with self.assertRaisesRegex(SelectionError, "newest exact-head"):
            select(
                FakeApi(),
                repository=REPO_NAME,
                branch="master",
                commit=COMMIT,
                tree=TREE,
                canonical_branch="master",
            )


class SourceAuthenticationTests(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        self.packaged = self.root / "packaged"
        _fixture_profiles(self.packaged)
        self.raw = self.root / "raw"

    def tearDown(self) -> None:
        self.temporary.cleanup()

    def curate(self) -> dict:
        with mock.patch.object(evidence, "inspect_screenshot_for_step", return_value=_metrics()), mock.patch.object(
            evidence, "compare_screenshots", return_value=_comparison()
        ):
            return evidence.curate(
                input_root=self.packaged,
                output=self.raw,
                matrix_path=MATRIX_PATH,
                repository=REPO_NAME,
                branch=ACTIVE_BRANCH,
                commit=COMMIT,
                tree=TREE,
                run_id=101,
                run_attempt=2,
            )

    def _jobs(
        self,
        attempt: int = 2,
        *,
        source_branch: str = ACTIVE_BRANCH,
    ) -> list[dict[str, object]]:
        return [
            {
                "id": 1000 + index,
                "name": expected.name,
                "run_attempt": attempt,
                "status": "completed",
                "conclusion": expected.conclusion,
            }
            for index, expected in enumerate(
                _expected_jobs(
                    MATRIX_PATH,
                    "workflow_dispatch",
                    source_branch=source_branch,
                )
            )
        ]

    def test_original_packaged_run_requires_exact_matrix_derived_job_graph(self) -> None:
        self.curate()
        jobs = self._jobs()

        class FakeApi:
            def workflow(self, filename):
                return {"id": 8}

            def run(self, run_id):
                return {
                    "workflow_id": 8,
                    "path": evidence.E2E_WORKFLOW,
                    "head_branch": ACTIVE_BRANCH,
                    "head_sha": COMMIT,
                    "event": "workflow_dispatch",
                    "head_repository": {"full_name": REPO_NAME},
                    "status": "completed",
                    "conclusion": "success",
                    "run_attempt": 2,
                }

            def commit_tree(self, commit):
                return TREE

            def jobs_for_attempt(self, run_id, attempt):
                return list(jobs)

        result = authenticate(
            FakeApi(),
            repository=REPO_NAME,
            canonical_branch=CANONICAL_BRANCH,
            matrix_path=MATRIX_PATH,
            evidence_root=self.raw,
            selected_kind="raw",
            selected_run_id=101,
            selected_run_attempt=2,
            expected_handoff_run_id=101,
            expected_handoff_run_attempt=2,
        )
        self.assertFalse(result["attested"])
        with self.assertRaisesRegex(SourceAuthenticationError, "newest exact-head"):
            authenticate(
                FakeApi(),
                repository=REPO_NAME,
                canonical_branch=CANONICAL_BRANCH,
                matrix_path=MATRIX_PATH,
                evidence_root=self.raw,
                selected_kind="raw",
                selected_run_id=101,
                selected_run_attempt=2,
                expected_handoff_run_id=102,
                expected_handoff_run_attempt=1,
            )
        jobs.pop()
        with self.assertRaisesRegex(SourceAuthenticationError, "exact job inventory mismatch"):
            authenticate(
                FakeApi(),
                repository=REPO_NAME,
                canonical_branch=CANONICAL_BRANCH,
                matrix_path=MATRIX_PATH,
                evidence_root=self.raw,
                selected_kind="raw",
                selected_run_id=101,
                selected_run_attempt=2,
                expected_handoff_run_id=101,
                expected_handoff_run_attempt=2,
            )

    def test_post_merge_handoff_requires_successful_exact_tree_attestation(self) -> None:
        with mock.patch.object(evidence, "inspect_screenshot_for_step", return_value=_metrics()), mock.patch.object(
            evidence, "compare_screenshots", return_value=_comparison()
        ):
            evidence.curate(
                input_root=self.packaged,
                output=self.raw,
                matrix_path=MATRIX_PATH,
                repository=REPO_NAME,
                branch=ACTIVE_BRANCH,
                commit=COMMIT,
                tree=TREE,
                run_id=101,
                run_attempt=2,
                packaged_run_id=99,
                packaged_run_attempt=3,
                packaged_branch="automation/release-sync/example",
                packaged_commit="6" * 40,
                packaged_tree=TREE,
            )
        packaged_jobs = self._jobs(
            attempt=3,
            source_branch="automation/release-sync/example",
        )
        attestation_jobs = [
            {
                "id": 88,
                "name": "Attest exact tested packaged tree / Verify exact tested tree",
                "run_attempt": 2,
                "status": "completed",
                "conclusion": "success",
            }
        ]

        class FakeApi:
            def workflow(self, filename):
                return {"id": 8}

            def run(self, run_id):
                packaged = run_id == 99
                return {
                    "workflow_id": 8,
                    "path": evidence.E2E_WORKFLOW,
                    "head_branch": (
                        "automation/release-sync/example" if packaged else ACTIVE_BRANCH
                    ),
                    "head_sha": "6" * 40 if packaged else COMMIT,
                    "event": "workflow_dispatch",
                    "head_repository": {"full_name": REPO_NAME},
                    "status": "completed",
                    "conclusion": "success",
                    "run_attempt": 3 if packaged else 2,
                }

            def commit_tree(self, commit):
                return TREE

            def jobs_for_attempt(self, run_id, attempt):
                return list(packaged_jobs if run_id == 99 else attestation_jobs)

        result = authenticate(
            FakeApi(),
            repository=REPO_NAME,
            canonical_branch=CANONICAL_BRANCH,
            matrix_path=MATRIX_PATH,
            evidence_root=self.raw,
            selected_kind="raw",
            selected_run_id=101,
            selected_run_attempt=2,
            expected_handoff_run_id=101,
            expected_handoff_run_attempt=2,
        )
        self.assertTrue(result["attested"])
        attestation_jobs.clear()
        with self.assertRaisesRegex(SourceAuthenticationError, "attestation job"):
            authenticate(
                FakeApi(),
                repository=REPO_NAME,
                canonical_branch=CANONICAL_BRANCH,
                matrix_path=MATRIX_PATH,
                evidence_root=self.raw,
                selected_kind="raw",
                selected_run_id=101,
                selected_run_attempt=2,
                expected_handoff_run_id=101,
                expected_handoff_run_attempt=2,
            )


class ArtifactArchiveTests(unittest.TestCase):
    @staticmethod
    def digest(path: Path) -> str:
        return "sha256:" + hashlib.sha256(path.read_bytes()).hexdigest()

    def test_bounded_extractor_accepts_regular_inventory(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            archive = root / "artifact.zip"
            with zipfile.ZipFile(archive, "w", compression=zipfile.ZIP_DEFLATED) as package:
                package.writestr("pages-evidence.json", b"{}")
                package.writestr("profiles/lane/result.json", b"{\"pass\":true}")
            summary = extract_archive(archive, root / "output", expected_digest=self.digest(archive))
            self.assertEqual(summary["files"], 2)
            self.assertEqual((root / "output/pages-evidence.json").read_bytes(), b"{}")

    def test_bounded_extractor_rejects_digest_traversal_duplicate_symlink_and_bombs(self) -> None:
        cases = {}
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            traversal = root / "traversal.zip"
            with zipfile.ZipFile(traversal, "w") as package:
                package.writestr("../escape", b"bad")
            cases["traversal"] = traversal

            duplicate = root / "duplicate.zip"
            with warnings.catch_warnings():
                warnings.simplefilter("ignore", UserWarning)
                with zipfile.ZipFile(duplicate, "w") as package:
                    package.writestr("same", b"one")
                    package.writestr("same", b"two")
            cases["duplicate"] = duplicate

            symlink = root / "symlink.zip"
            link = zipfile.ZipInfo("link")
            link.create_system = 3
            link.external_attr = (stat.S_IFLNK | 0o777) << 16
            with zipfile.ZipFile(symlink, "w") as package:
                package.writestr(link, b"target")
            cases["symlink"] = symlink

            for label, archive in cases.items():
                with self.subTest(label=label), self.assertRaises(ArtifactDownloadError):
                    extract_archive(archive, root / f"out-{label}", expected_digest=self.digest(archive))

            valid = root / "valid.zip"
            with zipfile.ZipFile(valid, "w", compression=zipfile.ZIP_DEFLATED) as package:
                package.writestr("large", b"x" * 1024)
            with self.assertRaisesRegex(ArtifactDownloadError, "digest mismatch"):
                extract_archive(valid, root / "bad-digest", expected_digest="sha256:" + "0" * 64)
            with mock.patch("scripts.pages.download_artifact.MAX_UNCOMPRESSED_BYTES", 100):
                with self.assertRaisesRegex(ArtifactDownloadError, "uncompressed"):
                    extract_archive(valid, root / "oversized", expected_digest=self.digest(valid))


class WorkflowContractTests(unittest.TestCase):
    def test_pages_workflow_keeps_writes_out_of_untrusted_validation_jobs(self) -> None:
        workflow = (REPOSITORY / ".github/workflows/pages.yml").read_text()
        self.assertIn("permissions: {}", workflow)
        self.assertIn('workflows: ["Packaged E2E"]', workflow)
        self.assertIn("persist-credentials: false", workflow)
        self.assertIn("digest-mismatch: error", workflow)
        self.assertIn("pages: write", workflow)
        self.assertIn("id-token: write", workflow)
        self.assertIn("actions: write", workflow)
        self.assertIn("--pages-run-id", workflow)
        self.assertIn("default_branch", workflow)
        self.assertIn("git check-ref-format", workflow)
        self.assertNotIn("branches/master", workflow)
        self.assertNotIn("--branch '${{", workflow)
        self.assertNotIn("pull_request_target", workflow)
        self.assertNotIn("\n  workflow_dispatch:", workflow)
        self.assertIn("repository_dispatch:", workflow)
        self.assertIn("event_type:\"pages-rotate\"", workflow)
        self.assertIn("scripts/pages/authenticate_source.py", workflow)
        collect = workflow.split("  collect:", 1)[1].split("  build:", 1)[0]
        build_job = workflow.split("  build:", 1)[1].split("  deploy:", 1)[0]
        self.assertNotIn("actions: write", collect)
        self.assertNotIn("actions: write", build_job)
        for line in workflow.splitlines():
            stripped = line.strip()
            if stripped.startswith("uses:"):
                self.assertRegex(stripped, r"uses: [^@]+@[0-9a-f]{40}(?:\s+#.*)?$")


if __name__ == "__main__":
    unittest.main()

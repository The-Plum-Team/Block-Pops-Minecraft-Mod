from __future__ import annotations

import copy
import hashlib
import json
import stat
import tempfile
import unittest
import urllib.error
import urllib.request
import warnings
import zipfile
from datetime import datetime, timedelta, timezone
from pathlib import Path
from unittest import mock

from PIL import Image

from e2e.scenario_contract import default_contract
from scripts.pages import evidence, visual_anchor
from scripts.pages.build_site import build
from scripts.pages.authenticate_source import (
    SourceAuthenticationError,
    _expected_jobs,
    authenticate,
)
from scripts.pages.download_artifact import (
    ArtifactDownloadError,
    _CredentialSafeRedirect,
    extract_archive,
)
from scripts.pages.rotate_artifacts import (
    RotationApi,
    RotationError,
    plan_rotation,
    validate_current_invocation,
)
from scripts.pages.select_artifact import Artifact, SelectionError, select
from scripts.release.matrix import load_matrix


REPOSITORY = Path(__file__).resolve().parents[1]
MATRIX_PATH = REPOSITORY / "release" / "release-matrix.json"
MATRIX = load_matrix(MATRIX_PATH)
ACTIVE_BRANCH = MATRIX["branch"]["name"]
CANONICAL_BRANCH = MATRIX["branch"]["canonical"]
COMMIT = "1" * 40
TREE = "2" * 40
CONTROLLER_SHA = "a" * 40
REPO_NAME = "AkaNebur/BlockPops"


def _metrics() -> dict[str, int]:
    return {"width": 1600, "height": 900}


def _comparison() -> dict[str, float]:
    return {"changed_fraction": 0.5}


def _fixture_profiles(root: Path, matrix_path: Path = MATRIX_PATH) -> None:
    matrix = load_matrix(matrix_path)
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
                "controller_branch": CANONICAL_BRANCH,
                "controller_sha": CONTROLLER_SHA,
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
                controller_branch=CANONICAL_BRANCH,
                controller_sha=CONTROLLER_SHA,
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
            "invalid controller": lambda value: value["provenance"]["handoff"].__setitem__(
                "controller_sha", "not-a-sha"
            ),
            "stale controller": lambda value: value["provenance"]["handoff"].__setitem__(
                "controller_sha", "9" * 40
            ),
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

    def test_workflow_uploads_raw_first_for_one_day_and_canonical_anchor_for_ninety(self) -> None:
        workflow = (REPOSITORY / ".github/workflows/on-demand-e2e.yml").read_text(
            encoding="utf-8"
        )
        public = workflow.split("  public-evidence:", 1)[1]
        raw = public.index("Upload a single-use current-head Pages handoff")
        create = public.index("Create the exact canonical lossless visual anchor")
        durable = public.index("Upload the durable lossless canonical visual anchor")
        self.assertLess(raw, create)
        self.assertLess(create, durable)
        self.assertIn("id: raw_evidence", public[raw:create])
        self.assertIn("retention-days: 1", public[raw:create])
        self.assertIn("steps.raw_evidence.outputs.artifact-id", public[create:durable])
        self.assertIn("steps.raw_evidence.outputs.artifact-digest", public[create:durable])
        self.assertIn("scripts/pages/visual_anchor.py create", public[create:durable])
        self.assertIn('--controller-branch "$HANDOFF_CONTROLLER_BRANCH"', public)
        self.assertIn('--controller-sha "$HANDOFF_CONTROLLER_SHA"', public)
        self.assertIn(
            '--packaged-controller-sha "$PACKAGED_CONTROLLER_SHA"', public
        )
        self.assertIn(
            '--source-controller-sha "$HANDOFF_CONTROLLER_SHA"',
            public[create:durable],
        )
        self.assertIn("retention-days: 90", public[durable:])


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
        raw = _artifact(
            5,
            evidence.raw_artifact_name("master", 2),
            101,
            CONTROLLER_SHA,
            created=now - timedelta(hours=1),
            branch="master",
        )
        anchor = _artifact(
            30,
            visual_anchor.visual_anchor_artifact_name("master", COMMIT, 102, 2),
            102,
            COMMIT,
            created=now - timedelta(minutes=30),
            branch="master",
        )
        old_anchor = _artifact(
            6,
            visual_anchor.visual_anchor_artifact_name(
                "master", "8" * 40, 104, 2
            ),
            104,
            "8" * 40,
            created=now - timedelta(days=2),
            branch="master",
        )
        duplicate_current_anchor = _artifact(
            8,
            visual_anchor.visual_anchor_artifact_name("master", COMMIT, 103, 2),
            103,
            COMMIT,
            created=now - timedelta(hours=2),
            branch="master",
        )
        unauthenticated_anchor = _artifact(
            7,
            visual_anchor.visual_anchor_artifact_name(
                "master", "7" * 40, 99, 2
            ),
            99,
            "7" * 40,
            created=now - timedelta(days=3),
            branch="fork/untrusted",
        )

        class FakeApi:
            def workflow(self, filename):
                return {"id": 77}

            def run(self, run_id):
                if run_id in {101, 102, 103, 104}:
                    source_sha = {
                        101: CONTROLLER_SHA,
                        102: COMMIT,
                        103: COMMIT,
                        104: "8" * 40,
                    }[run_id]
                    published_sha = "8" * 40 if run_id == 104 else COMMIT
                    return {
                        "id": run_id,
                        "workflow_id": 77,
                        "path": ".github/workflows/on-demand-e2e.yml",
                        "head_branch": "master",
                        "head_sha": source_sha,
                        "display_title": f"Packaged E2E / {published_sha}",
                        "event": "workflow_dispatch",
                        "head_repository": {"full_name": REPO_NAME},
                        "status": "completed",
                        "conclusion": "success",
                        "run_attempt": 2,
                    }
                sha = "a" * 40 if run_id == 200 else "9" * 40
                return {
                    "id": run_id,
                    "workflow_id": 77,
                    "path": ".github/workflows/pages.yml",
                    "head_branch": "master",
                    "head_sha": sha,
                    "event": "repository_dispatch",
                    "head_repository": {"full_name": REPO_NAME},
                    "status": "completed",
                    "conclusion": "success",
                    "run_attempt": 2,
                }

            def run_attempt(self, run_id, run_attempt):
                if run_attempt != 2:
                    raise AssertionError((run_id, run_attempt))
                return self.run(run_id)

            def artifacts_for_run(self, run_id):
                return [collected, keep, deploy, promotion]

            def all_artifacts(self):
                return [
                    keep,
                    old,
                    deploy,
                    promotion,
                    raw,
                    anchor,
                    old_anchor,
                    duplicate_current_anchor,
                    unauthenticated_anchor,
                ]

            def artifacts_named(self, name):
                return [raw] if name == raw.name else []

            def branch_head(self, branch):
                return COMMIT, TREE

        compact_manifest = {
            "source_artifact": {
                "id": raw.id,
                "name": raw.name,
                "digest": raw.digest,
            },
            "provenance": {
                "handoff": {
                    "run_id": raw.run_id,
                    "run_attempt": 2,
                    "controller_branch": "master",
                    "controller_sha": CONTROLLER_SHA,
                }
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
                now=now,
            )
        self.assertEqual(planned, [5, 10, 19, 21, 22])

        active = FakeApi()
        completed_run = active.run

        def active_run(run_id):
            value = completed_run(run_id)
            if run_id == 200:
                value = {
                    **value,
                    "status": "in_progress",
                    "conclusion": None,
                    "run_attempt": 3,
                }
            return value

        active.run = active_run
        with mock.patch(
            "scripts.pages.rotate_artifacts.validate_compact",
            return_value=compact_manifest,
        ):
            active_plan = plan_rotation(
                api=active,
                repository=REPO_NAME,
                pages_run_id=200,
                pages_run_sha="a" * 40,
                inventory=_inventory("master"),
                caches_root=Path("unused"),
                canonical_branch="master",
                current_run_attempt=3,
                now=now,
            )
        self.assertEqual(active_plan, planned)
        with mock.patch(
            "scripts.pages.rotate_artifacts.validate_compact",
            return_value=compact_manifest,
        ), self.assertRaisesRegex(RotationError, "attempt is not exact"):
            plan_rotation(
                api=active,
                repository=REPO_NAME,
                pages_run_id=200,
                pages_run_sha="a" * 40,
                inventory=_inventory("master"),
                caches_root=Path("unused"),
                canonical_branch="master",
                current_run_attempt=2,
                now=now,
            )

        with mock.patch(
            "scripts.pages.rotate_artifacts.validate_compact",
            return_value=compact_manifest,
        ):
            within_expiry_margin = plan_rotation(
                api=FakeApi(),
                repository=REPO_NAME,
                pages_run_id=200,
                pages_run_sha="a" * 40,
                inventory=_inventory("master"),
                caches_root=Path("unused"),
                canonical_branch="master",
                now=now + timedelta(days=7),
            )
        self.assertEqual(within_expiry_margin, [5, 10, 19, 21, 22])

        with mock.patch(
            "scripts.pages.rotate_artifacts.validate_compact",
            return_value=compact_manifest,
        ):
            after_anchor_grace = plan_rotation(
                api=FakeApi(),
                repository=REPO_NAME,
                pages_run_id=200,
                pages_run_sha="a" * 40,
                inventory=_inventory("master"),
                caches_root=Path("unused"),
                canonical_branch="master",
                now=now + timedelta(days=9),
            )
        self.assertEqual(after_anchor_grace, [5, 6, 8, 10, 19, 21, 22])

        missing_anchor = FakeApi()
        missing_anchor.all_artifacts = lambda: [keep, old, raw]
        with self.assertRaisesRegex(RotationError, "current-head visual anchor"):
            plan_rotation(
                api=missing_anchor,
                repository=REPO_NAME,
                pages_run_id=200,
                pages_run_sha="a" * 40,
                inventory=_inventory("master"),
                caches_root=Path("unused"),
                canonical_branch="master",
                now=now,
            )

        newer = _artifact(30, old_name, 300, "b" * 40, created=now + timedelta(minutes=1))
        api = FakeApi()
        api.all_artifacts = lambda: [keep, newer, anchor]
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
                now=now,
            )

    def test_delete_by_exact_id_treats_only_404_as_idempotent(self) -> None:
        api = RotationApi(
            repository=REPO_NAME,
            token="test-token",
            api_url="https://api.github.invalid",
        )
        missing = urllib.error.HTTPError(
            "https://api.github.invalid/artifact/9", 404, "missing", {}, None
        )
        with mock.patch.object(api.opener, "open", side_effect=missing):
            api.delete_artifact(9)

        denied = urllib.error.HTTPError(
            "https://api.github.invalid/artifact/9", 403, "denied", {}, None
        )
        with mock.patch.object(api.opener, "open", side_effect=denied), self.assertRaises(
            RotationError
        ):
            api.delete_artifact(9)

    def test_current_rotation_invocation_is_exactly_workflow_attempt_bound(self) -> None:
        environment = {
            "GITHUB_REPOSITORY": REPO_NAME,
            "GITHUB_REF": "refs/heads/master",
            "GITHUB_SHA": "a" * 40,
            "GITHUB_RUN_ID": "200",
            "GITHUB_RUN_ATTEMPT": "3",
            "GITHUB_WORKFLOW_REF": (
                f"{REPO_NAME}/.github/workflows/pages.yml@refs/heads/master"
            ),
        }
        validate_current_invocation(
            environment,
            repository=REPO_NAME,
            canonical_branch="master",
            pages_run_id=200,
            pages_run_attempt=3,
            pages_run_sha="a" * 40,
        )
        stale = {**environment, "GITHUB_RUN_ATTEMPT": "4"}
        with self.assertRaisesRegex(RotationError, "GITHUB_RUN_ATTEMPT"):
            validate_current_invocation(
                stale,
                repository=REPO_NAME,
                canonical_branch="master",
                pages_run_id=200,
                pages_run_attempt=3,
                pages_run_sha="a" * 40,
            )


class SelectionTests(unittest.TestCase):
    def test_release_head_is_selected_by_exact_title_on_default_controller(self) -> None:
        now = datetime.now(timezone.utc)
        release_branch = "release/1.21.1"
        title = f"Packaged E2E / {COMMIT}"
        handoff = _artifact(
            45,
            evidence.raw_artifact_name(release_branch, 2),
            88,
            CONTROLLER_SHA,
            created=now,
            branch=CANONICAL_BRANCH,
        )

        class FakeApi:
            def branch_head(self, branch):
                if branch != release_branch:
                    raise AssertionError(branch)
                return COMMIT, TREE

            def workflow(self, filename):
                return {"id": 8}

            def runs(self, workflow_id, branch):
                if branch != CANONICAL_BRANCH:
                    raise AssertionError("selector queried the published branch, not default")
                return [
                    {
                        "id": 88,
                        "workflow_id": 8,
                        "path": evidence.E2E_WORKFLOW,
                        "head_branch": CANONICAL_BRANCH,
                        "head_sha": CONTROLLER_SHA,
                        "display_title": title,
                        "event": "workflow_dispatch",
                        "head_repository": {"full_name": REPO_NAME},
                        "status": "completed",
                        "conclusion": "success",
                        "created_at": now.isoformat().replace("+00:00", "Z"),
                        "run_attempt": 2,
                    }
                ]

            def artifacts_for_run(self, run_id):
                return [handoff]

            def artifacts_named(self, name):
                return []

        result = select(
            FakeApi(),
            repository=REPO_NAME,
            branch=release_branch,
            commit=COMMIT,
            tree=TREE,
            canonical_branch=CANONICAL_BRANCH,
        )
        self.assertEqual(result[0:5], ("raw", handoff, 2, 88, 2))
        self.assertEqual(result[5:], (CANONICAL_BRANCH, CONTROLLER_SHA))
        title = f"Packaged E2E / {'f' * 40}"
        with self.assertRaisesRegex(SelectionError, "no exact-head"):
            select(
                FakeApi(),
                repository=REPO_NAME,
                branch=release_branch,
                commit=COMMIT,
                tree=TREE,
                canonical_branch=CANONICAL_BRANCH,
            )

    def test_selector_binds_raw_handoff_to_current_commit_tree_run_and_attempt(self) -> None:
        now = datetime.now(timezone.utc)
        handoff = _artifact(
            50,
            evidence.raw_artifact_name("master", 3),
            101,
            CONTROLLER_SHA,
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
                        "head_sha": CONTROLLER_SHA,
                        "display_title": f"Packaged E2E / {COMMIT}",
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

        (
            kind,
            selected,
            attempt,
            source_run_id,
            source_run_attempt,
            controller_branch,
            controller_sha,
        ) = select(
            FakeApi(),
            repository=REPO_NAME,
            branch="master",
            commit=COMMIT,
            tree=TREE,
            canonical_branch="master",
        )
        self.assertEqual((kind, selected.id, attempt), ("raw", 50, 3))
        self.assertEqual((source_run_id, source_run_attempt), (101, 3))
        self.assertEqual((controller_branch, controller_sha), ("master", CONTROLLER_SHA))
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
                    "head_sha": CONTROLLER_SHA,
                    "display_title": f"Packaged E2E / {COMMIT}",
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
                    "id": 900,
                    "workflow_id": 9,
                    "path": ".github/workflows/pages.yml",
                    "head_branch": "master",
                    "head_sha": "9" * 40,
                    "event": "repository_dispatch",
                    "head_repository": {"full_name": REPO_NAME},
                    "status": "completed",
                    "conclusion": "success",
                    "run_attempt": 2,
                }

        (
            kind,
            selected,
            artifact_attempt,
            source_run_id,
            source_attempt,
            controller_branch,
            controller_sha,
        ) = select(
            FakeApi(),
            repository=REPO_NAME,
            branch="master",
            commit=COMMIT,
            tree=TREE,
            canonical_branch="master",
        )
        self.assertEqual(
            (
                kind,
                selected.id,
                artifact_attempt,
                source_run_id,
                source_attempt,
                controller_branch,
                controller_sha,
            ),
            ("compact", 70, 2, 101, 4, "master", CONTROLLER_SHA),
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
                    "head_sha": CONTROLLER_SHA,
                    "display_title": f"Packaged E2E / {COMMIT}",
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
        self.selected = _artifact(
            501,
            evidence.raw_artifact_name(ACTIVE_BRANCH, 2),
            101,
            CONTROLLER_SHA,
            created=datetime.now(timezone.utc),
            branch=CANONICAL_BRANCH,
        )

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
                controller_branch=CANONICAL_BRANCH,
                controller_sha=CONTROLLER_SHA,
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

    def authenticate(self, api, **overrides):
        arguments = {
            "repository": REPO_NAME,
            "canonical_branch": CANONICAL_BRANCH,
            "matrix_path": MATRIX_PATH,
            "evidence_root": self.raw,
            "selected_kind": "raw",
            "selected_artifact_id": self.selected.id,
            "selected_artifact_name": self.selected.name,
            "selected_artifact_digest": self.selected.digest,
            "selected_run_id": 101,
            "selected_run_attempt": 2,
            "expected_handoff_run_id": 101,
            "expected_handoff_run_attempt": 2,
        }
        arguments.update(overrides)
        return authenticate(api, **arguments)

    def test_original_packaged_run_requires_exact_matrix_derived_job_graph(self) -> None:
        self.curate()
        jobs = self._jobs()

        class FakeApi:
            def workflow(self, filename):
                return {"id": 8}

            def run_attempt(self, run_id, run_attempt):
                return {
                    "id": run_id,
                    "workflow_id": 8,
                    "path": evidence.E2E_WORKFLOW,
                    "head_branch": CANONICAL_BRANCH,
                    "head_sha": CONTROLLER_SHA,
                    "display_title": f"Packaged E2E / {COMMIT}",
                    "event": "workflow_dispatch",
                    "head_repository": {"full_name": REPO_NAME},
                    "status": "completed",
                    "conclusion": "success",
                    "run_attempt": 2,
                }

            def branch_head(self, branch):
                return COMMIT, TREE

            def commit_tree(self, commit):
                return TREE

            def jobs_for_attempt(self, run_id, attempt):
                return list(jobs)

            def artifacts_for_run(self, run_id):
                return [self_outer.selected]

        self_outer = self
        result = self.authenticate(FakeApi())
        self.assertFalse(result["attested"])

        manifest_path = self.raw / "pages-evidence.json"
        original_manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        for label, section in (
            ("handoff controller", "handoff"),
            ("packaged controller", "packaged"),
        ):
            mutated = copy.deepcopy(original_manifest)
            mutated["provenance"][section]["controller_sha"] = "d" * 40
            manifest_path.write_text(json.dumps(mutated), encoding="utf-8")
            with self.subTest(label=label), self.assertRaisesRegex(
                SourceAuthenticationError, "provenance"
            ):
                self.authenticate(FakeApi())
        manifest_path.write_text(json.dumps(original_manifest), encoding="utf-8")

        class BadTitleApi(FakeApi):
            def run_attempt(self, run_id, run_attempt):
                record = super().run_attempt(run_id, run_attempt)
                record["display_title"] = f"Packaged E2E / {'f' * 40}"
                return record

        with self.assertRaisesRegex(SourceAuthenticationError, "provenance"):
            self.authenticate(BadTitleApi())
        with self.assertRaisesRegex(SourceAuthenticationError, "artifact"):
            self.authenticate(
                FakeApi(), selected_artifact_digest="sha256:" + "f" * 64
            )
        with self.assertRaisesRegex(SourceAuthenticationError, "newest exact-head"):
            self.authenticate(
                FakeApi(),
                expected_handoff_run_id=102,
                expected_handoff_run_attempt=1,
            )
        jobs.pop()
        with self.assertRaisesRegex(SourceAuthenticationError, "exact job inventory mismatch"):
            self.authenticate(FakeApi())

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
                controller_branch=CANONICAL_BRANCH,
                controller_sha=CONTROLLER_SHA,
                packaged_run_id=99,
                packaged_run_attempt=3,
                packaged_branch="automation/release-sync/example",
                packaged_commit="6" * 40,
                packaged_tree=TREE,
                packaged_controller_branch=CANONICAL_BRANCH,
                packaged_controller_sha="b" * 40,
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

            def run_attempt(self, run_id, run_attempt):
                packaged = run_id == 99
                return {
                    "id": run_id,
                    "workflow_id": 8,
                    "path": evidence.E2E_WORKFLOW,
                    "head_branch": CANONICAL_BRANCH,
                    "head_sha": "b" * 40 if packaged else CONTROLLER_SHA,
                    "display_title": (
                        f"Packaged E2E / {'6' * 40}"
                        if packaged
                        else f"Packaged E2E / {COMMIT}"
                    ),
                    "event": "workflow_dispatch",
                    "head_repository": {"full_name": REPO_NAME},
                    "status": "completed",
                    "conclusion": "success",
                    "run_attempt": 3 if packaged else 2,
                }

            def branch_head(self, branch):
                return COMMIT, TREE

            def commit_tree(self, commit):
                return TREE

            def jobs_for_attempt(self, run_id, attempt):
                return list(packaged_jobs if run_id == 99 else attestation_jobs)

            def artifacts_for_run(self, run_id):
                return [self_outer.selected] if run_id == 101 else []

        self_outer = self
        result = self.authenticate(FakeApi())
        self.assertTrue(result["attested"])
        attestation_jobs.append({**attestation_jobs[0], "id": 89})
        with self.assertRaisesRegex(SourceAuthenticationError, "attestation job"):
            self.authenticate(FakeApi())
        attestation_jobs.clear()
        with self.assertRaisesRegex(SourceAuthenticationError, "attestation job"):
            self.authenticate(FakeApi())

    def test_compact_fallback_reauthenticates_its_historical_pages_owner(self) -> None:
        self.curate()
        compact = self.root / "compact"
        expected = {
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
                "controller_branch": CANONICAL_BRANCH,
                "controller_sha": CONTROLLER_SHA,
            },
        }
        with mock.patch.object(
            evidence, "inspect_screenshot_for_step", return_value=_metrics()
        ), mock.patch.object(
            evidence, "compare_screenshots", return_value=_comparison()
        ):
            evidence.compact(
                input_root=self.raw,
                output=compact,
                matrix_path=MATRIX_PATH,
                expected=expected,
                source_artifact_id=self.selected.id,
                source_artifact_name=self.selected.name,
                source_artifact_digest=self.selected.digest,
            )
        cache = _artifact(
            700,
            evidence.cache_artifact_name(ACTIVE_BRANCH, COMMIT),
            900,
            "c" * 40,
            created=datetime.now(timezone.utc),
            branch=CANONICAL_BRANCH,
        )
        jobs = self._jobs()

        class FakeApi:
            def workflow(self, filename):
                return {"id": 8 if filename == "on-demand-e2e.yml" else 9}

            def run_attempt(self, run_id, run_attempt):
                if run_id == 900:
                    return {
                        "id": 900,
                        "workflow_id": 9,
                        "path": ".github/workflows/pages.yml",
                        "head_branch": CANONICAL_BRANCH,
                        "head_sha": "c" * 40,
                        "event": "workflow_run",
                        "head_repository": {"full_name": REPO_NAME},
                        "status": "completed",
                        "conclusion": "success",
                        "run_attempt": 4,
                    }
                return {
                    "id": 101,
                    "workflow_id": 8,
                    "path": evidence.E2E_WORKFLOW,
                    "head_branch": CANONICAL_BRANCH,
                    "head_sha": CONTROLLER_SHA,
                    "display_title": f"Packaged E2E / {COMMIT}",
                    "event": "workflow_dispatch",
                    "head_repository": {"full_name": REPO_NAME},
                    "status": "completed",
                    "conclusion": "success",
                    "run_attempt": 2,
                }

            def branch_head(self, branch):
                return COMMIT, TREE

            def commit_tree(self, commit):
                return TREE

            def jobs_for_attempt(self, run_id, attempt):
                return list(jobs)

            def artifacts_for_run(self, run_id):
                return [cache] if run_id == 900 else []

        result = self.authenticate(
            FakeApi(),
            evidence_root=compact,
            selected_kind="compact",
            selected_artifact_id=cache.id,
            selected_artifact_name=cache.name,
            selected_artifact_digest=cache.digest,
            selected_run_id=900,
            selected_run_attempt=4,
        )
        self.assertFalse(result["attested"])


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

    def test_artifact_redirects_require_https_and_strip_cross_origin_auth(self) -> None:
        handler = _CredentialSafeRedirect()
        request = urllib.request.Request(
            "https://api.github.invalid/repos/example/artifact",
            headers={"Authorization": "Bearer secret"},
        )
        same_origin = handler.redirect_request(
            request,
            None,
            302,
            "Found",
            {},
            "https://api.github.invalid/temporary?signature=one",
        )
        self.assertIsNotNone(same_origin)
        self.assertEqual(same_origin.get_header("Authorization"), "Bearer secret")
        object_storage = handler.redirect_request(
            request,
            None,
            302,
            "Found",
            {},
            "https://objects.github.invalid/temporary?signature=two",
        )
        self.assertIsNotNone(object_storage)
        self.assertIsNone(object_storage.get_header("Authorization"))
        self.assertIsNone(
            handler.redirect_request(
                request,
                None,
                302,
                "Found",
                {},
                "http://objects.github.invalid/temporary",
            )
        )


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
        self.assertIn("  rotate-current:", workflow)
        current_rotation = workflow.split("  rotate-current:", 1)[1].split(
            "  rotate:", 1
        )[0]
        self.assertIn(
            "needs: [discover, collect, build, deploy, refresh-cache]",
            current_rotation,
        )
        self.assertIn("needs.refresh-cache.result == 'success'", current_rotation)
        self.assertIn('--current-run-attempt "$GITHUB_RUN_ATTEMPT"', current_rotation)
        self.assertNotIn('event_type:"pages-rotate"', current_rotation)
        self.assertEqual(1, workflow.count("group: blockpops-pages-lifecycle"))
        self.assertEqual(1, workflow.count("cancel-in-progress: false"))
        self.assertNotIn("blockpops-pages-deployment", workflow)
        self.assertNotIn("blockpops-pages-rotation", workflow)
        self.assertIn("scripts/pages/authenticate_source.py", workflow)
        self.assertIn(".display_title", (REPOSITORY / "scripts/pages/select_artifact.py").read_text())
        self.assertIn("api.run_attempt", (REPOSITORY / "scripts/pages/authenticate_source.py").read_text())
        self.assertIn("--selected-artifact-digest", workflow)
        self.assertIn("SOURCE_CONTROLLER_BRANCH", workflow)
        collect = workflow.split("  collect:", 1)[1].split("  build:", 1)[0]
        build_job = workflow.split("  build:", 1)[1].split("  deploy:", 1)[0]
        self.assertNotIn("actions: write", collect)
        self.assertNotIn("actions: write", build_job)
        for line in workflow.splitlines():
            stripped = line.strip()
            if stripped.startswith("uses:"):
                self.assertRegex(stripped, r"uses: [^@]+@[0-9a-f]{40}(?:\s+#.*)?$")

    def test_pages_bearer_clients_disable_proxies_and_unsafe_redirects(self) -> None:
        selector = (REPOSITORY / "scripts/pages/select_artifact.py").read_text()
        rotation = (REPOSITORY / "scripts/pages/rotate_artifacts.py").read_text()
        download = (REPOSITORY / "scripts/pages/download_artifact.py").read_text()
        self.assertIn("urllib.request.ProxyHandler({})", selector)
        self.assertIn("_NoRedirect()", selector)
        self.assertIn(
            "urllib.request.HTTPSHandler(context=ssl.create_default_context())",
            selector,
        )
        self.assertIn("self.opener.open", selector)
        self.assertIn("self.opener.open", rotation)
        self.assertNotIn("urllib.request.urlopen", selector + rotation + download)
        self.assertIn("urllib.request.ProxyHandler({})", download)
        self.assertIn("_CredentialSafeRedirect()", download)
        self.assertIn(
            "urllib.request.HTTPSHandler(context=ssl.create_default_context())",
            download,
        )
        self.assertIn('redirected.remove_header("Authorization")', download)


if __name__ == "__main__":
    unittest.main()

from __future__ import annotations

import copy
import hashlib
import json
import re
import shutil
import tempfile
import unittest
from datetime import datetime, timezone
from pathlib import Path
from unittest import mock

from e2e.visual_capsule import validate_capsule, write_capsule
from e2e.visual_evidence import load_archived_evidence
from e2e.visual_review_output import read_and_validate_review
from scripts.ci.e2e_fanin import aggregate_artifact_name
from scripts.release.matrix import load_matrix
from scripts.visual.curate import (
    ArtifactIdentity,
    CurationError,
    RunIdentity,
    _resolve_tested_identity,
    authenticate_run,
    exact_aggregate_artifact,
    reference_candidates,
    select_reference_run,
)
from scripts.visual.handoff import build_handoff
from scripts.visual.normalize import NormalizeError, normalize
from scripts.visual.review_client import (
    ReviewClientError,
    build_request,
    extract_response,
    run_review,
    validate_handoff,
)
from tests.visual_test_capsule import CONTRACT_PATH, MATRIX_PATH, _source


REPO = Path(__file__).resolve().parents[1]
WORKFLOW = REPO / ".github" / "workflows" / "visual-review.yml"
CLIENT = REPO / "scripts" / "visual" / "review_client.py"
REPOSITORY = "AkaNebur/BlockPops"
MASTER_SHA = "b" * 40
SOURCE_SHA = "a" * 40
MERGE_SHA = "9" * 40
BASE_SHA = "8" * 40
TREE_SHA = "7" * 40


def _job_block(text: str, name: str) -> str:
    start = text.index(f"  {name}:\n")
    matches = [match.start() for match in re.finditer(r"(?m)^  [a-z][a-z0-9_-]*:\n", text)]
    end = min((position for position in matches if position > start), default=len(text))
    return text[start:end]


def _run_record(
    run_id: int,
    *,
    status: str = "completed",
    conclusion: str | None = "success",
    created_at: str = "2026-08-10T10:00:00Z",
    event: str = "workflow_dispatch",
    head_sha: str = MASTER_SHA,
    head_branch: str = "master",
    head_repository: str = REPOSITORY,
) -> dict[str, object]:
    return {
        "id": run_id,
        "run_attempt": 1,
        "path": ".github/workflows/on-demand-e2e.yml",
        "status": status,
        "conclusion": conclusion,
        "event": event,
        "repository": {"full_name": REPOSITORY},
        "head_repository": {"full_name": head_repository},
        "head_branch": head_branch,
        "head_sha": head_sha,
        "created_at": created_at,
    }


def _artifact(run_id: int, *, commit: str = MASTER_SHA, attempt: int = 1) -> dict[str, object]:
    return {
        "id": 1000 + run_id,
        "name": aggregate_artifact_name(commit, attempt),
        "digest": "sha256:" + "d" * 64,
        "size_in_bytes": 12345,
        "expired": False,
        "workflow_run": {"id": run_id},
    }


def _job(run_id: int, name: str, *, attempt: int = 1) -> dict[str, object]:
    return {"id": 2000 + run_id, "name": name, "run_attempt": attempt}


def _response(pair: dict[str, object], *, defect: bool = False) -> bytes:
    findings = (
        [{"category": "clipping", "severity": "defect", "detail": "A label is clipped."}]
        if defect
        else []
    )
    result = {
        "schema_version": 1,
        "advisory": True,
        "verdict": {
            "label": pair["label"],
            "capture_id": pair["capture_id"],
            "matches_expectation": not defect,
            "semantic_regression": defect,
            "visible": "The expected packaged UI state is visible.",
            "findings": findings,
        },
    }
    envelope = {
        "status": "completed",
        "error": None,
        "incomplete_details": None,
        "output": [
            {
                "type": "message",
                "status": "completed",
                "content": [{"type": "output_text", "text": json.dumps(result)}],
            }
        ],
    }
    return json.dumps(envelope).encode("utf-8")


class _SelectionApi:
    def __init__(self, artifacts: dict[int, list[dict[str, object]]], jobs: dict[int, list[dict[str, object]]]):
        self._artifacts = artifacts
        self._jobs = jobs

    def artifacts(self, run_id: int) -> list[dict[str, object]]:
        return self._artifacts.get(run_id, [])

    def jobs(self, run_id: int) -> list[dict[str, object]]:
        return self._jobs.get(run_id, [])


class _PullApi:
    def __init__(self) -> None:
        self.pull = {
            "state": "open",
            "head": {
                "sha": SOURCE_SHA,
                "ref": "feature/current-ui",
                "repo": {"full_name": "contributor/BlockPops"},
            },
            "base": {
                "sha": BASE_SHA,
                "ref": "master",
                "repo": {"full_name": REPOSITORY},
            },
            "merge_commit_sha": MERGE_SHA,
        }
        self.parents = (BASE_SHA, SOURCE_SHA)
        self.branch_heads = {
            ("contributor/BlockPops", "feature/current-ui"): SOURCE_SHA,
            (REPOSITORY, "master"): BASE_SHA,
        }

    def pull_request(self, number: int) -> dict[str, object]:
        if number != 17:
            raise AssertionError(number)
        return copy.deepcopy(self.pull)

    def branch_head(self, repository: str, branch: str) -> str:
        return self.branch_heads[(repository, branch)]

    def commit_identity(self, repository: str, commit: str) -> tuple[str, tuple[str, ...]]:
        if (repository, commit) != (REPOSITORY, MERGE_SHA):
            raise AssertionError((repository, commit))
        return TREE_SHA, self.parents


class VisualReviewWorkflowContractTests(unittest.TestCase):
    def test_workflow_has_authenticated_advisory_triggers_and_pinned_actions(self) -> None:
        text = WORKFLOW.read_text(encoding="utf-8")
        self.assertIn("workflow_run:", text)
        self.assertIn("- Packaged E2E", text)
        self.assertIn("repository_dispatch:", text)
        self.assertIn("- visual-review-requested", text)
        self.assertIn("github.event.client_payload.source_run_id", text)
        self.assertIn(".github/workflows/on-demand-e2e.yml", text)
        self.assertIn("source_run_attempt", text)
        self.assertIn("aggregate_pattern=", text)
        self.assertIn("packaged-e2e-[0-9a-f]{40}", text)
        uses = re.findall(r"(?m)^\s*uses:\s*([^\s#]+)", text)
        self.assertTrue(uses)
        for action in uses:
            self.assertRegex(action, r"^[^@\s]+@[0-9a-f]{40}$")

    def test_credential_job_has_only_the_bounded_handoff_and_openai_secret(self) -> None:
        text = WORKFLOW.read_text(encoding="utf-8")
        review = _job_block(text, "review")
        self.assertIn("permissions: {}", review)
        self.assertIn("environment: visual-review", review)
        self.assertNotIn("actions/checkout", review)
        self.assertNotIn("github.token", review)
        self.assertNotIn("GITHUB_REPOSITORY", review)
        self.assertIn("artifact-ids: ${{ needs.curate.outputs.artifact_id }}", review)
        self.assertIn("digest-mismatch: error", review)
        self.assertIn('GH_TOKEN: ""', review)
        self.assertIn('GITHUB_TOKEN: ""', review)
        self.assertIn("OPENAI_API_KEY: ${{ secrets.OPENAI_API_KEY }}", review)
        self.assertIn("OPENAI_VISUAL_MODEL: ${{ vars.OPENAI_VISUAL_MODEL }}", review)
        self.assertNotIn("OPENAI_VISUAL_ENVIRONMENT", review)
        self.assertEqual(1, text.count("secrets.OPENAI_API_KEY"))
        client = CLIENT.read_text(encoding="utf-8")
        self.assertIn(
            'OPENAI_ENDPOINT = "https://api.openai.com/v1/responses"', client
        )
        self.assertIn("_NoRedirect()", client)

    def test_handoff_cleanup_is_always_exact_id_deletion(self) -> None:
        text = WORKFLOW.read_text(encoding="utf-8")
        cleanup = _job_block(text, "cleanup-handoff")
        self.assertIn("always()", cleanup)
        self.assertIn("actions: write", cleanup)
        self.assertIn("actions/artifacts/$ARTIFACT_ID", cleanup)
        self.assertIn(".workflow_run.id == $run_id", cleanup)
        self.assertNotIn("artifacts?name", cleanup)
        self.assertNotIn("--pattern", cleanup)
        curate = _job_block(text, "curate")
        self.assertEqual(1, curate.count("actions/upload-artifact@"))
        self.assertIn("retention-days: 1", curate)

    def test_publish_reauthenticates_tested_merge_and_remains_advisory(self) -> None:
        text = WORKFLOW.read_text(encoding="utf-8")
        publish = _job_block(text, "publish")
        self.assertIn(".merge_commit_sha == $tested_sha", publish)
        self.assertIn(".head.sha == $sha", publish)
        self.assertIn("--source-head-sha", publish)
        self.assertIn("--tested-sha", publish)
        self.assertIn("without gating on findings", publish)
        self.assertNotIn("OPENAI_API_KEY", publish)


class VisualReviewHandoffTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.shared = tempfile.TemporaryDirectory(prefix="blockpops-review-workflow-")
        root = Path(cls.shared.name)
        nodes = sorted(row["artifact_node"] for row in load_matrix(MATRIX_PATH)["runtimes"])
        candidate_input = _source(
            root,
            name="candidate",
            nodes=nodes,
            artifact_id=71,
            source_head_branch="feature/visual-candidate",
            base_branch="master",
            event="pull_request",
            metadata="candidate",
        )
        reference_input = _source(
            root,
            name="reference",
            nodes=["fabric-1.20.1"],
            artifact_id=72,
            source_head_branch="master",
            base_branch="master",
            event="push",
            metadata="reference",
        )
        candidate = load_archived_evidence(
            archive=candidate_input[0],
            attestation_path=candidate_input[1],
            expectation=candidate_input[2],
            matrix_path=MATRIX_PATH,
            contract_path=CONTRACT_PATH,
            extraction_destination=root / "candidate-extracted",
        )
        reference = load_archived_evidence(
            archive=reference_input[0],
            attestation_path=reference_input[1],
            expectation=reference_input[2],
            matrix_path=MATRIX_PATH,
            contract_path=CONTRACT_PATH,
            extraction_destination=root / "reference-extracted",
        )
        capsule = root / "capsule"
        write_capsule(capsule, candidate, reference)
        cls.handoff = root / "handoff"
        cls.identity = build_handoff(cls.handoff, capsule=capsule, client=CLIENT)

    @classmethod
    def tearDownClass(cls) -> None:
        cls.shared.cleanup()

    def _copy(self, destination: Path) -> Path:
        root = destination / "handoff"
        shutil.copytree(self.handoff, root)
        return root

    def test_exact_handoff_validates_and_binds_every_capsule_pair(self) -> None:
        manifest, pairs = validate_handoff(
            self.handoff, self.identity["manifest_sha256"]
        )
        capsule_manifest, protected_pairs = validate_capsule(self.handoff / "capsule")
        self.assertEqual(len(protected_pairs), len(pairs))
        self.assertEqual(
            capsule_manifest["candidate_source"]["tested_commit"], "9" * 40
        )
        self.assertEqual(
            capsule_manifest["candidate_source"]["source_head_commit"], "a" * 40
        )
        self.assertEqual("advisory-semantic-ui-review", manifest["purpose"])
        handoff_manifest = json.loads(
            (self.handoff / "handoff.json").read_text(encoding="utf-8")
        )
        self.assertEqual(
            "openai-advisory-semantic-ui-review", handoff_manifest["purpose"]
        )
        self.assertLessEqual(handoff_manifest["total_bytes"], 500 * 1024 * 1024)

    def test_request_is_pair_local_tool_free_strict_and_bounded(self) -> None:
        _manifest, pairs = validate_handoff(
            self.handoff, self.identity["manifest_sha256"]
        )
        payload = build_request(self.handoff, pairs[0], model="visual-model-under-test")
        request = json.loads(payload)
        self.assertEqual("visual-model-under-test", request["model"])
        self.assertIs(request["store"], False)
        self.assertEqual([], request["tools"])
        self.assertIs(request["parallel_tool_calls"], False)
        output_format = request["text"]["format"]
        self.assertEqual("json_schema", output_format["type"])
        self.assertIs(output_format["strict"], True)
        content = request["input"][0]["content"]
        images = [item for item in content if item["type"] == "input_image"]
        self.assertEqual(2, len(images))
        self.assertTrue(all(item["image_url"].startswith("data:image/png;base64,") for item in images))
        decoded = payload.decode("utf-8")
        self.assertIn(pairs[0]["expectation"], decoded)
        self.assertNotIn(REPOSITORY, decoded)
        self.assertNotIn("OPENAI_API_KEY", decoded)
        with self.assertRaisesRegex(ReviewClientError, "MODEL"):
            build_request(self.handoff, pairs[0], model="")

    def test_one_response_request_per_pair_produces_complete_advisory_output(self) -> None:
        _manifest, pairs = validate_handoff(
            self.handoff, self.identity["manifest_sha256"]
        )
        calls: list[dict[str, object]] = []

        def transport(payload: bytes, api_key: str) -> bytes:
            self.assertEqual("test-key-never-logged", api_key)
            request = json.loads(payload)
            verdict_properties = request["text"]["format"]["schema"]["properties"]["verdict"]["properties"]
            pair = {
                "label": verdict_properties["label"]["enum"][0],
                "capture_id": verdict_properties["capture_id"]["enum"][0],
            }
            calls.append(pair)
            return _response(pair, defect=len(calls) == 1)

        with tempfile.TemporaryDirectory(prefix="blockpops-review-result-") as temporary:
            output = Path(temporary) / "raw.json"
            report = run_review(
                self.handoff,
                expected_manifest_sha256=self.identity["manifest_sha256"],
                model="visual-model-under-test",
                api_key="test-key-never-logged",
                output=output,
                transport=transport,
            )
            self.assertEqual(len(pairs), len(calls))
            self.assertEqual(len(pairs), len(report["verdicts"]))
            self.assertEqual(1, sum(item["semantic_regression"] for item in report["verdicts"]))
            normalized = read_and_validate_review(self.handoff / "capsule", output)
            self.assertEqual(report, normalized)
            publication = normalize(
                handoff=self.handoff,
                expected_handoff_sha256=self.identity["manifest_sha256"],
                raw_output=output,
                normalized_output=Path(temporary) / "normalized.json",
                markdown_output=Path(temporary) / "advisory.md",
                repository=REPOSITORY,
                source_run_id=171,
                source_head_sha="a" * 40,
                tested_sha="9" * 40,
                reference_sha="b" * 40,
            )
            self.assertEqual(1, publication["semantic_regressions"])
            with self.assertRaisesRegex(NormalizeError, "different source or baseline"):
                normalize(
                    handoff=self.handoff,
                    expected_handoff_sha256=self.identity["manifest_sha256"],
                    raw_output=output,
                    normalized_output=Path(temporary) / "wrong-normalized.json",
                    markdown_output=Path(temporary) / "wrong-advisory.md",
                    repository=REPOSITORY,
                    source_run_id=171,
                    source_head_sha="a" * 40,
                    tested_sha="0" * 40,
                    reference_sha="b" * 40,
                )

    def test_malformed_or_refused_model_output_fails_closed(self) -> None:
        _manifest, pairs = validate_handoff(
            self.handoff, self.identity["manifest_sha256"]
        )
        pair = pairs[0]
        wrong = json.loads(_response(pair))
        result = json.loads(wrong["output"][0]["content"][0]["text"])
        result["verdict"]["label"] = "mixed/source"
        wrong["output"][0]["content"][0]["text"] = json.dumps(result)
        with self.assertRaisesRegex(ReviewClientError, "identity"):
            extract_response(json.dumps(wrong).encode(), pair)
        refused = {
            "status": "completed",
            "error": None,
            "incomplete_details": None,
            "output": [
                {
                    "type": "message",
                    "status": "completed",
                    "content": [{"type": "refusal", "refusal": "cannot comply"}],
                }
            ],
        }
        with self.assertRaisesRegex(ReviewClientError, "refused"):
            extract_response(json.dumps(refused).encode(), pair)
        unexpected_tool = json.loads(_response(pair))
        unexpected_tool["output"].insert(
            0, {"type": "function_call", "name": "read_repository"}
        )
        with self.assertRaisesRegex(ReviewClientError, "unexpected output item"):
            extract_response(json.dumps(unexpected_tool).encode(), pair)

    def test_handoff_rejects_traversal_symlink_mutation_extra_and_size_overflow(self) -> None:
        for mutation in ("traversal", "symlink", "mutation", "extra", "size"):
            with self.subTest(mutation=mutation), tempfile.TemporaryDirectory() as temporary:
                handoff = self._copy(Path(temporary))
                digest = self.identity["manifest_sha256"]
                if mutation == "traversal":
                    manifest_path = handoff / "handoff.json"
                    manifest_path.chmod(0o644)
                    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
                    manifest["inventory"][0]["path"] = "../escape"
                    payload = json.dumps(
                        manifest,
                        ensure_ascii=False,
                        sort_keys=True,
                        separators=(",", ":"),
                    ).encode() + b"\n"
                    manifest_path.write_bytes(payload)
                    digest = hashlib.sha256(payload).hexdigest()
                elif mutation == "symlink":
                    client = handoff / "review_client.py"
                    client.unlink()
                    client.symlink_to(handoff / "handoff.json")
                elif mutation == "mutation":
                    client = handoff / "review_client.py"
                    client.chmod(0o644)
                    client.write_bytes(client.read_bytes() + b"\n")
                elif mutation == "extra":
                    (handoff / "capsule" / "unexpected.txt").write_text("x")
                else:
                    with mock.patch("scripts.visual.review_client.MAX_IMAGE_BYTES", 1):
                        with self.assertRaises(ReviewClientError):
                            validate_handoff(handoff, digest)
                    continue
                with self.assertRaises(ReviewClientError):
                    validate_handoff(handoff, digest)

    def test_encoded_request_limit_is_enforced_before_transport(self) -> None:
        _manifest, pairs = validate_handoff(
            self.handoff, self.identity["manifest_sha256"]
        )
        with mock.patch("scripts.visual.review_client.MAX_REQUEST_BYTES", 1):
            with self.assertRaisesRegex(ReviewClientError, "encoded Responses request"):
                build_request(self.handoff, pairs[0], model="visual-model-under-test")


class VisualSourceAuthenticationTests(unittest.TestCase):
    def test_source_run_authenticates_exact_repo_path_event_status_and_attempt(self) -> None:
        run = _run_record(21, event="pull_request", head_sha=SOURCE_SHA, head_branch="feature/ui")
        identity = authenticate_run(run, repository=REPOSITORY, expected_id=21)
        self.assertEqual(SOURCE_SHA, identity.head_sha)
        with self.assertRaises(CurationError):
            authenticate_run([], repository=REPOSITORY, expected_id=21)
        for field, value in (
            ("path", ".github/workflows/untrusted.yml"),
            ("status", "in_progress"),
            ("conclusion", "failure"),
            ("event", "push"),
        ):
            with self.subTest(field=field):
                broken = copy.deepcopy(run)
                broken[field] = value
                with self.assertRaises(CurationError):
                    authenticate_run(broken, repository=REPOSITORY, expected_id=21)

    def test_exact_artifact_name_binds_tested_commit_and_attempt(self) -> None:
        current = _artifact(22, commit=MERGE_SHA, attempt=2)
        selected = exact_aggregate_artifact(
            [current], run_id=22, tested_commit=MERGE_SHA, run_attempt=2
        )
        self.assertIsInstance(selected, ArtifactIdentity)
        self.assertEqual(aggregate_artifact_name(MERGE_SHA, 2), selected.name)
        self.assertIsNone(
            exact_aggregate_artifact(
                [current], run_id=22, tested_commit=MERGE_SHA, run_attempt=3
            )
        )
        stale = copy.deepcopy(current)
        stale["expired"] = True
        with self.assertRaises(CurationError):
            exact_aggregate_artifact(
                [stale], run_id=22, tested_commit=MERGE_SHA, run_attempt=2
            )

    def test_reference_inventory_keeps_newest_failure_or_pending_visible(self) -> None:
        older = _run_record(30, created_at="2026-08-10T10:00:00Z")
        newest = _run_record(
            31,
            status="in_progress",
            conclusion=None,
            created_at="2026-08-10T11:00:00Z",
        )
        candidates = reference_candidates(
            [older, newest], repository=REPOSITORY, branch="master", head_sha=MASTER_SHA
        )
        self.assertEqual([31, 30], [item.identity.run_id for item in candidates])
        self.assertEqual("in_progress", candidates[0].status)

    def test_newest_full_reference_failure_pending_or_missing_artifact_blocks_fallback(self) -> None:
        older = _run_record(40, created_at="2026-08-10T10:00:00Z")
        older_artifact = _artifact(40)
        for status, conclusion in (
            ("completed", "failure"),
            ("in_progress", None),
            ("completed", "success"),
        ):
            with self.subTest(status=status, conclusion=conclusion):
                newest = _run_record(
                    41,
                    status=status,
                    conclusion=conclusion,
                    created_at="2026-08-10T11:00:00Z",
                )
                api = _SelectionApi(
                    artifacts={40: [older_artifact], 41: []},
                    jobs={
                        40: [_job(40, "Resolve authoritative packaged matrix")],
                        41: [_job(41, "Resolve authoritative packaged matrix")],
                    },
                )
                with self.assertRaisesRegex(CurationError, "newest exact current-head full"):
                    select_reference_run(
                        api,
                        [older, newest],
                        repository=REPOSITORY,
                        branch="master",
                        head_sha=MASTER_SHA,
                    )

    def test_only_authenticated_attestation_mode_may_be_skipped(self) -> None:
        older = _run_record(50, created_at="2026-08-10T10:00:00Z")
        attestation = _run_record(51, created_at="2026-08-10T11:00:00Z")
        api = _SelectionApi(
            artifacts={50: [_artifact(50)], 51: []},
            jobs={
                50: [_job(50, "Resolve authoritative packaged matrix")],
                51: [_job(51, "Attest exact tested packaged tree / Verify exact tested tree")],
            },
        )
        run, artifact = select_reference_run(
            api,
            [older, attestation],
            repository=REPOSITORY,
            branch="master",
            head_sha=MASTER_SHA,
        )
        self.assertEqual(50, run.run_id)
        self.assertEqual(1050, artifact.artifact_id)

    def test_pull_request_binds_current_source_base_and_tested_merge_parents(self) -> None:
        run = RunIdentity(
            repository=REPOSITORY,
            head_repository="contributor/BlockPops",
            head_branch="feature/current-ui",
            head_sha=SOURCE_SHA,
            run_id=60,
            run_attempt=2,
            event="pull_request",
            created_at=datetime(2026, 8, 10, tzinfo=timezone.utc),
        )
        record = {"pull_requests": [{"number": 17}]}
        api = _PullApi()
        tested = _resolve_tested_identity(api, record, run)
        self.assertEqual(MERGE_SHA, tested.tested_commit)
        self.assertEqual(TREE_SHA, tested.tested_tree)
        self.assertEqual("master", tested.base_branch_hint)
        for mutation in ("head", "base", "parents", "merge"):
            with self.subTest(mutation=mutation):
                broken = _PullApi()
                if mutation == "head":
                    broken.pull["head"]["sha"] = "1" * 40
                elif mutation == "base":
                    broken.branch_heads[(REPOSITORY, "master")] = "2" * 40
                elif mutation == "parents":
                    broken.parents = (SOURCE_SHA, BASE_SHA)
                else:
                    broken.pull["merge_commit_sha"] = SOURCE_SHA
                with self.assertRaises(CurationError):
                    _resolve_tested_identity(broken, record, run)

    def test_non_pr_baseline_requires_the_exact_current_branch_head(self) -> None:
        run = RunIdentity(
            repository=REPOSITORY,
            head_repository=REPOSITORY,
            head_branch="master",
            head_sha=MASTER_SHA,
            run_id=70,
            run_attempt=1,
            event="schedule",
            created_at=datetime(2026, 8, 10, tzinfo=timezone.utc),
        )

        class Api:
            current = MASTER_SHA

            def branch_head(self, repository: str, branch: str) -> str:
                self.assertions = (repository, branch)
                return self.current

            def commit_identity(self, repository: str, commit: str) -> tuple[str, tuple[str, ...]]:
                return TREE_SHA, ("6" * 40,)

        api = Api()
        tested = _resolve_tested_identity(api, None, run)
        self.assertEqual(MASTER_SHA, tested.tested_commit)
        api.current = "5" * 40
        with self.assertRaisesRegex(CurationError, "exact current head"):
            _resolve_tested_identity(api, None, run)


if __name__ == "__main__":
    unittest.main()

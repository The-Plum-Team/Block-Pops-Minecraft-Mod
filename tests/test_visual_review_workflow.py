from __future__ import annotations

import copy
import hashlib
import io
import json
import math
import re
import shutil
import subprocess
import tempfile
import unittest
import urllib.request
from datetime import datetime, timezone
from pathlib import Path
from unittest import mock

from e2e.visual_capsule import MAX_CAPSULE_PAIRS, validate_capsule, write_capsule
from e2e.visual_evidence import load_archived_evidence
from e2e.visual_review_output import (
    MAX_PAIRS as MAX_OUTPUT_PAIRS,
    VisualReviewOutputError,
    advisory_markdown,
    read_and_validate_review,
)
from scripts.ci.e2e_fanin import aggregate_artifact_name
from scripts.release.matrix import load_matrix
from scripts.visual.curate import (
    ArtifactIdentity,
    CurationError,
    GitHubApi as CuratorGitHubApi,
    RunIdentity,
    _SafeRedirect,
    _candidate_reference_binding,
    _resolve_tested_identity,
    aggregate_tested_commit,
    authenticate_run,
    exact_aggregate_artifact,
    reference_candidates,
    select_reference_run,
)
from scripts.pages.visual_anchor import visual_anchor_artifact_name
from scripts.visual import review_client
from scripts.visual.handoff import (
    MAX_PAIRS as MAX_HANDOFF_PAIRS,
    build_handoff,
    build_queue,
)
from scripts.visual.normalize import (
    NormalizeError,
    normalize,
    validate_review_provenance,
)
from scripts.visual.review_client import (
    SONNET_MODEL,
    VERIFY_MODEL,
    ReviewClientError,
    ReviewFailure,
    build_prompt,
    extract_cli_result,
    failure_marker,
    ordered_changed_pairs,
    run_review,
    validate_handoff,
    validate_review_cost_envelope,
)
from scripts.ci.tests.matrix_fixtures import (
    canonical_integration_matrix,
    write_matrix_fixture,
)
from tests.test_visual_capsule import (
    ACTIVE_BRANCH,
    BRANCH_MATRIX,
    CANONICAL_BRANCH,
    CONTRACT_PATH,
    MATRIX_PATH,
    REFERENCE_NODE,
    _source,
)


REPO = Path(__file__).resolve().parents[1]
WORKFLOW = REPO / ".github" / "workflows" / "visual-review.yml"
DRAIN_WORKFLOW = REPO / ".github" / "workflows" / "visual-review-drain.yml"
BUILD_GATE_WORKFLOW = REPO / ".github" / "workflows" / "build-gate.yml"
PACKAGED_E2E_WORKFLOW = REPO / ".github" / "workflows" / "on-demand-e2e.yml"
CLIENT = REPO / "scripts" / "visual" / "review_client.py"
PREFLIGHT = REPO / "scripts" / "visual" / "credential_preflight.py"
QUEUE_SELECTOR = REPO / "scripts" / "ci" / "visual_review_queue.py"
SONNET_PROMPT = REPO / "scripts" / "visual" / "prompts" / "sonnet.md"
FABLE_PROMPT = REPO / "scripts" / "visual" / "prompts" / "fable.md"
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
    display_title: str | None = None,
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
        "display_title": display_title or f"Packaged E2E / {head_sha}",
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


def _anchor_artifact(
    run_id: int, *, commit: str = MASTER_SHA, attempt: int = 1
) -> dict[str, object]:
    artifact = _artifact(run_id, commit=commit)
    artifact["name"] = visual_anchor_artifact_name(
        "master", commit, run_id, attempt
    )
    artifact["workflow_run"] = {"id": run_id, "head_sha": commit}
    return artifact


def _job(run_id: int, name: str, *, attempt: int = 1) -> dict[str, object]:
    return {"id": 2000 + run_id, "name": name, "run_attempt": attempt}


def _structured(
    pairs: list[dict[str, object]],
    *,
    model: str,
    classification: str = "clean",
    defect: bool = False,
) -> dict[str, object]:
    verdicts: list[dict[str, object]] = []
    for pair in pairs:
        findings = (
            [
                {
                    "category": "clipping",
                    "severity": "defect",
                    "detail": "A label is clipped.",
                }
            ]
            if defect
            else []
        )
        if model == SONNET_MODEL:
            verdicts.append(
                {
                    "label": pair["label"],
                    "capture_id": pair["capture_id"],
                    "classification": classification,
                    "visible": "The expected packaged UI state is visible.",
                    "findings": findings,
                }
            )
        else:
            verdicts.append(
                {
                    "label": pair["label"],
                    "capture_id": pair["capture_id"],
                    "matches_expectation": not defect,
                    "semantic_regression": defect,
                    "visible": "The expected packaged UI state is visible.",
                    "findings": findings,
                }
            )
    return {"schema_version": 1, "advisory": True, "verdicts": verdicts}


def _envelope(
    structured: dict[str, object] | None,
    *,
    session: int = 1,
    cost: float = 0.0125,
    **overrides: object,
) -> dict[str, object]:
    """Shape one `claude --print --output-format json` result as the pinned CLI emits it."""

    envelope: dict[str, object] = {
        "type": "result",
        "subtype": "success",
        "is_error": False,
        "duration_ms": 1000,
        "num_turns": 3,
        "result": "",
        "session_id": f"00000000-0000-4000-8000-{session:012x}",
        "total_cost_usd": cost,
        "usage": {
            "input_tokens": 100,
            "output_tokens": 20,
            "cache_creation_input_tokens": 0,
            "cache_read_input_tokens": 0,
            "server_tool_use": {"web_search_requests": 0, "web_fetch_requests": 0},
            "service_tier": "standard",
        },
        "modelUsage": {},
        "permission_denials": [],
    }
    if structured is not None:
        envelope["structured_output"] = structured
    envelope.update(overrides)
    return envelope


def _failure(result: str, *, status: int | None = None) -> dict[str, object]:
    return _envelope(
        None, is_error=True, result=result, api_error_status=status, total_cost_usd=0
    )


class _FakeCli:
    """Record Claude Code invocations and answer each like the pinned CLI would."""

    def __init__(self, respond) -> None:
        self.respond = respond
        self.calls: list[dict[str, object]] = []

    def __call__(self, command, *, cwd, env, input, stdout, stderr, timeout, check):
        model = command[command.index("--model") + 1]
        schema = json.loads(command[command.index("--json-schema") + 1])
        properties = schema["properties"]["verdicts"]["items"]["properties"]
        pairs = [
            {"label": label, "capture_id": capture}
            for label, capture in zip(
                properties["label"]["enum"], properties["capture_id"]["enum"]
            )
        ]
        self.calls.append(
            {
                "model": model,
                "pairs": pairs,
                "command": list(command),
                "cwd": Path(cwd),
                "env": dict(env),
                "prompt": input.decode("utf-8"),
                "timeout": timeout,
            }
        )
        outcome = self.respond(model, pairs, len(self.calls))
        if isinstance(outcome, BaseException):
            raise outcome
        returncode, payload = outcome if isinstance(outcome, tuple) else (0, outcome)
        if not isinstance(payload, bytes):
            payload = json.dumps(payload).encode("utf-8")
        return subprocess.CompletedProcess(command, returncode, stdout=payload, stderr=None)


TOKEN = "sk-ant-oat01-" + "x" * 32
CLAUDE = Path("/opt/claude-cli/package/claude")


class _Clock:
    def __init__(self, now: float = 1_800_000_000.0) -> None:
        self.now = now
        self.sleeps: list[float] = []

    def monotonic(self) -> float:
        return self.now

    def wall(self) -> float:
        return self.now

    def sleep(self, seconds: float) -> None:
        self.sleeps.append(seconds)
        self.now += seconds


class _SelectionApi:
    def __init__(self, artifacts: dict[int, list[dict[str, object]]], jobs: dict[int, list[dict[str, object]]]):
        self._artifacts = artifacts
        self._jobs = jobs

    def artifacts(self, run_id: int) -> list[dict[str, object]]:
        return self._artifacts.get(run_id, [])

    def jobs(self, run_id: int) -> list[dict[str, object]]:
        return self._jobs.get(run_id, [])


class _PullApi:
    repository = REPOSITORY

    def __init__(self) -> None:
        self.pull = {
            "state": "open",
            "head": {
                "sha": SOURCE_SHA,
                "ref": "feature/current-ui",
                "repo": {"full_name": REPOSITORY},
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
    def test_curator_disables_proxies_and_only_redirects_over_https(self) -> None:
        with mock.patch(
            "scripts.visual.curate.urllib.request.build_opener",
            wraps=urllib.request.build_opener,
        ) as build_opener:
            CuratorGitHubApi(
                repository=REPOSITORY,
                token="test-token",
                api_url="https://api.github.com",
            )
        handlers = build_opener.call_args.args
        self.assertTrue(
            any(
                isinstance(handler, urllib.request.ProxyHandler)
                and handler.proxies == {}
                for handler in handlers
            )
        )

        request = urllib.request.Request(
            "https://api.github.com/repos/AkaNebur/BlockPops/actions/artifacts/1/zip",
            headers={"Authorization": "Bearer test-token"},
        )
        handler = _SafeRedirect()
        cross_origin = handler.redirect_request(
            request,
            None,
            302,
            "Found",
            {},
            "https://objects.example.invalid/artifact.zip?signature=test",
        )
        self.assertIsNotNone(cross_origin)
        assert cross_origin is not None
        self.assertIsNone(cross_origin.get_header("Authorization"))
        same_origin = handler.redirect_request(
            request,
            None,
            302,
            "Found",
            {},
            "https://api.github.com/repos/AkaNebur/BlockPops/actions/artifacts/1",
        )
        self.assertIsNotNone(same_origin)
        assert same_origin is not None
        self.assertEqual("Bearer test-token", same_origin.get_header("Authorization"))
        self.assertIsNone(
            handler.redirect_request(
                request,
                None,
                302,
                "Found",
                {},
                "http://api.github.com/insecure",
            )
        )

    def test_workflow_has_authenticated_advisory_triggers_and_pinned_actions(self) -> None:
        queue = WORKFLOW.read_text(encoding="utf-8")
        drain = DRAIN_WORKFLOW.read_text(encoding="utf-8")
        self.assertIn("workflow_run:", queue)
        self.assertIn("- Packaged E2E", queue)
        self.assertIn("- visual-review-requested", queue)
        self.assertIn(".github/workflows/on-demand-e2e.yml", queue)
        self.assertIn('.event == "pull_request_target"', queue)
        self.assertNotIn('.event == "pull_request"', queue)
        self.assertIn('--source-branch "$SOURCE_BRANCH"', queue)
        self.assertIn("source_run_attempt", queue)
        self.assertIn(
            "DISPATCH_RUN_ATTEMPT: ${{ github.event.client_payload.source_run_attempt }}",
            queue,
        )
        self.assertIn(
            "TRIGGER_RUN_ATTEMPT: ${{ github.event.workflow_run.run_attempt }}",
            queue,
        )
        self.assertIn('[[ "$source_run_attempt" == "$locator_run_attempt" ]]', queue)
        self.assertIn(
            'aggregate_name="packaged-e2e-$tested_sha-$source_run_attempt-aggregate"',
            queue,
        )
        self.assertIn(
            'queue_name="visual-review-input-$source_run_id-$source_run_attempt-$tested_sha-$GITHUB_RUN_ATTEMPT"',
            queue,
        )
        association = queue.index('commits/$tested_sha/pulls?per_page=100')
        sync_open = queue.index('if [[ "$pull_state" == open ]]', association)
        deleted_branch_lookup = queue.index(
            'branches/$encoded_source" --jq .commit.sha', association
        )
        self.assertGreater(deleted_branch_lookup, sync_open)
        self.assertIn(
            '"$GITHUB_EVENT_NAME" == workflow_run',
            queue[queue.index('if [[ "${#aggregate_names[@]}" == 0'):association],
        )
        self.assertIn('--producer-run-id "$GITHUB_RUN_ID"', queue)
        self.assertIn('--producer-run-attempt "$GITHUB_RUN_ATTEMPT"', queue)
        self.assertIn('--source-run-attempt "$SOURCE_RUN_ATTEMPT"', queue)
        curator = (REPO / "scripts" / "visual" / "curate.py").read_text(encoding="utf-8")
        self.assertIn(
            "api.run_attempt(source_run_id, source_run_attempt)", curator
        )
        self.assertNotIn("source_run_record = api.run(source_run_id)", curator)
        reauthenticator = (REPO / "scripts" / "visual" / "reauth.py").read_text(
            encoding="utf-8"
        )
        self.assertIn(
            'queue["source_run_id"], queue["source_run_attempt"]',
            reauthenticator,
        )
        self.assertIn("_current_source_run(api, source_run)", reauthenticator)
        self.assertIn("_source_controller_ancestry(", reauthenticator)
        selector = (REPO / "scripts" / "ci" / "visual_review_queue.py").read_text(
            encoding="utf-8"
        )
        self.assertIn("current_source_run = api.get_run(identity.source_run_id)", selector)
        self.assertIn("urllib.request.ProxyHandler({})", selector)
        self.assertIn("_NoRedirect()", selector)
        self.assertIn("urllib.request.ProxyHandler({})", curator)
        self.assertIn('redirected_url.scheme != "https"', curator)
        preflight = PREFLIGHT.read_text(encoding="utf-8")
        self.assertIn('/actions/runs/{run_id}/attempts/{attempt}', preflight)
        self.assertIn('/actions/runs/{run_id}', preflight)
        self.assertIn("source workflow was re-run or changed after curation", preflight)
        self.assertIn("api.compare(controller_head, queue_implementation)", preflight)
        self.assertIn("api.compare(queue_implementation, implementation)", preflight)
        packaged = PACKAGED_E2E_WORKFLOW.read_text(encoding="utf-8")
        self.assertIn('--run-id "$GITHUB_RUN_ID"', packaged)
        self.assertIn('--run-attempt "$GITHUB_RUN_ATTEMPT"', packaged)
        self.assertIn('--source-run-id "$GITHUB_RUN_ID"', packaged)
        self.assertIn('--source-run-attempt "$GITHUB_RUN_ATTEMPT"', packaged)
        self.assertIn("- visual-review-queue-wake", drain)
        self.assertIn('cron: "17,47 * * * *"', drain)
        self.assertIn("group: blockpops-visual-review-global-drain", drain)
        self.assertIn("cancel-in-progress: false", drain)
        continuation = _job_block(drain, "continue")
        self.assertIn("needs: [select, exhausted, attempt, cleanup]", continuation)
        self.assertIn("needs.cleanup.result == 'success'", continuation)
        self.assertIn("needs.exhausted.result == 'success'", continuation)
        self.assertIn("needs.attempt.result == 'success'", continuation)
        self.assertIn(
            "needs.select.outputs.queue_state == 'stale_source'", continuation
        )
        uses = re.findall(r"(?m)^\s*uses:\s*([^\s#]+)", queue + drain)
        self.assertTrue(uses)
        for action in uses:
            self.assertRegex(action, r"^[^@\s]+@[0-9a-f]{40}$")

    def test_credential_job_has_only_the_pinned_cli_the_token_and_the_exact_handoff(self) -> None:
        drain = DRAIN_WORKFLOW.read_text(encoding="utf-8")
        admit = _job_block(drain, "admit")
        review = _job_block(drain, "review")
        self.assertIn("actions: read", review)
        self.assertIn("contents: read", review)
        self.assertIn("pull-requests: read", review)
        self.assertNotIn("id-token: write", drain)
        self.assertIn("environment: visual-review", review)
        for forbidden in (
            "actions/checkout",
            "actions/setup-python",
            "actions/setup-node",
            "pip install",
            "npm ",
            "scripts/visual/reauth.py",
        ):
            self.assertNotIn(forbidden, review)
        self.assertIn("artifact-ids: ${{ needs.admit.outputs.artifact_id }}", review)
        self.assertIn("digest-mismatch: error", review)
        self.assertIn("environment: visual-review", admit)
        self.assertIn("python3 scripts/visual/reauth.py", admit)
        self.assertIn("artifact-ids: ${{ needs.prepare.outputs.artifact_id }}", admit)
        self.assertIn("name: Post-approval reauthenticate on a non-credential runner", admit)
        self.assertNotIn("secrets.", admit)
        self.assertIn('GH_TOKEN: ""', review)
        self.assertIn('GITHUB_TOKEN: ""', review)
        for static_credential in (
            "ANTHROPIC_API_KEY",
            "ANTHROPIC_AUTH_TOKEN",
            "ANTHROPIC_OAUTH_ACCESS_TOKEN",
            "OPENAI_API_KEY",
            "vars.",
        ):
            self.assertNotIn(static_credential, review)

        install = review.split("- name: Install the hash-pinned Claude Code binary", 1)[1].split(
            "- name: Invoke only the stdlib client with the pinned Claude Code CLI", 1
        )[0]
        self.assertIn(
            "https://registry.npmjs.org/@anthropic-ai/claude-code-linux-x64/-/"
            "claude-code-linux-x64-2.1.220.tgz",
            install,
        )
        self.assertIn(
            "CLAUDE_CODE_INTEGRITY: sha512-3CGFCnI0gpgsqNeJruFALBDGJaKXOuok3alQEg56ty2yOPpIrOx/"
            "r2Y0+T4uhJl7kP5Hzw4IFkxo4DZKWvzQ7Q==",
            install,
        )
        self.assertLess(install.index("openssl dgst -sha512"), install.index("tar -xzf"))
        self.assertIn("--proto '=https'", install)
        self.assertIn('== "2.1.220 (Claude Code)" ]]', install)
        self.assertNotIn("CLAUDE_CODE_OAUTH_TOKEN", install)

        model_step = review.split(
            "- name: Invoke only the stdlib client with the pinned Claude Code CLI", 1
        )[1].split("- name: Upload only the bounded normalized result", 1)[0]
        self.assertEqual(
            1, model_step.count("CLAUDE_CODE_OAUTH_TOKEN: ${{ secrets.CLAUDE_CODE_OAUTH_TOKEN }}")
        )
        self.assertEqual(1, review.count("secrets."))
        self.assertEqual(1, model_step.count("${{ github.token }}"))
        self.assertIn('--claude "$RUNNER_TEMP/claude-cli/package/claude"', model_step)
        self.assertIn("-u GITHUB_REPOSITORY", model_step)
        self.assertNotRegex(
            model_step,
            r"(?m)^\s*GITHUB_TOKEN:\s*\$\{\{ github\.token \}\}\s*$",
        )
        # The preflight is the last check before the token reaches the client and never
        # sees the token itself.
        preflight = model_step.index("credential_preflight.py")
        self.assertIn("env -u CLAUDE_CODE_OAUTH_TOKEN \\", model_step[:preflight])
        self.assertLess(
            model_step.index("unset PREFLIGHT_GITHUB_TOKEN GH_TOKEN GITHUB_TOKEN"),
            model_step.index("            invoke_client\n"),
        )
        identical = model_step.split('elif [[ "$all_identical" == true ]]; then', 1)[1].split(
            "          elif", 1
        )[0]
        self.assertIn("(unset CLAUDE_CODE_OAUTH_TOKEN; invoke_client)", identical)
        missing = model_step.split('elif [[ -z "$CLAUDE_CODE_OAUTH_TOKEN" ]]; then', 1)[1].split(
            "          else", 1
        )[0]
        self.assertIn('category:"invalid_configuration",stage:"authentication"', missing)
        self.assertNotIn("invoke_client", missing)
        self.assertIn('[[ "${#result_inventory[@]}" == 1 ]]', review)
        self.assertIn('[[ -f "$result_path" && ! -L "$result_path" ]]', review)
        self.assertIn('"$result_size" -le 1048576', review)
        self.assertIn("[[ ! -e \"$GITHUB_WORKSPACE/.git\" ]]", review)
        self.assertIn("preflight_sha256", review)

        client = CLIENT.read_text(encoding="utf-8")
        self.assertIn('SONNET_MODEL = "claude-sonnet-5"', client)
        self.assertIn('VERIFY_MODEL = "claude-opus-5"', client)
        self.assertIn('"schema_version": 2', client)
        self.assertNotIn("api.anthropic.com", client)
        self.assertNotIn("api.openai.com", client)
        self.assertNotIn("urllib", client)

    def test_the_queue_item_is_downloaded_from_the_run_that_enqueued_it(self) -> None:
        drain = DRAIN_WORKFLOW.read_text(encoding="utf-8")
        step = drain.split("      - name: Download the exact selected data-only queue artifact\n", 1)[1]
        step = step.split("      - name:", 1)[0]
        self.assertIn("artifact-ids: ${{ needs.select.outputs.artifact_id }}", step)
        self.assertIn("run-id: ${{ needs.select.outputs.producer_run_id }}", step)

    def test_queue_retry_retention_and_temporary_git_auth_are_bounded(self) -> None:
        queue = WORKFLOW.read_text(encoding="utf-8")
        drain = DRAIN_WORKFLOW.read_text(encoding="utf-8")
        packaged = PACKAGED_E2E_WORKFLOW.read_text(encoding="utf-8")
        selector = QUEUE_SELECTOR.read_text(encoding="utf-8")
        classify = _job_block(queue, "classify")
        attempt = _job_block(drain, "attempt")
        review = _job_block(drain, "review")
        self.assertIn('git_auth_config="$RUNNER_TEMP/visual-impact-git-auth.config"', classify)
        self.assertIn("(umask 077 && : > \"$git_auth_config\")", classify)
        self.assertIn("trap cleanup_git_auth EXIT INT TERM", classify)
        self.assertIn(
            'GIT_CONFIG_GLOBAL="$git_auth_config" gh auth setup-git --hostname github.com',
            classify,
        )
        self.assertIn('GIT_CONFIG_GLOBAL="$git_auth_config" git fetch --no-tags origin', classify)
        self.assertIn("commits/$TESTED_SHA/pulls?per_page=100", classify)
        self.assertIn("+refs/pull/$pull_number/head:refs/remotes/origin/visual-impact-source", classify)
        self.assertNotIn("+refs/heads/$SOURCE_BRANCH", classify)
        self.assertNotIn("+refs/heads/$target_branch", classify)
        self.assertIn('target_sha="$(jq -er .parents[0].sha', classify)
        self.assertIn('[[ "$(jq -er .base.sha', classify)
        for tokenized_url in (
            "x-access-token",
            "oauth2:",
            "https://$GH_TOKEN@",
            "http.extraHeader",
        ):
            self.assertNotIn(tokenized_url, classify)

        self.assertIn("python3 scripts/ci/visual_review_queue.py", drain)
        self.assertIn('"stale_source"', selector)
        self.assertIn('[[ "$QUEUE_STATE" == stale_source ]]', drain)
        self.assertIn("needs.select.outputs.queue_state == 'eligible'", attempt)
        self.assertIn("needs.admit.outputs.status == 'authenticated'", review)
        self.assertIn('[[ "$COMPLETED_ATTEMPTS" == 2 ]]', drain)
        self.assertIn('[[ "$NEXT_ORDINAL" =~ ^[12]$ ]]', drain)
        self.assertIn("if [[ \"$cooldown_seconds\" -lt 1800 ]]; then", drain)
        self.assertIn("visual-review-cooldown-$SOURCE_RUN_ID", drain)
        self.assertIn("retain_queue:true", drain)
        self.assertIn("retention-days: 7", _job_block(queue, "curate"))
        self.assertIn("retention-days: 7", _job_block(drain, "attempt"))
        self.assertIn("retention-days: 1", _job_block(drain, "prepare"))
        self.assertIn("retention-days: 1", _job_block(drain, "review"))
        self.assertIn("retention-days: 7", _job_block(drain, "publish"))
        self.assertIn("retention-days: 30", _job_block(drain, "publish"))
        self.assertIn("retention-days: 90", packaged)

        self.assertIn(
            'Attest exact tested packaged tree / Verify exact tested tree', queue
        )
        self.assertIn("Authenticated exact-tree attestation delivery has no review aggregate", queue)

    def test_exact_cleanup_and_publication_keep_ai_out_of_deterministic_gates(self) -> None:
        drain = DRAIN_WORKFLOW.read_text(encoding="utf-8")
        cleanup = _job_block(drain, "cleanup")
        self.assertIn("always()", cleanup)
        self.assertIn("actions: write", cleanup)
        self.assertIn('status="$(request GET "$api/$id" "$metadata")"', cleanup)
        self.assertIn(".id == $id and .name == $name and .digest == $digest", cleanup)
        self.assertIn(".workflow_run.id == $run_id", cleanup)
        self.assertIn('status="$(request DELETE "$api/$id"', cleanup)
        self.assertIn('status="$(request DELETE "$api/$QUEUE_ID"', cleanup)
        self.assertIn(
            'actions/runs/$QUEUE_OWNER_RUN_ID/attempts/$QUEUE_OWNER_RUN_ATTEMPT',
            cleanup,
        )
        self.assertIn(
            "reviewed|stale_source|terminal_input_rejected) delete_queue_artifact",
            cleanup,
        )
        self.assertIn("transient_network|unknown) ;;", cleanup)
        self.assertNotIn("artifacts?name", cleanup)
        self.assertNotIn("--pattern", cleanup)

        publish = _job_block(drain, "publish")
        comment = _job_block(drain, "comment")
        self.assertIn("*) code=unknown ;;", publish)
        self.assertIn("retain_queue=true", publish)
        self.assertIn('[[ "$delivered_sha" == "$TESTED_SHA" ]]', comment)
        self.assertIn(".head.sha == $head", comment)
        self.assertIn('.event == "pull_request_target"', comment)
        self.assertNotIn('.event == "pull_request"', comment)
        self.assertIn('.head_branch == "master"', comment)
        self.assertIn("compare/$source_controller_sha...$IMPLEMENTATION_SHA", comment)
        self.assertIn(".merge_base_commit.sha == $base", comment)
        mutable_attempt = comment.index(
            'current_source_run="$(gh api "repos/$GITHUB_REPOSITORY/actions/runs/$SOURCE_RUN_ID")"'
        )
        issue_write = comment.index('gh api --method "$comment_method" "$comment_route"')
        self.assertLess(mutable_attempt, issue_write)
        self.assertIn(
            '[[ "$current_source_identity" == "$historical_source_identity" ]]',
            comment,
        )
        self.assertIn("--source-run-attempt", publish)
        self.assertIn("--source-head-sha", publish)
        self.assertIn("--tested-sha", publish)
        self.assertIn("continue-on-error: true", comment)
        self.assertNotIn("ANTHROPIC_", publish)

        self.assertIn('(.state == "open" or (.state == "closed" and .merged == true))', comment)
        self.assertIn('if [[ "$pull_state" == open ]]', comment)
        self.assertIn('branches/$encoded_base" --jq .commit.sha', comment)
        self.assertIn('.tree.sha == $tree and [.parents[].sha] == $parents', comment)
        self.assertIn('.parents[1].sha == $head', comment)

        for deterministic in (BUILD_GATE_WORKFLOW, PACKAGED_E2E_WORKFLOW):
            text = deterministic.read_text(encoding="utf-8").lower()
            self.assertNotIn("anthropic", text)
            self.assertNotIn("claude", text)
            self.assertNotIn("id-token: write", text)
            self.assertNotIn("environment: visual-review", text)


class VisualReviewHandoffTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.shared = tempfile.TemporaryDirectory(prefix="blockpops-review-workflow-")
        root = Path(cls.shared.name)
        cls.reference_matrix_path = write_matrix_fixture(
            root, canonical_integration_matrix(BRANCH_MATRIX)
        )
        nodes = sorted(row["artifact_node"] for row in load_matrix(MATRIX_PATH)["runtimes"])
        candidate_input = _source(
            root,
            name="candidate",
            nodes=nodes,
            artifact_id=71,
            source_head_branch="feature/visual-candidate",
            base_branch=ACTIVE_BRANCH,
            event="pull_request_target",
            metadata="candidate",
        )
        reference_input = _source(
            root,
            name="reference",
            nodes=[REFERENCE_NODE],
            artifact_id=72,
            source_head_branch=CANONICAL_BRANCH,
            base_branch=CANONICAL_BRANCH,
            event="push",
            metadata="reference",
            matrix_path=cls.reference_matrix_path,
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
            matrix_path=cls.reference_matrix_path,
            contract_path=CONTRACT_PATH,
            extraction_destination=root / "reference-extracted",
        )
        capsule = root / "capsule"
        write_capsule(capsule, candidate, reference)
        cls.queue = root / "queue"
        cls.queue_identity = build_queue(
            cls.queue,
            capsule=capsule,
            implementation_sha="c" * 40,
            producer_run_id=901,
            producer_run_attempt=2,
        )
        cls.handoff = root / "handoff"
        cls.identity = build_handoff(
            cls.handoff,
            queue=cls.queue,
            reviewer_implementation_sha="c" * 40,
            client=CLIENT,
            preflight=PREFLIGHT,
            sonnet_prompt=SONNET_PROMPT,
            fable_prompt=FABLE_PROMPT,
        )

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
        capsule_manifest, protected_pairs = validate_capsule(
            self.handoff / "queue" / "capsule"
        )
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
            "claude-advisory-visual-tool-handoff", handoff_manifest["purpose"]
        )
        self.assertEqual("c" * 40, handoff_manifest["reviewer_implementation_sha"])
        self.assertLessEqual(handoff_manifest["total_bytes"], 96 * 1024 * 1024)
        queue_manifest = json.loads(
            (self.handoff / "queue" / "queue.json").read_text(encoding="utf-8")
        )
        self.assertEqual("claude-advisory-visual-queue", queue_manifest["purpose"])
        self.assertEqual("c" * 40, queue_manifest["implementation_sha"])
        self.assertEqual(901, queue_manifest["producer_run_id"])
        self.assertEqual(2, queue_manifest["producer_run_attempt"])
        self.assertEqual(len(pairs), queue_manifest["pair_count"])
        self.assertEqual(
            hashlib.sha256(CLIENT.read_bytes()).hexdigest(),
            handoff_manifest["client_sha256"],
        )
        self.assertEqual(
            hashlib.sha256(PREFLIGHT.read_bytes()).hexdigest(),
            handoff_manifest["preflight_sha256"],
        )
        self.assertEqual(
            {
                "credential_preflight.py",
                "handoff.json",
                "prompts",
                "queue",
                "review_client.py",
            },
            {path.name for path in self.handoff.iterdir()},
        )

    def test_prompts_are_chunked_and_expose_only_the_chunk_images(self) -> None:
        self.assertEqual(10, MAX_CAPSULE_PAIRS)
        self.assertEqual(MAX_CAPSULE_PAIRS, MAX_HANDOFF_PAIRS)
        self.assertEqual(MAX_CAPSULE_PAIRS, review_client.MAX_PAIRS)
        self.assertEqual(MAX_CAPSULE_PAIRS, MAX_OUTPUT_PAIRS)
        _manifest, pairs = validate_handoff(
            self.handoff, self.identity["manifest_sha256"]
        )
        changed = ordered_changed_pairs(pairs)
        chunk = changed[:5]
        text, images = build_prompt(
            self.handoff,
            chunk,
            stage="sonnet",
            prompt=SONNET_PROMPT.read_text(encoding="utf-8").strip(),
        )
        expected_images = tuple(
            dict.fromkeys(
                path
                for pair in chunk
                for path in (pair["candidate"]["path"], pair["reference"]["path"])
            )
        )
        self.assertEqual(expected_images, images)
        self.assertLessEqual(len(images), 10)
        for path in images:
            self.assertRegex(path, r"^images/[0-9a-f]{64}\.png$")
            self.assertIn(f"./{path}", text)
        self.assertIn(chunk[0]["expectation"], text)
        self.assertNotIn(REPOSITORY, text)
        self.assertNotIn("ANTHROPIC_API_KEY", text)

        session = review_client.ClaudeCodeSession(
            claude=CLAUDE,
            token=TOKEN,
            handoff_root=self.handoff,
            runner=mock.Mock(),
            sleep=lambda _seconds: None,
            jitter=lambda lower, _upper: lower,
            monotonic=lambda: 0.0,
            started_at=0.0,
        )
        command = session._command("sonnet", chunk, images)
        self.assertEqual(str(CLAUDE), command[0])
        self.assertEqual(SONNET_MODEL, command[command.index("--model") + 1])
        self.assertEqual("json", command[command.index("--output-format") + 1])
        self.assertEqual("Read", command[command.index("--tools") + 1])
        self.assertEqual("dontAsk", command[command.index("--permission-mode") + 1])
        for flag in ("--print", "--safe-mode", "--no-session-persistence"):
            self.assertIn(flag, command)
        allowed = command[command.index("--allowedTools") + 1 : command.index("--permission-mode")]
        self.assertEqual([f"Read(./{path})" for path in images], allowed)
        schema = json.loads(command[command.index("--json-schema") + 1])
        self.assertIs(schema["additionalProperties"], False)
        self.assertNotIn(TOKEN, " ".join(command))
        self.assertNotIn(text, command)
        environment = session._environment()
        self.assertEqual(TOKEN, environment["CLAUDE_CODE_OAUTH_TOKEN"])
        self.assertTrue(
            set(environment)
            <= {
                "CLAUDE_CODE_OAUTH_TOKEN",
                "CLAUDE_CODE_SKIP_PROMPT_HISTORY",
                "DISABLE_AUTOUPDATER",
                "LANG",
                "PATH",
                "HOME",
            }
        )

        escalated = chunk[:4]
        sonnet = {
            pair["label"]: {
                "label": pair["label"],
                "capture_id": pair["capture_id"],
                "classification": "uncertain",
                "visible": "The state needs independent verification.",
                "findings": [],
            }
            for pair in escalated
        }
        fable_text, fable_images = build_prompt(
            self.handoff,
            escalated,
            stage="fable",
            prompt=FABLE_PROMPT.read_text(encoding="utf-8").strip(),
            sonnet_results=sonnet,
        )
        self.assertIn('"sonnet_triage"', fable_text)
        self.assertLessEqual(len(fable_images), 8)
        self.assertEqual(
            VERIFY_MODEL,
            session._command("fable", escalated, fable_images)[
                session._command("fable", escalated, fable_images).index("--model") + 1
            ],
        )
        with self.assertRaisesRegex(ReviewClientError, "only anomaly or uncertain"):
            bad = copy.deepcopy(sonnet)
            bad[escalated[0]["label"]]["classification"] = "clean"
            build_prompt(
                self.handoff,
                escalated,
                stage="fable",
                prompt="bounded",
                sonnet_results=bad,
            )
        with self.assertRaisesRegex(ReviewClientError, "outside 1..5"):
            build_prompt(self.handoff, list(pairs[:6]), stage="sonnet", prompt="bounded")

    def test_cost_envelope_rejects_an_eleventh_pair_before_transport(self) -> None:
        _manifest, pairs = validate_handoff(
            self.handoff, self.identity["manifest_sha256"]
        )
        over_budget = [copy.deepcopy(pair) for pair in pairs]
        extra = copy.deepcopy(over_budget[0])
        extra["label"] = "extra-lane/visual-regression/client_a/favorite_color_prompt"
        extra["capture_id"] = "visual-regression.client_a.favorite_color_prompt"
        extra["triage"]["byte_identical"] = False
        over_budget.append(extra)
        with self.assertRaisesRegex(ReviewClientError, "1..10"):
            validate_review_cost_envelope(over_budget)

    def test_worst_case_prompts_are_preflighted_before_any_call(self) -> None:
        manifest, pairs = validate_handoff(
            self.handoff, self.identity["manifest_sha256"]
        )
        all_changed = [copy.deepcopy(pair) for pair in pairs]
        for pair in all_changed:
            pair["triage"].update(
                {
                    "byte_identical": False,
                    "changed_pixels": max(1, pair["triage"]["changed_pixels"]),
                    "sum_absolute_delta": max(
                        1, pair["triage"]["sum_absolute_delta"]
                    ),
                    "sum_squared_delta": max(
                        1, pair["triage"]["sum_squared_delta"]
                    ),
                }
            )
        changed = ordered_changed_pairs(all_changed)
        self.assertEqual(10, len(changed))
        # Worst-case bounded Sonnet text makes the verification prompt the larger one; a budget
        # that fits every Sonnet chunk but not it must stop the review before the first call.
        sonnet_prompt = SONNET_PROMPT.read_text(encoding="utf-8").strip()
        fable_prompt = FABLE_PROMPT.read_text(encoding="utf-8").strip()
        worst = review_client._worst_case_sonnet_results(changed)
        sonnet_bytes = max(
            len(build_prompt(self.handoff, chunk, stage="sonnet", prompt=sonnet_prompt)[0].encode())
            for chunk in review_client._chunk_pairs(changed, stage="sonnet")
        )
        fable_bytes = max(
            len(
                build_prompt(
                    self.handoff, chunk, stage="fable", prompt=fable_prompt, sonnet_results=worst
                )[0].encode()
            )
            for chunk in review_client._chunk_pairs(changed, stage="fable")
        )
        self.assertGreater(fable_bytes, sonnet_bytes)
        runner = mock.Mock(side_effect=AssertionError("must fail before Claude Code"))
        with mock.patch(
            "scripts.visual.review_client.validate_handoff", return_value=(manifest, all_changed)
        ), mock.patch("scripts.visual.review_client.MAX_REQUEST_BYTES", sonnet_bytes):
            with tempfile.TemporaryDirectory() as temporary:
                with self.assertRaisesRegex(ReviewClientError, "fable prompt exceeds"):
                    run_review(
                        self.handoff,
                        expected_manifest_sha256=self.identity["manifest_sha256"],
                        claude=CLAUDE,
                        token=TOKEN,
                        output=Path(temporary) / "never.json",
                        runner=runner,
                    )
        runner.assert_not_called()

    def test_all_identical_review_needs_no_token_or_cli(self) -> None:
        manifest, pairs = validate_handoff(
            self.handoff, self.identity["manifest_sha256"]
        )
        all_identical = [copy.deepcopy(pair) for pair in pairs]
        for pair in all_identical:
            pair["triage"].update(
                {
                    "byte_identical": True,
                    "changed_pixels": 0,
                    "sum_absolute_delta": 0,
                    "sum_squared_delta": 0,
                }
            )
        runner = mock.Mock(side_effect=AssertionError("Claude Code must not be called"))
        with tempfile.TemporaryDirectory() as temporary, mock.patch(
            "scripts.visual.review_client.validate_handoff",
            return_value=(manifest, all_identical),
        ):
            report = run_review(
                self.handoff,
                expected_manifest_sha256=self.identity["manifest_sha256"],
                claude=None,
                token="",
                output=Path(temporary) / "identical.json",
                runner=runner,
            )
        runner.assert_not_called()
        telemetry = report["telemetry"]
        self.assertEqual(10, telemetry["identical_pairs"])
        for field in (
            "triaged_pairs",
            "escalated_pairs",
            "sonnet_calls",
            "fable_calls",
            "provider_attempts",
            "retries",
            "estimated_cost_micro_usd",
        ):
            self.assertEqual(0, telemetry[field])
        self.assertEqual([], telemetry["session_ids"])
        self.assertTrue(
            all(value == 0 for usage in (telemetry["sonnet_usage"], telemetry["fable_usage"]) for value in usage.values())
        )
        self.assertEqual({"identical"}, {item["route"] for item in report["verdicts"]})

    def test_changed_pair_priority_uses_key_tier_then_integer_metrics(self) -> None:
        _manifest, pairs = validate_handoff(
            self.handoff, self.identity["manifest_sha256"]
        )
        low_key, high_all, low_all = [copy.deepcopy(pair) for pair in pairs[:3]]
        low_key["label"] = "key"
        low_key["review_tier"] = "key"
        low_key["triage"].update(
            {
                "byte_identical": False,
                "changed_pixels": 1,
                "sum_absolute_delta": 1,
                "sum_squared_delta": 1,
            }
        )
        high_all["label"] = "all-high"
        high_all["review_tier"] = "all"
        high_all["triage"].update(
            {
                "byte_identical": False,
                "changed_pixels": 20,
                "sum_absolute_delta": 30,
                "sum_squared_delta": 40,
            }
        )
        low_all["label"] = "all-low"
        low_all["review_tier"] = "all"
        low_all["triage"].update(
            {
                "byte_identical": False,
                "changed_pixels": 10,
                "sum_absolute_delta": 20,
                "sum_squared_delta": 30,
            }
        )
        ordered = ordered_changed_pairs([low_all, high_all, low_key])
        self.assertEqual(["key", "all-high", "all-low"], [item["label"] for item in ordered])

    def test_sonnet_then_opus_produces_complete_schema_two_output(self) -> None:
        capsule, pairs = validate_handoff(self.handoff, self.identity["manifest_sha256"])
        clock = _Clock()
        sonnet_anomaly_emitted = False
        costs: list[float] = []

        def respond(model: str, request_pairs: list[dict[str, object]], call: int) -> object:
            nonlocal sonnet_anomaly_emitted
            cost = 0.01 * call
            costs.append(cost)
            if model == SONNET_MODEL:
                structured = _structured(request_pairs, model=model)
                for verdict in structured["verdicts"]:
                    if not sonnet_anomaly_emitted:
                        sonnet_anomaly_emitted = True
                        verdict.update(
                            classification="anomaly",
                            visible="A label may be clipped.",
                            findings=[
                                {
                                    "category": "clipping",
                                    "severity": "defect",
                                    "detail": "A label may be clipped.",
                                }
                            ],
                        )
                return _envelope(structured, session=call, cost=cost)
            return _envelope(
                _structured(request_pairs, model=model, defect=True), session=call, cost=cost
            )

        cli = _FakeCli(respond)
        with tempfile.TemporaryDirectory(prefix="blockpops-review-result-") as temporary:
            output = Path(temporary) / "raw.json"
            report = run_review(
                self.handoff,
                expected_manifest_sha256=self.identity["manifest_sha256"],
                claude=CLAUDE,
                token=TOKEN,
                output=output,
                runner=cli,
                sleep=clock.sleep,
                monotonic=clock.monotonic,
            )
            for call in cli.calls:
                self.assertEqual(self.handoff / "queue" / "capsule", call["cwd"])
                self.assertEqual(TOKEN, call["env"]["CLAUDE_CODE_OAUTH_TOKEN"])
                self.assertNotIn("GITHUB_TOKEN", call["env"])
                self.assertLessEqual(call["timeout"], 15 * 60)
            sonnet_calls = math.ceil(len(ordered_changed_pairs(pairs)) / 5)
            self.assertEqual(
                [SONNET_MODEL] * sonnet_calls + [VERIFY_MODEL],
                [call["model"] for call in cli.calls],
            )
            self.assertEqual(len(pairs), len(report["verdicts"]))
            self.assertEqual(2, report["schema_version"])
            self.assertEqual(1, report["telemetry"]["escalated_pairs"])
            self.assertLessEqual(
                report["telemetry"]["sonnet_calls"]
                + report["telemetry"]["fable_calls"],
                5,
            )
            telemetry = report["telemetry"]
            self.assertEqual("claude-code-oauth", telemetry["auth_mode"])
            self.assertEqual(VERIFY_MODEL, telemetry["verification_model"])
            self.assertEqual(
                sum(math.ceil(cost * 1_000_000) for cost in costs),
                telemetry["estimated_cost_micro_usd"],
            )
            self.assertEqual(
                [f"00000000-0000-4000-8000-{call:012x}" for call in range(1, len(cli.calls) + 1)],
                telemetry["session_ids"],
            )
            self.assertEqual(100 * sonnet_calls, telemetry["sonnet_usage"]["input_tokens"])
            self.assertEqual(100, telemetry["fable_usage"]["input_tokens"])
            self.assertEqual(1, sum(item["semantic_regression"] for item in report["verdicts"]))
            self.assertEqual(
                {"identical", "sonnet", "fable"},
                {item["route"] for item in report["verdicts"]},
            )
            normalized = read_and_validate_review(self.handoff / "queue" / "capsule", output)
            self.assertEqual(report, normalized)
            publication = normalize(
                handoff=self.handoff,
                expected_handoff_sha256=self.identity["manifest_sha256"],
                raw_output=output,
                normalized_output=Path(temporary) / "normalized.json",
                markdown_output=Path(temporary) / "advisory.md",
                provenance_output=Path(temporary) / "provenance.json",
                repository=REPOSITORY,
                source_run_id=171,
                source_run_attempt=1,
                source_head_sha="a" * 40,
                tested_sha="9" * 40,
                reference_sha="b" * 40,
            )
            self.assertEqual(1, publication["semantic_regressions"])
            provenance_path = Path(temporary) / "provenance.json"
            provenance = validate_review_provenance(
                handoff=self.handoff,
                expected_handoff_sha256=self.identity["manifest_sha256"],
                normalized_output=Path(temporary) / "normalized.json",
                provenance_output=provenance_path,
            )
            self.assertEqual("9" * 40, provenance["candidate_source"]["tested_commit"])
            self.assertEqual(
                capsule["candidate_source"]["tested_tree"],
                provenance["candidate_source"]["tested_tree"],
            )
            self.assertEqual("b" * 40, provenance["reference_source"]["tested_commit"])
            self.assertEqual(10, len(provenance["pairs"]))
            self.assertEqual(
                hashlib.sha256((Path(temporary) / "normalized.json").read_bytes()).hexdigest(),
                provenance["normalized_review_sha256"],
            )
            self.assertEqual(
                publication["provenance_sha256"],
                hashlib.sha256(provenance_path.read_bytes()).hexdigest(),
            )
            injected = copy.deepcopy(report)
            defect = next(item for item in injected["verdicts"] if item["semantic_regression"])
            defect["visible"] = (
                "Ping @org/team, &commat;entity/team, and &#64;numeric-user at "
                "https://evil.example/#123"
            )
            defect["findings"][0]["detail"] = (
                "Notify @user and inspect https&#58;//entity.example"
            )
            rendered = advisory_markdown(injected)
            self.assertNotIn("@org/team", rendered)
            self.assertNotIn("@user", rendered)
            self.assertNotIn("https://", rendered)
            self.assertNotIn("#123", rendered)
            self.assertNotIn("&commat;entity/team", rendered)
            self.assertNotIn("&#64;numeric-user", rendered)
            self.assertNotIn("https&#58;//entity.example", rendered)
            bidi = copy.deepcopy(report)
            bidi["verdicts"][0]["visible"] = "safe\u202espoof"
            bidi_path = Path(temporary) / "bidi.json"
            bidi_path.write_text(
                json.dumps(bidi, sort_keys=True, separators=(",", ":")) + "\n",
                encoding="utf-8",
            )
            with self.assertRaises(VisualReviewOutputError):
                read_and_validate_review(self.handoff / "queue" / "capsule", bidi_path)
            mixed_report = copy.deepcopy(report)
            mixed_report["telemetry"]["client_sha256"] = "0" * 64
            mixed_raw = Path(temporary) / "mixed-tool.json"
            mixed_raw.write_text(
                json.dumps(mixed_report, sort_keys=True, separators=(",", ":")) + "\n",
                encoding="utf-8",
            )
            with self.assertRaisesRegex(NormalizeError, "authenticated client and prompts"):
                normalize(
                    handoff=self.handoff,
                    expected_handoff_sha256=self.identity["manifest_sha256"],
                    raw_output=mixed_raw,
                    normalized_output=Path(temporary) / "mixed-normalized.json",
                    markdown_output=Path(temporary) / "mixed-advisory.md",
                    provenance_output=Path(temporary) / "mixed-provenance.json",
                    repository=REPOSITORY,
                    source_run_id=171,
                    source_run_attempt=1,
                    source_head_sha="a" * 40,
                    tested_sha="9" * 40,
                    reference_sha="b" * 40,
                )
            with self.assertRaisesRegex(NormalizeError, "different source or baseline"):
                normalize(
                    handoff=self.handoff,
                    expected_handoff_sha256=self.identity["manifest_sha256"],
                    raw_output=output,
                    normalized_output=Path(temporary) / "wrong-normalized.json",
                    markdown_output=Path(temporary) / "wrong-advisory.md",
                    provenance_output=Path(temporary) / "wrong-provenance.json",
                    repository=REPOSITORY,
                    source_run_id=171,
                    source_run_attempt=1,
                    source_head_sha="a" * 40,
                    tested_sha="0" * 40,
                    reference_sha="b" * 40,
                )
            mutated_provenance = copy.deepcopy(provenance)
            mutated_provenance["pairs"][0]["triage"]["changed_pixels"] += 1
            provenance_path.write_text(
                json.dumps(mutated_provenance, sort_keys=True, separators=(",", ":"))
                + "\n",
                encoding="utf-8",
            )
            with self.assertRaisesRegex(NormalizeError, "disagrees"):
                validate_review_provenance(
                    handoff=self.handoff,
                    expected_handoff_sha256=self.identity["manifest_sha256"],
                    normalized_output=Path(temporary) / "normalized.json",
                    provenance_output=provenance_path,
                )
        self.assertGreaterEqual(clock.now, 1_800_000_000 + 15 * (len(cli.calls) - 1))

    def test_cli_results_are_classified_without_keeping_provider_text(self) -> None:
        _manifest, pairs = validate_handoff(
            self.handoff, self.identity["manifest_sha256"]
        )
        pair = {"label": pairs[0]["label"], "capture_id": pairs[0]["capture_id"]}
        valid = _envelope(_structured([pair], model=SONNET_MODEL))
        result = extract_cli_result(json.dumps(valid).encode(), [pair], stage="sonnet")
        self.assertEqual(1, len(result.verdicts))
        self.assertEqual(12_500, result.cost_micro_usd)
        cases = {
            "mixed identity": (
                lambda envelope: envelope["structured_output"]["verdicts"][0].update(
                    label="mixed/source"
                ),
                "provider_response",
                True,
            ),
            "missing structured output": (
                lambda envelope: envelope.pop("structured_output"),
                "provider_response",
                True,
            ),
            "stale schema": (
                lambda envelope: envelope["structured_output"].update(schema_version=2),
                "provider_response",
                True,
            ),
            "bidi text": (
                lambda envelope: envelope["structured_output"]["verdicts"][0].update(
                    visible="safe‮spoof"
                ),
                "provider_response",
                True,
            ),
            "unknown session": (
                lambda envelope: envelope.update(session_id="req_not_a_session"),
                "provider_response",
                True,
            ),
            "negative cost": (
                lambda envelope: envelope.update(total_cost_usd=-1),
                "provider_response",
                True,
            ),
            "not logged in": (
                lambda envelope: envelope.update(
                    is_error=True, result="Not logged in · Please run /login"
                ),
                "authentication",
                False,
            ),
            "forbidden": (
                lambda envelope: envelope.update(is_error=True, api_error_status=403),
                "authentication",
                False,
            ),
            "rate limited": (
                lambda envelope: envelope.update(is_error=True, api_error_status=429),
                "rate_limited",
                True,
            ),
            "usage limit": (
                lambda envelope: envelope.update(
                    is_error=True, result="Claude usage limit reached; do not persist this"
                ),
                "rate_limited",
                True,
            ),
            "overloaded": (
                lambda envelope: envelope.update(is_error=True, api_error_status=529),
                "provider_unavailable",
                True,
            ),
            "structured retries": (
                lambda envelope: envelope.update(subtype="error_max_structured_output_retries"),
                "provider_response",
                True,
            ),
        }
        for name, (mutate, category, transient) in cases.items():
            with self.subTest(name=name):
                envelope = copy.deepcopy(valid)
                mutate(envelope)
                with self.assertRaises(review_client.CliOutcome) as captured:
                    extract_cli_result(json.dumps(envelope).encode(), [pair], stage="sonnet")
                self.assertEqual(category, captured.exception.category)
                self.assertIs(transient, captured.exception.transient)
                self.assertNotIn("persist", str(captured.exception))
        for payload in (b"", b"not json", b"[]", b"x" * (review_client.MAX_RESPONSE_BYTES + 1)):
            with self.subTest(payload=payload[:10]):
                with self.assertRaises(review_client.CliOutcome):
                    extract_cli_result(payload, [pair], stage="sonnet")

    def test_rate_limit_retries_once_and_emits_only_a_sanitized_marker(self) -> None:
        clock = _Clock()
        cli = _FakeCli(
            lambda model, pairs, call: _failure(
                "Rate limit reached: do not persist this provider text", status=429
            )
        )
        with tempfile.TemporaryDirectory() as temporary:
            with self.assertRaises(ReviewFailure) as captured:
                run_review(
                    self.handoff,
                    expected_manifest_sha256=self.identity["manifest_sha256"],
                    claude=CLAUDE,
                    token=TOKEN,
                    output=Path(temporary) / "never.json",
                    runner=cli,
                    sleep=clock.sleep,
                    jitter=lambda lower, upper: upper,
                    monotonic=clock.monotonic,
                )
            marker = failure_marker(captured.exception)
            serialized = json.dumps(marker)
            self.assertEqual("rate_limited", marker["category"])
            self.assertEqual("sonnet", marker["stage"])
            self.assertIs(True, marker["transient"])
            self.assertEqual(1800, marker["cooldown_seconds"])
            self.assertEqual(2, marker["attempts"])
            self.assertNotIn("provider text", serialized)
            self.assertEqual(2, len(cli.calls))
            self.assertEqual([30.0], clock.sleeps)

    def test_authentication_failure_is_terminal_without_a_second_call(self) -> None:
        clock = _Clock()
        cli = _FakeCli(lambda model, pairs, call: _failure("Not logged in · Please run /login"))
        with tempfile.TemporaryDirectory() as temporary:
            with self.assertRaises(ReviewFailure) as captured:
                run_review(
                    self.handoff,
                    expected_manifest_sha256=self.identity["manifest_sha256"],
                    claude=CLAUDE,
                    token=TOKEN,
                    output=Path(temporary) / "never.json",
                    runner=cli,
                    sleep=clock.sleep,
                    monotonic=clock.monotonic,
                )
        self.assertEqual("authentication", captured.exception.category)
        self.assertIs(False, captured.exception.transient)
        self.assertEqual(1, len(cli.calls))

    def test_cli_process_failures_are_bounded_and_classified(self) -> None:
        cases = (
            ("timeout", subprocess.TimeoutExpired("claude", 1), "transport", True, 2),
            ("missing binary", FileNotFoundError("claude"), "invalid_configuration", False, 1),
            ("nonzero exit", (1, b""), "provider_response", False, 2),
        )
        for name, outcome, category, transient, calls in cases:
            with self.subTest(name=name), tempfile.TemporaryDirectory() as temporary:
                clock = _Clock()
                cli = _FakeCli(lambda model, pairs, call, outcome=outcome: outcome)
                with self.assertRaises(ReviewFailure) as captured:
                    run_review(
                        self.handoff,
                        expected_manifest_sha256=self.identity["manifest_sha256"],
                        claude=CLAUDE,
                        token=TOKEN,
                        output=Path(temporary) / "never.json",
                        runner=cli,
                        sleep=clock.sleep,
                        monotonic=clock.monotonic,
                    )
                self.assertEqual(category, captured.exception.category)
                self.assertIs(transient, captured.exception.transient)
                self.assertEqual(calls, len(cli.calls))

    def test_malformed_cli_result_gets_only_one_bounded_retry(self) -> None:
        clock = _Clock()
        malformed_sent = False

        def respond(model: str, request_pairs: list[dict[str, object]], call: int) -> object:
            nonlocal malformed_sent
            if not malformed_sent:
                malformed_sent = True
                return b'{"type": "result", "truncated'
            return _envelope(_structured(request_pairs, model=model), session=call)

        cli = _FakeCli(respond)
        with tempfile.TemporaryDirectory() as temporary:
            report = run_review(
                self.handoff,
                expected_manifest_sha256=self.identity["manifest_sha256"],
                claude=CLAUDE,
                token=TOKEN,
                output=Path(temporary) / "raw.json",
                runner=cli,
                sleep=clock.sleep,
                jitter=lambda lower, upper: upper,
                monotonic=clock.monotonic,
            )
        self.assertEqual(1, report["telemetry"]["retries"])
        self.assertEqual(
            report["telemetry"]["sonnet_calls"] + 1,
            report["telemetry"]["provider_attempts"],
        )
        self.assertEqual(report["telemetry"]["provider_attempts"], len(cli.calls))
        # The malformed attempt reported no usage; only the answered calls count.
        sonnet_calls = report["telemetry"]["sonnet_calls"]
        self.assertEqual(100 * sonnet_calls, report["telemetry"]["sonnet_usage"]["input_tokens"])
        self.assertEqual(20 * sonnet_calls, report["telemetry"]["sonnet_usage"]["output_tokens"])
        self.assertIn(30.0, clock.sleeps)

    def test_only_a_claude_code_token_and_the_cli_may_start_a_review(self) -> None:
        for name, claude, token in (
            ("missing token", CLAUDE, ""),
            ("api key", CLAUDE, "sk-ant-api03-" + "x" * 32),
            ("missing cli", None, TOKEN),
        ):
            with self.subTest(name=name), tempfile.TemporaryDirectory() as temporary:
                runner = mock.Mock(side_effect=AssertionError("must not call Claude Code"))
                with self.assertRaises(ReviewFailure) as captured:
                    run_review(
                        self.handoff,
                        expected_manifest_sha256=self.identity["manifest_sha256"],
                        claude=claude,
                        token=token,
                        output=Path(temporary) / "never.json",
                        runner=runner,
                    )
                self.assertEqual("invalid_configuration", captured.exception.category)
                self.assertEqual("authentication", captured.exception.stage)
                self.assertIs(captured.exception.transient, False)
                runner.assert_not_called()
        for variable in ("ANTHROPIC_API_KEY", "ANTHROPIC_AUTH_TOKEN", "ANTHROPIC_OAUTH_ACCESS_TOKEN"):
            with self.subTest(variable=variable), mock.patch.dict(
                "os.environ", {variable: "x", "CLAUDE_CODE_OAUTH_TOKEN": TOKEN}
            ):
                with self.assertRaises(ReviewFailure) as captured:
                    review_client._oauth_token()
                self.assertEqual("invalid_configuration", captured.exception.category)
        with mock.patch.dict("os.environ", {"CLAUDE_CODE_OAUTH_TOKEN": TOKEN}):
            self.assertEqual(TOKEN, review_client._oauth_token())
            # Read once, then gone: nothing else in the process inherits it.
            self.assertNotIn("CLAUDE_CODE_OAUTH_TOKEN", review_client.os.environ)

    def test_unexpected_runner_errors_emit_unknown_retained_failure_markers(self) -> None:
        for injected in (
            OSError("sensitive filesystem detail"),
            RuntimeError("sensitive unexpected detail"),
        ):
            with self.subTest(
                kind=type(injected).__name__
            ), tempfile.TemporaryDirectory() as temporary:
                marker_path = Path(temporary) / "failure.json"
                stderr = io.StringIO()
                with mock.patch(
                    "scripts.visual.review_client.validate_handoff",
                    side_effect=injected,
                ), mock.patch("sys.stderr", stderr):
                    status = review_client.main(
                        [
                            "--handoff",
                            str(Path(temporary) / "unused-handoff"),
                            "--expected-manifest-sha256",
                            "a" * 64,
                            "--failure-output",
                            str(marker_path),
                        ]
                    )
                marker = json.loads(marker_path.read_text(encoding="utf-8"))
                self.assertEqual(2, status)
                self.assertEqual("unknown", marker["category"])
                self.assertIs(marker["transient"], False)
                self.assertNotIn("sensitive", json.dumps(marker))
                self.assertIn("category=unknown", stderr.getvalue())
                self.assertNotIn("sensitive", stderr.getvalue())

    def test_handoff_rejects_traversal_symlink_mutation_extra_and_size_overflow(self) -> None:
        for mutation in (
            "traversal",
            "symlink",
            "mutation",
            "extra",
            "implementation",
            "prompt",
            "size",
        ):
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
                    (handoff / "queue" / "capsule" / "unexpected.txt").write_text("x")
                elif mutation == "implementation":
                    queue_path = handoff / "queue" / "queue.json"
                    queue_path.chmod(0o644)
                    queue = json.loads(queue_path.read_text(encoding="utf-8"))
                    queue["implementation_sha"] = "unknown"
                    queue_path.write_text(
                        json.dumps(queue, sort_keys=True, separators=(",", ":")) + "\n",
                        encoding="utf-8",
                    )
                elif mutation == "prompt":
                    prompt = handoff / "prompts" / "sonnet.md"
                    prompt.chmod(0o644)
                    prompt.write_text("mutated prompt\n", encoding="utf-8")
                else:
                    with mock.patch("scripts.visual.review_client.MAX_FILE_BYTES", 1):
                        with self.assertRaises(ReviewClientError):
                            validate_handoff(handoff, digest)
                    continue
                with self.assertRaises(ReviewClientError):
                    validate_handoff(handoff, digest)

    def test_prompt_limit_is_enforced_before_any_call(self) -> None:
        _manifest, pairs = validate_handoff(
            self.handoff, self.identity["manifest_sha256"]
        )
        with mock.patch("scripts.visual.review_client.MAX_REQUEST_BYTES", 1):
            with self.assertRaisesRegex(ReviewClientError, "sonnet prompt exceeds"):
                build_prompt(
                    self.handoff,
                    [pairs[0]],
                    stage="sonnet",
                    prompt="bounded prompt",
                )

class VisualSourceAuthenticationTests(unittest.TestCase):
    def test_source_run_authenticates_exact_repo_path_event_status_and_attempt(self) -> None:
        run = _run_record(21, event="pull_request_target")
        identity = authenticate_run(run, repository=REPOSITORY, expected_id=21)
        self.assertEqual(MASTER_SHA, identity.head_sha)
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

    def test_newer_default_controller_sync_title_cannot_shadow_baseline(self) -> None:
        baseline = _run_record(32, created_at="2026-08-10T10:00:00Z")
        sync = _run_record(
            33,
            created_at="2026-08-10T11:00:00Z",
            display_title=f"Packaged E2E / {MERGE_SHA}",
        )
        candidates = reference_candidates(
            [baseline, sync],
            repository=REPOSITORY,
            branch="master",
            head_sha=MASTER_SHA,
        )
        self.assertEqual([32], [item.identity.run_id for item in candidates])

    def test_newest_full_reference_failure_pending_or_missing_artifact_blocks_fallback(self) -> None:
        older = _run_record(40, created_at="2026-08-10T10:00:00Z")
        older_artifact = _anchor_artifact(40)
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
            artifacts={50: [_anchor_artifact(50)], 51: []},
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

    def test_newer_attestation_with_anchor_cannot_shadow_full_baseline(self) -> None:
        older = _run_record(52, created_at="2026-08-10T10:00:00Z")
        attestation = _run_record(53, created_at="2026-08-10T11:00:00Z")
        api = _SelectionApi(
            artifacts={52: [_anchor_artifact(52)], 53: [_anchor_artifact(53)]},
            jobs={
                52: [_job(52, "Resolve authoritative packaged matrix")],
                53: [_job(53, "Attest exact tested packaged tree / Verify exact tested tree")],
            },
        )
        run, artifact = select_reference_run(
            api,
            [older, attestation],
            repository=REPOSITORY,
            branch="master",
            head_sha=MASTER_SHA,
        )
        self.assertEqual(52, run.run_id)
        self.assertEqual(1052, artifact.artifact_id)

    def test_pull_request_target_binds_separate_source_base_and_tested_merge(self) -> None:
        run = RunIdentity(
            repository=REPOSITORY,
            head_repository=REPOSITORY,
            head_branch="master",
            head_sha=MASTER_SHA,
            run_id=60,
            run_attempt=2,
            event="pull_request_target",
            created_at=datetime(2026, 8, 10, tzinfo=timezone.utc),
        )
        record = {"pull_requests": [{"number": 17}]}
        api = _PullApi()
        tested = _resolve_tested_identity(
            api, record, run, expected_tested=MERGE_SHA
        )
        self.assertEqual(MERGE_SHA, tested.tested_commit)
        self.assertEqual(TREE_SHA, tested.tested_tree)
        self.assertEqual(SOURCE_SHA, tested.source_head_commit)
        self.assertEqual("feature/current-ui", tested.source_head_branch)
        self.assertEqual("master", tested.base_branch_hint)
        self.assertEqual(
            (BASE_SHA, False),
            _candidate_reference_binding(api, record, run, tested),
        )
        for mutation in ("head", "fork", "base", "parents", "merge"):
            with self.subTest(mutation=mutation):
                broken = _PullApi()
                if mutation == "head":
                    broken.pull["head"]["sha"] = "1" * 40
                elif mutation == "fork":
                    broken.pull["head"]["repo"]["full_name"] = "contributor/BlockPops"
                elif mutation == "base":
                    broken.branch_heads[(REPOSITORY, "master")] = "2" * 40
                elif mutation == "parents":
                    broken.parents = (SOURCE_SHA, BASE_SHA)
                else:
                    broken.pull["merge_commit_sha"] = SOURCE_SHA
                with self.assertRaises(CurationError):
                    _resolve_tested_identity(
                        broken, record, run, expected_tested=MERGE_SHA
                    )

    def test_merged_pull_delivers_the_exact_tested_tree_and_head_parent(self) -> None:
        delivered = "4" * 40

        class MergedApi(_PullApi):
            def __init__(self) -> None:
                super().__init__()
                self.pull.update(
                    {"state": "closed", "merged": True, "merge_commit_sha": delivered}
                )
                self.branch_heads[(REPOSITORY, "master")] = delivered
                self.delivered_tree = TREE_SHA

            def commit_identity(
                self, repository: str, commit: str
            ) -> tuple[str, tuple[str, ...]]:
                if repository != REPOSITORY or commit not in {MERGE_SHA, delivered}:
                    raise AssertionError((repository, commit))
                tree = self.delivered_tree if commit == delivered else TREE_SHA
                return tree, self.parents

        run = RunIdentity(
            repository=REPOSITORY,
            head_repository=REPOSITORY,
            head_branch="master",
            head_sha=MASTER_SHA,
            run_id=61,
            run_attempt=2,
            event="pull_request_target",
            created_at=datetime(2026, 8, 10, tzinfo=timezone.utc),
        )
        record = {"pull_requests": [{"number": 17}]}
        api = MergedApi()
        tested = _resolve_tested_identity(
            api, record, run, expected_tested=MERGE_SHA
        )
        self.assertEqual(MERGE_SHA, tested.tested_commit)
        self.assertEqual(
            (BASE_SHA, True),
            _candidate_reference_binding(api, record, run, tested),
        )
        api.branch_heads[(REPOSITORY, "master")] = "6" * 40
        with self.assertRaisesRegex(CurationError, "historical baseline"):
            _candidate_reference_binding(api, record, run, tested)
        api.branch_heads[(REPOSITORY, "master")] = delivered
        api.delivered_tree = "5" * 40
        with self.assertRaisesRegex(CurationError, "delivers the exact tested tree"):
            _resolve_tested_identity(api, record, run, expected_tested=MERGE_SHA)

    def test_aggregate_name_is_the_only_tested_merge_identity(self) -> None:
        values = [_artifact(80, commit=MERGE_SHA, attempt=2)]
        values[0]["name"] = aggregate_artifact_name(MERGE_SHA, 2)
        self.assertEqual(
            MERGE_SHA,
            aggregate_tested_commit(values, run_id=80, run_attempt=2),
        )
        values[0]["name"] = aggregate_artifact_name("6" * 40, 1)
        with self.assertRaises(CurationError):
            aggregate_tested_commit(values, run_id=80, run_attempt=2)

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

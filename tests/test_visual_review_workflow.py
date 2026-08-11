from __future__ import annotations

import base64
import copy
import hashlib
import io
import json
import re
import shutil
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
    FEDERATION_BETAS,
    MESSAGES_ENDPOINT,
    FABLE_MODEL,
    SONNET_MODEL,
    TOKEN_ENDPOINT,
    HttpResponse,
    ReviewClientError,
    ReviewFailure,
    build_request,
    extract_response,
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


def _response(
    pairs: list[dict[str, object]],
    *,
    model: str,
    classification: str = "clean",
    defect: bool = False,
) -> bytes:
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
    result = {"schema_version": 1, "advisory": True, "verdicts": verdicts}
    envelope = {
        "id": "msg_test_response",
        "type": "message",
        "role": "assistant",
        "model": model,
        "content": [{"type": "text", "text": json.dumps(result)}],
        "stop_reason": "end_turn",
        "stop_sequence": None,
        "stop_details": None,
        "usage": {
            "input_tokens": 100,
            "output_tokens": 20,
            "cache_creation_input_tokens": 0,
            "cache_read_input_tokens": 0,
            "cache_creation": None,
            "output_tokens_details": {
                "thinking_tokens": 12 if model == FABLE_MODEL else 0
            },
            "server_tool_use": None,
            "service_tier": "standard",
            "inference_geo": "global",
        },
    }
    return json.dumps(envelope).encode("utf-8")


def _jwt(
    repository: str, *, now: int, overrides: dict[str, object] | None = None
) -> str:
    def encode(value: dict[str, object]) -> str:
        payload = json.dumps(value, sort_keys=True, separators=(",", ":")).encode()
        return base64.urlsafe_b64encode(payload).decode().rstrip("=")

    header = encode({"alg": "RS256", "kid": "test-key", "typ": "JWT"})
    claim_record: dict[str, object] = {
        "iss": "https://token.actions.githubusercontent.com",
        "aud": "https://api.anthropic.com",
        "sub": f"repo:{repository}:environment:visual-review",
        "repository": repository,
        "ref": "refs/heads/master",
        "workflow_ref": (
            f"{repository}/.github/workflows/visual-review-drain.yml@refs/heads/master"
        ),
        "workflow_sha": "c" * 40,
        "event_name": "workflow_dispatch",
        "iat": now,
        "nbf": now,
        "exp": now + 300,
    }
    claim_record.update(overrides or {})
    claims = encode(claim_record)
    return f"{header}.{claims}.test-signature"


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
        api = CuratorGitHubApi(
            repository=REPOSITORY,
            token="test-token",
            api_url="https://api.github.com",
        )
        proxies = [
            handler.proxies
            for handler in api.opener.handlers
            if isinstance(handler, urllib.request.ProxyHandler)
        ]
        self.assertEqual([{}], proxies)

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

    def test_credential_job_has_only_oidc_wif_and_the_exact_bounded_handoff(self) -> None:
        drain = DRAIN_WORKFLOW.read_text(encoding="utf-8")
        admit = _job_block(drain, "admit")
        review = _job_block(drain, "review")
        self.assertIn("actions: read", review)
        self.assertIn("contents: read", review)
        self.assertIn("id-token: write", review)
        self.assertIn("pull-requests: read", review)
        self.assertIn("environment: visual-review", review)
        self.assertNotIn("actions/checkout", review)
        self.assertNotIn("actions/setup-python", review)
        self.assertNotIn("pip install", review)
        self.assertNotIn("scripts/visual/reauth.py", review)
        self.assertIn("artifact-ids: ${{ needs.admit.outputs.artifact_id }}", review)
        self.assertIn("digest-mismatch: error", review)
        self.assertNotIn("id-token: write", admit)
        self.assertIn("environment: visual-review", admit)
        self.assertIn("python3 scripts/visual/reauth.py", admit)
        self.assertIn("artifact-ids: ${{ needs.prepare.outputs.artifact_id }}", admit)
        self.assertIn("name: Post-approval reauthenticate on a non-credential runner", admit)
        self.assertIn('GH_TOKEN: ""', review)
        self.assertIn('GITHUB_TOKEN: ""', review)
        for variable in (
            "ANTHROPIC_FEDERATION_RULE_ID",
            "ANTHROPIC_ORGANIZATION_ID",
            "ANTHROPIC_SERVICE_ACCOUNT_ID",
            "ANTHROPIC_WORKSPACE_ID",
        ):
            self.assertIn(f"{variable}: ${{{{ vars.{variable} }}}}", review)
        for static_credential in (
            "ANTHROPIC_API_KEY",
            "ANTHROPIC_AUTH_TOKEN",
            "ANTHROPIC_OAUTH_ACCESS_TOKEN",
            "CLAUDE_CODE_OAUTH_TOKEN",
            "OPENAI_API_KEY",
            "secrets.",
        ):
            self.assertNotIn(static_credential, review)
        self.assertIn("$RUNNER_TEMP/visual-review-handoff", review)
        self.assertIn("$RUNNER_TEMP/visual-review-result/review.json", review)
        self.assertIn("$RUNNER_TEMP/visual-review-result/failure.json", review)
        self.assertIn("credential_preflight.py", review)
        self.assertIn("PREFLIGHT_GITHUB_TOKEN: ${{ github.token }}", review)
        self.assertIn("preflight_sha256", review)
        self.assertIn("--identity-token-file \"$identity_file\"", review)
        model_step = review.split(
            "- name: Mint renewable GitHub OIDC and invoke only the stdlib client", 1
        )[1].split("- name: Upload only the bounded normalized result", 1)[0]
        self.assertEqual(1, model_step.count("${{ github.token }}"))
        self.assertIn("unset PREFLIGHT_GITHUB_TOKEN GH_TOKEN GITHUB_TOKEN", model_step)
        self.assertLess(
            model_step.index("unset PREFLIGHT_GITHUB_TOKEN GH_TOKEN GITHUB_TOKEN"),
            model_step.index("refresh_oidc || oidc_status=$?"),
        )
        self.assertIn("-u GITHUB_REPOSITORY", model_step)
        self.assertNotIn("GITHUB_TOKEN: ${{ github.token }}", model_step)
        self.assertIn("[[ ! -e \"$GITHUB_WORKSPACE/.git\" ]]", review)
        self.assertIn("env -u ACTIONS_ID_TOKEN_REQUEST_URL", review)
        self.assertIn("payload = response.read(17 * 1024 + 1)", review)
        self.assertIn("len(token_bytes) > 16 * 1024", review)
        self.assertIn("urllib.request.ProxyHandler({})", review)
        self.assertIn("ssl.create_default_context()", review)
        self.assertIn("if error.code == 429:", review)
        self.assertIn("error.code in (408, 425) or 500 <= error.code <= 599", review)
        self.assertIn("raise SystemExit(41) from None", review)
        self.assertIn("raise SystemExit(42) from None", review)
        self.assertIn("raise SystemExit(43) from None", review)
        self.assertIn("category=invalid_configuration", review)
        self.assertIn("category=authentication", review)
        self.assertIn("category=rate_limited", review)
        self.assertIn("category=transport", review)
        self.assertIn('write_oidc_failure "$oidc_status"', review)
        self.assertIn('[[ "${#result_inventory[@]}" == 1 ]]', review)
        self.assertIn('[[ -f "$result_path" && ! -L "$result_path" ]]', review)
        self.assertIn('"$result_size" -le 1048576', review)

        identical = review.split('if [[ "$all_identical" == true ]]; then', 1)[1].split(
            "\n          else\n", 1
        )[0]
        self.assertIn(': > "$identity_file"', identical)
        self.assertIn("invoke_client", identical)
        self.assertNotIn("refresh_oidc", identical)

        client = CLIENT.read_text(encoding="utf-8")
        self.assertIn('TOKEN_ENDPOINT = "https://api.anthropic.com/v1/oauth/token"', client)
        self.assertIn('MESSAGES_ENDPOINT = "https://api.anthropic.com/v1/messages"', client)
        self.assertIn('SONNET_MODEL = "claude-sonnet-5"', client)
        self.assertIn('FABLE_MODEL = "claude-fable-5"', client)
        self.assertIn('"schema_version": 2', client)
        self.assertNotIn("api.openai.com", client)
        self.assertIn("_NoRedirect()", client)

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

    def test_requests_are_chunked_tool_free_structured_and_bounded(self) -> None:
        self.assertEqual(10, MAX_CAPSULE_PAIRS)
        self.assertEqual(MAX_CAPSULE_PAIRS, MAX_HANDOFF_PAIRS)
        self.assertEqual(MAX_CAPSULE_PAIRS, review_client.MAX_PAIRS)
        self.assertEqual(MAX_CAPSULE_PAIRS, MAX_OUTPUT_PAIRS)
        _manifest, pairs = validate_handoff(
            self.handoff, self.identity["manifest_sha256"]
        )
        changed = ordered_changed_pairs(pairs)
        chunk = changed[:5]
        payload = build_request(
            self.handoff,
            chunk,
            stage="sonnet",
            prompt=SONNET_PROMPT.read_text(encoding="utf-8").strip(),
        )
        request = json.loads(payload)
        self.assertEqual(SONNET_MODEL, request["model"])
        self.assertEqual({"type": "disabled"}, request["thinking"])
        self.assertEqual("standard_only", request["service_tier"])
        self.assertEqual("global", request["inference_geo"])
        self.assertEqual("high", request["output_config"]["effort"])
        self.assertNotIn("tools", request)
        self.assertNotIn("tool_choice", request)
        output_format = request["output_config"]["format"]
        self.assertEqual("json_schema", output_format["type"])
        self.assertIs(output_format["schema"]["additionalProperties"], False)
        content = request["messages"][0]["content"]
        images = [item for item in content if item["type"] == "image"]
        self.assertEqual(len(chunk) * 2, len(images))
        self.assertLessEqual(len(images), 10)
        self.assertTrue(
            all(item["source"]["media_type"] == "image/png" for item in images)
        )
        self.assertLessEqual(len(payload), 28 * 1024 * 1024)
        decoded = payload.decode("utf-8")
        self.assertIn(chunk[0]["expectation"], decoded)
        self.assertNotIn(REPOSITORY, decoded)
        self.assertNotIn("ANTHROPIC_API_KEY", decoded)

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
        fable_payload = build_request(
            self.handoff,
            escalated,
            stage="fable",
            prompt=FABLE_PROMPT.read_text(encoding="utf-8").strip(),
            sonnet_results=sonnet,
        )
        fable = json.loads(fable_payload)
        self.assertEqual(FABLE_MODEL, fable["model"])
        self.assertEqual({"type": "adaptive"}, fable["thinking"])
        self.assertEqual("standard_only", fable["service_tier"])
        self.assertEqual("global", fable["inference_geo"])
        self.assertEqual("high", fable["output_config"]["effort"])
        self.assertEqual(8, sum(item["type"] == "image" for item in fable["messages"][0]["content"]))
        with self.assertRaisesRegex(ReviewClientError, "only anomaly or uncertain"):
            bad = copy.deepcopy(sonnet)
            bad[escalated[0]["label"]]["classification"] = "clean"
            build_request(
                self.handoff,
                escalated,
                stage="fable",
                prompt="bounded",
                sonnet_results=bad,
            )

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

    def test_fable_byte_partition_is_preflighted_before_any_provider_call(self) -> None:
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
        # Deterministic high-entropy bytes make base64 request sizing realistic without decoding
        # or running Minecraft. Worst-case bounded Sonnet text makes Fable the larger request.
        image_fixture = bytes(range(256)) * 256
        sonnet_prompt = SONNET_PROMPT.read_text(encoding="utf-8").strip()
        fable_prompt = FABLE_PROMPT.read_text(encoding="utf-8").strip()
        worst = review_client._worst_case_sonnet_results(changed)
        with mock.patch(
            "scripts.visual.review_client._read_bound_image",
            return_value=image_fixture,
        ):
            sonnet_five = build_request(
                self.handoff,
                changed[:5],
                stage="sonnet",
                prompt=sonnet_prompt,
            )
            fable_three = build_request(
                self.handoff,
                changed[:3],
                stage="fable",
                prompt=fable_prompt,
                sonnet_results=worst,
            )
            fable_four = build_request(
                self.handoff,
                changed[:4],
                stage="fable",
                prompt=fable_prompt,
                sonnet_results=worst,
            )
            byte_budget = max(len(sonnet_five), len(fable_three))
            self.assertGreater(len(fable_four), byte_budget)
            transport = mock.Mock(side_effect=AssertionError("must fail before Anthropic"))
            with mock.patch("scripts.visual.review_client.validate_handoff", return_value=(manifest, all_changed)), mock.patch(
                "scripts.visual.review_client.MAX_REQUEST_BYTES", byte_budget
            ):
                with tempfile.TemporaryDirectory() as temporary:
                    with self.assertRaisesRegex(ReviewClientError, "at most five calls"):
                        run_review(
                            self.handoff,
                            expected_manifest_sha256=self.identity["manifest_sha256"],
                            identity_token_file=Path(temporary) / "unused.jwt",
                            federation_rule_id="fdrl_test_rule",
                            organization_id="12345678-1234-1234-1234-123456789abc",
                            service_account_id="svac_visual_review",
                            workspace_id="wrkspc_visual_review",
                            output=Path(temporary) / "never.json",
                            transport=transport,
                        )
            transport.assert_not_called()

    def test_all_identical_review_needs_no_identity_or_provider_configuration(self) -> None:
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
        transport = mock.Mock(side_effect=AssertionError("provider must not be called"))
        with tempfile.TemporaryDirectory() as temporary, mock.patch(
            "scripts.visual.review_client.validate_handoff",
            return_value=(manifest, all_identical),
        ):
            report = run_review(
                self.handoff,
                expected_manifest_sha256=self.identity["manifest_sha256"],
                identity_token_file=Path(temporary) / "missing.jwt",
                federation_rule_id="",
                organization_id="",
                service_account_id="",
                workspace_id="",
                output=Path(temporary) / "identical.json",
                transport=transport,
            )
        transport.assert_not_called()
        telemetry = report["telemetry"]
        self.assertEqual(10, telemetry["identical_pairs"])
        for field in (
            "triaged_pairs",
            "escalated_pairs",
            "sonnet_calls",
            "fable_calls",
            "provider_attempts",
            "retries",
            "reported_cost_upper_bound_micro_usd",
        ):
            self.assertEqual(0, telemetry[field])
        self.assertEqual([], telemetry["request_ids"])
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

    def test_wif_sonnet_then_fable_produces_complete_schema_two_output(self) -> None:
        capsule, pairs = validate_handoff(self.handoff, self.identity["manifest_sha256"])
        clock = _Clock()
        calls: list[dict[str, object]] = []
        sonnet_anomaly_emitted = False

        def transport(request: object, timeout: float) -> HttpResponse:
            nonlocal sonnet_anomaly_emitted
            self.assertLessEqual(timeout, 180)
            url = request.full_url
            headers = {key.lower(): value for key, value in request.header_items()}
            body = json.loads(request.data)
            if url == TOKEN_ENDPOINT:
                self.assertEqual(FEDERATION_BETAS, headers["anthropic-beta"])
                self.assertNotIn("authorization", headers)
                self.assertEqual(
                    {
                        "grant_type",
                        "assertion",
                        "federation_rule_id",
                        "organization_id",
                        "service_account_id",
                        "workspace_id",
                    },
                    set(body),
                )
                calls.append({"stage": "authentication"})
                return HttpResponse(
                    200,
                    {"request-id": "req_token_exchange"},
                    json.dumps(
                        {
                            "access_token": "sk-ant-oat01-" + "x" * 32,
                            "token_type": "Bearer",
                            "expires_in": 600,
                            "scope": "workspace:inference",
                        }
                    ).encode(),
                )
            self.assertEqual(MESSAGES_ENDPOINT, url)
            self.assertEqual("oauth-2025-04-20", headers["anthropic-beta"])
            self.assertEqual("2023-06-01", headers["anthropic-version"])
            self.assertEqual("Bearer sk-ant-oat01-" + "x" * 32, headers["authorization"])
            self.assertNotIn("tools", body)
            properties = body["output_config"]["format"]["schema"]["properties"][
                "verdicts"
            ]["items"]["properties"]
            request_pairs = [
                {"label": label, "capture_id": capture}
                for label, capture in zip(
                    properties["label"]["enum"], properties["capture_id"]["enum"]
                )
            ]
            model = body["model"]
            calls.append({"stage": model, "pairs": request_pairs})
            if model == SONNET_MODEL:
                verdicts = []
                for pair in request_pairs:
                    anomaly = not sonnet_anomaly_emitted
                    if anomaly:
                        sonnet_anomaly_emitted = True
                    verdicts.append(
                        {
                            "label": pair["label"],
                            "capture_id": pair["capture_id"],
                            "classification": "anomaly" if anomaly else "clean",
                            "visible": "A label may be clipped." if anomaly else "The expected UI is visible.",
                            "findings": (
                                [
                                    {
                                        "category": "clipping",
                                        "severity": "defect",
                                        "detail": "A label may be clipped.",
                                    }
                                ]
                                if anomaly
                                else []
                            ),
                        }
                    )
                response = _response(request_pairs, model=model)
                envelope = json.loads(response)
                envelope["content"][0]["text"] = json.dumps(
                    {"schema_version": 1, "advisory": True, "verdicts": verdicts}
                )
                response = json.dumps(envelope).encode()
            else:
                response = _response(request_pairs, model=model, defect=True)
            return HttpResponse(
                200,
                {"request-id": f"req_message_{len(calls)}"},
                response,
            )

        with tempfile.TemporaryDirectory(prefix="blockpops-review-result-") as temporary:
            identity = Path(temporary) / "github.jwt"
            identity.write_text(_jwt(REPOSITORY, now=int(clock.wall())), encoding="ascii")
            output = Path(temporary) / "raw.json"
            report = run_review(
                self.handoff,
                expected_manifest_sha256=self.identity["manifest_sha256"],
                identity_token_file=identity,
                federation_rule_id="fdrl_test_rule",
                organization_id="12345678-1234-1234-1234-123456789abc",
                service_account_id="svac_visual_review",
                workspace_id="wrkspc_visual_review",
                output=output,
                transport=transport,
                sleep=clock.sleep,
                monotonic=clock.monotonic,
                wall_clock=clock.wall,
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
            sonnet_usage = telemetry["sonnet_usage"]
            fable_usage = telemetry["fable_usage"]
            self.assertEqual(
                sonnet_usage["input_tokens"] * 3
                + sonnet_usage["cache_creation_input_tokens"] * 6
                + sonnet_usage["cache_read_input_tokens"] * 3
                + sonnet_usage["output_tokens"] * 15
                + fable_usage["input_tokens"] * 10
                + fable_usage["cache_creation_input_tokens"] * 20
                + fable_usage["cache_read_input_tokens"] * 10
                + fable_usage["output_tokens"] * 50,
                telemetry["reported_cost_upper_bound_micro_usd"],
            )
            self.assertEqual(1, sum(item["semantic_regression"] for item in report["verdicts"]))
            self.assertEqual(
                {"sonnet", "fable"},
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
            self.assertEqual("7" * 40, provenance["candidate_source"]["tested_tree"])
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
        model_calls = [call for call in calls if call["stage"] != "authentication"]
        self.assertLessEqual(len(model_calls), 5)
        self.assertGreaterEqual(clock.now, 1_800_000_000 + 15 * (len(model_calls) - 1))

    def test_malformed_or_refused_model_output_fails_closed(self) -> None:
        _manifest, pairs = validate_handoff(
            self.handoff, self.identity["manifest_sha256"]
        )
        pair = {"label": pairs[0]["label"], "capture_id": pairs[0]["capture_id"]}
        wrong = json.loads(_response([pair], model=SONNET_MODEL))
        result = json.loads(wrong["content"][0]["text"])
        result["verdicts"][0]["label"] = "mixed/source"
        wrong["content"][0]["text"] = json.dumps(result)
        with self.assertRaisesRegex(ReviewClientError, "identity"):
            extract_response(json.dumps(wrong).encode(), [pair], stage="sonnet")
        unexpected_tool = json.loads(_response([pair], model=SONNET_MODEL))
        unexpected_tool["content"] = [
            {"type": "tool_use", "id": "tool_1", "name": "read_repository", "input": {}}
        ]
        with self.assertRaisesRegex(ReviewClientError, "structured text block"):
            extract_response(json.dumps(unexpected_tool).encode(), [pair], stage="sonnet")
        fable = json.loads(_response([pair], model=FABLE_MODEL, defect=True))
        fable["content"].insert(
            0,
            {"type": "thinking", "thinking": "", "signature": "sig_test_123"},
        )
        self.assertEqual(
            1,
            len(extract_response(json.dumps(fable).encode(), [pair], stage="fable").verdicts),
        )
        self.assertEqual(12, fable["usage"]["output_tokens_details"]["thinking_tokens"])
        excessive_thinking = copy.deepcopy(fable)
        excessive_thinking["usage"]["output_tokens_details"]["thinking_tokens"] = 21
        with self.assertRaisesRegex(ReviewClientError, "thinking-token"):
            extract_response(
                json.dumps(excessive_thinking).encode(), [pair], stage="fable"
            )
        priority = copy.deepcopy(fable)
        priority["usage"]["service_tier"] = "priority"
        with self.assertRaises(review_client.ProviderPayloadError) as captured:
            extract_response(json.dumps(priority).encode(), [pair], stage="fable")
        self.assertIs(captured.exception.retryable, False)
        us_only = copy.deepcopy(fable)
        us_only["usage"]["inference_geo"] = "us"
        with self.assertRaises(review_client.ProviderPayloadError) as captured:
            extract_response(json.dumps(us_only).encode(), [pair], stage="fable")
        self.assertIs(captured.exception.retryable, False)
        refusal_details = copy.deepcopy(fable)
        refusal_details["stop_details"] = {
            "type": "refusal",
            "category": "general_harms",
            "explanation": "untrusted provider text",
        }
        with self.assertRaises(review_client.ProviderPayloadError) as captured:
            extract_response(
                json.dumps(refusal_details).encode(), [pair], stage="fable"
            )
        self.assertIs(captured.exception.retryable, False)
        malformed_stop_details = copy.deepcopy(refusal_details)
        malformed_stop_details["stop_details"]["unknown"] = "schema drift"
        with self.assertRaisesRegex(ReviewClientError, "stop details"):
            extract_response(
                json.dumps(malformed_stop_details).encode(), [pair], stage="fable"
            )
        redacted = json.loads(_response([pair], model=FABLE_MODEL, defect=True))
        redacted["content"].insert(0, {"type": "redacted_thinking", "data": "redacted_123"})
        self.assertEqual(
            1,
            len(
                extract_response(
                    json.dumps(redacted).encode(), [pair], stage="fable"
                ).verdicts
            ),
        )
        unknown_fable = copy.deepcopy(fable)
        unknown_fable["content"][0] = {"type": "server_tool_use", "name": "search"}
        with self.assertRaisesRegex(ReviewClientError, "structured text block"):
            extract_response(json.dumps(unknown_fable).encode(), [pair], stage="fable")
        sonnet_thinking = json.loads(_response([pair], model=SONNET_MODEL))
        sonnet_thinking["content"].insert(
            0,
            {"type": "thinking", "thinking": "", "signature": "sig_test_123"},
        )
        with self.assertRaisesRegex(ReviewClientError, "structured text block"):
            extract_response(json.dumps(sonnet_thinking).encode(), [pair], stage="sonnet")
        stale = json.loads(_response([pair], model=SONNET_MODEL))
        result = json.loads(stale["content"][0]["text"])
        result["schema_version"] = 2
        stale["content"][0]["text"] = json.dumps(result)
        with self.assertRaisesRegex(ReviewClientError, "root schema"):
            extract_response(json.dumps(stale).encode(), [pair], stage="sonnet")
        bidi = json.loads(_response([pair], model=SONNET_MODEL))
        bidi_result = json.loads(bidi["content"][0]["text"])
        bidi_result["verdicts"][0]["visible"] = "safe\u202espoof"
        bidi["content"][0]["text"] = json.dumps(bidi_result)
        with self.assertRaisesRegex(ReviewClientError, "invalid Unicode"):
            extract_response(json.dumps(bidi).encode(), [pair], stage="sonnet")

    def test_rate_limit_retries_once_and_emits_only_a_sanitized_marker(self) -> None:
        clock = _Clock()
        calls = 0

        def transport(request: object, timeout: float) -> HttpResponse:
            nonlocal calls
            calls += 1
            if request.full_url == TOKEN_ENDPOINT:
                return HttpResponse(
                    200,
                    {},
                    json.dumps(
                        {
                            "access_token": "sk-ant-oat01-" + "x" * 32,
                            "token_type": "Bearer",
                            "expires_in": 600,
                            "scope": "workspace:inference",
                        }
                    ).encode(),
                )
            return HttpResponse(
                429,
                {"retry-after": "30", "request-id": "req_secret_provider_text"},
                b'{"error":{"message":"do not persist this provider text"}}',
            )

        with tempfile.TemporaryDirectory() as temporary:
            identity = Path(temporary) / "github.jwt"
            identity.write_text(_jwt(REPOSITORY, now=int(clock.wall())), encoding="ascii")
            with self.assertRaises(ReviewFailure) as captured:
                run_review(
                    self.handoff,
                    expected_manifest_sha256=self.identity["manifest_sha256"],
                    identity_token_file=identity,
                    federation_rule_id="fdrl_test_rule",
                    organization_id="12345678-1234-1234-1234-123456789abc",
                    service_account_id="svac_visual_review",
                    workspace_id="wrkspc_visual_review",
                    output=Path(temporary) / "never.json",
                    transport=transport,
                    sleep=clock.sleep,
                    monotonic=clock.monotonic,
                    wall_clock=clock.wall,
                )
            marker = failure_marker(captured.exception)
            serialized = json.dumps(marker)
            self.assertEqual("rate_limited", marker["category"])
            self.assertEqual("sonnet", marker["stage"])
            self.assertEqual(30, marker["cooldown_seconds"])
            self.assertNotIn("provider text", serialized)
            self.assertNotIn("req_secret", serialized)
            self.assertEqual(3, calls)
            self.assertEqual([30], clock.sleeps)

    def test_refusal_is_terminal_without_a_second_paid_call(self) -> None:
        clock = _Clock()
        message_calls = 0

        def transport(request: object, timeout: float) -> HttpResponse:
            nonlocal message_calls
            if request.full_url == TOKEN_ENDPOINT:
                return HttpResponse(
                    200,
                    {},
                    json.dumps(
                        {
                            "access_token": "sk-ant-oat01-" + "x" * 32,
                            "token_type": "Bearer",
                            "expires_in": 600,
                            "scope": "workspace:inference",
                        }
                    ).encode(),
                )
            message_calls += 1
            body = json.loads(request.data)
            envelope = json.loads(
                _response(
                    [
                        {"label": label, "capture_id": capture}
                        for label, capture in zip(
                            body["output_config"]["format"]["schema"]["properties"][
                                "verdicts"
                            ]["items"]["properties"]["label"]["enum"],
                            body["output_config"]["format"]["schema"]["properties"][
                                "verdicts"
                            ]["items"]["properties"]["capture_id"]["enum"],
                        )
                    ],
                    model=body["model"],
                )
            )
            # Current Messages may expose a refusal through stop_details while retaining
            # end_turn as the stop reason; this must not spend the one schema retry.
            envelope["stop_reason"] = "end_turn"
            envelope["stop_details"] = {
                "type": "refusal",
                "category": "general_harms",
                "explanation": "untrusted provider policy text",
            }
            return HttpResponse(
                200,
                {"request-id": "req_refusal"},
                json.dumps(envelope).encode(),
            )

        with tempfile.TemporaryDirectory() as temporary:
            identity = Path(temporary) / "github.jwt"
            identity.write_text(_jwt(REPOSITORY, now=int(clock.wall())), encoding="ascii")
            with self.assertRaises(ReviewFailure) as captured:
                run_review(
                    self.handoff,
                    expected_manifest_sha256=self.identity["manifest_sha256"],
                    identity_token_file=identity,
                    federation_rule_id="fdrl_test_rule",
                    organization_id="12345678-1234-1234-1234-123456789abc",
                    service_account_id="svac_visual_review",
                    workspace_id="wrkspc_visual_review",
                    output=Path(temporary) / "never.json",
                    transport=transport,
                    sleep=clock.sleep,
                    monotonic=clock.monotonic,
                    wall_clock=clock.wall,
                )
            self.assertEqual("provider_response", captured.exception.category)
            self.assertEqual(1, message_calls)

    def test_truncation_and_http_policy_errors_are_terminal_without_retry(self) -> None:
        cases = (
            ("max_tokens", 200, "provider_response"),
            ("bad_request", 400, "provider_response"),
            ("unauthorized", 401, "authentication"),
            ("payment_required", 402, "provider_response"),
            ("forbidden", 403, "authentication"),
        )
        for kind, status, expected_category in cases:
            with self.subTest(kind=kind), tempfile.TemporaryDirectory() as temporary:
                clock = _Clock()
                message_calls = 0

                def transport(request: object, timeout: float) -> HttpResponse:
                    nonlocal message_calls
                    if request.full_url == TOKEN_ENDPOINT:
                        return HttpResponse(
                            200,
                            {},
                            json.dumps(
                                {
                                    "access_token": "sk-ant-oat01-" + "x" * 32,
                                    "token_type": "Bearer",
                                    "expires_in": 600,
                                    "scope": "workspace:inference",
                                }
                            ).encode(),
                        )
                    message_calls += 1
                    if status != 200:
                        return HttpResponse(
                            status,
                            {},
                            b'{"error":{"message":"sanitized by the client"}}',
                        )
                    body = json.loads(request.data)
                    properties = body["output_config"]["format"]["schema"]["properties"][
                        "verdicts"
                    ]["items"]["properties"]
                    request_pairs = [
                        {"label": label, "capture_id": capture}
                        for label, capture in zip(
                            properties["label"]["enum"],
                            properties["capture_id"]["enum"],
                        )
                    ]
                    envelope = json.loads(
                        _response(request_pairs, model=body["model"])
                    )
                    envelope["stop_reason"] = "max_tokens"
                    return HttpResponse(
                        200,
                        {"request-id": "req_truncated"},
                        json.dumps(envelope).encode(),
                    )

                identity = Path(temporary) / "github.jwt"
                identity.write_text(
                    _jwt(REPOSITORY, now=int(clock.wall())), encoding="ascii"
                )
                with self.assertRaises(ReviewFailure) as captured:
                    run_review(
                        self.handoff,
                        expected_manifest_sha256=self.identity["manifest_sha256"],
                        identity_token_file=identity,
                        federation_rule_id="fdrl_test_rule",
                        organization_id="12345678-1234-1234-1234-123456789abc",
                        service_account_id="svac_visual_review",
                        workspace_id="wrkspc_visual_review",
                        output=Path(temporary) / "never.json",
                        transport=transport,
                        sleep=clock.sleep,
                        monotonic=clock.monotonic,
                        wall_clock=clock.wall,
                    )
                self.assertEqual(expected_category, captured.exception.category)
                self.assertEqual(1, message_calls)

    def test_malformed_provider_envelope_gets_only_one_bounded_retry(self) -> None:
        clock = _Clock()
        malformed_sent = False
        message_calls = 0

        def transport(request: object, timeout: float) -> HttpResponse:
            nonlocal malformed_sent, message_calls
            if request.full_url == TOKEN_ENDPOINT:
                return HttpResponse(
                    200,
                    {},
                    json.dumps(
                        {
                            "access_token": "sk-ant-oat01-" + "x" * 32,
                            "token_type": "Bearer",
                            "expires_in": 600,
                            "scope": "workspace:inference",
                        }
                    ).encode(),
                )
            message_calls += 1
            body = json.loads(request.data)
            properties = body["output_config"]["format"]["schema"]["properties"][
                "verdicts"
            ]["items"]["properties"]
            request_pairs = [
                {"label": label, "capture_id": capture}
                for label, capture in zip(
                    properties["label"]["enum"],
                    properties["capture_id"]["enum"],
                )
            ]
            if not malformed_sent:
                malformed_sent = True
                malformed = json.loads(
                    _response(request_pairs, model=body["model"])
                )
                malformed["usage"]["output_tokens_details"]["thinking_tokens"] = 21
                return HttpResponse(
                    200,
                    {"request-id": "req_ignored_malformed"},
                    json.dumps(malformed).encode(),
                )
            return HttpResponse(
                200,
                {"request-id": f"req_valid_{message_calls}"},
                _response(request_pairs, model=body["model"]),
            )

        with tempfile.TemporaryDirectory() as temporary:
            identity = Path(temporary) / "github.jwt"
            identity.write_text(
                _jwt(REPOSITORY, now=int(clock.wall())), encoding="ascii"
            )
            report = run_review(
                self.handoff,
                expected_manifest_sha256=self.identity["manifest_sha256"],
                identity_token_file=identity,
                federation_rule_id="fdrl_test_rule",
                organization_id="12345678-1234-1234-1234-123456789abc",
                service_account_id="svac_visual_review",
                workspace_id="wrkspc_visual_review",
                output=Path(temporary) / "raw.json",
                transport=transport,
                sleep=clock.sleep,
                jitter=lambda lower, upper: upper,
                monotonic=clock.monotonic,
                wall_clock=clock.wall,
            )
        self.assertEqual(1, report["telemetry"]["retries"])
        self.assertEqual(
            report["telemetry"]["sonnet_calls"] + 1,
            report["telemetry"]["provider_attempts"],
        )
        self.assertEqual(report["telemetry"]["provider_attempts"], message_calls)
        self.assertEqual(200, report["telemetry"]["sonnet_usage"]["input_tokens"])
        self.assertEqual(40, report["telemetry"]["sonnet_usage"]["output_tokens"])
        self.assertIn(30.0, clock.sleeps)

    def test_oidc_claims_must_bind_the_protected_drain_environment(self) -> None:
        for claim, value in (
            ("sub", f"repo:{REPOSITORY}:pull_request"),
            ("ref", "refs/heads/feature"),
            (
                "workflow_ref",
                f"{REPOSITORY}/.github/workflows/untrusted.yml@refs/heads/master",
            ),
            ("workflow_sha", "d" * 40),
            ("event_name", "pull_request"),
        ):
            with self.subTest(claim=claim), tempfile.TemporaryDirectory() as temporary:
                clock = _Clock()
                identity = Path(temporary) / "github.jwt"
                identity.write_text(
                    _jwt(
                        REPOSITORY,
                        now=int(clock.wall()),
                        overrides={claim: value},
                    ),
                    encoding="ascii",
                )
                transport = mock.Mock(side_effect=AssertionError("must not call Anthropic"))
                with self.assertRaises(ReviewFailure) as captured:
                    run_review(
                        self.handoff,
                        expected_manifest_sha256=self.identity["manifest_sha256"],
                        identity_token_file=identity,
                        federation_rule_id="fdrl_test_rule",
                        organization_id="12345678-1234-1234-1234-123456789abc",
                        service_account_id="svac_visual_review",
                        workspace_id="wrkspc_visual_review",
                        output=Path(temporary) / "never.json",
                        transport=transport,
                        sleep=clock.sleep,
                        monotonic=clock.monotonic,
                        wall_clock=clock.wall,
                    )
                self.assertEqual("authentication", captured.exception.category)
                transport.assert_not_called()

    def test_malformed_wif_owner_configuration_is_not_misclassified_as_queue_input(self) -> None:
        clock = _Clock()
        with tempfile.TemporaryDirectory() as temporary:
            identity = Path(temporary) / "github.jwt"
            identity.write_text(_jwt(REPOSITORY, now=int(clock.wall())), encoding="ascii")
            transport = mock.Mock(side_effect=AssertionError("must not call Anthropic"))
            with self.assertRaises(ReviewFailure) as captured:
                run_review(
                    self.handoff,
                    expected_manifest_sha256=self.identity["manifest_sha256"],
                    identity_token_file=identity,
                    federation_rule_id="not-a-federation-rule",
                    organization_id="12345678-1234-1234-1234-123456789abc",
                    service_account_id="svac_visual_review",
                    workspace_id="wrkspc_visual_review",
                    output=Path(temporary) / "never.json",
                    transport=transport,
                    sleep=clock.sleep,
                    monotonic=clock.monotonic,
                    wall_clock=clock.wall,
                )
            self.assertEqual("invalid_configuration", captured.exception.category)
            self.assertEqual("authentication", captured.exception.stage)
            self.assertIs(captured.exception.transient, False)
            transport.assert_not_called()

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

    def test_encoded_request_limit_is_enforced_before_transport(self) -> None:
        _manifest, pairs = validate_handoff(
            self.handoff, self.identity["manifest_sha256"]
        )
        with mock.patch("scripts.visual.review_client.MAX_REQUEST_BYTES", 1):
            with self.assertRaisesRegex(ReviewClientError, "encoded sonnet request"):
                build_request(
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

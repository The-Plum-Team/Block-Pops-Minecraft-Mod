from __future__ import annotations

import re
import unittest
from pathlib import Path


REPO = Path(__file__).resolve().parents[3]
WORKFLOWS = REPO / ".github" / "workflows"
REMOTE_USE = re.compile(r"^\s*uses:\s*([^./\s][^@\s]*)@([^\s#]+)", re.MULTILINE)
CREDENTIAL_SCRUB = (
    "unset ACTIONS_RUNTIME_TOKEN ACTIONS_CACHE_URL ACTIONS_RESULTS_URL "
    "GITHUB_TOKEN GH_TOKEN"
)
RUNNER_COMMAND = re.compile(
    r'(?m)^[ \t]*python3[ \t]+(?:controller/scripts/ci/untrusted_runner\.py|'
    r'"\$CONTROLLER_ROOT/scripts/ci/untrusted_runner\.py")[ \t]+'
    r"(?:run|validate)\b"
)


def _has_credentialless_candidate_boundary(text: str, position: int) -> bool:
    if CREDENTIAL_SCRUB in text[max(0, position - 900) : position]:
        return True
    prefix = text[:position]
    invocations = list(RUNNER_COMMAND.finditer(prefix))
    if not invocations:
        return False
    boundary = invocations[-1].start()
    invocation = prefix[boundary:]
    if re.search(r"(?m)^\s+- name:", invocation):
        return False
    return re.search(r"(?m)(?:^|[ \t])--(?:[ \t]*\\)?[ \t]*$", invocation) is not None


class WorkflowSecurityTests(unittest.TestCase):
    def test_gradle_builders_share_one_noncancelling_repository_queue(self) -> None:
        groups = []
        for name in ("build-gate.yml", "on-demand-e2e.yml"):
            text = (WORKFLOWS / name).read_text("utf-8")
            build = text.split("\n  build:\n", 1)[1].split("    steps:", 1)[0]
            # Read the full four-line job header, not the separate workflow cancellation policy.
            header = build.splitlines()[:4]
            self.assertEqual("    concurrency:", header[0])
            self.assertEqual("      cancel-in-progress: false", header[2])
            self.assertEqual("      queue: max", header[3])
            groups.append(header[1].strip())
        self.assertEqual(["group: blockpops-gradle-build"] * 2, groups)

    def test_only_protected_candidate_gates_trigger_on_prt_and_actions_are_pinned(self) -> None:
        files = [*WORKFLOWS.glob("*.yml"), REPO / ".github/actions/run-packaged-e2e/action.yml"]
        self.assertTrue(files)
        for path in files:
            text = path.read_text("utf-8")
            with self.subTest(path=path.name):
                if path.name in {"build-gate.yml", "on-demand-e2e.yml"}:
                    self.assertIn("\n  pull_request_target:", text)
                    self.assertIn(
                        "types: [opened, synchronize, reopened, edited, labeled, unlabeled]",
                        text,
                    )
                    self.assertNotIn("\n  pull_request:", text)
                else:
                    self.assertNotIn("\n  pull_request_target:", text)
                for action, revision in REMOTE_USE.findall(text):
                    self.assertRegex(revision, r"^[0-9a-f]{40}$", action)

    def test_candidate_gates_have_no_write_permission(self) -> None:
        for name in ("build-gate.yml", "on-demand-e2e.yml"):
            text = (WORKFLOWS / name).read_text("utf-8")
            prefix = text.split("jobs:", 1)[0]
            self.assertNotRegex(prefix, r":\s*write\s*$")
            self.assertIn("pull_request_target:", prefix)
            self.assertNotIn("\n  pull_request:", prefix)

    def test_candidate_processes_cannot_inherit_artifact_or_github_credentials(self) -> None:
        targets = {
            WORKFLOWS / "build-gate.yml": (
                "scripts/release/matrix.py",
                "python3 -m unittest",
                "./gradlew",
                "scripts/release/verify_release.py",
            ),
            WORKFLOWS / "on-demand-e2e.yml": (
                "scripts/release/matrix.py",
                "./gradlew",
                "scripts/release/verify_release.py",
                "scripts/ci/e2e_fanin.py",
                "scripts/pages/evidence.py",
                "scripts/pages/visual_anchor.py",
            ),
            REPO / ".github/actions/run-packaged-e2e/action.yml": (
                "python3 -m pip install",
                "scripts/release/verify_release.py",
                "e2e/orchestrator.py",
            ),
        }
        for path, markers in targets.items():
            text = path.read_text("utf-8")
            for marker in markers:
                positions = [match.start() for match in re.finditer(re.escape(marker), text)]
                self.assertTrue(positions, f"{path.name}: missing {marker}")
                for position in positions:
                    with self.subTest(path=path.name, marker=marker, position=position):
                        self.assertTrue(
                            _has_credentialless_candidate_boundary(text, position),
                            f"{path.name}: {marker} lacks an associated credentialless boundary",
                        )

    def test_direct_host_candidate_marker_without_a_scrub_is_rejected(self) -> None:
        workflow = """jobs:
  build:
    steps:
      - name: Unsafe direct candidate execution
        run: |
          set -euo pipefail
          ./gradlew check
"""
        self.assertFalse(
            _has_credentialless_candidate_boundary(workflow, workflow.index("./gradlew"))
        )

    def test_quoted_controller_runner_path_preserves_candidate_boundary(self) -> None:
        action = r'''run: |
  python3 "$CONTROLLER_ROOT/scripts/ci/untrusted_runner.py" run \
    --root "$SANDBOX_ROOT" -- \
    bash -euo pipefail -c '
      python3 e2e/orchestrator.py
    '
'''
        self.assertTrue(
            _has_credentialless_candidate_boundary(
                action, action.index("e2e/orchestrator.py")
            )
        )

    def test_build_and_e2e_authenticate_loader_bootstrap_before_gradle(self) -> None:
        for name in ("build-gate.yml", "on-demand-e2e.yml"):
            text = (WORKFLOWS / name).read_text("utf-8")
            with self.subTest(name=name):
                bootstrap = text.index("scripts/ci/loader_bootstrap.py")
                gradle = text.index("./gradlew")
                self.assertLess(bootstrap, gradle)
                self.assertIn(
                    "BLOCKPOPS_TESTED_SHA: ${{ needs.identity.outputs.tested_sha }}",
                    text,
                )
                self.assertIn('--head-sha "$BLOCKPOPS_TESTED_SHA"', text)
                self.assertNotIn('--head-sha "$GITHUB_SHA"', text)
        attestation = (WORKFLOWS / "verify-gate-attestation.yml").read_text("utf-8")
        self.assertIn("scripts/ci/loader_bootstrap.py", attestation)
        self.assertIn('--head-sha "$TARGET_SHA"', attestation)

    def test_candidate_gate_identity_and_concurrency_use_logical_tested_sources(self) -> None:
        expected_groups = {
            "build-gate.yml": (
                "group: build-gate-${{ inputs.expected_sha || inputs.attest_target_sha || "
                "github.event.pull_request.number || github.ref }}"
            ),
            "on-demand-e2e.yml": (
                "group: packaged-e2e-${{ inputs.expected_sha || inputs.attest_target_sha || "
                "github.event.pull_request.number || github.ref }}"
            ),
        }
        for name, concurrency in expected_groups.items():
            text = (WORKFLOWS / name).read_text("utf-8")
            identity = text.split("  identity:", 1)[1].split("\n  build:", 1)[0]
            with self.subTest(name=name):
                self.assertIn("pull_request_target:", text.split("permissions:", 1)[0])
                self.assertIn(concurrency, text)
                self.assertIn("pr_gate.py resolve-source", identity)
                self.assertIn("pr_gate.py resolve-dispatch-source", identity)
                self.assertIn('if [[ "$GITHUB_EVENT_NAME" == pull_request_target ]]', identity)
                self.assertIn(
                    'elif [[ "$GITHUB_EVENT_NAME" == workflow_dispatch && -n "$EXPECTED_SHA" ]]',
                    identity,
                )
                self.assertIn("ref: ${{ github.sha }}", identity)
                self.assertIn('--implementation-sha "$GITHUB_SHA"', identity)

    def test_release_matrix_identity_is_parsed_only_by_the_protected_controller(self) -> None:
        for name in ("build-gate.yml", "on-demand-e2e.yml"):
            text = (WORKFLOWS / name).read_text("utf-8")
            identity = text.split("  identity:", 1)[1].split("\n  build:", 1)[0]
            explicit = identity.split(
                "- name: Authenticate explicit release-sync inputs", 1
            )[1].split("- name: Resolve", 1)[0]
            with self.subTest(name=name):
                self.assertNotIn("release/release-matrix.json", explicit)
                self.assertIn("controller/scripts/release/matrix.py", identity)
                self.assertIn("protected-matrix.json", identity)
                self.assertIn('.branch.role == "release"', identity)
                self.assertIn(".branch.name == $branch", identity)
        e2e = (WORKFLOWS / "on-demand-e2e.yml").read_text("utf-8")
        self.assertIn(
            '--matrix "$GITHUB_WORKSPACE/candidate/release/release-matrix.json"',
            e2e,
        )
        self.assertNotIn(
            'jq -er .branch.name candidate/release/release-matrix.json',
            e2e,
        )
        self.assertNotIn(
            'jq -er .branch.role candidate/release/release-matrix.json',
            e2e,
        )
        self.assertIn('jq -er .branch.name "$RUNNER_TEMP/protected-matrix.json"', e2e)
        self.assertIn('jq -er .branch.role "$RUNNER_TEMP/protected-matrix.json"', e2e)

    def test_gradle_runtime_and_artifact_toolchains_are_matrix_owned(self) -> None:
        for name in ("build-gate.yml", "on-demand-e2e.yml"):
            text = (WORKFLOWS / name).read_text("utf-8")
            with self.subTest(name=name):
                self.assertIn("--kind gradle-java", text)
                self.assertIn("needs.identity.outputs.gradle_java", text)
                self.assertNotIn("needs.identity.outputs.java_versions", text)
        build = (WORKFLOWS / "build-gate.yml").read_text("utf-8")
        identity = build.split("  identity:", 1)[1].split("\n  build:", 1)[0]
        self.assertIn("../controller/scripts/release/matrix.py", identity)
        self.assertIn("--no-source-check --kind gradle-java", identity)
        self.assertNotIn("jq -er '.gradle_java'", identity)
        e2e = (WORKFLOWS / "on-demand-e2e.yml").read_text("utf-8")
        self.assertIn("java-version: ${{ matrix.java }}", e2e)

    def test_verification_failure_diagnostics_never_auto_admit_bytes(self) -> None:
        build = (WORKFLOWS / "build-gate.yml").read_text("utf-8")
        self.assertIn("Report rejected generated mapping hashes without admitting them", build)
        self.assertIn("mappings-layered+hash.*.jar", build)
        self.assertIn('sha256sum "$mapping"', build)
        for path in WORKFLOWS.glob("*.yml"):
            with self.subTest(path=path.name):
                self.assertNotIn("--write-verification-metadata", path.read_text("utf-8"))

    def test_python_gates_install_the_hash_locked_png_decoder(self) -> None:
        build = (WORKFLOWS / "build-gate.yml").read_text("utf-8")
        aggregate = (WORKFLOWS / "on-demand-e2e.yml").read_text("utf-8").split(
            "  aggregate:", 1
        )[1].split("  required:", 1)[0]
        for name, text in (("build", build), ("aggregate", aggregate)):
            with self.subTest(name=name):
                self.assertIn("actions/setup-python@", text)
                self.assertIn("--only-binary=:all: --require-hashes", text)
        self.assertIn("--requirement scripts/pages/requirements.txt", build)
        self.assertIn(
            "--requirement controller/scripts/pages/requirements.txt", aggregate
        )

    def test_writers_do_not_execute_gradle_or_packaged_candidate(self) -> None:
        for name in (
            "sync-release-branches.yml",
            "handle-release-sync-result.yml",
            "reconcile-release-sync.yml",
        ):
            text = (WORKFLOWS / name).read_text("utf-8")
            with self.subTest(name=name):
                self.assertNotIn("./gradlew", text)
                self.assertNotIn("e2e/orchestrator.py", text)
                self.assertNotIn("workflow_dispatch:", text)
                event_block = text.split("permissions:", 1)[0]
                self.assertNotRegex(event_block, r"(?m)^\s{2}(?:push|pull_request):")

    def test_controller_upgrade_writer_reauthenticates_before_status_only_app(self) -> None:
        workflow = (WORKFLOWS / "handle-pr-gate-result.yml").read_text("utf-8")
        evaluator = (REPO / "scripts/ci/pr_gate.py").read_text("utf-8")
        self.assertIn("urllib.request.ProxyHandler({})", evaluator)
        self.assertIn("_NoRedirect()", evaluator)
        self.assertIn("issue_comment:", workflow)
        self.assertIn("- created\n      - edited\n      - deleted", workflow)
        self.assertIn("github.event.comment.user.login == 'AkaNebur'", workflow)
        self.assertIn("github.event.comment.author_association == 'MEMBER'", workflow)
        self.assertIn(
            "github.event.workflow_run.event == 'pull_request_target'", workflow
        )
        self.assertNotIn("\n  pull_request_target:", workflow)
        publish = workflow.split("  publish:", 1)[1]
        token = publish.index("Mint one status-only installation token")
        reauth = publish.index("pr_gate.py reauthorize")
        self.assertLess(reauth, token)
        self.assertIn("ref: ${{ github.sha }}", publish[:token])
        self.assertIn("persist-credentials: false", publish[:token])
        self.assertIn("contents: read", publish[:token])
        self.assertIn("issues: read", publish[:token])
        self.assertIn("pull-requests: read", publish[:token])
        for argument in (
            "--expected-default-branch",
            "--expected-base-branch",
            "--expected-head-branch",
            "--expected-merge-tree",
        ):
            self.assertIn(argument, publish[:token])
        self.assertNotIn("path: candidate", publish)
        self.assertNotIn("./gradlew", publish)
        self.assertNotIn("e2e/orchestrator.py", publish)
        self.assertIn("permission-statuses: write", publish[token:])
        for permission in (
            "permission-actions:",
            "permission-contents:",
            "permission-issues:",
            "permission-pull-requests:",
        ):
            self.assertNotIn(permission, publish[token:])
        status = publish.split("Publish only the two fixed exact-head contexts", 1)[1]
        self.assertNotIn("github.token", status)
        self.assertIn("GH_TOKEN: ${{ steps.app-token.outputs.token }}", status)

    def test_required_contexts_and_distinct_bridges_are_stable(self) -> None:
        build = (WORKFLOWS / "build-gate.yml").read_text("utf-8")
        e2e = (WORKFLOWS / "on-demand-e2e.yml").read_text("utf-8")
        sync = (WORKFLOWS / "handle-release-sync-result.yml").read_text("utf-8")
        self.assertIn("name: Build and verify", build)
        self.assertIn("name: Packaged E2E gate", e2e)
        self.assertIn("Release sync / Build and verify", sync)
        self.assertIn("Release sync / Packaged E2E gate", sync)
        self.assertIn(
            "name: staged-release-bundle-${{ needs.identity.outputs.tested_sha }}-${{ github.run_attempt }}",
            build,
        )

    def test_e2e_lanes_are_never_overlaid_and_gate_requires_exact_fan_in(self) -> None:
        workflow = (WORKFLOWS / "on-demand-e2e.yml").read_text("utf-8")
        action = (REPO / ".github/actions/run-packaged-e2e/action.yml").read_text(
            "utf-8"
        )
        self.assertIn("name: Validate and aggregate packaged evidence", workflow)
        self.assertIn(
            "name: packaged-e2e-${{ needs.identity.outputs.tested_sha }}-${{ github.run_attempt }}-aggregate",
            workflow,
        )
        self.assertIn(
            "evidence-name: packaged-e2e-${{ needs.identity.outputs.tested_sha }}-${{ github.run_attempt }}-${{ matrix.id }}",
            workflow,
        )
        self.assertIn(
            "name: e2e-input-bundle-${{ needs.identity.outputs.tested_sha }}-${{ github.run_attempt }}",
            workflow,
        )
        self.assertIn("merge-multiple: false", workflow)
        self.assertNotIn("merge-multiple: true", workflow)
        self.assertIn("digest-mismatch: error", action)
        aggregate = workflow.split("  aggregate:", 1)[1].split("  required:", 1)[0]
        self.assertIn("actions/setup-python@", aggregate)
        protected_requirements = "--requirement controller/scripts/pages/requirements.txt"
        self.assertIn(protected_requirements, aggregate)
        self.assertLess(
            aggregate.index(protected_requirements),
            aggregate.index("scripts/ci/e2e_fanin.py create"),
        )
        required = workflow.split("  required:", 1)[1].split("  attest:", 1)[0]
        self.assertIn("needs: [identity, build, e2e, aggregate]", required)
        self.assertIn('[[ "$AGGREGATE_RESULT" == success ]]', required)
        public = workflow.split("  public-evidence:", 1)[1]
        self.assertIn("scripts/ci/e2e_fanin.py validate", public)
        self.assertIn(
            "packaged-e2e-${{ inputs.attest_sha || github.sha }}-${{",
            public,
        )
        self.assertIn(
            "inputs.attest_run_attempt || github.run_attempt }}-aggregate",
            public,
        )

    def test_runtime_and_validator_use_the_same_caller_owned_anchor_projection(self) -> None:
        action = (REPO / ".github/actions/run-packaged-e2e/action.yml").read_text("utf-8")
        runtime = action.split("    - name: Reverify and launch", 1)[1].split("    - name: Kill and lock", 1)[0]
        self.assertIn("BLOCKPOPS_PROJECTION: ${{ inputs.projection }}", runtime)
        self.assertIn("--pass-env BLOCKPOPS_PROJECTION", runtime)
        self.assertIn('--projection "$BLOCKPOPS_PROJECTION"', runtime)
        validator = action.split("    - name: Revalidate passing lane evidence", 1)[1].split(
            "    - name: Upload bounded packaged evidence", 1)[0]
        self.assertIn("E2E_PROJECTION: ${{ inputs.projection }}", validator)
        self.assertIn('--projection "$2"', validator)
        self.assertIn("' _ \"$repository\" \"$E2E_PROJECTION\"", validator)

    def test_all_candidate_execution_uses_protected_sandbox_controller_and_fresh_validation(self) -> None:
        build = (WORKFLOWS / "build-gate.yml").read_text("utf-8")
        e2e = (WORKFLOWS / "on-demand-e2e.yml").read_text("utf-8")
        action = (REPO / ".github/actions/run-packaged-e2e/action.yml").read_text("utf-8")
        for name, text in (("build", build), ("e2e", e2e)):
            with self.subTest(name=name):
                self.assertIn('--controller-source "$GITHUB_WORKSPACE/controller"', text)
                self.assertIn("untrusted_runner.py run", text)
                self.assertIn("untrusted_runner.py seal", text)
                self.assertIn("untrusted_runner.py validate", text)
                self.assertIn("ref: ${{ needs.identity.outputs.controller_sha }}", text)
        self.assertIn('--overlay "$RUNNER_TEMP/blockpops-e2e-bundle" build/release', e2e)
        self.assertIn('--overlay "$RUNNER_TEMP/blockpops-e2e-lanes" packaged-e2e-lanes', e2e)
        self.assertIn('--controller-source "$CONTROLLER_ROOT"', action)
        self.assertIn('--overlay "$RUNNER_TEMP/blockpops-e2e-bundle" build/release', action)
        self.assertRegex(action, r'untrusted_runner\.py" validate\b')
        self.assertIn("steps.validate-runtime.outcome == 'success'", action)

    def test_release_attestation_runs_from_default_and_reauthenticates_controller_attempt(self) -> None:
        attestation = (WORKFLOWS / "verify-gate-attestation.yml").read_text("utf-8")
        handler = (WORKFLOWS / "handle-release-sync-result.yml").read_text("utf-8")
        self.assertIn('jq -n --arg ref "$DEFAULT_BRANCH"', handler)
        self.assertIn("attest_release_branch:$release_branch", handler)
        self.assertIn("attest_controller_sha:$controller_sha", handler)
        self.assertIn('[[ "$GITHUB_REF_NAME" == "$DEFAULT_BRANCH" ]]', attestation)
        self.assertIn(
            '"repos/$GITHUB_REPOSITORY/actions/runs/$SOURCE_RUN_ID/attempts/$SOURCE_RUN_ATTEMPT"',
            attestation,
        )
        self.assertIn(".head_branch == $branch and .head_sha == $controller", attestation)
        self.assertIn(".display_title == $title", attestation)
        self.assertIn('test("^sha256:[0-9a-f]{64}$")', attestation)

    def test_candidate_e2e_has_no_notification_write_surface(self) -> None:
        workflow = (WORKFLOWS / "on-demand-e2e.yml").read_text("utf-8")
        self.assertNotIn("notify-pages:", workflow)
        self.assertNotIn("contents: write", workflow)
        self.assertNotIn("actions: write", workflow)
        self.assertNotIn("pull-requests: write", workflow)
        self.assertIn("workflow_run:", (WORKFLOWS / "pages.yml").read_text("utf-8"))
        self.assertIn(
            "workflow_run:", (WORKFLOWS / "visual-review.yml").read_text("utf-8")
        )

    def test_release_handler_authenticates_default_dispatch_and_exact_candidate_evidence(self) -> None:
        workflow = (WORKFLOWS / "handle-release-sync-result.yml").read_text("utf-8")
        self.assertNotIn("--delete-branch", workflow)
        self.assertIn("group: release-sync-result", workflow)
        self.assertNotIn("group: release-sync-result-${{", workflow)
        self.assertIn("cancel-in-progress: false", workflow)
        route = workflow.split("  route:", 1)[1].split("  handle:", 1)[0]
        handle = workflow.split("  handle:", 1)[1]
        self.assertIn("permissions: {}", workflow.split("jobs:", 1)[0])
        self.assertIn("permissions: {}", route)
        for permission in (
            "actions: write",
            "contents: write",
            "pull-requests: write",
            "statuses: write",
        ):
            self.assertIn(permission, handle)
        self.assertIn('[[ "$requested_sha" =~ ^[0-9a-f]{40}$ ]]', route)
        self.assertIn("requested_sha: ${{ steps.route.outputs.requested_sha }}", route)
        self.assertIn('[[ "$requested_sha" == "$ROUTED_SHA" ]]', workflow)
        self.assertIn("--match-head-commit \"$head_sha\"", workflow)
        self.assertIn(".display_title == $display", workflow)
        self.assertIn('"$EVENT_CONTROLLER_SHA" != "$protected_sha"', workflow)
        self.assertIn('"$EVENT_CONTROLLER_BRANCH" == "$DEFAULT_BRANCH"', workflow)
        self.assertIn("--controller-branch \"$DEFAULT_BRANCH\"", workflow)
        self.assertIn("--controller-sha \"$protected_sha\"", workflow)
        self.assertIn("--tested-branch \"$head_branch\"", workflow)
        self.assertIn("--tested-sha \"$head_sha\"", workflow)
        self.assertIn(
            "runs?event=workflow_dispatch&head_sha=$protected_sha", workflow
        )
        self.assertNotIn("runs?event=workflow_dispatch&head_sha=$head_sha", workflow)
        self.assertIn("commits/$requested_sha/pulls?per_page=100", workflow)
        self.assertIn("staged-release-bundle-$head_sha-$run_attempt", workflow)
        self.assertIn("packaged-e2e-$head_sha-$run_attempt-aggregate", workflow)
        self.assertEqual(4, workflow.count("validate_run_artifact "))
        self.assertIn("gate_controller.py validate-artifact", workflow)
        self.assertIn('cmp -s "$build_artifact" "$build_artifact_reselected"', workflow)
        self.assertIn('cmp -s "$e2e_artifact" "$e2e_artifact_reselected"', workflow)
        self.assertIn('jq -n --arg ref "$DEFAULT_BRANCH"', workflow)
        self.assertIn("attest_release_branch:$release_branch", workflow)
        self.assertIn("attest_controller_sha:$controller_sha", workflow)
        self.assertIn('event_type:"visual-review-requested"', workflow)
        self.assertIn('source_run_id:$run_id', workflow)
        self.assertIn('source_run_attempt:$run_attempt', workflow)

    def test_visual_review_provider_boundary_is_advisory_and_least_privilege(self) -> None:
        enqueue = (WORKFLOWS / "visual-review.yml").read_text("utf-8")
        drain = (WORKFLOWS / "visual-review-drain.yml").read_text("utf-8")
        client = (REPO / "scripts/visual/review_client.py").read_text("utf-8")
        review = drain.split("  review:", 1)[1].split("  publish:", 1)[0]
        publish = drain.split("  publish:", 1)[1].split("  comment:", 1)[0]
        comment = drain.split("  comment:", 1)[1].split("  cleanup:", 1)[0]

        self.assertIn("permissions: {}", enqueue.split("jobs:", 1)[0])
        self.assertIn("permissions: {}", drain.split("jobs:", 1)[0])
        self.assertIn("group: blockpops-visual-review-global-drain", drain)
        self.assertIn("environment: visual-review", review)
        # The owner's Claude Code token is the one credential; no job mints an OIDC identity.
        self.assertNotIn("id-token: write", drain)
        self.assertEqual(1, drain.count("secrets."))
        self.assertEqual(
            1, review.count("CLAUDE_CODE_OAUTH_TOKEN: ${{ secrets.CLAUDE_CODE_OAUTH_TOKEN }}")
        )
        self.assertNotIn("actions/checkout@", review)
        self.assertIn('GH_TOKEN: ""', review)
        self.assertIn('GITHUB_TOKEN: ""', review)
        for credential in (
            "ANTHROPIC_API_KEY",
            "ANTHROPIC_AUTH_TOKEN",
            "ANTHROPIC_OAUTH_ACCESS_TOKEN",
            "OPENAI_API_KEY",
        ):
            self.assertNotIn(credential, drain)
        self.assertNotIn("issues: write", review)
        self.assertNotIn("issues: write", publish)
        self.assertNotIn('GH_TOKEN: ${{ github.token }}', publish)
        self.assertIn("issues: write", comment)
        self.assertIn('GH_TOKEN: ${{ github.token }}', comment)
        self.assertNotIn("actions/checkout", comment)
        self.assertNotIn("actions/setup-python", comment)
        self.assertNotIn("pip install", comment)
        self.assertNotIn("Pillow", comment)
        self.assertNotIn("visual-review-publication/handoff", comment)
        self.assertIn("artifact-ids: ${{ needs.publish.outputs.report_artifact_id }}", comment)
        self.assertIn("report inventory is not exactly the four bounded publication files", comment)
        self.assertIn("downloaded Markdown differs from the independent safe rendering", comment)
        self.assertIn('actions/artifacts/$REPORT_ARTIFACT_ID', comment)
        self.assertIn('actions/runs/$SOURCE_RUN_ID/attempts/$SOURCE_RUN_ATTEMPT', comment)
        mutable_attempt = comment.index(
            'current_source_run="$(gh api "repos/$GITHUB_REPOSITORY/actions/runs/$SOURCE_RUN_ID")"'
        )
        issue_write = comment.index('gh api --method "$comment_method" "$comment_route"')
        self.assertLess(mutable_attempt, issue_write)
        self.assertIn(
            '[[ "$current_source_identity" == "$historical_source_identity" ]]',
            comment,
        )
        self.assertIn('TRIAGE_MODEL = "claude-opus-5-5"', client)
        self.assertIn('VERIFY_MODEL = "claude-opus-5-5"', client)
        # The model may only read the chunk's own images.
        self.assertIn('"--tools",\n            "Read",', client)
        self.assertIn('*(f"Read(./{path})" for path in images)', client)
        self.assertIn("retention-days: 7", enqueue)
        self.assertGreaterEqual(drain.count("retention-days: 1"), 2)
        self.assertGreaterEqual(drain.count("retention-days: 7"), 2)
        self.assertGreaterEqual(drain.count("retention-days: 30"), 2)


if __name__ == "__main__":
    unittest.main()

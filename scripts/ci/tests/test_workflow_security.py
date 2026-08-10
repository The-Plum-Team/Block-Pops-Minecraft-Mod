from __future__ import annotations

import re
import unittest
from pathlib import Path


REPO = Path(__file__).resolve().parents[3]
WORKFLOWS = REPO / ".github" / "workflows"
REMOTE_USE = re.compile(r"^\s*uses:\s*([^./\s][^@\s]*)@([^\s#]+)", re.MULTILINE)


class WorkflowSecurityTests(unittest.TestCase):
    def test_no_pull_request_target_or_floating_actions(self) -> None:
        files = [*WORKFLOWS.glob("*.yml"), REPO / ".github/actions/run-packaged-e2e/action.yml"]
        self.assertTrue(files)
        for path in files:
            text = path.read_text("utf-8")
            with self.subTest(path=path.name):
                self.assertNotIn("pull_request_target", text)
                for action, revision in REMOTE_USE.findall(text):
                    self.assertRegex(revision, r"^[0-9a-f]{40}$", action)

    def test_candidate_gates_have_no_write_permission(self) -> None:
        for name in ("build-gate.yml", "on-demand-e2e.yml"):
            text = (WORKFLOWS / name).read_text("utf-8")
            prefix = text.split("jobs:", 1)[0]
            self.assertNotRegex(prefix, r":\s*write\s*$")
            self.assertNotIn("pull_request_target", prefix)

    def test_build_and_e2e_authenticate_loader_bootstrap_before_gradle(self) -> None:
        for name in ("build-gate.yml", "on-demand-e2e.yml"):
            text = (WORKFLOWS / name).read_text("utf-8")
            with self.subTest(name=name):
                bootstrap = text.index("scripts/ci/loader_bootstrap.py")
                gradle = text.index("./gradlew")
                self.assertLess(bootstrap, gradle)
                self.assertIn("BLOCKPOPS_TESTED_SHA: ${{ github.sha }}", text)
                self.assertIn('--head-sha "$BLOCKPOPS_TESTED_SHA"', text)
                self.assertNotIn('--head-sha "$GITHUB_SHA"', text)
        attestation = (WORKFLOWS / "verify-gate-attestation.yml").read_text("utf-8")
        self.assertIn("scripts/ci/loader_bootstrap.py", attestation)
        self.assertIn('--head-sha "$TARGET_SHA"', attestation)

    def test_gradle_runtime_and_artifact_toolchains_are_matrix_owned(self) -> None:
        for name in ("build-gate.yml", "on-demand-e2e.yml"):
            text = (WORKFLOWS / name).read_text("utf-8")
            with self.subTest(name=name):
                self.assertIn("--kind gradle-java", text)
                self.assertIn("needs.identity.outputs.gradle_java", text)
                self.assertNotIn("needs.identity.outputs.java_versions", text)
        e2e = (WORKFLOWS / "on-demand-e2e.yml").read_text("utf-8")
        self.assertIn("java-version: ${{ matrix.java }}", e2e)

    def test_python_gates_install_the_hash_locked_png_decoder(self) -> None:
        build = (WORKFLOWS / "build-gate.yml").read_text("utf-8")
        aggregate = (WORKFLOWS / "on-demand-e2e.yml").read_text("utf-8").split(
            "  aggregate:", 1
        )[1].split("  required:", 1)[0]
        for name, text in (("build", build), ("aggregate", aggregate)):
            with self.subTest(name=name):
                self.assertIn("actions/setup-python@", text)
                self.assertIn("--only-binary=:all: --require-hashes", text)
                self.assertIn("--requirement scripts/pages/requirements.txt", text)

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

    def test_required_contexts_and_distinct_bridges_are_stable(self) -> None:
        build = (WORKFLOWS / "build-gate.yml").read_text("utf-8")
        e2e = (WORKFLOWS / "on-demand-e2e.yml").read_text("utf-8")
        sync = (WORKFLOWS / "handle-release-sync-result.yml").read_text("utf-8")
        self.assertIn("name: Build and verify", build)
        self.assertIn("name: Packaged E2E gate", e2e)
        self.assertIn("Release sync / Build and verify", sync)
        self.assertIn("Release sync / Packaged E2E gate", sync)
        self.assertIn(
            "name: staged-release-bundle-${{ github.sha }}-${{ github.run_attempt }}",
            build,
        )

    def test_e2e_lanes_are_never_overlaid_and_gate_requires_exact_fan_in(self) -> None:
        workflow = (WORKFLOWS / "on-demand-e2e.yml").read_text("utf-8")
        action = (REPO / ".github/actions/run-packaged-e2e/action.yml").read_text(
            "utf-8"
        )
        self.assertIn("name: Validate and aggregate packaged evidence", workflow)
        self.assertIn(
            "name: packaged-e2e-${{ github.sha }}-${{ github.run_attempt }}-aggregate",
            workflow,
        )
        self.assertIn(
            "evidence-name: packaged-e2e-${{ github.sha }}-${{ github.run_attempt }}-${{ matrix.id }}",
            workflow,
        )
        self.assertIn(
            "name: e2e-input-bundle-${{ github.sha }}-${{ github.run_attempt }}",
            workflow,
        )
        self.assertIn("merge-multiple: false", workflow)
        self.assertNotIn("merge-multiple: true", workflow)
        self.assertIn("digest-mismatch: error", action)
        aggregate = workflow.split("  aggregate:", 1)[1].split("  required:", 1)[0]
        self.assertIn("actions/setup-python@", aggregate)
        self.assertIn("--requirement scripts/pages/requirements.txt", aggregate)
        self.assertLess(
            aggregate.index("--requirement scripts/pages/requirements.txt"),
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

    def test_release_handler_keeps_the_exact_visual_source_ref_and_dispatches_advisory_review(self) -> None:
        workflow = (WORKFLOWS / "handle-release-sync-result.yml").read_text("utf-8")
        self.assertNotIn("--delete-branch", workflow)
        self.assertIn("--match-head-commit \"$head_sha\"", workflow)
        self.assertIn('event_type:"visual-review-requested"', workflow)
        self.assertIn('source_run_id:$run_id', workflow)


if __name__ == "__main__":
    unittest.main()

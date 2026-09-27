from __future__ import annotations

import json
import os
import re
import shutil
import stat
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

from scripts.ci import mod_base_boundary, mod_base_kit


REPO = Path(__file__).resolve().parents[3]
WORKFLOWS = REPO / ".github" / "workflows"
REMOTE_USE = re.compile(r"^\s*uses:\s*([^./\s][^@\s]*)@([^\s#]+)", re.MULTILINE)
CREDENTIAL_SCRUB = (
    "unset ACTIONS_RUNTIME_TOKEN ACTIONS_CACHE_URL ACTIONS_RESULTS_URL "
    "GITHUB_TOKEN GH_TOKEN"
)
SCRUBBED_CREDENTIALS = frozenset(CREDENTIAL_SCRUB.split()[1:])
PREPARE_EVIDENCE = "The-Plum-Team/mod-base/actions/prepare-evidence@"
KIT_PIN_LINE = re.compile(
    r"^\s*(?:-\s+)?uses: The-Plum-Team/mod-base/(\S+)@([0-9a-f]{40}) # (v\d+\.\d+\.\d+)$"
)
VERIFY_STEP = "Verify the candidate's mod-base pin (controller-side)"
SETUP_STEP = "Verify the controller-pinned mod-base kit"
STAGE_STEP = "Stage the controller-verified kit for the candidate sandbox"
PREPARE_STEP = "Prepare the isolated candidate filesystem and OS identity"
SANDBOX_STEP = "Validate and build entirely inside the credentialless account"
IMPORT_ROOT_STEP = "Refuse importable entries at the candidate root (controller-side)"
COMPOSITE_STEP = "Check the staged kit's evidence composite (controller-side)"
KIT_OVERLAY = '--overlay "$RUNNER_TEMP/mod-base-kit" out/mod-base-kit'
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


def _kit_root() -> Path:
    """The verified pinned kit root, found only through the managed bootstrap.

    Protected controller Python never imports the candidate-owned ``tests`` package; an unavailable
    kit raises here and fails the test, never skips it.
    """

    return Path(mod_base_kit.kit_path(REPO))


def _enclosing_step(text: str, position: int) -> str:
    """The complete ``- name:`` step of a workflow or action that contains ``position``."""

    starts = list(re.finditer(r"(?m)^( *)- name: ", text[:position]))
    if not starts:
        raise AssertionError("the position lies outside every step")
    start = starts[-1]
    indent = len(start.group(1))
    lines = text[start.start() :].splitlines(keepends=True)
    step = [lines[0]]
    for line in lines[1:]:
        stripped = line.lstrip(" ")
        depth = len(line) - len(stripped)
        if stripped.strip() and (depth < indent or (depth == indent and stripped.startswith("- "))):
            break
        step.append(line)
    return "".join(step)


def _composite_step_is_credentialless(text: str, position: int) -> bool:
    """Whether the step using the kit composite at ``position`` hands it no credential.

    The composite scrubs every credential before it runs Python; the calling step must not pass one
    in through ``env:`` or ``with:``. Its only token is the one it takes for its own tree check.
    """

    step = _enclosing_step(text, position)
    indent = len(step) - len(step.lstrip(" "))
    if KIT_PIN_LINE.match(step[step.index("\n") + 1 :].split("\n", 1)[0]) is None:
        return False
    keys = re.findall(rf"(?m)^ {{{indent + 2}}}([A-Za-z_-]+):", step)
    if "env" in keys:
        return False
    return not re.search(r"GH_TOKEN|GITHUB_TOKEN|github\.token|secrets\.", step)


def _unset_names(script: str) -> tuple[int, frozenset[str]]:
    """The position and names of the first ``unset`` command of a shell script."""

    match = re.search(r"(?m)^[ \t]*unset ([A-Z_ ]+)$", script)
    if match is None:
        return len(script), frozenset()
    return match.start(), frozenset(match.group(1).split())


def _build_job() -> str:
    text = (WORKFLOWS / "build-gate.yml").read_text("utf-8")
    return text.split("\n  build:\n", 1)[1].split("\n  required:\n", 1)[0]


def _identity_job() -> str:
    text = (WORKFLOWS / "build-gate.yml").read_text("utf-8")
    return text.split("\n  identity:\n", 1)[1].split("\n  build:\n", 1)[0]


def _step(job: str, name: str) -> str:
    marker = f"      - name: {name}\n"
    if job.count(marker) != 1:
        raise AssertionError(f"expected exactly one step {name!r}")
    return job.split(marker, 1)[1].split("\n      - name: ", 1)[0]


def _run_block(step: str) -> str:
    """The shell text of a step's ``run: |`` block, without its YAML indentation."""

    lines = []
    for line in step.split("        run: |\n", 1)[1].splitlines():
        if line and not line.startswith("          "):
            break
        lines.append(line[10:])
    return "\n".join(lines).rstrip("\n") + "\n"


def _sandbox_script(step: str) -> str:
    """The candidate-side script that the sandbox step passes to ``bash -c``."""

    match = re.search(r"bash -euo pipefail -c '\n(.*?)\n            ' _", step, re.S)
    if match is None:
        raise AssertionError("the sandbox step has no bash -c script")
    return match.group(1)


def _hermetic_environment() -> dict[str, str]:
    """The test process environment without startup files or an inherited bytecode setting.

    ``BASH_ENV``/``ENV`` could define shell functions that shadow the PATH stubs (a developer's
    ``gh`` wrapper, for example), and the steps under test must set the bytecode policy themselves.
    """

    dropped = {"BASH_ENV", "ENV", "PYTHONDONTWRITEBYTECODE"}
    return {name: value for name, value in os.environ.items() if name not in dropped}


# Records its argv and bytecode/credential environment, then writes a staged-kit tree whose
# entries the scenario makes irregular, as the real bootstrap would under the step's umask.
FAKE_STAGE = """import json, os, sys
from pathlib import Path
arguments = sys.argv[1:]
with open(os.environ["COMMAND_LOG"], "w", encoding="utf-8") as stream:
    json.dump({"argv": arguments, "bytecode": os.environ.get("PYTHONDONTWRITEBYTECODE"),
               "credentials": sorted(name for name in ("ACTIONS_RUNTIME_TOKEN", "ACTIONS_CACHE_URL",
                                                       "ACTIONS_RESULTS_URL", "GH_TOKEN",
                                                       "GITHUB_TOKEN") if name in os.environ)},
              stream)
scenario = os.environ["STAGE_SCENARIO"]
if scenario == "unavailable":
    sys.exit(2)
if scenario == "missing":
    sys.exit(0)
output = Path(arguments[arguments.index("--output") + 1])
package = output / "src" / "mod_base"
package.mkdir(parents=True)
(package / "__init__.py").write_text("")
(output / "tools").mkdir()
(output / "tools" / "kit_digest.sh").write_text("#!/bin/sh\\n")
(output / "MOD_BASE_KIT.json").write_text("{}\\n")
if scenario == "executable":
    os.chmod(output / "tools" / "kit_digest.sh", 0o755)
elif scenario == "group-writable":
    os.chmod(package / "__init__.py", 0o664)
elif scenario == "open-directory":
    os.chmod(output / "tools", 0o775)
elif scenario == "bytecode-directory":
    (package / "__pycache__").mkdir()
elif scenario == "bytecode-file":
    (package / "__init__.cpython-313.pyc").write_bytes(b"")
elif scenario == "symlink":
    (output / "site").symlink_to(package)
elif scenario != "plain":
    raise SystemExit("unknown scenario")
"""

# Records the argv and credential/bytecode environment of the controller-side pin verification.
FAKE_VERIFY = """import json, os, sys
with open(os.environ["COMMAND_LOG"], "w", encoding="utf-8") as stream:
    json.dump({"argv": sys.argv[1:], "bytecode": os.environ.get("PYTHONDONTWRITEBYTECODE"),
               "credentials": sorted(name for name in ("ACTIONS_RUNTIME_TOKEN", "ACTIONS_CACHE_URL",
                                                       "ACTIONS_RESULTS_URL", "GH_TOKEN",
                                                       "GITHUB_TOKEN") if name in os.environ)},
              stream)
sys.exit(int(os.environ.get("VERIFY_EXIT", "0")))
"""

# Logs every sandbox Python/Gradle process with the bytecode setting it inherited.
FAKE_SANDBOX_TOOL = """import json, os, sys
with open(os.environ["COMMAND_LOG"], "a", encoding="utf-8") as stream:
    stream.write(json.dumps([os.path.basename(sys.argv[0]), os.environ.get("PYTHONDONTWRITEBYTECODE"),
                             *sys.argv[1:]]) + "\\n")
if sys.argv[1:2] == ["scripts/ci/matrix_scope.py"]:
    print(os.environ["MATRIX_SCOPE"])
"""

# A stub gh for the notifier's inline run authentication: it serves one run record and the
# run's artifact listing as consecutive pages, the way ``gh api --paginate`` prints them.
FAKE_GH = """import json, os, sys
arguments = sys.argv[1:]
repository = os.environ["GITHUB_REPOSITORY"]
run = f"repos/{repository}/actions/runs/{os.environ['RUN_ID']}"
if arguments == ["api", f"repos/{repository}", "--jq", ".default_branch"]:
    print("master")
elif arguments == ["api", run]:
    print(os.environ["RUN_RECORD"])
elif arguments == ["api", "--paginate", f"{run}/artifacts?per_page=100"]:
    for page in json.loads(os.environ["ARTIFACT_PAGES"]):
        print(json.dumps(page))
else:
    sys.exit(f"unexpected gh call {arguments}")
"""


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
                # Imports and runs every candidate test module, so it is candidate execution.
                "scripts/ci/parallel_unittest.py",
                "./gradlew",
                "scripts/release/verify_release.py",
            ),
            WORKFLOWS / "on-demand-e2e.yml": (
                "scripts/release/matrix.py",
                "./gradlew",
                "scripts/release/verify_release.py",
                "scripts/ci/e2e_fanin.py",
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
        # Public evidence is now curated by the pinned kit composite, which scrubs every credential
        # itself; the calling step must not hand it one.
        e2e = (WORKFLOWS / "on-demand-e2e.yml").read_text("utf-8")
        positions = [match.start() for match in re.finditer(re.escape(PREPARE_EVIDENCE), e2e)]
        self.assertEqual(1, len(positions))
        self.assertTrue(_composite_step_is_credentialless(e2e, positions[0]))

    def test_every_mod_base_use_is_the_single_released_pin(self) -> None:
        pin = mod_base_kit.parse_pin(REPO)
        files = [*sorted(WORKFLOWS.glob("*.yml")), *sorted((REPO / ".github" / "actions").rglob("action.yml"))]
        pins = set()
        for path in files:
            for number, line in enumerate(path.read_text("utf-8").splitlines(), start=1):
                if "uses:" not in line or "the-plum-team/mod-base" not in line.lower():
                    continue
                with self.subTest(path=path.name, line=number):
                    match = KIT_PIN_LINE.match(line)
                    self.assertIsNotNone(match, line)
                    pins.add((match.group(2), match.group(3)))
        self.assertEqual({(pin.sha, pin.version)}, pins)

    def test_composite_credential_boundary_rejects_a_handed_in_token(self) -> None:
        pin = f"{PREPARE_EVIDENCE}{'a' * 40} # v1.2.3"
        clean = f"""    steps:
      - name: Prepare
        uses: {pin}
        with:
          key: abc
      - name: Next
        run: echo
"""
        self.assertTrue(_composite_step_is_credentialless(clean, clean.index(PREPARE_EVIDENCE)))
        for injected in (
            "        env:\n          GH_TOKEN: ${{ github.token }}\n",
            "          token: ${{ github.token }}\n",
            "          token: ${{ secrets.PAT }}\n",
            "          github-token: $GITHUB_TOKEN\n",
        ):
            with self.subTest(injected=injected):
                unsafe = clean.replace("          key: abc\n", "          key: abc\n" + injected)
                self.assertFalse(
                    _composite_step_is_credentialless(unsafe, unsafe.index(PREPARE_EVIDENCE))
                )
        tagged = clean.replace(f"{'a' * 40} # v1.2.3", "main")
        self.assertFalse(_composite_step_is_credentialless(tagged, tagged.index(PREPARE_EVIDENCE)))

    def test_pinned_prepare_evidence_composite_scrubs_credentials_before_python(self) -> None:
        """The pinned ``prepare-evidence`` composite scrubs every credential before Python.

        This runs against whichever kit root the bootstrap resolves, and none lacks the composites:
        the Build gate's staged overlay ``out/mod-base-kit`` (a controller bootstrap from mod-base
        v0.9.2 stages ``actions/`` bound by its lock, and the Build gate refuses an overlay without
        it), ``MOD_BASE_KIT_PATH``, or the verified user cache that the anonymous fetch fills. The
        adoption's own pull request runs under the previous ``build-gate.yml``, which stages no kit,
        so its tests take the fetch. The Build gate runs the same check controller-side on the staged
        bytes before the sandbox starts (``test_build_checks_the_staged_kit_composite_after_staging``).
        """

        root = _kit_root()
        # Every released kit root binds its actions/ by the lock inside its digested src/ (the
        # overlay resolution has just checked it; a clean checkout of the pin holds it too).
        lock = root.joinpath(*mod_base_kit.ACTIONS_LOCK.split("/"))
        self.assertTrue(lock.is_file() and not lock.is_symlink(), lock)
        self.assertEqual(lock.read_bytes(), mod_base_kit.actions_listing(root))
        action = mod_base_boundary.read_composite(root)
        self.assertEqual([], mod_base_boundary.composite_problems(action))
        # The check is not vacuous on the pinned text: it sees the tree check and the kit steps.
        self.assertIn(mod_base_boundary.TREE_CHECK, action)
        self.assertGreaterEqual(action.count("-m mod_base"), mod_base_boundary.MIN_PYTHON_STEPS - 1)

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

    def test_identity_verifies_the_candidate_pin_with_protected_controller_code(self) -> None:
        identity = _identity_job()
        order = [
            identity.index(f"      - name: {name}\n")
            for name in (
                "Check out the authenticated merge only as candidate data",
                "Require an authenticated eligible source",
                VERIFY_STEP,
                "Resolve matrix-owned Java toolchains",
            )
        ]
        self.assertEqual(sorted(order), order)
        step = _step(identity, VERIFY_STEP)
        self.assertNotIn("        if:", step)
        self.assertIn("        env:\n          GH_TOKEN: ${{ github.token }}\n        run: |\n", step)
        script = _run_block(step)
        self.assertNotIn("${{", script)
        self.assertIn(
            "PYTHONDONTWRITEBYTECODE=1 python3 -P controller/scripts/ci/mod_base_kit.py \\\n"
            "  verify --network --repo candidate\n",
            script,
        )
        self.assertEqual(1, len(re.findall(r"python3\b", script)))
        position, names = _unset_names(script)
        self.assertLess(position, script.index("python3"))
        self.assertEqual(SCRUBBED_CREDENTIALS - {"GH_TOKEN"}, names)
        # No other identity step receives a token for the kit, and the build job never verifies
        # the candidate's pin with candidate code.
        self.assertEqual(1, identity.count("mod_base_kit.py"))
        self.assertNotIn("candidate/scripts/ci/mod_base_kit.py", _build_job())

    def run_verify_step(self, **env: str) -> tuple[subprocess.CompletedProcess[str], dict]:
        return self.run_controller_step(_identity_job(), VERIFY_STEP, **env)

    def run_controller_step(
        self, job: str, name: str, **env: str
    ) -> tuple[subprocess.CompletedProcess[str], dict]:
        """Run one controller step's shell with a recording ``python3`` and every credential set."""

        with tempfile.TemporaryDirectory() as raw:
            root = Path(raw)
            tools = root / "bin"
            tools.mkdir()
            fake = tools / "python3"
            fake.write_text(f"#!{sys.executable}\n{FAKE_VERIFY}", encoding="utf-8")
            fake.chmod(0o755)
            log = root / "verify.json"
            environment = _hermetic_environment()
            environment.update(
                {
                    "PATH": f"{tools}{os.pathsep}{os.environ['PATH']}",
                    "COMMAND_LOG": str(log),
                    "ACTIONS_RUNTIME_TOKEN": "runtime-secret",
                    "ACTIONS_CACHE_URL": "cache-url",
                    "ACTIONS_RESULTS_URL": "results-url",
                    "GITHUB_TOKEN": "github-secret",
                    "GH_TOKEN": "read-only-token",
                    **env,
                }
            )
            result = subprocess.run(
                ["bash", "-euo", "pipefail", "-c", _run_block(_step(job, name))],
                cwd=root,
                env=environment,
                capture_output=True,
                text=True,
                check=False,
            )
            return result, json.loads(log.read_text("utf-8"))

    def test_identity_pin_verification_is_read_only_and_fails_closed(self) -> None:
        result, record = self.run_verify_step()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(
            ["-P", "controller/scripts/ci/mod_base_kit.py", "verify", "--network", "--repo", "candidate"],
            record["argv"],
        )
        self.assertEqual("1", record["bytecode"])
        self.assertEqual(["GH_TOKEN"], record["credentials"])
        result, _record = self.run_verify_step(VERIFY_EXIT="2")
        self.assertEqual(2, result.returncode)

    def assert_credentialless_controller_check(
        self, job: str, name: str, command: str, argv: list[str] | None = None, **env: str
    ) -> None:
        """Check one credential-free boundary step; ``argv`` is ``command`` as the shell expands it."""

        step = _step(job, name)
        self.assertNotIn("        if:", step)
        self.assertNotIn("        env:", step)
        self.assertIn("        shell: bash\n        run: |\n", step)
        script = _run_block(step)
        self.assertNotIn("${{", script)
        self.assertIn(
            "PYTHONDONTWRITEBYTECODE=1 python3 -P controller/scripts/ci/mod_base_boundary.py \\\n"
            f"  {command}\n",
            script,
        )
        self.assertEqual(1, len(re.findall(r"python3\b", script)))
        position, names = _unset_names(script)
        self.assertLess(position, script.index("python3"))
        self.assertEqual(SCRUBBED_CREDENTIALS, names)
        result, record = self.run_controller_step(job, name, **env)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(
            ["-P", "controller/scripts/ci/mod_base_boundary.py", *(argv or command.split())], record["argv"]
        )
        self.assertEqual("1", record["bytecode"])
        self.assertEqual([], record["credentials"])
        for status in ("1", "2"):
            with self.subTest(name=name, status=status):
                result, _record = self.run_controller_step(job, name, VERIFY_EXIT=status, **env)
                self.assertEqual(int(status), result.returncode)

    def test_identity_refuses_importable_candidate_root_entries_controller_side(self) -> None:
        identity = _identity_job()
        order = [
            identity.index(f"      - name: {name}\n")
            for name in (VERIFY_STEP, IMPORT_ROOT_STEP, "Resolve matrix-owned Java toolchains")
        ]
        self.assertEqual(sorted(order), order)
        self.assert_credentialless_controller_check(
            identity, IMPORT_ROOT_STEP, "import-root --repo candidate"
        )
        self.assertEqual(1, identity.count("mod_base_boundary.py"))
        self.assertNotIn("candidate/scripts/ci/mod_base_boundary.py", _build_job())

    def test_build_checks_the_staged_kit_composite_after_staging(self) -> None:
        build = _build_job()
        order = [
            build.index(f"      - name: {name}\n")
            for name in (SETUP_STEP, STAGE_STEP, COMPOSITE_STEP, PREPARE_STEP, SANDBOX_STEP)
        ]
        self.assertEqual(sorted(order), order)
        # The check reads the stage output, the exact bytes prepare then copies into the sandbox,
        # and never re-resolves or fetches a kit of its own.
        runner_temp = "/runner/temp"
        self.assert_credentialless_controller_check(
            build,
            COMPOSITE_STEP,
            'composite --kit "$RUNNER_TEMP/mod-base-kit" --candidate-repo candidate',
            ["composite", "--kit", f"{runner_temp}/mod-base-kit", "--candidate-repo", "candidate"],
            RUNNER_TEMP=runner_temp,
        )
        self.assertEqual(1, build.count("mod_base_boundary.py"))
        self.assertNotIn("--controller-repo", _step(build, COMPOSITE_STEP))

    def test_build_stages_the_controller_verified_kit_before_the_sandbox(self) -> None:
        build = _build_job()
        order = [
            build.index(f"      - name: {name}\n")
            for name in ("Install Python", SETUP_STEP, STAGE_STEP, PREPARE_STEP, SANDBOX_STEP)
        ]
        self.assertEqual(sorted(order), order)
        pin = mod_base_kit.parse_pin(REPO)
        setup = _step(build, SETUP_STEP)
        self.assertEqual(
            f"        uses: The-Plum-Team/mod-base/actions/setup@{pin.sha} # {pin.version}\n"
            "        with:\n"
            "          mod-root: controller\n"
            '          install-imaging: "false"\n',
            setup.rstrip("\n") + "\n",
        )
        stage_step = _step(build, STAGE_STEP)
        stage = _run_block(stage_step)
        # Protected controller code only; the read-only token raises the API rate limit of the
        # release checks of a changed pin, and every other credential is gone before Python.
        self.assertIn("        env:\n          GH_TOKEN: ${{ github.token }}\n        run: |\n", stage_step)
        self.assertNotIn("${{", stage)
        position, names = _unset_names(stage)
        self.assertEqual(SCRUBBED_CREDENTIALS - {"GH_TOKEN"}, names)
        self.assertLess(position, stage.index("python3"))
        self.assertEqual(
            ["python3 -P controller/scripts/ci/mod_base_kit.py stage \\"],
            re.findall(r"python3\b[^\n]*", stage),
        )
        self.assertIn(
            "--controller-repo controller --candidate-repo candidate \\\n"
            '  --output "$RUNNER_TEMP/mod-base-kit"',
            stage,
        )
        self.assertIn("umask 022\n", stage)
        self.assertLess(stage.index("umask 022\n"), stage.index("python3"))
        self.assertIn("PYTHONDONTWRITEBYTECODE=1 python3 -P", stage)
        prepare = _step(build, PREPARE_STEP)
        self.assertEqual(1, prepare.count(KIT_OVERLAY))
        self.assertLess(
            prepare.index('--controller-source "$GITHUB_WORKSPACE/controller"'),
            prepare.index(KIT_OVERLAY),
        )
        # Only the stage writes the overlay source, only prepare copies it into the sandbox, and
        # between them only the protected composite check reads it.
        for name in re.findall(r"(?m)^      - name: (.+)$", build):
            if name not in {STAGE_STEP, PREPARE_STEP, COMPOSITE_STEP}:
                with self.subTest(step=name):
                    self.assertNotIn("$RUNNER_TEMP/mod-base-kit", _step(build, name))
        self.assertEqual(
            ['--kit "$RUNNER_TEMP/mod-base-kit"'],
            re.findall(r'\S*\s*"\$RUNNER_TEMP/mod-base-kit"', _step(build, COMPOSITE_STEP)),
        )

    def test_candidate_kit_imports_run_without_bytecode(self) -> None:
        script = _sandbox_script(_step(_build_job(), SANDBOX_STEP))
        export = script.index("export PYTHONDONTWRITEBYTECODE=1\n")
        self.assertLess(export, script.index("python3"))
        self.assertLess(export, script.index("scripts/ci/parallel_unittest.py"))
        with tempfile.TemporaryDirectory() as raw:
            root = Path(raw)
            log = root / "commands.jsonl"
            for name in ("python3", "gradlew"):
                tool = root / name
                tool.write_text(f"#!{sys.executable}\n{FAKE_SANDBOX_TOOL}", encoding="utf-8")
                tool.chmod(0o755)
            for scope in ("unscoped", "legacy"):
                with self.subTest(scope=scope):
                    environment = _hermetic_environment()
                    environment.update(
                        {
                            "PATH": f"{root}{os.pathsep}{os.environ['PATH']}",
                            "COMMAND_LOG": str(log),
                            "MATRIX_SCOPE": scope,
                            "JAVA_HOME": str(root),
                            "GRADLE_USER_HOME": str(root),
                            "BLOCKPOPS_TESTED_SHA": "a" * 40,
                        }
                    )
                    result = subprocess.run(
                        ["bash", "-euo", "pipefail", "-c", script, "_", str(root)],
                        cwd=root,
                        env=environment,
                        capture_output=True,
                        text=True,
                        check=False,
                    )
                    self.assertEqual(0, result.returncode, result.stderr)
                    rows = [json.loads(row) for row in log.read_text("utf-8").splitlines()]
                    log.unlink()
                    self.assertIn(
                        ["python3", "1", "scripts/ci/parallel_unittest.py"],
                        [row[:3] for row in rows],
                    )
                    self.assertEqual([], [row for row in rows if row[1] != "1"])

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
        # Pages is woken only by workflow_dispatch; workflow_run is a signal in notify-pages.yml.
        self.assertNotIn("workflow_run:", (WORKFLOWS / "pages.yml").read_text("utf-8"))
        self.assertIn("workflow_run:", (WORKFLOWS / "notify-pages.yml").read_text("utf-8"))
        self.assertIn(
            "workflow_run:", (WORKFLOWS / "visual-review.yml").read_text("utf-8")
        )

    def test_pages_notifier_is_a_checkout_free_actions_write_signal(self) -> None:
        text = (WORKFLOWS / "notify-pages.yml").read_text("utf-8")
        head, jobs = text.split("\njobs:\n", 1)
        self.assertEqual(
            "  workflow_run:\n    workflows: [Packaged E2E]\n    types: [completed]\n",
            head.split("\non:\n", 1)[1].split("permissions:", 1)[0],
        )
        self.assertIn("\npermissions: {}", head)
        # One job, which only dispatches: no checkout, no candidate code, no other write.
        self.assertEqual(["notify"], re.findall(r"(?m)^  ([a-z0-9-]+):$", jobs))
        self.assertIn("    permissions:\n      actions: write\n    steps:\n", jobs)
        self.assertEqual(1, len(re.findall(r":\s*write\b", jobs)))
        self.assertNotIn("actions/checkout@", text)
        self.assertNotIn("pull_request", text)
        self.assertNotIn("secrets.", text)
        self.assertIn("github.event.workflow_run.conclusion == 'success'", jobs)
        self.assertIn(
            "(github.event.workflow_run.event == 'schedule' || "
            "github.event.workflow_run.event == 'workflow_dispatch')",
            jobs,
        )
        # Event data reaches shell only through env; nothing interpolates it into run: text.
        for script in re.findall(r"(?ms)^        run: \|\n(.*?)(?=^      - |\Z)", jobs):
            self.assertNotIn("${{", script)
        uses = [line.strip() for line in jobs.splitlines() if line.strip().startswith("uses:")]
        pin = mod_base_kit.parse_pin(REPO)
        self.assertEqual(
            [f"uses: The-Plum-Team/mod-base/actions/notify-pages@{pin.sha} # {pin.version}"], uses
        )
        wake = jobs.split("      - name: Dispatch the separately locked Pages publication\n", 1)[1]
        # Only an authenticated wake (the step printed sha=) reaches the dispatch.
        self.assertEqual(
            "        if: steps.source.outputs.sha != ''\n"
            f"        uses: The-Plum-Team/mod-base/actions/notify-pages@{pin.sha} # {pin.version}\n"
            "        with:\n          operation: deploy\n"
            "          run-id: ${{ github.event.workflow_run.id }}\n"
            "          sha: ${{ steps.source.outputs.sha }}\n",
            wake,
        )

    def run_notifier_authentication(
        self, run_id: str = "42", artifact_pages=None, **record_overrides
    ) -> subprocess.CompletedProcess[str]:
        text = (WORKFLOWS / "notify-pages.yml").read_text("utf-8")
        step = text.split("      - name: Authenticate the completed packaged run\n", 1)[1]
        script = _run_block(step.split("\n      - name: ", 1)[0])
        run = {
            "id": 42,
            "status": "completed",
            "conclusion": "success",
            "path": ".github/workflows/on-demand-e2e.yml",
            "event": "schedule",
            "head_branch": "master",
            "head_repository": {"full_name": "owner/repo"},
            "head_sha": "a" * 40,
            "run_attempt": 1,
        }
        run.update(record_overrides)
        if artifact_pages is None:
            artifact_pages = [
                {"artifacts": [{"name": "mb-cache-unrelated", "expired": False}]},
                {"artifacts": [{"name": f"mb-handoff--{'b' * 24}--a1", "expired": False}]},
            ]
        with tempfile.TemporaryDirectory() as raw:
            root = Path(raw)
            gh = root / "gh"
            gh.write_text(f"#!{sys.executable}\n{FAKE_GH}", encoding="utf-8")
            gh.chmod(0o755)
            output = root / "output"
            environment = {
                **_hermetic_environment(),
                "PATH": f"{root}{os.pathsep}{os.environ['PATH']}",
                "GITHUB_REPOSITORY": "owner/repo",
                "GITHUB_OUTPUT": str(output),
                "RUN_ID": run_id,
                "RUN_RECORD": json.dumps(run),
                "ARTIFACT_PAGES": json.dumps(artifact_pages),
            }
            result = subprocess.run(
                ["bash", "-euo", "pipefail", "-c", script],
                cwd=root,
                env=environment,
                capture_output=True,
                text=True,
                check=False,
            )
            result.log = result.stdout
            result.stdout = output.read_text("utf-8") if output.exists() else ""
        return result

    @unittest.skipUnless(shutil.which("jq"), "the notifier's inline authentication needs jq")
    def test_pages_notifier_wakes_only_for_an_exact_successful_default_branch_run(self) -> None:
        for event in ("schedule", "workflow_dispatch"):
            with self.subTest(event=event):
                result = self.run_notifier_authentication(event=event)
                self.assertEqual(0, result.returncode, result.stderr)
                self.assertEqual(f"sha={'a' * 40}\n", result.stdout)
        rejected = (
            {"status": None},
            {"status": 1},
            {"conclusion": 0},
            {"conclusion": ["success"]},
            {"event": "pull_request_target"},
            {"path": ".github/workflows/build-gate.yml"},
            {"head_branch": None},
            {"head_repository": {"full_name": "attacker/repo"}},
            {"head_sha": "A" * 40},
            {"id": 43},
            {"run_attempt": 0},
            {"run_attempt": "1"},
            {"run_id": "42 --jq ."},
            {"run_id": "042"},
        )
        for overrides in rejected:
            with self.subTest(overrides=overrides):
                result = self.run_notifier_authentication(**overrides)
                self.assertNotEqual(0, result.returncode)
                self.assertEqual("", result.stdout)
        # The latest attempt's handoff is what the publisher admits, across paginated listings.
        result = self.run_notifier_authentication(
            run_attempt=2,
            artifact_pages=[
                {"artifacts": [{"name": f"mb-handoff--{'b' * 24}--a1", "expired": False}]},
                {"artifacts": [{"name": f"mb-handoff--{'b' * 24}--a2", "expired": False}]},
            ],
        )
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(f"sha={'a' * 40}\n", result.stdout)

    @unittest.skipUnless(shutil.which("jq"), "the notifier's inline authentication needs jq")
    def test_pages_notifier_leaves_the_site_alone_for_a_run_that_is_not_a_wake(self) -> None:
        # The retired workflow_run publisher answered these runs with "leaving the previous site
        # unchanged" and success; the notifier keeps that instead of a red publication run.
        handoff = f"mb-handoff--{'b' * 24}--a1"
        quiet = (
            ({"head_branch": "1.21.1-neoforge-fabric"}, None, "is not on master"),
            # A re-run started before this signal executed: the record now describes that attempt.
            ({"status": "in_progress", "conclusion": None, "run_attempt": 2}, None,
             "is in_progress/none in its latest attempt"),
            ({"status": "queued", "conclusion": None}, None, "is queued/none"),
            # Only both fields together make a completed success, whatever the other one says.
            ({"status": "in_progress"}, None, "is in_progress/success"),
            ({"conclusion": "failure", "run_attempt": 2}, None, "is completed/failure"),
            ({"conclusion": "cancelled"}, None, "is completed/cancelled"),
            ({}, [], "handed off no evidence in attempt 1"),
            ({}, [{"artifacts": []}], "handed off no evidence in attempt 1"),
            ({}, [{"artifacts": [{"name": handoff, "expired": True}]}], "handed off no evidence"),
            ({"run_attempt": 2}, [{"artifacts": [{"name": handoff, "expired": False}]}],
             "handed off no evidence in attempt 2"),
            ({}, [{"artifacts": [{"name": f"mb-anchor--{'b' * 24}--{'a' * 40}--42--a1",
                                  "expired": False}]}], "handed off no evidence"),
            ({}, [{"artifacts": [{"name": f"x-{handoff}", "expired": False}]}], "handed off no evidence"),
        )
        for overrides, pages, message in quiet:
            with self.subTest(overrides=overrides, pages=pages):
                result = self.run_notifier_authentication(artifact_pages=pages, **overrides)
                self.assertEqual(0, result.returncode, result.stderr)
                self.assertEqual("", result.stdout)
                self.assertIn(message, result.log)
                self.assertIn("leaving the previous site unchanged", result.log)
        # A listing the notifier cannot read is a failure, never a silent skip.
        result = self.run_notifier_authentication(artifact_pages=[{"artifacts": "none"}])
        self.assertNotEqual(0, result.returncode)
        self.assertEqual("", result.stdout)

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


class ModBaseKitOverlayTests(unittest.TestCase):
    """The ``out/mod-base-kit`` sandbox overlay holds plain 0644 files and never bytecode."""

    SHA = "3" * 40
    VERSION = "v1.2.3"

    def setUp(self) -> None:
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)

    def run_stage_step(self, scenario: str) -> tuple[subprocess.CompletedProcess[str], dict, Path]:
        workspace = self.root / scenario / "workspace"
        runner_temp = self.root / scenario / "runner-temp"
        tools = self.root / scenario / "bin"
        for directory in (workspace, runner_temp, tools):
            directory.mkdir(parents=True)
        fake = tools / "python3"
        fake.write_text(f"#!{sys.executable}\n{FAKE_STAGE}", encoding="utf-8")
        fake.chmod(0o755)
        log = self.root / scenario / "stage.json"
        environment = _hermetic_environment()
        environment.update(
            {
                "PATH": f"{tools}{os.pathsep}{os.environ['PATH']}",
                "RUNNER_TEMP": str(runner_temp),
                "COMMAND_LOG": str(log),
                "STAGE_SCENARIO": scenario,
                "ACTIONS_RUNTIME_TOKEN": "runtime-secret",
                "ACTIONS_CACHE_URL": "cache-url",
                "ACTIONS_RESULTS_URL": "results-url",
                "GH_TOKEN": "read-only-token",
                "GITHUB_TOKEN": "github-secret",
            }
        )
        script = _run_block(_step(_build_job(), STAGE_STEP))
        # A restrictive inherited umask proves that the step itself fixes the staged modes.
        result = subprocess.run(
            ["bash", "-c", 'umask 077 && exec bash -euo pipefail -c "$0"', script],
            cwd=workspace,
            env=environment,
            capture_output=True,
            text=True,
            check=False,
        )
        record = json.loads(log.read_text("utf-8")) if log.exists() else {}
        return result, record, runner_temp / "mod-base-kit"

    def test_stage_step_writes_plain_modes_without_bytecode_or_other_credentials(self) -> None:
        result, record, staged = self.run_stage_step("plain")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(
            [
                "-P",
                "controller/scripts/ci/mod_base_kit.py",
                "stage",
                "--controller-repo",
                "controller",
                "--candidate-repo",
                "candidate",
                "--output",
                str(staged),
            ],
            record["argv"],
        )
        self.assertEqual("1", record["bytecode"])
        self.assertEqual(["GH_TOKEN"], record["credentials"])
        modes = {
            path.relative_to(staged).as_posix(): stat.S_IMODE(path.lstat().st_mode)
            for path in (staged, *staged.rglob("*"))
        }
        self.assertEqual(
            {
                ".": 0o755,
                "MOD_BASE_KIT.json": 0o644,
                "src": 0o755,
                "src/mod_base": 0o755,
                "src/mod_base/__init__.py": 0o644,
                "tools": 0o755,
                "tools/kit_digest.sh": 0o644,
            },
            modes,
        )

    def test_stage_step_rejects_irregular_or_bytecode_entries(self) -> None:
        for scenario in (
            "executable",
            "group-writable",
            "open-directory",
            "bytecode-directory",
            "bytecode-file",
            "symlink",
        ):
            with self.subTest(scenario=scenario):
                result, _record, _staged = self.run_stage_step(scenario)
                self.assertNotEqual(0, result.returncode)
                self.assertIn("are not plain 0644 files without bytecode", result.stderr)

    def test_stage_step_fails_closed_without_a_staged_kit(self) -> None:
        for scenario in ("unavailable", "missing"):
            with self.subTest(scenario=scenario):
                result, record, staged = self.run_stage_step(scenario)
                self.assertNotEqual(0, result.returncode)
                self.assertEqual("1", record["bytecode"])
                self.assertFalse(staged.exists())

    def make_kit(self) -> Path:
        kit = self.root / "kit"
        files = {
            "src/mod_base/__init__.py": b"",
            "src/mod_base/template/__init__.py": b"",
            "site/assets/site.css": b"body{}\n",
            "requirements/pillow.txt": b"pillow==12.3.0\n",
            "template/manifest.json": b"{}\n",
            "tools/kit_digest.sh": b"#!/bin/sh\n",
        }
        for relative, data in files.items():
            path = kit / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(data)
        # Neither an executable source file nor source bytecode may reach the overlay.
        (kit / "tools" / "kit_digest.sh").chmod(0o755)
        (kit / "src" / "mod_base" / "__pycache__").mkdir()
        (kit / "src" / "mod_base" / "__pycache__" / "__init__.cpython-313.pyc").write_bytes(b"planted")
        (kit / mod_base_kit.STAGED_LOCK).write_bytes(mod_base_kit.staged_listing(kit))
        return kit

    def make_repository(self, name: str) -> Path:
        repository = self.root / name
        workflows = repository / ".github" / "workflows"
        workflows.mkdir(parents=True)
        (workflows / "build-gate.yml").write_text(
            "jobs:\n  build:\n    steps:\n"
            f"      - uses: The-Plum-Team/mod-base/actions/setup@{self.SHA} # {self.VERSION}\n",
            encoding="utf-8",
        )
        return repository

    def stage(self, kit: Path, controller: Path, candidate: Path, output: Path) -> Path:
        def offline(path: str) -> None:
            raise AssertionError(f"an equal pin must stage without the API: {path}")

        environment = {
            "CI": "true",
            "MOD_BASE_KIT_PATH": str(kit),
            "MOD_BASE_KIT_SHA": self.SHA,
            "MOD_BASE_CACHE_DIR": str(self.root / "cache"),
        }
        output.parent.mkdir(parents=True, exist_ok=True)
        previous = os.umask(0o022)  # the stage step's umask
        try:
            return mod_base_kit.stage(controller, candidate, output, environment, get_json=offline)
        finally:
            os.umask(previous)

    def test_staged_overlay_is_plain_locked_and_resolved_inside_the_sandbox(self) -> None:
        kit = self.make_kit()
        controller = self.make_repository("controller")
        candidate = self.make_repository("candidate")
        staged = self.stage(kit, controller, candidate, self.root / "runner-temp" / "mod-base-kit")
        for path in (staged, *staged.rglob("*")):
            with self.subTest(path=path.relative_to(staged).as_posix()):
                self.assertNotEqual("__pycache__", path.name)
                self.assertFalse(path.is_symlink())
                expected = 0o755 if path.is_dir() else 0o644
                self.assertEqual(expected, stat.S_IMODE(path.lstat().st_mode))
        stamp = mod_base_kit.read_stamp(staged)
        self.assertEqual((self.SHA, self.VERSION[1:]), (stamp["sha"], stamp["version"]))
        self.assertEqual(mod_base_kit.tree_digest(staged), stamp["tree_digest"])

        # untrusted_runner copies the overlay with ``cp -a``, which keeps these modes, to the
        # generated top-level ``out/`` path that its seal accepts.
        overlay = candidate.joinpath(*mod_base_kit.OVERLAY_PATH)
        self.assertEqual("out/mod-base-kit", KIT_OVERLAY.rsplit(" ", 1)[1])
        self.assertEqual(("out", "mod-base-kit"), mod_base_kit.OVERLAY_PATH)
        overlay.parent.mkdir()
        shutil.copytree(staged, overlay, symlinks=True, copy_function=shutil.copy2)
        resolution = mod_base_kit.resolve(candidate, {"CI": "true"})
        self.assertEqual(("overlay", overlay), (resolution.source, resolution.root))

        source = overlay / "src" / "mod_base" / "__init__.py"
        source.chmod(0o755)
        with self.assertRaisesRegex(mod_base_kit.KitError, "is executable"):
            mod_base_kit.resolve(candidate, {"CI": "true"})
        source.chmod(0o644)
        (overlay / "src" / "mod_base" / "__pycache__").mkdir()
        with self.assertRaisesRegex(mod_base_kit.KitError, "holds bytecode"):
            mod_base_kit.resolve(candidate, {"CI": "true"})
        (overlay / "src" / "mod_base" / "__pycache__").rmdir()
        (overlay / "tools" / "kit_digest.sh").write_bytes(b"#!/bin/sh\nexit 0\n")
        with self.assertRaisesRegex(mod_base_kit.KitError, "staged_files.sha256"):
            mod_base_kit.resolve(candidate, {"CI": "true"})


if __name__ == "__main__":
    unittest.main()

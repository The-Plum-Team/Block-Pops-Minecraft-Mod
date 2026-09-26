"""The Pages caller is the pinned mod-base managed region with least-privilege caller jobs.

Block Pops publishes its site only through ``.github/workflows/pages.yml``, whose managed region is
byte-identical to the pinned mod-base template. These checks read the workflow text as data; the
kit itself is reached only through the verified managed bootstrap. Protected controller Python never
imports the candidate-owned ``tests`` package, so this uses ``mod_base_kit.kit_path`` directly: an
unavailable kit raises and fails the test, never skips it.
"""

from __future__ import annotations

import re
import unittest
from pathlib import Path

from scripts.ci import mod_base_kit


REPO = Path(__file__).resolve().parents[3]
WORKFLOWS = REPO / ".github" / "workflows"
CALLER = WORKFLOWS / "pages.yml"
MANAGED_BEGIN = "# >>> mod-base managed:"
MANAGED_END = "# <<< mod-base managed\n"
EXTENSION_BEGIN = (
    '# >>> mod-local extensions: only jobs whose id starts with "ext-"; '
    "no mod-base uses, no pages/id-token/actions:write\n"
)
EXTENSION_END = "# <<< mod-local extensions\n"
#: Every caller job and exactly the grant its own steps or its callee need.
CALLER_PERMISSIONS = {
    "verify-kit": {"actions": "read", "contents": "read"},
    "publish": {"actions": "read", "contents": "read"},
    "deploy": {"contents": "read", "pages": "write", "id-token": "write"},
    "finalize": {"actions": "read", "contents": "read"},
    "request-rotation": {"actions": "write"},
    "rotate": {"actions": "write", "contents": "read"},
}
CALLER_NAMES = {
    "verify-kit": "Verify pinned mod-base",
    "publish": "Publish",
    "deploy": "Deploy GitHub Pages",
    "finalize": "Finalize",
    "request-rotation": "Request post-success evidence rotation",
    "rotate": "Rotate",
}
#: The callee each reusable job runs, always at the single pin.
CALLEES = {"publish": "publish.yml", "finalize": "finalize.yml", "rotate": "rotate.yml"}
#: Triggers and identities retired from Pages: no event payload may reach the publisher.
FORBIDDEN = (
    "github.event.workflow_run",
    "workflow_run",
    "implementation_sha",
    "pull_request_target",
    "repository_dispatch",
    "quick-skin-pages-wake",
    "pages-deploy",
    "pages-rotate",
    "secrets.",
    "secrets: inherit",
)
#: Every workflow that references the kit, each at the one pin.
PINNED_WORKFLOWS = frozenset(
    {
        ".github/workflows/build-gate.yml",
        ".github/workflows/notify-pages.yml",
        ".github/workflows/on-demand-e2e.yml",
        ".github/workflows/pages.yml",
        ".github/workflows/visual-review-drain.yml",
        ".github/workflows/visual-review.yml",
    }
)


def kit_root() -> Path:
    """The verified pinned kit root (it turns bytecode writing off for this process)."""

    return Path(mod_base_kit.kit_path(REPO))


def caller_text() -> str:
    return CALLER.read_text("utf-8")


def jobs(text: str) -> dict[str, str]:
    """Each top-level job id of ``text`` mapped to its block (the lines below its id)."""

    body = text.split("\njobs:\n", 1)[1].split(MANAGED_END, 1)[0]
    blocks: dict[str, str] = {}
    current = None
    for line in body.splitlines(keepends=True):
        match = re.fullmatch(r"  ([a-z0-9-]+):\n", line)
        if match is not None:
            current = match.group(1)
            if current in blocks:
                raise AssertionError(f"duplicate caller job {current}")
            blocks[current] = ""
        elif current is not None:
            blocks[current] += line
        elif line.strip() and not line.lstrip().startswith("#"):
            raise AssertionError(f"caller content outside a job: {line!r}")
    return blocks


def permissions(block: str) -> dict[str, str]:
    """The job-level ``permissions:`` block mapping (never a shorthand)."""

    lines = block.splitlines()
    starts = [index for index, line in enumerate(lines) if line == "    permissions:"]
    if len(starts) != 1:
        raise AssertionError("a caller job must declare exactly one block permissions mapping")
    grants: dict[str, str] = {}
    for line in lines[starts[0] + 1 :]:
        match = re.fullmatch(r"      ([a-z-]+): (read|write)", line)
        if match is None:
            break
        grants[match.group(1)] = match.group(2)
    return grants


class ManagedCallerTests(unittest.TestCase):
    def test_managed_region_is_the_pinned_kit_template_and_extensions_are_empty(self) -> None:
        pin = mod_base_kit.parse_pin(REPO)
        template = (
            kit_root() / "template" / "managed" / ".github" / "workflows" / "pages.yml"
        ).read_text("utf-8")
        expected = template.replace("{{PIN}}", pin.sha).replace("{{VERSION}}", pin.version)
        text = caller_text()
        self.assertTrue(text.startswith(MANAGED_BEGIN))
        self.assertEqual(1, text.count(MANAGED_END))
        # Block Pops keeps the extension region empty: the managed caller is the whole workflow.
        self.assertEqual(expected, text)
        self.assertTrue(text.endswith(MANAGED_END + EXTENSION_BEGIN + EXTENSION_END))
        self.assertNotIn("{{PIN}}", text)
        self.assertNotIn("{{VERSION}}", text)

    def test_every_kit_reference_carries_the_one_pin(self) -> None:
        pin = mod_base_kit.parse_pin(REPO)
        self.assertRegex(pin.sha, r"^[0-9a-f]{40}$")
        self.assertRegex(pin.version, r"^v(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)$")
        referencing = {reference.rsplit("@", 1)[0] for reference in pin.references}
        self.assertEqual(PINNED_WORKFLOWS, referencing)
        text = caller_text()
        for job, callee in CALLEES.items():
            with self.subTest(job=job):
                line = f"    uses: The-Plum-Team/mod-base/.github/workflows/{callee}@{pin.sha} # {pin.version}\n"
                self.assertEqual(1, jobs(text)[job].count(line))
        self.assertEqual(3, len([ref for ref in pin.references if ref.startswith(".github/workflows/pages.yml@")]))

    def test_pillow_locks_move_in_lockstep_with_the_kit(self) -> None:
        kit_lock = (kit_root() / "requirements" / "pillow.txt").read_text("utf-8")
        stanza = kit_lock.split("\n", 1)[1]
        self.assertTrue(stanza.startswith("Pillow=="))
        kit_hashes = set(re.findall(r"--hash=sha256:([0-9a-f]{64})", stanza))
        self.assertTrue(kit_hashes)
        # The packaged runtime lock carries the kit's stanza byte for byte.
        self.assertIn(stanza, (REPO / "e2e" / "requirements.txt").read_text("utf-8"))
        # The Pages-side lock pins the same release to wheels the kit lock admits.
        pages = (REPO / "scripts" / "pages" / "requirements.txt").read_text("utf-8")
        self.assertEqual(
            re.findall(r"(?m)^Pillow==\S+", stanza), re.findall(r"(?m)^Pillow==\S+", pages)
        )
        pages_hashes = set(re.findall(r"--hash=sha256:([0-9a-f]{64})", pages))
        self.assertTrue(pages_hashes)
        self.assertLessEqual(pages_hashes, kit_hashes)

    def test_caller_jobs_hold_exactly_their_least_privilege_grants(self) -> None:
        text = caller_text()
        self.assertIn("\npermissions: {}\n", text.split("\njobs:\n", 1)[0])
        blocks = jobs(text)
        self.assertEqual(set(CALLER_PERMISSIONS), set(blocks))
        for job, grants in CALLER_PERMISSIONS.items():
            with self.subTest(job=job):
                self.assertEqual(grants, permissions(blocks[job]))
                self.assertIn(f"    name: {CALLER_NAMES[job]}\n", blocks[job])

    def test_only_deploy_mints_pages_or_oidc_credentials_and_it_checks_out_nothing(self) -> None:
        blocks = jobs(caller_text())
        for job, block in blocks.items():
            with self.subTest(job=job):
                holds = "pages: write" in block or "id-token: write" in block
                self.assertEqual(job == "deploy", holds)
                self.assertEqual(job == "deploy", "environment:" in block)
                self.assertNotIn("actions/checkout@", block)
        deploy = blocks["deploy"]
        self.assertIn("      name: github-pages\n", deploy)
        uses = re.findall(r"(?m)^\s+uses: (\S+)", deploy)
        self.assertEqual(
            ["actions/deploy-pages@cd2ce8fcbc39b97be8ca5fce6e763baed58fa128"], uses
        )
        # The head recheck runs before the deployment, in the same job.
        self.assertLess(
            deploy.index("Recheck every published source head immediately before deployment"),
            deploy.index("actions/deploy-pages@"),
        )

    def test_caller_accepts_no_event_payload_and_no_retired_trigger(self) -> None:
        text = caller_text()
        on = text.split("\non:\n", 1)[1].split("\npermissions:", 1)[0]
        self.assertEqual(
            ["schedule", "workflow_dispatch"], re.findall(r"(?m)^  ([a-z_]+):", on)
        )
        self.assertIn('    - cron: "43 * * * *"\n', on)
        self.assertNotIn("workflows:", on)
        for token in FORBIDDEN:
            with self.subTest(token=token):
                self.assertNotIn(token, text)
        self.assertIn("\nname: Project site\n", text)

    def test_publication_and_rotation_use_separate_noncancelling_locks(self) -> None:
        text = caller_text()
        concurrency = text.split("\nconcurrency:\n", 1)[1].split("\njobs:\n", 1)[0]
        self.assertIn("'mod-base-pages-rotation' || 'mod-base-pages-publication'", concurrency)
        self.assertIn("inputs.operation == 'rotate'", concurrency)
        self.assertIn("  cancel-in-progress: false", concurrency)
        # Callee jobs never take their own locks: the caller owns concurrency.
        for block in jobs(text).values():
            self.assertNotIn("concurrency:", block)


if __name__ == "__main__":
    unittest.main()

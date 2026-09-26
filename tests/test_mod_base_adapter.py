"""Block Pops' mod-base adapter and configuration, driven through the pinned kit's own protocol.

Every hook runs exactly as the kit's in-process host runs it (``host_child.run_hook``: the same
argument and result validation as the isolated child) against the real release matrix and scenario
contract, committed as inert Git objects in a throwaway repository, because the adapter may only
read them through ``ctx.read_blob``. The helpers here (:class:`SubjectRepository`,
:func:`call_hook`, :func:`produce_evidence`) are shared with the visual-review tests, which build a
real kit handoff and lossless ``mb-anchor`` with them.
"""

from __future__ import annotations

import copy
import hashlib
import json
import os
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from types import MappingProxyType
from typing import Any
from unittest import mock

from tests import mod_base_path

KIT = mod_base_path.kit_root()

from mod_base.adapter import host, host_child  # noqa: E402
from mod_base.adapter.api import Context  # noqa: E402
from mod_base.adapter.protocol import HookFailed, HookUnsupported  # noqa: E402
from mod_base.config import load_config  # noqa: E402
from mod_base.errors import MbError  # noqa: E402
from mod_base.evidence.anchor import create_anchor  # noqa: E402
from mod_base.evidence.prepare import prepare_handoff  # noqa: E402
from mod_base.imaging.metrics import SizePolicy, inspect_png  # noqa: E402
from mod_base.imaging.png import pattern_png  # noqa: E402
from mod_base.model import grammar  # noqa: E402
from mod_base.model.canonical import canonical_sha256  # noqa: E402
from mod_base.runtime import Invocation  # noqa: E402
from mod_base.template.tool import DEFERRABLE  # noqa: E402

from e2e.scenario_contract import load_contract  # noqa: E402
from scripts.ci import e2e_job_graph  # noqa: E402
from scripts.ci.gate_controller import branch_token  # noqa: E402
from scripts.ci.mod_base_kit import parse_pin  # noqa: E402
from scripts.release.matrix import MAX_MATRIX_BYTES, MatrixDocument, normalize_matrix_inventory  # noqa: E402

REPO = Path(__file__).resolve().parents[1]
MATRIX_PATH = REPO / "release" / "release-matrix.json"
CONTRACT_PATH = REPO / "e2e" / "scenario-contract.json"
REAL_MATRIX = MATRIX_PATH.read_bytes()
REAL_CONTRACT = CONTRACT_PATH.read_bytes()
CONFIG = load_config(REPO)
ADAPTER = host_child.load_adapter(REPO / "scripts" / "pages" / "mod_base_adapter.py")
FIXTURES = host_child.load_adapter(REPO / "scripts" / "pages" / "mod_base_fixtures.py")
REPOSITORY = "The-Plum-Team/Block-Pops-Minecraft-Mod"
SOURCE_WORKFLOW = ".github/workflows/on-demand-e2e.yml"
MASTER_KEY = "fc613b4dfd6736a7bd268c8a"
# The kit commit a producer records is the one pin this checkout declares.
KIT_SHA = parse_pin(REPO).sha
# The root files the template-adoption pull request (PR B) adds; the controller upgrade that adopts
# the kit cannot change them, and the one after PR B empties this list.
PR_B_PATHS = [".gitattributes", ".gitignore", ".github/dependabot.yml", ".github/pull_request_template.md",
              "AGENTS.md"]
LEGACY_NODES = ["fabric-1.20.1", "forge-1.20.1"]
# The palette the retired three-file gallery rendered (site/assets/site.css). bg, text, surface
# (figure/select), surface_raised (the body gradient's first stop), line (borders), muted (advisory
# and caption text), accent (.eyebrow, #status, .meta), accent_strong (links) and image_well (the
# image background) come from it. Its light block overrode only the background, text, surfaces,
# borders and muted text, so the light theme keeps the dark accents and image well, exactly as the
# retired light page showed them. site.css never set surface_soft, highlight or danger; those three
# come from the kit's Block Pops sample configuration.
DARK = {"bg": "#090d17", "surface": "#101827", "surface_raised": "#17213b", "surface_soft": "#1b2640",
        "text": "#ecf2ff", "muted": "#b9c5dc", "line": "#273651", "accent": "#8dd7ff", "accent_strong": "#9adeff",
        "highlight": "#f5c451", "danger": "#ff8f8f", "image_well": "#000000"}
LIGHT = {"bg": "#f4f7fd", "surface": "#ffffff", "surface_raised": "#dbeaff", "surface_soft": "#eef3fb",
         "text": "#101828", "muted": "#475569", "line": "#cbd5e1", "accent": "#8dd7ff", "accent_strong": "#9adeff",
         "highlight": "#b45309", "danger": "#b42318", "image_well": "#000000"}


def git(root: Path, *arguments: str, stdin: bytes | None = None) -> str:
    """Run git in a throwaway repository with no user or system configuration."""

    environment = {"PATH": os.environ.get("PATH", "/usr/bin:/bin"), "HOME": str(root), "LC_ALL": "C",
                   "GIT_CONFIG_NOSYSTEM": "1", "GIT_CONFIG_GLOBAL": os.devnull, "GIT_TERMINAL_PROMPT": "0",
                   "GIT_AUTHOR_NAME": "Fixture", "GIT_AUTHOR_EMAIL": "fixture@example.invalid",
                   "GIT_COMMITTER_NAME": "Fixture", "GIT_COMMITTER_EMAIL": "fixture@example.invalid",
                   "GIT_AUTHOR_DATE": "2026-09-01T12:00:00Z", "GIT_COMMITTER_DATE": "2026-09-01T12:00:00Z"}
    completed = subprocess.run(["git", "-C", str(root), "-c", "core.autocrlf=false", *arguments], input=stdin,
                               check=True, capture_output=True, env=environment, timeout=60)
    return completed.stdout.decode("utf-8").strip()


class SubjectRepository:
    """A bare object store whose commits hold chosen files, readable only as inert objects."""

    def __init__(self, root: Path) -> None:
        self.root = root
        root.mkdir(parents=True)
        git(root, "init", "-q", "--bare")

    def _tree(self, files: dict[str, bytes]) -> str:
        entries, directories = [], {}
        for path, data in files.items():
            head, _, rest = path.partition("/")
            if rest:
                directories.setdefault(head, {})[rest] = data
            else:
                blob = git(self.root, "hash-object", "-w", "--stdin", stdin=data)
                entries.append(f"100644 blob {blob}\t{head}")
        entries.extend(f"040000 tree {self._tree(nested)}\t{name}" for name, nested in directories.items())
        return git(self.root, "mktree", stdin=("\n".join(entries) + "\n").encode("utf-8"))

    def head(self, name: str, files: dict[str, bytes]) -> dict[str, str]:
        """A commit holding exactly ``files``, as the listed head of branch ``name``."""

        tree = self._tree(files)
        commit = git(self.root, "commit-tree", tree, "-m", f"{name} fixture")
        return {"name": name, "commit": commit, "tree": tree}


def master_files(matrix: bytes = REAL_MATRIX, contract: bytes = REAL_CONTRACT) -> dict[str, bytes]:
    return {"release/release-matrix.json": matrix, "e2e/scenario-contract.json": contract}


def full_scope_matrix(raw: bytes = REAL_MATRIX) -> bytes:
    """The matrix with its migration flipped to ``shared``: every target in one ``full`` scope."""

    matrix = json.loads(raw)
    matrix["migration"] = {"mode": "shared", "legacy_nodes": []}
    display = {"fabric": "Fabric", "forge": "Forge", "neoforge": "NeoForge"}
    for artifact in matrix["artifacts"]:
        if artifact["build_layout"] == "legacy":
            loader, minecraft = artifact["loader"], artifact["minecraft"]
            root = f"{loader}/versions/{minecraft}/build/libs"
            artifact.update(build_layout="stonecutter", gradle_task=f":{loader}:{minecraft}:remapJar",
                            harness_task=f":{loader}:{minecraft}:remapE2EHarnessJar",
                            jar=f"{root}/BlockPops - {display[loader]} - {minecraft}-{{mod_version}}.jar",
                            harness_jar=f"{root}/BlockPops E2E - {display[loader]} - {minecraft}-0.0.0.jar")
    return (json.dumps(matrix, indent=2) + "\n").encode("utf-8")


def release_matrix(name: str, raw: bytes = REAL_MATRIX) -> bytes:
    """The matrix of an enrolled release branch ``name`` of ``master``."""

    matrix = json.loads(raw)
    matrix["branch"] = {"role": "release", "name": name, "canonical": "master",
                        "sync": {"enabled": True, "source": "master"}}
    return (json.dumps(matrix, indent=2) + "\n").encode("utf-8")


def context(repository: Path, commit: str, tmp: Path) -> Context:
    tmpdir = Path(tempfile.mkdtemp(prefix="hook-", dir=tmp))
    return Context(repo_root=repository, config=CONFIG, tmpdir=tmpdir, implementation_sha=commit, api=None)


def call_hook(repository: Path, commit: str, tmp: Path, hook: str, arguments: dict[str, Any]) -> Any:
    """One adapter hook, validated in and out exactly as the kit's host validates it."""

    return host_child.run_hook(context(repository, commit, tmp), ADAPTER, hook, arguments)


def target_of(repository: Path, head: dict[str, str], tmp: Path) -> dict[str, Any]:
    (target,) = call_hook(repository, head["commit"], tmp, "targets", {"branches": [head]})
    return target


def expectation_of(repository: Path, target: dict[str, Any], tmp: Path, *, event: str = "workflow_dispatch",
                   extensions: dict[str, Any] | None = None) -> dict[str, Any]:
    return call_hook(repository, target["subject"]["commit"], tmp, "expectation",
                     {"target": target, "tested_run": {"event": event, "branch": target["subject"]["branch"]},
                      "extensions": extensions or {}})


def synthesize(repository: Path, target: dict[str, Any], expectation: dict[str, Any], out_root: Path,
               tmp: Path) -> None:
    host_child.run_hook(context(repository, target["subject"]["commit"], tmp), FIXTURES, "synthesize",
                        {"target": target, "expectation": expectation, "out_root": str(out_root)},
                        image_factory=pattern_png)


class InProcessHooks:
    """``mod_base.adapter.host.call`` as the kit's conformance replaces it: the same dispatch and
    validation as the isolated child, in this process, over the subject repository's objects."""

    def __init__(self, repository: Path, tmp: Path) -> None:
        self.repository, self.tmp = repository, tmp

    def __call__(self, invocation: Invocation, hook: str, arguments: dict[str, Any], *, network: bool = False) -> Any:
        return call_hook(self.repository, invocation.implementation_sha, self.tmp, hook, dict(arguments))


def produce_evidence(work: Path, repository: Path, head: dict[str, str], *, run_id: int = 77, run_attempt: int = 1,
                     event: str = "workflow_dispatch", anchor: bool = True) -> dict[str, Any]:
    """A real kit handoff (and, when eligible, its lossless ``mb-anchor``) of one direct packaged run
    of ``head`` on its own branch, produced by the kit's ``prepare`` and ``anchor create``."""

    environ = {"GITHUB_REPOSITORY": REPOSITORY, "GITHUB_SHA": head["commit"], "GITHUB_RUN_ID": str(run_id),
               "GITHUB_RUN_ATTEMPT": str(run_attempt), "GITHUB_REF": f"refs/heads/{head['name']}",
               "GITHUB_REF_NAME": head["name"], "GITHUB_EVENT_NAME": event, "MOD_BASE_KIT_SHA": KIT_SHA,
               "GITHUB_WORKFLOW_REF": f"{REPOSITORY}/{SOURCE_WORKFLOW}@refs/heads/{head['name']}"}
    invocation = Invocation(repo_root=REPO, config=CONFIG, kit_root=KIT, environ=MappingProxyType(environ))
    claim = {"run_id": run_id, "run_attempt": run_attempt, "workflow_path": SOURCE_WORKFLOW, "branch": head["name"],
             "commit": head["commit"], "controller_branch": head["name"], "controller_sha": head["commit"]}
    subject = {"branch": head["name"], "commit": head["commit"], "tree": head["tree"]}
    target = target_of(repository, head, work)
    expectation = expectation_of(repository, target, work, event=event)
    e2e_root = work / "e2e-output"
    e2e_root.mkdir()
    synthesize(repository, target, expectation, e2e_root, work)
    handoff_dir, anchor_dir = work / "handoff", work / "anchor"
    with mock.patch.object(host, "call", InProcessHooks(repository, work)):
        result = prepare_handoff(invocation, e2e_root=e2e_root, key=target["key"], output=handoff_dir, subject=subject,
                                 tested=claim, handoff=claim, anchor="auto" if anchor else "off",
                                 anchor_output=anchor_dir if anchor else None)
        if anchor and result.anchor_eligible:
            create_anchor(invocation, key=target["key"], handoff_dir=handoff_dir, raw_artifact_id=501,
                          raw_artifact_name=grammar.handoff_name(target["key"], run_attempt),
                          raw_artifact_digest="sha256:" + "5" * 64, output=anchor_dir)
    return {"target": target, "expectation": expectation, "handoff": handoff_dir, "manifest": result.manifest,
            "anchor": anchor_dir if anchor_dir.is_dir() else None, "claim": claim}


class _Repository(unittest.TestCase):
    def setUp(self) -> None:
        temporary = tempfile.TemporaryDirectory(prefix="blockpops-mod-base-")
        self.addCleanup(temporary.cleanup)
        self.tmp = Path(temporary.name)
        self.repository = SubjectRepository(self.tmp / "objects.git")
        self.master = self.repository.head("master", master_files())

    def hook(self, head: dict[str, str], name: str, arguments: dict[str, Any]) -> Any:
        return call_hook(self.repository.root, head["commit"], self.tmp, name, arguments)

    def target(self, head: dict[str, str]) -> dict[str, Any]:
        return target_of(self.repository.root, head, self.tmp)

    def expectation(self, head: dict[str, str], **kwargs: Any) -> dict[str, Any]:
        return expectation_of(self.repository.root, self.target(head), self.tmp, **kwargs)


class ConfigurationTests(unittest.TestCase):
    def test_config_binds_the_block_pops_source_policy(self) -> None:
        source = CONFIG.source
        self.assertEqual("master", CONFIG.canonical_branch)
        self.assertEqual({"mode": "enrolled-branches", "max": 64, "max_branches": 100}, CONFIG.targets)
        self.assertEqual(SOURCE_WORKFLOW, source["workflow"])
        self.assertEqual({"canonical": ["schedule", "workflow_dispatch"], "other": ["workflow_dispatch"]},
                         source["events"])
        self.assertEqual("Packaged E2E / {subject_commit}", source["display_title"])
        self.assertEqual(f"{e2e_job_graph.E2E_ATTEST} / Verify exact tested tree", source["attestation_job"])
        self.assertEqual(e2e_job_graph.E2E_PUBLIC, source["handoff_job"])
        self.assertIsNone(source["delegated_reuse_extension"])
        self.assertTrue(source["require_job_graph"])
        self.assertTrue(source["require_newest_run"])
        workflow = (REPO / SOURCE_WORKFLOW).read_text(encoding="utf-8")
        self.assertIn(f"    name: {source['handoff_job']}\n", workflow)
        self.assertIn(f"      - name: {source['handoff_step']}\n"
                      "        uses: The-Plum-Team/mod-base/actions/prepare-evidence@", workflow)
        self.assertIn("run-name: Packaged E2E / ", workflow)

    def test_config_keeps_block_pops_retention_admission_and_capabilities(self) -> None:
        self.assertEqual({"enabled": True, "retention_days": 90, "successor_grace_days": 8}, CONFIG.anchor)
        self.assertEqual({"mode": "always", "defer_on_active_source_runs": False}, CONFIG.admission)
        self.assertEqual({"enabled": False}, CONFIG.baseline_archive)
        self.assertEqual([], CONFIG.families)
        # No hook needs the API: authenticate_extensions recomputes the scope from inert objects, so it
        # receives no token.
        self.assertEqual([], CONFIG.adapter["network_hooks"])
        self.assertEqual(["block-pops.aggregate_scope"], CONFIG.adapter["extensions"])
        self.assertEqual(["."], CONFIG.adapter["python_path"])
        self.assertIsNone(CONFIG.project["icon"])
        self.assertEqual([], CONFIG.project["links"])
        self.assertEqual({"from_matrix": "project.description"}, CONFIG.project["description"])
        self.assertEqual(json.loads(REAL_MATRIX)["project"]["license"], CONFIG.project["license_label"])

    def test_images_labels_and_theme_follow_the_contract_matrix_and_retired_site(self) -> None:
        contract = load_contract(CONTRACT_PATH)
        matrix = json.loads(REAL_MATRIX)
        self.assertEqual(list(contract.gui_text_reference_size), CONFIG.images["source_size"])
        self.assertEqual([1280, 720], CONFIG.images["derivative_box"])
        self.assertTrue(CONFIG.images["cross_check_runtime_metrics"])
        labels = CONFIG.labels
        self.assertEqual(set(contract.scenario_ids), set(labels["scenarios"]))
        self.assertEqual({role.role for scenario in contract.scenarios for role in scenario.roles},
                         set(labels["roles"]))
        self.assertEqual({target["loader"] for target in matrix["targets"]}, set(labels["loaders"]))
        self.assertEqual({capture.review_tier for scenario in contract.scenarios for role in scenario.roles
                          for step in role.steps if step.capture is not None for capture in (step.capture,)},
                         set(labels["tiers"]))
        self.assertEqual("Branch", labels["release_prefix"])
        self.assertEqual({"dark": DARK, "light": LIGHT}, CONFIG.theme)

    def test_template_defers_exactly_the_pr_b_root_files(self) -> None:
        self.assertEqual(PR_B_PATHS, CONFIG.template["deferred"])
        self.assertEqual(DEFERRABLE, frozenset(CONFIG.template["deferred"]))
        self.assertEqual(["docs/ai/PROJECT.md"], CONFIG.template["agents_local"])

    def test_kit_path_helper_resolves_the_verified_kit_without_bytecode(self) -> None:
        import mod_base

        self.assertEqual(str(KIT / "src"), os.path.commonpath([str(KIT / "src"), mod_base.__file__]))
        self.assertTrue(sys.dont_write_bytecode)
        self.assertEqual("1", os.environ.get("PYTHONDONTWRITEBYTECODE"))
        self.assertEqual(KIT, mod_base_path.kit_root())
        with mock.patch("scripts.ci.mod_base_kit.kit_path", side_effect=OSError("no pin")):
            with self.assertRaisesRegex(RuntimeError, "the pinned mod-base kit is unavailable: no pin"):
                mod_base_path.kit_root()


class TargetsTests(_Repository):
    def test_master_is_the_one_enrolled_key_today(self) -> None:
        targets = self.hook(self.master, "targets", {"branches": [self.master]})
        self.assertEqual([{"key": MASTER_KEY, "label": "master",
                           "subject": {"branch": "master", "commit": self.master["commit"],
                                       "tree": self.master["tree"]},
                           "matrix_sha256": hashlib.sha256(REAL_MATRIX).hexdigest(),
                           "contract_sha256": hashlib.sha256(REAL_CONTRACT).hexdigest()}], targets)
        self.assertEqual(branch_token("master"), MASTER_KEY)

    def test_only_self_identified_release_branches_join_the_canonical_branch(self) -> None:
        heads = [
            self.master,
            self.repository.head("1.21.1-neoforge-fabric", {"README.md": b"no matrix on this branch\n"}),
            self.repository.head("feature/topic", master_files()),
            self.repository.head("broken-claim", {"release/release-matrix.json": b"{not json"}),
            self.repository.head("release/1.21", master_files(release_matrix("release/1.21"))),
        ]
        targets = self.hook(self.master, "targets", {"branches": list(reversed(heads))})
        self.assertEqual(["master", "release/1.21"], [target["label"] for target in targets])
        self.assertEqual([MASTER_KEY, branch_token("release/1.21")], [target["key"] for target in targets])

    def test_only_an_absent_matrix_skips_a_listed_branch(self) -> None:
        # read_blob raises one error type for every failure; the adapter skips a branch only on the
        # kit's exact absence message, which the missing-file branch above pins against the kit.
        unreadable = {
            "oversized": ({"release/release-matrix.json": b" " * (MAX_MATRIX_BYTES + 1)}, "more than"),
            "tree-typed": ({"release/release-matrix.json/nested.json": b"{}"}, "not a blob"),
        }
        for label, (files, message) in unreadable.items():
            with self.subTest(label):
                head = self.repository.head(f"release/{label}", files)
                with self.assertRaisesRegex(MbError, message):
                    self.hook(self.master, "targets", {"branches": [self.master, head]})
        missing = self.repository.head("topic", {"README.md": b"no matrix\n"})
        error = MbError(f"release/release-matrix.json is not present at {missing['commit']} as an inert object")
        self.assertTrue(ADAPTER.matrix_absent(error, missing["commit"]))
        self.assertFalse(ADAPTER.matrix_absent(error, self.master["commit"]))
        self.assertFalse(ADAPTER.matrix_absent(MbError("git cat-file failed with exit status 128"),
                                               missing["commit"]))

    def test_a_branch_claiming_enrollment_must_validate_completely(self) -> None:
        claim = json.dumps({"branch": {"name": "release/bad", "role": "release"}}).encode("utf-8")
        bad = self.repository.head("release/bad", master_files(claim))
        with self.assertRaisesRegex(HookFailed, "branch release matrix is invalid"):
            self.hook(self.master, "targets", {"branches": [self.master, bad]})
        foreign = self.repository.head("release/other", master_files(release_matrix("release/other")))
        renamed = self.repository.head("release/renamed", master_files(release_matrix("release/other")))
        self.assertEqual(["release/other"],
                         [target["label"] for target in self.hook(self.master, "targets",
                                                                   {"branches": [foreign, renamed]})])

    def test_the_canonical_branch_fails_closed_without_a_valid_matrix(self) -> None:
        missing = self.repository.head("master", {"README.md": b"no matrix\n"})
        with self.assertRaisesRegex(MbError, "is not present"):
            self.hook(missing, "targets", {"branches": [missing]})
        wrong_role = self.repository.head("master", master_files(release_matrix("master")))
        with self.assertRaises(HookFailed):
            self.hook(wrong_role, "targets", {"branches": [wrong_role]})
        with self.assertRaises(HookFailed):
            self.hook(self.master, "targets", {"branches": None})


class ExpectationTests(_Repository):
    def contract(self):
        return load_contract(CONTRACT_PATH)

    def assert_lanes(self, expectation: dict[str, Any], nodes: list[str]) -> None:
        contract = self.contract()
        scenarios = list(contract.scenarios_for_profile("release"))
        self.assertEqual([f"{node}/{scenario}" for node in nodes for scenario in scenarios],
                         [lane["lane_id"] for lane in expectation["lanes"]])
        per_node = sum(1 for scenario in scenarios for role in contract.scenario(scenario).roles
                       for step in role.steps if step.capture is not None)
        comparisons = sum(len(contract.comparisons_for(scenario, role.role)) for scenario in scenarios
                          for role in contract.scenario(scenario).roles)
        self.assertEqual(per_node * len(nodes), len(expectation["captures"]))
        self.assertEqual(comparisons * len(nodes), len(expectation["comparisons"]))

    def test_legacy_scope_expectation_of_the_real_contract_and_matrix(self) -> None:
        expectation = self.expectation(self.master)
        self.assert_lanes(expectation, LEGACY_NODES)
        self.assertEqual((44, 6), (len(expectation["captures"]), len(expectation["comparisons"])))
        self.assertEqual(REPOSITORY, expectation["repository"])
        self.assertEqual((MASTER_KEY, "master"), (expectation["key"], expectation["label"]))
        self.assertEqual("pr-anchors", expectation["profile"])
        self.assertEqual({"artifact_nodes": ["fabric-1.20.1"]}, expectation["anchor"])
        self.assertEqual(CONFIG.image_policy(), expectation["image_policy"])
        detail = {"kind": "legacy", "selected_nodes": LEGACY_NODES,
                  "target_nodes": [target["artifact_node"] for target in json.loads(REAL_MATRIX)["targets"]],
                  "migration_mode": "preparing", "partial": True, "projection": "pr-anchors",
                  "scenarios": list(self.contract().scenarios_for_profile("pr"))}
        self.assertEqual({"kind": "complete", "detail": detail, "detail_sha256": canonical_sha256(detail)},
                         expectation["scope"])
        contract = self.contract()
        for capture in expectation["captures"]:
            lane = next(lane for lane in expectation["lanes"] if lane["lane_id"] == capture["lane_id"])
            wanted = contract.capture(lane["scenario"], capture["role"], capture["step"])
            self.assertEqual((wanted.capture_id, wanted.title, wanted.expectation, wanted.review_tier),
                             (capture["capture_id"], capture["title"], capture["expectation"],
                              capture["review_tier"]))

    def test_the_run_event_selects_the_packaged_projection(self) -> None:
        scheduled = self.expectation(self.master, event="schedule")
        self.assertEqual("scheduled-anchors", scheduled["profile"])
        self.assertEqual("scheduled-anchors", scheduled["scope"]["detail"]["projection"])
        dispatched = self.expectation(self.master)
        self.assertNotEqual(scheduled["scope"]["detail_sha256"], dispatched["scope"]["detail_sha256"])
        self.assertEqual(scheduled["lanes"], dispatched["lanes"])

    def test_full_scope_puts_every_target_under_the_one_master_key(self) -> None:
        head = self.repository.head("master", master_files(full_scope_matrix()))
        expectation = self.expectation(head)
        nodes = [lane.identity.artifact_node for lane in sorted(
            MatrixDocument(normalize_matrix_inventory(json.loads(full_scope_matrix())), "{}").select_lanes(),
            key=lambda lane: (tuple(map(int, lane.identity.minecraft.split("."))), lane.identity.loader))]
        self.assertEqual(20, len(nodes))
        self.assert_lanes(expectation, nodes)
        self.assertEqual((40, 440, 60), (len(expectation["lanes"]), len(expectation["captures"]),
                                         len(expectation["comparisons"])))
        self.assertEqual(MASTER_KEY, expectation["key"])
        self.assertEqual(("full", False), (expectation["scope"]["detail"]["kind"],
                                           expectation["scope"]["detail"]["partial"]))
        self.assertEqual({"artifact_nodes": ["fabric-1.20.1"]}, expectation["anchor"])

    def test_a_schema1_matrix_runs_every_runtime_without_an_aggregate_scope(self) -> None:
        raw = (REPO / "tests" / "fixtures" / "release-matrix-schema1.json").read_bytes()
        head = self.repository.head("master", master_files(raw))
        expectation = self.expectation(head)
        self.assertEqual({"kind": "complete"}, expectation["scope"])
        self.assert_lanes(expectation, [row["artifact_node"] for row in json.loads(raw)["runtimes"]])
        self.assertEqual("AkaNebur/BlockPops", expectation["repository"])
        path = self.tmp / "schema1-matrix.json"
        path.write_bytes(raw)
        record = {"run_id": 10, "run_attempt": 1, "workflow_path": SOURCE_WORKFLOW, "branch": "master",
                  "commit": head["commit"], "controller_branch": "master", "controller_sha": head["commit"],
                  "event": "schedule", "created_at": "2026-09-01T12:00:00Z", "conclusion": "success",
                  "head_sha": head["commit"]}
        jobs = self.hook(head, "expected_source_jobs", {"expectation": expectation, "tested_run": record})
        self.assertEqual([{"name": job.name, "conclusion": job.conclusion} for job in e2e_job_graph.expected_jobs(
            path, "on-demand-e2e.yml", event="schedule", source_branch="master")], jobs)

    def test_a_release_branch_publishes_without_the_canonical_anchor(self) -> None:
        head = self.repository.head("release/1.21", master_files(release_matrix("release/1.21")))
        expectation = self.expectation(head)
        self.assertEqual(branch_token("release/1.21"), expectation["key"])
        self.assertIsNone(expectation["anchor"])
        self.assertIsNone(self.hook(head, "anchor_selection", {"expectation": expectation}))
        self.assertEqual({"artifact_nodes": ["fabric-1.20.1"]},
                         self.hook(self.master, "anchor_selection", {"expectation": self.expectation(self.master)}))

    def test_a_supplied_aggregate_scope_must_be_the_executed_one(self) -> None:
        detail = self.expectation(self.master)["scope"]["detail"]
        self.assertEqual(detail, self.expectation(self.master, extensions={"block-pops.aggregate_scope": detail})
                         ["scope"]["detail"])
        with self.assertRaisesRegex(HookFailed, "aggregate scope is not the scope"):
            self.expectation(self.master, extensions={"block-pops.aggregate_scope": {**detail, "partial": False}})
        with self.assertRaisesRegex(HookFailed, "aggregate scope is not the scope"):
            self.expectation(self.master, event="schedule", extensions={"block-pops.aggregate_scope": detail})

    def test_a_target_must_name_its_own_matrix_contract_and_key(self) -> None:
        target = self.target(self.master)
        for field, value in (("matrix_sha256", "0" * 64), ("contract_sha256", "0" * 64), ("key", "0" * 24)):
            with self.subTest(field=field), self.assertRaises(HookFailed):
                expectation_of(self.repository.root, {**target, field: value}, self.tmp)

    def test_absent_hooks_refuse_selected_and_family_evidence(self) -> None:
        for hook, arguments in (
            ("compose", {"key": MASTER_KEY, "selected_compact_dir": str(self.tmp), "output_dir": str(self.tmp)}),
            ("family_validate", {"family": "demo", "key": MASTER_KEY, "bundle_dir": str(self.tmp),
                                 "expected_coverage_sha": "1" * 40, "output_dir": str(self.tmp)}),
        ):
            with self.subTest(hook=hook), self.assertRaises(HookUnsupported):
                self.hook(self.master, hook, arguments)
        self.assertFalse(hasattr(ADAPTER, "verify_publication"))


class SourceJobsTests(_Repository):
    def record(self, event: str, branch: str = "master") -> dict[str, Any]:
        return {"run_id": 10, "run_attempt": 1, "workflow_path": SOURCE_WORKFLOW, "branch": branch,
                "commit": self.master["commit"], "controller_branch": "master",
                "controller_sha": self.master["commit"], "event": event, "created_at": "2026-09-01T12:00:00Z",
                "conclusion": "success", "head_sha": self.master["commit"]}

    def test_the_exact_job_graph_is_e2e_job_graph_unchanged(self) -> None:
        for event, branch in (("schedule", "master"), ("workflow_dispatch", "master"),
                              ("workflow_dispatch", "release/1.21")):
            with self.subTest(event=event, branch=branch):
                expectation = self.expectation(self.master, event=event)
                jobs = self.hook(self.master, "expected_source_jobs",
                                 {"expectation": expectation, "tested_run": self.record(event, branch)})
                expected = e2e_job_graph.expected_jobs(MATRIX_PATH, "on-demand-e2e.yml", event=event,
                                                       source_branch=branch, scope="legacy")
                self.assertEqual([{"name": job.name, "conclusion": job.conclusion} for job in expected], jobs)
                public = {job["name"]: job["conclusion"] for job in jobs}[e2e_job_graph.E2E_PUBLIC]
                self.assertEqual("success" if branch == "master" else "skipped", public)


class CollectTests(_Repository):
    def setUp(self) -> None:
        super().setUp()
        self.target_value = self.target(self.master)
        self.full_expectation = expectation_of(self.repository.root, self.target_value, self.tmp)
        self.lane = "fabric-1.20.1/ui-regression"
        self.value = self.one_lane(self.full_expectation, self.lane)
        self.output = self.tmp / "packaged"
        self.output.mkdir()
        synthesize(self.repository.root, self.target_value, self.value, self.output, self.tmp)
        self.profile = self.output / "profiles" / "fabric-1.20.1--1.20.1--ui-regression"

    @staticmethod
    def one_lane(expectation: dict[str, Any], lane_id: str) -> dict[str, Any]:
        value = copy.deepcopy(expectation)
        value["lanes"] = [lane for lane in value["lanes"] if lane["lane_id"] == lane_id]
        value["captures"] = [item for item in value["captures"] if item["lane_id"] == lane_id]
        value["comparisons"] = [item for item in value["comparisons"] if item["lane_id"] == lane_id]
        return value

    def collect(self, root: Path | None = None) -> dict[str, Any]:
        return self.hook(self.master, "collect", {"runtime_root": str(root or self.output), "target": self.target_value,
                                                  "expectation": self.value})

    def result(self) -> dict[str, Any]:
        return json.loads((self.profile / "result.json").read_text(encoding="utf-8"))

    def rewrite(self, result: dict[str, Any]) -> None:
        (self.profile / "result.json").write_text(json.dumps(result), encoding="utf-8")

    def test_collect_reads_block_pops_packaged_output_and_is_pure(self) -> None:
        collected = self.collect()
        self.assertEqual(["profiles/fabric-1.20.1--1.20.1--ui-regression/result.json"],
                         [path for path in collected["runtime_files"] if path.endswith(".json")])
        self.assertEqual(len(self.value["captures"]) + 1, len(collected["runtime_files"]))
        self.assertEqual([capture["frame_id"] for capture in self.value["captures"]],
                         [frame["frame_id"] for frame in collected["frames"]])
        result = self.result()
        steps = {step["id"]: step for step in result["reports"]["client_a"]["steps"]}
        for frame, capture in zip(collected["frames"], self.value["captures"], strict=True):
            self.assertEqual(steps[capture["step"]]["message"], frame["runtime_evidence"])
            recorded = result["reports"]["client_a"]["pixel_validation"]["screenshots"][capture["step"]]
            self.assertEqual(recorded, frame["reported_pixel"])
            # The kit's own metrics equal Block Pops' recorded ones: the prepare cross-check holds.
            self.assertEqual(recorded, inspect_png(self.output / frame["source_path"], SizePolicy("exact", 1600, 900)))
        self.assertEqual([{"lane_id": self.lane, "java": 17, "profile": "pr-anchors", "status": "pass",
                           "jars": {"production_sha256": result["production_jar_sha256"],
                                    "harness_sha256": result["harness_jar_sha256"]},
                           "elapsed_s": result["elapsed_s"]}], collected["lanes"])
        self.assertEqual(3, len(collected["comparisons"]))
        self.assertEqual(collected, self.collect())
        # R1: collect over exactly the declared runtime files (the handoff's runtime/) is equal.
        copied = self.tmp / "runtime"
        for relative in collected["runtime_files"]:
            (copied / relative).parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(self.output / relative, copied / relative)
        self.assertEqual(collected, self.collect(copied))

    def test_recorded_pixels_and_comparisons_must_equal_protected_measurement(self) -> None:
        original = self.result()
        step = self.value["captures"][0]["step"]
        result = copy.deepcopy(original)
        screenshots = result["reports"]["client_a"]["pixel_validation"]["screenshots"]
        screenshots[step] = {**screenshots[step], "luma_entropy": 0.5}
        self.rewrite(result)
        with self.assertRaisesRegex(HookFailed, "protected pixel validation disagrees"):
            self.collect()
        result = copy.deepcopy(original)
        comparisons = result["reports"]["client_a"]["pixel_validation"]["comparisons"]
        key = next(iter(comparisons))
        comparisons[key] = {**comparisons[key], "changed_fraction": 0.5}
        self.rewrite(result)
        with self.assertRaisesRegex(HookFailed, "protected comparison disagrees"):
            self.collect()

    def test_a_frame_failing_its_contract_probe_is_refused(self) -> None:
        from PIL import Image

        capture = self.value["captures"][0]
        path = self.profile / "client_a" / "screenshots" / f"{capture['capture_id']}.png"
        with Image.open(path) as image:
            washed = image.convert("RGB")
        washed.paste((250, 250, 250), (32, 135, 320, 765))
        washed.save(path, format="PNG")
        with self.assertRaisesRegex(HookFailed, "OPAQUE_STARS background"):
            self.collect()

    def test_packaged_identity_and_report_shape_are_exact(self) -> None:
        mutations = {
            "loader": lambda result: result.update(loader="forge"),
            "status": lambda result: result.update(status="fail", error="broken"),
            "role": lambda result: result["reports"].update(client_b=result["reports"]["client_a"]),
            "message": lambda result: result["reports"]["client_a"]["steps"][0].update(message="x" * 1025),
            "step": lambda result: result["reports"]["client_a"]["steps"].pop(),
            "contract": lambda result: result["reports"]["client_a"].update(contract_sha256="0" * 64),
        }
        original = self.result()
        for name, mutate in mutations.items():
            with self.subTest(mutation=name):
                result = copy.deepcopy(original)
                mutate(result)
                self.rewrite(result)
                with self.assertRaises(HookFailed):
                    self.collect()
        self.rewrite(original)
        self.collect()

    def test_protected_probes_must_equal_the_subject_contract(self) -> None:
        document = json.loads(REAL_CONTRACT)
        probe = document["scenarios"][0]["roles"][0]["steps"][0]["capture"]["probes"][0]
        probe["maximum_mean_luma"] = probe["maximum_mean_luma"] + 1
        drifted = (json.dumps(document, indent=2) + "\n").encode("utf-8")
        head = self.repository.head("master", master_files(contract=drifted))
        target = self.target(head)
        expectation = self.one_lane(expectation_of(self.repository.root, target, self.tmp), self.lane)
        with self.assertRaisesRegex(HookFailed, "protected screenshot probes differ"):
            self.hook(head, "collect", {"runtime_root": str(self.output), "target": target, "expectation": expectation})


class ExtensionTests(_Repository):
    def test_a_supplied_aggregate_scope_is_recomputed_from_the_subject(self) -> None:
        expectation = self.expectation(self.master)
        detail = expectation["scope"]["detail"]
        manifest = {"subject": expectation["subject"], "matrix_sha256": expectation["matrix_sha256"],
                    "contract_sha256": expectation["contract_sha256"], "scope": {"kind": "complete",
                                                                                 "detail_sha256": canonical_sha256(detail)}}
        arguments = {"manifest": manifest, "extensions": {"block-pops.aggregate_scope": detail}}
        self.assertEqual({"verified": ["block-pops.aggregate_scope"], "reuse_verified": False},
                         self.hook(self.master, "authenticate_extensions", arguments))
        self.assertEqual({"verified": [], "reuse_verified": False},
                         self.hook(self.master, "authenticate_extensions", {"manifest": manifest, "extensions": {}}))
        for label, changed in (
            ("partial", {**detail, "partial": False}),
            ("projection", {**detail, "projection": "unknown"}),
        ):
            with self.subTest(label=label), self.assertRaises(HookFailed):
                self.hook(self.master, "authenticate_extensions",
                          {"manifest": manifest, "extensions": {"block-pops.aggregate_scope": changed}})
        stale = {**manifest, "scope": {"kind": "complete", "detail_sha256": "0" * 64}}
        with self.assertRaisesRegex(HookFailed, "not the manifest's scope"):
            self.hook(self.master, "authenticate_extensions", {"manifest": stale, "extensions": arguments["extensions"]})


if __name__ == "__main__":
    unittest.main()

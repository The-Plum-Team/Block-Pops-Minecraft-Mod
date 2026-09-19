from __future__ import annotations

import copy
import json
import shutil
import subprocess
import tempfile
import unittest
import urllib.request
from dataclasses import replace
from pathlib import Path
from unittest import mock

from scripts.ci.e2e_job_graph import expected_jobs
from scripts.ci.pr_gate import (
    CONTROLLER_UPGRADE_REQUIRED,
    CONTEXTS,
    EXACT_BASE_OWNED_PATHS,
    GateResult,
    GitHubApi,
    NotEligible,
    PrGateError,
    PullIdentity,
    RESTRICTED_TRANSITION_SCOPES,
    SelectedRun,
    UpgradeAuthorization,
    _source_outputs,
    _upgrade_path_allowed,
    bind_restricted_transition_decision,
    controller_upgrade_authorization,
    _gate_result,
    _result,
    main as pr_gate_main,
    parse_restricted_transition,
    reauthorize,
    resolve_dispatch_source,
    resolve_pull_identity,
    select_newest_pull_run,
    validate_exact_artifact,
    validate_controller_upgrade_tree,
    validate_pr_tree,
    validate_restricted_transition_tree,
)


REPO = Path(__file__).resolve().parents[3]
MATRIX = REPO / "release/release-matrix.json"
MATRIX_IDENTITY = json.loads(MATRIX.read_text(encoding="utf-8"))["branch"]
MERGE = "d" * 40
HEAD = "c" * 40
BASE = "b" * 40
DEFAULT = "a" * 40


def identity(**overrides: object) -> PullIdentity:
    values: dict[str, object] = {
        "number": 17,
        "default_branch": "master",
        "default_sha": DEFAULT,
        "base_branch": "master",
        "base_sha": BASE,
        "head_branch": "feature/ui",
        "head_sha": HEAD,
        "merge_sha": MERGE,
        "merge_tree": "e" * 40,
    }
    values.update(overrides)
    return PullIdentity(**values)  # type: ignore[arg-type]


def run(
    run_id: int,
    *,
    workflow: str = "build-gate.yml",
    attempt: int = 1,
    status: str = "completed",
    conclusion: str | None = "success",
    created_at: str = "2026-08-10T10:00:00Z",
    pull: int = 17,
    branch: str = "master",
    head: str = DEFAULT,
    event: str = "pull_request_target",
) -> dict[str, object]:
    return {
        "id": run_id,
        "run_attempt": attempt,
        "path": f".github/workflows/{workflow}",
        "event": event,
        "head_branch": branch,
        "head_sha": head,
        "status": status,
        "conclusion": conclusion,
        "created_at": created_at,
        "repository": {"full_name": "owner/repo"},
        "head_repository": {"full_name": "owner/repo"},
        "pull_requests": [{"number": pull}],
    }


class RestrictedTransitionDeclarationTests(unittest.TestCase):
    def declaration(self, **overrides: object) -> dict[str, object]:
        return {
            "schema_version": 1, "controller_generation": 1, "controller_sha": BASE,
            "base_sha": BASE, "head_sha": HEAD, "scope": "matrix",
            "paths": ["release/release-matrix.json"], **overrides,
        }

    def parse(self, declaration=None, **overrides):
        arguments = {
            "identity": identity(default_sha=BASE), "deployed_controller_sha": BASE,
            "deployed_generation": 1, **overrides,
        }
        raw = json.dumps(self.declaration() if declaration is None else declaration).encode()
        return parse_restricted_transition(raw, **arguments)

    def decision(self, transition, **overrides):
        return {
            "schema_version": 1, "purpose": "restricted-transition-owner-authorization",
            "repository": "AkaNebur/BlockPops", "pull_request": 17, "owner": "AkaNebur",
            "decision": "approve", "controller_generation": 1, "controller_sha": BASE,
            "base_sha": BASE, "head_sha": HEAD, "declaration_sha256": transition.digest,
            "comment_id": 91, "comment_updated_at": "2026-09-19T10:00:00Z", **overrides,
        }

    def bind(self, transition, decision):
        return bind_restricted_transition_decision(
            transition, repository="AkaNebur/BlockPops", pull_number=17,
            authenticated_owner_decision=decision)

    def test_exact_static_scopes_and_external_deployment_bind_inert_proposals(self):
        self.assertEqual(
            {"matrix", "verification", "vanilla-shim", "datapack-metadata", "stonecutter-bootstrap",
             "fabric-contract", "forge-contract", "neoforge-contract", "fabric-next", "forge-next", "neoforge-next"},
            set(RESTRICTED_TRANSITION_SCOPES))
        self.assertTrue(set(EXACT_BASE_OWNED_PATHS).issubset(
            {path for paths in RESTRICTED_TRANSITION_SCOPES.values() for path in paths}))
        for scope, paths in RESTRICTED_TRANSITION_SCOPES.items():
            with self.subTest(scope=scope):
                result = self.parse(self.declaration(scope=scope, paths=sorted(paths)))
                self.assertEqual(tuple(sorted(paths)), result.paths)
                self.assertRegex(self.bind(result, self.decision(result)), r"^[0-9a-f]{64}$")
                self.assertTrue(all(not path.startswith(("scripts/", ".github/")) for path in paths))

    def test_bounded_strict_json_rejects_malformed_duplicate_and_extra_fields(self):
        for raw in (b"", b"[", b"\xff", b"{}", b"[]", b'{"scope":"matrix","scope":"matrix"}',
                    b'{"schema_version":NaN}', b" " * 8193, "{}"):
            with self.subTest(raw=repr(raw)[:80]), self.assertRaises(PrGateError):
                parse_restricted_transition(raw, identity=identity(default_sha=BASE),
                                            deployed_controller_sha=BASE, deployed_generation=1)
        for value in (self.declaration(authority=True), {"schema_version": 1}):
            with self.assertRaises(PrGateError):
                self.parse(value)

    def test_unknown_versions_generations_and_self_reported_deployment_fail(self):
        for field in ("schema_version", "controller_generation"):
            for value in (True, "1", 0, 2, None):
                with self.subTest(field=field, value=value), self.assertRaises(PrGateError):
                    self.parse(self.declaration(**{field: value}))
        for arguments in ({"deployed_generation": True}, {"deployed_generation": 2},
                          {"deployed_controller_sha": HEAD}, {"deployed_controller_sha": "bad"},
                          {"identity": identity()}, {"identity": identity(default_sha=BASE, base_branch="1.20.1")},
                          {"identity": identity(default_sha=HEAD, base_sha=HEAD), "deployed_controller_sha": HEAD}):
            with self.subTest(arguments=arguments), self.assertRaises(PrGateError):
                self.parse(**arguments)
        with self.assertRaisesRegex(PrGateError, "distinct candidate"):
            self.parse(self.declaration(head_sha=BASE), identity=identity(default_sha=BASE, head_sha=BASE))

    def test_paths_cannot_be_inferred_expanded_aliased_duplicated_or_cross_loader(self):
        for paths in ([], ["release/*"], ["release"], ["release/release-matrix.json/"],
                      ["./release/release-matrix.json"], ["release/../release/release-matrix.json"],
                      ["release\\release-matrix.json"], ["release/release-matrix.json"] * 2,
                      ["release/release-matrix.json", "scripts/ci/pr_gate.py"], [None], [{}], "matrix"):
            with self.subTest(paths=paths), self.assertRaises(PrGateError):
                self.parse(self.declaration(paths=paths))
        for declaration in (self.declaration(scope="future"), self.declaration(scope=[]),
                            self.declaration(scope="fabric-next", paths=["forge/build.gradle"]),
                            self.declaration(scope="neoforge-contract", paths=["neoforge/build.gradle"]),
                            self.declaration(scope="stonecutter-bootstrap", paths=["settings.gradle", "build.gradle"])):
            with self.subTest(declaration=declaration), self.assertRaises(PrGateError):
                self.parse(declaration)
        result = self.parse(self.declaration(scope="stonecutter-bootstrap", paths=["stonecutter.gradle"]))
        self.assertEqual(("stonecutter.gradle",), result.paths)

    def test_declaration_and_owner_decision_bind_every_identity_and_generation(self):
        for field in ("controller_sha", "base_sha", "head_sha"):
            with self.subTest(field=field), self.assertRaises(PrGateError):
                self.parse(self.declaration(**{field: "f" * 40}))
        transition = self.parse()
        mutations = {"schema_version": True, "purpose": "controller-upgrade-owner-authorization",
                     "repository": "other/repo", "pull_request": 18, "owner": "collaborator",
                     "decision": "revoke", "controller_generation": 2, "controller_sha": HEAD,
                     "base_sha": HEAD, "head_sha": BASE, "declaration_sha256": "0" * 64,
                     "comment_id": True, "comment_updated_at": "yesterday", "extra": True}
        for field, value in mutations.items():
            with self.subTest(field=field), self.assertRaises(PrGateError):
                self.bind(transition, self.decision(transition, **{field: value}))
        for decision in ({}, None, self.decision(transition, controller_generation=True)):
            with self.assertRaises(PrGateError):
                self.bind(transition, decision)
        other = self.parse(self.declaration(scope="verification", paths=["gradle/verification-metadata.xml"]))
        with self.assertRaises(PrGateError):
            self.bind(other, self.decision(transition))
        self.assertNotEqual(self.bind(transition, self.decision(transition)),
                            self.bind(transition, self.decision(transition, comment_id=92)))
        narrowed = self.parse(self.declaration(scope="stonecutter-bootstrap", paths=["stonecutter.gradle"]))
        expanded = self.parse(self.declaration(scope="stonecutter-bootstrap", paths=["build.gradle", "stonecutter.gradle"]))
        with self.assertRaises(PrGateError):
            self.bind(expanded, self.decision(narrowed))


class RunSelectionTests(unittest.TestCase):
    def test_authenticated_api_disables_environment_proxies_and_redirects(self) -> None:
        with mock.patch(
            "scripts.ci.pr_gate.urllib.request.build_opener",
            wraps=urllib.request.build_opener,
        ) as build_opener:
            GitHubApi(
                repository="owner/repo",
                token="test-token",
                api_url="https://api.github.invalid",
            )
        handlers = build_opener.call_args.args
        self.assertTrue(
            any(
                isinstance(handler, urllib.request.ProxyHandler)
                and handler.proxies == {}
                for handler in handlers
            )
        )
        self.assertTrue(any(type(handler).__name__ == "_NoRedirect" for handler in handlers))

    def test_newest_pending_attempt_supersedes_an_older_success(self) -> None:
        old = run(10)
        current = run(
            11,
            attempt=2,
            status="in_progress",
            conclusion=None,
            created_at="2026-08-10T10:01:00Z",
        )
        selected = select_newest_pull_run(
            [old, current],
            workflow="build-gate.yml",
            repository="owner/repo",
            identity=identity(),
        )
        self.assertEqual(SelectedRun(11, 2, "in_progress", None, current["created_at"]), selected)

    def test_wrong_pr_repository_and_default_controller_are_not_evidence(self) -> None:
        wrong_pr = run(10, pull=99)
        wrong_head = run(11, head="f" * 40)
        foreign = run(12)
        foreign["head_repository"] = {"full_name": "attacker/repo"}
        self.assertIsNone(
            select_newest_pull_run(
                [wrong_pr, wrong_head, foreign],
                workflow="build-gate.yml",
                repository="owner/repo",
                identity=identity(),
            )
        )

    def test_legacy_pull_request_run_is_not_protected_evidence(self) -> None:
        self.assertIsNone(
            select_newest_pull_run(
                [run(10, event="pull_request")],
                workflow="build-gate.yml",
                repository="owner/repo",
                identity=identity(),
            )
        )

    def test_workflow_run_inventory_is_prt_only_without_head_sha_query(self) -> None:
        api = object.__new__(GitHubApi)
        api.pages = mock.Mock(return_value=[])
        self.assertEqual([], api.workflow_runs("build-gate.yml"))
        suffix = api.pages.call_args.args[0]
        self.assertIn("event=pull_request_target", suffix)
        self.assertNotIn("head_sha", suffix)

    def test_duplicate_current_run_id_fails_closed_even_with_another_attempt(self) -> None:
        current = run(10)
        repeated = copy.deepcopy(current)
        repeated["run_attempt"] = 2
        with self.assertRaisesRegex(PrGateError, "repeats"):
            select_newest_pull_run(
                [current, repeated],
                workflow="build-gate.yml",
                repository="owner/repo",
                identity=identity(),
            )


class ArtifactAndGraphTests(unittest.TestCase):
    def artifact(self, name: str, *, run_id: int = 51) -> dict[str, object]:
        return {
            "id": 701,
            "name": name,
            "expired": False,
            "size_in_bytes": 4096,
            "digest": "sha256:" + "9" * 64,
            "workflow_run": {"id": run_id},
        }

    def test_artifact_identity_binds_tested_merge_attempt_and_owner(self) -> None:
        expected = f"staged-release-bundle-{MERGE}-3"
        artifact = self.artifact(expected)
        self.assertEqual(
            701,
            validate_exact_artifact([artifact], expected_name=expected, run_id=51)["id"],
        )
        for label, mutate in (
            ("stale merge", lambda value: value.__setitem__("name", f"staged-release-bundle-{HEAD}-3")),
            ("wrong owner", lambda value: value.__setitem__("workflow_run", {"id": 52})),
            ("expired", lambda value: value.__setitem__("expired", True)),
            ("no digest", lambda value: value.__setitem__("digest", None)),
        ):
            broken = copy.deepcopy(artifact)
            mutate(broken)
            with self.subTest(label=label), self.assertRaises(PrGateError):
                validate_exact_artifact([broken], expected_name=expected, run_id=51)

    def test_success_requires_exact_job_graph_and_artifact(self) -> None:
        selected = SelectedRun(51, 3, "completed", "success", "2026-08-10T10:00:00Z")
        graph = expected_jobs(
            MATRIX,
            "build-gate.yml",
            event="pull_request_target",
            source_branch="master",
        )
        jobs = [
            {
                "id": index + 1,
                "name": item.name,
                "run_attempt": 3,
                "status": "completed",
                "conclusion": item.conclusion,
            }
            for index, item in enumerate(graph)
        ]
        expected_name = f"staged-release-bundle-{MERGE}-3"

        class Api:
            def __init__(self, values: list[dict[str, object]]) -> None:
                self.values = values

            def jobs(self, _run_id: int):
                return self.values

            def artifacts(self, _run_id: int):
                return [ArtifactAndGraphTests().artifact(expected_name)]

        accepted = _gate_result(
            Api(jobs),
            kind="build",
            identity=identity(),
            matrix_path=MATRIX,
            selected=selected,
        )
        self.assertEqual("success", accepted.state)
        rejected = _gate_result(
            Api([*jobs, {"id": 99, "name": "invented", "run_attempt": 3, "status": "completed", "conclusion": "success"}]),
            kind="build",
            identity=identity(),
            matrix_path=MATRIX,
            selected=selected,
        )
        self.assertEqual("failure", rejected.state)


class PullIdentityTests(unittest.TestCase):
    class Api:
        repository = "owner/repo"

        def __init__(
            self,
            *,
            head_branch: str = "feature/ui",
            run_record: dict[str, object] | None = None,
            fork_pull: bool = False,
        ) -> None:
            self.head_branch = head_branch
            self.run_record = run(51) if run_record is None else run_record
            self.fork_pull = fork_pull

        def run(self, run_id: int):
            value = copy.deepcopy(self.run_record)
            value["id"] = run_id
            return value

        def repository_record(self):
            return {"full_name": self.repository, "default_branch": "master"}

        def pull(self, number: int):
            return {
                "number": number,
                "state": "open",
                "head": {
                    "ref": self.head_branch,
                    "sha": HEAD,
                    "repo": {
                        "full_name": "attacker/repo" if self.fork_pull else self.repository
                    },
                },
                "base": {
                    "ref": "ship/stable",
                    "sha": BASE,
                    "repo": {"full_name": self.repository},
                },
                "merge_commit_sha": MERGE,
            }

        def branch_sha(self, branch: str):
            return {
                "master": DEFAULT,
                "ship/stable": BASE,
                self.head_branch: HEAD,
            }[branch]

        def commit_identity(self, commit: str):
            self.assert_commit = commit
            return "e" * 40, (BASE, HEAD)

    def test_trigger_authenticates_default_controller_and_current_merge_parents(self) -> None:
        current = resolve_pull_identity(
            self.Api(), trigger_run_id=51, implementation_sha=DEFAULT
        )
        self.assertEqual(
            identity(base_branch="ship/stable"),
            current,
        )

    def test_source_locator_needs_no_trigger_and_exports_complete_identity(self) -> None:
        current = resolve_pull_identity(
            self.Api(), pr_number=17, implementation_sha=DEFAULT
        )
        self.assertEqual(
            {
                "eligible": True,
                "pr_number": 17,
                "default_branch": "master",
                "default_sha": DEFAULT,
                "base_branch": "ship/stable",
                "base_sha": BASE,
                "head_branch": "feature/ui",
                "head_sha": HEAD,
                "merge_sha": MERGE,
                "merge_tree": "e" * 40,
            },
            _source_outputs(current),
        )

    def test_trigger_rejects_legacy_event_path_repository_and_controller(self) -> None:
        invalid: list[dict[str, object]] = []
        legacy = run(51, event="pull_request")
        invalid.append(legacy)
        invalid.append(run(51, workflow="untrusted.yml"))
        foreign = run(51)
        foreign["head_repository"] = {"full_name": "attacker/repo"}
        invalid.append(foreign)
        invalid.append(run(51, branch="ship/stable"))
        invalid.append(run(51, head="f" * 40))
        for record in invalid:
            with self.subTest(record=record), self.assertRaises(NotEligible):
                resolve_pull_identity(
                    self.Api(run_record=record),
                    trigger_run_id=51,
                    implementation_sha=DEFAULT,
                )

    def test_trigger_and_pull_associations_are_exactly_one_and_same_repository(self) -> None:
        ambiguous = run(51)
        ambiguous["pull_requests"] = [{"number": 17}, {"number": 18}]
        with self.assertRaisesRegex(PrGateError, "exactly one"):
            resolve_pull_identity(
                self.Api(run_record=ambiguous),
                trigger_run_id=51,
                implementation_sha=DEFAULT,
            )
        with self.assertRaisesRegex(NotEligible, "wholly same-repository"):
            resolve_pull_identity(
                self.Api(fork_pull=True),
                trigger_run_id=51,
                implementation_sha=DEFAULT,
            )

    def test_default_implementation_and_ordered_merge_parents_fail_closed(self) -> None:
        with self.assertRaisesRegex(NotEligible, "default branch advanced"):
            resolve_pull_identity(
                self.Api(), pr_number=17, implementation_sha="f" * 40
            )
        api = self.Api()
        api.commit_identity = lambda _commit: ("e" * 40, (HEAD, BASE))
        with self.assertRaisesRegex(PrGateError, "exact current base/head parents"):
            resolve_pull_identity(api, pr_number=17, implementation_sha=DEFAULT)

    def test_release_sync_heads_are_explicitly_ineligible(self) -> None:
        api = self.Api(head_branch="automation/release-sync/abc")
        with self.assertRaisesRegex(NotEligible, "release synchronization"):
            resolve_pull_identity(api, trigger_run_id=51, implementation_sha=DEFAULT)


class ResolveSourceCliTests(unittest.TestCase):
    def arguments(self, output: Path) -> list[str]:
        return [
            "resolve-source",
            "--repository-name",
            "owner/repo",
            "--implementation-sha",
            DEFAULT,
            "--pr-number",
            "17",
            "--github-output",
            str(output),
        ]

    def test_cli_exports_every_authenticated_source_identity(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary) / "github-output"
            with mock.patch("scripts.ci.pr_gate.GitHubApi"), mock.patch(
                "scripts.ci.pr_gate.resolve_pull_identity",
                return_value=identity(base_branch="ship/stable"),
            ), mock.patch("sys.stdout"):
                self.assertEqual(0, pr_gate_main(self.arguments(output)))
            values = dict(
                line.split("=", 1)
                for line in output.read_text("utf-8").splitlines()
            )
            self.assertEqual("true", values["eligible"])
            self.assertEqual("17", values["pr_number"])
            self.assertEqual("master", values["default_branch"])
            self.assertEqual(DEFAULT, values["default_sha"])
            self.assertEqual("ship/stable", values["base_branch"])
            self.assertEqual(BASE, values["base_sha"])
            self.assertEqual("feature/ui", values["head_branch"])
            self.assertEqual(HEAD, values["head_sha"])
            self.assertEqual(MERGE, values["merge_sha"])
            self.assertEqual("e" * 40, values["merge_tree"])

    def test_ineligible_source_is_a_hard_gate_failure(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary) / "github-output"
            with mock.patch("scripts.ci.pr_gate.GitHubApi"), mock.patch(
                "scripts.ci.pr_gate.resolve_pull_identity",
                side_effect=NotEligible("stale source"),
            ), mock.patch("sys.stderr"):
                self.assertEqual(2, pr_gate_main(self.arguments(output)))
            self.assertFalse(output.exists())


class DispatchSourceTests(unittest.TestCase):
    CANDIDATE_BRANCH = "automation/release-sync/ship-stable"
    TARGET_BRANCH = "ship/stable"

    class Api:
        repository = "owner/repo"

        def __init__(
            self,
            *,
            default_sha: str = DEFAULT,
            target_sha: str = BASE,
            candidate_sha: str = MERGE,
            candidate_tree: str = "e" * 40,
            parents: tuple[str, ...] = (BASE, DEFAULT),
        ) -> None:
            self.branches = {
                "master": default_sha,
                DispatchSourceTests.TARGET_BRANCH: target_sha,
                DispatchSourceTests.CANDIDATE_BRANCH: candidate_sha,
            }
            self.candidate_tree = candidate_tree
            self.parents = parents

        def repository_record(self):
            return {"full_name": self.repository, "default_branch": "master"}

        def branch_sha(self, branch: str):
            return self.branches[branch]

        def commit_identity(self, commit: str):
            if commit != MERGE:
                raise AssertionError("unexpected candidate commit")
            return self.candidate_tree, self.parents

    @classmethod
    def arguments(cls, **overrides: object) -> dict[str, object]:
        values: dict[str, object] = {
            "implementation_sha": DEFAULT,
            "expected_source_sha": DEFAULT,
            "target_branch": cls.TARGET_BRANCH,
            "expected_target_sha": BASE,
            "candidate_branch": cls.CANDIDATE_BRANCH,
            "expected_candidate_sha": MERGE,
            "expected_candidate_tree": "e" * 40,
        }
        values.update(overrides)
        return values

    def test_exact_dispatch_source_exports_resolve_source_compatible_identity(self) -> None:
        current = resolve_dispatch_source(self.Api(), **self.arguments())
        self.assertEqual(
            {
                "eligible": True,
                "pr_number": 0,
                "default_branch": "master",
                "default_sha": DEFAULT,
                "base_branch": self.TARGET_BRANCH,
                "base_sha": BASE,
                "head_branch": self.CANDIDATE_BRANCH,
                "head_sha": MERGE,
                "merge_sha": MERGE,
                "merge_tree": "e" * 40,
            },
            _source_outputs(current),
        )

    def test_cli_exposes_the_exact_dispatch_contract(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary) / "github-output"
            argv = [
                "resolve-dispatch-source",
                "--repository-name",
                "owner/repo",
                "--implementation-sha",
                DEFAULT,
                "--expected-source-sha",
                DEFAULT,
                "--target-branch",
                self.TARGET_BRANCH,
                "--expected-target-sha",
                BASE,
                "--candidate-branch",
                self.CANDIDATE_BRANCH,
                "--expected-candidate-sha",
                MERGE,
                "--expected-candidate-tree",
                "e" * 40,
                "--github-output",
                str(output),
            ]
            with mock.patch("scripts.ci.pr_gate.GitHubApi"), mock.patch(
                "scripts.ci.pr_gate.resolve_dispatch_source",
                return_value=identity(
                    number=0,
                    base_branch=self.TARGET_BRANCH,
                    head_branch=self.CANDIDATE_BRANCH,
                    head_sha=MERGE,
                ),
            ), mock.patch("sys.stdout"):
                self.assertEqual(0, pr_gate_main(argv))
            values = dict(
                line.split("=", 1)
                for line in output.read_text("utf-8").splitlines()
            )
            self.assertEqual("0", values["pr_number"])
            self.assertEqual(MERGE, values["merge_sha"])
            self.assertEqual("e" * 40, values["merge_tree"])

    def test_default_source_target_candidate_and_tree_drift_are_ineligible(self) -> None:
        cases = (
            (self.Api(default_sha="f" * 40), self.arguments()),
            (self.Api(), self.arguments(expected_source_sha="f" * 40)),
            (self.Api(target_sha="f" * 40), self.arguments()),
            (self.Api(candidate_sha="f" * 40), self.arguments()),
            (self.Api(candidate_tree="f" * 40), self.arguments()),
        )
        for api, arguments in cases:
            with self.subTest(arguments=arguments), self.assertRaises(NotEligible):
                resolve_dispatch_source(api, **arguments)

    def test_dispatch_candidate_prefix_and_ordered_parents_fail_closed(self) -> None:
        with self.assertRaisesRegex(PrGateError, "protected prefix"):
            resolve_dispatch_source(
                self.Api(),
                **self.arguments(candidate_branch="feature/not-sync"),
            )
        with self.assertRaisesRegex(PrGateError, "ordered target/default parents"):
            resolve_dispatch_source(
                self.Api(parents=(DEFAULT, BASE)),
                **self.arguments(),
            )


class ControllerUpgradeAuthorizationTests(unittest.TestCase):
    class Api:
        repository = "AkaNebur/BlockPops"

        def __init__(self, comments: list[dict[str, object]], *, labelled: bool = True) -> None:
            self.comments = comments
            self.labelled = labelled

        def pull(self, number: int):
            return {
                "number": number,
                "labels": [{"name": "controller-upgrade"}] if self.labelled else [],
            }

        def repository_record(self):
            return {
                "full_name": self.repository,
                "owner": {"login": "AkaNebur", "type": "User"},
            }

        def issue_comments(self, _number: int):
            return self.comments

    @staticmethod
    def comment(
        comment_id: int,
        decision: str,
        *,
        head: str = HEAD,
        actor: str = "AkaNebur",
        association: str = "OWNER",
        updated: str = "2026-08-11T10:00:00Z",
    ) -> dict[str, object]:
        return {
            "id": comment_id,
            "body": f"/controller-upgrade {decision} {head}",
            "updated_at": updated,
            "author_association": association,
            "user": {"login": actor, "type": "User"},
        }

    @staticmethod
    def current() -> PullIdentity:
        return identity(
            default_sha=BASE,
            base_sha=BASE,
            head_branch="controller-upgrade/visual-gate",
        )

    def test_exact_current_head_owner_approval_is_digest_bound(self) -> None:
        api = self.Api([self.comment(91, "approve")])
        authorization = controller_upgrade_authorization(api, self.current())
        self.assertEqual(91, authorization.comment_id)
        self.assertEqual(HEAD, authorization.head_sha)
        self.assertRegex(authorization.digest, r"^[0-9a-f]{64}$")

    def test_latest_exact_head_revoke_wins(self) -> None:
        api = self.Api(
            [
                self.comment(91, "approve"),
                self.comment(92, "revoke", updated="2026-08-11T10:01:00Z"),
            ]
        )
        with self.assertRaisesRegex(PrGateError, "revokes"):
            controller_upgrade_authorization(api, self.current())

    def test_stale_or_non_owner_commands_cannot_authorize(self) -> None:
        for comment in (
            self.comment(91, "approve", head="f" * 40),
            self.comment(91, "approve", actor="collaborator", association="MEMBER"),
        ):
            with self.subTest(comment=comment), self.assertRaisesRegex(
                PrGateError, "lacks"
            ):
                controller_upgrade_authorization(self.Api([comment]), self.current())

    def test_label_and_exact_branch_prefix_are_both_required(self) -> None:
        with self.assertRaisesRegex(PrGateError, "label and branch prefix"):
            controller_upgrade_authorization(
                self.Api([self.comment(91, "approve")], labelled=False), self.current()
            )


class FinalReauthorizationTests(unittest.TestCase):
    def expected(self, **overrides: object) -> dict[str, object]:
        values: dict[str, object] = {
            "implementation_sha": DEFAULT,
            "expected_pr_number": 17,
            "expected_default_branch": "master",
            "expected_default_sha": DEFAULT,
            "expected_base_branch": "master",
            "expected_base_sha": BASE,
            "expected_head_branch": "feature/ui",
            "expected_head_sha": HEAD,
            "expected_merge_sha": MERGE,
            "expected_merge_tree": "e" * 40,
            "expected_policy_mode": "ordinary",
            "expected_authorization_digest": "0" * 64,
            "expected_authorization_comment_id": 0,
        }
        values.update(overrides)
        return values

    def test_ordinary_identity_and_policy_are_reauthenticated(self) -> None:
        with mock.patch(
            "scripts.ci.pr_gate.resolve_pull_identity", return_value=identity()
        ), mock.patch("scripts.ci.pr_gate._upgrade_requested", return_value=False):
            value = reauthorize(mock.Mock(), **self.expected())
        self.assertTrue(value["authorization_current"])
        self.assertEqual(HEAD, value["head_sha"])

    def test_any_changed_branch_sha_or_tree_identity_is_ineligible_before_app_token(self) -> None:
        mutations = (
            identity(default_branch="mainline"),
            identity(default_sha="f" * 40),
            identity(base_branch="ship/other"),
            identity(base_sha="f" * 40),
            identity(head_branch="feature/renamed"),
            identity(head_sha="f" * 40),
            identity(merge_sha="f" * 40),
            identity(merge_tree="f" * 40),
        )
        for moved in mutations:
            with self.subTest(moved=moved), mock.patch(
                "scripts.ci.pr_gate.resolve_pull_identity", return_value=moved
            ):
                with self.assertRaisesRegex(NotEligible, "identity changed"):
                    reauthorize(mock.Mock(), **self.expected())

    def test_upgrade_digest_and_comment_must_still_match(self) -> None:
        authorization = UpgradeAuthorization(91, "2026-08-11T10:00:00Z", HEAD, "9" * 64)
        expected = self.expected(
            expected_policy_mode="controller-upgrade",
            expected_authorization_digest=authorization.digest,
            expected_authorization_comment_id=authorization.comment_id,
        )
        with mock.patch(
            "scripts.ci.pr_gate.resolve_pull_identity", return_value=identity()
        ), mock.patch(
            "scripts.ci.pr_gate.controller_upgrade_authorization",
            return_value=authorization,
        ):
            self.assertTrue(reauthorize(mock.Mock(), **expected)["authorization_current"])
        with mock.patch(
            "scripts.ci.pr_gate.resolve_pull_identity", return_value=identity()
        ), mock.patch(
            "scripts.ci.pr_gate.controller_upgrade_authorization",
            return_value=UpgradeAuthorization(92, authorization.comment_updated_at, HEAD, "8" * 64),
        ):
            self.assertFalse(reauthorize(mock.Mock(), **expected)["authorization_current"])


class TreePolicyTests(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.repository = Path(self.temporary.name)
        self.git("init", "-q", "-b", "master")
        self.git("config", "user.name", "Tests")
        self.git("config", "user.email", "tests@invalid.test")
        for relative in EXACT_BASE_OWNED_PATHS:
            target = self.repository / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            source = REPO / relative
            if source.is_file():
                shutil.copyfile(source, target)
            else:
                target.write_text("base-owned\n", encoding="utf-8")
        self.git("add", ".")
        self.git("commit", "-qm", "base")
        self.base = self.git("rev-parse", "HEAD")

    def tearDown(self) -> None:
        self.temporary.cleanup()

    def git(self, *arguments: str) -> str:
        return subprocess.check_output(
            ["git", "-C", str(self.repository), *arguments], text=True
        ).strip()

    def merged(self, mutation: str | None = None) -> PullIdentity:
        self.git("switch", "-qc", "feature")
        if mutation is None:
            (self.repository / "product.txt").write_text("candidate\n", encoding="utf-8")
        else:
            path = self.repository / mutation
            path.write_text(path.read_text("utf-8") + "candidate\n", encoding="utf-8")
        self.git("add", ".")
        self.git("commit", "-qm", "candidate")
        head = self.git("rev-parse", "HEAD")
        self.git("switch", "-q", "master")
        self.git("merge", "--no-ff", "--no-edit", "feature")
        merge = self.git("rev-parse", "HEAD")
        tree = self.git("rev-parse", "HEAD^{tree}")
        return identity(
            default_sha=self.base,
            default_branch=MATRIX_IDENTITY["canonical"],
            base_branch=MATRIX_IDENTITY["name"],
            base_sha=self.base,
            head_sha=head,
            merge_sha=merge,
            merge_tree=tree,
        )

    def test_default_to_base_and_base_to_merge_parity_are_both_required(self) -> None:
        current = self.merged()
        with mock.patch("scripts.ci.pr_gate.validate_controller_parity") as parity:
            matrix = validate_pr_tree(self.repository, current)
        self.assertEqual((REPO / "release/release-matrix.json").read_bytes(), matrix)
        self.assertEqual(
            [
                mock.call(self.repository.resolve(), protected_sha=self.base, candidate_sha=self.base),
                mock.call(self.repository.resolve(), protected_sha=self.base, candidate_sha=current.merge_sha),
            ],
            parity.call_args_list,
        )

    def test_matrix_verification_and_version_specific_mutations_fail_closed(self) -> None:
        for path in EXACT_BASE_OWNED_PATHS:
            with self.subTest(path=path):
                # Each mutation needs an isolated repository because merged() advances master.
                self.tearDown()
                self.setUp()
                current = self.merged(path)
                with mock.patch("scripts.ci.pr_gate.validate_controller_parity"):
                    with self.assertRaisesRegex(PrGateError, "base-owned"):
                        validate_pr_tree(self.repository, current)

    def test_enrolled_opaque_release_base_is_authenticated_from_default_policy(self) -> None:
        # Start again from the integration base, then create a branch-local release matrix whose
        # name contains no Minecraft or loader assumptions.
        self.tearDown()
        self.setUp()
        integration = self.base
        self.git("switch", "-qc", "ship/aurora-ui")
        matrix_path = self.repository / "release/release-matrix.json"
        matrix = json.loads(matrix_path.read_text("utf-8"))
        matrix["branch"] = {
            "role": "release",
            "name": "ship/aurora-ui",
            "canonical": "master",
            "sync": {"enabled": True, "source": "master"},
        }
        matrix_path.write_text(json.dumps(matrix) + "\n", encoding="utf-8")
        self.git("add", "release/release-matrix.json")
        self.git("commit", "-qm", "opaque release matrix")
        release_base = self.git("rev-parse", "HEAD")
        self.git("switch", "-qc", "ordinary-change")
        (self.repository / "product.txt").write_text("candidate\n", encoding="utf-8")
        self.git("add", "product.txt")
        self.git("commit", "-qm", "ordinary release PR")
        head = self.git("rev-parse", "HEAD")
        self.git("switch", "-q", "ship/aurora-ui")
        self.git("merge", "--no-ff", "--no-edit", "ordinary-change")
        merge = self.git("rev-parse", "HEAD")
        current = identity(
            default_sha=integration,
            base_branch="ship/aurora-ui",
            base_sha=release_base,
            head_sha=head,
            merge_sha=merge,
            merge_tree=self.git("rev-parse", "HEAD^{tree}"),
        )
        with mock.patch("scripts.ci.pr_gate.validate_controller_parity") as parity:
            validate_pr_tree(self.repository, current)
        self.assertEqual(
            [
                mock.call(
                    self.repository.resolve(),
                    protected_sha=integration,
                    candidate_sha=release_base,
                ),
                mock.call(
                    self.repository.resolve(),
                    protected_sha=release_base,
                    candidate_sha=merge,
                ),
            ],
            parity.call_args_list,
        )


class RestrictedTransitionTreeTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.repository = Path(self.temporary.name)
        self.git("init", "-q", "-b", "master")
        self.git("config", "user.name", "Tests")
        self.git("config", "user.email", "tests@invalid.test")
        for path in ("release/release-matrix.json", "settings.gradle", "scripts/ci/pr_gate.py"):
            self.write(path, "base\n")
        self.git("add", ".")
        self.git("commit", "-qm", "base")
        self.base = self.git("rev-parse", "HEAD")
        self.addCleanup(self.temporary.cleanup)

    def git(self, *arguments):
        return subprocess.check_output(["git", "-C", str(self.repository), *arguments], text=True).strip()

    def write(self, path, content):
        target = self.repository / path
        target.parent.mkdir(parents=True, exist_ok=True)
        if target.is_symlink() or target.is_file():
            target.unlink()
        if content is not None:
            target.write_text(content, encoding="utf-8")

    def merged(self, changes, *, mode=None, head_base=None, merge_tree=None):
        self.git("switch", "--discard-changes", "-qC", "candidate", head_base or self.base)
        for path, content in changes.items():
            self.write(path, content)
        self.git("add", "-A")
        if mode is not None:
            path, bits = mode
            oid = self.base if bits == "160000" else self.git("hash-object", "-w", "settings.gradle")
            self.git("update-index", "--add", "--cacheinfo", f"{bits},{oid},{path}")
        self.git("commit", "--allow-empty", "-qm", "candidate")
        head = self.git("rev-parse", "HEAD")
        tree = merge_tree or self.git("rev-parse", "HEAD^{tree}")
        merge = self.git("commit-tree", tree, "-p", self.base, "-p", head, "-m", "synthetic merge")
        self.git("checkout", "-qf", merge)
        return identity(default_sha=self.base, base_sha=self.base, head_sha=head,
                        merge_sha=merge, merge_tree=tree)

    def validate(self, current, paths, *, scope="stonecutter-bootstrap"):
        declaration = {"schema_version": 1, "controller_generation": 1, "scope": scope,
                       "paths": sorted(paths), "controller_sha": self.base,
                       "base_sha": self.base, "head_sha": current.head_sha}
        return validate_restricted_transition_tree(
            self.repository, current, declaration=json.dumps(declaration).encode(),
            deployed_controller_sha=self.base, deployed_generation=1)

    def test_exact_add_modify_delete_and_immutable_objects(self):
        current = self.merged({"settings.gradle": "changed\n", "stonecutter.gradle": "new\n"})
        self.write("settings.gradle", "uncommitted attacker bytes\n")
        result = self.validate(current, ["settings.gradle", "stonecutter.gradle"])
        self.assertEqual(("settings.gradle", "stonecutter.gradle"), result.paths)
        self.git("restore", "settings.gradle")
        current = self.merged({"settings.gradle": None})
        self.validate(current, ["settings.gradle"])

    def test_extras_authority_updates_undeclared_and_unchanged_paths_fail(self):
        for changes, declared in (
            ({"settings.gradle": "next", "product.txt": "extra"}, ["settings.gradle"]),
            ({"settings.gradle": "next", "scripts/ci/pr_gate.py": "self-authorize"}, ["settings.gradle"]),
            ({"settings.gradle": "next"}, ["settings.gradle", "stonecutter.gradle"]),
            ({"stonecutter.gradle": "next"}, ["settings.gradle"]),
            ({}, ["settings.gradle"]),
        ):
            with self.subTest(changes=changes), self.assertRaises(PrGateError):
                self.validate(self.merged(changes), declared)

    def test_rename_detection_cannot_widen_scope(self):
        self.git("config", "diff.renames", "true")
        current = self.merged({"settings.gradle": None, "outside.gradle": "base\n"})
        with self.assertRaises(PrGateError):
            self.validate(current, ["settings.gradle"])

    def test_symlink_gitlink_and_executable_entries_fail(self):
        for mode in ("120000", "160000", "100755"):
            with self.subTest(mode=mode):
                current = self.merged({}, mode=("stonecutter.gradle", mode))
                with self.assertRaisesRegex(PrGateError, "non-executable blobs"):
                    self.validate(current, ["stonecutter.gradle"])

    def test_local_config_cannot_hide_an_out_of_scope_submodule(self):
        self.git("config", "diff.ignoreSubmodules", "all")
        current = self.merged({"settings.gradle": "next"}, mode=("hidden", "160000"))
        with self.assertRaises(PrGateError):
            self.validate(current, ["settings.gradle"])

    def test_replacement_base_cannot_hide_an_extra_candidate_path(self):
        current = self.merged({"settings.gradle": "next", "product.txt": "extra"})
        base_blob = self.git("rev-parse", f"{self.base}:settings.gradle")
        self.git("read-tree", current.merge_sha)
        self.git("update-index", "--cacheinfo", f"100644,{base_blob},settings.gradle")
        replacement = self.git("commit-tree", self.git("write-tree"), "-m", "forged base")
        self.git("replace", self.base, replacement)
        with self.assertRaises(PrGateError):
            self.validate(current, ["settings.gradle"])

    def test_grafts_cannot_invent_base_ancestry(self):
        base_tree = self.git("rev-parse", f"{self.base}^{{tree}}")
        unrelated = self.git("commit-tree", base_tree, "-m", "unrelated root")
        current = self.merged({"settings.gradle": "next"}, head_base=unrelated)
        grafts = self.repository / ".git/info/grafts"
        grafts.write_text(f"{current.head_sha} {self.base}\n", encoding="utf-8")
        with self.assertRaises(PrGateError):
            self.validate(current, ["settings.gradle"])

    def test_mode_and_type_changes_fail_even_for_an_exact_declared_path(self):
        for mode in ("100755", "120000", "160000"):
            with self.subTest(mode=mode):
                current = self.merged({}, mode=("settings.gradle", mode))
                with self.assertRaises(PrGateError):
                    self.validate(current, ["settings.gradle"])

    def test_stale_checkout_tree_parents_and_divergent_head_fail(self):
        current = self.merged({"settings.gradle": "next"})
        with self.assertRaisesRegex(PrGateError, "tree disagrees"):
            self.validate(replace(current, merge_tree="f" * 40), ["settings.gradle"])
        self.git("checkout", "-q", current.head_sha)
        with self.assertRaisesRegex(PrGateError, "exact current synthetic merge"):
            self.validate(current, ["settings.gradle"])
        bad_merge = self.git("commit-tree", current.merge_tree, "-p", current.head_sha,
                             "-p", self.base, "-m", "reordered")
        self.git("checkout", "-q", bad_merge)
        with self.assertRaisesRegex(PrGateError, "stale or reordered parents"):
            self.validate(replace(current, merge_sha=bad_merge), ["settings.gradle"])
        base_tree = self.git("rev-parse", f"{self.base}^{{tree}}")
        mismatch = self.merged({"settings.gradle": "next"}, merge_tree=base_tree)
        with self.assertRaisesRegex(PrGateError, "different trees"):
            self.validate(mismatch, ["settings.gradle"])
        unrelated = self.git("commit-tree", base_tree, "-m", "unrelated root")
        divergent = self.merged({"settings.gradle": "next"}, head_base=unrelated)
        with self.assertRaises(PrGateError):
            self.validate(divergent, ["settings.gradle"])


class ControllerUpgradeTreeTests(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.repository = Path(self.temporary.name)
        self.git("init", "-q", "-b", "master")
        self.git("config", "user.name", "Tests")
        self.git("config", "user.email", "tests@invalid.test")
        files = set(EXACT_BASE_OWNED_PATHS) | CONTROLLER_UPGRADE_REQUIRED | {
            "docs/operations.md"
        }
        for relative in sorted(files):
            target = self.repository / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            source = REPO / relative
            if source.is_file():
                shutil.copyfile(source, target)
            else:
                target.write_text("base-owned\n", encoding="utf-8")
        matrix_path = self.repository / "release/release-matrix.json"
        matrix = json.loads(matrix_path.read_text("utf-8"))
        canonical = matrix["branch"]["canonical"]
        self.canonical = canonical
        matrix["branch"] = {
            "role": "integration",
            "name": canonical,
            "canonical": canonical,
            "sync": {"enabled": False, "source": canonical},
        }
        matrix_path.write_text(json.dumps(matrix, indent=2) + "\n", encoding="utf-8")
        self.matrix_bytes = matrix_path.read_bytes()
        self.git("add", ".")
        self.git("commit", "-qm", "protected controller baseline")
        self.base = self.git("rev-parse", "HEAD")

    def tearDown(self) -> None:
        self.temporary.cleanup()

    def git(self, *arguments: str) -> str:
        return subprocess.check_output(
            ["git", "-C", str(self.repository), *arguments], text=True
        ).strip()

    def controller_merge(
        self, path: str, *, executable: bool = False, symlink: bool = False
    ) -> PullIdentity:
        self.git("switch", "-qC", "controller-upgrade/visual-gate", self.base)
        target = self.repository / path
        target.parent.mkdir(parents=True, exist_ok=True)
        if symlink:
            target.symlink_to("outside")
        elif target.exists():
            target.write_text(target.read_text("utf-8") + "candidate\n", encoding="utf-8")
        else:
            target.write_text("candidate\n", encoding="utf-8")
        if executable:
            target.chmod(0o755)
        self.git("add", "--", path)
        self.git("commit", "-qm", "controller candidate")
        head = self.git("rev-parse", "HEAD")
        self.git("switch", "-qC", "master", self.base)
        self.git("merge", "--no-ff", "--no-edit", "controller-upgrade/visual-gate")
        merge = self.git("rev-parse", "HEAD")
        return identity(
            default_sha=self.base,
            default_branch=self.canonical,
            base_branch=self.canonical,
            base_sha=self.base,
            head_branch="controller-upgrade/visual-gate",
            head_sha=head,
            merge_sha=merge,
            merge_tree=self.git("rev-parse", "HEAD^{tree}"),
        )

    def validate(self, current: PullIdentity) -> bytes:
        with mock.patch("scripts.ci.pr_gate.validate_controller_parity"), mock.patch(
            "scripts.ci.pr_gate.validate_loader_bootstrap_commit"
        ) as bootstrap:
            matrix = validate_controller_upgrade_tree(self.repository, current)
        bootstrap.assert_called_once_with(
            self.repository.resolve(),
            head_sha=current.merge_sha,
            contract_sha=current.base_sha,
        )
        return matrix

    def test_docs_and_controller_roots_use_old_loader_contract(self) -> None:
        current = self.controller_merge("docs/operations.md")
        self.assertEqual(self.matrix_bytes, self.validate(current))
        matrix = json.loads(self.matrix_bytes)
        loader = sorted({row["loader"] for row in matrix["artifacts"]})[0]
        loader_paths = frozenset({f"{loader}/build.gradle", f"{loader}/src/e2e"})
        self.assertTrue(_upgrade_path_allowed(f"{loader}/src/e2e/NewProbe.java", loader_paths))
        self.assertFalse(_upgrade_path_allowed(f"{loader}/src/main/Product.java", loader_paths))
        self.assertFalse(_upgrade_path_allowed(f"{loader}/build.gradle/nested", loader_paths))
        self.assertFalse(_upgrade_path_allowed(".github/CODEOWNERS/nested", loader_paths))

    def test_product_matrix_verification_shim_and_forbidden_paths_fail_closed(self) -> None:
        rejected = (
            "common/src/main/java/Product.java",
            "release/release-matrix.json",
            "gradle/verification-metadata.xml",
            "common/src/e2e/java/com/theplumteam/e2e/VanillaShim.java",
            ".gradle/injected.txt",
        )
        for path in rejected:
            with self.subTest(path=path):
                current = self.controller_merge(path)
                with mock.patch("scripts.ci.pr_gate.validate_controller_parity"):
                    with self.assertRaises(PrGateError):
                        validate_controller_upgrade_tree(self.repository, current)

    def test_added_symlinks_and_executable_blobs_fail_closed(self) -> None:
        for path, options in (
            ("scripts/ci/unsafe-link", {"symlink": True}),
            ("scripts/ci/unsafe-executable.py", {"executable": True}),
        ):
            with self.subTest(path=path):
                current = self.controller_merge(path, **options)
                with mock.patch("scripts.ci.pr_gate.validate_controller_parity"):
                    with self.assertRaisesRegex(PrGateError, "unsafe"):
                        validate_controller_upgrade_tree(self.repository, current)


class ResultAndWorkflowContractTests(unittest.TestCase):
    def test_contexts_are_fixed_and_result_is_source_head_bound(self) -> None:
        value = _result(
            identity(),
            {
                "build": GateResult("success", "accepted", 1, 2),
                "e2e": GateResult("pending", "waiting", 3, 4),
            },
        )
        self.assertEqual(HEAD, value["head_sha"])
        self.assertEqual("master", value["default_branch"])
        self.assertEqual("master", value["base_branch"])
        self.assertEqual("feature/ui", value["head_branch"])
        self.assertEqual("e" * 40, value["merge_tree"])
        self.assertEqual(CONTEXTS["build"], value["gates"]["build"]["context"])
        self.assertEqual(CONTEXTS["e2e"], value["gates"]["e2e"]["context"])

    def test_workflow_uses_protected_evaluator_and_fresh_status_only_app_writer(self) -> None:
        workflow = (REPO / ".github/workflows/handle-pr-gate-result.yml").read_text("utf-8")
        self.assertIn("workflow_run:", workflow)
        self.assertIn("issue_comment:", workflow)
        self.assertIn("- created\n      - edited\n      - deleted", workflow)
        self.assertIn("github.event.comment.author_association == 'OWNER'", workflow)
        self.assertIn("types:\n      - requested\n      - in_progress\n      - completed", workflow)
        self.assertIn("permissions: {}", workflow)
        self.assertIn("validate", (REPO / "scripts/ci/pr_gate.py").read_text("utf-8"))
        publish = workflow.split("  publish:", 1)[1]
        self.assertIn("environment: pr-gate", publish)
        self.assertIn("contents: read", publish)
        self.assertIn("issues: read", publish)
        self.assertIn("pull-requests: read", publish)
        self.assertIn("ref: ${{ github.sha }}", publish)
        self.assertIn("persist-credentials: false", publish)
        self.assertNotIn("path: candidate", publish)
        for argument in (
            "--expected-default-branch",
            "--expected-base-branch",
            "--expected-head-branch",
            "--expected-merge-tree",
        ):
            self.assertIn(argument, publish)
        self.assertLess(publish.index("pr_gate.py reauthorize"), publish.index("Mint one status-only"))
        self.assertIn(
            "actions/create-github-app-token@bcd2ba49218906704ab6c1aa796996da409d3eb1",
            publish,
        )
        self.assertIn("client-id: ${{ vars.PR_GATE_APP_CLIENT_ID }}", publish)
        self.assertIn("private-key: ${{ secrets.PR_GATE_APP_PRIVATE_KEY }}", publish)
        self.assertIn("permission-statuses: write", publish)
        self.assertEqual(4, publish.count('publish_status "Trusted PR /'))
        status_step = publish.split("Publish only the two fixed exact-head contexts", 1)[1]
        self.assertNotIn("github.token", status_step)
        self.assertIn("authorization_current", publish)
        self.assertIn(
            "github.event.workflow_run.event == 'pull_request_target'", workflow
        )
        self.assertNotIn("github.event.workflow_run.event == 'pull_request'", workflow)
        self.assertNotIn("\n  pull_request_target:", workflow)

    def test_codeowners_is_additional_control_plane_review_not_check_provenance(self) -> None:
        codeowners = (REPO / ".github/CODEOWNERS").read_text("utf-8")
        self.assertIn("/.github/ @AkaNebur", codeowners)
        self.assertIn("/scripts/ci/ @AkaNebur", codeowners)
        self.assertIn("/tests/ @AkaNebur", codeowners)
        for loader in ("fabric", "forge", "neoforge"):
            self.assertIn(f"/{loader}/src/e2e/ @AkaNebur", codeowners)


if __name__ == "__main__":
    unittest.main()

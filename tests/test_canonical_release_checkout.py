"""Real Git/bootstrap bytes bind the implementation location to simulated GitHub."""

import copy
import importlib.util
import json
import os
from pathlib import Path
import shutil
import unittest
from unittest.mock import patch

from scripts.ci.tests import test_loader_bootstrap as bootstrap_tests
from scripts.ci.tests.matrix_fixtures import schema2_configuration
from scripts.release import plan_release

ROOT = Path(__file__).resolve().parents[1]


class Api:
    repository = "AkaNebur/BlockPops"
    api_url = "https://api.github.com"

    def __init__(self, head, tree):
        self.record = {"full_name": self.repository, "default_branch": "master"}
        self.head = (head, tree)
        self.reads = 0
        self.after_head = lambda: None
        self.before_record = lambda: None

    def get(self, path):
        if path != "/repos/" + self.repository:
            raise AssertionError(path)
        self.reads += 1
        self.before_record()
        return copy.deepcopy(self.record)

    def branch_head(self, branch):
        if branch != self.record["default_branch"]:
            raise AssertionError(branch)
        result = self.head
        self.after_head()
        return result


class CanonicalCheckoutTests(unittest.TestCase):
    def setUp(self):
        environment = {name: value for name, value in os.environ.items() if not name.startswith("GIT_")}
        isolated = patch.dict(os.environ, environment, clear=True)
        isolated.start()
        self.addCleanup(isolated.stop)
        self.fixture = bootstrap_tests.LoaderBootstrapTests(methodName="runTest")
        self.fixture.setUp()
        self.addCleanup(self.fixture.tearDown)
        self.root = self.fixture.repository.resolve()
        bootstrap_tests._run(self.root, "config", "core.autocrlf", "false")
        matrix = schema2_configuration()
        nodes = set(matrix["migration"]["legacy_nodes"])
        for key in ("artifacts", "runtimes"):
            matrix[key] = [row for row in matrix[key] if row["artifact_node"] in nodes]
        matrix["lane_count"] = len(nodes)
        installers = {row["installer"] for row in matrix["runtimes"]}
        matrix["installers"] = {key: value for key, value in matrix["installers"].items() if key in installers}
        del matrix["source_routing"]["neoforge"]
        plan_release.normalize_matrix_inventory(matrix)
        self.matrix = self.root / "release/release-matrix.json"
        self.matrix.write_text(json.dumps(matrix))
        shutil.copyfile(ROOT / "e2e/scenario-contract.json", self.root / "e2e/scenario-contract.json")
        self.implementation = self.root / "scripts/release/plan_release.py"
        self.implementation.parent.mkdir(parents=True)
        shutil.copyfile(plan_release.__file__, self.implementation)
        self.refresh()
        spec = importlib.util.spec_from_file_location("canonical_checkout_fixture", self.implementation)
        self.module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(self.module)
        self.assertEqual(self.implementation, Path(self.module.__file__))

    def refresh(self):
        head = self.fixture.commit("canonical implementation fixture")
        self.api = Api(head, bootstrap_tests._run(self.root, "rev-parse", "HEAD^{tree}"))

    def observe(self):
        return self.module.authenticate_canonical_checkout(self.api)

    def test_real_implementation_root_and_current_bootstrap_are_bound_without_authority(self):
        result = self.observe()
        self.assertEqual((self.api.head[0], self.api.head[1], "master"),
                         (result["commit"], result["tree"], result["default_branch"]))
        self.assertEqual(str(self.root), result["checkout"])
        self.assertEqual(self.api.head[0], result["bootstrap"]["contract_sha"])
        self.assertEqual({"fabric", "forge"}, set(result["bootstrap"]["verified"]))
        self.assertEqual(result["matrix"]["sha256"], result["bootstrap"]["matrix_sha256"])
        self.assertEqual("scripts/release/plan_release.py", result["implementation"]["path"])
        self.assertEqual(2, self.api.reads)
        self.assertFalse({"qualified", "authorized", "deployed_generation", "publication"} & set(result))
        with self.assertRaises(TypeError):
            self.module.authenticate_canonical_checkout(self.api, repository=self.root)
        with self.assertRaises(TypeError):
            self.module.authenticate_canonical_checkout(self.api, controller_sha=self.api.head[0])

    def test_api_origin_repository_default_and_exact_objects_are_not_caller_claims(self):
        original = self.api
        for key, value in (("api_url", "https://example.invalid"), ("repository", "foreign/repo"),
                           ("head", ("a" * 40, original.head[1])), ("head", (original.head[0], "b" * 40)),
                           ("head", (True, original.head[1]))):
            self.api = copy.deepcopy(original)
            setattr(self.api, key, value)
            with self.subTest(key=key, value=value), self.assertRaises(self.module.ReleaseEvidenceError):
                self.observe()
        for changes in ({"full_name": "foreign/repo"}, {"default_branch": "other"},
                        {"default_branch": "../master"}):
            self.api = copy.deepcopy(original)
            self.api.record.update(changes)
            with self.subTest(changes=changes), self.assertRaises(self.module.ReleaseEvidenceError):
                self.observe()

    def test_dirty_hidden_raw_bytes_and_untracked_implementation_inputs_are_rejected(self):
        raw = self.implementation.read_bytes()
        for hidden in (False, True):
            if hidden:
                bootstrap_tests._run(self.root, "update-index", "--assume-unchanged", "scripts/release/plan_release.py")
            self.implementation.write_bytes(raw + b"\n# changed raw input\n")
            with self.subTest(hidden=hidden), self.assertRaises(self.module.ReleaseEvidenceError):
                self.observe()
            self.implementation.write_bytes(raw)
        bootstrap_tests._run(self.root, "update-index", "--no-assume-unchanged", "scripts/release/plan_release.py")
        (self.implementation.parent / "untracked.py").write_text("unreviewed = True\n")
        with self.assertRaises(self.module.ReleaseEvidenceError): self.observe()

    def test_inherited_git_redirections_are_rejected_before_any_api_or_git_read(self):
        for name in ("GIT_DIR", "GIT_WORK_TREE", "GIT_INDEX_FILE", "GIT_OBJECT_DIRECTORY",
                     "GIT_CONFIG_COUNT", "GIT_CONFIG_GLOBAL", "GIT_CONFIG_PARAMETERS", "GIT_GRAFT_FILE"):
            value = "private-sentinel-never-report"
            with self.subTest(name=name), patch.dict(os.environ, {name: value}):
                with self.assertRaises(self.module.ReleaseEvidenceError) as error:
                    self.observe()
                self.assertNotIn(value, str(error.exception))
                self.assertEqual(0, self.api.reads)
        with patch.dict(os.environ, {"GIT_DIR": str(ROOT / ".git"), "GIT_WORK_TREE": str(ROOT)}):
            with self.assertRaises(self.module.ReleaseEvidenceError): self.observe()
            self.assertEqual(0, self.api.reads)
        def redirected():
            os.environ["GIT_DIR"] = "private-sentinel-never-report"
        self.api.after_head = redirected
        with patch.dict(os.environ):
            with self.assertRaises(self.module.ReleaseEvidenceError): self.observe()

    def test_live_default_and_local_matrix_drift_are_rechecked(self):
        for drift in ("default", "head", "matrix", "environment"):
            original = self.matrix.read_bytes()
            self.api = Api(*self.api.head)
            def changed():
                if self.api.reads != 2: return
                if drift == "default": self.api.record["default_branch"] = "renamed"
                elif drift == "head": self.api.head = ("f" * 40, self.api.head[1])
                elif drift == "matrix": self.matrix.write_bytes(original + b" ")
                else: os.environ["GIT_WORK_TREE"] = "untrusted"
            self.api.before_record = changed
            with self.subTest(drift=drift), patch.dict(os.environ), self.assertRaises(self.module.ReleaseEvidenceError):
                self.observe()
            self.matrix.write_bytes(original)
            self.api = Api(bootstrap_tests._run(self.root, "rev-parse", "HEAD"),
                           bootstrap_tests._run(self.root, "rev-parse", "HEAD^{tree}"))

    def test_bootstrap_or_canonical_matrix_mismatch_cannot_become_valid_by_committing(self):
        build = self.root / "fabric/build.gradle"
        original = build.read_bytes()
        build.write_bytes(b"// unauthorized executable change\n" + original)
        self.refresh()
        with self.assertRaises(self.module.ReleaseEvidenceError): self.observe()
        build.write_bytes(original)
        matrix = json.loads(self.matrix.read_bytes())
        matrix["branch"].update(name="release/other", role="release",
                               sync={"enabled": True, "source": "master"})
        self.matrix.write_text(json.dumps(matrix)); self.refresh()
        with self.assertRaises(self.module.ReleaseEvidenceError): self.observe()

    def test_current_transition_is_reported_without_accepting_proposed_next_bytes(self):
        _, _, _ = self.fixture.prepare_transition()
        self.api = Api(bootstrap_tests._run(self.root, "rev-parse", "HEAD"),
                       bootstrap_tests._run(self.root, "rev-parse", "HEAD^{tree}"))
        result = self.observe()
        self.assertEqual("current", result["bootstrap"]["transition"]["phase"])
        self.assertNotIn("controller_generation", result)


if __name__ == "__main__":
    unittest.main()

from __future__ import annotations

import copy
import hashlib
import json
import os
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path
from unittest import mock

from scripts.ci.loader_bootstrap import (
    HARNESS_BINDING,
    LoaderBootstrapError,
    load_contract_bytes,
    validate_commit,
    validate_transition,
)
from scripts.release.matrix import load_matrix


REPO = Path(__file__).resolve().parents[3]


def _run(repository: Path, *arguments: str) -> str:
    return subprocess.run(
        ["git", "-C", str(repository), *arguments],
        check=True,
        stdout=subprocess.PIPE,
        text=True,
    ).stdout.strip()


class LoaderBootstrapTests(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.repository = Path(self.temporary.name)
        _run(self.repository, "init", "-q", "-b", "fixture")
        _run(self.repository, "config", "user.name", "BlockPops Tests")
        _run(self.repository, "config", "user.email", "tests@blockpops.invalid")
        matrix = load_matrix(
            REPO / "release/release-matrix.json",
            validate_sources=False,
        )
        paths = {
            "release/release-matrix.json",
            "e2e/loader-bootstrap-contract.json",
        }
        for loader in {row["loader"] for row in matrix["artifacts"]}:
            paths.add(f"{loader}/build.gradle")
            paths.update(
                path.relative_to(REPO).as_posix()
                for path in (REPO / loader / "src/e2e").rglob("*")
                if path.is_file()
            )
        for relative in sorted(paths):
            target = self.repository / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(REPO / relative, target)
        self.head = self.commit("valid active loader bootstrap")

    def tearDown(self) -> None:
        self.temporary.cleanup()

    def commit(self, message: str) -> str:
        _run(self.repository, "add", "-A")
        _run(self.repository, "commit", "-q", "-m", message)
        return _run(self.repository, "rev-parse", "HEAD")

    def contract(self) -> dict:
        return json.loads(
            (self.repository / "e2e/loader-bootstrap-contract.json").read_text()
        )

    def write_contract(self, value: dict) -> None:
        (self.repository / "e2e/loader-bootstrap-contract.json").write_text(
            json.dumps(value, indent=2, sort_keys=True, allow_nan=False) + "\n",
            encoding="utf-8",
        )

    def prepare_transition(self) -> tuple[str, dict, dict[str, bytes]]:
        current = self.contract()
        next_contract = copy.deepcopy(current)
        build_path = "fabric/build.gradle"
        java_path = next(path for path in current["loaders"]["fabric"]["files"] if path.endswith(".java"))
        changes = {path: b"// next generation\n" + (self.repository / path).read_bytes()
                   for path in (build_path, java_path)}
        next_contract["loaders"]["fabric"]["build_sha256"] = hashlib.sha256(changes[build_path]).hexdigest()
        next_contract["loaders"]["fabric"]["files"][java_path] = hashlib.sha256(changes[java_path]).hexdigest()
        self.write_contract({"schema_version": 2, "generation": 1, "loader": "fabric",
                             "current": current, "next": next_contract})
        return self.commit("contract first current and next"), next_contract, changes

    def test_transition_self_gate_accepts_current_and_never_authorizes_next(self) -> None:
        base, _, changes = self.prepare_transition()
        report = validate_commit(self.repository, head_sha=base)
        self.assertEqual("current", report["transition"]["phase"])
        for path, payload in changes.items():
            (self.repository / path).write_bytes(payload)
        candidate = self.commit("next bytes without protected collapse")
        for authority in (None, base, self.head):
            with self.subTest(authority=authority), self.assertRaises(LoaderBootstrapError):
                validate_commit(self.repository, head_sha=candidate, contract_sha=authority)

    def test_exact_next_requires_separate_ancestor_contract_and_collapse(self) -> None:
        base, next_contract, changes = self.prepare_transition()
        for path, payload in changes.items():
            (self.repository / path).write_bytes(payload)
        self.write_contract(next_contract)
        candidate = self.commit("exact next and collapse")
        report = validate_transition(self.repository, head_sha=candidate, base_sha=base)
        self.assertEqual((base, candidate), (report["contract_sha"], report["head_sha"]))
        self.assertEqual(("fabric", "next"),
                         (report["transition"]["loader"], report["transition"]["phase"]))
        self.assertEqual({"fabric", "forge"}, set(report["verified"]))
        with self.assertRaises(LoaderBootstrapError):
            validate_commit(self.repository, head_sha=candidate, contract_sha=base)
        unrelated = _run(self.repository, "commit-tree", f"{base}^{{tree}}", "-m", "unrelated authority")
        for authority in (candidate, self.head, unrelated):
            with self.subTest(authority=authority), self.assertRaises(LoaderBootstrapError):
                validate_transition(self.repository, head_sha=candidate, base_sha=authority)

    def test_transition_rejects_current_mixed_and_out_of_scope_candidates(self) -> None:
        base, next_contract, changes = self.prepare_transition()
        for case in ("current", "mixed", "other loader", "matrix", "wrong next", "uncollapsed", "extra file"):
            _run(self.repository, "reset", "--hard", base)
            candidate_contract = copy.deepcopy(next_contract)
            if case != "current":
                for path, payload in changes.items():
                    if case != "mixed" or path.endswith("build.gradle"):
                        (self.repository / path).write_bytes(payload)
            if case == "other loader":
                path = self.repository / "forge/build.gradle"
                path.write_bytes(b"// another loader\n" + path.read_bytes())
            elif case == "matrix":
                path = self.repository / "release/release-matrix.json"
                matrix = json.loads(path.read_bytes())
                matrix["unit_test_lane"] = "forge-1.20.1"
                path.write_text(json.dumps(matrix))
            elif case == "wrong next":
                candidate_contract["loaders"]["forge"]["build_sha256"] = "0" * 64
            elif case == "extra file":
                (self.repository / "fabric/src/e2e/resources/extra.txt").write_text("unreviewed")
            if case != "uncollapsed":
                self.write_contract(candidate_contract)
            candidate = self.commit(case)
            with self.subTest(case=case), self.assertRaises(LoaderBootstrapError):
                validate_transition(self.repository, head_sha=candidate, base_sha=base)

    def test_declared_next_loader_is_verified_even_when_not_in_active_matrix(self) -> None:
        _, next_contract, changes = self.prepare_transition()
        path = self.repository / "release/release-matrix.json"
        matrix = json.loads(path.read_bytes())
        matrix.update(lane_count=1, unit_test_lane="forge-1.20.1")
        for key in ("artifacts", "runtimes"):
            matrix[key] = [row for row in matrix[key] if row["loader"] == "forge"]
        matrix["source_routing"].pop("fabric")
        matrix["installers"].pop("fabric-1.1.0")
        matrix["branch"].update(role="release", name="ship/forge-only")
        matrix["branch"]["sync"]["enabled"] = True
        path.write_text(json.dumps(matrix))
        base = self.commit("protected base with inactive transition loader")
        self.write_contract(next_contract)
        stale = self.commit("collapse without inactive next bytes")
        with self.assertRaises(LoaderBootstrapError):
            validate_transition(self.repository, head_sha=stale, base_sha=base)
        for relative, payload in changes.items():
            (self.repository / relative).write_bytes(payload)
        candidate = self.commit("complete inactive next bytes")
        report = validate_transition(self.repository, head_sha=candidate, base_sha=base)
        self.assertEqual(["forge"], report["active_loaders"])
        self.assertEqual({"fabric", "forge"}, set(report["verified"]))
        _run(self.repository, "reset", "--hard", base)
        build = self.repository / "fabric/build.gradle"
        build.write_bytes(b"// stale current\n" + build.read_bytes())
        stale_base = self.commit("inactive current differs from its contract")
        with self.assertRaisesRegex(LoaderBootstrapError, "build script differs"):
            validate_commit(self.repository, head_sha=stale_base)
        for relative, payload in changes.items():
            (self.repository / relative).write_bytes(payload)
        self.write_contract(next_contract)
        candidate = self.commit("next cannot wash out stale current")
        with self.assertRaisesRegex(LoaderBootstrapError, "build script differs"):
            validate_transition(self.repository, head_sha=candidate, base_sha=stale_base)

    def test_local_git_configuration_cannot_hide_out_of_scope_gitlinks(self) -> None:
        base, next_contract, changes = self.prepare_transition()
        for relative, payload in changes.items():
            (self.repository / relative).write_bytes(payload)
        self.write_contract(next_contract)
        self.commit("next candidate")
        _run(self.repository, "config", "diff.ignoreSubmodules", "all")
        _run(self.repository, "update-index", "--add", "--cacheinfo", f"160000,{self.head},vendor/hidden")
        _run(self.repository, "commit", "-q", "-m", "hidden gitlink")
        candidate = _run(self.repository, "rev-parse", "HEAD")
        with self.assertRaisesRegex(LoaderBootstrapError, "outside the declared"):
            validate_transition(self.repository, head_sha=candidate, base_sha=base)

    def test_active_loaders_are_derived_from_this_branch_matrix(self) -> None:
        result = validate_commit(self.repository, head_sha=self.head)
        matrix = load_matrix(
            REPO / "release/release-matrix.json",
            validate_sources=False,
        )
        self.assertEqual(
            sorted({row["loader"] for row in matrix["artifacts"]}),
            result["active_loaders"],
        )
        self.assertEqual(set(result["active_loaders"]), set(result["verified"]))

    def test_build_byte_mutation_is_rejected_by_protected_contract(self) -> None:
        loader = load_matrix(
            REPO / "release/release-matrix.json", validate_sources=False
        )["artifacts"][0]["loader"]
        path = self.repository / loader / "build.gradle"
        path.write_text(path.read_text() + "\n// candidate mutation\n", encoding="utf-8")
        candidate = self.commit("mutate loader build")
        with self.assertRaisesRegex(LoaderBootstrapError, "build script differs"):
            validate_commit(
                self.repository,
                head_sha=candidate,
                contract_sha=self.head,
            )

    def test_binding_must_be_unique_and_final_even_if_digest_is_allowlisted(self) -> None:
        loader = load_matrix(
            REPO / "release/release-matrix.json", validate_sources=False
        )["artifacts"][0]["loader"]
        for label, mutation in (
            ("duplicated", lambda text: text + "\n" + HARNESS_BINDING + "\n"),
            ("not-final", lambda text: text + "\n// executable tail\n"),
        ):
            with self.subTest(label=label):
                original = _run(self.repository, "show", f"{self.head}:{loader}/build.gradle")
                path = self.repository / loader / "build.gradle"
                value = mutation(original)
                path.write_text(value, encoding="utf-8")
                contract = self.contract()
                contract["loaders"][loader]["build_sha256"] = hashlib.sha256(
                    value.encode("utf-8")
                ).hexdigest()
                self.write_contract(contract)
                candidate = self.commit(f"{label} binding")
                with self.assertRaisesRegex(LoaderBootstrapError, "one protected harness binding"):
                    validate_commit(self.repository, head_sha=candidate)
                _run(self.repository, "reset", "--hard", self.head)

    def test_missing_extra_and_executable_bootstrap_files_fail_closed(self) -> None:
        matrix = load_matrix(
            REPO / "release/release-matrix.json", validate_sources=False
        )
        loader = matrix["artifacts"][0]["loader"]
        files = sorted((self.repository / loader / "src/e2e").rglob("*"))
        regular = next(path for path in files if path.is_file())

        regular.unlink()
        missing = self.commit("missing bootstrap")
        with self.assertRaisesRegex(LoaderBootstrapError, "inventory differs"):
            validate_commit(self.repository, head_sha=missing, contract_sha=self.head)
        _run(self.repository, "reset", "--hard", self.head)

        extra = self.repository / loader / "src/e2e/resources/extra.txt"
        extra.parent.mkdir(parents=True, exist_ok=True)
        extra.write_text("extra\n", encoding="utf-8")
        extra_head = self.commit("extra bootstrap")
        with self.assertRaisesRegex(LoaderBootstrapError, "inventory differs"):
            validate_commit(self.repository, head_sha=extra_head, contract_sha=self.head)
        _run(self.repository, "reset", "--hard", self.head)

        regular = next(
            path
            for path in (self.repository / loader / "src/e2e").rglob("*")
            if path.is_file()
        )
        regular.chmod(0o755)
        executable = self.commit("executable bootstrap")
        with self.assertRaisesRegex(LoaderBootstrapError, "non-executable regular blobs"):
            validate_commit(self.repository, head_sha=executable, contract_sha=self.head)

        _run(self.repository, "reset", "--hard", self.head)
        regular = next(
            path
            for path in (self.repository / loader / "src/e2e").rglob("*")
            if path.is_file()
        )
        regular.write_bytes(b"12345")
        oversized = self.commit("oversized bootstrap")
        with mock.patch(
            "scripts.ci.loader_bootstrap.MAX_BOOTSTRAP_FILE_BYTES", 4
        ), self.assertRaisesRegex(LoaderBootstrapError, "size is outside"):
            validate_commit(self.repository, head_sha=oversized, contract_sha=self.head)

        if hasattr(os, "symlink"):
            _run(self.repository, "reset", "--hard", self.head)
            regular = next(
                path
                for path in (self.repository / loader / "src/e2e").rglob("*")
                if path.is_file()
            )
            regular.unlink()
            regular.symlink_to("untrusted-target")
            symlink = self.commit("symlink bootstrap")
            with self.assertRaisesRegex(
                LoaderBootstrapError, "non-executable regular blobs"
            ):
                validate_commit(self.repository, head_sha=symlink, contract_sha=self.head)

    def test_loader_contract_schema_and_digest_mutations_are_rejected(self) -> None:
        original = json.loads((REPO / "e2e/loader-bootstrap-contract.json").read_text())
        mutations = {
            "unknown root": lambda value: value.__setitem__("trusted", True),
            "unknown loader": lambda value: value["loaders"].__setitem__(
                "quilt", copy.deepcopy(value["loaders"]["fabric"])
            ),
            "invalid digest": lambda value: value["loaders"]["fabric"].__setitem__(
                "build_sha256", "not-a-digest"
            ),
            "missing resources": lambda value: value["loaders"]["fabric"].__setitem__(
                "files",
                {
                    key: digest
                    for key, digest in value["loaders"]["fabric"]["files"].items()
                    if "/java/" in key
                },
            ),
        }
        for label, mutate in mutations.items():
            value = copy.deepcopy(original)
            mutate(value)
            with self.subTest(label=label), self.assertRaises(LoaderBootstrapError):
                load_contract_bytes(json.dumps(value).encode("utf-8"))
        duplicate = (REPO / "e2e/loader-bootstrap-contract.json").read_bytes().replace(
            b'{\n  "schema_version": 1,',
            b'{\n  "schema_version": 1,\n  "schema_version": 1,',
            1,
        )
        with self.assertRaisesRegex(LoaderBootstrapError, "duplicate"):
            load_contract_bytes(duplicate)


class LoaderBootstrapTransitionTests(unittest.TestCase):
    def setUp(self) -> None:
        self.current = json.loads((REPO / "e2e/loader-bootstrap-contract.json").read_bytes())
        next_contract = copy.deepcopy(self.current)
        next_contract["loaders"]["fabric"]["build_sha256"] = "1" * 64
        self.document = {
            "schema_version": 2,
            "generation": 1,
            "loader": "fabric",
            "current": self.current,
            "next": next_contract,
        }

    def payload(self, document: dict | None = None) -> bytes:
        return json.dumps(self.document if document is None else document).encode("utf-8")

    def test_current_and_next_remain_separate_and_exact(self) -> None:
        payload = self.payload()
        contract = load_contract_bytes(payload)
        current = load_contract_bytes(self.payload(self.current))
        self.assertEqual(2, contract.schema_version)
        self.assertEqual(hashlib.sha256(payload).hexdigest(), contract.sha256)
        self.assertEqual(current.loaders, contract.loaders)
        self.assertIsNotNone(contract.transition)
        self.assertEqual(1, contract.transition.generation)
        self.assertEqual("fabric", contract.transition.loader)
        self.assertEqual("1" * 64, contract.transition.next_loaders["fabric"].build_sha256)
        self.assertEqual(1, current.schema_version)
        self.assertIsNone(current.transition)

    def test_transition_shape_and_scope_fail_closed(self) -> None:
        mutations = {
            "unknown root": lambda value: value.__setitem__("authorized", True),
            "missing current": lambda value: value.pop("current"),
            "unknown schema": lambda value: value.__setitem__("schema_version", 3),
            "numeric schema": lambda value: value.__setitem__("schema_version", 2.0),
            "bool generation": lambda value: value.__setitem__("generation", True),
            "numeric generation": lambda value: value.__setitem__("generation", 1.0),
            "stale generation": lambda value: value.__setitem__("generation", 0),
            "unknown generation": lambda value: value.__setitem__("generation", 2),
            "unknown loader": lambda value: value.__setitem__("loader", "quilt"),
            "loader array": lambda value: value.__setitem__("loader", ["fabric"]),
            "wrong loader": lambda value: value.__setitem__("loader", "forge"),
            "no change": lambda value: value.__setitem__("next", value["current"]),
            "nested transition": lambda value: value["next"].__setitem__("schema_version", 2),
            "nested bool schema": lambda value: value["current"].__setitem__("schema_version", True),
            "extra nested key": lambda value: value["current"].__setitem__("generation", 1),
            "extra loader change": lambda value: value["next"]["loaders"]["forge"].__setitem__(
                "build_sha256", "2" * 64
            ),
            "malformed next digest": lambda value: value["next"]["loaders"]["fabric"].__setitem__(
                "build_sha256", "*"
            ),
            "missing next loader": lambda value: value["next"]["loaders"].pop("neoforge"),
        }
        for label, mutate in mutations.items():
            document = copy.deepcopy(self.document)
            mutate(document)
            with self.subTest(label=label), self.assertRaises(LoaderBootstrapError):
                load_contract_bytes(self.payload(document))

    def test_duplicate_generation_and_nested_keys_are_rejected(self) -> None:
        for field in (b'"generation": 1', b'"schema_version": 1'):
            payload = self.payload().replace(field, field + b", " + field, 1)
            with self.subTest(field=field), self.assertRaisesRegex(LoaderBootstrapError, "duplicate"):
                load_contract_bytes(payload)

    def test_schema_one_cannot_smuggle_transition_authority(self) -> None:
        for field, value in (("schema_version", True), ("schema_version", 1.0), ("generation", 1)):
            document = copy.deepcopy(self.current)
            document[field] = value
            with self.subTest(field=field, value=value), self.assertRaises(LoaderBootstrapError):
                load_contract_bytes(self.payload(document))

if __name__ == "__main__":
    unittest.main()

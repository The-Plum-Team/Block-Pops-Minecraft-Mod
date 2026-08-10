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


if __name__ == "__main__":
    unittest.main()

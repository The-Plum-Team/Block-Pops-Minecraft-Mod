from __future__ import annotations

import os
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path
from unittest import mock

from scripts.ci.untrusted_runner import (
    SandboxError,
    _candidate_environment,
    _refresh_candidate_index,
    _refresh_restored_index,
    _relative,
    _restore_authenticated_tree,
    _root,
    _terminate_identity,
    _validate_export_tree,
)


REPO = Path(__file__).resolve().parents[3]


class BoundaryTests(unittest.TestCase):
    def test_root_is_one_exact_private_boundary_child(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary).resolve()
            boundary = root / "boundary"
            boundary.mkdir()
            expected = boundary / "blockpops-candidate-sandbox"
            with mock.patch("scripts.ci.untrusted_runner.SANDBOX_BOUNDARY", boundary):
                self.assertEqual(expected, _root(expected))
                for invalid in (
                    boundary,
                    boundary / "other",
                    boundary / "nested/blockpops-candidate-sandbox",
                ):
                    with self.subTest(path=invalid), self.assertRaises(SandboxError):
                        _root(invalid)
                destination = root / "outside"
                destination.mkdir()
                expected.symlink_to(destination, target_is_directory=True)
                with self.subTest(symlink="root"), self.assertRaises(SandboxError):
                    _root(expected)
                expected.unlink()
                boundary.rmdir()
                boundary.symlink_to(destination, target_is_directory=True)
                with self.subTest(symlink="parent"), self.assertRaises(SandboxError):
                    _root(expected)

    def test_export_paths_are_canonical_and_cannot_escape(self) -> None:
        self.assertEqual("build/release", _relative("build/release"))
        for invalid in ("", ".", "../escape", "/absolute", "a//b", "a\\b"):
            with self.subTest(path=invalid), self.assertRaises(SandboxError):
                _relative(invalid)

    def test_empty_environment_never_inherits_actions_or_command_file_credentials(self) -> None:
        with tempfile.TemporaryDirectory() as temporary, mock.patch.dict(
            os.environ,
            {
                "ACTIONS_RUNTIME_TOKEN": "must-not-escape",
                "ACTIONS_RESULTS_URL": "https://must-not-escape.invalid/",
                "GITHUB_ENV": "/must/not/escape",
                "GITHUB_TOKEN": "must-not-escape",
                "RUNNER_TEMP": temporary,
            },
            clear=False,
        ):
            values = _candidate_environment(Path(temporary), ())
            joined = "\n".join(values)
            self.assertNotIn("must-not-escape", joined)
            self.assertNotIn("GITHUB_", joined)
            self.assertNotIn("ACTIONS_", joined)
            with self.assertRaises(SandboxError):
                _candidate_environment(Path(temporary), ("GITHUB_TOKEN",))

    def test_copied_index_stat_metadata_is_refreshed_inside_the_empty_boundary(self) -> None:
        root = Path("/tmp/blockpops-sandbox-boundary/blockpops-candidate-sandbox")
        repository = root / "repository"
        with mock.patch(
            "scripts.ci.untrusted_runner._candidate_environment",
            return_value=["PATH=/usr/bin"],
        ), mock.patch(
            "scripts.ci.untrusted_runner._execute_untrusted",
            side_effect=(0, 0),
        ) as execute:
            _refresh_candidate_index(root, repository, 1234)
        self.assertEqual(2, execute.call_count)
        self.assertEqual(
            ("/usr/bin/git", "update-index", "--really-refresh"),
            execute.call_args_list[0].kwargs["command"],
        )
        self.assertEqual(
            ("/usr/bin/git", "diff-index", "--quiet", "HEAD", "--"),
            execute.call_args_list[1].kwargs["command"],
        )
        for call in execute.call_args_list:
            self.assertEqual(["PATH=/usr/bin"], call.kwargs["environment"])
            self.assertEqual(repository, call.kwargs["cwd"])

        with mock.patch(
            "scripts.ci.untrusted_runner._candidate_environment",
            return_value=[],
        ), mock.patch(
            "scripts.ci.untrusted_runner._execute_untrusted",
            return_value=1,
        ), self.assertRaises(SandboxError):
            _refresh_candidate_index(root, repository, 1234)

    def test_restored_index_is_rebound_to_the_exact_authenticated_identity(self) -> None:
        repository = Path("/tmp/blockpops-sandbox-boundary/blockpops-candidate-sandbox/repository")
        commit = "a" * 40
        tree = "b" * 40
        state = {
            "repository": str(repository),
            "source_commit": commit,
            "source_tree": tree,
        }
        with mock.patch(
            "scripts.ci.untrusted_runner._run",
            side_effect=(f"{commit}\n".encode(), f"{tree}\n".encode(), b"", b""),
        ) as run:
            _refresh_restored_index(state)
        prefix = ("/usr/bin/git", "-c", "core.fsmonitor=false", "-C", str(repository))
        self.assertEqual(
            [
                mock.call((*prefix, "rev-parse", "HEAD")),
                mock.call((*prefix, "rev-parse", "HEAD^{tree}")),
                mock.call((*prefix, "update-index", "--really-refresh")),
                mock.call(
                    (*prefix, "diff-index", "--no-ext-diff", "--quiet", commit, "--")
                ),
            ],
            run.call_args_list,
        )

        with mock.patch(
            "scripts.ci.untrusted_runner._run",
            side_effect=(b"c" * 40 + b"\n", f"{tree}\n".encode()),
        ), self.assertRaises(SandboxError):
            _refresh_restored_index(state)

        for failed_call in (2, 3):
            effects: list[bytes | BaseException] = [
                f"{commit}\n".encode(),
                f"{tree}\n".encode(),
                b"",
                b"",
            ]
            effects[failed_call] = SandboxError("fail closed")
            with self.subTest(failed_call=failed_call), mock.patch(
                "scripts.ci.untrusted_runner._run", side_effect=effects
            ), self.assertRaises(SandboxError):
                _refresh_restored_index(state)

    def test_export_inventory_rejects_symlinks_hardlinks_and_special_files(self) -> None:
        for kind in ("symlink", "hardlink", "fifo"):
            with self.subTest(kind=kind), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                source = root / "source.bin"
                source.write_bytes(b"bounded")
                if kind == "symlink":
                    (root / "unsafe").symlink_to(source)
                elif kind == "hardlink":
                    os.link(source, root / "unsafe")
                else:
                    os.mkfifo(root / "unsafe")
                with self.assertRaises(SandboxError):
                    _validate_export_tree(root, "fixture")

    def test_surviving_process_still_locks_the_disposable_identity(self) -> None:
        commands: list[tuple[str, ...]] = []

        def fake_run(arguments, **_kwargs):
            command = tuple(arguments)
            commands.append(command)
            if command[:2] == ("pgrep", "-u"):
                return b"123\n"
            return b""

        with mock.patch("scripts.ci.untrusted_runner._run", side_effect=fake_run):
            with self.assertRaises(SandboxError):
                _terminate_identity(1234, "bounded_candidate")
        self.assertIn(("pgrep", "-u", "1234"), commands)
        self.assertIn(("pgrep", "-U", "1234"), commands)
        self.assertTrue(
            any(command[:4] == ("sudo", "-n", "usermod", "--lock") for command in commands)
        )

    def test_assume_unchanged_cannot_hide_a_tracked_byte_mutation(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            source = root / "source"
            repository = root / "repository"
            source.mkdir()
            subprocess.run(["git", "init", "-q", str(source)], check=True)
            subprocess.run(
                ["git", "-C", str(source), "config", "user.email", "sandbox@example.invalid"],
                check=True,
            )
            subprocess.run(
                ["git", "-C", str(source), "config", "user.name", "Sandbox Test"], check=True
            )
            (source / "release").mkdir()
            tracked = source / "release/release-matrix.json"
            tracked.write_text('{"trusted":true}\n', "utf-8")
            subprocess.run(["git", "-C", str(source), "add", "."], check=True)
            subprocess.run(["git", "-C", str(source), "commit", "-qm", "fixture"], check=True)
            shutil.copytree(source, repository, symlinks=True)
            subprocess.run(
                [
                    "git",
                    "-C",
                    str(repository),
                    "update-index",
                    "--assume-unchanged",
                    str(tracked.relative_to(source)),
                ],
                check=True,
            )
            (repository / tracked.relative_to(source)).write_text('{"trusted":false}\n', "utf-8")
            commit = subprocess.run(
                ["git", "-C", str(source), "rev-parse", "HEAD"],
                check=True,
                capture_output=True,
                text=True,
            ).stdout.strip()
            tree = subprocess.run(
                ["git", "-C", str(source), "rev-parse", "HEAD^{tree}"],
                check=True,
                capture_output=True,
                text=True,
            ).stdout.strip()
            with self.assertRaises(SandboxError):
                _restore_authenticated_tree(
                    {
                        "source": str(source),
                        "source_commit": commit,
                        "source_tree": tree,
                        "repository": str(repository),
                    }
                )


class WorkflowContractTests(unittest.TestCase):
    def test_build_seals_candidate_before_exact_upload(self) -> None:
        workflow = (REPO / ".github/workflows/build-gate.yml").read_text("utf-8")
        build = workflow.split("  build:", 1)[1].split("  required:", 1)[0]
        self.assertIn("untrusted_runner.py prepare", build)
        self.assertIn("untrusted_runner.py run", build)
        self.assertIn("untrusted_runner.py seal", build)
        self.assertIn("untrusted_runner.py validate", build)
        self.assertIn("--controller-source", build)
        self.assertIn("--pass-env BLOCKPOPS_TESTED_SHA", build)
        self.assertLess(build.index("untrusted_runner.py seal"), build.index("actions/upload-artifact@"))
        self.assertIn("steps.seal.outcome == 'success'", build)
        self.assertNotIn("./gradlew --no-daemon", build.split("untrusted_runner.py run", 1)[0])

    def test_packaged_runtime_and_fanin_seal_before_upload(self) -> None:
        action = (REPO / ".github/actions/run-packaged-e2e/action.yml").read_text("utf-8")
        self.assertIn('untrusted_runner.py" run', action)
        self.assertIn('untrusted_runner.py" seal', action)
        self.assertIn('untrusted_runner.py" validate', action)
        self.assertIn("--controller-source", action)
        self.assertIn("--overlay", action)
        self.assertLess(
            action.index('untrusted_runner.py" seal'),
            action.index("actions/upload-artifact@"),
        )
        self.assertIn("steps.seal-runtime.outcome == 'success'", action)
        self.assertIn("steps.validate-runtime.outcome == 'success'", action)

        workflow = (REPO / ".github/workflows/on-demand-e2e.yml").read_text("utf-8")
        aggregate = workflow.split("  aggregate:", 1)[1].split("  required:", 1)[0]
        self.assertIn("untrusted_runner.py run", aggregate)
        self.assertIn("untrusted_runner.py seal", aggregate)
        self.assertIn("untrusted_runner.py validate", aggregate)
        self.assertLess(
            aggregate.index("untrusted_runner.py seal"),
            aggregate.index("actions/upload-artifact@"),
        )
        self.assertIn("steps.seal-fanin.outcome == 'success'", aggregate)

    def test_helper_destroys_both_unprivileged_identities(self) -> None:
        helper = (REPO / "scripts/ci/untrusted_runner.py").read_text("utf-8")
        self.assertIn('"/usr/bin/env",\n            "-i"', helper)
        self.assertEqual(1, helper.count('"/usr/bin/setpriv"'))
        self.assertEqual(1, helper.count('"--no-new-privs"'))
        self.assertGreaterEqual(helper.count("_execute_untrusted("), 3)
        self.assertIn('"pkill", "-KILL", "-u"', helper)
        self.assertIn('"pkill", "-KILL", "-U"', helper)
        self.assertIn('"pgrep", "-U"', helper)
        self.assertGreaterEqual(helper.count("_terminate_identity("), 4)
        self.assertIn('"usermod",', helper)
        self.assertIn("GITHUB_", helper)
        self.assertIn("ACTIONS_", helper)

    def test_seal_refreshes_restored_git_metadata_before_publication(self) -> None:
        helper = (REPO / "scripts/ci/untrusted_runner.py").read_text("utf-8")
        seal = helper.split("def seal(", 1)[1].split("def validate_sealed(", 1)[0]
        self.assertLess(
            seal.index("_terminate_identity(uid, USER_NAME)"),
            seal.index("_restore_authenticated_tree(state)"),
        )
        self.assertLess(
            seal.index("_restore_authenticated_tree(state)"),
            seal.index("_refresh_restored_index(state)"),
        )
        self.assertLess(
            seal.index("_refresh_restored_index(state)"),
            seal.index('state["sealed"] = True'),
        )


if __name__ == "__main__":
    unittest.main()

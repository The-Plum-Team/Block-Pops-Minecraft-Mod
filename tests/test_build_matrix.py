"""Planning cannot launch builds, hide missing lanes, or soften verification."""

from __future__ import annotations

import contextlib
import hashlib
import io
import json
import os
import signal
import subprocess
import sys
import tempfile
import threading
import unittest
from pathlib import Path
from unittest.mock import Mock, patch

from scripts.ci.tests.matrix_fixtures import schema2_configuration
from scripts.lib.secure_json import SecureJsonError
from scripts.release.build_matrix import (
    BuildProcessError, _finish_owned_group, checkout_lock, main, numeric_version, plan_build, run_lane,
)
from scripts.release.matrix import MatrixError
from tests.test_release_matrix_portability import arbitrary_named_1211_release_matrix


ROOT = Path(__file__).resolve().parents[1]


class BuildMatrixPlanningTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name).resolve()
        self.path = self.root / "release/release-matrix.json"
        self.path.parent.mkdir()

    def write_matrix(self, matrix):
        for route in matrix["source_routing"].values():
            for key in ("canonical", "e2e"):
                if key in route:
                    (self.root / route[key]).mkdir(parents=True, exist_ok=True)
        self.path.write_text(json.dumps(matrix))

    def test_schema_one_current_and_historical_commands_retain_owned_tasks_and_java(self):
        current = json.loads((ROOT / "release/release-matrix.json").read_text())
        for matrix in (current, arbitrary_named_1211_release_matrix()):
            self.write_matrix(matrix)
            plan = plan_build(self.path, windows=False)
            self.assertEqual("full", plan["scope"])
            self.assertFalse(plan["partial_scope"])
            for row in plan["lanes"]:
                node = row["artifact_node"]
                artifact = next(item for item in matrix["artifacts"] if item["artifact_node"] == node)
                home = str(self.root / "build/gradle-home" / node)
                self.assertEqual([
                    "./gradlew", "--no-daemon", "--no-parallel", "--max-workers=1",
                    "--dependency-verification", "strict", "--gradle-user-home", home,
                    f"-PblockpopsLane={node}", "validateReleaseMatrix",
                    artifact["gradle_task"], artifact["harness_task"],
                ], row["command"])
                self.assertEqual({"gradle": 21, "artifact": artifact["java"], "runtime": artifact["java"]},
                                 row["required_java"])
                self.assertEqual(home, row["gradle_user_home"])

    def test_full_is_default_even_during_preparation_and_partial_scope_is_explicit(self):
        self.write_matrix(schema2_configuration())
        with self.assertRaisesRegex(MatrixError, "unresolved"):
            plan_build(self.path)
        legacy = plan_build(self.path, scope="legacy")
        self.assertTrue(legacy["partial_scope"])
        self.assertEqual(["fabric-1.20.1", "forge-1.20.1"], legacy["selected_nodes"])
        selected = plan_build(self.path, artifact_node="neoforge-1.21.1", clean=True, windows=True)
        lane = selected["lanes"][0]
        self.assertEqual("lane", selected["scope"])
        self.assertTrue(selected["partial_scope"])
        self.assertEqual("gradlew.bat", lane["command"][0])
        self.assertIn(":neoforge:1.21.1:clean", lane["command"])
        self.assertNotIn("clean", lane["command"])
        self.assertTrue(lane["outputs"]["production"].endswith("1.21.1-2.3.4.jar"))
        for node in ("fabric-1.21.7", "forge-1.21.7"):
            with self.subTest(node=node), self.assertRaises(MatrixError):
                plan_build(self.path, artifact_node=node)
        with self.assertRaises(MatrixError):
            plan_build(self.path, scope="legacy", artifact_node="neoforge-1.21.1")

    def test_shared_inventory_is_sorted_numerically_then_by_loader_and_isolated(self):
        matrix = schema2_configuration(shared=True)
        expected = [item["artifact_node"] for item in matrix["targets"]]
        matrix["artifacts"].reverse()
        matrix["runtimes"].reverse()
        self.write_matrix(matrix)
        plan = plan_build(self.path)
        self.assertEqual(expected, plan["selected_nodes"])
        self.assertEqual(12, len({row["gradle_user_home"] for row in plan["lanes"]}))
        self.assertFalse(plan["partial_scope"])
        self.assertEqual(["1.20.1", "1.21.7", "1.21.10"],
                         sorted(["1.21.10", "1.21.7", "1.20.1"], key=numeric_version))

    def test_plan_binds_exact_matrix_bytes_without_claiming_source_toolchains_or_execution(self):
        self.write_matrix(schema2_configuration(shared=True))
        with patch("subprocess.run") as run, patch("subprocess.Popen") as popen:
            first = plan_build(self.path)
            run.assert_not_called()
            popen.assert_not_called()
        self.assertEqual(hashlib.sha256(self.path.read_bytes()).hexdigest(), first["matrix"]["sha256"])
        self.assertEqual("planned", first["status"])
        self.assertEqual("unverified", first["source"]["status"])
        self.assertEqual("unverified", first["toolchains"]["status"])
        self.assertFalse((self.root / "build").exists())
        self.path.write_bytes(self.path.read_bytes() + b"\n")
        second = plan_build(self.path)
        self.assertNotEqual(first["matrix"]["sha256"], second["matrix"]["sha256"])
        self.assertEqual(first["lanes"], second["lanes"])

    def test_invalid_configuration_and_wrong_gradle_java_cannot_be_planned(self):
        matrix = arbitrary_named_1211_release_matrix()
        matrix["gradle_java"] = 25
        self.write_matrix(matrix)
        with self.assertRaisesRegex(MatrixError, "Gradle Java 21"):
            plan_build(self.path)
        matrix = schema2_configuration()
        matrix["runtimes"].pop()
        self.write_matrix(matrix)
        with self.assertRaises(MatrixError):
            plan_build(self.path, scope="legacy")

    def test_plan_preserves_the_secure_reader_and_explicit_scope_boundary(self):
        self.write_matrix(schema2_configuration(shared=True))
        link = self.path.with_name("linked.json")
        link.symlink_to(self.path.name)
        with self.assertRaisesRegex(SecureJsonError, "symlink"):
            plan_build(link)
        for scope in (None, "lane", "configured"):
            with self.subTest(scope=scope), self.assertRaises(MatrixError):
                plan_build(self.path, scope=scope)
        self.path.write_text('{"schema_version": 1, "schema_version": 2}')
        with self.assertRaisesRegex(SecureJsonError, "duplicate"):
            plan_build(self.path)

    def test_cli_requires_plan_rejects_extra_gradle_flags_and_prints_actionable_failure(self):
        self.write_matrix(schema2_configuration())
        arguments = ["--matrix", str(self.path)]
        for extra in ([], ["--plan", "--parallel"], ["--plan", "--max-workers=4"],
                      ["--plan", "--dependency-verification", "off"],
                      ["--plan", "--scope", "full", "--artifact-node", "fabric-1.20.1"]):
            with contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit) as error:
                main(arguments + extra)
            self.assertEqual(2, error.exception.code)
        error = io.StringIO()
        with contextlib.redirect_stderr(error):
            self.assertEqual(2, main(arguments + ["--plan"]))
        self.assertIn("unresolved", error.getvalue())
        output = io.StringIO()
        with contextlib.redirect_stdout(output):
            self.assertEqual(0, main(arguments + ["--plan", "--scope", "legacy"]))
        self.assertEqual("planned", json.loads(output.getvalue())["status"])


class BuildMatrixProcessTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name).resolve()
        self.path = self.root / "build/.matrix-build.lock"

    def test_cross_process_contention_never_enters_a_second_build(self):
        code = (
            "import sys; from pathlib import Path; "
            "from scripts.release.build_matrix import checkout_lock, BuildProcessError\n"
            "try:\n with checkout_lock(Path(sys.argv[1])): sys.exit(99)\n"
            "except BuildProcessError: sys.exit(23)\n"
        )
        with checkout_lock(self.root):
            with self.assertRaises(BuildProcessError), patch("subprocess.Popen") as spawn:
                with checkout_lock(self.root):
                    self.fail("contending lock admitted")
            spawn.assert_not_called()
            result = subprocess.run([sys.executable, "-c", code, str(self.root)], cwd=ROOT, timeout=15)
            self.assertEqual(23, result.returncode)
        self.assertFalse(self.path.exists())
        with checkout_lock(self.root):
            self.assertTrue(self.path.exists())

    def test_stale_and_linked_lock_paths_are_not_stolen_or_followed(self):
        self.path.parent.mkdir()
        self.path.write_text('{"pid": 999999999}')
        with self.assertRaises(BuildProcessError):
            with checkout_lock(self.root):
                self.fail("stale lock stolen")
        self.assertEqual('{"pid": 999999999}', self.path.read_text())
        self.path.unlink()
        target = self.root / "outside"
        target.write_text("untouched")
        self.path.symlink_to(target)
        with self.assertRaises(BuildProcessError):
            with checkout_lock(self.root):
                self.fail("symlink lock followed")
        self.assertEqual("untouched", target.read_text())
        self.path.unlink()
        self.path.parent.rmdir()
        elsewhere = self.root / "elsewhere"
        elsewhere.mkdir()
        self.path.parent.symlink_to(elsewhere, target_is_directory=True)
        with self.assertRaises(BuildProcessError):
            with checkout_lock(self.root):
                self.fail("symlink parent followed")
        self.assertEqual([], list(elsewhere.iterdir()))

    def test_cleanup_never_unlinks_a_replacement_lock(self):
        with self.assertRaises(BuildProcessError):
            with checkout_lock(self.root):
                self.path.rename(self.path.with_suffix(".owned"))
                self.path.write_text("replacement")
        self.assertEqual("replacement", self.path.read_text())

    @unittest.skipIf(os.name == "nt", "POSIX process-group primitive")
    def test_startup_failure_releases_lock_and_completed_handle_cannot_be_reused(self):
        with patch("subprocess.Popen", side_effect=OSError("startup failed")) as spawn:
            with self.assertRaises(OSError), checkout_lock(self.root) as lock:
                run_lane(["missing"], lock=lock, env={})
            self.assertFalse(self.path.exists())
            with self.assertRaises(BuildProcessError):
                run_lane(["missing"], lock=lock, env={})
            self.assertEqual(1, spawn.call_count)
        with checkout_lock(self.root) as lock, patch("os.name", "nt"), patch("subprocess.Popen") as spawn:
            with self.assertRaisesRegex(BuildProcessError, "Windows"):
                run_lane(["wrapper"], lock=lock, env={})
            spawn.assert_not_called()

    @unittest.skipIf(os.name == "nt", "POSIX process-group primitive")
    def test_serial_calls_wait_and_clean_up_before_the_next_spawn(self):
        events = []
        process = Mock(pid=1234)
        process.wait.side_effect = lambda: events.append("wait") or 7
        def spawn(*args, **kwargs):
            events.append("spawn")
            self.assertTrue(kwargs["start_new_session"])
            self.assertTrue(self.path.is_file())
            return process
        with checkout_lock(self.root) as lock, patch("subprocess.Popen", side_effect=spawn), patch(
            "scripts.release.build_matrix._finish_owned_group", side_effect=lambda p: events.append("cleanup")
        ):
            for _ in range(2):
                self.assertEqual(7, run_lane(["wrapper"], lock=lock, env={"JAVA_HOME": "selected"}))
        self.assertEqual(["spawn", "wait", "cleanup"] * 2, events)

    @unittest.skipIf(os.name == "nt", "POSIX process-group primitive")
    def test_thread_contention_cannot_spawn_under_one_lease(self):
        entered, release = threading.Event(), threading.Event()
        process = Mock(pid=1234)
        process.wait.side_effect = lambda: (entered.set(), release.wait(5), 0)[-1]
        with checkout_lock(self.root) as lock, patch("subprocess.Popen", return_value=process) as spawn, patch(
            "scripts.release.build_matrix._finish_owned_group"
        ):
            worker = threading.Thread(target=run_lane, args=(["wrapper"],), kwargs={"lock": lock, "env": {}})
            worker.start()
            try:
                self.assertTrue(entered.wait(5))
                with self.assertRaises(BuildProcessError):
                    run_lane(["second"], lock=lock, env={})
                self.assertEqual(1, spawn.call_count)
            finally:
                release.set()
                worker.join(5)
            self.assertFalse(worker.is_alive())

    @unittest.skipIf(os.name == "nt", "POSIX process-group primitive")
    def test_cancellation_reaps_owned_process_group_before_unlock(self):
        process = Mock(pid=1234)
        process.wait.side_effect = [KeyboardInterrupt(), 0]
        with patch("subprocess.Popen", return_value=process), patch("os.killpg") as kill, patch(
            "scripts.release.build_matrix._group_alive", return_value=False
        ):
            with self.assertRaises(KeyboardInterrupt), checkout_lock(self.root) as lock:
                run_lane(["wrapper"], lock=lock, env={})
        kill.assert_called_once_with(1234, signal.SIGTERM)
        self.assertEqual(2, process.wait.call_count)
        self.assertFalse(self.path.exists())

    @unittest.skipIf(os.name == "nt", "POSIX process-group primitive")
    def test_stubborn_owned_process_escalates_and_cleanup_failure_retains_lock(self):
        process = Mock(pid=1234)
        process.wait.side_effect = [subprocess.TimeoutExpired("wrapper", 10), 0]
        with patch("os.killpg") as kill, patch("scripts.release.build_matrix._group_alive", return_value=False):
            _finish_owned_group(process)
        self.assertEqual([(1234, signal.SIGTERM), (1234, signal.SIGKILL)],
                         [call.args for call in kill.call_args_list])
        process.wait.side_effect = None
        with patch("subprocess.Popen", return_value=process), patch(
            "scripts.release.build_matrix._finish_owned_group", side_effect=BuildProcessError("still alive")
        ):
            with self.assertRaises(BuildProcessError), checkout_lock(self.root) as lock:
                run_lane(["wrapper"], lock=lock, env={})
        self.assertTrue(self.path.is_file())

    @unittest.skipIf(os.name == "nt", "POSIX process-group primitive")
    def test_real_cancelled_python_tree_exits_without_touching_another_process(self):
        code = ("import signal,subprocess,sys,time\n"
                "child=subprocess.Popen([sys.executable,'-c','import time; time.sleep(60)'])\n"
                "def stop(*args):\n child.terminate(); child.wait(); sys.exit(0)\n"
                "signal.signal(signal.SIGTERM,stop)\nprint(child.pid,flush=True)\ntime.sleep(60)\n")
        factory = subprocess.Popen
        unrelated = factory([sys.executable, "-c", "import time; time.sleep(60)"])
        handles, descendants = [], []
        def spawn(command, **kwargs):
            kwargs.update(stdout=subprocess.PIPE, text=True)
            process = factory(command, **kwargs)
            handles.append(process)
            descendants.append(int(process.stdout.readline()))
            process.stdout.close()
            wait = process.wait
            responses = iter([True, False])
            def cancelled_wait(**options):
                if next(responses, False):
                    raise KeyboardInterrupt()
                return wait(**options)
            process.wait = cancelled_wait
            return process
        try:
            with patch("subprocess.Popen", side_effect=spawn):
                with self.assertRaises(KeyboardInterrupt), checkout_lock(self.root) as lock:
                    run_lane([sys.executable, "-c", code], lock=lock, env=dict(os.environ))
            self.assertIsNone(unrelated.poll())
            self.assertFalse(self.path.exists())
            with self.assertRaises(ProcessLookupError):
                os.kill(descendants[0], 0)
        finally:
            unrelated.terminate()
            unrelated.wait(timeout=5)
            for process in handles:
                if process.poll() is None:
                    os.killpg(process.pid, signal.SIGKILL)
                    process.wait(timeout=5)


if __name__ == "__main__":
    unittest.main()

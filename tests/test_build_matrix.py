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
    BuildProcessError, _finish_owned_group, begin_report, checkout_lock, file_snapshot, finish_report,
    main, numeric_version, output_snapshot, plan_build, prepare_observation, run_lane, source_snapshot,
    validate_observation, verify_toolchains,
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


class BuildMatrixToolchainTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name).resolve()
        matrix = schema2_configuration(shared=True)
        for route in matrix["source_routing"].values():
            for key in ("canonical", "e2e"):
                if key in route:
                    (self.root / route[key]).mkdir(parents=True, exist_ok=True)
        path = self.root / "release/release-matrix.json"
        path.parent.mkdir(); path.write_text(json.dumps(matrix))
        self.plan = plan_build(path)
        self.homes = {}
        for major in (17, 21):
            home = self.root / f"JDK {major}"
            (home / "bin").mkdir(parents=True)
            for name in ("java", "javac", "../release"):
                binary = home / "bin" / name
                binary.write_text(f"{major} {name}"); binary.chmod(0o755)
            self.homes[major] = home
        self.env = {"PATH": "/test/bin", "JAVA_HOME": "/unreviewed/ambient"}

    def probe(self, command, **kwargs):
        home = Path(command[0]).parents[1]
        major = next(key for key, value in self.homes.items() if value == home)
        output = (f"javac {major}.0.1\n" if Path(command[0]).name == "javac" else
                  f'    java.home = {home}\n    java.version = {major}.0.1\n'
                  f'    java.specification.version = {major}\nopenjdk version "{major}.0.1"\n')
        return subprocess.CompletedProcess(command, 0, "", output)

    def verify(self, **kwargs):
        return verify_toolchains(self.plan, self.homes[21], {17: self.homes[17]},
                                 environment=self.env, **kwargs)

    def test_explicit_jdks_are_probed_once_and_bound_without_claiming_gradle_execution(self):
        before = json.dumps(self.plan, sort_keys=True)
        with patch("subprocess.run", side_effect=self.probe) as run:
            result = self.verify()
        self.assertEqual(4, run.call_count)
        for call in run.call_args_list:
            self.assertEqual(15, call.kwargs["timeout"])
            self.assertEqual(str(Path(call.args[0][0]).parents[1]), call.kwargs["env"]["JAVA_HOME"])
        self.assertEqual("probed", result["status"])
        self.assertEqual("unverified", result["gradle_jvm"])
        self.assertEqual("unverified", result["compiler_selection"])
        self.assertEqual({"JAVA_HOME": str(self.homes[21])}, result["environment"])
        self.assertEqual({"17", "21"}, set(result["homes"]))
        self.assertEqual(before, json.dumps(self.plan, sort_keys=True))
        for original, bound in zip(self.plan["lanes"], result["lanes"], strict=True):
            self.assertEqual(original["artifact_node"], bound["artifact_node"])
            self.assertEqual(str(self.homes[original["required_java"]["runtime"]]), bound["runtime_home"])
            self.assertEqual(str(self.homes[original["required_java"]["artifact"]]), bound["compile_home"])
            self.assertIn(f"-Dorg.gradle.java.home={self.homes[21]}", bound["command"])
            self.assertIn("-Porg.gradle.java.installations.auto-download=false", bound["command"])
            self.assertIn("-Porg.gradle.java.installations.auto-detect=false", bound["command"])
            self.assertIn("-Porg.gradle.java.installations.fromEnv=", bound["command"])
            self.assertIn(f"-Porg.gradle.java.installations.paths={self.homes[17]},{self.homes[21]}", bound["command"])
            self.assertIn("--no-parallel", bound["command"])
            self.assertIn("--max-workers=1", bound["command"])
        self.assertEqual(hashlib.sha256((self.homes[17] / "bin/java").read_bytes()).hexdigest(),
                         result["homes"]["17"]["files"]["java"]["sha256"])

    def test_missing_conflicting_and_non_jdk_homes_fail_before_any_probe(self):
        with patch("subprocess.run") as run:
            for homes in ({}, {17: self.homes[17], 21: self.homes[17]}, {"17": self.homes[17]}):
                with self.subTest(homes=homes), self.assertRaises(BuildProcessError):
                    verify_toolchains(self.plan, self.homes[21], homes, environment={})
            (self.homes[17] / "bin/javac").unlink()
            with self.assertRaises(BuildProcessError):
                self.verify()
            run.assert_not_called()

    def test_inherited_option_injection_and_daemon_criteria_are_rejected_before_probes(self):
        for key in ("JAVA_OPTS", "GRADLE_OPTS", "JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "JDK_JAVA_OPTIONS"):
            with self.subTest(key=key), patch("subprocess.run") as run:
                self.env[key] = "-Dorg.gradle.parallel=true"
                with self.assertRaisesRegex(BuildProcessError, key):
                    self.verify()
                run.assert_not_called(); del self.env[key]
        (self.root / "gradle").mkdir(exist_ok=True)
        (self.root / "gradle/gradle-daemon-jvm.properties").write_text("toolchainVersion=25")
        with patch("subprocess.run") as run, self.assertRaisesRegex(BuildProcessError, "daemon JVM"):
            self.verify()
        run.assert_not_called()

    def test_probe_rejects_major_home_compiler_mismatch_failure_and_timeout(self):
        changes = (("java.version = 17.0.1", "java.version = 25.0.1"),
                   ("java.specification.version = 17", "java.specification.version = 21"),
                   (str(self.homes[17]), str(self.homes[21])), ("javac 17.0.1", "javac 21.0.1"))
        for old, new in changes:
            def altered(command, **kwargs):
                result = self.probe(command, **kwargs)
                result.stderr = result.stderr.replace(old, new)
                return result
            with self.subTest(old=old), patch("subprocess.run", side_effect=altered):
                with self.assertRaises(BuildProcessError):
                    self.verify()
        for failure in (subprocess.CompletedProcess([], 1, "", "failure"),
                        subprocess.TimeoutExpired("java", 15)):
            with patch("subprocess.run", side_effect=failure if isinstance(failure, Exception) else None,
                       return_value=failure), self.assertRaises(BuildProcessError):
                self.verify()

    def test_linked_or_changing_binaries_cannot_be_reported_as_probed(self):
        java = self.homes[17] / "bin/java"
        java.unlink(); java.symlink_to(self.homes[21] / "bin/java")
        with patch("subprocess.run") as run, self.assertRaises(BuildProcessError):
            self.verify()
        run.assert_not_called(); java.unlink(); java.write_text("original"); java.chmod(0o755)
        def changing(command, **kwargs):
            result = self.probe(command, **kwargs)
            java.write_text("changed during probe")
            return result
        with patch("subprocess.run", side_effect=changing), self.assertRaisesRegex(BuildProcessError, "changed"):
            self.verify()

    def test_stale_or_injected_plan_cannot_supply_arbitrary_process_arguments(self):
        original = json.dumps(self.plan)
        for mutation in ("parallel", "required_java", "schema_type", "matrix"):
            self.plan = json.loads(original)
            if mutation == "parallel":
                self.plan["lanes"][0]["command"].append("--parallel")
            elif mutation == "required_java":
                self.plan["lanes"][0]["required_java"]["artifact"] = 21
            elif mutation == "schema_type":
                self.plan["schema_version"] = True
            else:
                self.plan["matrix"]["sha256"] = "0" * 64
            with self.subTest(mutation=mutation), patch("subprocess.run") as run:
                with self.assertRaises(BuildProcessError):
                    self.verify()
                run.assert_not_called()

    def test_modern_lane_reuses_launch_jdk_and_property_overrides_are_explicit(self):
        self.plan = plan_build(Path(self.plan["matrix"]["path"]), artifact_node="neoforge-1.21.1")
        (self.root / "gradle.properties").write_text(
            "org.gradle.java.home=/old/jdk\norg.gradle.parallel=true\n"
            "org.gradle.java.installations.auto-download=true\n")
        with patch("subprocess.run", side_effect=self.probe) as run:
            result = verify_toolchains(self.plan, self.homes[21], {}, environment=self.env)
        self.assertEqual(2, run.call_count)
        self.assertEqual({"21"}, set(result["homes"]))
        self.assertIn("-Dorg.gradle.parallel=false", result["property_overrides"])
        self.assertIn("-Dorg.gradle.workers.max=1", result["property_overrides"])
        self.assertEqual(str(self.homes[21]), result["lanes"][0]["runtime_home"])


class BuildMatrixObservationTests(unittest.TestCase):
    def setUp(self):
        BuildMatrixToolchainTests.setUp(self)
        (self.root / "gradle").mkdir(exist_ok=True)
        (self.root / "gradle/build-observation.init.gradle").write_bytes((ROOT / "gradle/build-observation.init.gradle").read_bytes())
        for args in (("init", "-q"), ("add", "."),
                     ("-c", "user.name=Test", "-c", "user.email=t@example.test", "commit", "-qm", "fixture")):
            subprocess.run(["git", "-C", str(self.root), *args], check=True, capture_output=True)

    @contextlib.contextmanager
    def prepared(self, node=None):
        node = node or self.plan["selected_nodes"][0]
        with patch("subprocess.run", side_effect=lambda *args, **kw: BuildMatrixToolchainTests.probe(self, *args, **kw)):
            toolchains = verify_toolchains(self.plan, self.homes[21], {17: self.homes[17]}, environment=self.env)
        with checkout_lock(self.root) as lock:
            run_id = begin_report(lock, self.plan)
            result = prepare_observation(lock, run_id, toolchains, node)
            request = json.loads(Path(result["request"]).read_bytes())
            receipt = {key: request[key] for key in ("schema_version", "run_id", "artifact_node", "caller_source")}
            receipt.update(request_sha256=hashlib.sha256(Path(result["request"]).read_bytes()).hexdigest(),
                           status="completed", gradle_jvm={"home": request["gradle_home"], "major": 21}, compilers={})
            for task, destination in lock.observations[(run_id, node)]["destinations"].items():
                directory = Path(destination); directory.mkdir(parents=True, exist_ok=True)
                (directory / "Compiled.class").write_bytes(b"synthetic unit fixture")
                receipt["compilers"][task] = {"selected": {"home": request["compile_home"], "major": request["compile_major"],
                    "release": request["compile_major"], "version": f"{request['compile_major']}.0.1+7-LTS",
                    "executable": str(Path(request["compile_home"]) / "bin/javac"), "destination": destination},
                    "outcome": {"did_work": True, "skipped": False, "up_to_date": False, "no_source": False,
                                "skip_message": None, "failed": False}}
            Path(result["receipt"]).write_text(json.dumps(receipt))
            yield lock, run_id, node, result, request, receipt

    def test_canonical_request_scopes_destinations_and_consumes_receipt_once(self):
        for legacy in (False, True):
            if legacy:
                path = Path(self.plan["matrix"]["path"])
                path.write_bytes((ROOT / "release/release-matrix.json").read_bytes())
                self.plan = plan_build(path)
            with self.prepared() as (lock, run_id, node, bound, request, receipt):
                payload = Path(bound["request"]).read_bytes()
                self.assertEqual(json.dumps(request, sort_keys=True, separators=(",", ":")).encode(), payload)
                self.assertIn("--no-configuration-cache", bound["command"])
                self.assertIn("--init-script", bound["command"])
                self.assertEqual(3, len(request["compile_tasks"]))
                self.assertEqual("validateReleaseMatrix", request["requested_tasks"][0])
                with self.assertRaises((BuildProcessError, FileExistsError)):
                    prepare_observation(lock, run_id, lock.observations[(run_id, node)]["toolchains"], node)
                with patch("scripts.release.build_matrix.source_snapshot", wraps=source_snapshot) as snapshot:
                    evidence = validate_observation(lock, run_id, node)
                    self.assertEqual(1, snapshot.call_count)
                self.assertEqual("observed", evidence["status"])
                self.assertEqual(3, len(evidence["classes"]))
                for task, rows in evidence["classes"].items():
                    self.assertEqual(1, len(rows))
                    self.assertEqual(not legacy, "/versions/" in rows[0]["path"])
                with self.assertRaises(BuildProcessError):
                    validate_observation(lock, run_id, node)

    def test_receipt_identity_scope_metadata_outcome_and_json_types_are_exact(self):
        for mutation in ("run", "node", "hash", "source", "jvm", "scope", "compilers_list", "version", "home", "executable", "destination", "no_source", "failed", "skipped", "float", "duplicate"):
            with self.prepared() as (lock, run_id, node, bound, request, receipt):
                compiler = next(iter(receipt["compilers"].values()))
                if mutation in {"run", "node", "hash"}:
                    receipt[{"run": "run_id", "node": "artifact_node", "hash": "request_sha256"}[mutation]] = "wrong"
                elif mutation == "source": receipt["caller_source"] = {}
                elif mutation == "jvm": receipt["gradle_jvm"]["major"] = 17
                elif mutation == "scope": receipt["compilers"].pop(next(iter(receipt["compilers"])))
                elif mutation == "compilers_list": receipt["compilers"] = list(receipt["compilers"])
                elif mutation in {"version", "home", "executable", "destination"}: compiler["selected"][mutation] = "wrong"
                elif mutation == "float": compiler["selected"]["major"] = float(request["compile_major"])
                elif mutation != "duplicate": compiler["outcome"][mutation] = True
                payload = json.dumps(receipt)
                if mutation == "duplicate": payload = payload.replace('"schema_version": 1', '"schema_version": 1, "schema_version": 1')
                Path(bound["receipt"]).write_text(payload)
                with self.subTest(mutation=mutation), self.assertRaises((BuildProcessError, SecureJsonError)):
                    validate_observation(lock, run_id, node)

    def test_up_to_date_requires_existing_classes_and_real_unlinked_output_paths(self):
        with self.prepared() as (lock, run_id, node, bound, request, receipt):
            for compiler in receipt["compilers"].values():
                compiler["outcome"].update(did_work=False, skipped=True, up_to_date=True, skip_message="UP-TO-DATE")
            Path(bound["receipt"]).write_text(json.dumps(receipt))
            self.assertEqual("observed", validate_observation(lock, run_id, node)["status"])
        for linked in (False, True):
            with self.prepared() as (lock, run_id, node, bound, request, receipt):
                compiler = next(iter(receipt["compilers"].values()))
                path = Path(compiler["selected"]["destination"]) / "Compiled.class"
                path.unlink()
                if linked: path.symlink_to(self.homes[17] / "bin/java")
                with self.subTest(linked=linked), self.assertRaises(BuildProcessError):
                    validate_observation(lock, run_id, node)
                path.unlink(missing_ok=True)

    def test_request_init_jdk_source_matrix_and_receipt_links_cannot_change(self):
        for mutation in ("request", "init", "jdk", "source", "matrix", "receipt_link"):
            with self.prepared() as (lock, run_id, node, bound, request, receipt):
                paths = {"request": Path(bound["request"]), "init": self.root / "gradle/build-observation.init.gradle",
                         "jdk": self.homes[17] / "bin/javac", "source": self.root / "common/src/main/New.java",
                         "matrix": Path(self.plan["matrix"]["path"]), "receipt_link": Path(bound["receipt"])}
                path = paths[mutation]
                original = path.read_bytes() if path.exists() else None
                if mutation == "receipt_link":
                    path.unlink(); path.symlink_to(bound["request"])
                else:
                    path.write_bytes((original or b"") + b"\nchanged")
                try:
                    with self.subTest(mutation=mutation), self.assertRaises((BuildProcessError, OSError)):
                        validate_observation(lock, run_id, node)
                finally:
                    if path.is_symlink(): path.unlink()
                    if original is None: path.unlink()
                    else: path.write_bytes(original)

    def test_running_lease_and_matching_run_are_required(self):
        with self.prepared() as (lock, run_id, node, bound, request, receipt):
            with lock.guard, self.assertRaisesRegex(BuildProcessError, "idle"):
                validate_observation(lock, run_id, node)
            with self.assertRaises(BuildProcessError): validate_observation(lock, "f" * 32, node)
            begin_report(lock, self.plan)
            with self.assertRaises(BuildProcessError): validate_observation(lock, run_id, node)
        with self.assertRaises(BuildProcessError): validate_observation(lock, run_id, node)

    def test_input_changes_during_final_source_scan_cannot_escape_validation(self):
        for mutation in ("jdk", "receipt", "class"):
            with self.prepared() as (lock, run_id, node, bound, request, receipt):
                compiler = next(iter(receipt["compilers"].values()))
                path = {"jdk": self.homes[17] / "bin/javac", "receipt": Path(bound["receipt"]),
                        "class": Path(compiler["selected"]["destination"]) / "Compiled.class"}[mutation]
                def mutate_after_scan(repository):
                    snapshot = source_snapshot(repository)
                    path.write_bytes(b"changed after initial identity check")
                    return snapshot
                with patch("scripts.release.build_matrix.source_snapshot", side_effect=mutate_after_scan):
                    with self.subTest(mutation=mutation), self.assertRaises(BuildProcessError):
                        validate_observation(lock, run_id, node)


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


class BuildMatrixEvidenceTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name).resolve()
        for name, content in {".gitignore": "build/\n.gradle/\n", "common/src/main/Block.java": "class Block {}",
                              "docs/note.md": "tracked documentation", "release/release-matrix.json": "{}",
                              "e2e/scenario-contract.json": "{}", "e2e/loader-bootstrap-contract.json": "{}"}.items():
            path = self.root / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(content)
        self.git("init", "-q")
        self.git("add", ".")
        self.git("-c", "user.name=Test", "-c", "user.email=test@example.invalid", "commit", "-qm", "fixture")
        matrix = self.root / "release/release-matrix.json"
        self.outputs = {"production": "build/game.jar", "harness": "build/harness.jar"}
        self.plan = {"kind": "blockpops-build-plan", "status": "planned",
                     "matrix": {"path": str(matrix), "sha256": hashlib.sha256(matrix.read_bytes()).hexdigest()},
                     "selected_nodes": ["fabric-1.20.1"],
                     "lanes": [{"artifact_node": "fabric-1.20.1", "outputs": self.outputs}]}

    def git(self, *arguments):
        return subprocess.run(["git", "-C", str(self.root), *arguments], check=True, capture_output=True).stdout

    def results(self):
        for path in self.outputs.values():
            output = self.root / path
            output.parent.mkdir(exist_ok=True)
            output.write_bytes(path.encode())
        return [{"artifact_node": "fabric-1.20.1", "exit_code": 0,
                 "outputs": output_snapshot(self.root, self.outputs)}]

    def test_source_snapshot_binds_commit_tree_and_hidden_dirty_tracked_bytes(self):
        clean = source_snapshot(self.root)
        self.assertFalse(clean["dirty"])
        self.assertEqual(self.git("rev-parse", "HEAD").decode().strip(), clean["commit"])
        self.assertEqual(self.git("rev-parse", "HEAD^{tree}").decode().strip(), clean["tree"])
        self.git("update-index", "--assume-unchanged", "docs/note.md")
        (self.root / "docs/note.md").write_text("hidden edit")
        changed = source_snapshot(self.root)
        self.assertTrue(changed["dirty"])
        self.assertNotEqual(clean["fingerprint"], changed["fingerprint"])
        (self.root / "common/src/main/Block.java").unlink()
        self.assertIn({"path": "common/src/main/Block.java", "missing": True}, source_snapshot(self.root)["files"])

    def test_staged_drift_is_bound_even_when_worktree_bytes_match_head(self):
        before = source_snapshot(self.root)
        path = self.root / "common/src/main/Block.java"
        original = path.read_bytes()
        path.write_text("staged")
        self.git("add", str(path))
        path.write_bytes(original)
        after = source_snapshot(self.root)
        self.assertTrue(after["dirty"])
        self.assertEqual(before["fingerprint"], after["fingerprint"])
        self.assertNotEqual(before["index_sha256"], after["index_sha256"])

    def test_replace_refs_cannot_hide_the_real_tree_or_dirty_source(self):
        head = self.git("rev-parse", "HEAD").decode().strip()
        tree = self.git("rev-parse", "HEAD^{tree}").decode().strip()
        (self.root / "common/src/main/Block.java").write_text("class Block { int replacement; }")
        self.git("add", ".")
        self.git("-c", "user.name=Test", "-c", "user.email=t@example.test", "commit", "-qm", "replacement")
        replacement = self.git("rev-parse", "HEAD").decode().strip()
        self.git("reset", "--soft", head)
        self.git("replace", head, replacement)
        self.assertNotEqual(tree, self.git("rev-parse", "HEAD^{tree}").decode().strip())
        run = subprocess.run
        with patch("subprocess.run", wraps=run) as calls:
            snapshot = source_snapshot(self.root)
        self.assertEqual((head, tree, True), (snapshot["commit"], snapshot["tree"], snapshot["dirty"]))
        for call in calls.call_args_list:
            self.assertEqual("1", call.kwargs["env"]["GIT_NO_REPLACE_OBJECTS"])
            self.assertEqual(os.devnull, call.kwargs["env"]["GIT_GRAFT_FILE"])

    def test_ignored_java_inputs_are_bound_and_changes_invalidate_a_running_report(self):
        with (self.root / ".gitignore").open("a") as stream:
            stream.write("common/src/main/Ignored.java\n")
        ignored = self.root / "common/src/main/Ignored.java"
        ignored.write_text("class Ignored {}")
        self.assertEqual(ignored.relative_to(self.root).as_posix(),
                         self.git("check-ignore", str(ignored.relative_to(self.root))).decode().strip())
        snapshot = source_snapshot(self.root)
        self.assertIn("common/src/main/Ignored.java", {row["path"] for row in snapshot["files"]})
        results = self.results()
        with checkout_lock(self.root) as lock:
            run_id = begin_report(lock, self.plan)
            ignored.write_text("class Ignored { int changed; }")
            self.assertEqual("failed", finish_report(lock, run_id, results)["status"])

    def test_index_mutation_during_source_reads_cannot_produce_a_mixed_snapshot(self):
        read = file_snapshot
        def mutate_index(repository, path):
            snapshot = read(repository, path)
            if str(path) == "docs/note.md":
                (self.root / path).write_text("concurrently staged")
                self.git("add", str(path))
            return snapshot
        with patch("scripts.release.build_matrix.file_snapshot", side_effect=mutate_index):
            with self.assertRaisesRegex(BuildProcessError, "index changed"):
                source_snapshot(self.root)

    def test_untracked_inputs_and_both_contracts_are_bound_but_pi_and_generated_files_are_excluded(self):
        before = source_snapshot(self.root)
        for name in (".pi/state.json", "build/cache.bin", "common/build/generated.java", ".gradle/cache.bin",
                     "common/versions/1.21.1/build/generated/stonecutter/main/Generated.java",
                     "fabric/versions/1.21.1/build/stonecutter-cache/sources/e2e/Cached.java"):
            path = self.root / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text("irrelevant")
        self.assertEqual(before, source_snapshot(self.root))
        for name in ("common/src/main/New.java", "common/versions/1.21.1/src/main/Override.java",
                     "scripts/new.py", "scripts/versions/example/build/hook.py", "stonecutter.gradle", ".gitignore",
                     "release/release-matrix.json", "e2e/scenario-contract.json", "e2e/loader-bootstrap-contract.json"):
            path = self.root / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text("changed")
            after = source_snapshot(self.root)
            self.assertTrue(after["dirty"])
            self.assertNotEqual(before["fingerprint"], after["fingerprint"], name)
            before = after
        paths = {record["path"] for record in source_snapshot(self.root)["files"]}
        self.assertNotIn("common/build/generated.java", paths)
        self.assertNotIn(".pi/state.json", paths)

    def test_output_snapshots_reject_missing_symlink_hardlink_special_and_escape(self):
        self.results()
        jar = self.root / self.outputs["production"]
        for path in ("../outside", "build/missing.jar"):
            with self.subTest(path=path), self.assertRaises((BuildProcessError, FileNotFoundError)):
                file_snapshot(self.root, path)
        link = jar.with_name("link.jar")
        link.symlink_to(jar.name)
        with self.assertRaises(BuildProcessError):
            file_snapshot(self.root, link)
        link.unlink()
        os.link(jar, link)
        with self.assertRaises(BuildProcessError):
            file_snapshot(self.root, jar)
        link.unlink()
        if hasattr(os, "mkfifo"):
            os.mkfifo(link)
            with self.assertRaises(BuildProcessError):
                file_snapshot(self.root, link)
        directory = self.root / "linked-build"
        directory.symlink_to("build", target_is_directory=True)
        with self.assertRaises(BuildProcessError):
            file_snapshot(self.root, directory / jar.name)

    def test_new_run_atomically_invalidates_previous_success_and_stale_finishers(self):
        results = self.results()
        report_path = self.root / "build/build-matrix-report.json"
        with checkout_lock(self.root) as lock:
            first = begin_report(lock, self.plan)
            lock.guard.acquire()
            try:
                with self.assertRaisesRegex(BuildProcessError, "process is active"):
                    finish_report(lock, first, results)
            finally:
                lock.guard.release()
            self.assertEqual("success", finish_report(lock, first, results)["status"])
            second = begin_report(lock, self.plan)
            self.assertEqual("running", json.loads(report_path.read_text())["status"])
            with self.assertRaises(BuildProcessError):
                finish_report(lock, first, results)
            self.assertEqual(second, json.loads(report_path.read_text())["run_id"])
            failed = finish_report(lock, second, [], error="startup failed")
            self.assertEqual("failed", failed["status"])
        with self.assertRaises(BuildProcessError):
            finish_report(lock, second, results)

    def test_source_matrix_and_previously_recorded_output_changes_cannot_finish_successfully(self):
        for mutation in ("source", "matrix", "output", "deleted", "scope", "exit"):
            results = self.results()
            with checkout_lock(self.root) as lock:
                run_id = begin_report(lock, self.plan)
                if mutation in {"source", "matrix"}:
                    name = "common/src/main/Block.java" if mutation == "source" else "release/release-matrix.json"
                    (self.root / name).write_text(mutation)
                elif mutation == "output":
                    (self.root / self.outputs["production"]).write_text("changed output")
                elif mutation == "deleted":
                    (self.root / self.outputs["harness"]).unlink()
                elif mutation == "scope":
                    results.clear()
                else:
                    results[0]["exit_code"] = True
                with self.subTest(mutation=mutation):
                    self.assertEqual("failed", finish_report(lock, run_id, results)["status"])
            (self.root / "release/release-matrix.json").write_text("{}")

    def test_report_links_and_stale_plan_are_rejected_without_overwriting_other_files(self):
        self.results()
        path = self.root / "build/build-matrix-report.json"
        target = self.root / "untouched.json"
        target.write_text("{}"); path.symlink_to(target)
        with checkout_lock(self.root) as lock:
            with self.assertRaises(BuildProcessError):
                begin_report(lock, self.plan)
            self.assertEqual("{}", target.read_text())
            path.unlink()
            (self.root / "release/release-matrix.json").write_text("changed")
            with self.assertRaisesRegex(BuildProcessError, "matrix changed"):
                begin_report(lock, self.plan)


if __name__ == "__main__":
    unittest.main()

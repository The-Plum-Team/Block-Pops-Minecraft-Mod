"""Opt-in real Gradle smoke tests; synthetic Java does not qualify Minecraft lanes."""

import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
import uuid

from scripts.release.build_matrix import _finish_owned_group


ROOT = Path(__file__).resolve().parents[1]
REQUIRED = ("BLOCKPOPS_TEST_GRADLE", "BLOCKPOPS_TEST_JAVA17", "BLOCKPOPS_TEST_JAVA21")


@unittest.skipUnless(all(os.environ.get(name) for name in REQUIRED), "explicit local Gradle/JDK smoke paths required")
class BuildObservationIntegrationTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name).resolve()
        self.homes = {major: str(Path(os.environ[f"BLOCKPOPS_TEST_JAVA{major}"]).resolve()) for major in (17, 21)}
        self.root.joinpath("settings.gradle").write_text("rootProject.name='observer-fixture'\ninclude 'common', 'fabric'\n")
        self.root.joinpath("build.gradle").write_text("""
def major = providers.gradleProperty('testMajor').get().toInteger()
subprojects {
    apply plugin: 'java'
    java.toolchain.languageVersion = JavaLanguageVersion.of(major)
    tasks.withType(JavaCompile).configureEach { options.release = major }
}
project(':fabric') {
    sourceSets { e2e }
    tasks.register('production') { dependsOn ':common:classes', 'classes' }
    tasks.register('harness') { dependsOn 'e2eClasses' }
    if (providers.gradleProperty('badCompiler').isPresent()) {
        tasks.withType(JavaCompile).configureEach { options.forkOptions.executable = '/unreviewed/javac' }
    }
}
""")
        for module, source, name in (("common", "main", "Shared"), ("fabric", "main", "Loader"), ("fabric", "e2e", "Harness")):
            path = self.root / module / "src" / source / "java" / f"{name}.java"
            path.parent.mkdir(parents=True); path.write_text(f"public class {name} {{}}\n")
        self.tasks = [":fabric:production", ":fabric:harness"]
        self.compile_tasks = [":common:compileJava", ":fabric:compileJava", ":fabric:compileE2eJava"]
        self.logs = Path(os.environ.get("BLOCKPOPS_TEST_LOGS", str(self.root / "logs"))).resolve()
        self.logs.mkdir(parents=True, exist_ok=True)

    def invoke(self, major, *, actual=None, extra=(), launch=None, request_overrides=None):
        run_id = uuid.uuid4().hex
        directory = self.root / "observations" / run_id
        directory.mkdir(parents=True)
        request = {"schema_version": 1, "run_id": run_id, "artifact_node": "fabric-1.20.1" if major == 17 else "fabric-1.21.1",
                   "repository": str(self.root), "caller_source": {"fingerprint": "a" * 64, "matrix_sha256": "b" * 64},
                   "gradle_home": self.homes[21], "compile_home": self.homes[major], "compile_major": major,
                   "requested_tasks": self.tasks, "compile_tasks": self.compile_tasks, "projects": [":common", ":fabric"]}
        request.update(request_overrides or {})
        path = directory / "request.json"
        path.write_text(json.dumps(request))
        command = [os.environ["BLOCKPOPS_TEST_GRADLE"], "--offline", "--no-daemon", "--no-parallel", "--max-workers=1",
                   "--no-configuration-cache", "--no-build-cache", "--dependency-verification", "strict",
                   "--gradle-user-home", str(self.root / "gradle-home"),
                   "--init-script", str(ROOT / "gradle/build-observation.init.gradle"),
                   f"-Dblockpops.observation.request={path}", f"-Dorg.gradle.java.home={self.homes[launch or 21]}",
                   f"-Porg.gradle.java.installations.paths={self.homes[17]},{self.homes[21]}",
                   "-Porg.gradle.java.installations.auto-detect=false", "-Porg.gradle.java.installations.auto-download=false",
                   "-Porg.gradle.java.installations.fromEnv=", f"-PtestMajor={actual or major}", *extra, *self.tasks]
        env = {**os.environ, "JAVA_HOME": self.homes[launch or 21]}
        for name in ("JAVA_OPTS", "GRADLE_OPTS", "JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "JDK_JAVA_OPTIONS"):
            env.pop(name, None)
        child = subprocess.Popen(command, cwd=self.root, env=env, stdin=subprocess.DEVNULL,
                                 stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, start_new_session=True)
        try:
            stdout, stderr = child.communicate(timeout=90)
        finally:
            _finish_owned_group(child)
        process = subprocess.CompletedProcess(command, child.returncode, stdout, stderr)
        self.logs.joinpath(f"{run_id}.log").write_text(process.stdout + process.stderr)
        if request_overrides:
            self.assertFalse(directory.joinpath("observation.json").exists())
            return process, None
        receipt = json.loads(directory.joinpath("observation.json").read_text())
        self.assertEqual(run_id, receipt["run_id"])
        self.assertEqual(request["artifact_node"], receipt["artifact_node"])
        self.assertEqual(request["caller_source"], receipt["caller_source"])
        self.assertEqual(hashlib.sha256(path.read_bytes()).hexdigest(), receipt["request_sha256"])
        self.logs.joinpath(f"{run_id}.json").write_text(json.dumps(receipt, indent=2))
        return process, receipt

    def test_real_compiler_homes_bytecode_and_up_to_date_outcomes(self):
        for major in (17, 21):
            process, receipt = self.invoke(major)
            self.assertEqual(0, process.returncode, process.stdout + process.stderr)
            self.assertEqual("completed", receipt["status"])
            self.assertEqual({"home": self.homes[21], "major": 21}, receipt["gradle_jvm"])
            self.assertEqual(set(self.compile_tasks), set(receipt["compilers"]))
            for compiler in receipt["compilers"].values():
                self.assertEqual(self.homes[major], compiler["selected"]["home"])
                self.assertEqual(major, compiler["selected"]["major"])
                self.assertTrue(compiler["selected"]["version"].startswith(f"{major}."))
                self.assertTrue(compiler["outcome"]["did_work"])
                outputs = list(Path(compiler["selected"]["destination"]).glob("*.class"))
                self.assertEqual(1, len(outputs))
                self.assertEqual(major + 44, int.from_bytes(outputs[0].read_bytes()[6:8], "big"))
        process, receipt = self.invoke(21)
        self.assertEqual(0, process.returncode, process.stdout + process.stderr)
        for compiler in receipt["compilers"].values():
            self.assertTrue(compiler["outcome"]["up_to_date"])
            self.assertFalse(compiler["outcome"]["did_work"])

    def test_wrong_jvm_compiler_parallel_and_executable_fail_before_compilation(self):
        for options in ({"launch": 17}, {"actual": 21}, {"extra": ["--parallel"]}, {"extra": ["-PbadCompiler=true"]}):
            with self.subTest(options=options):
                process, receipt = self.invoke(17, **options)
                self.assertNotEqual(0, process.returncode)
                self.assertIn("Build observation rejected:", process.stdout + process.stderr)
                self.assertEqual("failed", receipt["status"])
                self.assertFalse(list(self.root.glob("*/build/classes/**/*.class")))

    def test_fractional_schema_major_and_oversized_requests_are_rejected(self):
        for values in ({"schema_version": 1.0}, {"compile_major": 17.0}, {"caller_source": {"large": "x" * 65536}}):
            with self.subTest(values=list(values)):
                process, _ = self.invoke(17, request_overrides=values)
                self.assertNotEqual(0, process.returncode)
                self.assertFalse(list(self.root.glob("*/build/classes/**/*.class")))


if __name__ == "__main__":
    unittest.main()

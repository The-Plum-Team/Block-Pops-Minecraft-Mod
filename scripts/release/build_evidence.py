"""Read exact durable build evidence; authentication and newest-run selection stay external."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import stat
import sys
from pathlib import Path, PurePosixPath

REPO = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO))

from scripts.lib.secure_json import canonical_json, read, require_object
from scripts.release.artifact_manifest import lane_build_identity, verify_staged
from scripts.release.build_matrix import SUPPORTED_JAVA, _toolchain_flags, plan_build
from scripts.release.matrix import MAX_MATRIX_BYTES, load_matrix_document

MAX_REPORT_BYTES = 8 * 1024 * 1024


class BuildEvidenceError(ValueError):
    """Report bytes or their binding to verified artifacts are invalid."""


def _check(condition, message):
    if not condition:
        raise BuildEvidenceError(message)


def _digest(value, length=64):
    _check(isinstance(value, str) and re.fullmatch(f"[0-9a-f]{{{length}}}", value), "invalid evidence digest")
    return value


def _record(value):
    require_object(value, label="file snapshot", required={"path", "size", "sha256", "git_blob", "executable"})
    path = PurePosixPath(value["path"])
    _check(path.parts and not path.is_absolute() and ".." not in path.parts
           and path.as_posix() == value["path"] and "\\" not in value["path"], "unsafe snapshot path")
    _check(type(value["size"]) is int and value["size"] >= 0 and type(value["executable"]) is bool,
           "invalid file snapshot types")
    _digest(value["sha256"]); _digest(value["git_blob"], 40)
    return value


def _report_bytes(path):
    path = Path(path).absolute()
    before = [item.lstat() for item in (path, *path.parents)]
    _check(all(not stat.S_ISLNK(item.st_mode) for item in before) and before[0].st_nlink == 1,
           "build report cannot use links")
    report, raw = read(path, label="build report", max_bytes=MAX_REPORT_BYTES)
    stamp = lambda item: (item.st_dev, item.st_ino, item.st_mode, item.st_size, item.st_mtime_ns, item.st_ctime_ns)
    _check([stamp(item) for item in before] == [stamp(item.lstat()) for item in (path, *path.parents)],
           "build report path changed during read")
    return report, raw


def read_lane_build_evidence(report_path, *, matrix_path, artifact_node, artifact_manifest, expected_sha256):
    """Bind an externally authenticated raw report digest to an already verified schema3 bundle.

    The caller owns both prerequisites; this API authenticates neither. It reuses planning to
    check commands and reads no historical checkout/JDK paths. Compiler observations were
    checked by the producer's live lease; this reader checks their durable scope and records,
    not the old process. It proves neither freshness nor qualification/publication authority.
    This first contract supports POSIX runner reports, matching the current execution runner.
    """
    try:
        _digest(expected_sha256)
        report, raw = _report_bytes(report_path)
        _check(hashlib.sha256(raw).hexdigest() == expected_sha256, "build report digest mismatch")
        require_object(report, label="build report", required={"schema_version", "kind", "status", "run_id",
                       "plan", "source", "lanes", "error", "toolchains"})
        _check(canonical_json([report["schema_version"], report["kind"], report["status"], report["error"]])
               == canonical_json([1, "blockpops-build-run", "success", None]), "build did not finish successfully")
        _check(isinstance(report["run_id"], str) and re.fullmatch("[0-9a-f]{32}", report["run_id"]), "invalid build run id")
        manifest = json.loads(canonical_json(artifact_manifest))
        _check(type(manifest["schema_version"]) is int and manifest["schema_version"] == 3, "verified schema3 bundle required")
        document = load_matrix_document(Path(matrix_path))
        lane = document.inventory.lane(artifact_node)
        plan = report["plan"]
        scope, nodes = plan["scope"], plan["selected_nodes"]
        _check(scope in {"lane", "legacy", "full"} and isinstance(nodes, list) and artifact_node in nodes, "build scope lacks selected lane")
        canonical = plan_build(Path(matrix_path), scope="full" if scope == "lane" else scope,
            artifact_node=nodes[0] if scope == "lane" else None,
            clean=any(arg.endswith(":clean") for arg in plan["lanes"][0]["command"]), windows=False)
        current, original = canonical["source"]["repository"], plan["source"]["repository"]
        _check(isinstance(original, str) and PurePosixPath(original).is_absolute()
               and PurePosixPath(original).as_posix() == original and ".." not in PurePosixPath(original).parts,
               "invalid historical checkout path")
        def relocate(value):
            if isinstance(value, dict): return {key: relocate(item) for key, item in value.items()}
            if isinstance(value, list): return [relocate(item) for item in value]
            return original + value[len(current):] if isinstance(value, str) and (value == current or value.startswith(current + "/")) else value
        _check(canonical_json(plan) == canonical_json(relocate(canonical)), "build plan differs from current matrix")
        matrix_digest = plan["matrix"]["sha256"]
        _check(matrix_digest == manifest["matrix"]["sha256"], "build matrix differs from verified bundle")
        source = report["source"]
        require_object(source, label="build source", required={"commit", "tree", "index_sha256", "dirty", "fingerprint", "files"})
        _check(source["dirty"] is False and source["commit"] == manifest["git_commit"]
               and source["tree"] == manifest["git_tree"], "dirty or mismatched build source")
        _digest(source["index_sha256"])
        files = {_record(row)["path"]: row for row in source["files"]}
        _check(list(files) == sorted(files) and len(files) == len(source["files"])
               and hashlib.sha256(json.dumps(source["files"], sort_keys=True).encode()).hexdigest() == source["fingerprint"],
               "source snapshot inventory is inconsistent")
        for record in (manifest["matrix"], manifest["scenario_contract"]):
            _check(files[record["path"]]["sha256"] == record["sha256"], "source snapshot has stale inputs")
        rows = [row for row in manifest["artifacts"] if row["artifact_node"] == artifact_node]
        _check(len(rows) == 1, "verified bundle lacks exactly one selected lane")
        selected = rows[0]
        identity = lane_build_identity(document, artifact_node, matrix_digest=matrix_digest,
            contract_digest=manifest["scenario_contract"]["sha256"], commit=source["commit"], tree=source["tree"])
        _check(canonical_json(selected["build_identity"]) == canonical_json(identity), "stale lane version or build identity")
        tools = report["toolchains"]
        require_object(tools, label="toolchain probes", required={"status", "gradle_jvm", "compiler_selection",
                       "homes", "environment", "property_overrides", "lanes"})
        _check(tools["status"] == "probed"
               and set(tools["homes"]) <= {str(major) for major in SUPPORTED_JAVA}, "missing JDK probes")
        # Probe markers never claim the JVM/compiler observations carried by each lane.
        _check(tools["gradle_jvm"] == "unverified" and tools["compiler_selection"] == "unverified",
               "JDK probes cannot claim observed execution")
        homes = {int(major): Path(row["home"]) for major, row in tools["homes"].items()}
        for major, home in homes.items():
            probe = tools["homes"][str(major)]
            require_object(probe, label="JDK probe", required={"home", "java_version", "javac_version", "files"})
            _check(home.is_absolute() and ".." not in home.parts and str(home) == probe["home"]
                   and not any(character in probe["home"] for character in ",\r\n")
                   and all(re.fullmatch(rf"{major}\.\d+(?:[.0-9A-Za-z+-]*)", probe[key])
                   for key in ("java_version", "javac_version")), "invalid durable JDK probe")
            _check(set(probe["files"]) == {"java", "javac", "release"}, "incomplete JDK file records")
            for kind, record in probe["files"].items():
                _check(_record(record)["path"] == ("release" if kind == "release" else "bin/" + kind), "stale JDK file record")
        # Gradle launches on the Java the planned lanes declare, not a fixed major.
        launch = plan["lanes"][0]["required_java"]["gradle"]
        flags = _toolchain_flags(homes, launch)
        _check(tools["property_overrides"] == flags
               and tools["environment"] == {"JAVA_HOME": str(homes[launch])}, "stale JDK binding")
        _check([row["artifact_node"] for row in report["lanes"]] == nodes
               == [row["artifact_node"] for row in tools["lanes"]], "incomplete build results")
        for planned, result, bound in zip(plan["lanes"], report["lanes"], tools["lanes"], strict=True):
            require_object(result, label="lane result", required={"artifact_node", "command", "exit_code", "outputs",
                           "log", "observation", "archive_validation"})
            node, major = planned["artifact_node"], planned["required_java"]["artifact"]
            expected_command = [planned["command"][0], *flags, *planned["command"][1:]]
            _check(bound == {"artifact_node": node, "command": expected_command, "compile_home": str(homes[major]),
                            "runtime_home": str(homes[planned["required_java"]["runtime"]])}, "stale lane toolchain")
            prefix = f"build/observations/{report['run_id']}/{node}"
            command = [expected_command[0], "--no-configuration-cache", "--init-script",
                       original + "/gradle/build-observation.init.gradle", f"-Dblockpops.observation.request={original}/{prefix}/request.json",
                       *expected_command[1:]]
            _check(result["command"] == command and type(result["exit_code"]) is int and result["exit_code"] == 0
                   and result["archive_validation"] == "boundary-and-metadata", "lane execution evidence is incomplete")
            observation = result["observation"]
            require_object(observation, label="durable observation", required={"status", "receipt", "compilers", "classes"})
            _check(observation["status"] == "observed" and _record(observation["receipt"])["path"] == prefix + "/observation.json"
                   and _record(result["log"])["path"] == prefix + "/gradle.log", "stale observation/log binding")
            suffix = ":" + planned["minecraft"] if planned["build_layout"] == "stonecutter" else ""
            projects = [":common" + suffix, ":" + planned["loader"] + suffix]
            required = {project + ":compileJava" for project in projects} | {projects[1] + ":compileE2eJava"}
            optional = {project + ":compileTestJava" for project in projects}
            _check(required <= set(observation["compilers"]) <= required | optional
                   and set(observation["classes"]) == set(observation["compilers"]), "compiler scope is incomplete")
            for task, compiler in observation["compilers"].items():
                selection, outcome = compiler["selected"], compiler["outcome"]
                module = task.split(":")[1]
                base = module + ("/versions/" + planned["minecraft"] if suffix else "")
                source_set = "test" if task.endswith(":compileTestJava") else "e2e" if task.endswith(":compileE2eJava") else "main"
                destination = base + "/build/classes/java/" + source_set
                version = selection["version"]
                expected_selection = {"major": major, "release": major, "home": str(homes[major]), "version": version,
                    "executable": str(homes[major] / "bin/javac"), "destination": original + "/" + destination}
                compiled = {"did_work": True, "skipped": False, "up_to_date": False, "no_source": False, "skip_message": None, "failed": False}
                unchanged = {"did_work": False, "skipped": True, "up_to_date": True, "no_source": False, "skip_message": "UP-TO-DATE", "failed": False}
                empty = {"did_work": False, "skipped": True, "up_to_date": False, "no_source": True, "skip_message": "NO-SOURCE", "failed": False}
                no_source = task in optional and canonical_json(outcome) == canonical_json(empty)
                _check(set(compiler) == {"selected", "outcome"} and canonical_json(selection) == canonical_json(expected_selection)
                       and re.fullmatch(re.escape(tools["homes"][str(major)]["java_version"]) + r"(?:\+[A-Za-z0-9.+-]+)?", version)
                       and (no_source or canonical_json(outcome) in (canonical_json(compiled), canonical_json(unchanged))), "invalid compiler selection/outcome")
                classes = observation["classes"][task]
                _check(isinstance(classes, list) and bool(classes) != no_source
                       and len({record["path"] for record in classes}) == len(classes), "missing or duplicate compiler class evidence")
                for record in classes:
                    _check(_record(record)["path"].startswith(destination + "/")
                           and record["path"].endswith(".class"), "class evidence outside compiler destination")
            configured = document.inventory.lane(node)
            _check(set(result["outputs"]) == {"production", "harness"}, "build output kinds differ")
            for kind, relative in (("production", configured.production_jar), ("harness", configured.harness_jar)):
                record = _record(result["outputs"][kind])
                _check(record["path"] == relative, "output belongs to another lane")
                if node == artifact_node:
                    _check(record["sha256"] == selected[kind]["sha256"] and type(selected[kind]["bytes"]) is int
                           and record["size"] == selected[kind]["bytes"], "build output differs from verified bundle")
        _check(_report_bytes(report_path)[1] == raw, "build report changed during validation")
        _check(hashlib.sha256(read(Path(matrix_path), label="final build matrix", max_bytes=MAX_MATRIX_BYTES)[1]).hexdigest()
               == matrix_digest, "build matrix changed during validation")
        return {"artifact_node": artifact_node, "build_identity": identity, "report_sha256": expected_sha256,
                "run_id": report["run_id"], "production": dict(selected["production"]), "harness": dict(selected["harness"])}
    except (OSError, ValueError, KeyError, TypeError, IndexError, AttributeError) as exc:
        raise BuildEvidenceError(f"invalid durable build evidence: {exc}") from exc


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repository", type=Path, default=REPO)
    for name, default in (("matrix", "release/release-matrix.json"), ("report", "build/build-matrix-report.json"),
                          ("stage", "build/release"), ("manifest", "build/release/artifacts.json")):
        parser.add_argument("--" + name, type=Path, default=Path(default))
    parser.add_argument("--scope", choices=("legacy", "full"), required=True)
    parser.add_argument("--expected-report-sha256", required=True, help="external byte binding, not freshness authority")
    parser.add_argument("--expected-commit", required=True)
    parser.add_argument("--expected-tree", required=True)
    args = parser.parse_args(argv)
    try:
        repository = args.repository.resolve(strict=True)
        paths = {name: value if value.is_absolute() else repository / value
                 for name, value in ((name, getattr(args, name)) for name in ("matrix", "report", "stage", "manifest"))}
        _digest(args.expected_report_sha256)
        _digest(args.expected_commit, 40); _digest(args.expected_tree, 40)
        manifest = verify_staged(repository=repository, matrix_path=paths["matrix"],
            manifest_path=paths["manifest"], stage=paths["stage"], scope=args.scope)
        _check(manifest["git_commit"] == args.expected_commit and manifest["git_tree"] == args.expected_tree,
               "verified bundle differs from externally expected source")
        document = load_matrix_document(paths["matrix"])
        for lane in document.select_lanes(scope=args.scope):
            read_lane_build_evidence(paths["report"], matrix_path=paths["matrix"],
                artifact_node=lane.identity.artifact_node, artifact_manifest=manifest,
                expected_sha256=args.expected_report_sha256)
    except (OSError, ValueError, KeyError, TypeError) as exc:
        print(f"build evidence verification failed: {exc}", file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

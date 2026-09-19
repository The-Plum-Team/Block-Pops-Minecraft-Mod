"""Compose independently bound Build and E2E content without granting authority."""

import hashlib
import re
from dataclasses import asdict
from pathlib import Path

from scripts.ci.e2e_fanin import (AGGREGATE_RECEIPT, MAX_JSON_BYTES, SourceIdentity,
                                _inventory, _read_file, validate_aggregate)
from scripts.lib.secure_json import canonical_json, read
from scripts.release.artifact_manifest import MAX_MANIFEST_BYTES, _manifest_file, _scoped_path, verify_staged
from scripts.release.build_evidence import MAX_REPORT_BYTES, read_lane_build_evidence
from scripts.release.matrix import MAX_MATRIX_BYTES


class ContentEvidenceError(ValueError):
    """Content does not match the caller's independent byte and source bindings."""


def _check(condition, message):
    if not condition:
        raise ContentEvidenceError(message)


def _bound(path, digest, maximum):
    _check(isinstance(digest, str) and re.fullmatch("[0-9a-f]{64}", digest), "invalid external digest")
    value, raw = read(path, label="release content input", max_bytes=maximum)
    _check(hashlib.sha256(raw).hexdigest() == digest, "release content digest differs")
    return value, raw


def read_lane_content_evidence(*, repository, matrix_path, expected_matrix_sha256, artifact_node,
        build_stage, build_scope, expected_build_manifest_sha256, build_report_path,
        expected_build_report_sha256, e2e_stage, e2e_scope, expected_e2e_manifest_sha256,
        aggregate_root, expected_aggregate_sha256, expected_e2e_identity: SourceIdentity):
    """Verify both complete bundles and choose only the E2E-tested production.

    All expected digests, scopes and SourceIdentity are caller-owned inputs. The
    aggregate digest binds aggregate.json, not its transport ZIP. The caller must
    authenticate downloads and current producers, then repeat those live checks
    after this function. This local reader proves neither freshness nor release
    qualification. Build's report attests its own bytes, which can differ from E2E.
    """
    try:
        _check(type(expected_e2e_identity) is SourceIdentity, "external E2E identity is required")
        identity = expected_e2e_identity
        identity.validate()
        repository, matrix_path = Path(repository).resolve(), Path(matrix_path)
        build_stage, e2e_stage, aggregate_root = (Path(path).absolute()
                                                for path in (build_stage, e2e_stage, aggregate_root))
        bindings = [
            (matrix_path, expected_matrix_sha256, MAX_MATRIX_BYTES),
            (build_stage / "artifacts.json", expected_build_manifest_sha256, MAX_MANIFEST_BYTES),
            (Path(build_report_path), expected_build_report_sha256, MAX_REPORT_BYTES),
            (e2e_stage / "artifacts.json", expected_e2e_manifest_sha256, MAX_MANIFEST_BYTES),
            (aggregate_root / AGGREGATE_RECEIPT, expected_aggregate_sha256, MAX_JSON_BYTES),
        ]
        inputs = [_bound(*binding) for binding in bindings]
        aggregate_inventory = _inventory(aggregate_root)
        matrix, build_manifest, report, e2e_manifest, receipt = [item[0] for item in inputs]
        _check(type(matrix.get("schema_version")) is int and matrix["schema_version"] == 2,
               "release content requires schema2")
        _check(matrix["branch"]["name"] == identity.source_branch, "external source branch differs")

        def bundle(stage, scope, expected):
            _check(scope in {"lane", "legacy", "full"}, "explicit bundle scope is required")
            verified = verify_staged(repository=repository, matrix_path=matrix_path,
                manifest_path=stage / "artifacts.json", stage=stage, scope=scope,
                artifact_node=artifact_node if scope == "lane" else None)
            _check(canonical_json(verified) == canonical_json(expected), "bundle changed during composition")
            _check((verified["git_commit"], verified["git_tree"]) == (identity.commit, identity.tree),
                   "bundle differs from external source identity")
            rows = [row for row in verified["artifacts"] if row["artifact_node"] == artifact_node]
            _check(len(rows) == 1, "bundle lacks the requested lane")
            return rows[0]

        built = bundle(build_stage, build_scope, build_manifest)
        tested = bundle(e2e_stage, e2e_scope, e2e_manifest)
        _check(canonical_json(built["build_identity"]) == canonical_json(tested["build_identity"]),
               "Build and E2E lane identities differ")
        _check(canonical_json([report["plan"]["scope"], report["plan"]["selected_nodes"]])
               == canonical_json([build_manifest["scope"]["kind"], build_manifest["scope"]["selected_nodes"]]),
               "Build report and bundle inventories differ")
        for node in build_manifest["scope"]["selected_nodes"]:
            read_lane_build_evidence(build_report_path, matrix_path=matrix_path, artifact_node=node,
                artifact_manifest=build_manifest, expected_sha256=expected_build_report_sha256)
        aggregate = validate_aggregate(root=aggregate_root, matrix_path=matrix_path,
            contract_path=repository / "e2e/scenario-contract.json", projection=identity.projection,
            expected_repository=identity.repository, expected_source_branch=identity.source_branch,
            expected_commit=identity.commit, expected_tree=identity.tree, expected_run_id=identity.run_id,
            expected_run_attempt=identity.run_attempt, scope=e2e_scope,
            artifact_node=artifact_node if e2e_scope == "lane" else None, artifact_manifest=e2e_manifest,
            artifact_manifest_sha256=expected_e2e_manifest_sha256)
        _check(canonical_json(aggregate) == canonical_json(receipt), "aggregate changed during composition")
        selected = [row for row in aggregate["lanes"] if row["artifact_node"] == artifact_node]
        _check(len(selected) == 1, "aggregate lacks the requested lane")
        # Revalidate archives after report/pixel validation, then every initial raw
        # binding. A later consumer still must verify the selected bytes on use.
        bundle(build_stage, build_scope, build_manifest)
        bundle(e2e_stage, e2e_scope, e2e_manifest)
        for record in aggregate["files"]:
            payload = _read_file(aggregate_root, record["path"], allow_empty="/logs/" in "/" + record["path"])
            _check(len(payload) == record["size"] and hashlib.sha256(payload).hexdigest() == record["sha256"],
                   "aggregate payload changed during composition")
        _check(_inventory(aggregate_root) == aggregate_inventory, "aggregate inventory changed during composition")
        for stage, manifest in ((build_stage, build_manifest), (e2e_stage, e2e_manifest)):
            for row in manifest["artifacts"]:
                for kind in ("production", "harness"):
                    _scoped_path(repository, stage / row[kind]["path"])
                    _manifest_file(stage, row[kind], label="final " + kind)
        for binding, (_, raw) in zip(bindings, inputs, strict=True):
            _check(_bound(*binding)[1] == raw, "content inputs changed during composition")
        return {"schema_version": 1, "kind": "blockpops-lane-content-evidence",
            "artifact_node": artifact_node, "build_identity": tested["build_identity"],
            "production": {"origin": "e2e", "stage": str(e2e_stage), **tested["production"]},
            "build": {"scope": build_manifest["scope"], "manifest_sha256": expected_build_manifest_sha256,
                "report_sha256": expected_build_report_sha256, "runner_run_id": report["run_id"],
                "production": built["production"], "harness": built["harness"]},
            "e2e": {"scope": e2e_manifest["scope"], "manifest_sha256": expected_e2e_manifest_sha256,
                "aggregate_sha256": expected_aggregate_sha256, "source_identity": asdict(identity),
                "scenarios": selected[0]["scenarios"], "harness": tested["harness"]}}
    except (OSError, ValueError, KeyError, TypeError, IndexError, AttributeError) as exc:
        raise ContentEvidenceError(f"invalid lane content evidence: {exc}") from exc

"""Render real compact pixels using externally authenticated selection fixtures."""

import copy
import hashlib
import json
from pathlib import Path
import shutil
import tempfile
import unittest
from unittest.mock import patch

from scripts.ci.tests.matrix_fixtures import schema1_matrix
from scripts.pages import build_site as site, evidence
from tests import test_pages_compact_selection as bindings


def discovery_row(matrix, raw):
    artifacts = sorted(matrix["artifacts"], key=lambda lane: (tuple(map(int, lane["minecraft"].split("."))), lane["loader"]))
    configured = [row["artifact_node"] for row in artifacts]
    migration = matrix.get("migration")
    nodes = migration["legacy_nodes"] if migration and migration["mode"] == "preparing" else configured
    selected = [row for row in artifacts if row["artifact_node"] in nodes]
    targets = [row["artifact_node"] for row in matrix["targets"]] if migration else configured
    return dict(name=matrix["branch"]["name"], commit="b" * 40, tree="c" * 40,
        matrix_blob=hashlib.sha1(b"blob " + str(len(raw)).encode() + b"\0" + raw).hexdigest(),
        matrix_sha256=evidence.sha256_bytes(raw), matrix_schema_version=matrix["schema_version"],
        minecraft_versions=sorted({row["minecraft"] for row in selected}, key=lambda value: tuple(map(int, value.split(".")))),
        loaders=sorted({row["loader"] for row in selected}), java=sorted({row["java"] for row in selected}),
        configured_nodes=configured, scope=dict(kind="unscoped" if not migration else "legacy" if migration["mode"] == "preparing" else "full",
            selected_nodes=nodes, target_nodes=targets, migration_mode=migration["mode"] if migration else None,
            partial=set(nodes) != set(targets)))


class ScopedSiteTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        bindings.CompactSelectionTests.setUpClass()
        cls.addClassCleanup(bindings.CompactSelectionTests.doClassCleanups)
        cls.templates = {}
        for key, options in ((False, {}), (True, {"shared": True}), ("cache", {"cache": True}), ("scheduled", {"event": "schedule"})):
            fixture = bindings.CompactSelectionTests()
            fixture.prepare(**options); cls.addClassCleanup(fixture.doCleanups)
            cls.templates[key] = fixture, fixture.bind()

    def setUp(self):
        temporary = tempfile.TemporaryDirectory(); self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name).resolve()
        self.configure()

    def configure(self, shared=False):
        self.current = self.root / str(len(list(self.root.iterdir()))); self.current.mkdir()
        fixture, selection = self.templates[shared]
        self.collected = self.current / "collected"; self.collected.mkdir()
        self.bundle = self.collected / "master"
        shutil.copytree(fixture.output, self.bundle)
        self.matrix_path = self.current / "matrix.json"
        self.matrix_path.write_bytes(fixture.pixels.matrix_path.read_bytes())
        self.selection_path = self.current / "selection.json"
        self.selection_path.write_text(json.dumps(selection))
        self.rows = [discovery_row(json.loads(self.matrix_path.read_bytes()), self.matrix_path.read_bytes())]
        self.inventory_path = self.current / "inventory.json"
        self.inventory_path.write_text(json.dumps(self.rows))
        self.inputs = {"master": dict(matrix_path=self.matrix_path, selection_path=self.selection_path,
                                    selection_sha256=evidence.sha256_bytes(self.selection_path.read_bytes()))}
        self.output = self.current / "site"

    def build(self, **overrides):
        return site.build(**{**dict(evidence_root=self.collected, inventory_path=self.inventory_path,
            output=self.output, repository="AkaNebur/BlockPops", canonical_matrix=self.matrix_path,
            branch_inputs=self.inputs), **overrides})

    def rejected(self):
        with self.assertRaises(site.SiteError): self.build()
        self.assertFalse(self.output.exists())

    def test_preparing_shared_and_mixed_schema_gallery_keep_exact_scope_and_pixels(self):
        for shared in (False, True, "cache", "scheduled"):
            self.configure(shared)
            summary = self.build()
            gallery = json.loads((self.output / "gallery-data.json").read_bytes())
            self.assertEqual(self.templates[shared][1]["aggregate_scope"], gallery["releases"][0]["aggregate_scope"])
            self.assertEqual(60 if shared is True else 10, summary["frames"])
            for frame in gallery["frames"]:
                self.assertEqual(frame["published_sha256"], evidence.sha256_bytes((self.output / frame["image"]).read_bytes()))
        self.configure()
        fixture = self.templates[False][0]
        raw_root = self.current / "legacy-raw"; shutil.copytree(fixture.pixels.raw, raw_root)
        matrix = schema1_matrix()
        matrix["branch"].update(name="release/legacy", role="release", sync=dict(enabled=True, source="master"))
        matrix_path = self.current / "legacy-matrix.json"; matrix_path.write_text(json.dumps(matrix))
        raw = json.loads((raw_root / "pages-evidence.json").read_bytes())
        raw.pop("aggregate_scope"); raw["schema_version"] = 1
        raw["provenance"].update(branch="release/legacy", matrix_sha256=evidence.sha256_bytes(matrix_path.read_bytes()))
        raw["provenance"]["packaged"]["branch"] = "release/legacy"
        (raw_root / "pages-evidence.json").write_text(json.dumps(raw))
        evidence.compact(input_root=raw_root, output=self.collected / "legacy", matrix_path=matrix_path,
            expected=raw["provenance"], source_artifact_id=77,
            source_artifact_name=evidence.raw_artifact_name("release/legacy", 2), source_artifact_digest="sha256:" + "a" * 64)
        self.rows.append(discovery_row(matrix, matrix_path.read_bytes()))
        self.inventory_path.write_text(json.dumps(self.rows))
        self.inputs["release/legacy"] = dict(matrix_path=matrix_path, selection_path=None, selection_sha256=None)
        self.assertEqual(2, self.build()["branches"])
        releases = json.loads((self.output / "gallery-data.json").read_bytes())["releases"]
        self.assertNotIn("aggregate_scope", next(row for row in releases if row["branch"] == "release/legacy"))

    def test_complete_typed_discovery_is_recomputed_from_the_external_matrix(self):
        original = self.inventory_path.read_bytes()
        changes = [(key, value) for key, value in (("commit", "d" * 40), ("tree", "d" * 40),
            ("matrix_blob", "d" * 40), ("matrix_sha256", "d" * 64), ("matrix_schema_version", 2.0),
            ("minecraft_versions", ["1.21.1"]), ("loaders", ["fabric"]), ("java", [17.0]), ("configured_nodes", []))]
        for key, value in changes:
            rows = copy.deepcopy(self.rows); rows[0][key] = value
            self.inventory_path.write_text(json.dumps(rows)); self.rejected()
        for key, value in (("kind", "full"), ("partial", 1), ("selected_nodes", ["fabric-1.20.1"]),
                           ("target_nodes", []), ("migration_mode", "shared")):
            rows = copy.deepcopy(self.rows); rows[0]["scope"][key] = value
            self.inventory_path.write_text(json.dumps(rows)); self.rejected()
        self.inventory_path.write_bytes(original)

    def test_rehashed_selection_cannot_relabel_provenance_source_scope_or_artifact(self):
        original = json.loads(self.selection_path.read_bytes())
        mutations = [lambda m: m.update(schema_version=True), lambda m: m.update(kind="other"),
            lambda m: m["source"].update(tree="f" * 40), lambda m: m["provenance"]["packaged"].update(run_id=99),
            lambda m: m["aggregate_scope"].update(projection="scheduled-anchors"), lambda m: m["aggregate_scope"].update(partial=1),
            lambda m: m.update(compact_manifest_sha256="f" * 64), lambda m: m["source_artifact"].update(id=99),
            lambda m: m["selected_artifact"].update(run_attempt=2.0), lambda m: m["selected_artifact"].update(id=99),
            lambda m: m.update(attested=0), lambda m: m.update(handoff_run_id=101.0), lambda m: m.update(packaged_job_graph_sha256=0)]
        for mutation in mutations:
            changed = copy.deepcopy(original); mutation(changed)
            self.selection_path.write_text(json.dumps(changed))
            self.inputs["master"]["selection_sha256"] = evidence.sha256_bytes(self.selection_path.read_bytes())
            self.rejected()

    def test_missing_extra_malformed_and_unbound_inputs_fail_closed(self):
        original = copy.deepcopy(self.inputs)
        for inputs in ({}, {**original, "unknown": original["master"]},
                {"master": dict(original["master"], selection_sha256="f" * 64)},
                {"master": dict(original["master"], selection_path=None)},
                {"master": dict(original["master"], unused=True)}):
            self.inputs = inputs; self.rejected()
        self.inputs = original
        self.selection_path.write_bytes(b'{"kind":0,"kind":1}')
        self.inputs["master"]["selection_sha256"] = evidence.sha256_bytes(self.selection_path.read_bytes())
        self.rejected()
        with self.assertRaises(site.SiteError): self.build(branch_inputs=None)

    def test_final_snapshots_and_payload_hashes_close_render_time_drift(self):
        contract = self.current / "contract.json"; contract.write_bytes(site.DEFAULT_CONTRACT.read_bytes())
        manifest = json.loads((self.bundle / "manifest.json").read_bytes())
        paths = [self.matrix_path, self.selection_path, self.inventory_path, contract,
            self.bundle / "manifest.json", self.bundle / manifest["files"][0]["path"]]
        write = site.write_new
        for changed_path in paths:
            raw = changed_path.read_bytes()
            def changed(fd, relative, data):
                write(fd, relative, data)
                if relative == "gallery-data.json": changed_path.write_bytes(raw + b" ")
            with patch.object(site, "DEFAULT_CONTRACT", contract), patch.object(site, "write_new", side_effect=changed):
                self.rejected()
            changed_path.write_bytes(raw)

    def test_symlinked_selection_and_missing_or_duplicate_compacts_fail(self):
        raw = self.selection_path.read_bytes()
        target = self.current / "elsewhere.json"; target.write_bytes(raw)
        self.selection_path.unlink(); self.selection_path.symlink_to(target)
        self.rejected()
        self.selection_path.unlink(); self.selection_path.write_bytes(raw)
        duplicate = self.collected / "duplicate"; shutil.copytree(self.bundle, duplicate)
        self.rejected()
        shutil.rmtree(duplicate); shutil.rmtree(self.bundle)
        self.rejected()

    def test_consistently_rehashed_attested_schedule_is_still_contradictory(self):
        self.configure("scheduled")
        path = self.bundle / "manifest.json"
        manifest = json.loads(path.read_bytes())
        manifest["provenance"]["packaged"]["run_id"] = 99
        path.write_text(json.dumps(manifest))
        selection = json.loads(self.selection_path.read_bytes())
        selection.update(provenance=manifest["provenance"], attested=True, packaged_run_id=99,
            compact_manifest_sha256=evidence.sha256_bytes(path.read_bytes()))
        self.selection_path.write_text(json.dumps(selection))
        self.inputs["master"]["selection_sha256"] = evidence.sha256_bytes(self.selection_path.read_bytes())
        self.rejected()

    def test_private_matrix_snapshot_and_collected_inventory_are_rechecked(self):
        validate = site.validate_compact
        matrix_raw = self.matrix_path.read_bytes()
        def changed(root, **kwargs):
            self.assertNotEqual(self.matrix_path, kwargs["matrix_path"])
            self.matrix_path.write_bytes(matrix_raw + b" ")
            return validate(root, **kwargs)
        with patch.object(site, "validate_compact", side_effect=changed): self.rejected()
        self.matrix_path.write_bytes(matrix_raw)
        copy_static = site._copy_static
        def extra(fd):
            copy_static(fd); (self.collected / "extra").mkdir()
        with patch.object(site, "_copy_static", side_effect=extra): self.rejected()

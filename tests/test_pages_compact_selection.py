"""Real pixel/API fixtures bind compact bytes without granting transport authority."""

import contextlib
import copy
import hashlib
import io
import json
import os
import unittest
from unittest.mock import patch

from scripts.pages import authenticate_source as source, evidence
from tests import test_pages_raw_scope as pixels, test_pages_source_scope as owners


class CompactSelectionTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        pixels.ScopedRawPagesTests.setUpClass()
        cls.addClassCleanup(pixels.ScopedRawPagesTests.doClassCleanups)

    def prepare(self, *, shared=False, event="workflow_dispatch", attested=False, cache=False):
        self.owner = owners.ScopedSourceTests()
        self.owner.setUp(); self.addCleanup(self.owner.doCleanups)
        self.owner.prepare(shared=shared, event=event, attested=attested, compact=cache)
        self.pixels = pixels.ScopedRawPagesTests()
        self.pixels.setUp(); self.addCleanup(self.pixels.doCleanups)
        self.pixels.prepare("full" if shared else "legacy", shared=shared,
            projection="scheduled-anchors" if event == "schedule" and not attested else "pr-anchors")
        p, owner = self.pixels, self.owner
        p.manifest["provenance"] = copy.deepcopy(owner.manifest["provenance"])
        p.expected = p.manifest["provenance"]
        p.write(p.manifest)
        self.output = p.root / "compact"
        compact_root = p.root / "selected-cache" if cache else self.output
        self.manifest = evidence.compact(input_root=p.raw, output=compact_root,
            matrix_path=p.matrix_path, expected=p.expected, **p.arguments,
            source_artifact_id=77 if cache else owner.selected.id,
            source_artifact_name=evidence.raw_artifact_name(p.expected["branch"], 2),
            source_artifact_digest="sha256:" + "d" * 64 if cache else owner.selected.digest)
        if cache:
            evidence.copy_compact(input_root=compact_root, output=self.output,
                matrix_path=p.matrix_path, expected=p.expected, **p.arguments)
        self.selected_root = compact_root if cache else p.raw
        self.arguments = {**owner.arguments, "evidence_root": self.selected_root,
                          "matrix_path": p.matrix_path, "bind_compact": self.output}
        self.manifest_path = self.output / "manifest.json"

    def bind(self, **overrides):
        return source.authenticate(self.owner.api, **{**self.arguments, **overrides})

    def write(self, manifest):
        self.manifest_path.write_text(json.dumps(manifest))

    def test_direct_scheduled_attested_full_and_cache_bind_real_compact_bytes(self):
        for options in ({}, {"event": "schedule"}, {"attested": True}, {"shared": True}, {"cache": True}):
            with self.subTest(options=options):
                self.prepare(**options)
                before = {str(path.relative_to(self.output)): path.read_bytes()
                          for path in self.output.rglob("*") if path.is_file()}
                result = self.bind()
                self.assertEqual("pages-compact-selection", result["kind"])
                self.assertEqual(self.manifest["provenance"], result["provenance"])
                self.assertEqual(self.manifest["aggregate_scope"], result["aggregate_scope"])
                self.assertEqual(self.manifest["source_artifact"], result["source_artifact"])
                self.assertEqual(hashlib.sha256(before["manifest.json"]).hexdigest(), result["compact_manifest_sha256"])
                self.assertEqual(self.owner.selected.id, result["selected_artifact"]["id"])
                self.assertEqual(self.owner.selected.run_id, result["selected_artifact"]["run_id"])
                self.assertEqual(2, result["selected_artifact"]["run_attempt"])
                self.assertEqual({"schema_version", "kind", "source", "provenance", "aggregate_scope",
                    "attested", "handoff_run_id", "packaged_run_id", "packaged_job_graph_sha256",
                    "compact_manifest_sha256", "source_artifact", "selected_artifact"}, set(result))
                self.assertEqual(before, {str(path.relative_to(self.output)): path.read_bytes()
                                         for path in self.output.rglob("*") if path.is_file()})

    def test_exact_provenance_source_artifact_coverage_and_frame_sources_are_required(self):
        self.prepare()
        original = copy.deepcopy(self.manifest)
        mutations = [lambda m: m["provenance"]["packaged"].update(run_id=99),
            lambda m: m["provenance"]["handoff"].update(run_attempt=2.0),
            lambda m: m["source_artifact"].update(id=999),
            lambda m: m["source_artifact"].update(digest="sha256:" + "f" * 64),
            lambda m: m["aggregate_scope"].update(kind="full"),
            lambda m: m["aggregate_scope"].update(projection="scheduled-anchors"),
            lambda m: m["aggregate_scope"].update(partial=1),
            lambda m: m["frames"][0]["source"].update(sha256="f" * 64)]
        for mutate in mutations:
            changed = copy.deepcopy(original); mutate(changed); self.write(changed)
            with self.subTest(mutation=mutate), self.assertRaises(source.SourceAuthenticationError): self.bind()

    def test_cache_preserves_original_raw_artifact_and_exact_manifest_bytes(self):
        self.prepare(cache=True)
        self.assertEqual(77, self.bind()["source_artifact"]["id"])
        raw = self.manifest_path.read_bytes()
        self.manifest_path.write_bytes(raw + b" ")
        with self.assertRaisesRegex(source.SourceAuthenticationError, "manifest bytes"): self.bind()
        changed = copy.deepcopy(self.manifest)
        changed["source_artifact"]["id"] = self.owner.selected.id
        self.write(changed)
        with self.assertRaisesRegex(source.SourceAuthenticationError, "manifest bytes"): self.bind()

    def test_pixel_corruption_and_extra_files_cannot_receive_a_binding(self):
        for target in ("raw", "compact", "extra"):
            self.prepare()
            path = (self.pixels.raw / self.pixels.manifest["frames"][0]["source"]["path"] if target == "raw"
                    else self.output / self.manifest["files"][0]["path"] if target == "compact"
                    else self.output / "selection.json")
            path.write_bytes(b"unvalidated")
            with self.subTest(target=target), self.assertRaises(source.SourceAuthenticationError): self.bind()

    def test_rehashed_unrelated_webp_cannot_replace_authenticated_raw_pixels(self):
        from PIL import Image
        self.prepare()
        before = {str(path): path.read_bytes() for path in self.pixels.raw.rglob("*") if path.is_file()}
        dimensions = tuple(self.manifest["frames"][0]["derivative"][key] for key in ("width", "height"))
        for width, height in ((1, 1), dimensions):
            encoded = io.BytesIO(); Image.new("RGB", (width, height), (255, 0, 0)).save(encoded, "WEBP")
            data = encoded.getvalue(); digest = hashlib.sha256(data).hexdigest()
            relative = "images/" + digest + ".webp"
            for path in (self.output / "images").iterdir(): path.unlink()
            (self.output / relative).write_bytes(data)
            self.manifest["files"] = [dict(path=relative, sha256=digest, size=len(data))]
            for frame in self.manifest["frames"]:
                frame["derivative"] = dict(path=relative, sha256=digest, size=len(data), width=width, height=height, format="WEBP")
            self.write(self.manifest)
            evidence.validate_compact(self.output, matrix_path=self.pixels.matrix_path,
                expected=self.pixels.expected, **self.pixels.arguments)
            with self.subTest(dimensions=(width, height)), self.assertRaises(source.SourceAuthenticationError): self.bind()
        self.assertEqual(before, {str(path): path.read_bytes() for path in self.pixels.raw.rglob("*") if path.is_file()})

    def test_final_input_rereads_detect_manifest_matrix_contract_and_payload_drift(self):
        self.prepare()
        original_validate = source.validate_compact
        paths = [self.pixels.raw / "pages-evidence.json", self.manifest_path,
            self.pixels.matrix_path, self.owner.contract_path,
            self.pixels.raw / self.pixels.manifest["frames"][0]["source"]["path"],
            self.output / self.manifest["files"][0]["path"]]
        for path in paths:
            raw = path.read_bytes()
            def changed(*args, **kwargs):
                result = original_validate(*args, **kwargs)
                path.write_bytes(raw + b" ")
                return result
            with patch.object(source, "validate_compact", side_effect=changed):
                with self.subTest(path=path), self.assertRaises(source.SourceAuthenticationError): self.bind()
            path.write_bytes(raw)

    def test_raw_drift_during_reencoding_is_rehashed_before_binding(self):
        self.prepare()
        path = self.pixels.raw / self.pixels.manifest["frames"][0]["source"]["path"]
        raw, encode = path.read_bytes(), source._encode_webp
        def changed(png):
            result = encode(png)
            path.write_bytes(raw + b" ")
            return result
        with patch.object(source, "_encode_webp", side_effect=changed):
            with self.assertRaises(source.SourceAuthenticationError): self.bind()

    def test_snapshot_precedes_api_callbacks_and_malformed_or_unbound_inputs_fail(self):
        self.prepare()
        raw = self.manifest_path.read_bytes()
        changed = copy.deepcopy(self.manifest); changed["source_artifact"]["id"] += 1
        def replaced(run_id):
            self.write(changed)
            return [self.owner.selected]
        with patch.object(self.owner.api, "artifacts_for_run", side_effect=replaced):
            with self.assertRaisesRegex(source.SourceAuthenticationError, "manifest/provenance changed"): self.bind()
        self.manifest_path.write_bytes(raw)
        for override in ({"scope": None}, {"scope": "full"}, {"expected": None},
                         {"bind_compact": self.pixels.raw}, {"bind_compact": self.output / "absent"}):
            with self.subTest(override=override), self.assertRaises(source.SourceAuthenticationError): self.bind(**override)
        self.manifest_path.unlink(); self.manifest_path.symlink_to(self.selected_root / "pages-evidence.json")
        with self.assertRaises(source.SourceAuthenticationError): self.bind()

    def test_default_and_schema1_results_remain_unchanged(self):
        self.prepare()
        result = self.bind(bind_compact=None)
        self.assertNotIn("kind", result); self.assertNotIn("compact_manifest_sha256", result)
        self.owner.prepare(schema1=True)
        before = self.owner.authenticate()
        self.assertEqual(before, self.owner.authenticate(bind_compact=None))
        with self.assertRaisesRegex(source.SourceAuthenticationError, "requires schema2"):
            self.owner.authenticate(bind_compact=self.output)

    def test_cli_emits_binding_only_after_real_validation(self):
        self.prepare(event="schedule")
        arguments = []
        for key, value in self.arguments.items():
            if key == "expected":
                for name, item in value.items(): arguments.extend(["--expected-" + name.replace("_", "-"), item])
            else:
                name = {"matrix_path": "matrix", "evidence_root": "evidence"}.get(key, key.replace("_", "-"))
                arguments.extend(["--" + name, str(value)])
        with patch.dict(os.environ, GH_TOKEN="fixture"), patch.object(source, "GitHubApi", return_value=self.owner.api):
            with contextlib.redirect_stdout(io.StringIO()) as output:
                self.assertEqual(0, source.main(arguments))
            self.assertEqual(self.bind(), json.loads(output.getvalue()))
            self.manifest_path.write_bytes(b"invalid")
            with contextlib.redirect_stdout(io.StringIO()) as rejected, contextlib.redirect_stderr(io.StringIO()):
                self.assertEqual(2, source.main(arguments))
            self.assertEqual("", rejected.getvalue())

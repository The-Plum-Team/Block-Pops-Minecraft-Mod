"""Embedded identity distinguishes clean reproducibility from dirty diagnostics."""

import contextlib
import io
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

from scripts.ci.tests.matrix_fixtures import schema2_configuration
from scripts.release.artifact_manifest import ArtifactError, BUILD_IDENTITY_PATH, scoped_manifest_context, verify_production_jar
from scripts.release.build_identity import generate_identity, main
from scripts.release.build_matrix import BuildProcessError, source_snapshot
from tests.test_artifact_and_report_validation import _fabric_production_entries, _write_zip


class BuildIdentityGenerationTests(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory(); self.addCleanup(temp.cleanup)
        self.repo = Path(temp.name).resolve()
        self.matrix = self.repo / 'release/release-matrix.json'
        self.matrix.parent.mkdir()
        data = schema2_configuration()
        self.matrix.write_text(json.dumps(data))
        for route in data['source_routing'].values():
            for key in ('canonical', 'e2e'):
                (self.repo / route[key]).mkdir(parents=True, exist_ok=True)
        self.source = self.repo / 'common/src/main/Tracked.java'
        self.source.write_text('class Tracked {}')
        contract = self.repo / 'e2e/scenario-contract.json'; contract.parent.mkdir()
        contract.write_text('{}')
        self.git('init', '-q'); self.git('config', 'core.autocrlf', 'false')
        self.git('add', '.')
        self.git('-c', 'user.name=Fixture', '-c', 'user.email=fixture@example.invalid', 'commit', '-qm', 'fixture')
        env = patch.dict(os.environ); env.start(); self.addCleanup(env.stop)
        os.environ.pop('BLOCKPOPS_TESTED_SHA', None)

    def git(self, *args):
        return subprocess.check_output(['git', '-C', str(self.repo), *args], stderr=subprocess.PIPE)

    def generate(self, node='fabric-1.20.1'):
        return generate_identity(self.repo, self.matrix, node)

    def test_clean_identity_matches_reader_for_legacy_and_detached_lanes_and_is_reproducible(self):
        for node in ('fabric-1.20.1', 'neoforge-1.21.1'):
            path = self.generate(node)
            _, _, rows = scoped_manifest_context(self.repo, self.matrix, scope='lane', artifact_node=node)
            self.assertEqual(rows[0]['build_identity'], json.loads(path.read_bytes()))
            self.assertEqual(node.startswith('neoforge'), '/versions/' in str(path))
            before = path.read_bytes()
            self.assertEqual(path, self.generate(node))
            self.assertEqual(before, path.read_bytes())
            self.assertFalse(source_snapshot(self.repo)['dirty'])
        with contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(0, main(['--repository', str(self.repo), '--artifact-node', 'fabric-1.20.1']))

    def test_dirty_or_hidden_source_is_explicitly_diagnostic_and_never_release_identity(self):
        document, _, rows = scoped_manifest_context(self.repo, self.matrix, scope='lane', artifact_node='fabric-1.20.1')
        self.git('update-index', '--assume-unchanged', 'common/src/main/Tracked.java')
        self.source.write_text('class Changed {}')
        path = self.generate()
        observed = json.loads(path.read_bytes())
        self.assertTrue(observed['diagnostic']['dirty'])
        self.assertEqual(source_snapshot(self.repo)['fingerprint'], observed['diagnostic']['source_fingerprint'])
        entries = _fabric_production_entries(); entries[BUILD_IDENTITY_PATH] = path.read_bytes()
        archive = self.repo / 'diagnostic.jar'; _write_zip(archive, entries)
        with self.assertRaisesRegex(ArtifactError, 'embedded build identity'):
            verify_production_jar(archive, document.inventory.lane('fabric-1.20.1').artifact, build_identity=rows[0]['build_identity'])

    def test_source_change_during_generation_and_unknown_lane_fail_without_output(self):
        calls = 0
        def changed(repo):
            nonlocal calls
            calls += 1
            if calls == 2: self.source.write_text('changed while generating')
            return source_snapshot(repo)
        with patch('scripts.release.build_identity.source_snapshot', side_effect=changed), self.assertRaisesRegex(ArtifactError, 'changed'):
            self.generate()
        self.assertFalse(list(self.repo.glob('*/build/generated/build-identity/**/*.json')))
        with contextlib.redirect_stderr(io.StringIO()):
            self.assertEqual(2, main(['--repository', str(self.repo), '--artifact-node', 'unknown']))

    def test_source_change_during_atomic_publication_invalidates_new_identity(self):
        replace = os.replace
        def changed(*args, **kwargs):
            replace(*args, **kwargs)
            self.source.write_text('changed after identity publication')
        with patch('scripts.release.build_identity.os.replace', side_effect=changed):
            with self.assertRaisesRegex(ArtifactError, 'changed before completion'): self.generate()
        self.assertFalse(list(self.repo.glob('*/build/generated/build-identity/**/*.json')))

    def test_linked_input_or_output_parent_cannot_be_followed(self):
        for output in (False, True):
            target = self.repo / 'outside'; target.mkdir(exist_ok=True)
            if output:
                path = self.repo / 'fabric/build'; path.symlink_to(target, target_is_directory=True)
            else:
                path = self.source; path.unlink(); path.symlink_to(target / 'private')
            try:
                with self.assertRaises((ArtifactError, BuildProcessError, OSError)): self.generate()
                self.assertFalse(list(target.iterdir()))
            finally:
                path.unlink()
                if not output: self.source.write_text('class Tracked {}')


if __name__ == '__main__':
    unittest.main()

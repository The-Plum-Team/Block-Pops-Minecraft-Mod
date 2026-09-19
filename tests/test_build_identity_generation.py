"""Embedded identity distinguishes clean reproducibility from dirty diagnostics."""

import contextlib
import copy
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
from scripts.release.matrix import load_matrix_document
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

    def test_clean_legacy_aggregate_is_diagnostic_and_cannot_pass_the_release_reader(self):
        document, _, rows = scoped_manifest_context(self.repo, self.matrix, scope='lane', artifact_node='fabric-1.20.1')
        contexts = set()
        expected = json.dumps(document.gradle_context())
        for node in ('fabric-1.20.1', 'forge-1.20.1'):
            output = generate_identity(self.repo, self.matrix, node, aggregate_context=True,
                                       expected_context_json=expected)
            identity = json.loads(output.read_bytes())
            self.assertEqual('aggregate-legacy-context', identity['diagnostic']['reason'])
            self.assertEqual(identity['build_context_sha256'], identity['diagnostic']['context_sha256'])
            contexts.add(identity['build_context_sha256'])
            if node == 'fabric-1.20.1':
                entries = _fabric_production_entries(); entries[BUILD_IDENTITY_PATH] = output.read_bytes()
                archive = self.repo / 'build/aggregate.jar'; archive.parent.mkdir(exist_ok=True)
                _write_zip(archive, entries)
                with self.assertRaisesRegex(ArtifactError, 'embedded build identity'):
                    verify_production_jar(archive, document.inventory.lane(node).artifact, build_identity=rows[0]['build_identity'])
        self.assertEqual(1, len(contexts))
        self.assertFalse(source_snapshot(self.repo)['dirty'])
        with self.assertRaisesRegex(ArtifactError, 'preparing legacy lane'):
            generate_identity(self.repo, self.matrix, 'neoforge-1.21.1', aggregate_context=True)

    def test_captured_gradle_context_must_match_without_boolean_coercion(self):
        context = load_matrix_document(self.matrix).gradle_context(artifact_node='fabric-1.20.1')
        with contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(0, main(['--repository', str(self.repo), '--artifact-node', 'fabric-1.20.1',
                                      '--expected-context-json', json.dumps(context)]))
        for field, value in (('loader_version', '0.17.4'), ('no_remap', 0)):
            stale = copy.deepcopy(context)
            stale['lanes'][0]['runtime' if field == 'loader_version' else 'artifact'][field] = value
            with self.subTest(field=field), self.assertRaisesRegex(ArtifactError, 'configured Gradle inputs'):
                generate_identity(self.repo, self.matrix, 'fabric-1.20.1', expected_context_json=json.dumps(stale))
        for field in ('installers', 'source_routing'):
            stale = copy.deepcopy(context)
            if field == 'installers':
                installer = context['lanes'][0]['runtime']['installer']
                stale['matrix']['installers'][installer]['sha256'] = 'b' * 64
            else:
                stale['matrix']['source_routing']['common']['e2e'] = 'common/src/legacy-stale'
            with self.subTest(field=field), self.assertRaisesRegex(ArtifactError, 'configured Gradle inputs'):
                generate_identity(self.repo, self.matrix, 'fabric-1.20.1', expected_context_json=json.dumps(stale))
        with self.assertRaisesRegex(ArtifactError, 'configured Gradle inputs'):
            generate_identity(self.repo, self.matrix, 'fabric-1.20.1', aggregate_context=True,
                              expected_context_json=json.dumps(context))

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

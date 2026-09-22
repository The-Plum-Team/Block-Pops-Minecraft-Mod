"""Scoped evidence is verified against the caller and immutable lane identities."""

import copy
import json
import os
import shutil
import contextlib
import io
import subprocess
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from scripts.ci.tests.matrix_fixtures import TARGET_COUNT, schema2_configuration
from scripts.release.artifact_manifest import (
    ArtifactError, BUILD_IDENTITY_PATH, _file_record, scoped_manifest_context, stage_release, verify_staged,
    verify_scoped_staged,
)
from scripts.release.matrix import MatrixError
from tests.test_artifact_and_report_validation import _fabric_harness_entries, _fabric_production_entries, _write_zip


class ScopedManifestTests(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.repo = Path(temp.name).resolve()
        self.matrix_path = self.repo / 'release/release-matrix.json'
        self.matrix_path.parent.mkdir()
        self.matrix = schema2_configuration()
        self.matrix_path.write_text(json.dumps(self.matrix))
        for route in self.matrix['source_routing'].values():
            for key in ('canonical', 'e2e'):
                (self.repo / route[key]).mkdir(parents=True, exist_ok=True)
        self.contract = self.repo / 'e2e/scenario-contract.json'
        self.contract.parent.mkdir()
        self.contract.write_text('{}')
        self.git('init', '-q')
        self.git('config', 'core.autocrlf', 'false')
        self.git('add', 'release', 'e2e')
        self.git('-c', 'user.name=Fixture', '-c', 'user.email=fixture@example.invalid', 'commit', '-qm', 'fixture')
        env = patch.dict(os.environ)
        env.start(); self.addCleanup(env.stop)
        os.environ.pop('BLOCKPOPS_TESTED_SHA', None)
        self.node = 'fabric-1.20.1'
        self.stage = self.repo / 'build/release'
        self.manifest_path = self.stage / 'artifacts.json'
        for kind in ('files', 'harness'):
            (self.stage / kind).mkdir(parents=True)
        self.document, header, rows = self.context()
        row = rows[0]
        lane = self.document.inventory.lane(self.node)
        for kind, directory, source, entries in (
            ('production', 'files', lane.production_jar, _fabric_production_entries()),
            ('harness', 'harness', lane.harness_jar, _fabric_harness_entries()),
        ):
            if kind == 'production':
                metadata = json.loads(entries['fabric.mod.json'])
                metadata['version'] = lane.mod_version
                for dep, key in (('minecraft', 'minecraft'), ('fabricloader', 'loader'),
                                 ('architectury', 'architectury'), ('geckolib', 'geckolib')):
                    metadata['depends'][dep] = lane.artifact['metadata'][key]
                entries['fabric.mod.json'] = json.dumps(metadata).encode()
            entries[BUILD_IDENTITY_PATH] = json.dumps(row['build_identity']).encode()
            path = self.stage / directory / Path(source).name
            _write_zip(path, entries)
            row[kind] = _file_record(path, relative=f'{directory}/{path.name}')
        self.manifest = dict(header, artifacts=rows)
        self.write(self.manifest)

    def git(self, *args):
        return subprocess.check_output(['git', '-C', str(self.repo), *args], stderr=subprocess.PIPE)

    def context(self, **kwargs):
        return scoped_manifest_context(self.repo, self.matrix_path, **(kwargs or dict(scope='lane', artifact_node=self.node)))

    def write(self, manifest):
        self.manifest_path.write_text(json.dumps(manifest))

    def verify(self, **kwargs):
        return verify_staged(repository=self.repo, matrix_path=self.matrix_path,
                             manifest_path=self.manifest_path, stage=self.stage,
                             **(kwargs or dict(scope='lane', artifact_node=self.node)))

    def source_archives(self):
        lane = self.document.inventory.lane(self.node)
        for kind, relative in (('production', lane.production_jar), ('harness', lane.harness_jar)):
            target = self.repo / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(self.stage / self.manifest['artifacts'][0][kind]['path'], target)
        return lane

    def stage_scoped(self):
        return stage_release(repository=self.repo, matrix_path=self.matrix_path,
                             manifest_path=self.manifest_path, stage=self.stage, scope='lane', artifact_node=self.node)

    def test_schema3_producer_round_trip_and_repository_relative_cli(self):
        from scripts.release.verify_release import main
        self.source_archives()
        self.assertEqual(self.manifest, self.stage_scoped())
        args = ['--repository', str(self.repo), '--artifact-node', self.node]
        self.assertEqual(0, main(args))
        self.assertEqual(0, main(args + ['--verify-staged']))
        with contextlib.redirect_stderr(io.StringIO()):
            self.assertEqual(2, main(['--repository', str(self.repo)]))
            self.assertEqual(2, main(['--repository', str(self.repo), '--scope', 'full']))
        self.assertEqual(self.manifest, self.verify())

    def test_downloaded_bundle_verifies_only_against_its_authenticated_source(self):
        commit = self.git('rev-parse', 'HEAD').decode().strip()
        tree = self.git('rev-parse', 'HEAD^{tree}').decode().strip()
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        # A consumer holding only the commit's inputs and the downloaded bundle:
        # no checkout, no source tree, nothing Git can audit.
        downloaded = Path(temp.name).resolve()
        for relative in ('release/release-matrix.json', 'e2e/scenario-contract.json'):
            (downloaded / relative).parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(self.repo / relative, downloaded / relative)
        shutil.copytree(self.stage, downloaded / 'build/release')

        def verify(source):
            return verify_scoped_staged(
                repository=downloaded, matrix_path=downloaded / 'release/release-matrix.json',
                manifest_path=downloaded / 'build/release/artifacts.json', stage=downloaded / 'build/release',
                scope='lane', artifact_node=self.node, authenticated_source=source)

        self.assertEqual(self.manifest, verify((commit, tree)))
        for source in (('0' * 40, tree), (commit, '0' * 40), ('HEAD', tree), (commit, None)):
            with self.subTest(source=source), self.assertRaises(ArtifactError):
                verify(source)
        # Without one, the checkout it lacks is what fails.
        with self.assertRaises((ArtifactError, MatrixError)):
            verify(None)

    def write_lane_sources(self, document, rows):
        """Write each selected lane's source JARs with its exact build identity."""
        for row in rows:
            lane = document.inventory.lane(row['artifact_node'])
            metadata, loader = lane.artifact['metadata'], lane.identity.loader
            for harness, source in ((False, lane.production_jar), (True, lane.harness_jar)):
                if loader == 'fabric':
                    entries = _fabric_harness_entries() if harness else _fabric_production_entries()
                    record = json.loads(entries['fabric.mod.json'])
                    record['version'] = '0.0.0' if harness else lane.mod_version
                    for dep, key in (('minecraft', 'minecraft'), ('fabricloader', 'loader'),
                                     ('architectury', 'architectury'), ('geckolib', 'geckolib')):
                        if not harness or key in ('minecraft', 'loader'):
                            record['depends'][dep] = metadata[key]
                    entries['fabric.mod.json'] = json.dumps(record).encode()
                else:
                    mod_id = 'blockpops_e2e' if harness else 'blockpops'
                    mod_version = '0.0.0' if harness else lane.mod_version
                    # NeoForge reads the javafml provider version from loaderVersion and
                    # its own bound from the neoforge dependency; Forge reads its own
                    # version from loaderVersion.
                    neoforge = loader == 'neoforge'
                    toml = (f'loaderVersion = "{"[4,)" if neoforge else metadata["loader"]}"\n'
                            f'[[mods]]\nmodId = "{mod_id}"\n'
                            f'version = "{mod_version}"\ndisplayTest = "IGNORE_ALL_VERSION"\n')
                    deps = {'blockpops': '*', 'minecraft': metadata['minecraft']} if harness else {
                        key: metadata[key] for key in ('minecraft', 'architectury', 'geckolib')}
                    if neoforge:
                        deps['neoforge'] = metadata['loader']
                    for dep, version in deps.items():
                        toml += f'[[dependencies.{mod_id}]]\nmodId = "{dep}"\nversionRange = "{version}"\n'
                    if harness:
                        label = 'Forge' if loader == 'forge' else 'NeoForge'
                        entries = {'com/theplumteam/e2e/E2EHarness.class': b'class',
                                   'com/theplumteam/e2e/generated/ScenarioContract.class': b'class',
                                   f'com/theplumteam/e2e/{loader}/BlockPopsE2E{label}.class': b'class',
                                   'pack.mcmeta': b'{}'}
                    else:
                        entries = {'com/theplumteam/BlockPopsMod.class': b'class',
                                   f'com/theplumteam/{loader}/BlockPopsModForge.class': b'class',
                                   'blockpops.mixins.json': b'{}'}
                    entries[metadata['file']] = toml.encode()
                entries[BUILD_IDENTITY_PATH] = json.dumps(row['build_identity']).encode()
                path = self.repo / source
                path.parent.mkdir(parents=True, exist_ok=True)
                _write_zip(path, entries)

    def test_legacy_and_full_producers_stage_exactly_their_selected_loader_pairs(self):
        for scope in ('legacy', 'full'):
            if scope == 'full':
                self.matrix_path.write_text(json.dumps(schema2_configuration(shared=True)))
                self.git('add', 'release/release-matrix.json')
                self.git('-c', 'user.name=Fixture', '-c', 'user.email=fixture@example.invalid', 'commit', '-qm', 'full fixture')
            document, header, rows = self.context(scope=scope)
            self.write_lane_sources(document, rows)
            report = stage_release(repository=self.repo, matrix_path=self.matrix_path,
                                   manifest_path=self.manifest_path, stage=self.stage, scope=scope)
            self.assertEqual(header['scope'], report['scope'])
            self.assertEqual(2 if scope == 'legacy' else TARGET_COUNT, len(report['artifacts']))
            self.assertEqual(report, self.verify(scope=scope))

    def test_invalid_source_invalidates_prior_manifest_without_leaving_success(self):
        lane = self.source_archives()
        (self.repo / lane.harness_jar).write_bytes(b'invalid archive')
        with self.assertRaises(ArtifactError): self.stage_scoped()
        self.assertFalse(self.manifest_path.exists())

    def test_producer_rejects_dirty_inputs_and_linked_output_parent_before_copy(self):
        self.source_archives()
        self.contract.write_text('{"dirty":true}')
        with self.assertRaises(ArtifactError): self.stage_scoped()
        self.assertEqual(self.manifest, json.loads(self.manifest_path.read_bytes()))
        self.contract.write_text('{}')
        self.git('update-index', '--refresh')  # Restore the fixture's clean index stat cache.
        output = self.repo / 'build'
        moved = self.repo / 'moved-output'
        output.rename(moved); output.symlink_to(moved, target_is_directory=True)
        with self.assertRaisesRegex(ArtifactError, 'symlink'): self.stage_scoped()

    def test_hidden_tracked_edits_and_untracked_compile_inputs_deny_source_provenance(self):
        self.source_archives()
        self.git('update-index', '--assume-unchanged', 'e2e/scenario-contract.json')
        self.contract.write_text('{"hidden":true}')
        with self.assertRaisesRegex(ArtifactError, 'exact committed tree'): self.stage_scoped()
        self.contract.write_text('{}')
        self.git('update-index', '--no-assume-unchanged', 'e2e/scenario-contract.json')
        self.git('update-index', '--refresh')
        (self.repo / 'common/src/main/Untracked.java').write_text('class Untracked {}')
        with self.assertRaisesRegex(ArtifactError, 'exact committed tree'): self.stage_scoped()

    def test_stage_parent_swap_cannot_delete_or_write_into_an_unrelated_directory(self):
        self.source_archives()
        from scripts.release import artifact_manifest as module
        foreign = self.repo / 'foreign'
        (foreign / 'files').mkdir(parents=True)
        sentinel = foreign / 'files/keep.txt'; sentinel.write_text('untouched')
        original_verify = module.verify_harness_jar
        def swap(*args, **kwargs):
            original_verify(*args, **kwargs)
            self.stage.rename(self.repo / 'build/owned-stage')
            self.stage.symlink_to(foreign, target_is_directory=True)
        with patch.object(module, 'verify_harness_jar', side_effect=swap), self.assertRaises(ArtifactError):
            self.stage_scoped()
        self.assertEqual('untouched', sentinel.read_text())
        self.assertEqual([sentinel.name], [path.name for path in sentinel.parent.iterdir()])
        self.assertFalse((foreign / 'artifacts.json').exists())

    def test_later_copy_cannot_hide_change_to_an_earlier_source(self):
        lane = self.source_archives()
        from scripts.release import artifact_manifest as module
        real_record = module._file_record
        production = self.repo / lane.production_jar
        harness = self.repo / lane.harness_jar
        harness_reads = 0
        def changed(path, **kwargs):
            nonlocal harness_reads
            value = real_record(path, **kwargs)
            if path == harness:
                harness_reads += 1
                if harness_reads == 2:
                    production.write_bytes(production.read_bytes() + b'changed')
            return value
        with patch.object(module, '_file_record', side_effect=changed), self.assertRaisesRegex(ArtifactError, 'earlier'):
            self.stage_scoped()
        self.assertFalse(self.manifest_path.exists())

    def test_lane_evidence_is_partial_and_legacy_scope_is_independently_derived(self):
        self.assertEqual(self.manifest, self.verify())
        self.assertTrue(self.manifest['scope']['partial'])
        self.assertEqual(TARGET_COUNT, len(self.manifest['scope']['target_nodes']))
        _, legacy, rows = self.context(scope='legacy')
        self.assertEqual(['fabric-1.20.1', 'forge-1.20.1'], legacy['scope']['selected_nodes'])
        self.assertEqual(2, len(rows))
        for scope in (None, 'legacy', 'full'):
            with self.subTest(scope=scope), self.assertRaises((ArtifactError, MatrixError)):
                self.verify(scope=scope)
        with self.assertRaises(ArtifactError):
            self.verify(scope='lane', artifact_node='fabric-1.21.1')

    def test_stale_mixed_duplicate_coercible_and_extra_claims_fail(self):
        mutations = [lambda v: v.update(schema_version=3.0), lambda v: v.update(extra=True),
                     lambda v: v['scope'].update(kind='full', partial=False),
                     lambda v: v['matrix'].update(sha256='0'*64), lambda v: v.update(git_tree='0'*40),
                     lambda v: v['scenario_contract'].update(sha256='0'*64),
                     lambda v: v['artifacts'].append(copy.deepcopy(v['artifacts'][0])),
                     lambda v: v['artifacts'][0].update(java=17.0),
                     lambda v: v['artifacts'][0]['build_identity'].update(artifact_node='fabric-1.21.1'),
                     lambda v: v['artifacts'][0]['harness'].update(path=v['artifacts'][0]['production']['path']),
                     lambda v: v['artifacts'][0]['production'].update(bytes=float(v['artifacts'][0]['production']['bytes']))]
        for mutate in mutations:
            candidate = copy.deepcopy(self.manifest); mutate(candidate); self.write(candidate)
            with self.subTest(mutate=mutate), self.assertRaises(ArtifactError):
                self.verify()

    def test_complete_context_retains_every_target_and_rejects_legacy_scope(self):
        self.matrix_path.write_text(json.dumps(schema2_configuration(shared=True)))
        self.git('add', 'release/release-matrix.json')
        self.git('-c', 'user.name=Fixture', '-c', 'user.email=fixture@example.invalid', 'commit', '-qm', 'shared fixture')
        _, header, rows = self.context(scope='full')
        self.assertFalse(header['scope']['partial'])
        self.assertEqual(TARGET_COUNT, len(rows))
        self.assertEqual(set(header['scope']['target_nodes']), set(header['scope']['selected_nodes']))
        with self.assertRaises(MatrixError): self.context(scope='legacy')

    def test_replacement_objects_cannot_change_protected_source_identity(self):
        head = self.git('rev-parse', 'HEAD').decode().strip()
        empty = subprocess.check_output(['git', '-C', str(self.repo), 'mktree'], input=b'').decode().strip()
        other = self.git('-c', 'user.name=Fixture', '-c', 'user.email=fixture@example.invalid',
                         'commit-tree', empty, '-m', 'replacement').decode().strip()
        self.git('replace', head, other)
        self.assertEqual(self.manifest, self.verify())

    def test_changed_inputs_and_unlisted_stage_objects_fail(self):
        (self.stage / 'unexpected').write_text('extra')
        with self.assertRaises(ArtifactError): self.verify()
        (self.stage / 'unexpected').unlink()
        self.contract.write_text('{"changed":true}')
        with self.assertRaises(ArtifactError): self.verify()

    def test_late_link_manifest_and_inventory_mutations_are_rejected(self):
        production = self.stage / self.manifest['artifacts'][0]['production']['path']
        for mutation in ('hardlink', 'manifest', 'inventory'):
            calls = 0
            def changed(*args, **kwargs):
                nonlocal calls
                context = scoped_manifest_context(*args, **kwargs)
                calls += 1
                if calls == 2:
                    if mutation == 'hardlink': os.link(production, self.repo / 'build/late-link')
                    elif mutation == 'manifest': self.write(dict(self.manifest, extra=True))
                    else: (self.stage / 'late-extra').write_text('extra')
                return context
            with self.subTest(mutation=mutation), patch('scripts.release.artifact_manifest.scoped_manifest_context', side_effect=changed):
                with self.assertRaises(ArtifactError): self.verify()
            (self.repo / 'build/late-link').unlink(missing_ok=True)
            (self.stage / 'late-extra').unlink(missing_ok=True)
            self.write(self.manifest)

    def test_linked_directories_and_hardlinked_archives_fail(self):
        files = self.stage / 'files'
        outside = self.repo / 'build/other'
        files.rename(outside); files.symlink_to(outside, target_is_directory=True)
        with self.assertRaisesRegex(ArtifactError, 'symlink'): self.verify()
        files.unlink(); outside.rename(files)
        artifact = next(files.iterdir())
        os.link(artifact, self.repo / 'build/hardlink')
        with self.assertRaisesRegex(ArtifactError, 'hardlink'): self.verify()


if __name__ == '__main__':
    unittest.main()

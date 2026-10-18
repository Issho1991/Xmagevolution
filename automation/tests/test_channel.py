from pathlib import Path
import copy
import json
import os
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import channel
import pipeline


def run():
    return {'id': 123, 'run_attempt': 1, 'repository': {'full_name': pipeline.TARGET},
            'head_repository': {'full_name': pipeline.TARGET}, 'head_branch': 'main',
            'path': channel.WORKFLOW, 'event': 'schedule', 'status': 'completed',
            'head_sha': 'a' * 40, 'conclusion': 'success'}


class ChannelTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)

    def test_foreign_fork_branch_workflow_or_unfinished_run_rejected(self):
        channel.validate_run(run())
        for name, value in [('repository', {'full_name': 'foreign/repo'}),
                            ('head_repository', {'full_name': 'attacker/fork'}),
                            ('head_branch', 'feature'), ('path', 'other.yml'),
                            ('event', 'pull_request'), ('status', 'in_progress'),
                            ('head_sha', 'bad'), ('id', -1)]:
            bad = run(); bad[name] = value
            with self.subTest(name=name), self.assertRaises(ValueError):
                channel.validate_run(bad)

    def test_stale_completion_cannot_overwrite_newer_status(self):
        self.assertTrue(channel.stale_run({'lastWorkflowRunId': 124}, run()))
        self.assertTrue(channel.stale_run({'lastWorkflowRunId': 123, 'lastWorkflowRunAttempt': 2}, run()))
        self.assertFalse(channel.stale_run({'lastWorkflowRunId': 122}, run()))

    def test_failure_does_not_change_success_date_or_catalog(self):
        current = {'serial': '3', 'release.0.id': 'lab-10.0-ai6', 'release.0.xmage.version': '1.4.62-V1'}
        previous = {'lastSuccessfulCheckAt': '2026-10-01T00:00:00Z', 'lastNotificationKey': 'old-chat-notice'}
        state = channel.state_for(previous, run(), current, 'blocked')
        self.assertEqual(state['lastSuccessfulCheckAt'], previous['lastSuccessfulCheckAt'])
        self.assertEqual(state['catalogSerial'], 3)
        self.assertEqual(state['notificationPolicy'], 'app-only')
        self.assertNotIn('lastNotificationKey', state)
        self.assertEqual(previous['lastNotificationKey'], 'old-chat-notice')

    def test_unrelated_files_cannot_be_published(self):
        with patch.object(channel, 'gh') as gh, self.assertRaises(ValueError):
            channel.commit_files('a' * 40, {'automation/pipeline.py': b'bad'}, 'wrong path')
        gh.assert_not_called()

    def test_concurrent_main_change_stops_before_publication(self):
        with patch.object(channel, 'api', return_value={'object': {'sha': 'b' * 40}}), \
             patch.object(channel, 'gh') as gh, self.assertRaises(ValueError):
            channel.commit_files('a' * 40, {channel.STATE: b'{}'}, 'state')
        gh.assert_not_called()

    def test_single_commit_preserves_tree_and_never_forces_ref(self):
        calls = []
        def mutation(method, path, body):
            calls.append((method, path, body))
            return {'sha': 'b' * 40}
        with patch.object(channel, 'assert_head') as lease, \
             patch.object(channel, 'api', return_value={'tree': {'sha': 'base-tree'}}), \
             patch.object(channel, 'gh', side_effect=mutation):
            channel.commit_files('a' * 40, {channel.STATE: b'{}'}, 'state')
        self.assertEqual(lease.call_count, 2)
        self.assertEqual(calls[1][2]['base_tree'], 'base-tree')
        self.assertEqual(calls[2][2]['parents'], ['a' * 40])
        self.assertEqual(calls[3][2], {'sha': 'b' * 40, 'force': False})

    def test_second_head_change_does_not_move_ref(self):
        calls = []
        def mutation(method, path, body):
            calls.append(path); return {'sha': 'b' * 40}
        with patch.object(channel, 'assert_head', side_effect=[None, ValueError('changed')]), \
             patch.object(channel, 'api', return_value={'tree': {'sha': 'base-tree'}}), \
             patch.object(channel, 'gh', side_effect=mutation), self.assertRaises(ValueError):
            channel.commit_files('a' * 40, {channel.STATE: b'{}'}, 'state')
        self.assertNotIn('/git/refs/heads/main', calls)

    def test_raw_key_and_tokens_not_inherited_by_promoter(self):
        (self.root / 'promotion.json').write_text('{}')
        with patch.dict(os.environ, {'LAB_CATALOG_PRIVATE_KEY': 'TEST-NOT-A-KEY', 'GH_TOKEN': 'TEST-TOKEN'}), \
             patch.object(channel.subprocess, 'run') as child:
            channel.run_promoter('lab-auto-u123', self.root)
        env = child.call_args.kwargs['env']
        self.assertNotIn('LAB_CATALOG_PRIVATE_KEY', env)
        self.assertNotIn('GH_TOKEN', env)
        self.assertNotIn('GITHUB_TOKEN', env)

    def test_private_key_file_restricted_and_removed_even_on_error(self):
        observed = []
        def promoter(tag, work, key):
            observed.append(key)
            self.assertEqual(key.stat().st_mode & 0o777, 0o600)
            self.assertEqual(key.parent.stat().st_mode & 0o777, 0o700)
            raise ValueError('intentional test failure')
        with patch.object(channel, 'run_promoter', side_effect=promoter), self.assertRaises(ValueError):
            channel.sign_candidate('lab-auto-u123', self.root, 'TEST-NOT-A-KEY')
        self.assertFalse(observed[0].exists())

    def test_changed_catalog_cannot_be_promoted(self):
        updates = self.root / 'updates'; updates.mkdir()
        (updates / 'catalog.properties').write_text('current')
        (self.root / 'promotion.json').write_text(json.dumps({'verified': True, 'catalogPrepared': True, 'previousCatalogSha256': '0' * 64}))
        with patch.object(channel, 'REPO', self.root), self.assertRaises(ValueError):
            channel.promotion_files(self.root, {'serial': '3'}, {})

    def test_served_catalog_must_match_exact_signed_result(self):
        with patch.object(channel, 'download'), patch.object(channel, 'signed_payload', return_value={'serial': '2'}), \
             patch.object(channel.time, 'sleep'), self.assertRaises(ValueError):
            channel.verify_served(self.root, {'serial': '3'})

    def test_missing_signing_key_records_limit_without_changing_catalog(self):
        current = {'serial': '3', 'release.0.id': 'lab-10.0-ai6', 'release.0.xmage.version': '1.4.62-V1'}
        spec = {'upstreamReleaseId': 123, 'upstreamTag': 'xmage_1.4.62V1', 'xmageVersion': '1.4.62-V1'}
        with patch.object(sys, 'argv', ['channel.py', '--run-id', '123', '--work', str(self.root), '--apply']), \
             patch.dict(os.environ, {}, clear=True), \
             patch.object(channel.subprocess, 'check_output', return_value='a' * 40), \
             patch.object(channel, 'assert_head'), patch.object(channel, 'read_state', return_value={}), \
             patch.object(channel, 'api', side_effect=[run(), {'tag_name': spec['upstreamTag']}, {'sha': 'b' * 40}]), \
             patch.object(channel, 'release_spec', return_value=spec), \
             patch.object(channel, 'signed_payload', return_value=current), \
             patch.object(channel, 'commit_files', return_value='c' * 40) as commit, \
             patch.object(channel, 'sign_candidate') as sign:
            channel.main()
        files = commit.call_args.args[1]
        self.assertEqual(set(files), {channel.STATE})
        self.assertEqual(json.loads(files[channel.STATE])['status'], 'signing_not_configured')
        sign.assert_not_called()

    def test_promotion_is_one_atomic_set_and_preserves_existing_archive(self):
        updates = self.root / 'updates'; updates.mkdir()
        old = updates / 'catalog.properties'; old.write_bytes(b'previous signed catalog')
        work = self.root / 'work'; work.mkdir()
        (work / 'catalog.properties').write_bytes(b'new signed catalog')
        proof = work / 'candidate.json'; proof.write_bytes(b'verified proof')
        identity = 'lab-10.0-u123-ai6'
        plan = {'verified': True, 'catalogPrepared': True, 'previousCatalogSha256': pipeline.digest(old),
                'serial': 4, 'id': identity, 'packageSha256': 'd' * 64}
        (work / 'promotion.json').write_text(json.dumps(plan))
        current = {'serial': '3', 'release.0.xmage.version': '1.4.62-V1'}
        new = {'serial': '4', 'release.0.id': identity, 'release.0.zip.sha256': 'd' * 64,
               'release.0.xmage.version': '1.4.63-V1', 'release.0.verification.sha256': pipeline.digest(proof)}
        with patch.object(channel, 'REPO', self.root), patch.object(channel, 'signed_payload', return_value=new):
            files, result = channel.promotion_files(work, current, {'lastCheckedAt': '2026-10-18T12:00:00Z'})
            self.assertEqual(set(files), {channel.CATALOG, channel.STATE, 'updates/archive/catalog-3.properties',
                                          'updates/verification-' + identity + '.json'})
            self.assertEqual(files['updates/archive/catalog-3.properties'], old.read_bytes())
            self.assertEqual(json.loads(files[channel.STATE])['catalogSerial'], 4)
            archive = updates / 'archive'; archive.mkdir()
            (archive / 'catalog-3.properties').write_bytes(b'different existing content')
            with self.assertRaises(ValueError):
                channel.promotion_files(work, current, {'lastCheckedAt': '2026-10-18T12:00:00Z'})
        with patch.object(channel, 'download'), patch.object(channel, 'signed_payload', return_value={'serial': '3'}):
            channel.verify_served(self.root, {'serial': '3'})


if __name__ == '__main__':
    unittest.main()

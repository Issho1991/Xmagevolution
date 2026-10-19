from pathlib import Path
import sys
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from publish_launcher import validate_run, validate_advance, version_number, TARGET, WORKFLOW


class LauncherReleaseChecks(unittest.TestCase):
    def setUp(self):
        self.source = 'a' * 40
        self.run = {'repository': {'full_name': TARGET}, 'head_repository': {'full_name': TARGET},
                    'head_branch': 'main', 'head_sha': self.source, 'path': WORKFLOW, 'event': 'push', 'id': 123, 'status': 'in_progress'}
        self.jobs = [{'name': 'build (ubuntu-24.04)', 'conclusion': 'success'}, {'name': 'build (windows-2022)', 'conclusion': 'success'}]

    def test_trusted_main_and_both_platforms(self):
        validate_run(self.run, self.jobs, self.source, 123)

    def test_wrong_branch_workflow_sha_or_event(self):
        for key, value in [('head_branch', 'other'), ('head_sha', 'b' * 40), ('path', 'fake.yml'), ('event', 'pull_request'), ('id', 124)]:
            with self.subTest(key=key), self.assertRaises(ValueError):
                validate_run(dict(self.run, **{key: value}), self.jobs, self.source, 123)

    def test_missing_failed_or_duplicate_platform(self):
        for jobs in [self.jobs[:1], [self.jobs[0], dict(self.jobs[1], conclusion='failure')], self.jobs + [self.jobs[0]]]:
            with self.assertRaises(ValueError):
                validate_run(self.run, jobs, self.source, 123)

    def test_semantic_version_order_and_validation(self):
        self.assertGreater(version_number('0.10.0'), version_number('0.9.99'))
        for v in ('0.04.0', '0.4.0-beta', '-1.0.0', '1000.0.0'):
            with self.assertRaises(ValueError):
                version_number(v)

    def test_replay_immutable_version_and_downgrade(self):
        old = {'product': 'xmage-laboratorio-launcher', 'version': '0.4.0', 'serial': '4000', 'jar.sha256': 'a'}
        validate_advance(None, old)
        validate_advance(old, old)
        with self.assertRaises(ValueError):
            validate_advance(old, dict(old, **{'jar.sha256': 'b'}))
        with self.assertRaises(ValueError):
            validate_advance(old, dict(old, version='0.3.2', serial='3002'))


if __name__ == '__main__':
    unittest.main()

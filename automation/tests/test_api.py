"""Exercise throttling and credential boundaries without making network requests."""
from contextlib import redirect_stdout
from email.message import Message
from pathlib import Path
import hashlib
import io
import os
import sys
import tempfile
import unittest
import urllib.error
import urllib.request
from unittest.mock import call, patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import pipeline

URL = pipeline.ORIGIN + '/releases/latest'


def failure(code=403, headers=None, body=b'{"message":"API rate limit exceeded"}'):
    fields = Message()
    for key, value in (headers or {}).items():
        fields[key] = value
    return urllib.error.HTTPError(URL, code, 'HTTP failure', fields, io.BytesIO(body))


class ApiTests(unittest.TestCase):
    def setUp(self):
        env = patch.dict(os.environ, {}, clear=True)
        env.start()
        self.addCleanup(env.stop)

    def test_token_is_used_for_api_get_only_and_never_forwarded_on_redirect(self):
        for name in ('GH_TOKEN', 'GITHUB_TOKEN'):
            with self.subTest(name=name), patch.dict(os.environ, {name: 'TEST-ONLY-TOKEN'}), \
                    patch.object(pipeline.urllib.request, 'urlopen', return_value=io.BytesIO(b'{"ok":true}')) as opened:
                self.assertEqual(pipeline.api(URL), {'ok': True})
                request = opened.call_args.args[0]
                self.assertEqual(request.get_method(), 'GET')
                self.assertEqual(request.get_header('Authorization'), 'Bearer TEST-ONLY-TOKEN')
                handler = urllib.request.HTTPRedirectHandler()
                for target in ('https://codeload.github.com/magefree/mage/zip/test',
                               'https://api.github.com/repos/magefree/mage/releases/1'):
                    redirected = handler.redirect_request(request, None, 302, 'Found', {}, target)
                    self.assertIsNone(redirected.get_header('Authorization'))

    def test_foreign_api_origins_rejected_before_network_access(self):
        with patch.dict(os.environ, {'GH_TOKEN': 'TEST-ONLY-TOKEN'}), \
                patch.object(pipeline.urllib.request, 'urlopen') as opened:
            for url in ('http://api.github.com/repos/test', 'https://api.github.com.evil.test/',
                        'https://api.github.com@evil.test/', 'https://example.com/',
                        'https://api.github.com:444/'):
                with self.subTest(url=url), self.assertRaises(ValueError):
                    pipeline.api(url)
            opened.assert_not_called()

    def test_manual_public_read_without_token_still_works(self):
        with patch.object(pipeline.urllib.request, 'urlopen', return_value=io.BytesIO(b'{}')) as opened:
            self.assertEqual(pipeline.api(URL), {})
            self.assertIsNone(opened.call_args.args[0].get_header('Authorization'))

    def test_primary_limit_waits_past_reset_even_if_retry_after_is_shorter(self):
        error = failure(headers={'X-RateLimit-Remaining': '0', 'X-RateLimit-Reset': '1030',
                                 'Retry-After': '5'})
        with patch.object(pipeline.time, 'time', return_value=1000), \
                patch.object(pipeline.time, 'sleep') as sleep, \
                patch.object(pipeline.urllib.request, 'urlopen', side_effect=[error, io.BytesIO(b'{"ok":1}')]) as opened:
            self.assertEqual(pipeline.api(URL), {'ok': 1})
            sleep.assert_called_once_with(31)
            self.assertEqual(opened.call_count, 2)

    def test_429_retry_after_seconds_and_http_date_are_respected(self):
        for value, expected in [('7', 7), ('Thu, 01 Jan 1970 00:17:00 GMT', 20)]:
            with self.subTest(value=value), patch.object(pipeline.time, 'time', return_value=1000), \
                    patch.object(pipeline.time, 'sleep') as sleep, \
                    patch.object(pipeline.urllib.request, 'urlopen', side_effect=[
                        failure(429, {'Retry-After': value}, b'busy'), io.BytesIO(b'{}')]):
                self.assertEqual(pipeline.api(URL), {})
                sleep.assert_called_once_with(expected)

    def test_secondary_limit_has_bounded_exponential_backoff_without_exposing_token(self):
        out = io.StringIO()
        with patch.dict(os.environ, {'GH_TOKEN': 'TEST-ONLY-TOKEN'}), redirect_stdout(out), \
                patch.object(pipeline.time, 'sleep') as sleep, \
                patch.object(pipeline.urllib.request, 'urlopen', side_effect=[failure(), failure(), failure()]) as opened:
            with self.assertRaisesRegex(RuntimeError, 'rate limit.*retry budget'):
                pipeline.api(URL)
            self.assertEqual(sleep.call_args_list, [call(60), call(120)])
            self.assertEqual(opened.call_count, 3)
        self.assertNotIn('TEST-ONLY-TOKEN', out.getvalue())

    def test_long_reset_is_not_shortened_to_retry_before_github_allows_it(self):
        with patch.object(pipeline.time, 'time', return_value=1000), \
                patch.object(pipeline.time, 'sleep') as sleep, \
                patch.object(pipeline.urllib.request, 'urlopen', side_effect=failure(headers={
                    'X-RateLimit-Remaining': '0', 'X-RateLimit-Reset': '4600'})) as opened:
            with self.assertRaisesRegex(RuntimeError, 'rate limit'):
                pipeline.api(URL)
            sleep.assert_not_called()
            self.assertEqual(opened.call_count, 1)

    def test_malformed_rate_limit_headers_use_conservative_wait(self):
        with patch.object(pipeline.time, 'sleep') as sleep, \
                patch.object(pipeline.urllib.request, 'urlopen', side_effect=[failure(429, {
                    'Retry-After': 'invalid', 'X-RateLimit-Remaining': '0',
                    'X-RateLimit-Reset': 'invalid'}), io.BytesIO(b'{}')]):
            pipeline.api(URL)
            sleep.assert_called_once_with(60)

    def test_authentication_permission_and_not_found_errors_are_not_retried(self):
        for code in (401, 403, 404):
            error = failure(code, body=b'{"message":"Resource not accessible by integration"}')
            with self.subTest(code=code), patch.object(pipeline.time, 'sleep') as sleep, \
                    patch.object(pipeline.urllib.request, 'urlopen', side_effect=error) as opened:
                with self.assertRaises(urllib.error.HTTPError) as raised:
                    pipeline.api(URL)
                self.assertIs(raised.exception, error)
                sleep.assert_not_called()
                self.assertEqual(opened.call_count, 1)

    def test_source_archive_uses_authenticated_retry_and_verifies_download(self):
        data = b'archive fixture'
        with tempfile.TemporaryDirectory() as temp, patch.dict(os.environ, {'GH_TOKEN': 'TEST-ONLY-TOKEN'}), \
                patch.object(pipeline.time, 'sleep') as sleep, \
                patch.object(pipeline.urllib.request, 'urlopen', side_effect=[
                    failure(429, {'Retry-After': '1'}), io.BytesIO(data)]) as opened:
            target = Path(temp) / 'source.zip'
            pipeline.download(pipeline.ORIGIN + '/zipball/' + 'a' * 40, target,
                              hashlib.sha256(data).hexdigest())
            self.assertEqual(target.read_bytes(), data)
            self.assertEqual(opened.call_args.args[0].get_header('Authorization'), 'Bearer TEST-ONLY-TOKEN')
            sleep.assert_called_once_with(1)
            self.assertFalse(target.with_suffix('.zip.partial').exists())

    def test_other_downloads_never_receive_token_and_hash_failure_preserves_existing_file(self):
        with tempfile.TemporaryDirectory() as temp, patch.dict(os.environ, {'GH_TOKEN': 'TEST-ONLY-TOKEN'}), \
                patch.object(pipeline.urllib.request, 'urlopen', return_value=io.BytesIO(b'tampered')) as opened:
            target = Path(temp) / 'package.zip'
            target.write_bytes(b'existing')
            with self.assertRaisesRegex(ValueError, 'SHA-256 mismatch'):
                pipeline.download(pipeline.BASELINE_URL, target, 'a' * 64)
            self.assertIsNone(opened.call_args.args[0].get_header('Authorization'))
            self.assertEqual(target.read_bytes(), b'existing')
            self.assertFalse(target.with_suffix('.zip.partial').exists())


if __name__ == '__main__':
    unittest.main()

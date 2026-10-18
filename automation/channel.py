"""Publish a verified channel after a completed, trusted integration workflow.

The upstream build never receives the key. This separate workflow downloads data
only, repeats promote.py's validation, and advances main without force. Service
status is informational: it never authorizes a launcher installation.
"""
from pathlib import Path
from datetime import datetime, timezone
import argparse
import base64
import json
import os
import re
import subprocess
import sys
import tempfile
import time
import urllib.error

from pipeline import REPO, HERE, TARGET, ORIGIN, api, digest, download
from pipeline import signed_payload, release_spec, version_tuple

API = 'https://api.github.com/repos/' + TARGET
STATE = 'updates/automation-state.json'
CATALOG = 'updates/catalog.properties'
WORKFLOW = '.github/workflows/automatic-upstream.yml'
CATALOG_URL = 'https://raw.githubusercontent.com/' + TARGET + '/main/' + CATALOG


def utc_now():
    return datetime.now(timezone.utc).isoformat(timespec='seconds').replace('+00:00', 'Z')


def validate_run(run):
    if (run.get('repository', {}).get('full_name') != TARGET
            or run.get('head_repository', {}).get('full_name') != TARGET
            or run.get('head_branch') != 'main' or run.get('path') != WORKFLOW
            or run.get('event') not in ('schedule', 'push', 'workflow_dispatch')
            or run.get('status') != 'completed'
            or not re.fullmatch('[a-f0-9]{40}', run.get('head_sha', ''))
            or type(run.get('id')) is not int or run['id'] <= 0):
        raise ValueError('Untrusted or incomplete integration run')


def assert_head(expected):
    if api(API + '/git/ref/heads/main')['object']['sha'] != expected:
        raise ValueError('main changed; retry from a fresh checkout, never force')


def gh(method, path, body):
    result = subprocess.run(
        ['gh', 'api', '--method', method, 'repos/' + TARGET + path, '--input', '-'],
        input=json.dumps(body), text=True, capture_output=True, check=True)
    return json.loads(result.stdout)


def commit_files(expected, files, message):
    """Only the exact catalog/status/proof paths may be written in one commit."""
    for path in files:
        if (path not in (STATE, CATALOG)
                and not re.fullmatch(r'updates/archive/catalog-[1-9][0-9]*\.properties', path)
                and not re.fullmatch(r'updates/verification-lab-10\.0-u[1-9][0-9]*-ai6\.json', path)):
            raise ValueError('Unexpected publication path')
    assert_head(expected)
    parent = api(API + '/git/commits/' + expected)
    entries = []
    for path, content in files.items():
        blob = gh('POST', '/git/blobs', {'content': base64.b64encode(content).decode(), 'encoding': 'base64'})
        entries.append({'path': path, 'mode': '100644', 'type': 'blob', 'sha': blob['sha']})
    tree = gh('POST', '/git/trees', {'base_tree': parent['tree']['sha'], 'tree': entries})
    commit = gh('POST', '/git/commits', {'message': message, 'tree': tree['sha'], 'parents': [expected]})
    assert_head(expected)
    # A concurrent unrelated commit causes the server's fast-forward check to
    # reject this update, even if it lands after the preceding read.
    gh('PATCH', '/git/refs/heads/main', {'sha': commit['sha'], 'force': False})
    return commit['sha']


def read_state():
    path = REPO / STATE
    return json.loads(path.read_text()) if path.exists() else {}


def stale_run(state, run):
    previous = (int(state.get('lastWorkflowRunId', 0)), int(state.get('lastWorkflowRunAttempt', 0)))
    incoming = (run['id'], int(run.get('run_attempt', 1)))
    return incoming < previous


def state_for(previous, run, current, status, spec=None):
    state = dict(previous)
    state.update(schema=2, mode='github-autonomous', status=status,
                 notificationPolicy='app-only', checkIntervalHours=6,
                 lastCheckedAt=utc_now(), lastWorkflowRunId=run['id'],
                 lastWorkflowRunAttempt=int(run.get('run_attempt', 1)),
                 workflowUrl='https://github.com/' + TARGET + '/actions/runs/' + str(run['id']),
                 catalogSerial=int(current['serial']), labId=current['release.0.id'],
                 xmageVersion=current['release.0.xmage.version'], pendingCandidate=None)
    if spec:
        state.update(upstreamReleaseId=spec['upstreamReleaseId'], upstreamTag=spec['upstreamTag'])
        if version_tuple(spec['xmageVersion']) > version_tuple(current['release.0.xmage.version']):
            state['pendingCandidate'] = 'lab-auto-u' + str(spec['upstreamReleaseId'])
    if status in ('current', 'published'):
        state['lastSuccessfulCheckAt'] = state['lastCheckedAt']
    # Remove the old ChatGPT activation/notification state; keep useful evidence.
    state.pop('lastNotificationKey', None)
    return state


def run_promoter(tag, work, key=None):
    command = [sys.executable, str(HERE / 'promote.py'), '--tag', tag, '--work', str(work)]
    if key is not None:
        command += ['--key', str(key)]
    # Child processes do not inherit the raw secret or a write token.
    env = {k: v for k, v in os.environ.items()
           if k not in ('LAB_CATALOG_PRIVATE_KEY', 'GH_TOKEN', 'GITHUB_TOKEN')}
    subprocess.run(command, env=env, check=True)
    return json.loads((work / 'promotion.json').read_text())


def sign_candidate(tag, work, secret):
    with tempfile.TemporaryDirectory(prefix='lab-sign-') as folder:
        os.chmod(folder, 0o700)
        key = Path(folder) / 'catalog-key.pem'
        fd = os.open(key, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(fd, 'w') as handle:
            handle.write(secret.rstrip() + '\n')
        return run_promoter(tag, work, key)


def promotion_files(work, current, state):
    plan = json.loads((work / 'promotion.json').read_text())
    old = REPO / CATALOG
    if (plan.get('verified') is not True or plan.get('catalogPrepared') is not True
            or plan['previousCatalogSha256'] != digest(old)):
        raise ValueError('Catalog changed or promotion is incomplete')
    new = signed_payload(work / 'catalog.properties')
    if (int(new['serial']) != int(current['serial']) + 1
            or int(new['serial']) != plan['serial'] or new['release.0.id'] != plan['id']
            or new['release.0.zip.sha256'] != plan['packageSha256']
            or version_tuple(new['release.0.xmage.version']) <= version_tuple(current['release.0.xmage.version'])
            or new['release.0.verification.sha256'] != digest(work / 'candidate.json')):
        raise ValueError('Prepared catalog does not match verified promotion')
    archive = 'updates/archive/catalog-' + current['serial'] + '.properties'
    proof_path = 'updates/verification-' + plan['id'] + '.json'
    files = {CATALOG: (work / 'catalog.properties').read_bytes()}
    for name, content in ((archive, old.read_bytes()), (proof_path, (work / 'candidate.json').read_bytes())):
        path = REPO / name
        if path.exists() and path.read_bytes() != content:
            raise ValueError('Existing archive or proof must never be overwritten')
        if not path.exists():
            files[name] = content
    state.update(status='published', catalogSerial=int(new['serial']), labId=plan['id'],
                 xmageVersion=new['release.0.xmage.version'], pendingCandidate=None,
                 lastSuccessfulCheckAt=state['lastCheckedAt'])
    files[STATE] = (json.dumps(state, indent=2) + '\n').encode()
    return files, new


def verify_served(work, expected):
    # raw.githubusercontent.com can briefly serve the previous cached value.
    for attempt in range(6):
        try:
            path = work / 'served-catalog.properties'
            path.unlink(missing_ok=True)
            download(CATALOG_URL + '?serial=' + expected['serial'], path)
            actual = signed_payload(path)
            if actual == expected:
                return
        except (OSError, ValueError, subprocess.SubprocessError):
            pass
        if attempt < 5:
            time.sleep(5)
    raise ValueError('Published catalog not yet verified at the launcher URL')


def main():
    p = argparse.ArgumentParser()
    p.add_argument('--run-id', type=int, required=True)
    p.add_argument('--work', type=Path, required=True)
    mode = p.add_mutually_exclusive_group()
    mode.add_argument('--apply', action='store_true')
    mode.add_argument('--record-error', action='store_true')
    args = p.parse_args()
    # Remove from the environment before starting any child process.
    secret = os.environ.pop('LAB_CATALOG_PRIVATE_KEY', '')
    if args.run_id <= 0:
        raise ValueError('Invalid workflow id')
    expected_head = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=REPO, text=True).strip()
    assert_head(expected_head)
    run = api(API + '/actions/runs/' + str(args.run_id))
    validate_run(run)
    current = signed_payload(REPO / CATALOG)
    previous = read_state()
    if stale_run(previous, run):
        print('A newer run has already been recorded; no changes.')
        return
    spec = None
    status = 'publication_failed' if args.record_error else 'blocked'
    if not args.record_error:
        official = api(ORIGIN + '/releases/latest')
        tag = official['tag_name']
        if not re.fullmatch(r'xmage_\d+\.\d+\.\d+V\d+', tag):
            raise ValueError('Unexpected official release tag')
        spec = release_spec(official, api(ORIGIN + '/commits/' + tag)['sha'])
        if run.get('conclusion') == 'success':
            status = ('ready' if version_tuple(spec['xmageVersion']) >
                      version_tuple(current['release.0.xmage.version']) else 'current')
    args.work.mkdir(parents=True, exist_ok=True)
    if status == 'ready':
        candidate = 'lab-auto-u' + str(spec['upstreamReleaseId'])
        run_promoter(candidate, args.work)  # Always validate before accessing the key.
        if args.apply:
            if not secret:
                status = 'signing_not_configured'
            else:
                sign_candidate(candidate, args.work, secret)
                status = 'published'
    state = state_for(previous, run, current, status, spec)
    if not (args.apply or args.record_error):
        (args.work / 'inspection.json').write_text(json.dumps(state, indent=2) + '\n')
        print(json.dumps({'status': status, 'signed': False, 'published': False}))
        return
    if status == 'current' and not secret:
        # Current releases remain usable, but do not misrepresent future signing.
        state['status'] = 'signing_not_configured'
    files = {STATE: (json.dumps(state, indent=2) + '\n').encode()}
    signed = None
    if status == 'published':
        files, signed = promotion_files(args.work, current, state)
    elif secret and status == 'current':
        # Verify configured key even before the next upstream release appears.
        with tempfile.TemporaryDirectory(prefix='lab-key-check-') as folder:
            key = Path(folder) / 'key.pem'
            fd = os.open(key, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
            with os.fdopen(fd, 'w') as handle:
                handle.write(secret.rstrip() + '\n')
            public = subprocess.check_output(['openssl', 'pkey', '-in', str(key), '-pubout', '-outform', 'DER'], stderr=subprocess.DEVNULL)
            if public != base64.b64decode((HERE / 'launcher/resources/lab-updates-public.der.b64').read_text()):
                raise ValueError('Configured signing key does not match the launcher')
    sha = commit_files(expected_head, files, 'Record Laboratorio service check' if signed is None else 'Publish verified Laboratorio update ' + state['labId'])
    if signed is not None:
        verify_served(args.work, signed)
    print(json.dumps({'status': state['status'], 'commit': sha, 'catalogChanged': signed is not None}))


if __name__ == '__main__':
    main()

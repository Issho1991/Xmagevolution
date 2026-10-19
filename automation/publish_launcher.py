"""Publish a tested launcher and its separate signed channel, never game data.

Run only in release-launcher.yml after successful Linux and Windows jobs.
Published assets are immutable; every byte is downloaded and rechecked before
signing. The main reference is advanced without force from a fresh head.
"""
from pathlib import Path
import argparse
import base64
import json
import os
import re
import subprocess
import tempfile
import time
import urllib.error
import zipfile

from pipeline import api, digest, download, signed_payload, TARGET, HERE
from channel import gh, assert_head

API = 'https://api.github.com/repos/' + TARGET
CHANNEL = 'updates/launcher.properties'
URL = 'https://raw.githubusercontent.com/' + TARGET + '/main/' + CHANNEL
WORKFLOW = '.github/workflows/release-launcher.yml'


def version_number(value):
    if not re.fullmatch(r'(0|[1-9][0-9]{0,2})\.(0|[1-9][0-9]{0,2})\.(0|[1-9][0-9]{0,2})', value):
        raise ValueError('Invalid launcher version')
    a, b, c = map(int, value.split('.'))
    return a * 1000000 + b * 1000 + c


def validate_package(folder):
    proof = json.loads((folder / 'launcher-verification.json').read_text())
    version_number(proof['version'])
    jar = folder / 'laboratorio-launcher.jar'
    package = folder / ('Launcher_' + proof['version'].replace('.', '_') + '_per_XMage_Lab.zip')
    if (jar.stat().st_size != proof['jarBytes'] or not 0 < proof['jarBytes'] <= 16 * 1024 * 1024
            or digest(jar) != proof['jarSha256'] or package.stat().st_size != proof['bytes']
            or digest(package) != proof['sha256']):
        raise ValueError('Launcher package does not match build evidence')
    public = base64.b64decode((HERE / 'launcher/resources/lab-updates-public.der.b64').read_bytes())
    with zipfile.ZipFile(jar) as z:
        manifest = z.read('META-INF/MANIFEST.MF').decode()
        if (z.read('lab-updates-public.der') != public
                or 'Implementation-Version: ' + proof['version'] + '\r\n' not in manifest
                or 'Main-Class: mage.lab.launcher.LaboratorioLauncher\r\n' not in manifest):
            raise ValueError('Launcher identity or signing key mismatch')
    with zipfile.ZipFile(package) as z:
        if (set(z.namelist()) != {'launcher/laboratorio-launcher.jar', 'LEGGIMI_LAUNCHER_' + proof['version'].replace('.', '_') + '.txt'}
                or len(z.namelist()) != 2 or z.read('launcher/laboratorio-launcher.jar') != jar.read_bytes()):
            raise ValueError('Installation ZIP must contain only the verified launcher and instructions')
    return proof, jar, package


def validate_run(run, jobs, source, run_id):
    if (run.get('repository', {}).get('full_name') != TARGET
            or run.get('head_repository', {}).get('full_name') != TARGET
            or run.get('head_branch') != 'main' or run.get('head_sha') != source
            or run.get('path') != WORKFLOW or run.get('event') not in ('push', 'workflow_dispatch')
            or run.get('id') != run_id or run.get('status') not in ('in_progress', 'completed')):
        raise ValueError('Untrusted launcher workflow')
    for name in ('build (ubuntu-24.04)', 'build (windows-2022)'):
        matching = [j for j in jobs if j.get('name') == name]
        if len(matching) != 1 or matching[0].get('conclusion') != 'success':
            raise ValueError('Missing successful platform checks: ' + name)


def remote_file(path, head):
    try:
        obj = api(API + '/contents/' + path + '?ref=' + head)
    except urllib.error.HTTPError as e:
        if e.code == 404:
            return None
        raise
    return base64.b64decode(obj['content'])


def payload(proof, source):
    version = proof['version']
    return {'product': 'xmage-laboratorio-launcher', 'serial': str(version_number(version)), 'version': version,
            'jar.sha256': proof['jarSha256'], 'jar.size': str(proof['jarBytes']), 'source.commit': source,
            'url': 'https://github.com/' + TARGET + '/releases/download/launcher-v' + version + '/laboratorio-launcher.jar'}


def validate_advance(old, new):
    if old is None:
        return
    if old.get('product') != 'xmage-laboratorio-launcher' or int(old['serial']) != version_number(old['version']):
        raise ValueError('Invalid previous launcher catalog')
    if int(old['serial']) > int(new['serial']):
        raise ValueError('Launcher downgrade refused')
    if int(old['serial']) == int(new['serial']) and old != new:
        raise ValueError('Published launcher version is immutable; increase its version')


def sign(data, secret, folder):
    key = folder / 'private.pem'
    fd = os.open(key, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    try:
        with os.fdopen(fd, 'w') as f:
            f.write(secret.rstrip() + '\n')
        public = subprocess.check_output(['openssl', 'pkey', '-in', str(key), '-pubout', '-outform', 'DER'], stderr=subprocess.DEVNULL)
        if public != base64.b64decode((HERE / 'launcher/resources/lab-updates-public.der.b64').read_bytes()):
            raise ValueError('The configured key does not match the launcher')
        content = ''.join(k + '=' + v + '\n' for k, v in sorted(data.items())).encode()
        source = folder / 'payload'; source.write_bytes(content)
        signature = subprocess.check_output(['openssl', 'dgst', '-sha256', '-sign', str(key), str(source)], stderr=subprocess.DEVNULL)
        return ('catalog.schema=1\npayload=' + base64.b64encode(content).decode() + '\nsignature=' + base64.b64encode(signature).decode() + '\n').encode()
    finally:
        key.unlink(missing_ok=True)


def fresh_head(source):
    head = api(API + '/git/ref/heads/main')['object']['sha']
    if head != source:
        comparison = api(API + '/compare/' + source + '...' + head)
        if comparison.get('status') not in ('ahead', 'identical') or any(not f['filename'].startswith('updates/') for f in comparison.get('files', [])):
            raise ValueError('Launcher source changed on main; build the new head')
    return head


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--folder', type=Path, default=Path('launcher-artifact'))
    args = parser.parse_args()
    # Never pass the raw secret to subprocesses or build/test code.
    secret = os.environ.pop('LAB_CATALOG_PRIVATE_KEY', '')
    if not secret.strip():
        raise ValueError('LAB_CATALOG_PRIVATE_KEY is missing from lab-release')
    source = os.environ['GITHUB_SHA']; run_id = int(os.environ['GITHUB_RUN_ID'])
    if not re.fullmatch('[a-f0-9]{40}', source):
        raise ValueError('Invalid source commit')
    run = api(API + '/actions/runs/' + str(run_id))
    jobs = api(API + '/actions/runs/' + str(run_id) + '/jobs?per_page=100')['jobs']
    validate_run(run, jobs, source, run_id)
    proof, jar, package = validate_package(args.folder)
    new = payload(proof, source); head = fresh_head(source)
    previous = remote_file(CHANNEL, head)
    with tempfile.TemporaryDirectory(prefix='launcher-publish-') as temp:
        folder = Path(temp);os.chmod(folder, 0o700)
        old = None
        if previous is not None:
            (folder / 'previous.properties').write_bytes(previous)
            old = signed_payload(folder / 'previous.properties')
        validate_advance(old, new)
        # Establish key correspondence before creating a release.
        signed = sign(new, secret, folder)
        secret = ''
        tag = 'launcher-v' + proof['version']
        try:
            release = api(API + '/releases/tags/' + tag)
        except urllib.error.HTTPError as e:
            if e.code != 404:
                raise
            release = None
        if release is None:
            notes = folder / 'notes.md'
            notes.write_text('Launcher ' + proof['version'] + ': interfaccia semplificata, controllo all’apertura e aggiornamento del launcher dall’app con firma, verifica e copia precedente.\n\nPer il primo passaggio dal launcher 0.3.x, chiudere client, server e launcher; estrarre lo ZIP nella cartella contenente Avvia_Laboratorio.cmd. Non occorre reinstallare il gioco.\n')
            subprocess.run(['gh', 'release', 'create', tag, str(jar), str(package), str(args.folder / 'launcher-verification.json'), *map(str, sorted(args.folder.glob('launcher-*.png'))),
                            '--target', source, '--latest=false', '--title', 'XMage Laboratorio — Launcher ' + proof['version'], '--notes-file', str(notes)], check=True)
            release = api(API + '/releases/tags/' + tag)
        if release.get('draft') or release.get('prerelease'):
            raise ValueError('Launcher release must be final and published')
        if api(API + '/git/ref/tags/' + tag)['object']['sha'] != source:
            raise ValueError('Release tag does not point to the tested source')
        for file in (jar, package):
            assets = [a for a in release['assets'] if a['name'] == file.name]
            if len(assets) != 1 or assets[0]['size'] != file.stat().st_size or assets[0].get('digest') != 'sha256:' + digest(file):
                raise ValueError('Existing release asset differs; never overwrite')
            expected_url = 'https://github.com/' + TARGET + '/releases/download/' + tag + '/' + file.name
            if assets[0]['browser_download_url'] != expected_url:
                raise ValueError('Unexpected release asset URL')
            download(expected_url, folder / file.name, digest(file))
        if old != new:
            # Re-read the head and catalog after publishing immutable artifacts.
            head = fresh_head(source)
            if remote_file(CHANNEL, head) != previous:
                raise ValueError('Launcher catalog changed; rerun from the current state')
            files = {CHANNEL: signed}
            if old:
                archive = 'updates/archive/launcher-' + old['serial'] + '.properties'
                existing = remote_file(archive, head)
                if existing is not None and existing != previous:
                    raise ValueError('Existing launcher archive differs')
                if existing is None:
                    files[archive] = previous
            parent = api(API + '/git/commits/' + head)
            entries = []
            for path, content in files.items():
                blob = gh('POST', '/git/blobs', {'content': base64.b64encode(content).decode(), 'encoding': 'base64'})
                entries.append({'path': path, 'mode': '100644', 'type': 'blob', 'sha': blob['sha']})
            tree = gh('POST', '/git/trees', {'base_tree': parent['tree']['sha'], 'tree': entries})
            commit = gh('POST', '/git/commits', {'message': 'Publish signed launcher ' + proof['version'], 'tree': tree['sha'], 'parents': [head]})
            assert_head(head)
            gh('PATCH', '/git/refs/heads/main', {'sha': commit['sha'], 'force': False})
        for attempt in range(6):
            download(URL + '?version=' + proof['version'], folder / 'served.properties')
            if signed_payload(folder / 'served.properties') == new:
                print('Launcher ' + proof['version'] + ' published; signed channel and downloaded assets verified.')
                return
            time.sleep(5)
        raise ValueError('Published launcher channel not yet visible; do not report success')


if __name__ == '__main__':
    main()

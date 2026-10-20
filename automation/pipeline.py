"""Integrate a stable official release, fail closed, and prepare a verified candidate.

No signing key, repository write credential, or upstream build script is used here.
"""
from pathlib import Path, PurePosixPath
import argparse, base64, hashlib, json, os, re, shutil, stat, subprocess, sys
import tempfile, time, urllib.error, urllib.parse, urllib.request, zipfile, xml.etree.ElementTree as ET
from email.utils import parsedate_to_datetime
from math import ceil

HERE = Path(__file__).resolve().parent
REPO = HERE.parent
BASE = 'eb69d05b88142b5c4eec4869bcb23a0fec13c091'
ORIGIN = 'https://api.github.com/repos/magefree/mage'
TARGET = 'Issho1991/Xmagevolution'
OVERLAY = REPO / 'releases/lab-10.0-ai6/overlay'
MAX_BYTES = 1024 * 1024 * 1024
BASELINE_URL = 'https://github.com/Issho1991/Xmagevolution/releases/download/lab-10.0-ai6/Pacchetto_Lab_10_0_per_Launcher.zip'
BASELINE_HASH = '4c380e8ac2819c0a3bc38cf034c214afc94ea4e11acb3e6490617f034007c8f9'
JUNIT_URL = 'https://repo.maven.apache.org/maven2/org/junit/platform/junit-platform-console-standalone/1.10.5/junit-platform-console-standalone-1.10.5.jar'
JUNIT_HASH = 'defd77f4a1cc960c05ec8eb3edce7094bf043b496819a808e882935d889c085b'
API_ATTEMPTS = 3
API_WAIT_BUDGET = 180

def digest(path):
    with Path(path).open('rb') as f:
        return hashlib.file_digest(f, 'sha256').hexdigest()

def is_github_api(url):
    parsed = urllib.parse.urlsplit(url)
    return parsed.scheme == 'https' and parsed.netloc == 'api.github.com'


def rate_limit_delay(error, attempt):
    """Return a safe wait for throttling, not for permission/authentication errors."""
    if error.code not in (403, 429):
        return None
    headers = error.headers
    retry_after = headers.get('Retry-After')
    remaining = headers.get('X-RateLimit-Remaining')
    message = error.read(8192).decode('utf-8', errors='replace').lower()
    if (error.code != 429 and remaining != '0' and retry_after is None
            and 'rate limit' not in message and 'rate limit' not in str(error.reason).lower()):
        return None
    delays = []
    if retry_after is not None:
        try:
            delays.append(max(1, int(retry_after)))
        except ValueError:
            try:
                delays.append(max(1, ceil(parsedate_to_datetime(retry_after).timestamp() - time.time())))
            except (TypeError, ValueError, OverflowError):
                pass
    if remaining == '0':
        try:
            delays.append(max(1, ceil(int(headers['X-RateLimit-Reset']) - time.time()) + 1))
        except (KeyError, TypeError, ValueError, OverflowError):
            pass
    return max(delays) if delays else 60 * (2 ** attempt)


def github_response(url, accept='application/vnd.github+json'):
    """Authenticated GETs only; never forward credentials to download redirects."""
    if not is_github_api(url):
        raise ValueError('Expected the HTTPS GitHub API origin')
    headers = {'User-Agent': 'XMage-Laboratorio-Automation',
               'Accept': accept, 'X-GitHub-Api-Version': '2022-11-28'}
    token = os.environ.get('GH_TOKEN') or os.environ.get('GITHUB_TOKEN')
    waited = 0
    for attempt in range(API_ATTEMPTS):
        request = urllib.request.Request(url, headers=headers)
        if token:
            # urllib excludes unredirected headers when constructing ANY redirect.
            request.add_unredirected_header('Authorization', 'Bearer ' + token)
        try:
            return urllib.request.urlopen(request, timeout=90)
        except urllib.error.HTTPError as error:
            delay = rate_limit_delay(error, attempt)
            if delay is None:
                raise
            error.close()
            if attempt + 1 == API_ATTEMPTS or waited + delay > API_WAIT_BUDGET:
                raise RuntimeError('GitHub API rate limit: retry budget exceeded; '
                                   'retry after the reset or at the next scheduled check.') from None
            print('GitHub API rate limit; waiting %d seconds before retry %d/%d.'
                  % (delay, attempt + 2, API_ATTEMPTS), flush=True)
            time.sleep(delay)
            waited += delay


def api(url):
    with github_response(url) as response:
        return json.loads(response.read(4 * 1024 * 1024))

def download(url, path, expected=None):
    path = Path(path)
    if path.exists() and expected and digest(path) == expected:
        return path
    tmp = path.with_suffix(path.suffix + '.partial')
    try:
        request = urllib.request.Request(url, headers={'User-Agent': 'XMage-Laboratorio-Automation'})
        response = (github_response(url, 'application/octet-stream') if is_github_api(url)
                    else urllib.request.urlopen(request, timeout=90))
        with response, tmp.open('wb') as out:
            size = 0
            while chunk := response.read(1024 * 1024):
                size += len(chunk)
                if size > MAX_BYTES:
                    raise ValueError('Archive exceeds download limit')
                out.write(chunk)
        if expected and digest(tmp) != expected:
            raise ValueError('Download SHA-256 mismatch')
        tmp.replace(path)
    finally:
        tmp.unlink(missing_ok=True)
    return path

def validate_zip(path):
    with zipfile.ZipFile(path) as archive:
        seen = set(); total = 0
        for item in archive.infolist():
            name = item.filename
            parts = PurePosixPath(name).parts
            if (not parts or name.startswith('/') or '\\' in name or ':' in name
                    or '..' in parts or any(ord(c) < 32 for c in name)
                    or stat.S_ISLNK(item.external_attr >> 16)):
                raise ValueError('Unsafe ZIP path: ' + repr(name))
            if name.casefold() in seen:
                raise ValueError('Duplicate ZIP path')
            seen.add(name.casefold()); total += item.file_size
            if total > 4 * MAX_BYTES:
                raise ValueError('Expanded archive too large')

def signed_payload(path):
    envelope = properties(Path(path).read_bytes())
    if envelope.get('catalog.schema') != '1':
        raise ValueError('Unsupported catalog')
    payload = base64.b64decode(envelope['payload'], validate=True)
    signature = base64.b64decode(envelope['signature'], validate=True)
    with tempfile.TemporaryDirectory() as temp:
        d = Path(temp)
        (d/'public.der').write_bytes(base64.b64decode((HERE/'launcher/resources/lab-updates-public.der.b64').read_text()))
        (d/'payload').write_bytes(payload); (d/'signature').write_bytes(signature)
        subprocess.run(['openssl','pkey','-pubin','-inform','DER','-in',str(d/'public.der'),'-out',str(d/'public.pem')],check=True,capture_output=True)
        subprocess.run(['openssl','dgst','-sha256','-verify',str(d/'public.pem'),'-signature',str(d/'signature'),str(d/'payload')],check=True,capture_output=True)
    return properties(payload)

def properties(data):
    return dict(line.split('=', 1) for line in data.decode('utf-8').splitlines()
                if line and not line.startswith(('#', '!')) and '=' in line)

def version_tuple(value):
    m = re.fullmatch(r'(\d+)\.(\d+)\.(\d+)-V(\d+)', value)
    if not m:
        raise ValueError('Unrecognized stable version: ' + value)
    return tuple(map(int, m.groups()))

def release_spec(release, commit):
    tag = release['tag_name']
    match = re.fullmatch(r'xmage_(\d+\.\d+\.\d+)V(\d+)', tag)
    if release.get('draft') or release.get('prerelease') or not match:
        raise ValueError('Only stable official XMage releases are supported')
    if not re.fullmatch('[a-f0-9]{40}', commit):
        raise ValueError('Invalid upstream commit')
    version, revision = match.groups()
    assets = [a for a in release['assets'] if re.fullmatch('mage-full_' + re.escape(version) + r'-dev_\d{4}-\d{2}-\d{2}_\d{2}-\d{2}\.zip', a['name'])]
    if len(assets) != 1:
        raise ValueError('Expected exactly one full official runtime')
    asset = assets[0]
    sha = asset.get('digest') or ''
    if not re.fullmatch(r'sha256:[a-f0-9]{64}', sha):
        raise ValueError('Missing official SHA-256; manual investigation required')
    url = 'https://github.com/magefree/mage/releases/download/' + tag + '/' + asset['name']
    if asset['browser_download_url'] != url or not 0 < asset['size'] < MAX_BYTES:
        raise ValueError('Unexpected official asset URL or size')
    rid = release['id']
    if type(rid) is not int or rid <= 0:
        raise ValueError('Invalid release ID')
    return {'upstreamReleaseId': rid, 'upstreamTag': tag, 'upstreamCommit': commit,
            'officialUrl': url, 'officialSha256': sha[7:], 'officialBytes': asset['size'],
            'jarVersion': version, 'xmageVersion': version + '-V' + revision,
            'labVersion': '10.0.u' + str(rid), 'id': 'lab-10.0-u' + str(rid) + '-ai6',
            'packageName': 'Pacchetto_Lab_10_0_u' + str(rid) + '_per_Launcher.zip'}

def unpack_source(commit, cache, destination):
    archive = cache / ('source-' + commit + '.zip')
    if not archive.exists():
        download(ORIGIN + '/zipball/' + commit, archive)
    validate_zip(archive)
    with zipfile.ZipFile(archive) as z:
        if z.comment.decode() != commit:
            raise ValueError('Source archive is not the pinned commit')
        roots = {PurePosixPath(n).parts[0] for n in z.namelist()}
        if len(roots) != 1:
            raise ValueError('Source archive must have one root')
        z.extractall(destination)
    return destination / roots.pop()

def merge_one(local, base, remote):
    ours, before, theirs = local.read_bytes(), base.read_bytes() if base.exists() else None, remote.read_bytes() if remote.exists() else None
    if before is None:
        if theirs is not None and theirs != ours:
            raise ValueError('Concurrent new file: ' + local.name)
        return ours
    if theirs is None:
        raise ValueError('Customized upstream file removed: ' + local.name)
    if theirs == before:
        return ours
    result = subprocess.run(['git','merge-file','-p',str(local),str(base),str(remote)],capture_output=True)
    if result.returncode:
        raise ValueError('Merge conflict: ' + local.name)
    return result.stdout

def integrate(base, source):
    merged = []
    for local in sorted(OVERLAY.rglob('*.java')):
        relative = local.relative_to(OVERLAY)
        content = merge_one(local, base / relative, source / relative)
        target = source / relative
        target.parent.mkdir(parents=True, exist_ok=True); target.write_bytes(content)
        merged.append(relative.as_posix())
    return merged

def output(name, value):
    if 'GITHUB_OUTPUT' in os.environ:
        with open(os.environ['GITHUB_OUTPUT'], 'a') as out:
            out.write(name + '=' + str(value) + '\n')

def tests_summary(folder):
    summary = dict(tests=0, failures=0, errors=0, skipped=0)
    for path in (folder / 'junit').glob('*.xml'):
        elem = ET.parse(path).getroot()
        for key in summary:
            summary[key] += int(elem.attrib.get(key, 0))
    if summary['tests'] < 148 or summary['failures'] or summary['errors'] or summary['skipped'] > 1:
        raise ValueError('Regression gate failed: ' + json.dumps(summary))
    summary['passed'] = summary['tests'] - summary['skipped']
    return summary

def smoke(package, spec, work, cache):
    baseline = download(BASELINE_URL, cache/'baseline.zip', BASELINE_HASH)
    validate_zip(baseline)
    stage = work/'smoke-stage'; stage.mkdir()
    with zipfile.ZipFile(baseline) as z:
        z.extractall(stage)
    classes = work/'launcher-classes'; classes.mkdir()
    java = sorted((HERE/'launcher/src').rglob('*.java')) + [HERE/'PackageSmoke.java']
    subprocess.run(['java','-m','jdk.compiler/com.sun.tools.javac.Main','--release','8','-encoding','UTF-8','-d',str(classes)] + list(map(str,java)),check=True)
    for path in (HERE/'launcher/resources').iterdir():
        if path.suffix == '.b64':
            (classes/path.stem).write_bytes(base64.b64decode(path.read_text()))
        else:
            shutil.copyfile(path, classes/path.name)
    with (work/'smoke.log').open('w') as log:
        subprocess.run(['java','-Djava.awt.headless=true','-cp',str(classes),'mage.lab.launcher.PackageSmoke',str(stage),str(package),str(work),spec['id']],stdout=log,stderr=subprocess.STDOUT,check=True,timeout=180)
    text = (work/'smoke.log').read_text()
    if 'PASS BACKUP_ACTIVATION_SERVER_ROLLBACK' not in text:
        raise ValueError('Incomplete installation smoke test')
    return {'backupActivationServerRollback': True}

def main():
    p = argparse.ArgumentParser(); p.add_argument('--work',type=Path,default=Path('auto-work')); p.add_argument('--replay',action='store_true')
    args = p.parse_args(); work = args.work.resolve(); work.mkdir(parents=True)
    cache = work/'cache'; cache.mkdir()
    release = api(ORIGIN + '/releases/latest')
    tag = release['tag_name']
    if not re.fullmatch(r'xmage_\d+\.\d+\.\d+V\d+', tag):
        raise ValueError('Unsupported official release tag')
    spec = release_spec(release, api(ORIGIN+'/commits/'+tag)['sha'])
    current = signed_payload(REPO/'updates/catalog.properties')
    newer = version_tuple(spec['xmageVersion']) > version_tuple(current['release.0.xmage.version'])
    if not newer and not args.replay:
        output('publish', 'false'); print('No newer stable official release; current channel preserved.'); return
    spec['replay'] = not newer
    (work/'spec.json').write_text(json.dumps(spec,indent=2)+'\n')
    base = unpack_source(BASE,cache,work/'base')
    source = unpack_source(spec['upstreamCommit'],cache,work/'integrated')
    merged = integrate(base,source)
    recorder = source/'Mage/src/main/java/mage/lab/LabRecorder.java'
    content, count = re.subn(r'XMage Lab 10\.0 · AI revisione 6', 'XMage Lab '+spec['labVersion']+' · AI revisione 6',recorder.read_text())
    if count != 1: raise ValueError('Unexpected Lab build marker')
    recorder.write_text(content)
    official = download(spec['officialUrl'],cache/'official.zip',spec['officialSha256']); validate_zip(official)
    if official.stat().st_size != spec['officialBytes']: raise ValueError('Official size mismatch')
    build = work/'build'
    subprocess.run([sys.executable,str(HERE/'build.py'),'--source',str(source),'--official',str(official),'--spec',str(work/'spec.json'),'--output',str(build)],check=True)
    junit = download(JUNIT_URL,cache/'junit.jar',JUNIT_HASH)
    subprocess.run([sys.executable,str(HERE/'verify.py'),'--source',str(source),'--jar-version',spec['jarVersion'],'--runtime',str(build/'runtime'),'--junit',str(junit),'--output',str(work/'tests'),'--suites',str(HERE/'suites.json')],check=True)
    tests = tests_summary(work/'tests')
    package = build/spec['packageName']
    installation = smoke(package,spec,work,cache)
    summary = json.loads((build/'build-summary.json').read_text())
    proof = {'schema':1,'spec':spec,'package':summary['package'],'jarCount':summary['jarCount'],
             'tests':tests,'installation':installation,'mergedJavaFiles':merged,
             'repository':TARGET,'workflowPath':'.github/workflows/automatic-upstream.yml',
             'workflowRunId':int(os.environ.get('GITHUB_RUN_ID','0')),
             'repositoryCommit':os.environ.get('GITHUB_SHA',subprocess.check_output(['git','rev-parse','HEAD'],cwd=REPO,text=True).strip())}
    candidate = work/'candidate'; candidate.mkdir()
    shutil.copyfile(package,candidate/package.name)
    (candidate/'candidate.json').write_text(json.dumps(proof,indent=2)+'\n')
    for path in [work/'smoke.log',work/'tests/run.log',build/'compile.log']:
        shutil.copyfile(path,candidate/(path.parent.name+'-'+path.name))
    output('publish','true' if newer else 'false')
    print(json.dumps({'newer':newer,'tests':tests,'package':summary['package'],'installation':installation}))

if __name__ == '__main__':
    main()

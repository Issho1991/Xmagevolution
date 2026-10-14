"""Verify an automatic candidate and prepare a signed catalog locally.

This script never uploads a key, writes GitHub, or executes a downloaded package.
The scheduled agent publishes the resulting catalog with a branch-head lease.
"""
from pathlib import Path
import argparse, base64, json, re, subprocess, tempfile, zipfile
from pipeline import HERE, REPO, ORIGIN, TARGET, api, download, digest, properties
from pipeline import signed_payload, release_spec, version_tuple, validate_zip

def validate_proof(proof):
    if proof.get('schema') != 1 or proof.get('repository') != TARGET:
        raise ValueError('Wrong candidate producer')
    if proof.get('workflowPath') != '.github/workflows/automatic-upstream.yml':
        raise ValueError('Wrong workflow')
    if not re.fullmatch('[a-f0-9]{40}', proof.get('repositoryCommit', '')):
        raise ValueError('Missing build commit')
    if not isinstance(proof.get('workflowRunId'), int) or proof['workflowRunId'] <= 0:
        raise ValueError('Missing workflow run')
    spec = proof['spec']; rid = spec['upstreamReleaseId']
    if spec.get('replay') or type(rid) is not int or rid <= 0:
        raise ValueError('Replay is never publishable')
    if (spec['id'] != 'lab-10.0-u'+str(rid)+'-ai6'
            or spec['labVersion'] != '10.0.u'+str(rid)
            or spec['packageName'] != 'Pacchetto_Lab_10_0_u'+str(rid)+'_per_Launcher.zip'):
        raise ValueError('Candidate identity mismatch')
    p = proof['package']
    if p['name'] != spec['packageName'] or not re.fullmatch('[a-f0-9]{64}',p['sha256']) or not re.fullmatch('[a-f0-9]{64}',p['inventorySha256']) or not 0 < p['bytes'] < 1024**3:
        raise ValueError('Invalid package declaration')
    t = proof['tests']
    if t['failures'] or t['errors'] or t['skipped'] > 1 or t['tests'] < 148 or t['passed'] != t['tests']-t['skipped'] or t['passed'] < 147:
        raise ValueError('Regression tests did not pass')
    if proof['installation'].get('backupActivationServerRollback') is not True or proof['jarCount'] < 190:
        raise ValueError('Installation gate did not pass')

def validate_package(package, proof):
    validate_zip(package)
    p = proof['package']; spec = proof['spec']
    if package.stat().st_size != p['bytes'] or digest(package) != p['sha256']:
        raise ValueError('Package digest/size mismatch')
    import hashlib
    with zipfile.ZipFile(package) as z:
        meta = properties(z.read('package.properties'))
        if meta != {'package.schema':'1','product':'xmage-laboratorio','id':spec['id'],'data.schema':'5','data.family':'lab9.1-ai6'}:
            raise ValueError('Unsupported package/data identity')
        manifest = properties(z.read('launcher/distribuzione.properties'))
        for key, expected in [('lab.version',spec['labVersion']),('ai.version','6'),('xmage.version',spec['xmageVersion']),('file.count',str(proof['jarCount']))]:
            if manifest.get(key) != expected:
                raise ValueError('Wrong runtime manifest: '+key)
        index = z.read('files.sha256')
        if hashlib.sha256(index).hexdigest() != p['inventorySha256']:
            raise ValueError('Inventory digest mismatch')
        names = set()
        for line in index.decode().splitlines():
            sha, name = line.split('\t', 1)
            if name in names or hashlib.sha256(z.read(name)).hexdigest() != sha:
                raise ValueError('Inventory entry mismatch')
            names.add(name)
        if names | {'files.sha256'} != set(z.namelist()):
            raise ValueError('Unlisted package files')
        declared_jars = set()
        for i in range(proof['jarCount']):
            name = manifest['file.%d.path' % i]
            if name in declared_jars or not name.endswith('.jar') or hashlib.sha256(z.read(name)).hexdigest() != manifest['file.%d.sha256' % i]:
                raise ValueError('Runtime JAR mismatch')
            declared_jars.add(name)
        if declared_jars != {name for name in names if name.endswith('.jar')}:
            raise ValueError('Runtime JAR list is incomplete')

def sign(proof, key, serial, url, output, verification):
    pinned = base64.b64decode((HERE/'launcher/resources/lab-updates-public.der.b64').read_text())
    public = subprocess.check_output(['openssl','pkey','-in',str(key),'-pubout','-outform','DER'],stderr=subprocess.DEVNULL)
    if public != pinned:
        raise ValueError('Signing key does not match installed launcher')
    spec, package = proof['spec'], proof['package']
    fields = {'product':'xmage-laboratorio','serial':str(serial),'release.count':'1',
              'release.0.id':spec['id'],'release.0.lab.version':spec['labVersion'],
              'release.0.ai.version':'6','release.0.xmage.version':spec['xmageVersion'],
              'release.0.zip.sha256':package['sha256'],
              'release.0.inventory.sha256':package['inventorySha256'],
              'release.0.zip.size':str(package['bytes']),'release.0.data.schema':'5',
              'release.0.data.family':'lab9.1-ai6','release.0.launcher.minimum':'3',
              'release.0.url':url,
              'release.0.notes':'Aggiornamento ufficiale '+spec['xmageVersion']+' integrato con Laboratorio e IA6. Test automatici, avvio server, backup e ritorno verificati. Chiudi client e server prima di aggiornare.',
              'release.0.verification.sha256':digest(verification)}
    payload = ''.join(k+'='+v.replace('\\','\\\\').replace('\n','\\n')+'\n' for k,v in fields.items()).encode()
    with tempfile.TemporaryDirectory() as temp:
        path = Path(temp)/'payload'; path.write_bytes(payload)
        signature = subprocess.check_output(['openssl','dgst','-sha256','-sign',str(key),str(path)],stderr=subprocess.DEVNULL)
    output.write_text('catalog.schema=1\npayload='+base64.b64encode(payload).decode()+'\nsignature='+base64.b64encode(signature).decode()+'\n')
    if signed_payload(output)['serial'] != str(serial):
        raise ValueError('New catalog signature verification failed')

def main():
    p = argparse.ArgumentParser(); p.add_argument('--tag',required=True); p.add_argument('--work',type=Path,required=True);p.add_argument('--key',type=Path)
    args = p.parse_args()
    if not re.fullmatch(r'lab-auto-u\d+',args.tag): raise ValueError('Invalid candidate tag')
    args.work.mkdir(parents=True,exist_ok=True)
    release = api('https://api.github.com/repos/'+TARGET+'/releases/tags/'+args.tag)
    if release.get('draft'): raise ValueError('Draft candidate not downloadable')
    assets = {a['name']:a for a in release['assets']}
    metadata = assets['candidate.json']
    if not re.fullmatch('sha256:[a-f0-9]{64}',metadata.get('digest','')): raise ValueError('Candidate metadata digest absent')
    proof_file = download(metadata['browser_download_url'],args.work/'candidate.json',metadata['digest'][7:])
    proof = json.loads(proof_file.read_text());validate_proof(proof)
    spec = proof['spec']
    if args.tag != 'lab-auto-u'+str(spec['upstreamReleaseId']):raise ValueError('Candidate tag mismatch')
    run = api('https://api.github.com/repos/'+TARGET+'/actions/runs/'+str(proof['workflowRunId']))
    if (run.get('status') != 'completed' or run.get('conclusion') != 'success'
            or run.get('head_branch') != 'main' or run.get('head_sha') != proof['repositoryCommit']
            or run.get('path') != proof['workflowPath'] or run.get('event') not in ('schedule','push','workflow_dispatch')):
        raise ValueError('Candidate workflow not successfully completed on main')
    official = api(ORIGIN+'/releases/latest')
    if not re.fullmatch(r'xmage_\d+\.\d+\.\d+V\d+',official['tag_name']):
        raise ValueError('Invalid official tag')
    expected = release_spec(official,api(ORIGIN+'/commits/'+official['tag_name'])['sha'])
    for key, value in expected.items():
        if spec.get(key) != value:raise ValueError('Official provenance mismatch: '+key)
    current = signed_payload(REPO/'updates/catalog.properties')
    if version_tuple(spec['xmageVersion']) <= version_tuple(current['release.0.xmage.version']):
        raise ValueError('Candidate is not newer than current signed channel')
    asset = assets[proof['package']['name']]
    url = 'https://github.com/'+TARGET+'/releases/download/'+args.tag+'/'+asset['name']
    if asset['browser_download_url'] != url or asset.get('digest') != 'sha256:'+proof['package']['sha256']:
        raise ValueError('Release asset mismatch')
    package = download(url,args.work/asset['name'],proof['package']['sha256']);validate_package(package,proof)
    result = {'verified':True,'tag':args.tag,'id':spec['id'],'packageSha256':proof['package']['sha256'],
              'previousCatalogSha256':digest(REPO/'updates/catalog.properties'),
              'serial':int(current['serial'])+1,'catalogPrepared':False}
    if args.key:
        sign(proof,args.key,result['serial'],url,args.work/'catalog.properties',proof_file)
        result['catalogPrepared']=True
    (args.work/'promotion.json').write_text(json.dumps(result,indent=2)+'\n')
    print(json.dumps(result))

if __name__=='__main__': main()

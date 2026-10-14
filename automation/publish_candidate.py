"""Publish only a completed build candidate; never modify the signed channel."""
from pathlib import Path
import json, os, subprocess, urllib.error
from promote import validate_proof, validate_package
from pipeline import api, TARGET

root=Path('candidate');proof=json.loads((root/'candidate.json').read_text())
validate_proof(proof)
if proof['workflowRunId'] != int(os.environ['GITHUB_RUN_ID']) or proof['repositoryCommit'] != os.environ['GITHUB_SHA']:
    raise ValueError('Artifact does not belong to this run')
package=root/proof['package']['name'];validate_package(package,proof)
tag='lab-auto-u'+str(proof['spec']['upstreamReleaseId'])
try:
    existing=api('https://api.github.com/repos/'+TARGET+'/releases/tags/'+tag)
except urllib.error.HTTPError as error:
    if error.code!=404:raise
    existing=None
if existing is not None:
    assets=existing['assets']
    same=[a for a in assets if a['name']==package.name and a.get('digest')=='sha256:'+proof['package']['sha256']]
    if len(same)!=1:raise ValueError('Existing candidate has different/missing package; never overwrite')
    print('Identical candidate already exists; signed channel unchanged.')
else:
    notes=root/'notes.md';notes.write_text('Integrazione automatica verificata di XMage '+proof['spec']['xmageVersion']+' con Laboratorio e IA6.\n\n'+str(proof['tests']['passed'])+' test attivi superati; installazione, backup, avvio server e ritorno verificati.\n\nIl launcher propone questa versione soltanto dopo la verifica separata e la firma del catalogo.\n')
    subprocess.run(['gh','release','create',tag,str(package),str(root/'candidate.json'),*map(str,sorted(root.glob('*.log'))),
                    '--target',os.environ['GITHUB_SHA'],'--prerelease','--latest=false',
                    '--title','Laboratorio '+proof['spec']['labVersion']+' — candidato verificato','--notes-file',str(notes)],check=True)

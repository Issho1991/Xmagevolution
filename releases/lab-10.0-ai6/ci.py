"""Rebuild and check the public Lab 10.0 overlay. No signing key or personal data."""
from pathlib import Path
import hashlib,json,shutil,subprocess,sys,urllib.request,zipfile,xml.etree.ElementTree as ET
HERE=Path(__file__).resolve().parent;WORK=Path.cwd()/'release-work';WORK.mkdir(exist_ok=True)
COMMIT='eb69d05b88142b5c4eec4869bcb23a0fec13c091'
URL='https://github.com/magefree/mage/releases/download/xmage_1.4.62V1/mage-full_1.4.62-dev_2026-10-03_20-47.zip'
def download(url,path,expected=None):
 with urllib.request.urlopen(url,timeout=90) as response,path.open('wb') as out:shutil.copyfileobj(response,out)
 if expected:assert hashlib.sha256(path.read_bytes()).hexdigest()==expected
source_zip=WORK/'source.zip';download('https://api.github.com/repos/magefree/mage/zipball/'+COMMIT,source_zip)
with zipfile.ZipFile(source_zip) as z:
 assert z.comment.decode()==COMMIT
 z.extractall(WORK/'upstream')
source=next((WORK/'upstream').iterdir());shutil.copytree(HERE/'overlay',source,dirs_exist_ok=True)
official=WORK/'official.zip';download(URL,official,'d256251ae41155638233c2cb20a7fec82f3f2dea7a468976691664c55e357b16')
junit=WORK/'junit.jar';download('https://repo.maven.apache.org/maven2/org/junit/platform/junit-platform-console-standalone/1.10.5/junit-platform-console-standalone-1.10.5.jar',junit,'defd77f4a1cc960c05ec8eb3edce7094bf043b496819a808e882935d889c085b')
release=source/'Mage.Laboratorio.Release/v10.0';build=WORK/'build';tests=WORK/'tests'
subprocess.run([sys.executable,str(release/'build_release.py'),'--official',str(official),'--output',str(build)],check=True)
expected=json.loads((HERE/'expected-classes.json').read_text());actual={p.relative_to(build/'classes').as_posix():hashlib.sha256(p.read_bytes()).hexdigest() for p in (build/'classes').rglob('*.class')}
assert actual==expected,'Compiled production classes differ from the locally verified release'
subprocess.run([sys.executable,str(release/'verify_release.py'),'--runtime',str(build/'runtime'),'--junit',str(junit),'--output',str(tests),'--suites',str(HERE/'public-suites.json')],check=True)
summary={'tests':0,'failures':0,'errors':0,'skipped':0}
for p in (tests/'junit').glob('*.xml'):
 e=ET.parse(p).getroot()
 for k in summary:summary[k]+=int(e.attrib.get(k,0))
assert summary['tests']>0 and summary['failures']==summary['errors']==0
summary['productionClassesMatchLocalVerification']=True
(build/'verification-public.json').write_text(json.dumps(summary,indent=2)+'\n')
print(json.dumps(summary))

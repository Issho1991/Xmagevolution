"""Build Lab 10.0 on the pinned official XMage 1.4.62 release. Python 3.9+, JDK 17."""
from pathlib import Path
import argparse,hashlib,json,os,re,shutil,subprocess,zipfile,io
HERE=Path(__file__).resolve().parent; SOURCE=None; SPEC=None
OFFICIAL_SHA=None
STAMP=(2026,10,14,0,0,0)
def sha(data):return hashlib.sha256(data).hexdigest()
def entry(z,name,data):
 i=zipfile.ZipInfo(name,STAMP);i.compress_type=zipfile.ZIP_DEFLATED;i.external_attr=0o100644<<16;z.writestr(i,data)
def build(official,out,reuse_classes=None):
 assert sha(official.read_bytes())==OFFICIAL_SHA,'Official archive hash mismatch'
 out.mkdir(parents=True,exist_ok=True);runtime=out/'runtime'
 if runtime.exists():raise ValueError('Runtime output already exists')
 with zipfile.ZipFile(official) as z:
  for name in z.namelist():
   if name.startswith('xmage/') and not name.endswith('/'):
    p=runtime/name[len('xmage/'):];p.parent.mkdir(parents=True,exist_ok=True);p.write_bytes(z.read(name))
 files=json.loads((HERE/'production.json').read_text());classes=out/'classes'
 if reuse_classes:shutil.copytree(reuse_classes,classes)
 else:
  classes.mkdir();cp=os.pathsep.join(str(p) for p in sorted(runtime.rglob('*.jar')))
  args=['--release','8','-encoding','UTF-8','-proc:none','-sourcepath','','-cp',cp,'-d',str(classes)]+[str(SOURCE/f) for f in files]
  argfile=out/'production.args';argfile.write_text('\n'.join('"'+s.replace('\\','/')+'"' for s in args))
  with (out/'compile.log').open('w') as log:subprocess.run(['java','-m','jdk.compiler/com.sun.tools.javac.Main','@'+str(argfile)],stdout=log,stderr=subprocess.STDOUT,check=True)
 module_map={'Mage':'mage','Mage.Client':'mage-client','Mage.Server':'mage-server','Mage.Server.Plugins/Mage.Player.AI.MAD':'mage-player-ai-mad','Mage.Server.Plugins/Mage.Player.AI':'mage-player-ai','Mage.Server.Plugins/Mage.Player.Human':'mage-player-human'}
 owners={}
 for name in files:
  p=SOURCE/name;package=re.search(r'^package\s+([\w.]+);',p.read_text(),re.M).group(1).replace('.','/')
  module=next(m for m in sorted(module_map,key=len,reverse=True) if name.startswith(m+'/src/'))
  for top in set([p.stem]+re.findall(r'^(?:(?:public|abstract|final|static)\s+)*(?:class|interface|enum)\s+(\w+)',p.read_text(),re.M)):
   owners[package+'/'+top]=module_map[module]+'-'+SPEC['jarVersion']+'.jar'
 compiled={p.relative_to(classes).as_posix():p.read_bytes() for p in classes.rglob('*.class')}
 replacements={}
 for name,data in compiled.items():
  assert int.from_bytes(data[6:8],'big')==52
  top=name.split('$')[0].removesuffix('.class')
  if top not in owners:raise ValueError('Unmapped compiled class '+name)
  replacements.setdefault(owners[top],{})[name]=data
 changes=[];used=set()
 for jar in sorted(runtime.rglob('*.jar')):
  if jar.name not in replacements:continue
  repl=replacements[jar.name];prefixes={n.split('$')[0].removesuffix('.class') for n in repl};content=io.BytesIO()
  with zipfile.ZipFile(jar) as old,zipfile.ZipFile(content,'w',zipfile.ZIP_DEFLATED) as z:
   kept={n:old.read(n) for n in old.namelist() if n.split('$')[0].removesuffix('.class') not in prefixes}
   for n,data in sorted({**kept,**repl}.items()):entry(z,n,data)
  jar.write_bytes(content.getvalue());used.update(repl);changes.append({'jar':jar.relative_to(runtime).as_posix(),'classes':sorted(repl)})
 assert used==set(compiled)
 config=runtime/'mage-server/config/config.xml';s=config.read_text()
 for required in ['serverAddress="0.0.0.0"','serverName="mage-server"','port="17171"']:
  if s.count(required)!=1:raise ValueError('Official server configuration changed: '+required)
 s=s.replace('serverAddress="0.0.0.0"','serverAddress="127.0.0.1"',1).replace('serverName="mage-server"','serverName="XMage Laboratorio '+SPEC['labVersion']+'"',1).replace('port="17171"','port="17181"',1);config.write_text(s)
 jars=sorted(p for p in runtime.rglob('*.jar'));manifest=['schema=1','lab.version='+SPEC['labVersion'],'ai.version=6','xmage.version='+SPEC['xmageVersion'],'client.jar=mage-client/lib/mage-client-'+SPEC['jarVersion']+'.jar','server.jar=mage-server/lib/mage-server-'+SPEC['jarVersion']+'.jar','file.count='+str(len(jars))]
 for i,p in enumerate(jars):manifest.extend(['file.%d.path=%s'%(i,p.relative_to(runtime).as_posix()),'file.%d.sha256=%s'%(i,sha(p.read_bytes()))])
 (runtime/'launcher').mkdir();(runtime/'launcher/distribuzione.properties').write_text('\n'.join(manifest)+'\n',encoding='ascii')
 (runtime/'package.properties').write_text('package.schema=1\nproduct=xmage-laboratorio\nid='+SPEC['id']+'\ndata.schema=5\ndata.family=lab9.1-ai6\n',encoding='ascii')
 names=sorted(p.relative_to(runtime).as_posix() for p in runtime.rglob('*') if p.is_file())
 index=''.join(sha((runtime/n).read_bytes())+'\t'+n+'\n' for n in names).encode();(runtime/'files.sha256').write_bytes(index)
 package=out/SPEC['packageName']
 with zipfile.ZipFile(package,'w',zipfile.ZIP_DEFLATED) as z:
  for n in names+['files.sha256']:entry(z,n,(runtime/n).read_bytes())
 summary={'lab':SPEC['labVersion'],'ai':'6','base':SPEC['xmageVersion'],'officialSha256':OFFICIAL_SHA,'package':{'name':package.name,'bytes':package.stat().st_size,'sha256':sha(package.read_bytes()),'inventorySha256':sha(index)},'jarCount':len(jars),'changedJars':changes}
 (out/'build-summary.json').write_text(json.dumps(summary,indent=2)+'\n');print(json.dumps({k:v for k,v in summary.items() if k!='changedJars'}));return summary
if __name__=='__main__':
 p=argparse.ArgumentParser();p.add_argument('--official',required=True,type=Path);p.add_argument('--output',required=True,type=Path);p.add_argument('--source',required=True,type=Path);p.add_argument('--spec',required=True,type=Path);a=p.parse_args()
 SPEC=json.loads(a.spec.read_text());SOURCE=a.source.resolve();OFFICIAL_SHA=SPEC['officialSha256']
 build(a.official.resolve(),a.output.resolve())

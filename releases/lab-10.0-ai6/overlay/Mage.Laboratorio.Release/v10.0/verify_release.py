"""Compile relevant regressions and run them against delivered JARs, never overlay classes."""
from pathlib import Path
import argparse,json,os,subprocess,shutil
HERE=Path(__file__).resolve().parent;SOURCE=HERE.parents[1]
p=argparse.ArgumentParser(description=__doc__)
p.add_argument('--runtime',type=Path,required=True);p.add_argument('--junit',type=Path,required=True)
p.add_argument('--output',type=Path,required=True);p.add_argument('--suites',type=Path);a=p.parse_args()
runtime=a.runtime.resolve();out=a.output.resolve();out.mkdir(parents=True,exist_ok=True)
roots=[SOURCE/p for p in json.loads((HERE/'test-roots.json').read_text())]
suites=json.loads((a.suites or HERE/'suites.json').read_text());files=[]
for name in suites:
    rel=name.replace('.','/')+'.java';files.append(next(root/rel for root in roots if (root/rel).exists()))
jars={}
for p in sorted((runtime/'mage-server').rglob('*.jar'))+sorted((runtime/'mage-client').rglob('*.jar')):
    if p.name not in jars:jars[p.name]=p
cp=os.pathsep.join([str(a.junit.resolve())]+[str(p) for p in jars.values()])
plugins=SOURCE/'Mage.Tests/plugins';plugins.mkdir(exist_ok=True)
for f in (runtime/'mage-server/plugins').glob('*.jar'):shutil.copyfile(f,plugins/f.name.replace('-1.4.62',''))
args=['--release','8','-encoding','UTF-8','-proc:none','-cp',cp,'-sourcepath',os.pathsep.join(map(str,roots)),'-d',str(out/'tests')]+list(map(str,files))
argfile=out/'tests.args';argfile.write_text('\n'.join('"'+s.replace('\\','/')+'"' for s in args))
with (out/'compile.log').open('w') as log:
    subprocess.run(['java','-m','jdk.compiler/com.sun.tools.javac.Main','@'+str(argfile)],stdout=log,stderr=subprocess.STDOUT,check=True)
resources=[str(p) for p in SOURCE.glob('*/src/main/resources')]+[str(p) for p in SOURCE.glob('*/src/test/resources')]+[str(SOURCE/'Mage.Tests/src/test')]
command=['java','-Xmx3g','-Djava.awt.headless=true','-cp',os.pathsep.join([str(out/'tests'),cp]+resources),'org.junit.platform.console.ConsoleLauncher','execute','--disable-banner','--details=summary','--reports-dir',str(out/'junit')]
for suite in suites:command+=['--select-class',suite]
with (out/'run.log').open('w') as log:result=subprocess.run(command,cwd=SOURCE/'Mage.Tests',stdout=log,stderr=subprocess.STDOUT)
print('\n'.join((out/'run.log').read_text().splitlines()[-19:]))
raise SystemExit(result.returncode)

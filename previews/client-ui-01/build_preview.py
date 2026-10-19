"""Reproducible opt-in UI preview; never writes a stable catalog or requests signing material."""
from pathlib import Path
import argparse, hashlib, importlib.util, json, os, shutil, subprocess, sys, urllib.request, zipfile
HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
COMMIT = 'eb69d05b88142b5c4eec4869bcb23a0fec13c091'
OFFICIAL_SHA = 'd256251ae41155638233c2cb20a7fec82f3f2dea7a468976691664c55e357b16'
OFFICIAL_URL = 'https://github.com/magefree/mage/releases/download/xmage_1.4.62V1/mage-full_1.4.62-dev_2026-10-03_20-47.zip'
JUNIT_SHA = 'defd77f4a1cc960c05ec8eb3edce7094bf043b496819a808e882935d889c085b'
NAME = 'XMage_Anteprima_Interfaccia_01'

def module(name, file):
    spec = importlib.util.spec_from_file_location(name, file); m = importlib.util.module_from_spec(spec); spec.loader.exec_module(m); return m

def download(url, target, expected=None):
    if not target.exists():
        with urllib.request.urlopen(urllib.request.Request(url, headers={'User-Agent':'XMage-Laboratorio-preview'}), timeout=120) as response, target.with_suffix('.part').open('wb') as out:
            shutil.copyfileobj(response, out)
        target.with_suffix('.part').rename(target)
    if expected and hashlib.sha256(target.read_bytes()).hexdigest() != expected: raise ValueError('Download hash mismatch: '+target.name)
    return target

def run(args, **kwargs):
    subprocess.run(list(map(str,args)), check=True, **kwargs)

def main():
    p=argparse.ArgumentParser();p.add_argument('--work', type=Path, required=True);p.add_argument('--cache',type=Path);p.add_argument('--gui',action='store_true');a=p.parse_args()
    work=a.work.resolve();work.mkdir(parents=True,exist_ok=True);cache=(a.cache or work/'cache').resolve();cache.mkdir(parents=True,exist_ok=True)
    if (work/'build').exists(): raise ValueError('Use a fresh work directory')
    official=download(OFFICIAL_URL,cache/'official.zip',OFFICIAL_SHA)
    upstream=download('https://api.github.com/repos/magefree/mage/zipball/'+COMMIT,cache/'upstream.zip')
    with zipfile.ZipFile(upstream) as archive:
        if archive.comment.decode()!=COMMIT:raise ValueError('Wrong upstream source commit')
        archive.extractall(work/'source')
    source=next((work/'source').iterdir())
    for overlay in (ROOT/'releases/lab-10.0-ai6/overlay', HERE/'overlay'):
        shutil.copytree(overlay,source,dirs_exist_ok=True)
    spec=dict(officialSha256=OFFICIAL_SHA,jarVersion='1.4.62',labVersion='10.0-ui-preview-01',xmageVersion='1.4.62-V1',id='lab-ui-preview-01',packageName='candidate-internal.zip')
    builder=module('preview_builder',ROOT/'automation/build.py')
    builder.SOURCE=source;builder.SPEC=spec;builder.OFFICIAL_SHA=OFFICIAL_SHA
    production=json.loads((ROOT/'automation/production.json').read_text())
    for f in sorted((HERE/'overlay').rglob('*.java')):
        relative=f.relative_to(HERE/'overlay').as_posix()
        if relative not in production:production.append(relative)
    (work/'production.json').write_text(json.dumps(production));builder.HERE=work
    summary=builder.build(official,work/'build');runtime=work/'build/runtime'
    junit=download('https://repo.maven.apache.org/maven2/org/junit/platform/junit-platform-console-standalone/1.10.5/junit-platform-console-standalone-1.10.5.jar',cache/'junit.jar',JUNIT_SHA)
    run([sys.executable,ROOT/'automation/verify.py','--source',source,'--jar-version','1.4.62','--runtime',runtime,'--junit',junit,'--output',work/'regressions'])
    pipeline=module('preview_pipeline',ROOT/'automation/pipeline.py')
    tests=pipeline.tests_summary(work/'regressions')
    smoke=pipeline.smoke(work/'build/candidate-internal.zip',spec,work,cache)
    # Standalone copy. No signed-channel import or updater is shipped in the preview.
    config=runtime/'mage-server/config/config.xml'
    text=config.read_text();assert text.count('port="17181"')==1
    config.write_text(text.replace('port="17181"','port="17182"'))
    runner=work/'runner-classes';runner.mkdir()
    run(['java','-m','jdk.compiler/com.sun.tools.javac.Main','--release','8','-encoding','UTF-8','-d',runner,*sorted((HERE/'runner').rglob('*.java'))])
    (runtime/'prova').mkdir()
    jar=runtime/'prova/anteprima.jar'
    with zipfile.ZipFile(jar,'w',zipfile.ZIP_DEFLATED) as z:
        builder.entry(z,'META-INF/MANIFEST.MF',b'Manifest-Version: 1.0\r\nMain-Class: mage.lab.preview.PreviewLauncher\r\n\r\n')
        for f in sorted(runner.rglob('*.class')):builder.entry(z,f.relative_to(runner).as_posix(),f.read_bytes())
    (runtime/'ANTEPRIMA_INTERFACCIA_01.txt').write_bytes((HERE/'ISTRUZIONI.txt').read_bytes())
    shutil.copyfile(HERE/'Avvia_Anteprima.cmd',runtime/'Avvia_Anteprima.cmd')
    (runtime/'Avvia_Anteprima.sh').write_text('#!/bin/sh\ncd "$(dirname "$0")" || exit 1\nexec java -jar prova/anteprima.jar\n')
    # Compile integration checks against the exact delivered jars.
    checkclasses=work/'ui-checks';checkclasses.mkdir()
    cp=os.pathsep.join([str(jar),*[str(f) for f in sorted(runtime.rglob('*.jar')) if f!=jar]])
    args=['--release','8','-encoding','UTF-8','-cp',cp,'-d',str(checkclasses),*map(str,sorted((HERE/'tests').rglob('*.java')))]
    argfile=work/'checks.args';argfile.write_text('\n'.join('"'+s.replace('\\','/')+'"' for s in args))
    run(['java','-m','jdk.compiler/com.sun.tools.javac.Main','@'+str(argfile)])
    testcp=str(checkclasses)+os.pathsep+cp
    run(['java','-Djava.awt.headless=true','-cp',testcp,'mage.lab.preview.IsolationChecks',work/'isolation'])
    run(['java','-Djava.awt.headless=true','-cp',testcp,'mage.lab.preview.HomeChecks',work])
    if a.gui:
        gui_runtime=work/'gui-runtime';shutil.copytree(runtime,gui_runtime)
        guicp=os.pathsep.join([str(checkclasses),str(gui_runtime/'prova/anteprima.jar'),str(gui_runtime/'mage-client/lib/mage-client-1.4.62.jar')])
        for mode in ('true','false'):
            run(['java','-Xmx3G','--add-opens=java.base/java.io=ALL-UNNAMED','-Djava.net.preferIPv4Stack=true','-Dxmage.lab.ui.preview='+mode,'-Djava.util.prefs.PreferencesFactory=mage.lab.preview.PrivatePreferences','-Dlab.preview.preferences='+str(work/'gui-prefs'),'-Duser.home='+str(work/'gui-home'),'-Dxmage.lab.dir='+str(work/'gui-data'),'-cp',guicp,'mage.lab.preview.GuiChecks',gui_runtime,work,mode],cwd=gui_runtime/'mage-client',timeout=240)
    # Tests may create files in runtime. Package only the original payload and preview harness.
    allowed={line.split('\t',1)[1] for line in (runtime/'files.sha256').read_text().splitlines()}
    # Only the isolated launcher may be used to start this copy.
    allowed={n for n in allowed if Path(n).suffix.lower() not in ('.bat','.cmd','.sh','.command')}
    allowed.update(['prova/anteprima.jar','ANTEPRIMA_INTERFACCIA_01.txt','Avvia_Anteprima.cmd','Avvia_Anteprima.sh'])
    inventory=''.join(hashlib.sha256((runtime/n).read_bytes()).hexdigest()+'\t'+n+'\n' for n in sorted(allowed))
    package=work/(NAME+'.zip')
    with zipfile.ZipFile(package,'w',zipfile.ZIP_DEFLATED) as z:
        for name in sorted(allowed):builder.entry(z,NAME+'/'+name,(runtime/name).read_bytes())
        builder.entry(z,NAME+'/files.sha256',inventory.encode())
    proof={'preview':1,'sourceCommit':os.environ.get('GITHUB_SHA','local-development'),'upstreamCommit':COMMIT,'officialSha256':OFFICIAL_SHA,'packageSha256':hashlib.sha256(package.read_bytes()).hexdigest(),'packageBytes':package.stat().st_size,'regressions':tests,'installation':smoke,'guiStartupAndProfiles':a.gui,'manualGamesVerified':False,'stableChannelChanged':False}
    (work/'preview-verification.json').write_text(json.dumps(proof,indent=2)+'\n');print(json.dumps(proof))
if __name__=='__main__':main()

"""Build and check the Java 8-compatible launcher without a game installation."""
from pathlib import Path
import argparse
import base64
import hashlib
import json
import shutil
import subprocess
import zipfile

HERE = Path(__file__).resolve().parent
VERSION = '0.4.0'


def entry(archive, name, content):
    info = zipfile.ZipInfo(name, (2026, 10, 19, 0, 0, 0))
    info.compress_type = zipfile.ZIP_DEFLATED
    info.external_attr = 0o100644 << 16
    archive.writestr(info, content)


def build(out):
    if 'VERSION = "' + VERSION + '"' not in (HERE / 'launcher/src/mage/lab/launcher/LauncherInfo.java').read_text():
        raise ValueError('Build and launcher versions differ')
    out.mkdir(parents=True, exist_ok=True)
    classes = out / 'classes'
    if classes.exists():
        shutil.rmtree(classes)
    classes.mkdir()
    files = sorted((HERE / 'launcher/src').rglob('*.java')) + sorted((HERE / 'launcher/tests').rglob('*.java'))
    compiler = ['javac'] if shutil.which('javac') else ['java', '-m', 'jdk.compiler/com.sun.tools.javac.Main']
    subprocess.run([*compiler, '--release', '8', '-encoding', 'UTF-8', '-d', str(classes), *map(str, files)], check=True)
    for resource in (HERE / 'launcher/resources').iterdir():
        content = resource.read_bytes()
        name = resource.name
        if name.endswith('.b64'):
            name = name[:-4]
            content = base64.b64decode(content)
        (classes / name).write_bytes(content)
    (classes / 'lab-catalog-corrente.properties').write_bytes((HERE.parent / 'updates/catalog.properties').read_bytes())
    for test in ('AutomationStatusChecks', 'LauncherUpdateChecks'):
        subprocess.run(['java', '-Djava.awt.headless=true', '-cp', str(classes), 'mage.lab.launcher.' + test], check=True)
    subprocess.run(['java', '-Djava.awt.headless=true', '-cp', str(classes), 'mage.lab.launcher.LauncherPreview', str(out)], check=True)
    jar = out / 'laboratorio-launcher.jar'
    with zipfile.ZipFile(jar, 'w') as archive:
        entry(archive, 'META-INF/MANIFEST.MF', ('Manifest-Version: 1.0\r\nMain-Class: mage.lab.launcher.LaboratorioLauncher\r\nImplementation-Version: ' + VERSION + '\r\n\r\n').encode())
        for f in sorted(classes.rglob('*')):
            if f.is_file() and not any(name in f.name for name in ('AutomationStatusChecks', 'LauncherUpdateChecks', 'LauncherRestartChecks', 'LauncherPreview')):
                data = f.read_bytes()
                if f.suffix == '.class' and int.from_bytes(data[6:8], 'big') != 52:
                    raise ValueError('Not compatible with Java 8')
                entry(archive, f.relative_to(classes).as_posix(), data)
    subprocess.run(['java', '-Djava.awt.headless=true', '-cp', str(classes), 'mage.lab.launcher.LauncherRestartChecks', str(jar)], check=True)
    readme = ('LAUNCHER ' + VERSION + ' — AVVIO E AGGIORNAMENTI\n\n'
              'Questo passaggio manuale serve per passare dal vecchio launcher alla versione 0.4.0.\n'
              '1. Chiudi client, server e launcher.\n'
              '2. Estrai questo ZIP nella cartella che contiene Avvia_Laboratorio.cmd.\n'
              '3. Sostituisci launcher/laboratorio-launcher.jar quando richiesto.\n'
              '4. Riapri il launcher: deve indicare 0.4.0.\n\n'
              'Gioca avvia XMage; I miei mazzi e Report aprono il tuo archivio.\n'
              'Il controllo aggiornamenti parte all’apertura senza bloccare il gioco; puoi disattivarlo.\n'
              'Aggiorna Laboratorio scarica la versione verificata e crea un backup prima di attivarla.\n'
              'Aggiorna launcher e riavvia aggiorna questa schermata, con firma e hash verificati.\n'
              'Client e server devono essere chiusi per entrambe le installazioni.\n'
              'Il launcher precedente resta in launcher/aggiornamento-launcher/update-*/backup-launcher.jar.\n'
              'Se il riavvio non riesce, viene ripristinato il file precedente.\n'
              'Per il recupero manuale, a launcher chiuso, copia backup-launcher.jar sopra launcher/laboratorio-launcher.jar.\n'
              'Il pacchetto non sostituisce il gioco, i mazzi o i report.\n')
    package = out / 'Launcher_0_4_0_per_XMage_Lab.zip'
    with zipfile.ZipFile(package, 'w') as archive:
        entry(archive, 'launcher/laboratorio-launcher.jar', jar.read_bytes())
        entry(archive, 'LEGGIMI_LAUNCHER_0_4_0.txt', readme.encode('utf-8'))
    result = {'version': VERSION, 'sha256': hashlib.sha256(package.read_bytes()).hexdigest(), 'bytes': package.stat().st_size, 'jarSha256': hashlib.sha256(jar.read_bytes()).hexdigest(), 'jarBytes': jar.stat().st_size}
    (out / 'launcher-verification.json').write_text(json.dumps(result, indent=2) + '\n')
    print(json.dumps(result))


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--output', type=Path, default=Path('launcher-build'))
    build(parser.parse_args().output.resolve())

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
VERSION = '0.3.2'


def entry(archive, name, content):
    info = zipfile.ZipInfo(name, (2026, 10, 18, 0, 0, 0))
    info.compress_type = zipfile.ZIP_DEFLATED
    info.external_attr = 0o100644 << 16
    archive.writestr(info, content)


def build(out):
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
    subprocess.run(['java', '-Djava.awt.headless=true', '-cp', str(classes), 'mage.lab.launcher.AutomationStatusChecks'], check=True)
    jar = out / 'laboratorio-launcher.jar'
    with zipfile.ZipFile(jar, 'w') as archive:
        entry(archive, 'META-INF/MANIFEST.MF', b'Manifest-Version: 1.0\r\nMain-Class: mage.lab.launcher.LaboratorioLauncher\r\n\r\n')
        for f in sorted(classes.rglob('*')):
            if f.is_file() and 'AutomationStatusChecks' not in f.name:
                data = f.read_bytes()
                if f.suffix == '.class' and int.from_bytes(data[6:8], 'big') != 52:
                    raise ValueError('Not compatible with Java 8')
                entry(archive, f.relative_to(classes).as_posix(), data)
    readme = ('LAUNCHER 0.3.2 — STATO AGGIORNAMENTI\n\n'
              '1. Chiudi client, server e launcher.\n'
              '2. Estrai questo ZIP nella cartella che contiene Avvia_Laboratorio.cmd.\n'
              '3. Sostituisci launcher/laboratorio-launcher.jar quando richiesto.\n'
              '4. Riapri il launcher: deve indicare 0.3.2.\n'
              '5. In Aggiornamenti premi Controlla aggiornamenti.\n\n'
              'La sezione Preparazione automatica degli aggiornamenti mostra lo stato del servizio GitHub.\n'
              'Un controllo vecchio di oltre 72 ore produce un avviso. Lo stato non autorizza installazioni: il catalogo deve sempre avere firma valida.\n'
              'Il controllo e l’installazione restano avviati dai pulsanti dell’app.\n'
              'Questa patch aggiorna solo il launcher: Lab 10.0, IA, mazzi e report non vengono sostituiti.\n')
    package = out / 'Launcher_0_3_2_per_XMage_Lab.zip'
    with zipfile.ZipFile(package, 'w') as archive:
        entry(archive, 'launcher/laboratorio-launcher.jar', jar.read_bytes())
        entry(archive, 'LEGGIMI_LAUNCHER_0_3_2.txt', readme.encode('utf-8'))
    result = {'version': VERSION, 'sha256': hashlib.sha256(package.read_bytes()).hexdigest(), 'bytes': package.stat().st_size}
    (out / 'launcher-verification.json').write_text(json.dumps(result, indent=2) + '\n')
    print(json.dumps(result))


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--output', type=Path, default=Path('launcher-build'))
    build(parser.parse_args().output.resolve())

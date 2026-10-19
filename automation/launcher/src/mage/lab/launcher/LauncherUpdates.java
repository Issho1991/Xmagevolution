package mage.lab.launcher;

import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.jar.*;
import java.util.function.Consumer;

/** Independent signed channel: a game catalog can never authorize launcher code. */
final class LauncherUpdates {
    static final int LIMIT = 32768;
    static final long MAX_JAR = 16L * 1024 * 1024;
    final Path home, cache;
    final PublicKey key;
    final UpdateNetwork.Transport network;

    static long number(String version) throws IOException {
        if (!version.matches("(0|[1-9][0-9]{0,2})\\.(0|[1-9][0-9]{0,2})\\.(0|[1-9][0-9]{0,2})"))
            throw new IOException("Versione launcher non valida.");
        String[] parts = version.split("\\.");
        return Long.parseLong(parts[0]) * 1000000 + Long.parseLong(parts[1]) * 1000 + Long.parseLong(parts[2]);
    }
    static final class Release {
        final String version, hash, commit;
        final long serial, size;
        final URI url;
        final byte[] encoded;
        Release(Properties p, byte[] bytes) throws IOException {
            if (!"xmage-laboratorio-launcher".equals(p.getProperty("product"))) throw new IOException("Catalogo di un altro prodotto.");
            version = LabCatalog.required(p,"version");
            try { serial=Long.parseLong(LabCatalog.required(p,"serial")); size=Long.parseLong(LabCatalog.required(p,"jar.size")); }
            catch (NumberFormatException e) { throw new IOException("Dimensione o seriale launcher non valido.",e); }
            hash=LabCatalog.required(p,"jar.sha256"); commit=LabCatalog.required(p,"source.commit");
            if (serial != number(version) || serial < 1 || size < 1 || size > MAX_JAR || !hash.matches("[a-f0-9]{64}") || !commit.matches("[a-f0-9]{40}"))
                throw new IOException("Identità del launcher non valida.");
            url=UpdateNetwork.https(LabCatalog.required(p,"url"));
            String expected="https://github.com/Issho1991/Xmagevolution/releases/download/launcher-v"+version+"/laboratorio-launcher.jar";
            if (!expected.equals(url.toString())) throw new IOException("Origine del launcher non riconosciuta.");
            encoded=bytes.clone();
        }
        boolean newer() throws IOException { return serial > number(LauncherInfo.VERSION); }
        void verifyJar(Path jar, PublicKey key) throws IOException {
            if (Files.isSymbolicLink(jar) || !Files.isRegularFile(jar) || Files.size(jar)!=size || !hash.equals(Installation.sha256(jar)))
                throw new IOException("Launcher incompleto o alterato.");
            try (JarFile file=new JarFile(jar.toFile())) {
                Manifest manifest=file.getManifest();
                if (manifest==null || !version.equals(manifest.getMainAttributes().getValue("Implementation-Version"))
                        || !LaboratorioLauncher.class.getName().equals(manifest.getMainAttributes().getValue("Main-Class")))
                    throw new IOException("Identità del programma diversa dal catalogo.");
                JarEntry entry=file.getJarEntry("lab-updates-public.der");
                if (entry==null || entry.getSize()!=key.getEncoded().length) throw new IOException("Chiave launcher diversa.");
                try (InputStream in=file.getInputStream(entry)) {
                    byte[] found=new byte[key.getEncoded().length]; int pos=0,n;
                    while(pos<found.length && (n=in.read(found,pos,found.length-pos))!=-1) pos+=n;
                    if (pos!=found.length || in.read()!=-1 || !Arrays.equals(found,key.getEncoded())) throw new IOException("Chiave launcher diversa.");
                }
            }
        }
    }
    LauncherUpdates(Path home) throws IOException { this(home,LabCatalog.publicKey(),new UpdateNetwork.Https()); }
    LauncherUpdates(Path home,PublicKey key,UpdateNetwork.Transport network) {
        this.home=home; this.key=key; this.network=network; cache=home.resolve("launcher/catalogo-launcher.properties");
    }
    static Release verify(byte[] bytes,PublicKey key) throws IOException {
        if(bytes.length>LIMIT)throw new IOException("Catalogo launcher troppo grande.");
        try {
            Properties envelope=new Properties();envelope.load(new ByteArrayInputStream(bytes));
            if(!"1".equals(envelope.getProperty("catalog.schema")))throw new IOException("Formato catalogo launcher non supportato.");
            byte[] payload=Base64.getDecoder().decode(envelope.getProperty("payload",""));
            byte[] signature=Base64.getDecoder().decode(envelope.getProperty("signature",""));
            Signature verifier=Signature.getInstance("SHA256withRSA");verifier.initVerify(key);verifier.update(payload);
            if(!verifier.verify(signature))throw new IOException("Firma launcher non valida. Aggiornamento rifiutato.");
            Properties p=new Properties();p.load(new InputStreamReader(new ByteArrayInputStream(payload),StandardCharsets.UTF_8));
            return new Release(p,bytes);
        } catch(GeneralSecurityException|IllegalArgumentException e) { throw new IOException("Catalogo launcher non autentico o malformato.",e); }
    }
    Release cached() throws IOException {
        if(!Files.exists(cache))return null;
        if(Files.isSymbolicLink(cache)||Files.size(cache)>LIMIT)throw new IOException("Catalogo launcher salvato non valido.");
        return verify(Files.readAllBytes(cache),key);
    }
    Release accept(byte[] bytes) throws IOException {
        Release next=verify(bytes,key),old=cached();
        if(next.serial<number(LauncherInfo.VERSION) || (old!=null && next.serial<old.serial)) throw new IOException("Il servizio propone un launcher precedente.");
        if(old!=null && next.serial==old.serial && !Arrays.equals(old.encoded,next.encoded))throw new IOException("La stessa versione del launcher ha contenuti diversi.");
        UpdateNetwork.atomic(cache,bytes);return next;
    }
    Release check() throws IOException { return accept(network.read(UpdateNetwork.https(LauncherInfo.CHANNEL),LIMIT)); }
    Path stage(Release selected,Consumer<String> status) throws IOException {
        Release current=cached();
        if(current==null || !Arrays.equals(current.encoded,selected.encoded) || !current.newer())throw new IOException("Ricontrolla gli aggiornamenti del launcher.");
        Path folder=home.resolve("launcher/aggiornamento-launcher");Files.createDirectories(folder);
        Path stage=Files.createTempDirectory(folder,"update-");
        try {
            Path jar=stage.resolve("nuovo.jar");network.download(current.url,jar,current.size,current.hash,status);current.verifyJar(jar,key);
            Files.write(stage.resolve("catalogo.properties"),current.encoded);
            Path running;
            try { running=Paths.get(LauncherUpdates.class.getProtectionDomain().getCodeSource().getLocation().toURI()); }
            catch(Exception e){throw new IOException("Percorso del launcher non riconosciuto.",e);}
            Path target=home.resolve("launcher/laboratorio-launcher.jar");
            if(!Files.isRegularFile(running)||!Files.isSameFile(running,target)||Files.isSymbolicLink(target))throw new IOException("Avvia il launcher dalla cartella del Laboratorio prima di aggiornarlo.");
            Files.copy(running,stage.resolve("precedente.jar"));
            return stage;
        } catch(IOException e) { ArchiveFiles.deleteTree(stage);throw e; }
    }
}

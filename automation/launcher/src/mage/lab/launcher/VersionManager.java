package mage.lab.launcher;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.function.Consumer;
import java.util.prefs.*;
import java.util.zip.*;

/** App versions are immutable payloads. The user's live laboratorio is never restored or replaced. */
public final class VersionManager {
    public static final String ORIGINAL = "originale";
    final Path home, versions, backups, stateFile;
    private final Properties trusted;
    private Properties state;
    public VersionManager(Path home) throws IOException {
        this(home, trust());
        refreshCatalog();
    }
    // Package-private injected trust is used only by isolated tests; never loaded from user packages.
    VersionManager(Path home, Properties trusted) throws IOException {
        this.home=home.toRealPath(); this.trusted=trusted;
        versions=this.home.resolve("launcher/versioni"); backups=this.home.resolve("launcher/backup"); stateFile=this.home.resolve("launcher/versione-attiva.properties");
        state=Files.exists(stateFile) ? ArchiveFiles.properties(stateFile) : new Properties();
    }
    static Properties trust() throws IOException {
        Properties p=new Properties();
        try(InputStream in=VersionManager.class.getResourceAsStream("/pacchetti-verificati.properties")) {
            if(in==null)throw new IOException("Catalogo locale dei pacchetti mancante."); p.load(in);
        }
        return p;
    }
    void refreshCatalog() throws IOException { refreshCatalog(new LabCatalog.Store(home)); }
    void refreshCatalog(LabCatalog.Store store) throws IOException { store.mergeInto(trusted); }
    void requireIdle(ProcessSupervisor processes) throws IOException { idle(processes,null); }
    public String activeId() { return state.getProperty("active", ORIGINAL); }
    public String previousId() { return state.getProperty("previous", ""); }
    private Path path(String id) throws IOException {
        if(ORIGINAL.equals(id))return home;
        if(!id.matches("[a-z0-9][a-z0-9._-]{0,79}"))throw new IOException("Identificativo versione non valido.");
        Path p=versions.resolve(id);
        if(Files.isSymbolicLink(versions)||Files.isSymbolicLink(p))throw new IOException("Collegamento non consentito nelle versioni.");
        if(!Files.isDirectory(p))throw new IOException("Versione installata non trovata: "+id);
        return p;
    }
    public Installation active() throws IOException { return load(activeId()); }
    public Installation load(String id) throws IOException {
        Installation i=Installation.load(path(id),home);
        if(!ORIGINAL.equals(id)) {
            String expected=trusted.getProperty(id+".inventory");
            if(expected==null || !expected.equals(Installation.sha256(i.root.resolve(ArchiveFiles.INVENTORY))))
                throw new IOException("Questa versione non appartiene al catalogo verificato del launcher.");
            Properties p=ArchiveFiles.properties(i.root.resolve("package.properties"));
            if(!"1".equals(p.getProperty("package.schema"))||!"xmage-laboratorio".equals(p.getProperty("product"))||!id.equals(p.getProperty("id"))
                    ||!"5".equals(p.getProperty("data.schema"))||!"lab9.1-ai6".equals(p.getProperty("data.family")))
                throw new IOException("Il pacchetto richiede una compatibilità dati non gestita da questo launcher.");
        }
        return i;
    }
    public String label(String id) throws IOException {
        Installation i=load(id);
        return "Lab "+i.value("lab.version")+" · IA "+i.value("ai.version")+(ORIGINAL.equals(id)?" · originale":" · installazione separata");
    }
    public Map<String,String> installed() throws IOException {
        Map<String,String> result=new LinkedHashMap<>(); result.put(ORIGINAL,label(ORIGINAL));
        if(Files.isDirectory(versions))try(DirectoryStream<Path> stream=Files.newDirectoryStream(versions)) {
            List<Path> dirs=new ArrayList<>(); for(Path p:stream)dirs.add(p); Collections.sort(dirs);
            for(Path p:dirs) { String id=p.getFileName().toString(); if(id.startsWith(".staging-"))continue;
                if(Files.isDirectory(p))try { result.put(id,label(id)); } catch(IOException e) { /* incomplete/untrusted versions cannot be selected */ }
            }
        }
        return result;
    }
    private void idle(ProcessSupervisor processes, Installation candidate) throws IOException {
        if(processes.serverAlive()||processes.clientAlive())throw new IOException("Termina la partita e chiudi client e server prima di questa operazione.");
        if(!processes.installation.home.equals(home))throw new IOException("Sessione di un’altra installazione.");
        Installation.requireFreePort(processes.installation.endpoint());
        Installation.requireFreePort(Installation.load(home).endpoint());
        if(candidate!=null)Installation.requireFreePort(candidate.endpoint());
    }
    private static void space(Path folder,long bytes) throws IOException {
        if(bytes<0 || Files.getFileStore(folder).getUsableSpace() < bytes + 64L*1024*1024)
            throw new IOException("Spazio libero insufficiente. La versione attiva resta invariata.");
    }
    public String install(Path zip, ProcessSupervisor processes, Consumer<String> status) throws IOException {
        idle(processes,null);
        if(!Files.isRegularFile(zip)||Files.size(zip)>ArchiveFiles.MAX_BYTES)throw new IOException("Pacchetto ZIP mancante o troppo grande.");
        Files.createDirectories(versions); space(versions, Files.size(zip));
        Path staging=Files.createTempDirectory(versions,".staging-");
        try {
            status.accept("Copia e verifica del pacchetto…");
            Path copy=staging.resolve("package.zip"); Files.copy(zip,copy);
            String digest=Installation.sha256(copy), id=null;
            for(String key:trusted.stringPropertyNames())if(key.endsWith(".zip")&&trusted.getProperty(key).equals(digest))id=key.substring(0,key.length()-4);
            if(id==null)throw new IOException("Pacchetto non riconosciuto o incompleto. Usa un pacchetto Laboratorio compatibile fornito per questo launcher. Gli ZIP ufficiali XMage non si installano qui.");
            if(!id.matches("[a-z0-9][a-z0-9._-]{0,79}")||ORIGINAL.equals(id))throw new IOException("Identificativo pacchetto non valido.");
            Path destination=versions.resolve(id);
            if(Files.exists(destination))throw new IOException("Questa versione è già installata. Selezionala dall’elenco.");
            long size=0;
            try(ZipFile f=new ZipFile(copy.toFile())) { Enumeration<? extends ZipEntry> it=f.entries();
                while(it.hasMoreElements()) { long n=it.nextElement().getSize(); if(n<0 || n>ArchiveFiles.MAX_BYTES-size)throw new IOException("Dimensione ZIP non valida."); size+=n; }
            }
            space(versions,size);
            status.accept("Installazione in una cartella separata…");
            Path payload=Files.createDirectory(staging.resolve("payload")); ArchiveFiles.extract(copy,payload,true);
            Properties metadata=ArchiveFiles.properties(payload.resolve("package.properties"));
            if(!id.equals(metadata.getProperty("id"))||!"1".equals(metadata.getProperty("package.schema"))
                    ||!"xmage-laboratorio".equals(metadata.getProperty("product"))||!"5".equals(metadata.getProperty("data.schema"))
                    ||!"lab9.1-ai6".equals(metadata.getProperty("data.family"))
                    ||!Installation.sha256(payload.resolve(ArchiveFiles.INVENTORY)).equals(trusted.getProperty(id+".inventory")))
                throw new IOException("Identità o formato dati del pacchetto non supportato.");
            Installation.load(payload,home).verify();
            idle(processes,null); ArchiveFiles.atomicMove(payload,destination,false);
            return id;
        } finally { ArchiveFiles.deleteTree(staging); }
    }
    private Map<String,Path> backupFiles(Installation current) throws IOException {
        Map<String,Path> result=new TreeMap<>();
        ArchiveFiles.collect(home,home.resolve("laboratorio"),result);
        for(String name:Arrays.asList("mage-client/decks","mage-client/config","mage-server/config","launcher/impostazioni.properties","launcher/versione-attiva.properties"))
            ArchiveFiles.collect(home,home.resolve(name),result);
        if(!current.root.equals(home))for(String name:Arrays.asList("mage-client/config","mage-server/config"))
            ArchiveFiles.collect(home,current.root.resolve(name),result);
        return result;
    }
    public Path backup(ProcessSupervisor processes,Consumer<String> status) throws IOException {
        idle(processes,null); Files.createDirectories(backups);
        Installation current=processes.installation; Map<String,Path> files=backupFiles(current);
        long bytes=0; Map<String,String> before=new TreeMap<>();
        for(Map.Entry<String,Path> e:files.entrySet()) { BasicFileAttributes a=Files.readAttributes(e.getValue(),BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
            bytes=Math.addExact(bytes,a.size()); before.put(e.getKey(),a.size()+":"+a.lastModifiedTime()); }
        if(bytes>ArchiveFiles.MAX_BYTES)throw new IOException("Il backup supera 8 GB: esegui prima una copia esterna dell’archivio.");
        space(backups,bytes*2+1024*1024);
        Path temp=Files.createTempFile(backups,".backup-",".partial"); Path verify=null;
        try {
            status.accept("Backup di mazzi, report e impostazioni…");
            StringBuilder index=new StringBuilder();
            try(ZipOutputStream out=new ZipOutputStream(Files.newOutputStream(temp))) {
                for(Map.Entry<String,Path> e:files.entrySet())try(InputStream in=Files.newInputStream(e.getValue())) {
                    index.append(ArchiveFiles.writeEntry(out,e.getKey(),in)).append('\t').append(e.getKey()).append('\n');
                }
                ByteArrayOutputStream prefs=new ByteArrayOutputStream();
                try { Preferences.userRoot().node("/mage/client").exportSubtree(prefs); }
                catch(BackingStoreException e) { throw new IOException("Impossibile salvare le preferenze del client.",e); }
                index.append(ArchiveFiles.writeEntry(out,"preferenze-client.xml",new ByteArrayInputStream(prefs.toByteArray()))).append("\tpreferenze-client.xml\n");
                String details="backup.schema=1\nactive="+activeId()+"\ndata.schema=5\n";
                index.append(ArchiveFiles.writeEntry(out,"backup.properties",new ByteArrayInputStream(details.getBytes(StandardCharsets.UTF_8)))).append("\tbackup.properties\n");
                ArchiveFiles.writeEntry(out,ArchiveFiles.INVENTORY,new ByteArrayInputStream(index.toString().getBytes(StandardCharsets.UTF_8)));
            }
            if(!files.keySet().equals(backupFiles(current).keySet()))throw new IOException("L’archivio è cambiato durante il backup. Chiudi le altre sessioni e riprova.");
            for(Map.Entry<String,Path> e:files.entrySet()) { BasicFileAttributes a=Files.readAttributes(e.getValue(),BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
                if(!before.get(e.getKey()).equals(a.size()+":"+a.lastModifiedTime()))throw new IOException("Un file è cambiato durante il backup: "+e.getKey()); }
            verify=Files.createTempDirectory(backups,".verify-"); ArchiveFiles.extract(temp,verify,false);
            // Compare the stored bytes to the originals as well as to the ZIP's own integrity index.
            Map<String,String> hashes=ArchiveFiles.inventory(verify);
            for(Map.Entry<String,Path> e:files.entrySet())if(!Installation.sha256(e.getValue()).equals(hashes.get(e.getKey())))
                throw new IOException("Il contenuto dei dati è cambiato durante il backup: "+e.getKey());
            idle(processes,null);
            String date=new java.text.SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date());
            Path target=backups.resolve("Laboratorio-"+date+"-"+UUID.randomUUID().toString().substring(0,8)+".zip");
            ArchiveFiles.atomicMove(temp,target,false); return target;
        } finally { Files.deleteIfExists(temp); if(verify!=null)ArchiveFiles.deleteTree(verify); }
    }
    public Path activate(String id,ProcessSupervisor processes,Consumer<String> status) throws IOException {
        if(id.equals(activeId()))throw new IOException("Questa versione è già attiva.");
        Installation candidate=load(id); idle(processes,candidate);
        status.accept("Verifica della versione selezionata…"); candidate.verify();
        if(!ORIGINAL.equals(id))ArchiveFiles.verify(candidate.root,false);
        Path saved=backup(processes,status); idle(processes,candidate);
        Properties next=new Properties(); next.setProperty("active",id); next.setProperty("previous",activeId()); next.setProperty("backup",home.relativize(saved).toString());
        Path temp=Files.createTempFile(stateFile.getParent(),"versione-",".tmp");
        try {
            try(OutputStream out=Files.newOutputStream(temp)) { next.store(out,"Versione attiva; dati personali condivisi e mai ripristinati automaticamente"); }
            ArchiveFiles.atomicMove(temp,stateFile,true); state=next; processes.use(candidate);
        } finally { Files.deleteIfExists(temp); }
        return saved;
    }
    public Path recover(Path zip, ProcessSupervisor processes, Consumer<String> status) throws IOException {
        idle(processes,null);
        if(!Files.isRegularFile(zip)||Files.size(zip)>ArchiveFiles.MAX_BYTES)throw new IOException("Backup non valido.");
        Path recoveries=home.resolve("launcher/recuperi"); Files.createDirectories(recoveries);
        long expanded=0;
        try(ZipFile file=new ZipFile(zip.toFile())) { Enumeration<? extends ZipEntry> it=file.entries(); while(it.hasMoreElements()) {
            long size=it.nextElement().getSize(); if(size<0||size>ArchiveFiles.MAX_BYTES-expanded)throw new IOException("Backup troppo grande."); expanded+=size;
        }}
        space(recoveries,expanded);
        Path temp=Files.createTempDirectory(recoveries,".recupero-");
        try {
            status.accept("Verifica e recupero in una nuova cartella…"); ArchiveFiles.extract(zip,temp,false);
            if(!"1".equals(ArchiveFiles.properties(temp.resolve("backup.properties")).getProperty("backup.schema")))throw new IOException("Questo ZIP non è un backup del launcher.");
            Path result=recoveries.resolve("copia-"+UUID.randomUUID().toString()); ArchiveFiles.atomicMove(temp,result,false); return result;
        } finally { ArchiveFiles.deleteTree(temp); }
    }
}

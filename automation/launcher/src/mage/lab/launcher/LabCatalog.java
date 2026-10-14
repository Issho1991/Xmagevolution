package mage.lab.launcher;

import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.security.spec.X509EncodedKeySpec;
import java.util.*;

/** Catalog authenticity is anchored in the launcher, never in the selected ZIP or website. */
final class LabCatalog {
    static final int LIMIT=512*1024;
    static final class Release {
        final String id,version,ai,xmage,sha256,inventory,notes,family; final URI url; final long size; final int schema,minimum;
        Release(Properties p,String prefix)throws IOException {
            id=required(p,prefix+"id");version=required(p,prefix+"lab.version");ai=required(p,prefix+"ai.version");xmage=required(p,prefix+"xmage.version");
            sha256=required(p,prefix+"zip.sha256");inventory=required(p,prefix+"inventory.sha256");family=required(p,prefix+"data.family");
            if(!id.matches("[a-z0-9][a-z0-9._-]{0,79}")||id.equals(VersionManager.ORIGINAL)||!sha256.matches("[a-f0-9]{64}")||!inventory.matches("[a-f0-9]{64}"))throw new IOException("Identità della release non valida.");
            try{size=Long.parseLong(required(p,prefix+"zip.size"));schema=Integer.parseInt(required(p,prefix+"data.schema"));minimum=Integer.parseInt(required(p,prefix+"launcher.minimum"));}
            catch(NumberFormatException e){throw new IOException("Requisiti della release non validi.",e);}
            if(size<=0||size>ArchiveFiles.MAX_BYTES||schema<1||minimum<1)throw new IOException("Dimensione o requisiti della release non validi.");
            String location=p.getProperty(prefix+"url","").trim();url=location.isEmpty()?null:UpdateNetwork.https(location);
            notes=p.getProperty(prefix+"notes","");if(notes.length()>4000)throw new IOException("Note della release troppo lunghe.");
        }
        boolean supported(){return schema==5&&family.equals("lab9.1-ai6")&&minimum<=3;}
        String label(){return "Lab "+version+" · IA "+ai+" · base XMage "+xmage;}
    }
    final long serial; final List<Release> releases; final byte[] encoded;
    private LabCatalog(long serial,List<Release> releases,byte[] bytes){this.serial=serial;this.releases=Collections.unmodifiableList(releases);encoded=bytes.clone();}
    static String required(Properties p,String key)throws IOException {
        String v=p.getProperty(key,"").trim();if(v.isEmpty()||v.length()>1024||v.matches("(?s).*[\\p{Cntrl}].*"))throw new IOException("Campo del catalogo assente o non valido: "+key);return v;
    }
    static PublicKey publicKey()throws IOException {
        try(InputStream in=LabCatalog.class.getResourceAsStream("/lab-updates-public.der")) {
            if(in==null)throw new IOException("Chiave pubblica degli aggiornamenti mancante.");
            ByteArrayOutputStream b=new ByteArrayOutputStream();byte[] bytes=new byte[4096];int n;while((n=in.read(bytes))!=-1)b.write(bytes,0,n);
            return KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(b.toByteArray()));
        } catch(GeneralSecurityException e){throw new IOException("Chiave degli aggiornamenti non valida.",e);}
    }
    static LabCatalog verify(byte[] bytes,PublicKey key)throws IOException {
        if(bytes.length>LIMIT)throw new IOException("Catalogo troppo grande.");
        try {
            Properties envelope=new Properties();envelope.load(new ByteArrayInputStream(bytes));
            if(!"1".equals(envelope.getProperty("catalog.schema")))throw new IOException("Formato del catalogo non supportato.");
            byte[] payload=Base64.getDecoder().decode(envelope.getProperty("payload","")),sig=Base64.getDecoder().decode(envelope.getProperty("signature",""));
            Signature verifier=Signature.getInstance("SHA256withRSA");verifier.initVerify(key);verifier.update(payload);
            if(!verifier.verify(sig))throw new IOException("Firma del catalogo non valida. Aggiornamento rifiutato.");
            Properties p=new Properties();p.load(new InputStreamReader(new ByteArrayInputStream(payload),StandardCharsets.UTF_8));
            if(!"xmage-laboratorio".equals(p.getProperty("product")))throw new IOException("Il catalogo appartiene a un altro prodotto.");
            long serial=Long.parseLong(required(p,"serial"));int count=Integer.parseInt(required(p,"release.count"));
            if(serial<1||count<1||count>100)throw new IOException("Numero di release non valido.");
            List<Release> releases=new ArrayList<>();Set<String> ids=new HashSet<>();
            for(int i=0;i<count;i++){Release r=new Release(p,"release."+i+".");if(!ids.add(r.id))throw new IOException("Release duplicata nel catalogo.");releases.add(r);}
            return new LabCatalog(serial,releases,bytes);
        } catch(GeneralSecurityException|IllegalArgumentException e){throw new IOException("Catalogo non autentico o malformato.",e);}
    }
    void mergeInto(Properties trust)throws IOException {
        for(Release r:releases)if(r.supported()) {
            for(String suffix:new String[]{".zip",".inventory"}) {
                String value=suffix.equals(".zip")?r.sha256:r.inventory,old=trust.getProperty(r.id+suffix);
                if(old!=null&&!old.equals(value))throw new IOException("Il catalogo tenta di sostituire una release già identificata: "+r.id);
            }
        }
        for(Release r:releases)if(r.supported()){trust.setProperty(r.id+".zip",r.sha256);trust.setProperty(r.id+".inventory",r.inventory);}
    }
    static final class Store {
        final Path folder; final PublicKey key;
        Store(Path home)throws IOException{this(home,publicKey());}
        Store(Path home,PublicKey key){folder=home.resolve("launcher/cataloghi");this.key=key;}
        List<LabCatalog> all()throws IOException {
            List<LabCatalog> list=new ArrayList<>();
            // The bootstrap catalog has no remote URL; retained legacy ZIPs remain importable.
            try(InputStream in=LabCatalog.class.getResourceAsStream("/lab-catalog-iniziale.properties")) {
                if(in!=null&&Arrays.equals(key.getEncoded(),publicKey().getEncoded())) {
                    ByteArrayOutputStream b=new ByteArrayOutputStream();byte[] buf=new byte[8192];int n;
                    while((n=in.read(buf))!=-1){if(b.size()+n>LIMIT)throw new IOException("Catalogo iniziale troppo grande.");b.write(buf,0,n);}list.add(verify(b.toByteArray(),key));
                }
            }
            if(Files.isSymbolicLink(folder))throw new IOException("Cartella cataloghi non valida.");
            if(Files.isDirectory(folder))try(DirectoryStream<Path> stream=Files.newDirectoryStream(folder,"catalogo-*.properties")) {
                for(Path p:stream){if(Files.isSymbolicLink(p)||!Files.isRegularFile(p)||Files.size(p)>LIMIT)throw new IOException("Catalogo salvato non valido.");list.add(verify(Files.readAllBytes(p),key));}
            }
            Collections.sort(list,(a,b)->Long.compare(a.serial,b.serial));return list;
        }
        LabCatalog latest()throws IOException{List<LabCatalog> list=all();return list.isEmpty()?null:list.get(list.size()-1);}
        void mergeInto(Properties trust)throws IOException{for(LabCatalog c:all())c.mergeInto(trust);}
        LabCatalog accept(byte[] bytes)throws IOException {
            LabCatalog c=verify(bytes,key);List<LabCatalog> old=all();
            Properties trust=VersionManager.trust();for(LabCatalog previous:old){previous.mergeInto(trust);
                if(previous.serial>c.serial)throw new IOException("Il servizio propone un catalogo precedente a quello già verificato.");
                if(previous.serial==c.serial&&!Arrays.equals(previous.encoded,c.encoded))throw new IOException("Due cataloghi diversi hanno lo stesso numero di pubblicazione.");}
            c.mergeInto(trust);Files.createDirectories(folder);
            Path target=folder.resolve("catalogo-"+c.serial+"-"+UpdateNetwork.hash(bytes)+".properties");
            if(!Files.exists(target))UpdateNetwork.atomic(target,bytes);return c;
        }
    }
}

package mage.lab.launcher;

import java.io.*;
import java.net.URI;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;

final class LabUpdates {
    final Path home,settings; final LabCatalog.Store store; final UpdateNetwork.Transport network;
    LabUpdates(Path home)throws IOException{this(home,new LabCatalog.Store(home),new UpdateNetwork.Https());}
    LabUpdates(Path home,LabCatalog.Store store,UpdateNetwork.Transport network){this.home=home;settings=home.resolve("launcher/canale-aggiornamenti.properties");this.store=store;this.network=network;}
    String channel()throws IOException{return Files.exists(settings)?ArchiveFiles.properties(settings).getProperty("url",""):"";}
    void channel(String value)throws IOException {
        value=value.trim();if(!value.isEmpty())UpdateNetwork.https(value);
        Properties p=new Properties();p.setProperty("url",value);ByteArrayOutputStream b=new ByteArrayOutputStream();p.store(b,"Canale pacchetti Laboratorio; ogni catalogo richiede firma verificata");UpdateNetwork.atomic(settings,b.toByteArray());
    }
    LabCatalog check()throws IOException {
        String url=channel();if(url.isEmpty())throw new IOException("Canale Laboratorio non ancora pubblicato/configurato. Puoi continuare a usare i pacchetti locali.");
        LabCatalog catalog=store.accept(network.read(UpdateNetwork.https(url),LabCatalog.LIMIT));
        Properties p=new Properties();p.setProperty("checked",java.time.Instant.now().toString());p.setProperty("url",url);
        ByteArrayOutputStream b=new ByteArrayOutputStream();p.store(b,"Ultimo controllo riuscito del catalogo firmato");UpdateNetwork.atomic(home.resolve("launcher/ultimo-controllo-laboratorio.properties"),b.toByteArray());return catalog;
    }
    LabCatalog importCatalog(Path file)throws IOException{if(Files.size(file)>LabCatalog.LIMIT)throw new IOException("Catalogo troppo grande.");return store.accept(Files.readAllBytes(file));}
    LabCatalog.Release latest()throws IOException{LabCatalog c=store.latest();return c==null?null:c.releases.get(0);}
    String install(LabCatalog.Release selected,VersionManager versions,ProcessSupervisor processes,Consumer<String> status)throws IOException {
        if(selected==null||!selected.supported())throw new IOException("Questa release richiede una nuova versione del launcher o una migrazione dati.");
        if(selected.url==null)throw new IOException("Per questa release non è stato pubblicato un download. Usa il pacchetto locale.");
        versions.requireIdle(processes);
        // Re-read the authenticated store at use time; a displayed value never grants trust by itself.
        LabCatalog.Release current=latest();
        if(current==null||!current.id.equals(selected.id)||!current.sha256.equals(selected.sha256)||!Objects.equals(current.url,selected.url))throw new IOException("Il catalogo è cambiato. Ricontrolla gli aggiornamenti.");
        Path downloads=home.resolve("launcher/download");Files.createDirectories(downloads);
        Path zip=downloads.resolve(selected.id+"-"+selected.sha256.substring(0,16)+".zip");
        if(!Files.exists(zip))network.download(selected.url,zip,selected.size,selected.sha256,status);
        if(Files.size(zip)!=selected.size||!Installation.sha256(zip).equals(selected.sha256))throw new IOException("Il pacchetto salvato è incompleto o alterato. Rimuovilo dalla cartella launcher/download e riprova.");
        versions.refreshCatalog(store);String id=versions.install(zip,processes,status);
        status.accept("Aggiornamento installato. Verifica e backup prima dell’attivazione…");
        versions.activate(id,processes,status);return id;
    }
}

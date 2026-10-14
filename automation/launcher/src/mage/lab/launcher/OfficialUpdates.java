package mage.lab.launcher;

import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

final class OfficialUpdates {
    static final URI SOURCE=URI.create("https://xmage.today/config.json");
    static final class Release {
        final String version; final URI update,full; final Instant checked; final boolean cached;
        Release(String v,URI u,URI f,Instant t,boolean c){version=v;update=u;full=f;checked=t;cached=c;}
        String describe(){return "XMage ufficiale: "+version+"\n"+(cached?"Ultimo controllo salvato: ":"Controllato: ")+checked+" · xmage.today";}
    }
    private final Path cache; private final UpdateNetwork.Transport network;
    OfficialUpdates(Path home){this(home,new UpdateNetwork.Https());}
    OfficialUpdates(Path home,UpdateNetwork.Transport n){cache=home.resolve("launcher/ultimo-controllo-ufficiale.properties");network=n;}
    static URI official(String text)throws IOException {
        URI u=UpdateNetwork.https(text);String host=u.getHost().toLowerCase(Locale.ROOT);
        if(!(host.equals("xmage.today")||host.equals("beta.xmage.today"))||!u.getPath().startsWith("/files/")||!u.getPath().endsWith(".zip"))
            throw new IOException("Il canale ufficiale indica una provenienza del pacchetto non riconosciuta.");return u;
    }
    static Release parse(byte[] json,Instant checked,boolean cached)throws IOException {
        Map<String,Object> root=UpdateJson.object(new String(json,StandardCharsets.UTF_8));Object x=root.get("XMage");
        if(!(x instanceof Map))throw new IOException("Sezione XMage assente dal canale ufficiale.");Map<?,?> m=(Map<?,?>)x;
        Object version=m.get("version"),location=m.get("location"),full=m.get("full");
        if(!(version instanceof String)||((String)version).isEmpty()||((String)version).length()>160||((String)version).matches("(?s).*[\\p{Cntrl}].*")||!(location instanceof String)||!(full instanceof String))
            throw new IOException("Descrizione della release ufficiale non valida.");
        return new Release((String)version,official((String)location),official((String)full),checked,cached);
    }
    Release check()throws IOException {
        byte[] json=network.read(SOURCE,128*1024);Instant now=Instant.now();Release r=parse(json,now,false);
        Properties p=new Properties();p.setProperty("checked",now.toString());p.setProperty("json",Base64.getEncoder().encodeToString(json));
        ByteArrayOutputStream b=new ByteArrayOutputStream();p.store(b,"Ultimo controllo ufficiale riuscito; non certifica compatibilita Laboratorio");UpdateNetwork.atomic(cache,b.toByteArray());return r;
    }
    Release cached()throws IOException {
        if(!Files.exists(cache))return null;Properties p=ArchiveFiles.properties(cache);
        try{return parse(Base64.getDecoder().decode(p.getProperty("json","")),Instant.parse(p.getProperty("checked","")),true);}
        catch(RuntimeException e){throw new IOException("Ultimo controllo salvato non leggibile.",e);}
    }
}

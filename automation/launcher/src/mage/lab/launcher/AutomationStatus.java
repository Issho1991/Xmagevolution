package mage.lab.launcher;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/** Informational health only. This class never grants trust or installs files. */
final class AutomationStatus {
    static final String CHANNEL="https://raw.githubusercontent.com/Issho1991/Xmagevolution/main/updates/catalog.properties";
    static final String URL="https://raw.githubusercontent.com/Issho1991/Xmagevolution/main/updates/automation-state.json";
    static final int LIMIT=16384;
    private final Path cache;
    private final UpdateNetwork.Transport network;
    AutomationStatus(Path home){this(home,new UpdateNetwork.Https());}
    AutomationStatus(Path home,UpdateNetwork.Transport network){this.cache=home.resolve("launcher/stato-servizio-aggiornamenti.json");this.network=network;}
    String check(String channel)throws IOException {
        if(!CHANNEL.equals(channel))return "Stato automatico disponibile per il canale Laboratorio ufficiale configurato.";
        byte[] data=network.read(UpdateNetwork.https(URL),LIMIT);
        String result=describe(data,Instant.now());
        UpdateNetwork.atomic(cache,data);
        return result;
    }
    String cached(String channel)throws IOException {
        if(!CHANNEL.equals(channel))return "Configura il canale Laboratorio per verificarne lo stato.";
        if(!Files.isRegularFile(cache))return "Stato del servizio non ancora verificato. Premi Controlla aggiornamenti.";
        if(Files.size(cache)>LIMIT)throw new IOException("Stato del servizio troppo grande.");
        return "Ultimo stato salvato — "+describe(Files.readAllBytes(cache),Instant.now());
    }
    static String describe(byte[] data,Instant now)throws IOException {
        if(data.length>LIMIT)throw new IOException("Stato del servizio troppo grande.");
        Map<String,Object> m=UpdateJson.object(new String(data,StandardCharsets.UTF_8));
        if(!"2".equals(m.get("schema"))||!"github-autonomous".equals(m.get("mode")))
            return "Servizio autonomo non ancora attivato. Gli aggiornamenti già pubblicati restano scaricabili.";
        String status=String.valueOf(m.get("status"));
        Instant checked;
        try{checked=Instant.parse(String.valueOf(m.get("lastCheckedAt")));}
        catch(RuntimeException e){throw new IOException("Data del controllo del servizio non valida.");}
        long age=Duration.between(checked,now).getSeconds();
        if(age < -600)throw new IOException("Data del servizio nel futuro: impossibile verificarne lo stato.");
        String date=DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm").withZone(ZoneId.systemDefault()).format(checked);
        String message;
        if("signing_not_configured".equals(status))message="Firma automatica da configurare: le prossime release non possono ancora essere pubblicate.";
        else if("blocked".equals(status))message="Integrazione o test non riusciti. Rimane disponibile l’ultima versione verificata.";
        else if("publication_failed".equals(status))message="Pubblicazione da verificare. Controlla il catalogo per gli aggiornamenti disponibili.";
        else if("current".equals(status))message="Servizio operativo. La base ufficiale disponibile è già integrata.";
        else if("published".equals(status))message="Nuovo pacchetto pubblicato. La versione in uso è indicata sopra.";
        else if("ready".equals(status))message="Pacchetto verificato in attesa di pubblicazione.";
        else throw new IOException("Stato del servizio non riconosciuto.");
        String stale=age>72L*3600?"Attenzione: nessun controllo registrato da oltre 72 ore; il servizio potrebbe essere fermo.\n":"";
        return stale+"Ultimo controllo del servizio: "+date+"\n"+message;
    }
}

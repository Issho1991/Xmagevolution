package mage.lab.launcher;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.function.Consumer;

public final class AutomationStatusChecks {
    static byte[] state(String status,String checked){return ("{\"schema\":2,\"mode\":\"github-autonomous\",\"status\":\""+status+"\",\"lastCheckedAt\":\""+checked+"\"}").getBytes(StandardCharsets.UTF_8);}
    static void require(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    public static void main(String[] args)throws Exception {
        Instant now=Instant.parse("2026-10-18T12:00:00Z");
        require(AutomationStatus.describe(state("current","2026-10-18T11:00:00Z"),now).contains("operativo"),"Healthy status");
        require(AutomationStatus.describe(state("current","2026-10-14T11:00:00Z"),now).contains("oltre 72 ore"),"Stale status must warn");
        for(String s:new String[]{"blocked","publication_failed","signing_not_configured","ready","published"})
            require(!AutomationStatus.describe(state(s,"2026-10-18T11:00:00Z"),now).contains("Servizio operativo"),"Failure/pending cannot look healthy");
        for(byte[] bad:new byte[][]{state("unknown","2026-10-18T11:00:00Z"),state("current","bad"),state("current","2026-10-20T12:00:00Z"),new byte[AutomationStatus.LIMIT+1]}) {
            try{AutomationStatus.describe(bad,now);throw new AssertionError("Invalid state accepted");}catch(IOException expected){}
        }
        require(AutomationStatus.describe("{\"schema\":1}".getBytes(StandardCharsets.UTF_8),now).contains("non ancora attivato"),"Legacy state");
        Path folder=Files.createTempDirectory("lab-status-test");
        try {
            final int[] reads={0};
            UpdateNetwork.Transport transport=new UpdateNetwork.Transport(){
                public byte[] read(URI uri,int limit)throws IOException{reads[0]++;require(uri.toString().equals(AutomationStatus.URL),"Pinned state URL");return state("current",Instant.now().toString());}
                public void download(URI u,Path p,long n,String s,Consumer<String> c){throw new AssertionError("Status must never download a package");}
            };
            AutomationStatus a=new AutomationStatus(folder,transport);
            a.check("https://example.com/catalog.properties");require(reads[0]==0,"Custom channel must not borrow another channel status");
            a.check(AutomationStatus.CHANNEL);require(reads[0]==1,"Status requested once");
            require(a.cached(AutomationStatus.CHANNEL).contains("salvato"),"Offline cache");
            require(!Files.exists(folder.resolve("launcher/cataloghi")),"Status must not authorize catalog");
        } finally {
            try(java.util.stream.Stream<Path> files=Files.walk(folder)){files.sorted(java.util.Comparator.reverseOrder()).forEach(p->{try{Files.delete(p);}catch(IOException e){throw new RuntimeException(e);}});}
        }
        System.out.println("PASS service health, staleness, failures, offline cache, channel isolation, no installation trust");
    }
}

package mage.lab.launcher;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.function.Consumer;

/** HTTPS only, bounded redirects/time/bytes; incomplete downloads never become installable. */
final class UpdateNetwork {
    interface Transport {
        byte[] read(URI uri, int limit) throws IOException;
        void download(URI uri, Path target, long size, String sha256, Consumer<String> status) throws IOException;
    }
    static URI https(String value) throws IOException {
        try {
            URI u=new URI(value);
            if(!"https".equalsIgnoreCase(u.getScheme())||u.getHost()==null||u.getUserInfo()!=null
                    ||u.getFragment()!=null||u.getPort()==0||u.getPort()>65535) throw new IOException("L’indirizzo deve essere HTTPS, senza credenziali e con una porta valida.");
            return u;
        } catch(URISyntaxException e) { throw new IOException("Indirizzo di aggiornamento non valido.",e); }
    }
    static void atomic(Path target,byte[] bytes) throws IOException {
        Files.createDirectories(target.getParent());
        Path tmp=Files.createTempFile(target.getParent(),".update-",".partial");
        try { Files.write(tmp,bytes); ArchiveFiles.atomicMove(tmp,target,true); }
        finally { Files.deleteIfExists(tmp); }
    }
    static String hash(byte[] bytes) throws IOException {
        try { return hex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch(NoSuchAlgorithmException e) { throw new IOException(e); }
    }
    static String hex(byte[] b) { StringBuilder s=new StringBuilder(); for(byte v:b)s.append(String.format(Locale.ROOT,"%02x",v&255)); return s.toString(); }
    static final class Https implements Transport {
        private HttpURLConnection open(URI uri) throws IOException {
            for(int redirects=0;redirects<=4;redirects++) {
                https(uri.toString());
                HttpURLConnection c=(HttpURLConnection)uri.toURL().openConnection();
                c.setConnectTimeout(8000); c.setReadTimeout(12000); c.setInstanceFollowRedirects(false);
                c.setRequestProperty("User-Agent","XMage-Laboratorio-Launcher/0.3");
                c.setRequestProperty("Accept-Encoding","identity");
                try {
                    int code=c.getResponseCode();
                    if(code==301||code==302||code==303||code==307||code==308) {
                        String location=c.getHeaderField("Location");
                        if(location==null)throw new IOException("Reindirizzamento senza destinazione.");
                        uri=https(uri.resolve(location).toString()); c.disconnect(); continue;
                    }
                    if(code!=200)throw new IOException("Il servizio aggiornamenti risponde HTTP "+code+".");
                    return c;
                } catch(IOException|IllegalArgumentException e) { c.disconnect(); throw new IOException("Connessione aggiornamenti: "+e.getMessage(),e); }
            }
            throw new IOException("Troppi reindirizzamenti durante l’aggiornamento.");
        }
        public byte[] read(URI uri,int limit) throws IOException {
            HttpURLConnection c=open(uri);
            try {
                if(c.getContentLengthLong()>limit)throw new IOException("Risposta del servizio troppo grande.");
                long started=System.nanoTime(); ByteArrayOutputStream out=new ByteArrayOutputStream();
                try(InputStream in=c.getInputStream()) { byte[] b=new byte[8192]; int n;
                    while((n=in.read(b))!=-1) {
                        if(n>limit-out.size()||System.nanoTime()-started>30000000000L)throw new IOException("Risposta incompleta o troppo grande.");
                        out.write(b,0,n);
                    }
                }
                if(c.getContentLengthLong()>=0&&c.getContentLengthLong()!=out.size())throw new IOException("Risposta interrotta.");
                return out.toByteArray();
            } finally { c.disconnect(); }
        }
        public void download(URI uri,Path target,long size,String sha256,Consumer<String> status) throws IOException {
            if(size<=0||size>ArchiveFiles.MAX_BYTES||!sha256.matches("[a-f0-9]{64}"))throw new IOException("Dimensione o impronta del download non valida.");
            Files.createDirectories(target.getParent());
            if(Files.getFileStore(target.getParent()).getUsableSpace()<size+64L*1024*1024)throw new IOException("Spazio insufficiente per il download.");
            Path part=Files.createTempFile(target.getParent(),".download-",".partial");
            HttpURLConnection c=null;
            try {
                c=open(uri);
                if(c.getContentLengthLong()>=0&&c.getContentLengthLong()!=size)throw new IOException("La dimensione pubblicata non corrisponde al pacchetto.");
                try(InputStream in=c.getInputStream(); OutputStream out=Files.newOutputStream(part)) { copyVerified(in,out,size,sha256,status); }
                ArchiveFiles.atomicMove(part,target,false);
            } finally { if(c!=null)c.disconnect(); Files.deleteIfExists(part); }
        }
    }
    static void copyVerified(InputStream in,OutputStream out,long size,String sha256,Consumer<String> status) throws IOException {
        try {
            MessageDigest digest=MessageDigest.getInstance("SHA-256"); byte[] b=new byte[65536]; long count=0,last=0,started=System.nanoTime(); int n;
            while((n=in.read(b))!=-1) {
                count+=n;
                if(count>size||System.nanoTime()-started>3600000000000L||Thread.currentThread().isInterrupted())throw new IOException("Download annullato, troppo lungo o dimensione incoerente.");
                out.write(b,0,n); digest.update(b,0,n);
                if(count-last>=8L*1024*1024) {status.accept("Download aggiornamento: "+(count*100/size)+"%");last=count;}
            }
            if(count!=size||!hex(digest.digest()).equals(sha256))throw new IOException("Download incompleto o alterato. Nessun file installato.");
        } catch(NoSuchAlgorithmException e) { throw new IOException(e); }
    }
}

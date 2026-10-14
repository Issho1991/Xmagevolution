package mage.lab.launcher;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;

/** Describes one explicitly supported distribution. Never rewrites its files. */
public final class Installation {
    public final Path root, home, data, launcherJar;
    public final Properties manifest;
    public Installation(Path root, Properties manifest) throws IOException {
        this(root, manifest, root);
    }
    public Installation(Path root, Properties manifest, Path home) throws IOException {
        this.root = root.toRealPath();
        this.home = home.toRealPath();
        this.manifest = manifest;
        this.data = this.home.resolve("laboratorio");
        this.launcherJar = this.home.resolve("launcher/laboratorio-launcher.jar");
        if (!"1".equals(manifest.getProperty("schema"))) throw new IOException("Descrizione della distribuzione non supportata.");
    }
    public static Installation load(Path root) throws IOException {
        return load(root, root);
    }
    public static Installation load(Path root, Path home) throws IOException {
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(root.resolve("launcher/distribuzione.properties"))) { p.load(in); }
        return new Installation(root, p, home);
    }
    public Path file(String relative) throws IOException {
        Path p = root.resolve(relative).normalize();
        if (!p.startsWith(root) || (Files.exists(p) && !p.toRealPath().startsWith(root)))
            throw new IOException("Percorso esterno alla distribuzione: " + relative);
        return p;
    }
    public String value(String key) throws IOException {
        String v = manifest.getProperty(key);
        if (v == null || v.trim().isEmpty()) throw new IOException("Descrizione incompleta: " + key);
        return v;
    }
    public static String sha256(Path path) throws IOException {
        try {
            MessageDigest d = MessageDigest.getInstance("SHA-256");
            try (InputStream in = Files.newInputStream(path)) {
                byte[] b = new byte[65536]; int n;
                while ((n = in.read(b)) != -1) d.update(b, 0, n);
            }
            StringBuilder result = new StringBuilder();
            for (byte b : d.digest()) result.append(String.format(Locale.ROOT, "%02x", b & 255));
            return result.toString();
        } catch (NoSuchAlgorithmException e) { throw new IOException(e); }
    }
    public int verify() throws IOException {
        if (!root.equals(home)) ArchiveFiles.verify(root, false);
        int count = Integer.parseInt(value("file.count"));
        if (count < 1 || count > 2000) throw new IOException("Elenco dei file non valido.");
        Set<Path> expected = new HashSet<>();
        for (int i = 0; i < count; i++) {
            String name = value("file." + i + ".path"); Path p = file(name); expected.add(p);
            if (!Files.isRegularFile(p)) throw new IOException("File mancante: " + name + "\nEstrai il launcher dentro la cartella XMage Laboratorio v9.1, accanto a mage-client e mage-server.");
            if (!sha256(p).equals(value("file." + i + ".sha256")))
                throw new IOException("File diverso dalla distribuzione verificata: " + name + "\nQuesta distribuzione richiede una verifica di compatibilità. I tuoi dati non sono stati modificati.");
        }
        // Duplicate or additional jars can change class loading even if every expected jar exists.
        for (String folder : Arrays.asList("mage-client/lib", "mage-client/plugins", "mage-server/lib", "mage-server/plugins")) {
            Path dir = file(folder);
            if (!Files.isDirectory(dir)) continue;
            try (java.util.stream.Stream<Path> files = Files.walk(dir)) {
                for (Iterator<Path> it = files.filter(p -> p.getFileName().toString().endsWith(".jar")).iterator(); it.hasNext();) {
                    Path p = it.next();
                    if (!expected.contains(p)) throw new IOException("JAR aggiuntivo non verificato: " + root.relativize(p));
                }
            }
        }
        if (!expected.contains(file(value("client.jar"))) || !expected.contains(file(value("server.jar"))))
            throw new IOException("Client o server non inclusi nella verifica.");
        endpoint();
        return count;
    }
    public int endpoint() throws IOException {
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            f.setFeature("http://xml.org/sax/features/external-general-entities", false);
            f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            f.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            f.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            Element server = (Element) f.newDocumentBuilder().parse(file("mage-server/config/config.xml").toFile()).getElementsByTagName("server").item(0);
            if (server == null || !"127.0.0.1".equals(server.getAttribute("serverAddress")))
                throw new IOException("Il launcher locale richiede serverAddress=127.0.0.1 nella configurazione del server.");
            int port = Integer.parseInt(server.getAttribute("port"));
            if (port < 1024 || port > 65535) throw new IOException("Porta del server non valida.");
            return port;
        } catch (IOException e) { throw e; }
        catch (Exception e) { throw new IOException("Configurazione del server non leggibile: " + e.getMessage(), e); }
    }
    public void verifyDataWritable() throws IOException {
        Files.createDirectories(data);
        Path probe = Files.createTempFile(data, "launcher-check-", ".tmp");
        Files.delete(probe);
    }
    public static boolean listening(int port) {
        try (Socket s = new Socket()) { s.connect(new InetSocketAddress("127.0.0.1", port), 250); return true; }
        catch (IOException e) { return false; }
    }
    public static void requireFreePort(int port) throws IOException {
        try (ServerSocket s = new ServerSocket()) {
            s.setReuseAddress(false); s.bind(new InetSocketAddress("127.0.0.1", port));
        } catch (IOException e) {
            throw new IOException("La porta " + port + " è già occupata. Potrebbe esserci un server XMage aperto.\nChiudilo dalla sua finestra al termine della partita, poi riprova. Nessun processo è stato terminato.", e);
        }
    }
    public static Path currentJava() { return Paths.get(System.getProperty("java.home"), "bin", System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java"); }
    public String verifyJava(Path java) throws IOException, InterruptedException {
        if (!Files.isRegularFile(java)) throw new IOException("Java non trovato: " + java);
        Path log = Files.createTempFile(home.resolve("launcher"), "java-check-", ".log");
        Process p = null;
        try {
            p = new ProcessBuilder(java.toString(), "-version").redirectErrorStream(true).redirectOutput(log.toFile()).start();
            if (!p.waitFor(10, TimeUnit.SECONDS)) { p.destroyForcibly(); throw new IOException("Java non risponde alla verifica."); }
            String s = new String(Files.readAllBytes(log), StandardCharsets.UTF_8);
            if (p.exitValue() != 0) throw new IOException("Java non si avvia. " + s);
            Matcher m = Pattern.compile("version\\s+\"(\\d+)(?:\\.(\\d+))?").matcher(s);
            if (!m.find()) throw new IOException("Versione Java non riconosciuta: " + s);
            int major = Integer.parseInt(m.group(1));
            if (major == 1) major = Integer.parseInt(m.group(2));
            if (major < 8) throw new IOException("Serve Java 8 o successivo.");
            return s.split("\\R")[0];
        } finally { if (p != null && p.isAlive()) p.destroyForcibly(); Files.deleteIfExists(log); }
    }
}

package mage.lab.launcher;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.channels.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** Owns only children started by this launcher. All blocking methods run off the EDT. */
public final class ProcessSupervisor implements AutoCloseable {
    public volatile Installation installation;
    private volatile Process server, client;
    private volatile Path serverLog, clientLog;
    private volatile int port;
    private FileChannel channel;
    private FileLock lock;
    public ProcessSupervisor(Installation i) { installation = i; }
    public void lock() throws IOException {
        channel = FileChannel.open(installation.home.resolve("launcher/session.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        try { lock = channel.tryLock(); } catch (OverlappingFileLockException e) { lock = null; }
        if (lock == null) { channel.close(); throw new IOException("Il launcher di questa cartella è già aperto."); }
    }
    public synchronized void use(Installation next) throws IOException {
        if (serverAlive() || clientAlive()) throw new IOException("Chiudi client e server prima di cambiare versione.");
        if (!next.home.equals(installation.home)) throw new IOException("Cartella dati diversa.");
        installation = next; serverLog = null; clientLog = null;
    }
    public boolean serverAlive() { Process p = server; return p != null && p.isAlive(); }
    public boolean clientAlive() { Process p = client; return p != null && p.isAlive(); }
    public Path serverLog() { return serverLog; }
    public Path clientLog() { return clientLog; }
    public int port() { return port; }
    public synchronized void start(Path java, Consumer<String> status) throws Exception {
        if (clientAlive()) throw new IOException("Il client avviato da questo launcher è già aperto.");
        status.accept("Verifica dei file della distribuzione…"); installation.verify();
        installation.verifyDataWritable();
        status.accept("Verifica di Java…"); installation.verifyJava(java);
        port = installation.endpoint();
        if (!serverAlive()) {
            Installation.requireFreePort(port);
            Path logs = installation.home.resolve("launcher/logs"); Files.createDirectories(logs);
            serverLog = Files.createTempFile(logs, "server-", ".log");
            server = new ProcessBuilder(serverCommand(java)).directory(installation.root.resolve("mage-server").toFile())
                    .redirectErrorStream(true).redirectOutput(serverLog.toFile()).start();
        }
        status.accept("Avvio del server locale…");
        awaitReady(server, serverLog, port, 120000);
        status.accept("Server pronto. Avvio del client…");
        clientLog = Files.createTempFile(installation.home.resolve("launcher/logs"), "client-", ".log");
        client = new ProcessBuilder(clientCommand(java)).directory(installation.root.resolve("mage-client").toFile())
                .redirectErrorStream(true).redirectOutput(clientLog.toFile()).start();
        if (client.waitFor(1500, TimeUnit.MILLISECONDS)) throw new IOException("Il client si è chiuso durante l’avvio (codice " + client.exitValue() + "). Consulta il registro client.");
        status.accept("Client avviato. Connessione locale: 127.0.0.1:" + port + ".");
    }
    public List<String> serverCommand(Path java) throws IOException {
        return Arrays.asList(java.toString(), "-Xms1024m", "-Xmx4096m", "-Dfile.encoding=UTF-8", "-Djava.net.preferIPv4Stack=true",
            "-Dxmage.lab.record=true", "-Dxmage.lab.dir=" + installation.data, "-jar", installation.file(installation.value("server.jar")).toString());
    }
    public List<String> clientCommand(Path java) throws IOException {
        return Arrays.asList(java.toString(), "-Xmx3072m", "-Dfile.encoding=UTF-8", "-Djava.net.preferIPv4Stack=true",
            "-Dxmage.lab.dir=" + installation.data, "-Dlab.launcher.port=" + port, "-Dlab.launcher.assets.base=" + installation.home.resolve("mage-client"),
            "-cp", installation.launcherJar + File.pathSeparator + installation.file(installation.value("client.jar")), ClientBootstrap.class.getName());
    }
    static void awaitReady(Process process, Path log, int port, long timeoutMillis) throws IOException, InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        StringBuilder recent = new StringBuilder();
        boolean announced = false;
        try (RandomAccessFile reader = new RandomAccessFile(log.toFile(), "r")) {
            byte[] b = new byte[8192];
            while (System.nanoTime() < deadline) {
                if (!process.isAlive()) throw new IOException("Il server si è chiuso durante l’avvio (codice " + process.exitValue() + "). Consulta il registro server.");
                int n;
                while ((n = reader.read(b)) > 0) {
                    recent.append(new String(b, 0, n, StandardCharsets.UTF_8));
                    if (recent.indexOf("Started MAGE server - listening on 127.0.0.1:" + port) >= 0) announced = true;
                    if (recent.length() > 16384) recent.delete(0, recent.length() - 8192);
                }
                if (announced && process.isAlive() && Installation.listening(port)) return;
                Thread.sleep(250);
            }
        }
        throw new IOException("Il server non è ancora pronto dopo 2 minuti. Consulta il registro e riprova.\nIl processo avviato resta sotto il controllo del launcher.");
    }
    public synchronized void stop() throws InterruptedException {
        stopChild(client); stopChild(server);
    }
    private static void stopChild(Process p) throws InterruptedException {
        if (p == null || !p.isAlive()) return;
        p.destroy();
        if (!p.waitFor(8, TimeUnit.SECONDS)) { p.destroyForcibly(); p.waitFor(3, TimeUnit.SECONDS); }
    }
    public void close() throws IOException {
        if (lock != null && lock.isValid()) lock.release();
        if (channel != null && channel.isOpen()) channel.close();
    }
}

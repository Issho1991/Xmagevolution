package mage.lab.preview;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.io.*;
import java.net.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.prefs.Preferences;

/** A separate, deliberately non-updating test harness. It cannot activate a stable version. */
public final class PreviewLauncher {
    public static final int PORT = 17182;
    private final Path home;
    private final Path jar;
    private Process server, client;
    private FileChannel lockChannel;
    private FileLock lock;
    private final JFrame frame = new JFrame("XMage · Prova interfaccia 01");
    private final JLabel status = new JLabel("Pronto. Questa copia usa solo dati di prova.");
    private final JButton start = new JButton("Avvia la prova");
    private final JButton stop = new JButton("Chiudi client e server di prova");
    private final JRadioButton modern = new JRadioButton("Interfaccia nuova", true);
    private final JRadioButton classic = new JRadioButton("Interfaccia classica (confronto)");

    private PreviewLauncher(Path jar) throws Exception {
        this.jar = jar; home = jar.getParent().getParent();
        if (!Files.exists(home.resolve("ANTEPRIMA_INTERFACCIA_01.txt"))) throw new IOException("Cartella di prova incompleta");
        lockChannel = FileChannel.open(home.resolve("prova/session.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        lock = lockChannel.tryLock();
        if (lock == null) throw new IOException("Questa prova è già aperta");
        JPanel page = new JPanel(); page.setLayout(new BoxLayout(page, BoxLayout.Y_AXIS));
        page.setBorder(BorderFactory.createEmptyBorder(28, 28, 28, 28));
        JLabel title = new JLabel("Un’interfaccia da provare, con calma.");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 22)); page.add(title);
        page.add(Box.createVerticalStrut(16));
        page.add(new JLabel("Lab 10.0 · XMage 1.4.62 · IA 6 — copia separata"));
        page.add(Box.createVerticalStrut(20));
        ButtonGroup modes = new ButtonGroup(); modes.add(modern); modes.add(classic);
        page.add(modern); page.add(classic);
        page.add(Box.createVerticalStrut(14));
        page.add(new JLabel("Per tornare al tuo Laboratorio abituale, chiudi questa prova"));
        page.add(new JLabel("e apri il collegamento che usi normalmente."));
        page.add(Box.createVerticalStrut(20));
        JPanel folders = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        JButton decks = new JButton("Cartella mazzi di prova");
        decks.addActionListener(e -> open("mage-client/decks")); folders.add(decks);
        JButton images = new JButton("Cartella immagini di prova");
        images.addActionListener(e -> open("mage-client/plugins/images")); folders.add(images);
        page.add(folders);
        page.add(new JLabel("Puoi copiarci mazzi e immagini. Mantieni gli originali nella cartella abituale."));
        page.add(Box.createVerticalStrut(22));
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        actions.add(start); actions.add(stop); page.add(actions);
        page.add(Box.createVerticalStrut(12)); page.add(status);
        stop.setEnabled(false);
        start.addActionListener(e -> launch());
        stop.addActionListener(e -> requestStop(false));
        frame.setContentPane(page); frame.pack(); frame.setLocationRelativeTo(null);
        frame.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        frame.addWindowListener(new WindowAdapter() { public void windowClosing(WindowEvent e) { requestStop(true); } });
        Runtime.getRuntime().addShutdownHook(new Thread(() -> { terminate(client); terminate(server); }));
        frame.setVisible(true);
    }
    private void open(String relative) {
        try { Path p = home.resolve(relative); Files.createDirectories(p); Desktop.getDesktop().open(p.toFile()); }
        catch (Exception e) { JOptionPane.showMessageDialog(frame, e.getMessage()); }
    }
    private void busy(boolean value) {
        start.setEnabled(!value); modern.setEnabled(!value); classic.setEnabled(!value); stop.setEnabled(value);
    }
    private void launch() {
        final boolean preview = modern.isSelected(); busy(true); stop.setEnabled(false); status.setText("Avvio del server di prova…");
        new SwingWorker<Void, Void>() {
            protected Void doInBackground() throws Exception {
                try (ServerSocket socket = new ServerSocket()) { socket.bind(new InetSocketAddress("127.0.0.1", PORT)); }
                Files.createDirectories(home.resolve("prova/logs"));
                Files.createDirectories(home.resolve("prova/home"));
                server = startChild(command(home, jar, false, preview), "mage-server", "server");
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(100);
                boolean ready = false;
                while (System.nanoTime() < deadline && server.isAlive()) {
                    try (Socket socket = new Socket()) {
                        socket.connect(new InetSocketAddress("127.0.0.1", PORT), 300); ready = true; break;
                    } catch (IOException e) { Thread.sleep(250); }
                }
                if (!ready || !server.isAlive()) throw new IOException("Il server non è pronto. Leggi prova/logs/server.log.");
                client = startChild(command(home, jar, true, preview), "mage-client", preview ? "client-nuovo" : "client-classico");
                return null;
            }
            protected void done() {
                try {
                    get(); stop.setEnabled(true); status.setText("Prova aperta. Chiudila prima di cambiare interfaccia.");
                } catch (Exception e) {
                    terminate(client); terminate(server); busy(false);
                    status.setText("Avvio non riuscito: controlla i log nella cartella prova/logs.");
                    JOptionPane.showMessageDialog(frame, e.getCause() == null ? e.getMessage() : e.getCause().getMessage(), "Avvio della prova", JOptionPane.ERROR_MESSAGE);
                }
            }
        }.execute();
    }
    private Process startChild(java.util.List<String> cmd, String cwd, String log) throws IOException {
        return new ProcessBuilder(cmd).directory(home.resolve(cwd).toFile()).redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(home.resolve("prova/logs/" + log + ".log").toFile())).start();
    }
    public static java.util.List<String> command(Path home, Path jar, boolean client, boolean preview) {
        String java = Paths.get(System.getProperty("java.home"), "bin", System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString();
        java.util.List<String> cmd = new ArrayList<>(Arrays.asList(java, "-Xmx" + (client ? "3G" : "4G"), "-Dfile.encoding=UTF-8", "-Djava.net.preferIPv4Stack=true",
                "-Duser.home=" + home.resolve("prova/home"), "-Dxmage.lab.dir=" + home.resolve("laboratorio"),
                "-Djava.util.prefs.PreferencesFactory=mage.lab.preview.PrivatePreferences", "-Dlab.preview.preferences=" + home.resolve("prova/preferenze")));
        // JBoss Remoting's Java serialization needs this package on modular JVMs.
        if (!System.getProperty("java.specification.version").startsWith("1."))
            cmd.add("--add-opens=java.base/java.io=ALL-UNNAMED");
        if (client) {
            cmd.add("-Dxmage.lab.ui.preview=" + preview);
            cmd.add("-cp"); cmd.add(jar + File.pathSeparator + home.resolve("mage-client/lib/mage-client-1.4.62.jar"));
            cmd.add(PreviewLauncher.class.getName()); cmd.add("--client");
        } else {
            cmd.add("-Dxmage.lab.record=true");
            cmd.add("-cp"); cmd.add(jar + File.pathSeparator + home.resolve("mage-server/lib/mage-server-1.4.62.jar"));
            cmd.add("mage.server.Main");
        }
        return cmd;
    }
    private void requestStop(boolean exit) {
        if (!start.isEnabled() && !stop.isEnabled()) return; // startup/stop still in flight
        boolean active = (client != null && client.isAlive()) || (server != null && server.isAlive());
        if (active && JOptionPane.showConfirmDialog(frame, "Chiudere client e server della prova? Le partite in corso termineranno.", "Chiudi prova", JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return;
        start.setEnabled(false); stop.setEnabled(false); status.setText("Chiusura della prova…");
        new SwingWorker<Void, Void>() {
            protected Void doInBackground() { terminate(client); terminate(server); return null; }
            protected void done() {
                busy(false); status.setText("Prova chiusa. Puoi cambiare interfaccia o riaprire il tuo Laboratorio abituale.");
                if (exit) { frame.dispose(); System.exit(0); }
            }
        }.execute();
    }
    private static void terminate(Process p) {
        if (p == null || !p.isAlive()) return;
        p.destroy();
        try { if (!p.waitFor(8, TimeUnit.SECONDS)) { p.destroyForcibly(); p.waitFor(8, TimeUnit.SECONDS); } }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
    public static void main(String[] args) throws Exception {
        if (args.length == 1 && args[0].equals("--client")) {
            Preferences p = Preferences.userRoot().node("/mage/client");
            p.put("serverAddress", "127.0.0.1"); p.putInt("serverPort", PORT);
            p.put("127.0.0.1/userName", "prova_lab"); p.putBoolean("autoConnect", true); p.flush();
            Class.forName("mage.client.MageFrame").getMethod("main", String[].class).invoke(null, (Object) new String[0]);
            return;
        }
        Path jar = Paths.get(PreviewLauncher.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toAbsolutePath();
        Path home = jar.getParent().getParent();
        System.setProperty("java.util.prefs.PreferencesFactory", PrivatePreferences.class.getName());
        System.setProperty("lab.preview.preferences", home.resolve("prova/preferenze-avviatore").toString());
        System.setProperty("user.home", home.resolve("prova/home-avviatore").toString());
        SwingUtilities.invokeLater(() -> {
            try { new PreviewLauncher(jar); }
            catch (Exception e) { JOptionPane.showMessageDialog(null, e.getMessage(), "Anteprima XMage", JOptionPane.ERROR_MESSAGE); System.exit(1); }
        });
    }
}

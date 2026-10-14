package mage.lab.launcher;

import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.Callable;
import javax.swing.*;
import javax.swing.border.EmptyBorder;

public final class LauncherPanel extends JPanel {
    static final Color INK = new Color(24, 42, 60), MUTED = new Color(80, 99, 112), PAPER = new Color(246, 247, 248);
    private final Installation installation;
    private final ProcessSupervisor processes;
    private final VersionManager versions;
    private final OfficialUpdates officialUpdates;
    private final LabUpdates labUpdates;
    private final JTextArea officialStatus=text("Controllo ufficiale non ancora eseguito.",4);
    private final JTextArea labStatus=text("",4), releaseNotes=text("",3);
    private final JLabel updateBase=label("",14);
    private final JButton checkUpdates=new JButton("Controlla aggiornamenti"), installUpdate=new JButton("Scarica e aggiorna Laboratorio");
    private final JButton configureChannel=new JButton("Imposta canale…"), catalogImport=new JButton("Importa catalogo firmato…");
    private LabCatalog.Release availableUpdate;
    private final JLabel currentVersion = label("", 16);
    private final JComboBox<VersionChoice> choices = new JComboBox<>();
    private final JButton importPackage = new JButton("Importa pacchetto…"), activate = new JButton("Usa versione selezionata");
    private final JButton previous = new JButton("Torna alla precedente"), backup = new JButton("Crea backup"), recover = new JButton("Recupera copia…");
    private static final class VersionChoice {
        final String id, label;
        VersionChoice(String id, String label) { this.id=id; this.label=label; }
        public String toString() { return label; }
    }
    private final JLabel status = new JLabel("Pronto per la verifica e l’avvio");
    private final JLabel serverState = new JLabel("Server: non avviato"), clientState = new JLabel("Client: non avviato");
    private final JButton play = new JButton("Avvia Laboratorio") {
        protected void paintComponent(Graphics g) {
            g.setColor(isEnabled() ? INK : new Color(120, 130, 140)); g.fillRect(0, 0, getWidth(), getHeight()); super.paintComponent(g);
        }
    }, stop = new JButton("Chiudi client e server");
    private final JButton check = new JButton("Verifica file installati"), javaButton = new JButton("Scegli Java…");
    private final JProgressBar progress = new JProgressBar();
    private final JTextArea journal = text("Le operazioni del launcher appariranno qui.\n", 12);
    private final JTextField javaPath = new JTextField();
    private volatile boolean busy;
    private Path javaExecutable;
    private javax.swing.Timer timer;

    public LauncherPanel(Installation i, ProcessSupervisor p) throws IOException {
        installation = i; processes = p; versions = new VersionManager(i.home);
        officialUpdates=new OfficialUpdates(i.home);labUpdates=new LabUpdates(i.home);
        javaExecutable = readJava();
        setLayout(new BorderLayout(0, 20)); setBorder(new EmptyBorder(25, 28, 22, 28)); setBackground(PAPER);
        JPanel heading = new JPanel(new BorderLayout()); heading.setOpaque(false);
        JPanel titles = column();
        JLabel title = new JLabel("XMage Laboratorio"); title.setFont(new Font("SansSerif", Font.BOLD, 29)); title.setForeground(INK);
        titles.add(title); titles.add(Box.createVerticalStrut(7));
        titles.add(label("Il tuo spazio per giocare, osservare e migliorare i mazzi.", 14));
        heading.add(titles, BorderLayout.CENTER);
        JLabel badge = label("LAUNCHER 0.3", 12); badge.setBorder(new EmptyBorder(5, 12, 5, 0)); heading.add(badge, BorderLayout.EAST);
        add(heading, BorderLayout.NORTH);
        JTabbedPane tabs = new JTabbedPane(); tabs.setFont(new Font("SansSerif", Font.PLAIN, 14));
        tabs.addTab("Avvio", home()); tabs.addTab("Aggiornamenti", updates()); tabs.addTab("Versioni e backup", versions()); tabs.addTab("Registri e diagnosi", logs());
        add(tabs, BorderLayout.CENTER);
        JPanel footer = new JPanel(new BorderLayout(12, 0)); footer.setOpaque(false);
        status.setForeground(INK); status.setFont(new Font("SansSerif", Font.PLAIN, 13)); footer.add(status);
        progress.setPreferredSize(new Dimension(95, 8)); progress.setVisible(false); footer.add(progress, BorderLayout.EAST); add(footer, BorderLayout.SOUTH);
        play.addActionListener(e -> work(() -> { processes.start(javaExecutable, this::say); return "Client avviato. Nel client scegli il tavolo o apri Laboratorio."; }));
        stop.addActionListener(e -> confirmStop(false));
        check.addActionListener(e -> work(() -> {
            int n = processes.installation.verify(); processes.installation.verifyDataWritable();
            String java = processes.installation.verifyJava(javaExecutable);
            return "Verifica completata: " + n + " JAR corrispondenti alla versione selezionata; dati scrivibili. " + java;
        }));
        javaButton.addActionListener(e -> chooseJava());
        javaPath.setText(javaExecutable.toString());
        importPackage.addActionListener(e -> importPackage());
        activate.addActionListener(e -> { VersionChoice c=(VersionChoice)choices.getSelectedItem(); if(c!=null) activate(c.id); });
        previous.addActionListener(e -> activate(versions.previousId()));
        backup.addActionListener(e -> work(() -> "Backup verificato: " + versions.backup(processes, this::say)));
        recover.addActionListener(e -> recoverBackup());
        checkUpdates.addActionListener(e -> checkUpdates());
        installUpdate.addActionListener(e -> installUpdate());
        configureChannel.addActionListener(e -> configureChannel());
        catalogImport.addActionListener(e -> importCatalog());
        choices.addActionListener(e -> refresh());
        try{OfficialUpdates.Release saved=officialUpdates.cached();if(saved!=null)officialStatus.setText(saved.describe()+"\nPremi Controlla aggiornamenti per verificare le novità.");}catch(IOException e){officialStatus.setText(e.getMessage());}
        refreshVersions();refreshUpdates(); refresh();
    }
    private JPanel home() throws IOException {
        JPanel page = page();
        currentVersion.setFont(currentVersion.getFont().deriveFont(Font.BOLD)); page.add(currentVersion); page.add(Box.createVerticalStrut(15));
        page.add(text("Avvia il server locale e poi il client. La ricerca dell’IA mantiene le impostazioni della v9.1 / AI6.", 2));
        page.add(Box.createVerticalStrut(15));
        JPanel actions = flow(); play.setFont(new Font("SansSerif", Font.BOLD, 16)); play.setPreferredSize(new Dimension(230, 43));
        play.setContentAreaFilled(false); play.setBorderPainted(false); play.setOpaque(false); play.setForeground(Color.WHITE); actions.add(play); actions.add(stop); page.add(actions);
        page.add(Box.createVerticalStrut(16));
        JPanel states = flow(); states.add(serverState); states.add(Box.createHorizontalStrut(24)); states.add(clientState); page.add(states);
        page.add(Box.createVerticalStrut(7));
        page.add(text("Il client viene aperto solo dopo il messaggio di disponibilità del server e la verifica della porta. Le preferenze di connessione puntano al server locale; premi Connect se richiesto dal client.", 3));
        page.add(Box.createVerticalStrut(14));
        page.add(label("I TUOI DATI", 12)); page.add(Box.createVerticalStrut(5));
        JTextField folder = new JTextField(installation.data.toString()); folder.setEditable(false); folder.setToolTipText(installation.data.toString());
        folder.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30)); folder.setAlignmentX(LEFT_ALIGNMENT); page.add(folder);
        JPanel folders = flow(); folders.add(button("Apri laboratorio", () -> openFolder(installation.data, true)));
        folders.add(button("Miei deck", () -> openFolder(installation.data.resolve("miei deck"), true)));
        folders.add(button("Report", () -> openFolder(installation.data.resolve("reports"), false))); page.add(folders);
        page.add(Box.createVerticalStrut(7));
        page.add(text("I report restano nella stessa cartella laboratorio anche quando cambi versione. La registrazione è attiva sul server avviato dal launcher e include le mani dei partecipanti.", 3));
        page.add(Box.createVerticalGlue()); return page;
    }
    private JPanel versions() throws IOException {
        JPanel page = page();
        page.add(label("Versioni del Laboratorio", 18)); page.add(Box.createVerticalStrut(8));
        page.add(text("Importa uno ZIP Laboratorio riconosciuto e scegli quale versione avviare. Il cambio crea prima un backup; mazzi e report restano nella cartella laboratorio.", 3));
        choices.setMaximumSize(new Dimension(Integer.MAX_VALUE, 32)); choices.setAlignmentX(LEFT_ALIGNMENT); page.add(choices);
        JPanel actions=flow(); actions.add(importPackage); actions.add(activate); actions.add(previous); page.add(actions);
        page.add(text("Pacchetti riconosciuti: Lab 9.1 e 9.2 / IA6. La 9.2 corregge il rollback e riduce allocazioni superflue, mantenendo gli stessi limiti di ricerca.", 3));
        page.add(Box.createVerticalStrut(8)); page.add(label("Backup e recupero", 18));
        JPanel copies=flow(); copies.add(backup); copies.add(button("Apri backup", () -> openFolder(versions.backups, true))); copies.add(recover); page.add(copies);
        page.add(text("Il recupero estrae una copia consultabile in launcher/recuperi. Non sostituisce i dati attivi. Le immagini restano nei loro percorsi e non sono incluse nei backup.", 3));
        page.add(Box.createVerticalGlue()); return page;
    }
    private JPanel updates() {
        JPanel page=page();page.add(label("Aggiornamenti XMage e Laboratorio",18));page.add(Box.createVerticalStrut(8));page.add(updateBase);
        page.add(Box.createVerticalStrut(10));page.add(officialStatus);
        JPanel checkRow=flow();checkRow.add(checkUpdates);checkRow.add(button("Novità ufficiali",()->{
            if(!Desktop.isDesktopSupported()||!Desktop.getDesktop().isSupported(Desktop.Action.BROWSE))throw new IOException("Apri https://github.com/magefree/mage/releases");
            Desktop.getDesktop().browse(URI.create("https://github.com/magefree/mage/releases"));
        }));page.add(checkRow);
        page.add(Box.createVerticalStrut(12));page.add(label("Aggiornamento compatibile con il Laboratorio",17));page.add(labStatus);page.add(releaseNotes);
        JPanel installRow=flow();installRow.add(installUpdate);page.add(installRow);
        page.add(text("Una nuova release ufficiale viene installata nel Laboratorio dopo l’integrazione e le verifiche. L’aggiornamento crea un backup e conserva mazzi e report; puoi tornare alla versione precedente.",3));
        JPanel channelRow=flow();channelRow.add(configureChannel);channelRow.add(catalogImport);page.add(channelRow);
        page.add(Box.createVerticalGlue());return page;
    }
    private void refreshUpdates() {
        try {
            updateBase.setText("In uso: Lab "+processes.installation.value("lab.version")+" · base XMage "+processes.installation.value("xmage.version"));
            availableUpdate=labUpdates.latest();String channel=labUpdates.channel();
            if(availableUpdate==null) {labStatus.setText(channel.isEmpty()?"Canale Laboratorio non ancora pubblicato/configurato.\nResta disponibile l’importazione dei pacchetti locali in Versioni e backup.":"Canale Laboratorio configurato. Premi Controlla aggiornamenti.");releaseNotes.setText("");}
            else {
                String state=!availableUpdate.supported()?"Richiede un launcher più recente o una migrazione dati.":versions.installed().containsKey(availableUpdate.id)?"Versione già installata; selezionabile in Versioni e backup.":availableUpdate.url==null?"Download non ancora pubblicato. Puoi importare lo ZIP locale.":"Pacchetto verificato disponibile per il download.";
                labStatus.setText(availableUpdate.label()+"\n"+state+(channel.isEmpty()?"\nCanale online non configurato.":"\nCatalogo firmato salvato; Controlla aggiornamenti verifica nuove pubblicazioni."));releaseNotes.setText(availableUpdate.notes);
            }
        } catch(IOException e) {availableUpdate=null;labStatus.setText("Catalogo da verificare: "+e.getMessage());}
    }
    private void checkUpdates() {
        work(()->{
            try{OfficialUpdates.Release r=officialUpdates.check();SwingUtilities.invokeLater(()->officialStatus.setText(r.describe()+"\nLa disponibilità ufficiale non certifica la compatibilità con il Laboratorio."));say("Canale ufficiale controllato: "+r.version);}
            catch(IOException e){final String message=e.getMessage();SwingUtilities.invokeLater(()->{
                try{OfficialUpdates.Release cached=officialUpdates.cached();officialStatus.setText("Controllo online non riuscito: "+message+"\n"+(cached==null?"Nessun controllo precedente disponibile.":cached.describe()));}
                catch(IOException ignored){officialStatus.setText("Controllo online non riuscito: "+message);}
            });say("Controllo ufficiale non riuscito: "+message);}
            String result;
            if(labUpdates.channel().isEmpty())result="Controllo concluso. Il canale di distribuzione Laboratorio deve ancora essere pubblicato/configurato.";
            else try{LabCatalog c=labUpdates.check();versions.refreshCatalog();result="Catalogo Laboratorio verificato: pubblicazione "+c.serial+".";}
            catch(IOException e){result="Controllo Laboratorio non riuscito: "+e.getMessage()+" La versione installata resta disponibile.";}
            SwingUtilities.invokeLater(()->{refreshUpdates();refresh();});return result;
        });
    }
    private void configureChannel() {
        try {
            String value=JOptionPane.showInputDialog(this,"Indirizzo HTTPS del catalogo Laboratorio pubblicato.\nQuesto canale è distinto dal sito ufficiale XMage.\nLascia vuoto per disattivare il controllo Laboratorio online.",labUpdates.channel());
            if(value==null)return;labUpdates.channel(value);refreshUpdates();refresh();
        }catch(IOException e){error(e.getMessage());}
    }
    private void importCatalog() {
        JFileChooser chooser=new JFileChooser();chooser.setDialogTitle("Scegli il catalogo Laboratorio firmato");
        if(chooser.showOpenDialog(this)!=JFileChooser.APPROVE_OPTION)return;Path selected=chooser.getSelectedFile().toPath();
        work(()->{LabCatalog c=labUpdates.importCatalog(selected);versions.refreshCatalog();SwingUtilities.invokeLater(()->{refreshVersions();refreshUpdates();});return "Catalogo firmato riconosciuto: "+c.serial+". Ora puoi importare i pacchetti indicati.";});
    }
    private void installUpdate() {
        final LabCatalog.Release r=availableUpdate;if(r==null)return;
        if(JOptionPane.showConfirmDialog(this,"Scaricare e attivare "+r.label()+"?\n\nPrima dell’attivazione verrà creato un backup.\nTermina la partita e chiudi client e server.","Aggiornamento Laboratorio",JOptionPane.OK_CANCEL_OPTION)!=JOptionPane.OK_OPTION)return;
        work(()->{String id=labUpdates.install(r,versions,processes,this::say);SwingUtilities.invokeLater(()->{refreshVersions();refreshUpdates();});return "Aggiornamento attivato: "+versions.label(id)+". Puoi avviare Laboratorio.";});
    }
    private void refreshVersions() {
        try {
            String selected=versions.activeId(); choices.removeAllItems();
            for(Map.Entry<String,String> e:versions.installed().entrySet()) {
                VersionChoice c=new VersionChoice(e.getKey(),e.getValue()); choices.addItem(c);
                if(e.getKey().equals(selected))choices.setSelectedItem(c);
            }
            currentVersion.setText(versions.label(selected));
        } catch(IOException e) { say("Versioni da verificare: "+e.getMessage()); }
    }
    private void importPackage() {
        JFileChooser chooser=new JFileChooser(); chooser.setDialogTitle("Scegli il pacchetto Laboratorio ZIP");
        chooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("Pacchetto Laboratorio (*.zip)", "zip"));
        if(chooser.showOpenDialog(this)!=JFileChooser.APPROVE_OPTION)return;
        Path file=chooser.getSelectedFile().toPath();
        work(() -> {
            String id=versions.install(file,processes,this::say);
            SwingUtilities.invokeLater(() -> { refreshVersions(); for(int n=0;n<choices.getItemCount();n++)if(choices.getItemAt(n).id.equals(id))choices.setSelectedIndex(n); });
            return "Pacchetto installato. Premi Usa versione selezionata per attivarlo con backup.";
        });
    }
    private void activate(String id) {
        if(id.isEmpty())return;
        try {
            String label=versions.label(id);
            if(JOptionPane.showConfirmDialog(this,"Attivare "+label+"?\n\nVerrà prima creato un backup di mazzi, report e impostazioni.\nI report più recenti rimangono disponibili anche tornando indietro.","Cambio versione",JOptionPane.OK_CANCEL_OPTION)!=JOptionPane.OK_OPTION)return;
            work(() -> { Path saved=versions.activate(id,processes,this::say); SwingUtilities.invokeLater(()->{refreshVersions();refreshUpdates();}); return "Versione attivata. Backup: "+saved.getFileName()+". Ora puoi avviare Laboratorio."; });
        } catch(IOException e) { error(e.getMessage()); }
    }
    private void recoverBackup() {
        JFileChooser chooser=new JFileChooser(versions.backups.toFile()); chooser.setDialogTitle("Scegli il backup da recuperare in una nuova cartella");
        chooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("Backup del launcher (*.zip)", "zip"));
        if(chooser.showOpenDialog(this)!=JFileChooser.APPROVE_OPTION)return;
        Path zip=chooser.getSelectedFile().toPath();
        work(() -> { Path folder=versions.recover(zip,processes,this::say);
            SwingUtilities.invokeLater(() -> { try { open(folder); } catch(Exception e) { error("Copia recuperata: "+folder); } });
            return "Copia recuperata: "+folder+". Dati attivi invariati."; });
    }
    private JPanel logs() {
        JPanel page = new JPanel(new BorderLayout(0, 12)); page.setBorder(new EmptyBorder(20, 20, 18, 20)); page.setBackground(Color.WHITE);
        journal.setFont(new Font("Monospaced", Font.PLAIN, 12)); journal.setLineWrap(false);
        page.add(new JScrollPane(journal), BorderLayout.CENTER);
        JPanel actions = flow();
        actions.add(button("Registro server", () -> openLog(processes.serverLog())));
        actions.add(button("Registro client", () -> openLog(processes.clientLog())));
        actions.add(button("Cartella registri", () -> openFolder(installation.home.resolve("launcher/logs"), true)));
        actions.add(button("Copia riepilogo", () -> Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(journal.getText()), null)));
        JPanel bottom=column(); bottom.add(actions);
        JPanel controls=flow(); controls.add(check); controls.add(javaButton); bottom.add(controls);
        javaPath.setEditable(false); javaPath.setAlignmentX(LEFT_ALIGNMENT); bottom.add(javaPath);
        bottom.add(text("Java della v9.1 · Client massimo 3 GB, server 4 GB · Limiti dell’IA invariati.", 2));
        page.add(bottom, BorderLayout.SOUTH); return page;
    }
    public void monitor() { timer = new javax.swing.Timer(1500, e -> refresh()); timer.start(); }
    private void refresh() {
        boolean s = processes.serverAlive(), c = processes.clientAlive();
        serverState.setText(s ? "Server: processo attivo" : "Server: non avviato o chiuso");
        clientState.setText(c ? "Client: processo attivo" : "Client: non avviato o chiuso");
        play.setEnabled(!busy && !c); stop.setEnabled(!busy && (s || c)); check.setEnabled(!busy); javaButton.setEnabled(!busy && !s && !c);
        boolean idle=!busy&&!s&&!c;
        checkUpdates.setEnabled(!busy);configureChannel.setEnabled(!busy);catalogImport.setEnabled(idle);
        boolean downloadable=availableUpdate!=null&&availableUpdate.supported()&&availableUpdate.url!=null;
        try{if(downloadable&&versions.installed().containsKey(availableUpdate.id))downloadable=false;}catch(IOException e){downloadable=false;}
        installUpdate.setEnabled(idle&&downloadable);
        importPackage.setEnabled(idle); choices.setEnabled(idle); backup.setEnabled(idle); recover.setEnabled(idle);
        VersionChoice choice=(VersionChoice)choices.getSelectedItem();
        activate.setEnabled(idle&&choice!=null&&!choice.id.equals(versions.activeId()));
        previous.setEnabled(idle&&!versions.previousId().isEmpty());
    }
    private void work(Callable<String> task) {
        if (busy) return; busy = true; refresh(); progress.setVisible(true); progress.setIndeterminate(true);
        new SwingWorker<String, Void>() {
            protected String doInBackground() throws Exception { return task.call(); }
            protected void done() {
                busy = false; progress.setIndeterminate(false); progress.setVisible(false); refresh();
                try { say(get()); } catch (Exception e) { Throwable cause = e.getCause() == null ? e : e.getCause(); error(cause.getMessage()); }
            }
        }.execute();
    }
    private void say(String message) {
        if (!SwingUtilities.isEventDispatchThread()) { SwingUtilities.invokeLater(() -> say(message)); return; }
        status.setText(message.length() > 104 ? message.substring(0, 101) + "…" : message); status.setToolTipText(message);
        journal.append(new SimpleDateFormat("HH:mm:ss").format(new Date()) + "  " + message + "\n");
        if (journal.getDocument().getLength() > 30000) journal.setText(journal.getText().substring(15000));
        journal.setCaretPosition(journal.getDocument().getLength());
    }
    private void error(String message) {
        say("Attenzione: " + message);
        JTextArea details = text(message, 6); details.setColumns(65);
        JOptionPane.showMessageDialog(this, new JScrollPane(details), "Operazione da verificare", JOptionPane.WARNING_MESSAGE);
    }
    public void requestClose() {
        if (busy) { JOptionPane.showMessageDialog(this, "Attendi il completamento dell’operazione in corso."); return; }
        if (processes.serverAlive() || processes.clientAlive()) confirmStop(true); else dispose();
    }
    private void confirmStop(boolean close) {
        String message = "La chiusura interrompe eventuali partite aperte.\nTermina prima la partita dal client.\n\nChiudere i processi avviati da questo launcher?";
        Object[] options = {"Torna al launcher", "Chiudi client e server"};
        if (JOptionPane.showOptionDialog(this, message, "Chiusura della sessione", JOptionPane.DEFAULT_OPTION,
                JOptionPane.WARNING_MESSAGE, null, options, options[0]) != 1) return;
        work(() -> { processes.stop(); if (close) SwingUtilities.invokeLater(this::dispose); return "Client e server chiusi."; });
    }
    private void dispose() {
        if (timer != null) timer.stop();
        try { processes.close(); } catch (IOException e) { say(e.getMessage()); }
        Window w = SwingUtilities.getWindowAncestor(this); if (w != null) w.dispose();
    }
    private Path readJava() throws IOException {
        Path settings = installation.home.resolve("launcher/impostazioni.properties"); Properties p = new Properties();
        if (Files.exists(settings)) try (InputStream in = Files.newInputStream(settings)) { p.load(in); }
        String value = p.getProperty("java.path"); return value == null ? Installation.currentJava() : Paths.get(value);
    }
    private void chooseJava() {
        JFileChooser chooser = new JFileChooser(javaExecutable.getParent().toFile()); chooser.setDialogTitle("Seleziona java.exe della tua installazione XMage");
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        Path selected = chooser.getSelectedFile().toPath().toAbsolutePath();
        work(() -> {
            String version = installation.verifyJava(selected);
            Properties p = new Properties(); p.setProperty("java.path", selected.toString());
            Path target = installation.home.resolve("launcher/impostazioni.properties");
            Path tmp = Files.createTempFile(target.getParent(), "impostazioni-", ".tmp");
            try {
                try (OutputStream out = Files.newOutputStream(tmp)) { p.store(out, "Java per XMage Laboratorio"); }
                try { Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
                catch (AtomicMoveNotSupportedException e) { Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING); }
            } finally { Files.deleteIfExists(tmp); }
            SwingUtilities.invokeLater(() -> { javaExecutable = selected; javaPath.setText(selected.toString()); });
            return "Java selezionato: " + version;
        });
    }
    private void openFolder(Path p, boolean create) throws Exception {
        if (create) Files.createDirectories(p);
        if (!Files.isDirectory(p)) throw new IOException("La cartella non è ancora presente. I report vengono creati dal Laboratorio alla fine dell’incontro.");
        open(p);
    }
    private void openLog(Path p) throws Exception {
        if (p == null || !Files.isRegularFile(p)) throw new IOException("Nessun registro per questo processo nella sessione corrente."); open(p);
    }
    private void open(Path p) throws Exception {
        if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) throw new IOException("Apri questo percorso: " + p);
        Desktop.getDesktop().open(p.toFile());
    }
    private interface Action { void run() throws Exception; }
    private JButton button(String name, Action action) {
        JButton b = new JButton(name); b.addActionListener(e -> { try { action.run(); } catch (Exception x) { error(x.getMessage()); } }); return b;
    }
    private static JPanel column() { JPanel p = new JPanel(); p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS)); p.setOpaque(false); return p; }
    private static JPanel page() { JPanel p = column(); p.setOpaque(true); p.setBackground(Color.WHITE); p.setBorder(new EmptyBorder(20, 20, 18, 20)); return p; }
    private static JPanel flow() { JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 5)); p.setOpaque(false); p.setAlignmentX(LEFT_ALIGNMENT); p.setMaximumSize(new Dimension(Integer.MAX_VALUE, 55)); return p; }
    private static JLabel label(String s, int size) { JLabel l = new JLabel(s); l.setFont(new Font("SansSerif", Font.PLAIN, size)); l.setForeground(MUTED); l.setAlignmentX(LEFT_ALIGNMENT); return l; }
    private static JTextArea text(String s, int rows) {
        JTextArea a = new JTextArea(s, rows, 1); a.setEditable(false); a.setLineWrap(true); a.setWrapStyleWord(true); a.setOpaque(false); a.setForeground(MUTED);
        a.setFont(new Font("SansSerif", Font.PLAIN, 13)); a.setAlignmentX(LEFT_ALIGNMENT); a.setBorder(null);
        a.setMaximumSize(new Dimension(Integer.MAX_VALUE, rows * 21)); return a;
    }
}

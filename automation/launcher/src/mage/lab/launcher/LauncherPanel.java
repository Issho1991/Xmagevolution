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
    static final Color INK = new Color(25, 43, 48), MUTED = new Color(91, 107, 111), PAPER = new Color(242, 245, 243), ACCENT = new Color(20, 111, 94);
    private final Installation installation;
    private final ProcessSupervisor processes;
    private final VersionManager versions;
    private final OfficialUpdates officialUpdates;
    private final LabUpdates labUpdates;
    private final AutomationStatus automationStatus;
    private final LauncherUpdates launcherUpdates;
    private LauncherUpdates.Release launcherRelease;
    private final JLabel overview=label("Controllo da eseguire",22), overviewDetail=label("",13);
    private final JTextArea launcherStatus=text("Controllo da eseguire.",2);
    private final JButton updateLauncher=new JButton("Aggiorna launcher e riavvia"), homeUpdates=new JButton("Apri aggiornamenti");
    private final JCheckBox autoCheck=new JCheckBox("Controlla gli aggiornamenti all’apertura",true);
    private final CardLayout pagesLayout=new CardLayout();
    private final JPanel pages=new JPanel(pagesLayout);
    private final java.util.List<JButton> navigation=new ArrayList<>();
    private boolean checking, catalogChecked, launcherChecked;
    private String checkedAt="", checkFailure="";
    private final JTextArea serviceStatus=text("Stato del servizio non ancora verificato.",4);
    private final JTextArea officialStatus=text("Controllo ufficiale non ancora eseguito.",4);
    private final JTextArea labStatus=text("",4), releaseNotes=text("",3);
    private final JLabel updateBase=label("",14);
    private final JButton checkUpdates=new JButton("Controlla aggiornamenti"), installUpdate=new JButton("Aggiorna Laboratorio");
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
    private final JButton play = new JButton("Gioca") {
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
        officialUpdates=new OfficialUpdates(i.home);labUpdates=new LabUpdates(i.home);automationStatus=new AutomationStatus(i.home);launcherUpdates=new LauncherUpdates(i.home);
        javaExecutable = readJava();
        setLayout(new BorderLayout(22,18));setBorder(new EmptyBorder(22,22,16,22));setBackground(PAPER);
        JPanel heading=new JPanel(new BorderLayout());heading.setOpaque(false);
        JPanel titles=column();JLabel title=label("XMage Laboratorio",28);title.setFont(title.getFont().deriveFont(Font.BOLD));title.setForeground(INK);
        titles.add(title);titles.add(label("Gioca. Prova i tuoi mazzi. Ritrova ogni partita.",14));heading.add(titles);
        JLabel badge=label("LAUNCHER "+LauncherInfo.VERSION,12);heading.add(badge,BorderLayout.EAST);add(heading,BorderLayout.NORTH);
        JPanel rail=column();rail.setPreferredSize(new Dimension(158,400));rail.setBorder(new EmptyBorder(8,0,0,0));
        pages.setOpaque(false);
        addPage(rail,"Gioca",home());addPage(rail,"Aggiornamenti",updates());
        JTabbedPane settings=new JTabbedPane();settings.setFont(new Font("SansSerif",Font.PLAIN,13));
        settings.addTab("Versioni e backup",scroll(versions()));settings.addTab("Configurazione",scroll(configuration()));settings.addTab("Diagnostica",logs());
        addPage(rail,"Impostazioni",settings);rail.add(Box.createVerticalGlue());
        rail.add(text("Il tuo archivio\nMazzi e report restano\nnella cartella laboratorio.",4));
        add(rail,BorderLayout.WEST);add(pages,BorderLayout.CENTER);navigate("Gioca");
        JPanel footer=new JPanel(new BorderLayout(12,0));footer.setOpaque(false);
        status.setForeground(MUTED);status.setFont(new Font("SansSerif",Font.PLAIN,12));footer.add(status);
        progress.setPreferredSize(new Dimension(95,8));progress.setVisible(false);footer.add(progress,BorderLayout.EAST);add(footer,BorderLayout.SOUTH);
        for(JButton action:new JButton[]{stop,check,javaButton,importPackage,activate,previous,backup,recover,checkUpdates,installUpdate,configureChannel,catalogImport,updateLauncher,homeUpdates})styleButton(action,false);
        styleButton(play,true);styleButton(installUpdate,true);styleButton(updateLauncher,true);
        homeUpdates.addActionListener(e->navigate("Aggiornamenti"));updateLauncher.addActionListener(e->installLauncher());
        autoCheck.setOpaque(false);autoCheck.setFont(new Font("SansSerif",Font.PLAIN,13));
        Path prefs=installation.home.resolve("launcher/interfaccia.properties");
        if(Files.exists(prefs))autoCheck.setSelected(!"false".equals(ArchiveFiles.properties(prefs).getProperty("check.on.start")));
        autoCheck.addActionListener(e->{try{UpdateNetwork.atomic(prefs,("check.on.start="+autoCheck.isSelected()+"\n").getBytes(StandardCharsets.UTF_8));}catch(IOException x){error(x.getMessage());}});
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
        try{serviceStatus.setText(automationStatus.cached(labUpdates.channel()));}catch(IOException e){serviceStatus.setText("Stato del servizio da verificare.");}
        try{launcherRelease=launcherUpdates.cached();}catch(IOException e){say(e.getMessage());}
        refreshUpdates();
    }
    private JPanel home() throws IOException {
        JPanel page=page();
        page.add(kicker("IL TUO LABORATORIO"));page.add(Box.createVerticalStrut(12));
        JLabel welcome=label("Una nuova partita ti aspetta.",27);welcome.setFont(welcome.getFont().deriveFont(Font.BOLD));welcome.setForeground(INK);page.add(welcome);
        page.add(Box.createVerticalStrut(10));currentVersion.setFont(currentVersion.getFont().deriveFont(Font.BOLD));page.add(currentVersion);
        page.add(text("Il launcher prepara il server e apre XMage. Tu scegli il tavolo e i mazzi.",2));
        JPanel actions=flow();play.setPreferredSize(new Dimension(185,48));play.setFont(new Font("SansSerif",Font.BOLD,20));actions.add(play);actions.add(stop);page.add(actions);
        JPanel states=flow();states.add(serverState);states.add(Box.createHorizontalStrut(12));states.add(clientState);page.add(states);
        page.add(Box.createVerticalStrut(20));
        JPanel update=card();update.add(kicker("AGGIORNAMENTI"));update.add(Box.createVerticalStrut(8));
        overview.setFont(overview.getFont().deriveFont(Font.BOLD));update.add(overview);update.add(Box.createVerticalStrut(5));update.add(overviewDetail);
        JPanel updateActions=flow();updateActions.add(homeUpdates);update.add(updateActions);page.add(update);
        page.add(Box.createVerticalStrut(20));page.add(kicker("MAZZI E PARTITE"));
        JPanel folders=flow();folders.add(button("I miei mazzi",()->openFolder(installation.data.resolve("miei deck"),true)));
        folders.add(button("Report delle partite",()->openFolder(installation.data.resolve("reports"),false)));
        folders.add(button("Apri archivio",()->openFolder(installation.data,true)));page.add(folders);
        page.add(text("Mazzi e report rimangono disponibili anche quando aggiorni o cambi versione.",2));
        page.add(Box.createVerticalGlue());return page;
    }
    private JPanel versions() throws IOException {
        JPanel page = page();
        page.add(label("Versioni del Laboratorio", 18)); page.add(Box.createVerticalStrut(8));
        page.add(text("Importa uno ZIP Laboratorio riconosciuto e scegli quale versione avviare. Il cambio crea prima un backup; mazzi e report restano nella cartella laboratorio.", 3));
        choices.setMaximumSize(new Dimension(Integer.MAX_VALUE, 32)); choices.setAlignmentX(LEFT_ALIGNMENT); page.add(choices);
        JPanel actions=flow(); actions.add(importPackage); actions.add(activate); actions.add(previous); page.add(actions);
        page.add(text("Qui trovi le versioni già installate. Per gli aggiornamenti online usa la sezione Aggiornamenti.", 2));
        page.add(Box.createVerticalStrut(8)); page.add(label("Backup e recupero", 18));
        JPanel copies=flow(); copies.add(backup); copies.add(button("Apri backup", () -> openFolder(versions.backups, true))); copies.add(recover); page.add(copies);
        page.add(text("Il recupero estrae una copia consultabile in launcher/recuperi. Non sostituisce i dati attivi. Le immagini restano nei loro percorsi e non sono incluse nei backup.", 3));
        page.add(Box.createVerticalGlue()); return page;
    }
    private JPanel updates() {
        JPanel page=page();page.add(kicker("TUTTO PRONTO, SENZA ESTRARRE ZIP"));page.add(Box.createVerticalStrut(10));
        JLabel title=label("Mantieni aggiornato il tuo Laboratorio",23);title.setForeground(INK);title.setFont(title.getFont().deriveFont(Font.BOLD));page.add(title);
        page.add(Box.createVerticalStrut(8));page.add(updateBase);
        JPanel checkRow=flow();checkRow.add(checkUpdates);page.add(checkRow);page.add(autoCheck);
        page.add(Box.createVerticalStrut(18));
        JPanel game=card();game.add(kicker("GIOCO E LABORATORIO"));game.add(labStatus);game.add(releaseNotes);
        JPanel installRow=flow();installRow.add(installUpdate);game.add(installRow);
        game.add(text("L’aggiornamento crea un backup. Termina la partita e chiudi client e server prima di installarlo.",2));page.add(game);
        page.add(Box.createVerticalStrut(14));
        JPanel launcher=card();launcher.add(kicker("QUESTA SCHERMATA · LAUNCHER "+LauncherInfo.VERSION));launcher.add(launcherStatus);
        JPanel launcherRow=flow();launcherRow.add(updateLauncher);launcher.add(launcherRow);
        launcher.add(text("Il launcher si riaprirà al termine. La copia precedente viene conservata.",2));page.add(launcher);
        page.add(Box.createVerticalStrut(14));page.add(kicker("PREPARAZIONE DELLE NUOVE VERSIONI"));page.add(serviceStatus);
        page.add(Box.createVerticalGlue());return page;
    }
    static String overviewText(boolean checking,boolean checked,boolean failed,boolean available) {
        if(checking)return "Controllo in corso…";
        if(failed)return "Controllo non completato";
        if(available)return "Aggiornamento disponibile";
        return checked?"Tutto aggiornato":"Controllo da eseguire";
    }
    private void refreshUpdates() {
        boolean gameAvailable=false,selfAvailable=false;
        try {
            String active=versions.activeId();
            updateBase.setText("In uso: Lab "+processes.installation.value("lab.version")+" · XMage "+processes.installation.value("xmage.version"));
            availableUpdate=labUpdates.latest();
            if(availableUpdate==null) {labStatus.setText("Premi Controlla aggiornamenti per cercare una nuova versione.");releaseNotes.setText("");}
            else {
                boolean newer=LabUpdates.newer(availableUpdate,processes.installation);
                boolean same=active.equals(availableUpdate.id)||(!newer&&availableUpdate.version.equals(processes.installation.value("lab.version"))&&availableUpdate.xmage.equals(processes.installation.value("xmage.version")));
                gameAvailable=newer;
                String state=same?(catalogChecked?"È in uso l’ultima versione pubblicata.":"Questa versione è in uso. Verifica se ci sono novità."):
                    !newer?"Il catalogo salvato è precedente alla versione in uso. Ricontrolla online.":
                    !availableUpdate.supported()?"Prima serve un launcher più recente o una migrazione dei dati.":
                    versions.installed().containsKey(availableUpdate.id)?"Già scaricata. Puoi attivarla con un backup automatico.":
                    availableUpdate.url==null?"Download non ancora disponibile.":"Pronta da scaricare e installare.";
                labStatus.setText(availableUpdate.label()+"\n"+state);releaseNotes.setText(availableUpdate.notes);
                installUpdate.setText(versions.installed().containsKey(availableUpdate.id)&&!same?"Attiva versione aggiornata":"Aggiorna Laboratorio");
            }
            launcherRelease=launcherUpdates.cached();selfAvailable=launcherRelease!=null&&launcherRelease.newer();
            launcherStatus.setText(selfAvailable?"Disponibile il launcher "+launcherRelease.version+". Scaricalo e riavvia da qui.":launcherChecked?"Il launcher è aggiornato.":"Versione in uso: "+LauncherInfo.VERSION+". Controllo da eseguire.");
        }catch(IOException e){checkFailure=e.getMessage();say(e.getMessage());}
        overview.setText(overviewText(checking,catalogChecked&&launcherChecked,!checkFailure.isEmpty(),gameAvailable||selfAvailable));
        overview.setForeground(checking?MUTED:!checkFailure.isEmpty()?new Color(151,88,26):ACCENT);
        overviewDetail.setText(checking?"Puoi giocare mentre cerchiamo le novità.":!checkFailure.isEmpty()?"Puoi continuare a giocare. Riprova dalla sezione Aggiornamenti.":checkedAt.isEmpty()?"Il controllo non installa nulla e non interrompe le partite.":"Ultimo controllo: "+checkedAt);
        homeUpdates.setText(gameAvailable||selfAvailable?"Vedi aggiornamenti":"Apri aggiornamenti");
    }
    private void checkUpdates() {
        if(checking||busy)return;checking=true;checkFailure="";refreshUpdates();refresh();
        new SwingWorker<String,Void>() {
            String service="", official="";boolean labOK=false,launcherOK=false;
            protected String doInBackground() throws Exception {
                StringBuilder errors=new StringBuilder();
                try{labUpdates.check();labOK=true;}catch(IOException e){errors.append("Laboratorio: ").append(e.getMessage()).append("\n");}
                try{launcherUpdates.check();launcherOK=true;}catch(IOException e){errors.append("Launcher: ").append(e.getMessage()).append("\n");}
                try{service=automationStatus.check(labUpdates.channel());}catch(IOException e){service="Servizio non raggiungibile. "+automationStatus.cached(labUpdates.channel());}
                try{official=officialUpdates.check().describe();}catch(IOException e){official="Controllo ufficiale non riuscito: "+e.getMessage();}
                return errors.toString();
            }
            protected void done() {
                checking=false;catalogChecked=labOK;launcherChecked=launcherOK;
                try{checkFailure=get();versions.refreshCatalog();}catch(Exception e){checkFailure=e.getMessage();}
                checkedAt=new SimpleDateFormat("dd/MM/yyyy HH:mm").format(new Date());
                serviceStatus.setText(service);officialStatus.setText(official);refreshVersions();refreshUpdates();refresh();
                say(checkFailure.isEmpty()?"Controllo completato. Nessun aggiornamento viene installato senza la tua conferma.":checkFailure);
            }
        }.execute();
    }
    private void installLauncher() {
        final LauncherUpdates.Release selected=launcherRelease;if(selected==null)return;
        if(JOptionPane.showConfirmDialog(this,"Aggiornare il launcher alla versione "+selected.version+"?\n\nIl launcher verrà chiuso e riaperto.\nLa versione precedente viene conservata.\nClient e server devono essere chiusi.","Aggiornamento launcher",JOptionPane.OK_CANCEL_OPTION)!=JOptionPane.OK_OPTION)return;
        work(()->{
            versions.requireIdle(processes);Path stage=launcherUpdates.stage(selected,this::say);versions.requireIdle(processes);
            LauncherUpdater.launchHelper(installation.home,stage);
            SwingUtilities.invokeLater(()->{dispose();System.exit(0);});return "Riavvio del launcher…";
        });
    }
    private JPanel configuration() {
        JPanel page=page();page.add(kicker("IMPOSTAZIONI AVANZATE"));page.add(text("Queste opzioni servono per assistenza o configurazioni particolari.",2));
        page.add(label("Canale degli aggiornamenti",18));JPanel channel=flow();channel.add(configureChannel);channel.add(catalogImport);page.add(channel);
        page.add(Box.createVerticalStrut(12));page.add(label("Java e verifica dei file",18));
        JPanel controls=flow();controls.add(check);controls.add(javaButton);page.add(controls);javaPath.setEditable(false);javaPath.setMaximumSize(new Dimension(Integer.MAX_VALUE,32));javaPath.setAlignmentX(LEFT_ALIGNMENT);page.add(javaPath);
        page.add(Box.createVerticalStrut(18));page.add(label("Release ufficiale XMage",18));page.add(officialStatus);
        page.add(text("Una release ufficiale diventa disponibile nel Laboratorio dopo l’integrazione e le verifiche automatiche.",3));
        JPanel links=flow();links.add(button("Apri le release ufficiali",()->{
            if(!Desktop.isDesktopSupported()||!Desktop.getDesktop().isSupported(Desktop.Action.BROWSE))throw new IOException("Apri https://github.com/magefree/mage/releases");
            Desktop.getDesktop().browse(URI.create("https://github.com/magefree/mage/releases"));
        }));page.add(links);page.add(Box.createVerticalGlue());return page;
    }
    private void configureChannel() {
        try {
            String value=JOptionPane.showInputDialog(this,"Indirizzo HTTPS del catalogo Laboratorio pubblicato.\nQuesto canale è distinto dal sito ufficiale XMage.\nLascia vuoto per disattivare il controllo Laboratorio online.",labUpdates.channel());
            if(value==null)return;catalogChecked=false;checkFailure="";labUpdates.channel(value);serviceStatus.setText(automationStatus.cached(value.trim()));refreshUpdates();refresh();
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
            currentVersion.setText("Lab "+processes.installation.value("lab.version")+"   ·   XMage "+processes.installation.value("xmage.version")+"   ·   IA "+processes.installation.value("ai.version"));
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
        page.add(bottom, BorderLayout.SOUTH); return page;
    }
    public void monitor() { timer = new javax.swing.Timer(1500, e -> refresh()); timer.start(); if(autoCheck.isSelected())SwingUtilities.invokeLater(this::checkUpdates); }
    private void refresh() {
        boolean s = processes.serverAlive(), c = processes.clientAlive();
        serverState.setText(s ? "Server pronto" : "Server spento");
        clientState.setText(c ? "XMage aperto" : "XMage chiuso");
        play.setEnabled(!busy && !c); stop.setEnabled(!busy && (s || c)); check.setEnabled(!busy); javaButton.setEnabled(!busy && !s && !c);
        boolean idle=!busy&&!checking&&!s&&!c;
        checkUpdates.setEnabled(!busy&&!checking);configureChannel.setEnabled(!busy&&!checking);catalogImport.setEnabled(idle);
        boolean downloadable=availableUpdate!=null&&availableUpdate.supported()&&!availableUpdate.id.equals(versions.activeId());
        try{downloadable=downloadable&&LabUpdates.newer(availableUpdate,processes.installation)&&(availableUpdate.url!=null||versions.installed().containsKey(availableUpdate.id));}catch(IOException e){downloadable=false;}
        try{updateLauncher.setEnabled(idle&&launcherRelease!=null&&launcherRelease.newer());}catch(IOException e){updateLauncher.setEnabled(false);}
        installUpdate.setToolTipText((s||c)?"Chiudi client e server per aggiornare.":null);
        updateLauncher.setToolTipText((s||c)?"Chiudi client e server per aggiornare.":null);
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
        JButton b = new JButton(name);styleButton(b,false); b.addActionListener(e -> { try { action.run(); } catch (Exception x) { error(x.getMessage()); } }); return b;
    }
    private void addPage(JPanel rail,String name,JComponent content) {
        JButton tab=new JButton(name);tab.setName(name);styleButton(tab,false);tab.setAlignmentX(LEFT_ALIGNMENT);tab.setMaximumSize(new Dimension(158,44));
        tab.setHorizontalAlignment(SwingConstants.LEFT);tab.addActionListener(e->navigate(name));navigation.add(tab);rail.add(tab);rail.add(Box.createVerticalStrut(8));
        pages.add(scroll(content),name);
    }
    private void navigate(String name) {
        pagesLayout.show(pages,name);
        for(JButton tab:navigation){boolean active=name.equals(tab.getName());tab.setBackground(active?INK:PAPER);tab.setForeground(active?Color.WHITE:MUTED);}
    }
    private static JScrollPane scroll(JComponent component) {
        JScrollPane scroll=new JScrollPane(component);scroll.setBorder(null);scroll.getVerticalScrollBar().setUnitIncrement(18);scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);return scroll;
    }
    private static JPanel card() {
        JPanel p=column();p.setOpaque(true);p.setBackground(new Color(243,247,245));p.setBorder(new EmptyBorder(16,18,12,18));p.setAlignmentX(LEFT_ALIGNMENT);return p;
    }
    private static JLabel kicker(String s) { JLabel label=label(s,11);label.setFont(label.getFont().deriveFont(Font.BOLD));label.setForeground(ACCENT);return label; }
    private static void styleButton(JButton b,boolean primary) {
        b.setFont(new Font("SansSerif",Font.BOLD,13));b.setBackground(primary?ACCENT:new Color(230,237,233));b.setForeground(primary?Color.WHITE:INK);
        b.setFocusPainted(true);b.setBorder(new EmptyBorder(11,16,11,16));b.setOpaque(true);b.setContentAreaFilled(true);b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
    }
    private static JPanel column() { JPanel p = new JPanel(); p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS)); p.setOpaque(false); return p; }
    private static JPanel page() { JPanel p = column(); p.setOpaque(true); p.setBackground(Color.WHITE); p.setBorder(new EmptyBorder(24, 24, 20, 24)); return p; }
    private static JPanel flow() { JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 5)); p.setOpaque(false); p.setAlignmentX(LEFT_ALIGNMENT); p.setMaximumSize(new Dimension(Integer.MAX_VALUE, 55)); return p; }
    private static JLabel label(String s, int size) { JLabel l = new JLabel(s); l.setFont(new Font("SansSerif", Font.PLAIN, size)); l.setForeground(MUTED); l.setAlignmentX(LEFT_ALIGNMENT); return l; }
    private static JTextArea text(String s, int rows) {
        JTextArea a = new JTextArea(s, rows, 1); a.setEditable(false); a.setLineWrap(true); a.setWrapStyleWord(true); a.setOpaque(false); a.setForeground(MUTED);
        a.setFont(new Font("SansSerif", Font.PLAIN, 13)); a.setAlignmentX(LEFT_ALIGNMENT); a.setBorder(null);
        a.setMaximumSize(new Dimension(Integer.MAX_VALUE, rows * 24)); return a;
    }
}

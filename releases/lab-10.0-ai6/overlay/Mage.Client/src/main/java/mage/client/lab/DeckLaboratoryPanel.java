package mage.client.lab;

import mage.cards.repository.CardInfo;
import mage.cards.repository.CardRepository;
import mage.lab.*;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.io.File;
import java.nio.file.*;
import java.text.DateFormat;
import java.util.*;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.function.Function;

/** The laboratory uses background workers for disk/database work and never blocks play. */
public final class DeckLaboratoryPanel extends JPanel {
    private final LabStore store;
    private final Function<String, CardInfo> cardLookup;
    private final JComboBox<LabDeck> variantA = new JComboBox<>();
    private final JComboBox<LabDeck> variantB = new JComboBox<>();
    private final JSpinner hand = new JSpinner(new SpinnerNumberModel(7, 1, 30, 1));
    private final JSpinner draws = new JSpinner(new SpinnerNumberModel(3, 0, 30, 1));
    private final JTextArea analysis = area();
    private final JTextArea comparison = area();
    private final JTextArea details = area();
    private final JTextArea guide = area();
    private final JTextArea matches = area();
    private long detailRequest;
    private final LabReviewPanel reviewPanel;
    private final JLabel status = new JLabel("Caricamento archivio…");
    private final JTabbedPane tabs = new JTabbedPane();
    private final List<JButton> buttons = new ArrayList<>();
    private final DefaultTableModel gameRows = new DefaultTableModel(new String[]{"Data", "Modalità", "Giocatori", "Vite", "Stato"}, 0) {
        @Override public boolean isCellEditable(int row, int column) { return false; }
    };
    private final JTable gameTable = new JTable(gameRows);
    private List<LabGame> games = Collections.emptyList();
    private boolean refreshing;

    public static void open(Frame parent) {
        JDialog dialog = new JDialog(parent, "Laboratorio dei mazzi · versione 6", false);
        dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
        dialog.setContentPane(new DeckLaboratoryPanel());
        dialog.setSize(1120, 780);
        dialog.setMinimumSize(new Dimension(900, 600));
        dialog.setLocationRelativeTo(parent);
        dialog.setVisible(true);
    }

    public DeckLaboratoryPanel() {
        this(new LabStore(LabStore.defaultRoot()), name -> CardRepository.instance.findCard(name, true));
    }

    DeckLaboratoryPanel(LabStore store, Function<String, CardInfo> cardLookup) {
        super(new BorderLayout(10, 10));
        this.store = store;
        this.reviewPanel = new LabReviewPanel(store);
        this.cardLookup = cardLookup;
        JPanel content = this;
        content.setBorder(new EmptyBorder(14, 14, 12, 14));
        JPanel header = new JPanel(new BorderLayout(0, 8));
        JLabel title = new JLabel("Laboratorio dei mazzi");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 23f));
        header.add(title, BorderLayout.NORTH);
        JPanel tools = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        tools.add(button("Importa mazzi .dck", this::importDecks));
        tools.add(button("Aggiorna archivio", () -> refresh(null)));
        tools.add(button("Esporta report", this::exportReport));
        tools.add(button("Apri cartella dati", this::openFolder));
        header.add(tools, BorderLayout.CENTER);
        JPanel selection = new JPanel(new GridLayout(2, 1, 0, 5));
        JPanel revisions = new JPanel(new GridLayout(1, 2, 12, 0));
        revisions.add(labeled("Versione A", variantA));
        revisions.add(labeled("Versione B", variantB));
        selection.add(revisions);
        JPanel settings = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        settings.add(new JLabel("Mano iniziale:")); settings.add(hand);
        settings.add(new JLabel("Pescate aggiuntive:")); settings.add(draws);
        settings.add(button("Analizza e confronta", this::analyze));
        settings.add(new JLabel("Le impostazioni della partita si scelgono nel tavolo XMage."));
        selection.add(settings);
        header.add(selection, BorderLayout.SOUTH);
        content.add(header, BorderLayout.NORTH);

        tabs.addTab("Regolarità e mana", new JScrollPane(analysis));
        tabs.addTab("Confronto A / B", new JScrollPane(comparison));
        gameTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        gameTable.setRowHeight(25);
        gameTable.setAutoCreateRowSorter(true);
        JSplitPane gamePane = new JSplitPane(JSplitPane.VERTICAL_SPLIT, new JScrollPane(gameTable), new JScrollPane(details));
        gamePane.setResizeWeight(.32);
        gamePane.setDividerLocation(170);
        tabs.addTab("Manche registrate", gamePane);
        tabs.addTab("Incontri", new JScrollPane(matches));
        tabs.addTab("Analisi incontri", reviewPanel);
        guide.setText("COME USARE IL LABORATORIO\n\n"
                + "1. Importa i mazzi .dck. Ogni composizione/stampa diversa crea una revisione conservata nell'archivio.\n"
                + "2. Scegli A e B, poi Analizza e confronta. Le liste originali non vengono modificate.\n"
                + "3. Avvia client e server con Avvia_XMage_Laboratorio.cmd. Attendi che il server sia pronto; collegati a 127.0.0.1, porta 17181.\n"
                + "4. Crea il tavolo: per le tue prove usa 4 giocatori tutti contro tutti e 30 vite, con obiettivo 2 vittorie per incontro.\n"
                + "5. Gioca con A, poi con B, contro gli stessi mazzi. Mantieni il livello IA e varia equamente i posti.\n"
                + "6. Premi Aggiorna archivio per leggere le partite e i campioni registrati.\n\n"
                + "NOVITÀ: ANALISI DOPO L’INCONTRO\n"
                + "Apri Analisi incontri, scegli l’incontro e il giocatore/revisione. Consulta Sintesi e verifiche oppure Schede delle carte. "
                + "Il server salva automaticamente il report completo in laboratorio/reports alla fine dell’incontro. "
                + "Le vecchie manche possono essere analizzate singolarmente con i dati disponibili.\n\n"
                + "CHE COSA MISURA QUESTA VERSIONE\n"
                + "Composizione, curva, probabilità esatte di pescare terre sul fronte, differenze fra liste, "
                + "risultati di manche e incontri, mani di mulligan, eventi delle carte e tempi IA nelle partite locali. Le partite con errori, timeout o rollback "
                + "sono conservate ma escluse dai confronti. Quelle senza risultato finale restano incomplete.\n\n"
                + "LIMITI DA TENERE PRESENTI\n"
                + "Le probabilità non includono mulligan, ricerca, selezione, acceleratori, colori o ingressi tappati. "
                + "Una terra pescata non garantisce il mana per una magia. "
                + "La frequenza di vittoria dipende dagli avversari e dal pilota; i risultati osservati non provano "
                + "che una modifica sia la causa del miglioramento.\n\n"
                + "Partite automatiche in serie, replay completo, riconoscimento delle sinergie e suggerimenti di "
                + "sostituzione convalidati non sono ancora implementati. "
                + "Le IA mantengono le impostazioni originali.\n\n"
                + "DATI LOCALI\n"
                + "L'archivio contiene anche le mani di tutti i partecipanti e viene salvato sul PC del server. "
                + "La registrazione si attiva con lo script del laboratorio; un client collegato ad altri server "
                + "non ne registra automaticamente le partite. Salva una copia della cartella dati per conservare "
                + "lo storico quando aggiorni l'applicazione.\n\nCartella dati: " + store.root);
        guide.setCaretPosition(0);
        tabs.addTab("Guida e limiti", new JScrollPane(guide));
        content.add(tabs, BorderLayout.CENTER);
        content.add(status, BorderLayout.SOUTH);
        gameTable.getSelectionModel().addListSelectionListener(event -> {
            if (event.getValueIsAdjusting() || refreshing) return;
            int viewRow = gameTable.getSelectedRow();
            if (viewRow >= 0) {
                LabGame selected = games.get(gameTable.convertRowIndexToModel(viewRow));
                showGame(selected);
            }
        });
        variantA.addActionListener(event -> staleAnalysis());
        variantB.addActionListener(event -> staleAnalysis());
        hand.addChangeListener(event -> staleAnalysis());
        draws.addChangeListener(event -> staleAnalysis());
        setSize(1120, 780);
        setMinimumSize(new Dimension(900, 600));
        refresh(null);
    }

    private void showGame(LabGame selected) {
        final long request = ++detailRequest;
        details.setText(LabReports.game(selected)); details.setCaretPosition(0);
        if (selected.telemetry == null) return;
        new SwingWorker<String, Void>() {
            @Override protected String doInBackground() throws Exception {
                String report = LabReports.game(selected);
                String telemetry = LabReports.telemetry(selected, store.events(selected.id));
                int samples = report.indexOf("CAMPIONI DELLA PARTITA");
                return samples < 0 ? report + telemetry : report.substring(0, samples) + telemetry + "\n" + report.substring(samples);
            }
            @Override protected void done() {
                if (request != detailRequest) return;
                try { details.setText(get()); details.setCaretPosition(0); }
                catch (Exception e) { details.append("\nTraccia eventi non leggibile: " + (e.getCause() == null ? e : e.getCause()).getMessage()); }
            }
        }.execute();
    }

    private static JTextArea area() {
        JTextArea result = new JTextArea();
        result.setEditable(false);
        result.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        result.setLineWrap(true); result.setWrapStyleWord(true);
        result.setMargin(new Insets(12, 12, 12, 12));
        return result;
    }

    private static JPanel labeled(String label, JComponent component) {
        JPanel panel = new JPanel(new BorderLayout(6, 0));
        panel.add(new JLabel(label), BorderLayout.WEST); panel.add(component, BorderLayout.CENTER);
        return panel;
    }

    private JButton button(String text, Runnable action) {
        JButton button = new JButton(text);
        button.addActionListener(event -> action.run());
        buttons.add(button);
        return button;
    }

    private void staleAnalysis() {
        if (refreshing) return;
        analysis.setText("Seleziona le revisioni e premi Analizza e confronta per aggiornare i risultati.");
        comparison.setText("Il confronto deve essere aggiornato con le selezioni correnti.");
    }

    private void task(String message, Callable<Runnable> work) {
        status.setText(message);
        setBusy(true);
        new SwingWorker<Runnable, Void>() {
            @Override protected Runnable doInBackground() throws Exception { return work.call(); }
            @Override protected void done() {
                try { get().run(); }
                catch (Exception e) {
                    Throwable reason = e.getCause() == null ? e : e.getCause();
                    status.setText("Operazione non completata: " + reason.getMessage());
                    JOptionPane.showMessageDialog(DeckLaboratoryPanel.this, reason.getMessage(), "Laboratorio", JOptionPane.ERROR_MESSAGE);
                } finally { setBusy(false); }
            }
        }.execute();
    }

    private void setBusy(boolean busy) {
        buttons.forEach(button -> button.setEnabled(!busy));
        variantA.setEnabled(!busy); variantB.setEnabled(!busy);
        hand.setEnabled(!busy); draws.setEnabled(!busy);
    }

    private void refresh(String preferred) {
        LabDeck oldA = (LabDeck) variantA.getSelectedItem();
        LabDeck oldB = (LabDeck) variantB.getSelectedItem();
        task("Lettura di mazzi e partite…", () -> {
            List<LabDeck> decks = store.decks();
            List<LabGame> loadedGames = store.games();
            return () -> {
                applyArchive(decks, loadedGames, preferred == null && oldA != null ? oldA.id : preferred, oldB == null ? null : oldB.id);
                status.setText(decks.size() + " revisioni · " + games.size() + " partite · " + store.root);
            };
        });
    }

    private void applyArchive(List<LabDeck> decks, List<LabGame> loadedGames, String a, String b) {
        refreshing = true;
        try {
            variantA.removeAllItems(); variantB.removeAllItems();
            for (LabDeck deck : decks) { variantA.addItem(deck); variantB.addItem(deck); }
            select(variantA, a); select(variantB, b);
            if (b == null && variantB.getItemCount() > 1) variantB.setSelectedIndex(1);
            detailRequest++;
            games = loadedGames;
            reviewPanel.setGames(games);
            matches.setText(LabReports.matches(games)); matches.setCaretPosition(0);
            gameRows.setRowCount(0);
            for (LabGame game : games) gameRows.addRow(new Object[]{DateFormat.getDateTimeInstance().format(new Date(game.startedAt)),
                    game.mode, game.players.size(), game.startingLife, LabReports.status(game)});
            details.setText(games.isEmpty() ? "Nessuna partita registrata. Avvia il server con lo script del laboratorio e gioca una partita." : "Seleziona una partita per leggerne i dati.");
        } finally { refreshing = false; }
        staleAnalysis();
    }

    private static void select(JComboBox<LabDeck> combo, String id) {
        if (id == null) return;
        for (int i = 0; i < combo.getItemCount(); i++) if (combo.getItemAt(i).id.equals(id)) { combo.setSelectedIndex(i); return; }
    }

    private void importDecks() {
        JFileChooser chooser = new JFileChooser();
        chooser.setMultiSelectionEnabled(true);
        chooser.setFileFilter(new FileNameExtensionFilter("Mazzi XMage (*.dck)", "dck"));
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        File[] files = chooser.getSelectedFiles();
        LabDeck oldB = (LabDeck) variantB.getSelectedItem();
        task("Importazione delle revisioni…", () -> {
            List<LabDeck> imported = new ArrayList<>();
            for (File file : files) imported.add(LabDeck.readDck(file.toPath()));
            for (LabDeck deck : imported) store.saveDeck(deck);
            List<LabDeck> archive = store.decks();
            List<LabGame> loadedGames = store.games();
            return () -> {
                applyArchive(archive, loadedGames, imported.isEmpty() ? null : imported.get(0).id, oldB == null ? null : oldB.id);
                status.setText(imported.size() + " file importati · " + archive.size() + " revisioni archiviate. Premi Analizza e confronta.");
            };
        });
    }

    private void analyze() {
        LabDeck a = (LabDeck) variantA.getSelectedItem();
        LabDeck b = (LabDeck) variantB.getSelectedItem();
        if (a == null || b == null) { status.setText("Importa almeno un mazzo per iniziare."); return; }
        int initialHand = (Integer) hand.getValue();
        int additionalDraws = (Integer) draws.getValue();
        task("Analisi delle liste e confronto dei dati…", () -> {
            Map<String, CardInfo> cache = new HashMap<>();
            Function<String, CardInfo> lookup = name -> {
                if (!cache.containsKey(name)) cache.put(name, cardLookup.apply(name));
                return cache.get(name);
            };
            String reportA = "VERSIONE A · " + a.label() + "\n\n" + LabAnalysis.analyze(a, lookup).describe(initialHand, additionalDraws);
            String reportB = "VERSIONE B · " + b.label() + "\n\n" + LabAnalysis.analyze(b, lookup).describe(initialHand, additionalDraws);
            String compared = "A: " + a.label() + "\nB: " + b.label() + "\n\n" + LabAnalysis.differences(a, b)
                    + "\n" + LabReports.compareGames(a, b, store.games()) + "\n" + reportA + "\n" + reportB;
            return () -> {
                analysis.setText(reportA + "\n\n" + reportB); analysis.setCaretPosition(0);
                comparison.setText(compared); comparison.setCaretPosition(0);
                status.setText("Analisi completata. Le revisioni originali sono conservate nell'archivio.");
            };
        });
    }

    private void exportReport() {
        JTextArea selected = tabs.getSelectedIndex() == 0 ? analysis : tabs.getSelectedIndex() == 1 ? comparison : tabs.getSelectedIndex() == 2 ? details : tabs.getSelectedIndex() == 3 ? matches : guide;
        String report = tabs.getSelectedIndex() == 4 ? reviewPanel.exportText() : selected.getText();
        JFileChooser chooser = new JFileChooser();
        chooser.setSelectedFile(new File("Report_Laboratorio_XMage.txt"));
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
        Path path = chooser.getSelectedFile().toPath();
        if (Files.exists(path) && JOptionPane.showConfirmDialog(this, "Sostituire il report esistente?", "Esporta report", JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) return;
        task("Salvataggio del report…", () -> {
            LabStore.writeAtomic(path, report, true);
            return () -> status.setText("Report salvato: " + path);
        });
    }

    private void openFolder() {
        task("Apertura della cartella dati…", () -> {
            Files.createDirectories(store.root);
            if (Desktop.isDesktopSupported()) Desktop.getDesktop().open(store.root.toFile());
            return () -> status.setText("Cartella dati: " + store.root);
        });
    }
}

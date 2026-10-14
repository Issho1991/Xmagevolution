package mage.client.lab;

import mage.lab.*;
import javax.swing.*;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.text.DateFormat;
import java.util.*;
import java.util.List;

/** Loads reports off the EDT; selections never display results from an older request. */
final class LabReviewPanel extends JPanel {
    private final LabStore store;
    private final JComboBox<Entry> sessions = new JComboBox<>();
    private final JComboBox<LabReview.PlayerReview> players = new JComboBox<>();
    private final JTextArea summary = area(), cardDetail = area();
    private final JLabel status = new JLabel("Seleziona un incontro o una manche storica.");
    private final DefaultTableModel rows = new DefaultTableModel(new String[]{"Carta", "Copie", "Pescate", "Lanci", "Fine turno", "Serie"}, 0) {
        @Override public boolean isCellEditable(int row, int column) { return false; }
        @Override public Class<?> getColumnClass(int column) { return column == 0 ? String.class : Integer.class; }
    };
    private final JTable cards = new JTable(rows);
    private List<LabReview.CardReview> visibleCards = Collections.emptyList();
    private LabReview review;
    private boolean updating;
    private long request;
    private SwingWorker<LabReview, Void> worker;

    LabReviewPanel(LabStore store) {
        super(new BorderLayout(8, 8)); this.store = store;
        sessions.setName("labReviewSessions"); players.setName("labReviewPlayers"); cards.setName("labReviewCards");
        JPanel controls = new JPanel(new GridLayout(2, 1, 4, 4));
        controls.add(labeled("Incontro / manche", sessions)); controls.add(labeled("Giocatore / revisione", players));
        add(controls, BorderLayout.NORTH);
        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("Sintesi e verifiche", new JScrollPane(summary));
        cards.setAutoCreateRowSorter(true); cards.setSelectionMode(ListSelectionModel.SINGLE_SELECTION); cards.setRowHeight(24);
        cards.getColumnModel().getColumn(0).setPreferredWidth(240);
        cards.setDefaultRenderer(Integer.class, new DefaultTableCellRenderer() {
            @Override protected void setValue(Object value) { setHorizontalAlignment(RIGHT); setText(value == null ? "—" : value.toString()); }
        });
        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, new JScrollPane(cards), new JScrollPane(cardDetail));
        split.setResizeWeight(.45); split.setDividerLocation(200);
        JPanel cardPanel = new JPanel(new BorderLayout(0, 6));
        cardPanel.add(new JLabel("Pescate/lanci: tratti confermati, anche parziali. Copertura nella scheda. Fine turno: campioni propri. — = non disponibile."), BorderLayout.NORTH);
        cardPanel.add(split, BorderLayout.CENTER);
        tabs.addTab("Schede delle carte", cardPanel); add(tabs, BorderLayout.CENTER); add(status, BorderLayout.SOUTH);
        sessions.addActionListener(e -> { if (!updating) load(); });
        players.addActionListener(e -> { if (!updating) showPlayer(); });
        cards.getSelectionModel().addListSelectionListener(e -> {
            if (e.getValueIsAdjusting() || updating) return;
            int row = cards.getSelectedRow();
            LabReview.PlayerReview player = (LabReview.PlayerReview) players.getSelectedItem();
            if (row >= 0 && player != null) {
                cardDetail.setText(visibleCards.get(cards.convertRowIndexToModel(row)).describe(player)); cardDetail.setCaretPosition(0);
            }
        });
    }

    void setGames(List<LabGame> games) {
        ++request; if (worker != null) worker.cancel(true);
        updating = true;
        try {
            review = null; players.removeAllItems(); sessions.removeAllItems(); rows.setRowCount(0); visibleCards = Collections.emptyList();
            Map<String, Entry> entries = new LinkedHashMap<>();
            for (LabGame game : games) {
                String key = game.match == null ? "game:" + game.id : "match:" + game.match.id;
                entries.computeIfAbsent(key, k -> new Entry()).games.add(game);
            }
            for (Entry entry : entries.values()) sessions.addItem(entry);
            sessions.setSelectedIndex(-1);
            summary.setText("Seleziona un incontro per la sintesi e le schede delle carte. Le vecchie manche senza identificativo incontro rimangono separate.");
            cardDetail.setText(""); status.setText(entries.size() + " incontri / manche storiche disponibili.");
        } finally { updating = false; }
    }

    private void load() {
        final long token = ++request;
        if (worker != null) worker.cancel(true);
        Entry entry = (Entry) sessions.getSelectedItem(); review = null;
        updating = true;
        try { players.removeAllItems(); rows.setRowCount(0); visibleCards = Collections.emptyList(); } finally { updating = false; }
        cardDetail.setText("");
        if (entry == null) return;
        summary.setText("Preparazione del report…"); status.setText("Analisi delle registrazioni…");
        worker = new SwingWorker<LabReview, Void>() {
            @Override protected LabReview doInBackground() throws Exception { return LabReview.create(entry.games, store); }
            @Override protected void done() {
                if (token != request) return;
                try {
                    review = get(); updating = true;
                    try { for (LabReview.PlayerReview player : review.players) players.addItem(player); } finally { updating = false; }
                    showPlayer(); status.setText("Report pronto. Esporta report salva la sintesi e tutte le schede dell’incontro selezionato.");
                } catch (Exception e) {
                    Throwable cause = e.getCause() == null ? e : e.getCause();
                    summary.setText("Report non disponibile: " + cause.getMessage()); status.setText("Controlla la registrazione e aggiorna l’archivio.");
                }
            }
        };
        worker.execute();
    }

    private void showPlayer() {
        if (review == null) return;
        LabReview.PlayerReview player = (LabReview.PlayerReview) players.getSelectedItem();
        summary.setText(review.overview + (player == null ? "" : "\n" + player.summary())); summary.setCaretPosition(0);
        updating = true;
        try {
            rows.setRowCount(0); visibleCards = player == null ? Collections.emptyList() : new ArrayList<>(player.cards.values());
            for (LabReview.CardReview card : visibleCards) rows.addRow(new Object[]{card.name, card.deckCopies,
                    player.eventGames + player.partialEventGames == 0 ? null : card.drawn, player.eventGames + player.partialEventGames == 0 ? null : card.cast, player.endSamples == 0 ? null : card.endSamples, player.endSamples == 0 ? null : card.longestRun});
            cardDetail.setText("Seleziona una carta. Le carte non osservate restano elencate quando la lista è disponibile.");
        } finally { updating = false; }
    }

    String exportText() { return review == null ? summary.getText() : review.describe(); }
    private static JPanel labeled(String label, JComponent component) {
        JPanel panel = new JPanel(new BorderLayout(8, 0)); panel.add(new JLabel(label), BorderLayout.WEST); panel.add(component, BorderLayout.CENTER); return panel;
    }
    private static JTextArea area() {
        JTextArea area = new JTextArea(); area.setEditable(false); area.setLineWrap(true); area.setWrapStyleWord(true);
        area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13)); area.setMargin(new Insets(10, 10, 10, 10)); return area;
    }
    private static final class Entry {
        final List<LabGame> games = new ArrayList<>();
        @Override public String toString() {
            LabGame first = games.get(0);
            return DateFormat.getDateTimeInstance().format(new Date(first.startedAt)) + " · "
                    + (first.match == null ? "Manche storica " + first.id.substring(0, 8) : "Incontro " + first.match.id.substring(0, 8) + " · " + games.size() + " manche")
                    + " · " + first.mode;
        }
    }
}

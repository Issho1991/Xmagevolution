package mage.client.lab;

import mage.client.MagePane;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.util.UUID;

/** Preview-only home. Actions delegate to the existing XMage controllers. */
public final class LabHomePane extends MagePane {
    private static final Color INK = new Color(28, 48, 50);
    private static final Color PAPER = new Color(246, 245, 239);
    private static final Color GREEN = new Color(34, 103, 91);
    private final JLabel connection = new JLabel();
    private final JButton play;
    private final JButton connect;

    public LabHomePane(Runnable newGame, Runnable decks, Runnable laboratory,
                       Runnable lobby, Runnable connectionAction) {
        setTitle("Home · Laboratorio");
        setLayout(new BorderLayout());
        JPanel page = new JPanel(new BorderLayout(0, 32));
        page.setBackground(PAPER);
        page.setBorder(new EmptyBorder(40, 42, 36, 42));

        JPanel heading = column();
        heading.add(label("LABORATORIO  /  ANTEPRIMA 01", 13, true, GREEN));
        heading.add(Box.createVerticalStrut(18));
        heading.add(label("Pronto per la prossima partita?", 30, true, INK));
        heading.add(Box.createVerticalStrut(12));
        heading.add(label("Scegli un mazzo, prepara il tavolo e metti alla prova le tue idee.", 16, false, INK));
        page.add(heading, BorderLayout.NORTH);

        JPanel cards = new JPanel(new GridLayout(1, 3, 18, 0));
        cards.setOpaque(false);
        play = button("Nuova partita", newGame, 'N');
        cards.add(card("01", "GIOCA", "Prepara il tavolo", "Giocatori, vite e vittorie\nin un unico posto.", play, true));
        cards.add(card("02", "MAZZI", "Lavora sulle carte", "Apri l’editor e prepara\nun mazzo per la prova.", button("Apri editor mazzi", decks, 'M'), false));
        cards.add(card("03", "LABORATORIO", "Rileggi la partita", "Analisi e report delle\npartite di questa copia.", button("Apri Laboratorio", laboratory, 'L'), false));
        page.add(cards, BorderLayout.CENTER);

        JPanel footer = column();
        JPanel status = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        status.setOpaque(false);
        connection.setFont(font(14, false));
        connection.setForeground(INK);
        status.add(connection);
        status.add(Box.createHorizontalStrut(20));
        connect = button("Collega al server", connectionAction, 'C');
        status.add(connect);
        status.add(Box.createHorizontalStrut(12));
        status.add(button("Tavoli e partite", lobby, 'T'));
        footer.add(status);
        footer.add(Box.createVerticalStrut(22));
        footer.add(label("Base Lab 10.0 · XMage 1.4.62 · IA 6", 12, false, INK));
        footer.add(Box.createVerticalStrut(6));
        footer.add(label("Copia di prova. Per tornare alla versione abituale, chiudi qui e apri il tuo Laboratorio.", 12, false, INK));
        page.add(footer, BorderLayout.SOUTH);
        JScrollPane scroll = new JScrollPane(page);
        scroll.setBorder(null);
        scroll.getVerticalScrollBar().setUnitIncrement(24);
        add(scroll);
        setConnected(false);
    }

    public void setConnected(boolean value) {
        connection.setText(value ? "Server di prova collegato" : "In attesa del server di prova");
        play.setEnabled(value);
        play.setToolTipText(value ? "Scegli le impostazioni e i mazzi prima di creare il tavolo" : "Collega prima il client al server di prova");
        connect.setText(value ? "Connessione…" : "Collega al server");
    }

    private static JPanel card(String number, String eyebrow, String title, String detail, JButton action, boolean primary) {
        JPanel card = new JPanel(new BorderLayout(0, 24));
        card.setBackground(primary ? GREEN : Color.WHITE);
        card.setBorder(new EmptyBorder(28, 24, 28, 24));
        Color text = primary ? Color.WHITE : INK;
        JPanel top = column();
        top.add(label(number + "  /  " + eyebrow, 12, true, text));
        top.add(Box.createVerticalStrut(28));
        top.add(label(title, 19, true, text));
        top.add(Box.createVerticalStrut(14));
        for (String line : detail.split("\n")) {
            top.add(label(line, 14, false, text));
            top.add(Box.createVerticalStrut(5));
        }
        card.add(top, BorderLayout.NORTH);
        action.setPreferredSize(new Dimension(210, 42));
        card.add(action, BorderLayout.SOUTH);
        return card;
    }

    private static JPanel column() {
        JPanel p = new JPanel(); p.setOpaque(false);
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS)); return p;
    }
    private static Font font(int size, boolean bold) {
        return new Font(Font.SANS_SERIF, bold ? Font.BOLD : Font.PLAIN, size);
    }
    private static JLabel label(String text, int size, boolean bold, Color color) {
        JLabel l = new JLabel(text); l.setFont(font(size, bold)); l.setForeground(color); return l;
    }
    private static JButton button(String text, Runnable action, char mnemonic) {
        JButton b = new JButton(text); b.setFont(font(14, true)); b.setMnemonic(mnemonic);
        b.addActionListener(e -> action.run()); return b;
    }
    @Override public UUID getSortTableId() { return null; }
    @Override public boolean isActiveTable() { return false; }
    @Override public int getSortOrder() { return -1; }
}

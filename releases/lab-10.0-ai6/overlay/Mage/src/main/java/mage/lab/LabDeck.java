package mage.lab;

import mage.cards.decks.DeckCardInfo;
import mage.cards.decks.DeckCardLists;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** An immutable-on-disk revision. Printing identity and gameplay composition are distinct. */
public final class LabDeck {
    public int schema = 1;
    public String id;
    public String compositionId;
    public long createdAt;
    public String source;
    public DeckCardLists deck;
    private static final Pattern CARD = Pattern.compile("(SB:)?\\s*(\\d+)\\s*\\[([^]:]+):([^]\\r\\n]+)]\\s*(.+)");

    public static LabDeck create(DeckCardLists cards, String source) {
        validate(cards);
        LabDeck result = new LabDeck();
        result.deck = cards.copy();
        result.source = source;
        result.createdAt = System.currentTimeMillis();
        result.id = fingerprint(cards, true);
        result.compositionId = fingerprint(cards, false);
        return result;
    }

    /** Strict, read-only DCK import: unknown cards and printing references are preserved. */
    public static LabDeck readDck(Path file) throws IOException {
        if (Files.size(file) > 1024 * 1024) throw new IOException("Il file del mazzo supera 1 MB.");
        DeckCardLists deck = new DeckCardLists();
        deck.setName(file.getFileName().toString().replaceFirst("(?i)\\.dck$", ""));
        int number = 0;
        for (String raw : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            String line = raw.replace("\uFEFF", "").trim();
            number++;
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("//") || line.startsWith("LAYOUT ")) continue;
            if (line.startsWith("NAME:")) { deck.setName(line.substring(5).trim()); continue; }
            if (line.startsWith("AUTHOR:")) { deck.setAuthor(line.substring(7).trim()); continue; }
            Matcher match = CARD.matcher(line);
            if (!match.matches()) throw new IOException("Riga " + number + " non riconosciuta: " + line);
            try {
                int amount = Integer.parseInt(match.group(2));
                if (amount <= 0) throw new IllegalArgumentException();
                DeckCardInfo card = new DeckCardInfo(match.group(5).trim(), match.group(4).trim(), match.group(3).trim(), amount);
                (match.group(1) == null ? deck.getCards() : deck.getSideboard()).add(card);
            } catch (IllegalArgumentException e) {
                throw new IOException("Quantità non valida alla riga " + number, e);
            }
        }
        try { return create(deck, file.getFileName().toString()); }
        catch (IllegalArgumentException e) { throw new IOException(e.getMessage(), e); }
    }

    public void validate() {
        if (schema != 1) throw new IllegalArgumentException("Versione archivio mazzo non supportata.");
        validate(deck);
        if (!fingerprint(deck, true).equals(id) || !fingerprint(deck, false).equals(compositionId)) {
            throw new IllegalArgumentException("Il contenuto del mazzo non corrisponde alla revisione salvata.");
        }
    }

    private static void validate(DeckCardLists deck) {
        if (deck == null || deck.getCards() == null || deck.getSideboard() == null || deck.getCards().isEmpty()) {
            throw new IllegalArgumentException("Il mazzo principale è vuoto.");
        }
        int total = 0;
        for (List<DeckCardInfo> zone : Arrays.asList(deck.getCards(), deck.getSideboard())) {
            for (DeckCardInfo card : zone) {
                if (card == null || card.getCardName() == null || card.getCardName().trim().isEmpty()
                        || card.getAmount() < 1 || card.getAmount() > 99) throw new IllegalArgumentException("Carta o quantità non valida.");
                total += card.getAmount();
                if (total > 10000) throw new IllegalArgumentException("Il mazzo supera 10000 carte.");
            }
        }
    }

    private static String field(String text) { return text == null ? "0:" : text.length() + ":" + text; }

    public static String fingerprint(DeckCardLists deck, boolean printings) {
        StringBuilder canonical = new StringBuilder("xmage-lab-1\n");
        int zoneIndex = 0;
        for (List<DeckCardInfo> zone : Arrays.asList(deck.getCards(), deck.getSideboard())) {
            SortedMap<String, Integer> entries = new TreeMap<>();
            for (DeckCardInfo card : zone) {
                String key = field(card.getCardName());
                if (printings) key += field(card.getSetCode()) + field(card.getCardNumber());
                entries.merge(key, card.getAmount(), Integer::sum);
            }
            canonical.append(zoneIndex++).append('\n');
            entries.forEach((key, amount) -> canonical.append(field(key)).append('=').append(amount).append('\n'));
        }
        return hash(canonical.toString());
    }

    public static String hash(String text) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder();
            for (byte value : bytes) result.append(String.format(Locale.ROOT, "%02x", value & 255));
            return result.toString();
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    public static SortedMap<String, Integer> counts(List<DeckCardInfo> cards) {
        SortedMap<String, Integer> counts = new TreeMap<>();
        for (DeckCardInfo card : cards) counts.merge(card.getCardName(), card.getAmount(), Integer::sum);
        return counts;
    }

    public String label() {
        return (deck.getName() == null || deck.getName().isEmpty() ? "Mazzo" : deck.getName()) + " · " + id.substring(0, 8);
    }

    @Override public String toString() { return label(); }
}

package mage.lab;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;

/** Atomic individual records; concurrent readers never see a half-written JSON document. */
public final class LabStore {
    public static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Gson COMPACT = new Gson();
    public final Path root;

    public LabStore(Path root) { this.root = root.toAbsolutePath().normalize(); }
    public static Path defaultRoot() {
        return Paths.get(System.getProperty("xmage.lab.dir", Paths.get(System.getProperty("user.home"), "XMage-Laboratorio").toString()));
    }

    public LabDeck saveDeck(LabDeck deck) throws IOException {
        deck.validate();
        Path path = root.resolve("decks").resolve(deck.id + ".json");
        if (Files.exists(path)) {
            LabDeck existing = read(path, LabDeck.class);
            existing.validate();
            return existing;
        }
        writeAtomic(path, JSON.toJson(deck), false);
        return deck;
    }

    public void saveGame(LabGame game) throws IOException {
        UUID.fromString(game.id);
        writeAtomic(root.resolve("games").resolve(game.id + ".json"), JSON.toJson(game), true);
    }

    /** One small append per observation; avoids rewriting the entire growing game history. */
    public void appendSample(String gameId, LabGame.Sample sample) throws IOException {
        UUID.fromString(gameId);
        Path folder = root.resolve("games");
        Files.createDirectories(folder);
        Files.write(folder.resolve(gameId + ".samples.jsonl"), (COMPACT.toJson(sample) + "\n").getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    public LabDeck deck(String id) throws IOException {
        if (id == null || !id.matches("[a-f0-9]{64}")) throw new IOException("Identificativo revisione non valido");
        LabDeck deck = read(root.resolve("decks").resolve(id + ".json"), LabDeck.class);
        try { deck.validate(); } catch (RuntimeException e) { throw new IOException("Revisione non valida", e); }
        if (!id.equals(deck.id)) throw new IOException("Revisione non corrispondente al file");
        return deck;
    }

    public List<LabDeck> decks() throws IOException {
        List<LabDeck> result = new ArrayList<>();
        for (Path path : records("decks")) {
            LabDeck deck = read(path, LabDeck.class);
            try { deck.validate(); } catch (RuntimeException e) { throw new IOException("Revisione non valida: " + path.getFileName(), e); }
            result.add(deck);
        }
        result.sort(Comparator.comparing(d -> d.label().toLowerCase(Locale.ROOT)));
        return result;
    }

    public java.io.BufferedWriter eventWriter(String id) throws IOException {
        UUID.fromString(id);
        Path folder = root.resolve("games");
        Files.createDirectories(folder);
        return Files.newBufferedWriter(folder.resolve(id + ".events.jsonl"), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
    }

    public List<LabGame.Event> events(String id) throws IOException {
        UUID.fromString(id);
        Path path = root.resolve("games").resolve(id + ".events.jsonl");
        List<LabGame.Event> events = new ArrayList<>();
        if (!Files.exists(path)) return events;
        if (Files.size(path) > 32L * 1024 * 1024) throw new IOException("Traccia troppo grande: " + path.getFileName());
        String text = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
        int end = text.lastIndexOf('\n');
        // Ignore a concurrent append or a crash-truncated final line only.
        if (end >= 0) for (String line : text.substring(0, end).split("\n")) {
            if (line.trim().isEmpty()) continue;
            try {
                LabGame.Event event = COMPACT.fromJson(line, LabGame.Event.class);
                if (event == null || event.kind == null || event.sequence != events.size() + 1L)
                    throw new IllegalArgumentException("Sequenza non valida");
                events.add(event);
            } catch (RuntimeException e) { throw new IOException("Evento non valido: " + path.getFileName(), e); }
        }
        return events;
    }

    public LabGame game(String id) throws IOException {
        UUID.fromString(id);
        LabGame game = read(root.resolve("games").resolve(id + ".json"), LabGame.class);
        if ((game.schema < 1 || game.schema > 5) || !id.equals(game.id)
                || game.players == null || game.samples == null || game.exclusions == null)
            throw new IOException("Partita non valida o versione non supportata: " + id);
        return game;
    }

    public List<LabGame> games() throws IOException {
        List<LabGame> result = new ArrayList<>();
        for (Path path : records("games")) {
            LabGame game = game(path.getFileName().toString().replace(".json", ""));
            if ("IN_PROGRESS".equals(game.status)) {
                Path samples = root.resolve("games").resolve(game.id + ".samples.jsonl");
                if (Files.exists(samples)) {
                    if (Files.size(samples) > 32L * 1024 * 1024) throw new IOException("Campioni troppo grandi: " + samples.getFileName());
                    String lines = new String(Files.readAllBytes(samples), StandardCharsets.UTF_8);
                    int completeEnd = lines.lastIndexOf('\n');
                    // A concurrent append or a crash may leave an unfinished final line.
                    if (completeEnd >= 0) for (String line : lines.substring(0, completeEnd).split("\n")) {
                        if (!line.trim().isEmpty()) {
                            try { game.samples.add(COMPACT.fromJson(line, LabGame.Sample.class)); }
                            catch (RuntimeException e) { throw new IOException("Campione non valido: " + samples.getFileName(), e); }
                        }
                    }
                }
            }
            result.add(game);
        }
        result.sort(Comparator.comparingLong((LabGame g) -> g.startedAt).reversed());
        return result;
    }

    private List<Path> records(String folder) throws IOException {
        Path directory = root.resolve(folder);
        if (!Files.exists(directory)) return Collections.emptyList();
        try (Stream<Path> stream = Files.list(directory)) {
            List<Path> paths = new ArrayList<>();
            stream.filter(p -> p.getFileName().toString().endsWith(".json")).sorted().forEach(paths::add);
            return paths;
        }
    }

    private static <T> T read(Path path, Class<T> type) throws IOException {
        if (Files.size(path) > 32L * 1024 * 1024) throw new IOException("Record troppo grande: " + path.getFileName());
        try {
            T result = JSON.fromJson(new String(Files.readAllBytes(path), StandardCharsets.UTF_8), type);
            if (result == null) throw new IllegalArgumentException("Documento vuoto");
            return result;
        } catch (RuntimeException e) { throw new IOException("Impossibile leggere " + path.getFileName(), e); }
    }

    public static void writeAtomic(Path path, String text, boolean replace) throws IOException {
        Path parent = path.toAbsolutePath().getParent();
        Files.createDirectories(parent);
        Path temporary = Files.createTempFile(parent, ".lab-", ".tmp");
        try {
            Files.write(temporary, text.getBytes(StandardCharsets.UTF_8));
            if (!replace && Files.exists(path)) return;
            if (!replace) {
                try { Files.move(temporary, path); }
                catch (FileAlreadyExistsException ignored) { /* Another importer already archived this immutable revision. */ }
            } else {
                try { Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
                catch (AtomicMoveNotSupportedException e) { Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING); }
            }
        } finally { Files.deleteIfExists(temporary); }
    }
}

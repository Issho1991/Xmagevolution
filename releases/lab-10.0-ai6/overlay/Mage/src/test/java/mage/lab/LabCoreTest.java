package mage.lab;

import mage.cards.decks.DeckCardInfo;
import mage.cards.decks.DeckCardLists;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class LabCoreTest {
    @TempDir Path directory;

    private LabDeck deck(String name, int lands) {
        DeckCardLists list = new DeckCardLists();
        list.setName(name);
        list.getCards().add(new DeckCardInfo("Mountain", "1", "TST", lands));
        list.getCards().add(new DeckCardInfo("Lightning Bolt", "2", "TST", 60 - lands));
        list.getSideboard().add(new DeckCardInfo("Forest", "3", "TST", 2));
        return LabDeck.create(list, "test");
    }

    @Test void identitiesIgnoreOrderQuantityGroupingAndLabelsButRespectZonesAndPrinting() {
        LabDeck a = deck("Prima", 24);
        DeckCardLists other = a.deck.copy();
        other.setName("Altro titolo");
        other.getCards().clear();
        for (int i = 0; i < 36; i++) other.getCards().add(new DeckCardInfo("Lightning Bolt", "2", "TST"));
        for (int i = 0; i < 24; i++) other.getCards().add(new DeckCardInfo("Mountain", "1", "TST"));
        LabDeck b = LabDeck.create(other, "second");
        assertEquals(a.id, b.id);
        assertEquals(a.compositionId, b.compositionId);
        other.getCards().set(0, new DeckCardInfo("Lightning Bolt", "22", "NEW"));
        b = LabDeck.create(other, "third");
        assertNotEquals(a.id, b.id);
        assertEquals(a.compositionId, b.compositionId);
        other.getSideboard().add(other.getCards().remove(0));
        assertNotEquals(a.compositionId, LabDeck.create(other, "moved").compositionId);
    }

    @Test void importsUtf8BomCrLfCommentsAndPreservesUnknownCards() throws Exception {
        Path file = directory.resolve("prova.dck");
        String source = "\uFEFF# Commento\r\nNAME: Prova è\r\nAUTHOR: Nicola\r\n2 [AAA:1a] Carta futura\r\nSB: 1 [BBB:2] Forest\r\nLAYOUT MAIN:ignored\r\n";
        Files.write(file, source.getBytes(StandardCharsets.UTF_8));
        LabDeck d = LabDeck.readDck(file);
        assertEquals("Prova è", d.deck.getName());
        assertEquals("Nicola", d.deck.getAuthor());
        assertEquals(2, d.deck.getCards().get(0).getAmount());
        assertEquals("Carta futura", d.deck.getCards().get(0).getCardName());
        assertEquals(1, d.deck.getSideboard().size());
        assertEquals(source, new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
    }

    @Test void rejectsMalformedEmptyAndInvalidQuantitiesInsteadOfDroppingLines() throws Exception {
        Path file = directory.resolve("bad.dck");
        for (String source : Arrays.asList("", "# only comments", "2 [TST:1] Mountain\ninvalid", "0 [TST:1] Mountain", "-1 [TST:1] Mountain", "9999999999 [TST:1] Mountain")) {
            Files.write(file, source.getBytes(StandardCharsets.UTF_8));
            assertThrows(java.io.IOException.class, () -> LabDeck.readDck(file), source);
        }
    }

    @Test void savesIndependentRevisionsAndNeverUpdatesExistingRevision() throws Exception {
        LabStore store = new LabStore(directory);
        LabDeck a = deck("Originale", 24);
        store.saveDeck(a);
        byte[] original = Files.readAllBytes(directory.resolve("decks").resolve(a.id + ".json"));
        LabDeck alias = deck("Nuovo nome", 24);
        store.saveDeck(alias);
        store.saveDeck(deck("Variante", 25));
        assertArrayEquals(original, Files.readAllBytes(directory.resolve("decks").resolve(a.id + ".json")));
        assertEquals(2, store.decks().size());
        a.deck.getCards().clear();
        assertEquals(2, store.decks().get(0).deck.getCards().size());
    }

    @Test void detectsModifiedArchivedDecks() throws Exception {
        LabStore store = new LabStore(directory);
        LabDeck a = deck("Originale", 24);
        store.saveDeck(a);
        Path file = directory.resolve("decks").resolve(a.id + ".json");
        String text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8).replace("Lightning Bolt", "Fireball");
        Files.write(file, text.getBytes(StandardCharsets.UTF_8));
        assertThrows(java.io.IOException.class, store::decks);
    }

    @Test void exactProbabilitiesMatchEnumerationOfEverySmallDeck() {
        for (int n = 0; n <= 10; n++) for (int successes = 0; successes <= n; successes++) for (int draws = 0; draws <= n; draws++) {
            int[] histogram = new int[draws + 1];
            int combinations = 0;
            for (int mask = 0; mask < (1 << n); mask++) if (Integer.bitCount(mask) == draws) {
                histogram[Integer.bitCount(mask & ((1 << successes) - 1))]++; combinations++;
            }
            for (int hits = 0; hits <= draws; hits++) {
                assertEquals((double) histogram[hits] / combinations, LabAnalysis.probability(n, successes, draws, hits, hits), 1e-12);
            }
            assertEquals(1, LabAnalysis.probability(n, successes, draws, 0, draws), 1e-12);
        }
    }

    @Test void handlesProbabilityBoundariesAndKnownHypergeometricValue() {
        assertEquals(0.02161452725913783, LabAnalysis.probability(60, 24, 7, 0, 0), 1e-12);
        assertEquals(1, LabAnalysis.probability(60, 60, 7, 7, 7), 1e-12);
        assertEquals(0, LabAnalysis.probability(60, 0, 7, 1, 7), 1e-12);
        assertThrows(IllegalArgumentException.class, () -> LabAnalysis.probability(60, 61, 7, 0, 7));
        assertThrows(IllegalArgumentException.class, () -> LabAnalysis.probability(6, 2, 7, 0, 7));
    }

    @Test void unknownCardsSuppressProbabilitiesAndStayVisible() {
        LabAnalysis a = LabAnalysis.analyze(deck("Unknown", 24), name -> null);
        assertEquals(60, a.total);
        assertEquals(2, a.sideboard);
        assertEquals(2, a.unknown.size());
        assertTrue(a.describe(7, 3).contains("ANALISI INCOMPLETA"));
        assertFalse(a.describe(7, 3).contains("REGOLARITÀ DELLE PESCATE"));
    }

    @Test void differencesRespectMainDeckSideboardAndQuantity() {
        String diff = LabAnalysis.differences(deck("A", 24), deck("B", 25));
        assertTrue(diff.contains("-1 Lightning Bolt"));
        assertTrue(diff.contains("+1 Mountain"));
        assertTrue(diff.contains("Sideboard:\nStessa composizione."));
    }

    private LabGame game(LabDeck deck, String outcome) {
        LabGame game = new LabGame();
        game.id = UUID.randomUUID().toString(); game.build = "test"; game.mode = "FreeForAll"; game.status = "COMPLETED";
        game.startingLife = 30; game.startingPlayer = "p1";
        for (int i = 1; i <= 4; i++) {
            LabGame.Participant p = new LabGame.Participant();
            p.id = "p" + i; p.name = p.id; p.seat = i; p.pilot = "AI depth6";
            p.compositionId = i == 1 ? deck.compositionId : "opponent" + i;
            p.result = i == 1 ? outcome : "LOSS";
            game.players.add(p);
        }
        return game;
    }

    @Test void comparableRecordsShareContextButDifferentModesPilotsAndSeatsDoNot() {
        LabGame a = game(deck("A", 24), "WIN"), b = game(deck("B", 25), "LOSS");
        String context = a.context(a.players.get(0));
        assertEquals(context, b.context(b.players.get(0)));
        b.startingLife = 20;
        assertNotEquals(context, b.context(b.players.get(0)));
        b.startingLife = 30; b.players.get(2).pilot = "AI depth4";
        assertNotEquals(context, b.context(b.players.get(0)));
        b.players.get(2).pilot = "AI depth6"; b.players.get(2).seat = 4; b.players.get(3).seat = 3;
        assertNotEquals(context, b.context(b.players.get(0)));
    }

    @Test void incompleteRollbackTimeoutAndErrorRecordsAreExcluded() {
        LabDeck a = deck("A", 24), b = deck("B", 25);
        List<LabGame> games = new ArrayList<>();
        games.add(game(a, "WIN")); games.add(game(b, "LOSS"));
        for (String exclusion : Arrays.asList("rollback", "timeout", "errore")) {
            LabGame excluded = game(a, "WIN"); excluded.exclusions.add(exclusion); games.add(excluded);
        }
        LabGame unfinished = game(a, "WIN"); unfinished.status = "IN_PROGRESS"; games.add(unfinished);
        String report = LabReports.compareGames(a, b, games);
        assertTrue(report.contains("incomplete/escluse: 4"));
        assertTrue(report.contains("A: 1 vittorie, 0 pareggi, 0 sconfitte"));
        assertTrue(report.contains("B: 0 vittorie, 0 pareggi, 1 sconfitte"));
    }

    @Test void replacingGameCheckpointDoesNotDuplicateGameOrLoseFinalResult() throws Exception {
        LabStore store = new LabStore(directory);
        LabGame game = game(deck("A", 24), "WIN"); game.status = "IN_PROGRESS";
        store.saveGame(game); game.status = "COMPLETED"; store.saveGame(game);
        assertEquals(1, store.games().size());
        assertEquals("COMPLETED", store.games().get(0).status);
        try (java.util.stream.Stream<Path> files = Files.list(directory.resolve("games"))) {
            assertEquals(1, files.count());
        }
    }

    @Test void readsIncrementalSamplesAndIgnoresOnlyAnIncompleteFinalLine() throws Exception {
        LabStore store = new LabStore(directory);
        LabGame game = game(deck("A", 24), "WIN"); game.status = "IN_PROGRESS";
        store.saveGame(game);
        LabGame.Sample sample = new LabGame.Sample(); sample.globalTurn = 3; sample.phase = "PRECOMBAT_MAIN";
        store.appendSample(game.id, sample);
        Files.write(directory.resolve("games").resolve(game.id + ".samples.jsonl"), "{unfinished".getBytes(StandardCharsets.UTF_8), StandardOpenOption.APPEND);
        assertEquals(1, store.games().get(0).samples.size());
        assertEquals(3, store.games().get(0).samples.get(0).globalTurn);
        game.status = "COMPLETED"; game.samples.add(sample); store.saveGame(game);
        assertEquals(1, store.games().get(0).samples.size());
    }
}

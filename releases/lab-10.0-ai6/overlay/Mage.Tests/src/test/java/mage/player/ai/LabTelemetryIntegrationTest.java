package mage.player.ai;

import mage.cards.Card;
import mage.cards.CardSetInfo;
import mage.cards.basiclands.Mountain;
import mage.cards.decks.Deck;
import mage.cards.m.Memnite;
import mage.constants.*;
import mage.game.*;
import mage.game.events.GameEvent;
import mage.game.events.ZoneChangeEvent;
import mage.game.match.MatchOptions;
import mage.game.mulligan.LondonMulligan;
import mage.lab.*;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.util.*;
import java.util.stream.Collectors;
import static org.junit.Assert.*;

public class LabTelemetryIntegrationTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder();
    private CardSetInfo info(String name) { return new CardSetInfo(name, "TST", "1", Rarity.COMMON); }
    private LabStore store() { return new LabStore(folder.getRoot().toPath()); }
    private ForcedPassPriorityTest.Scenario scenario() {
        ForcedPassPriorityTest.Scenario s = new ForcedPassPriorityTest.Scenario(4, 0, 0);
        s.game.setStartingPlayerId(s.ai.getId());
        for (ComputerPlayer7 player : s.players) player.getMatchPlayer().getDeck().getCards().add(new Mountain(player.getId(), info("Mountain")));
        return s;
    }

    @Test public void recordsActualDrawCastLandDiscardAndZoneTransitionsOnce() throws Exception {
        ForcedPassPriorityTest.Scenario s = scenario();
        Card creature = new Memnite(s.ai.getId(), info("Memnite"));
        s.game.loadCards(Collections.singleton(creature), s.ai.getId());
        s.ai.getLibrary().addAll(Collections.singleton(creature), s.game);
        Card land = new Mountain(s.ai.getId(), info("Mountain"));
        s.put(land, s.ai, Zone.HAND);
        Card discarded = new Mountain(s.ai.getId(), info("Mountain"));
        s.put(discarded, s.ai, Zone.HAND);
        LabRecorder recorder = LabRecorder.start(s.game, store());
        LabRecorder.mulliganFinished(s.game);
        assertEquals(1, s.ai.drawCards(1, null, s.game));
        assertTrue(s.ai.playLand(land, s.game, false));
        assertTrue(s.ai.cast(creature.getSpellAbility(), s.game, false, null));
        assertTrue(s.ai.discard(discarded, false, null, s.game));
        s.game.getState().handleSimultaneousEvent(s.game);
        recorder.finish(s.game, false);
        List<LabGame.Event> events = store().events(s.game.getId().toString());
        for (String kind : Arrays.asList("DREW_CARD", "SPELL_CAST", "LAND_PLAYED", "DISCARDED_CARD"))
            assertEquals(kind, 1, events.stream().filter(e -> kind.equals(e.kind)).count());
        LabGame.Event cast = events.stream().filter(e -> "SPELL_CAST".equals(e.kind)).findFirst().get();
        assertEquals("Memnite", cast.sourceName);
        assertTrue(events.stream().anyMatch(e -> "ZONE_CHANGE".equals(e.kind) && "GRAVEYARD".equals(e.to) && discarded.getId().toString().equals(e.targetId)));
        assertTrue(LabReports.telemetry(store().games().get(0), events).contains("Conteggi omessi: copertura non confermata."));
        assertTrue(LabReports.telemetry(store().games().get(0), events).contains("Memnite"));
    }

    @Test public void copiesWithTheSameUuidCannotRecordEventsHandsTimingsOrFinishOriginal() throws Exception {
        ForcedPassPriorityTest.Scenario s = scenario();
        LabRecorder recorder = LabRecorder.start(s.game, store());
        Game copy = s.game.copy(); // intentionally not flagged as simulation
        assertEquals(s.game.getId(), copy.getId());
        assertEquals(0, LabRecorder.beginDecision(copy, copy.getPlayer(s.ai.getId())));
        copy.fireEvent(GameEvent.getEvent(GameEvent.EventType.DREW_CARD, UUID.randomUUID(), null, s.ai.getId()));
        LabRecorder.hand(copy, copy.getPlayer(s.ai.getId()), "MULLIGAN_KEEP");
        recorder.observe(copy); recorder.finish(copy, true);
        LabRecorder.hand(s.game, s.ai, "MULLIGAN_KEEP");
        recorder.finish(s.game, false);
        List<LabGame.Event> events = store().events(s.game.getId().toString());
        assertEquals(1, events.size());
        assertEquals("MULLIGAN_KEEP", events.get(0).kind);
        assertTrue(store().games().get(0).telemetry.ai.isEmpty());
    }

    @Test public void recordsRealLondonFreeAndPaidMulligansIncludingTheKeptSix() throws Exception {
        LondonMulligan mulligan = new LondonMulligan(1);
        FreeForAll game = new FreeForAll(MultiplayerAttackOption.MULTIPLE, RangeOfInfluence.ALL, mulligan, 30, 7);
        game.setGameOptions(new GameOptions()); game.setNumPlayers(2);
        FreeForAllMatch match = new FreeForAllMatch(new MatchOptions("mulligan", "FreeForAll", true));
        List<ComputerPlayer7> players = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            final int rejects = i == 0 ? 2 : 0;
            ComputerPlayer7 player = new ComputerPlayer7("AI" + i, RangeOfInfluence.ALL, 6) {
                int decisions;
                @Override public boolean chooseMulligan(Game g) { return decisions++ < rejects; }
            };
            Deck deck = new Deck();
            for (int n = 0; n < 20; n++) deck.getCards().add(new Mountain(player.getId(), info("Mountain")));
            game.addPlayer(player, deck); match.addPlayer(player, deck); player.init(game);
            game.loadCards(deck.getCards(), player.getId()); player.getLibrary().addAll(deck.getCards(), game);
            players.add(player);
        }
        game.setStartingPlayerId(players.get(0).getId());
        LabRecorder recorder = LabRecorder.start(game, store());
        for (ComputerPlayer7 player : players) mulligan.drawHand(7, player, game);
        mulligan.executeMulliganPhase(game, 7);
        recorder.finish(game, false);
        List<LabGame.Event> hands = store().events(game.getId().toString()).stream()
                .filter(e -> players.get(0).getId().toString().equals(e.playerId) && e.kind.startsWith("MULLIGAN_")).collect(Collectors.toList());
        assertEquals(Arrays.asList("MULLIGAN_HAND", "MULLIGAN_REJECT", "MULLIGAN_HAND", "MULLIGAN_REJECT", "MULLIGAN_HAND", "MULLIGAN_KEEP"),
                hands.stream().map(e -> e.kind).collect(Collectors.toList()));
        assertEquals(Arrays.asList(7, 7, 7, 7, 7, 6), hands.stream().map(e -> e.amount).collect(Collectors.toList()));
        assertEquals(6, hands.get(5).handCards.size());
        assertEquals(Integer.valueOf(6), hands.get(5).hand.get("Mountain"));
        assertEquals(6, players.get(0).getHand().size());
        assertEquals(7, players.get(1).getHand().size());
    }

    @Test public void firstToTwoFourPlayerMatchCanHaveFiveManchesAndUsesEngineScore() throws Exception {
        String previous = System.getProperty("xmage.lab.dir");
        System.setProperty("xmage.lab.dir", folder.getRoot().getAbsolutePath());
        try {
            MatchOptions options = new MatchOptions("match", "FreeForAll", true); options.setWinsNeeded(2);
            FreeForAllMatch match = new FreeForAllMatch(options);
            List<ComputerPlayer7> players = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                ComputerPlayer7 player = new ComputerPlayer7("AI" + i, RangeOfInfluence.ALL, 6);
                Deck deck = new Deck(); deck.getCards().add(new Mountain(player.getId(), info("Mountain")));
                match.addPlayer(player, deck); players.add(player);
            }
            int[] winners = {0, 1, 2, 3, 0};
            for (int round = 0; round < winners.length; round++) {
                match.startGame();
                Game game = match.getGame();
                // This fixture resolves winners directly; initialize the clock normally set by Game.start.
                java.lang.reflect.Field clock = GameImpl.class.getDeclaredField("startTime");
                clock.setAccessible(true); clock.set(game, new Date());
                GameOptions gameOptions = new GameOptions(); gameOptions.laboratoryMatch = LabRecorder.matchInfo(match);
                game.setGameOptions(gameOptions); game.setStartingPlayerId(players.get(0).getId());
                LabRecorder recorder = LabRecorder.start(game, store());
                for (ComputerPlayer7 player : players) player.updateRange(game);
                players.get(winners[round]).won(game);
                // Game.start normally settles the winner ID after the loop; keep the engine's resolved winner.
                java.lang.reflect.Field winnerId = GameImpl.class.getDeclaredField("winnerId");
                winnerId.setAccessible(true); winnerId.set(game, players.get(winners[round]).getId());
                recorder.finish(game, true);
                match.endGame(); // actual engine award + persistence hook
                LabGame data = store().game(game.getId().toString());
                assertEquals(round + 1, data.match.round);
                assertTrue(data.match.resultRecorded);
                assertEquals(round == 4, data.match.finished);
                assertEquals(match.getPlayer(players.get(0).getId()).getWins(), (int) data.match.scoreAfter.get(players.get(0).getId().toString()));
                assertEquals(round == 4, match.hasEnded());
            }
            LabReviewService.scheduleMatch(store(), match.getId().toString(), match.getGames().stream()
                    .map(g -> g.getId().toString()).collect(Collectors.toList())).get(5, java.util.concurrent.TimeUnit.SECONDS);
            String report = LabReports.matches(store().games());
            assertTrue(report, report.contains("Manche registrate: 5"));
            assertTrue(report, report.contains("Copertura: tutte le manche e punteggio finale · utilizzabile"));
            assertTrue(report, report.contains("Vincitore dell’incontro: AI0"));
        } finally {
            if (previous == null) System.clearProperty("xmage.lab.dir"); else System.setProperty("xmage.lab.dir", previous);
        }
    }

    @Test public void savesHandBeforeActualPlayerLeaveAndTimesOnlyOriginalAi() throws Exception {
        ForcedPassPriorityTest.Scenario s = scenario();
        s.put(new Memnite(s.ai.getId(), info("Memnite")), s.ai, Zone.HAND);
        LabRecorder recorder = LabRecorder.start(s.game, store());
        LabRecorder.endDecision(s.game, s.ai, "PRIORITY", System.nanoTime() - 200000000L);
        LabRecorder.endDecision(s.game, s.ai, "ATTACK", LabRecorder.beginDecision(s.game, s.ai));
        s.ai.lostForced(s.game);
        s.game.checkIfGameIsOver(); // consumes pending leaving players on the game thread
        recorder.finish(s.game, false);
        LabGame data = store().games().get(0);
        LabGame.Sample before = data.samples.stream().filter(e -> "PRE_ELIMINATION".equals(e.phase)).findFirst().get();
        assertEquals(s.ai.getId().toString(), before.departingPlayer);
        LabGame.Position position = before.players.stream().filter(p -> s.ai.getId().toString().equals(p.playerId)).findFirst().get();
        assertEquals(Integer.valueOf(1), position.hand.get("Memnite"));
        assertEquals(0, s.ai.getHand().size());
        assertEquals(1, data.telemetry.ai.get(s.ai.getId() + ":PRIORITY").calls);
        assertEquals(1, data.telemetry.ai.get(s.ai.getId() + ":ATTACK").calls);
        assertTrue(data.telemetry.ai.get(s.ai.getId() + ":PRIORITY").totalNanos >= 200000000L);
        assertEquals(1, store().events(data.id).stream().filter(e -> "AI_PRIORITY".equals(e.kind)).count());
    }

    @Test public void skipsHumanAndControlledPilotsAndFlagsActualUndo() throws Exception {
        ForcedPassPriorityTest.Scenario s = scenario();
        LabRecorder recorder = LabRecorder.start(s.game, store());
        mage.player.human.HumanPlayer human = new mage.player.human.HumanPlayer("Human", RangeOfInfluence.ALL, 1);
        assertEquals(0, LabRecorder.beginDecision(s.game, human));
        s.ai.setTurnControlledBy(s.players.get(1).getId());
        s.ai.setGameUnderYourControl(s.game, false);
        assertEquals(0, LabRecorder.beginDecision(s.game, s.ai));
        s.ai.setGameUnderYourControl(s.game, true);
        java.lang.reflect.Field order = GameImpl.class.getDeclaredField("playerList");
        order.setAccessible(true); order.set(s.game, s.game.getPlayerList());
        int bookmark = s.game.bookmarkState();
        LabRecorder.hand(s.game, s.ai, "MULLIGAN_KEEP");
        assertNotNull(s.game.restoreState(bookmark, "test undo"));
        recorder.finish(s.game, false);
        LabGame data = store().games().get(0);
        assertTrue(data.telemetry.stateRestored);
        assertFalse(data.eligible());
        assertEquals(1, store().events(data.id).stream().filter(e -> "STATE_RESTORED".equals(e.kind)).count());
    }

    @Test public void recordsReplacementDestinationAndBoundsTraceWhileKeepingTimingTotals() throws Exception {
        ForcedPassPriorityTest.Scenario s = scenario();
        Card card = new Mountain(s.ai.getId(), info("Mountain")); s.put(card, s.ai, Zone.HAND);
        LabRecorder recorder = LabRecorder.start(s.game, store());
        ZoneChangeEvent moved = new ZoneChangeEvent(card.getId(), null, s.ai.getId(), Zone.HAND, Zone.GRAVEYARD);
        moved.setToZone(Zone.EXILED); // destination after a replacement; hook reads completed event
        LabRecorder.event(s.game, moved);
        long start = System.nanoTime();
        for (int i = 0; i < 22000; i++) LabRecorder.event(s.game, GameEvent.getEvent(GameEvent.EventType.DREW_CARD, card.getId(), null, s.ai.getId()));
        long elapsed = System.nanoTime() - start;
        LabRecorder.endDecision(s.game, s.ai, "BLOCK", System.nanoTime() - 200000000L);
        recorder.finish(s.game, false);
        LabGame data = store().games().get(0);
        List<LabGame.Event> events = store().events(data.id);
        assertTrue(events.size() <= 20000);
        assertFalse(data.telemetry.warnings.isEmpty());
        assertFalse(data.telemetry.complete);
        assertEquals("EXILED", events.get(0).to);
        assertEquals(1, data.telemetry.ai.get(s.ai.getId() + ":BLOCK").calls);
        assertEquals(Long.valueOf(22000), data.telemetry.counts.get(s.ai.getId().toString()).get("MULLIGAN:DREW_CARD"));
        System.out.printf(Locale.ROOT, "LAB_EVENT_TRACE events=%d hook_average_us=%.3f%n", events.size(), elapsed / 22000.0 / 1000.0);
    }
}

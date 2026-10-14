package mage.player.ai;

import mage.abilities.Ability;
import mage.abilities.PlayLandAbility;
import mage.abilities.effects.OneShotEffect;
import mage.cards.Card;
import mage.cards.CardImpl;
import mage.cards.CardSetInfo;
import mage.cards.basiclands.Swamp;
import mage.constants.*;
import mage.game.Game;
import mage.game.GameImpl;
import org.junit.Test;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

/** Real play/restore/search paths reproducing the Lab7 traces, without a card database. */
public class DecisionRecoveryAITest {
    private static CardSetInfo info(String name) {
        return new CardSetInfo(name, "TST", "1", Rarity.COMMON);
    }

    ForcedPassPriorityTest.Scenario scenario() throws Exception {
        ForcedPassPriorityTest.Scenario s = new ForcedPassPriorityTest.Scenario(4, 0, 0);
        s.ai.maxDepth = 4;
        s.ai.setMaxThinkTimeSecs(3);
        s.game.setStartingPlayerId(s.ai.getId());
        java.lang.reflect.Field order = GameImpl.class.getDeclaredField("playerList");
        order.setAccessible(true);
        order.set(s.game, s.game.getPlayerList());
        s.put(new Swamp(s.ai.getId(), info("Swamp")), s.ai, Zone.BATTLEFIELD);
        s.put(new Swamp(s.ai.getId(), info("Swamp")), s.ai, Zone.BATTLEFIELD);
        return s;
    }

    Card land(ForcedPassPriorityTest.Scenario s) {
        Card card = new Swamp(s.ai.getId(), info("Swamp"));
        s.put(card, s.ai, Zone.HAND);
        return card;
    }

    private void playSelectedLand(ForcedPassPriorityTest.Scenario s, Card card) {
        s.ai.calculateActions(s.game);
        assertFalse("A legal land must remain selectable", s.ai.actions.isEmpty());
        assertTrue(s.ai.actions.getFirst() instanceof PlayLandAbility);
        assertEquals(card.getId(), s.ai.actions.getFirst().getSourceId());
        s.ai.act(s.game);
        assertNotNull(s.game.getPermanent(card.getId()));
        assertEquals(1, s.ai.getLandsPlayed());
    }

    @Test public void bookmarkRestoreAllowsTheSameLandToBePlayedAgain() throws Exception {
        ForcedPassPriorityTest.Scenario s = scenario();
        Card card = land(s);
        int bookmark = s.game.bookmarkState();
        playSelectedLand(s, card);
        assertNotNull(s.game.restoreState(bookmark, "undo"));
        assertTrue(s.ai.getHand().contains(card.getId()));
        assertEquals(0, s.ai.getLandsPlayed());
        playSelectedLand(s, card);
    }

    @Test public void repeatedTurnRollbackDoesNotRememberTheAbandonedLandPlay() throws Exception {
        ForcedPassPriorityTest.Scenario s = scenario();
        s.game.getOptions().rollbackTurnsAllowed = true;
        Card card = land(s);
        s.game.saveRollBackGameState();
        java.lang.reflect.Method restore = GameImpl.class.getDeclaredMethod("rollbackTurnsExecution", int.class);
        restore.setAccessible(true);
        for (int i = 0; i < 3; i++) {
            playSelectedLand(s, card);
            restore.invoke(s.game, 1);
            assertTrue(s.ai.getHand().contains(card.getId()));
            assertEquals(0, s.ai.getLandsPlayed());
        }
        playSelectedLand(s, card);
    }

    @Test public void copyingTheGameDoesNotShareActionHistoryOrSearchLimits() throws Exception {
        ForcedPassPriorityTest.Scenario s = scenario();
        s.ai.maxNodes = 123;
        s.ai.setMaxThinkTimeSecs(7);
        s.ai.actionCache.add("past");
        ComputerPlayer6 saved = (ComputerPlayer6) s.game.copy().getPlayer(s.ai.getId());
        s.ai.actionCache.add("future");
        assertFalse(saved.actionCache.contains("future"));
        saved.actionCache.add("copy only");
        assertFalse(s.ai.actionCache.contains("copy only"));
        assertEquals(123, saved.maxNodes);
        assertEquals(7, saved.maxThinkTimeSecs);
        s.ai.restore(saved);
        assertTrue(s.ai.actionCache.contains("past"));
        assertFalse(s.ai.actionCache.contains("future"));
    }

    @Test public void restoreDiscardsQueuedActionsAndSearchContinuation() throws Exception {
        ForcedPassPriorityTest.Scenario s = scenario();
        Card card = land(s);
        int bookmark = s.game.bookmarkState();
        s.ai.calculateActions(s.game);
        assertFalse(s.ai.actions.isEmpty());
        s.game.restoreState(bookmark, "undo");
        assertTrue(s.ai.actions.isEmpty());
        assertNull(s.ai.root);
        playSelectedLand(s, card);
    }

    @Test public void timeoutKeepsAnEvaluatedLandAndLateWorkerCannotOverwriteNextSearch() throws Exception {
        ForcedPassPriorityTest.Scenario s = scenario();
        Card card = land(s);
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1), finished = new CountDownLatch(1);
        SlowSpell slow = new SlowSpell(s.ai.getId(), entered, release, finished);
        s.put(slow, s.ai, Zone.HAND);
        s.ai.setMaxThinkTimeSecs(1);
        try {
            playSelectedLand(s, card);
            assertEquals("Fixture must have reached the slow simulation", 0, entered.getCount());
            assertNull("Cancelled tree must not be reused", s.ai.root);
            // The next real decision starts while the cancelled effect has not returned.
            s.ai.getHand().remove(slow.getId());
            s.game.setZone(slow.getId(), Zone.EXILED);
            s.ai.calculateActions(s.game);
            SimulationNode2 next = s.ai.root;
            assertNotNull(next);
            release.countDown();
            assertTrue(finished.await(3, TimeUnit.SECONDS));
            assertSame(next, s.ai.root);
            assertNotNull(s.game.getPermanent(card.getId()));
            assertEquals(1, s.ai.getLandsPlayed());
        } finally {
            release.countDown();
        }
    }

    @Test public void timeoutWithoutEvaluatedActionDoesNotReuseAnOldDecision() throws Exception {
        ForcedPassPriorityTest.Scenario s = scenario();
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1), finished = new CountDownLatch(1);
        s.put(new SlowSpell(s.ai.getId(), entered, release, finished), s.ai, Zone.HAND);
        s.ai.setMaxThinkTimeSecs(1);
        try {
            s.ai.calculateActions(s.game);
            assertEquals(0, entered.getCount());
            assertTrue(s.ai.actions.isEmpty());
            assertNull(s.ai.root);
        } finally {
            release.countDown();
            assertTrue(finished.await(3, TimeUnit.SECONDS));
        }
    }

    @Test public void landLimitIsStillEnforcedAfterNormalSelection() throws Exception {
        ForcedPassPriorityTest.Scenario s = scenario();
        Card first = land(s), second = land(s);
        s.ai.calculateActions(s.game);
        assertFalse(s.ai.actions.isEmpty());
        s.ai.act(s.game);
        assertEquals(1, s.ai.getLandsPlayed());
        s.ai.calculateActions(s.game);
        assertTrue(s.ai.actions.stream().noneMatch(a -> a instanceof PlayLandAbility));
        assertTrue(s.ai.getHand().contains(first.getId()) || s.ai.getHand().contains(second.getId()));
    }

    static final class SlowSpell extends CardImpl {
        SlowSpell(UUID owner, CountDownLatch entered, CountDownLatch release, CountDownLatch finished) {
            this(owner, entered, release, finished, false);
        }
        SlowSpell(UUID owner, CountDownLatch entered, CountDownLatch release, CountDownLatch finished, boolean beforeLandOnly) {
            super(owner, info("Slow test spell"), new CardType[]{CardType.SORCERY}, "{0}");
            getSpellAbility().addEffect(new SlowEffect(entered, release, finished, beforeLandOnly));
        }
        SlowSpell(SlowSpell card) { super(card); }
        @Override public SlowSpell copy() { return new SlowSpell(this); }
    }

    private static final class SlowEffect extends OneShotEffect {
        // Shared test instrumentation, intentionally retained by the simulated copy.
        private final CountDownLatch entered, release, finished;
        private final boolean beforeLandOnly;
        SlowEffect(CountDownLatch entered, CountDownLatch release, CountDownLatch finished, boolean beforeLandOnly) {
            super(Outcome.Neutral);
            this.entered = entered; this.release = release; this.finished = finished;
            this.beforeLandOnly = beforeLandOnly;
            staticText = "wait in a simulated resolution";
        }
        SlowEffect(SlowEffect effect) {
            super(effect); entered = effect.entered; release = effect.release; finished = effect.finished;
            beforeLandOnly = effect.beforeLandOnly;
        }
        @Override public boolean apply(Game game, Ability source) {
            if (game.isSimulation() && (!beforeLandOnly || game.getPlayer(source.getControllerId()).getLandsPlayed() == 0)) {
                entered.countDown();
                try {
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                    while (release.getCount() != 0 && System.nanoTime() < deadline) {
                        try { release.await(50, TimeUnit.MILLISECONDS); }
                        catch (InterruptedException ignored) { /* deliberately emulate a late worker */ }
                    }
                } finally { finished.countDown(); }
            }
            return true;
        }
        @Override public SlowEffect copy() { return new SlowEffect(this); }
    }
}

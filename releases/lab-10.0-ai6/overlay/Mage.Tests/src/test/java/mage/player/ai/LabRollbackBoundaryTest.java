package mage.player.ai;

import mage.cards.CardSetInfo;
import mage.cards.basiclands.Mountain;
import mage.constants.Rarity;
import mage.game.GameImpl;
import mage.lab.*;
import mage.players.Player;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.lang.reflect.*;
import java.util.*;
import static org.junit.Assert.*;

/** A real four-player engine fixture: elimination is not a reversible player field. */
public class LabRollbackBoundaryTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder();
    private LabStore store() { return new LabStore(folder.getRoot().toPath()); }
    private ForcedPassPriorityTest.Scenario scenario() throws Exception {
        ForcedPassPriorityTest.Scenario s = new ForcedPassPriorityTest.Scenario(4, 0, 0);
        s.game.setStartingPlayerId(s.ai.getId());
        s.game.getOptions().rollbackTurnsAllowed = true;
        Field order = GameImpl.class.getDeclaredField("playerList"); order.setAccessible(true);
        order.set(s.game, s.game.getPlayerList());
        for (ComputerPlayer7 p : s.players) p.getMatchPlayer().getDeck().getCards().add(
                new Mountain(p.getId(), new CardSetInfo("Mountain", "TST", "1", Rarity.COMMON)));
        return s;
    }
    private void eliminate(ForcedPassPriorityTest.Scenario s, Player victim) throws Exception {
        victim.setLife(0, s.game, null);
        Method m = GameImpl.class.getDeclaredMethod("checkStateBasedActions"); m.setAccessible(true); m.invoke(s.game);
        s.game.checkConcede(false);
        assertFalse(victim.isInGame());
    }
    private void apply(ForcedPassPriorityTest.Scenario s, int turn) throws Exception {
        Method m = GameImpl.class.getDeclaredMethod("rollbackTurnsExecution", int.class);
        m.setAccessible(true); m.invoke(s.game, turn);
    }
    @Test public void turnBeforeEliminationIsUnavailableAndCannotChangeState() throws Exception {
        ForcedPassPriorityTest.Scenario s = scenario();
        LabRecorder r = LabRecorder.start(s.game, store()); LabRecorder.mulliganFinished(s.game);
        s.game.saveRollBackGameState();
        s.game.getState().setTurnNum(2); eliminate(s, s.players.get(1));
        String before = s.game.getState().getValue(true);
        assertFalse("Must refuse a turn containing a player already eliminated", s.game.canRollbackTurns(1));
        s.game.rollbackTurns(1);
        assertFalse(s.game.executingRollback());
        assertEquals(before, s.game.getState().getValue(true));
        r.finish(s.game, false);
        List<LabGame.Event> events = store().events(s.game.getId().toString());
        assertEquals(1, events.stream().filter(e -> "PLAYER_EXIT".equals(e.kind)).count());
        assertFalse(events.stream().anyMatch(e -> "STATE_RESTORED".equals(e.kind)));
    }
    @Test public void bookmarkCannotRestoreLifeAndCardsAcrossElimination() throws Exception {
        ForcedPassPriorityTest.Scenario s = scenario();
        int bookmark = s.game.bookmarkState(); eliminate(s, s.players.get(1));
        assertNull(s.game.restoreState(bookmark, "undo"));
        assertEquals(0, s.players.get(1).getLife());
        assertFalse(s.players.get(1).isInGame());
    }
    @Test public void keepingStackCannotRestoreAPlayerAcrossElimination() throws Exception {
        ForcedPassPriorityTest.Scenario s = scenario();
        int bookmark = s.game.bookmarkState(); eliminate(s, s.players.get(1));
        assertNull(s.ai.restoreStateKeepingStack(bookmark, "failed mana action", s.game));
        assertEquals(0, s.players.get(1).getLife());
        assertFalse(s.players.get(1).isInGame());
    }
    @Test public void turnsAfterAnEliminationRemainReversible() throws Exception {
        ForcedPassPriorityTest.Scenario s = scenario();
        eliminate(s, s.players.get(1)); s.game.getState().setTurnNum(2); s.game.saveRollBackGameState();
        s.game.getState().setTurnNum(3); s.ai.setLife(17, s.game, null);
        assertTrue(s.game.canRollbackTurns(1)); s.game.rollbackTurns(1); apply(s, 2);
        assertEquals(2, s.game.getTurnNum()); assertEquals(30, s.ai.getLife());
        assertFalse(s.players.get(1).isInGame()); assertFalse(s.game.executingRollback());
    }
    @Test public void executionRechecksAnExitAfterTheRequest() throws Exception {
        ForcedPassPriorityTest.Scenario s = scenario(); s.game.saveRollBackGameState();
        s.game.getState().setTurnNum(2); assertTrue(s.game.canRollbackTurns(1)); s.game.rollbackTurns(1);
        eliminate(s, s.players.get(1)); apply(s, 1);
        assertEquals("Rejected pending rollback must not rewind", 2, s.game.getTurnNum());
        assertEquals(0, s.players.get(1).getLife()); assertFalse(s.players.get(1).isInGame());
        assertFalse(s.game.executingRollback());
    }
    @Test public void administrativeDepartureAlsoPreventsCrossingTheBoundary() throws Exception {
        ForcedPassPriorityTest.Scenario s = scenario(); s.game.saveRollBackGameState(); s.game.getState().setTurnNum(2);
        s.players.get(1).quit(s.game); s.game.checkConcede(false);
        assertFalse(s.game.canRollbackTurns(1));
    }
    @Test(timeout = 10000) public void rejectedPendingRequestResumesWithoutStartingOrDrawingAgain() throws Exception {
        ForcedPassPriorityTest.Scenario s = scenario(); s.game.saveRollBackGameState();
        s.game.getState().setTurnNum(2); s.game.rollbackTurns(1);
        eliminate(s, s.players.get(1));
        // The current step is already precombat main. Starting this turn again
        // would draw from the empty library and eliminate its active player.
        int turnsStarted = s.ai.getTurns();
        mage.game.turn.Phase phase = s.game.getTurn().getPhase(mage.constants.TurnPhase.PRECOMBAT_MAIN);
        Field steps = mage.game.turn.Phase.class.getDeclaredField("steps"); steps.setAccessible(true);
        mage.game.turn.Step step = ((List<mage.game.turn.Step>) steps.get(phase)).get(0);
        step.beginStep(s.game, s.ai.getId()); phase.setStep(step); s.game.getTurn().setPhase(phase);
        Method play = GameImpl.class.getDeclaredMethod("playTurn", Player.class); play.setAccessible(true);
        String threadName = Thread.currentThread().getName();
        Object result;
        try {
            Thread.currentThread().setName(mage.util.ThreadUtils.THREAD_PREFIX_GAME + "rollback-boundary-test");
            result = play.invoke(s.game, s.ai);
        } finally { Thread.currentThread().setName(threadName); }
        assertEquals("paused=" + s.game.isPaused() + " ended=" + s.game.hasEnded()
                + " active=" + s.ai.isInGame() + " turns=" + s.ai.getTurns(), Boolean.TRUE, result);
        assertTrue(s.ai.isInGame()); assertEquals(turnsStarted, s.ai.getTurns());
        assertEquals(3, s.game.getTurnNum()); assertFalse(s.game.executingRollback());
    }
}

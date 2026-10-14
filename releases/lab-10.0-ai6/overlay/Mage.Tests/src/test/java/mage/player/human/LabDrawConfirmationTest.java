package mage.player.human;

import mage.abilities.Ability;
import mage.constants.*;
import mage.game.*;
import mage.game.mulligan.MulliganType;
import mage.util.MessageToClient;
import mage.util.ThreadUtils;
import mage.players.Player;
import mage.player.ai.ComputerPlayerControllableProxy;
import org.junit.Test;
import java.io.Serializable;
import java.lang.reflect.Field;
import java.util.*;
import static org.junit.Assert.*;

public class LabDrawConfirmationTest {
    private static final String QUESTION = "Draw game because of infinite looping?";
    static class DialogGame extends FreeForAll {
        int asks;
        Map<String, Serializable> options;
        DialogGame() {
            super(MultiplayerAttackOption.MULTIPLE, RangeOfInfluence.ALL,
                    MulliganType.GAME_DEFAULT.getMulligan(0), 30, 7);
        }
        @Override public void fireAskPlayerEvent(UUID id, MessageToClient message, Ability source,
                                                 Map<String, Serializable> options) {
            asks++; this.options = options;
        }
    }
    static class DialogPlayer extends HumanPlayer {
        final boolean explicit;
        int waits;
        DialogPlayer(boolean explicit) { super("Human", RangeOfInfluence.ALL, 6); this.explicit = explicit; }
        void saveAnswer(boolean answer) { requestAutoAnswerText.put(QUESTION, answer); }
        @Override public Player prepareControllableProxy(Player controlled) { return this; }
        void queueMacro() {
            PlayerResponse r = new PlayerResponse(); r.setBoolean(true);
            actionQueue.add(r); actionQueueSaved.add(r); actionIterations = 2;
        }
        @Override protected void waitForResponse(Game game) {
            waits++;
            assertFalse("No recorded macro may supply this decision", isExecutingMacro());
            try {
                Field f = HumanPlayer.class.getDeclaredField("response"); f.setAccessible(true);
                PlayerResponse r = (PlayerResponse) f.get(this); r.resetAnswers(); r.setBoolean(explicit);
            } catch (Exception ex) { throw new AssertionError(ex); }
        }
    }
    private boolean confirm(DialogPlayer p, DialogGame g) {
        String before = Thread.currentThread().getName();
        try {
            Thread.currentThread().setName(ThreadUtils.THREAD_PREFIX_GAME + "draw-dialog-test");
            return p.confirmDraw(QUESTION, g);
        } finally { Thread.currentThread().setName(before); }
    }
    @Test public void savedYesCannotReplaceExplicitNo() {
        DialogPlayer p = new DialogPlayer(false); DialogGame g = new DialogGame();
        p.saveAnswer(true);
        assertFalse(confirm(p, g)); assertEquals(1, g.asks); assertEquals(1, p.waits);
        assertFalse(g.options.containsKey(Constants.Option.AUTO_ANSWER_MESSAGE));
    }
    @Test public void savedNoCannotReplaceExplicitYes() {
        DialogPlayer p = new DialogPlayer(true); DialogGame g = new DialogGame();
        p.saveAnswer(false);
        assertTrue(confirm(p, g)); assertEquals(1, g.asks); assertEquals(1, p.waits);
    }
    @Test public void queuedMacroCannotConfirmDraw() {
        DialogPlayer p = new DialogPlayer(false); DialogGame g = new DialogGame(); p.queueMacro();
        assertFalse(confirm(p, g)); assertEquals(1, g.asks); assertEquals(1, p.waits);
        assertTrue(p.actionQueue.isEmpty()); assertTrue(p.actionQueueSaved.isEmpty());
    }
    @Test public void unavailableHumanDoesNotAgree() {
        DialogPlayer p = new DialogPlayer(true); DialogGame g = new DialogGame(); p.abort();
        assertFalse(confirm(p, g)); assertEquals(0, g.asks); assertEquals(0, p.waits);
    }
    @Test public void controlledComputerDelegatesToFreshHumanConfirmation() {
        DialogPlayer p = new DialogPlayer(false); DialogGame g = new DialogGame(); p.saveAnswer(true);
        ComputerPlayerControllableProxy cpu = new ComputerPlayerControllableProxy("CPU", RangeOfInfluence.ALL, 6);
        g.getState().getPlayers().put(p.getId(), p);
        g.getState().getPlayers().put(cpu.getId(), cpu);
        cpu.setTurnControlledBy(p.getId()); cpu.setGameUnderYourControl(g, false, false);
        String before = Thread.currentThread().getName();
        try {
            Thread.currentThread().setName(ThreadUtils.THREAD_PREFIX_GAME + "draw-proxy-test");
            assertFalse(cpu.confirmDraw(QUESTION, g));
        } finally { Thread.currentThread().setName(before); }
        assertEquals(1, g.asks); assertEquals(1, p.waits);
    }
}

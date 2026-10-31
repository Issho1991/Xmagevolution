package mage.player.ai;

import mage.cards.*;
import mage.cards.basiclands.Mountain;
import mage.cards.m.Memnite;
import mage.constants.*;
import mage.game.*;
import mage.game.turn.*;
import mage.game.events.GameEvent;
import mage.lab.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.util.*;
import static org.junit.Assert.*;

public class LabUndoIntegrationTest {
    @Rule public TemporaryFolder folder=new TemporaryFolder();
    private LabStore store(){return new LabStore(folder.getRoot().toPath());}
    private CardSetInfo info(String name){return new CardSetInfo(name,"TST","1",Rarity.COMMON);}
    private ForcedPassPriorityTest.Scenario scenario() throws Exception {
        ForcedPassPriorityTest.Scenario s=new ForcedPassPriorityTest.Scenario(4,0,0);s.game.setStartingPlayerId(s.ai.getId());
        for(ComputerPlayer7 p:s.players)p.getMatchPlayer().getDeck().getCards().add(new Mountain(p.getId(),info("Mountain")));
        java.lang.reflect.Field order=GameImpl.class.getDeclaredField("playerList");order.setAccessible(true);order.set(s.game,s.game.getPlayerList());
        return s;
    }
    private LabGame end(ForcedPassPriorityTest.Scenario s,LabRecorder recorder) throws Exception {
        s.ai.won(s.game);
        // Game.play normally settles this after leaving its loop; the fixture resolves a winner directly.
        java.lang.reflect.Field winner=GameImpl.class.getDeclaredField("winnerId");winner.setAccessible(true);winner.set(s.game,s.ai.getId());
        recorder.finish(s.game,true);return store().games().get(0);
    }
    private void event(ForcedPassPriorityTest.Scenario s,String name) {
        Card c=new Memnite(s.ai.getId(),info(name));s.put(c,s.ai,Zone.HAND);
        s.game.fireEvent(GameEvent.getEvent(GameEvent.EventType.DREW_CARD,c.getId(),null,s.ai.getId()));
    }
    @Test public void CanceledSpellRemovesItsZoneMoveButKeepsLaterRealCast() throws Exception {
        ForcedPassPriorityTest.Scenario s=scenario();
        Card cannotPay=new mage.cards.s.SerraAngel(s.ai.getId(),info("Serra Angel"));s.put(cannotPay,s.ai,Zone.HAND);
        Card free=new Memnite(s.ai.getId(),info("Memnite"));s.put(free,s.ai,Zone.HAND);
        LabRecorder rec=LabRecorder.start(s.game,store());LabRecorder.mulliganFinished(s.game);
        assertFalse(s.ai.cast(cannotPay.getSpellAbility(),s.game,false,null));
        assertTrue(s.ai.cast(free.getSpellAbility(),s.game,false,null));
        LabGame data=end(s,rec);List<LabGame.Event> raw=store().events(data.id);LabTrace t=LabTrace.select(data,raw);
        assertTrue(data.exclusions.toString(),data.eligible());assertTrue(t.notes.toString(),t.completeEvents);
        assertTrue(raw.stream().anyMatch(e->"STATE_RESTORED".equals(e.kind)&&e.restoredAfterEvent!=null));
        assertFalse(t.events.stream().anyMatch(e->"Serra Angel".equals(e.targetName)));
        assertEquals(1,t.events.stream().filter(e->"SPELL_CAST".equals(e.kind)&&"Memnite".equals(e.targetName)).count());
    }
    @Test public void OrdinaryTriggerWithNoLegalTargetPreservesEarlierEvents() throws Exception {
        ForcedPassPriorityTest.Scenario s=scenario();LabRecorder rec=LabRecorder.start(s.game,store());LabRecorder.mulliganFinished(s.game);event(s,"Memnite");
        Card ashes=new mage.cards.c.CrumblingAshes(s.ai.getId(),info("Crumbling Ashes"));s.put(ashes,s.ai,Zone.BATTLEFIELD);
        mage.abilities.TriggeredAbility trigger=(mage.abilities.TriggeredAbility)ashes.getAbilities().stream()
                .filter(a->a instanceof mage.abilities.TriggeredAbility).findFirst().get();
        assertFalse(s.ai.triggerAbility(trigger,s.game));
        LabGame data=end(s,rec);LabTrace t=LabTrace.read(data,store());assertTrue(data.eligible());assertTrue(t.completeEvents);assertEquals(0,t.removedEvents);
        assertEquals(1,t.events.stream().filter(e->"DREW_CARD".equals(e.kind)).count());
    }
    @Test public void BookmarkUndoExcludesCanceledSamplesAndResamplesSameTurn() throws Exception {
        ForcedPassPriorityTest.Scenario s=scenario();LabRecorder rec=LabRecorder.start(s.game,store());LabRecorder.mulliganFinished(s.game);
        int bookmark=s.game.bookmarkState();rec.observe(s.game);event(s,"Memnite");
        assertNotNull(s.game.restoreState(bookmark,"undo"));rec.observe(s.game);
        LabGame data=end(s,rec);LabTrace t=LabTrace.read(data,store());assertFalse(data.eligible());assertTrue(t.notes.toString(),t.completeEvents);
        assertEquals(1,t.removedSamples);assertEquals(1,t.samples.stream().filter(x->"PRECOMBAT_MAIN".equals(x.phase)).count());
        assertEquals(0,t.events.stream().filter(e->"DREW_CARD".equals(e.kind)).count());
    }
    @Test public void SuccessfulTurnRollbackIsRecordedOnEngineThreadAndKeepsFinalBranch() throws Exception {
        ForcedPassPriorityTest.Scenario s=scenario();s.game.getOptions().rollbackTurnsAllowed=true;
        LabRecorder rec=LabRecorder.start(s.game,store());LabRecorder.mulliganFinished(s.game);
        s.game.saveRollBackGameState();event(s,"Memnite");rec.observe(s.game);
        s.game.getState().setTurnNum(2);s.game.saveRollBackGameState();event(s,"Memnite");rec.observe(s.game);
        s.game.rollbackTurns(1);
        assertTrue(s.game.executingRollback());
        // Apply where Game.playTurn would consume the pending request, on this fixture's game thread.
        java.lang.reflect.Method apply=GameImpl.class.getDeclaredMethod("rollbackTurnsExecution",int.class);apply.setAccessible(true);apply.invoke(s.game,1);
        assertEquals(1,s.game.getTurnNum());assertFalse(s.game.executingRollback());event(s,"Memnite");rec.observe(s.game);
        LabGame data=end(s,rec);LabTrace t=LabTrace.read(data,store());assertFalse(data.eligible());assertTrue(t.notes.toString(),t.completeEvents);
        assertEquals(2,t.removedEvents);assertEquals(2,t.removedSamples);
        assertEquals(1,t.events.stream().filter(e->"DREW_CARD".equals(e.kind)).count());
        assertEquals(1,store().events(data.id).stream().filter(e->"TURN_ROLLBACK".equals(e.restoreKind)).count());
    }
    @Test public void StateCopiesCannotRegisterBookmarksOrRestoresAgainstOriginal() throws Exception {
        ForcedPassPriorityTest.Scenario s=scenario();LabRecorder rec=LabRecorder.start(s.game,store());Game copy=s.game.copy();
        int b=s.game.bookmarkState();LabRecorder.Checkpoint point=LabRecorder.bookmarkCheckpoint(s.game,b);
        LabRecorder.bookmark(copy,b);LabRecorder.discardBookmarks(copy,0);LabRecorder.stateRestored(copy,point,"copy");
        assertSame(point,LabRecorder.bookmarkCheckpoint(s.game,b));
        assertTrue(store().events(s.game.getId().toString()).isEmpty());
        LabGame data=end(s,rec);assertFalse(data.telemetry.stateRestored);
        // Lab6 now records the three real opponent exits produced by end(), after the copy-only assertions.
        List<LabGame.Event> exits=store().events(data.id);assertEquals(3,exits.size());
        assertTrue(exits.stream().allMatch(e->"PLAYER_EXIT".equals(e.kind)&&"OPPONENT_WIN".equals(e.exitReason)
                &&!s.ai.getId().toString().equals(e.playerId)));
    }
    @Test public void MissingCheckpointStillExcludesTheGameRatherThanTrustingAReset() throws Exception {
        ForcedPassPriorityTest.Scenario s=scenario();LabRecorder rec=LabRecorder.start(s.game,store());LabRecorder.stateRestored(s.game);
        LabGame data=end(s,rec);assertFalse(data.eligible());assertFalse(data.telemetry.complete);assertFalse(LabTrace.read(data,store()).completeEvents);
    }
    @Test public void TurnCounterChangeWithoutEngineRollbackDoesNotInventRollback() throws Exception {
        ForcedPassPriorityTest.Scenario s=scenario();LabRecorder rec=LabRecorder.start(s.game,store());
        s.game.getState().setTurnNum(2);rec.observe(s.game);s.game.getState().setTurnNum(1);rec.observe(s.game);
        LabGame data=end(s,rec);assertFalse(data.exclusions.contains("Partita con rollback"));
    }

    @Test public void ManaActionRestoreKeepsLiveStackAndDiscardsOnlyCanceledLabEvents() throws Exception {
        verifyStackAndTraceAfterRestore(true);
    }

    @Test public void OrdinaryRestoreCopiesStackAndDiscardsOnlyCanceledLabEvents() throws Exception {
        verifyStackAndTraceAfterRestore(false);
    }

    private void verifyStackAndTraceAfterRestore(boolean keepStack) throws Exception {
        ForcedPassPriorityTest.Scenario s=scenario();
        Card free=new Memnite(s.ai.getId(),info("Memnite"));s.put(free,s.ai,Zone.HAND);
        LabRecorder rec=LabRecorder.start(s.game,store());LabRecorder.mulliganFinished(s.game);
        assertTrue(s.ai.cast(free.getSpellAbility(),s.game,false,null));
        mage.game.stack.SpellStack liveStack=s.game.getStack();
        mage.game.stack.StackObject liveSpell=liveStack.peek();
        assertNotNull(liveSpell);
        int bookmark=s.game.bookmarkState();
        s.ai.setLife(17,s.game,null);event(s,"Canceled draw");
        // A failed mana action changes no stack objects. The active spell must
        // retain its identity so activation can continue writing to that object.
        if(keepStack){
            assertNotNull(s.ai.restoreStateKeepingStack(bookmark,"mana action compatibility",s.game));
            assertSame(liveStack,s.game.getStack());assertSame(liveSpell,s.game.getStack().peek());
        }else{
            assertNotNull(s.ai.restoreState(bookmark,"ordinary action compatibility",s.game));
            assertNotSame(liveStack,s.game.getStack());assertNotSame(liveSpell,s.game.getStack().peek());
        }
        assertEquals(30,s.ai.getLife());assertEquals(1,s.game.getStack().size());
        assertEquals(liveSpell.getId(),s.game.getStack().peek().getId());
        event(s,"Later real draw");
        LabGame data=end(s,rec);List<LabGame.Event> raw=store().events(data.id);LabTrace trace=LabTrace.select(data,raw);
        assertTrue(data.exclusions.toString(),data.eligible());assertTrue(trace.notes.toString(),trace.completeEvents);
        assertEquals(1,raw.stream().filter(e->"STATE_RESTORED".equals(e.kind)&&e.restoredAfterEvent!=null).count());
        assertEquals(1,trace.events.stream().filter(e->"SPELL_CAST".equals(e.kind)).count());
        assertEquals(1,trace.events.stream().filter(e->"DREW_CARD".equals(e.kind)).count());
        assertFalse(trace.events.stream().anyMatch(e->"Canceled draw".equals(e.targetName)));
    }
}

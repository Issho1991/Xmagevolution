package mage.player.ai;

import mage.abilities.effects.common.LoseGameSourceControllerEffect;
import mage.cards.CardSetInfo;
import mage.cards.basiclands.Mountain;
import mage.cards.p.PlatinumAngel;
import mage.constants.*;
import mage.counters.CounterType;
import mage.game.*;
import mage.game.events.DamagedPlayerEvent;
import mage.lab.*;
import mage.players.Player;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.util.*;
import java.util.function.Consumer;
import static org.junit.Assert.*;

public class LabExitDiagnosticsTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder();
    private LabStore store() { return new LabStore(folder.getRoot().toPath()); }
    private CardSetInfo info(String name) { return new CardSetInfo(name,"TST","1",Rarity.COMMON); }
    private ForcedPassPriorityTest.Scenario scenario() throws Exception {
        ForcedPassPriorityTest.Scenario s = new ForcedPassPriorityTest.Scenario(4,0,0);
        s.game.setStartingPlayerId(s.ai.getId());
        for(ComputerPlayer7 p:s.players) p.getMatchPlayer().getDeck().getCards().add(new Mountain(p.getId(),info("Mountain")));
        java.lang.reflect.Field order=GameImpl.class.getDeclaredField("playerList"); order.setAccessible(true); order.set(s.game,s.game.getPlayerList());
        return s;
    }
    private void stateActions(ForcedPassPriorityTest.Scenario s) throws Exception {
        java.lang.reflect.Method m=GameImpl.class.getDeclaredMethod("checkStateBasedActions"); m.setAccessible(true); m.invoke(s.game);
    }
    private LabGame.Event finish(ForcedPassPriorityTest.Scenario s,LabRecorder r,String reason) throws Exception {
        // Mirrors the engine's queued exit processing on non-game test threads.
        s.game.checkConcede(false);
        r.finish(s.game,false);
        List<LabGame.Event> exits=new ArrayList<>();
        for(LabGame.Event e:store().events(s.game.getId().toString())) if("PLAYER_EXIT".equals(e.kind)&&e.playerId.equals(s.ai.getId().toString())) exits.add(e);
        assertEquals(1,exits.size()); assertEquals(reason,exits.get(0).exitReason);
        return exits.get(0);
    }
    @Test public void poisonDefeatAtPositiveLifeHasActualCounters() throws Exception {
        ForcedPassPriorityTest.Scenario s=scenario(); LabRecorder r=LabRecorder.start(s.game,store());
        s.ai.addCounters(CounterType.POISON.createInstance(10),s.ai.getId(),null,s.game);
        stateActions(s); assertTrue(s.ai.hasLost()); LabGame.Event e=finish(s,r,"POISON");
        assertEquals(Integer.valueOf(30),e.life); assertEquals(Integer.valueOf(10),e.poison);
    }
    @Test public void zeroLifeAndEmptyLibraryHaveDifferentEngineReasons() throws Exception {
        for(boolean empty:new boolean[]{false,true}) {
            ForcedPassPriorityTest.Scenario s=scenario(); LabRecorder r=LabRecorder.start(s.game,store());
            if(empty) s.ai.drawCards(1,null,s.game); else s.ai.setLife(0,s.game,null);
            stateActions(s); assertTrue(s.ai.hasLost()); finish(s,r,empty?"EMPTY_LIBRARY":"LIFE_ZERO");
        }
    }
    @Test public void concessionsQuitAndTimeoutsAreDistinct() throws Exception {
        String[] reasons={"CONCEDE","MATCH_QUIT","TIMER_TIMEOUT","IDLE_TIMEOUT"};
        for(int i=0;i<reasons.length;i++) {
            ForcedPassPriorityTest.Scenario s=scenario(); LabRecorder r=LabRecorder.start(s.game,store());
            switch(i) {case 0:s.ai.concede(s.game);break;case 1:s.ai.quit(s.game);break;case 2:s.ai.timerTimeout(s.game);break;default:s.ai.idleTimeout(s.game);}
            finish(s,r,reasons[i]);
        }
    }
    @Test public void asynchronousConcessionStillHasReasonThroughCannotLoseEffect() throws Exception {
        ForcedPassPriorityTest.Scenario s=scenario();s.put(new PlatinumAngel(s.ai.getId(),info("Platinum Angel")),s.ai,Zone.BATTLEFIELD);
        s.game.applyEffects();LabRecorder r=LabRecorder.start(s.game,store());
        Thread t=new Thread(()->s.ai.concede(s.game),"CALL-test");t.start();t.join();
        assertFalse(s.ai.hasLost()); // request is queued; replacement still prevents the rule loss
        finish(s,r,"CONCEDE");assertTrue(s.ai.hasLeft());
    }
    @Test public void sessionRemovalReasonSurvivesAsyncQuitButDoesNotEmitEarlyExit() throws Exception {
        ForcedPassPriorityTest.Scenario s=scenario(); LabRecorder r=LabRecorder.start(s.game,store());
        LabRecorder.requestedExit(s.game,s.ai,"SESSION_EXPIRED");
        assertTrue(s.ai.isInGame()); assertTrue(store().events(s.game.getId().toString()).isEmpty());
        Thread t=new Thread(()->s.ai.quit(s.game)); t.start();t.join();
        finish(s,r,"SESSION_EXPIRED");
    }
    @Test public void pendingSessionRemovalSurvivesInternalGameStateRestore() throws Exception {
        ForcedPassPriorityTest.Scenario s=scenario(); LabRecorder r=LabRecorder.start(s.game,store());
        int b=s.game.bookmarkState();
        LabRecorder.requestedExit(s.game,s.ai,"SESSION_EXPIRED");
        assertNotNull(s.game.restoreState(b,"cancelled spell"));
        s.game.getPlayer(s.ai.getId()).quit(s.game);finish(s,r,"SESSION_EXPIRED");
    }
    @Test public void cardEffectAndOpponentWinAreRecorded() throws Exception {
        ForcedPassPriorityTest.Scenario s=scenario(); LabRecorder r=LabRecorder.start(s.game,store());
        mage.cards.m.Memnite source=new mage.cards.m.Memnite(s.ai.getId(),info("Memnite"));s.put(source,s.ai,Zone.BATTLEFIELD);
        new LoseGameSourceControllerEffect().apply(s.game,source.getSpellAbility());
        assertEquals("Memnite",finish(s,r,"CARD_EFFECT").exitSource);
        s=scenario();r=LabRecorder.start(s.game,store());s.players.get(1).won(s.game);finish(s,r,"OPPONENT_WIN");
    }
    @Test public void preventedPoisonLossAndSimulationDoNotCreateExit() throws Exception {
        ForcedPassPriorityTest.Scenario s=scenario(); s.put(new PlatinumAngel(s.ai.getId(),info("Platinum Angel")),s.ai,Zone.BATTLEFIELD);
        s.game.applyEffects();LabRecorder r=LabRecorder.start(s.game,store());
        s.ai.addCounters(CounterType.POISON.createInstance(10),s.ai.getId(),null,s.game);stateActions(s); assertFalse(s.ai.hasLost());
        Game clone=s.game.copy();LabRecorder.requestedExit(clone,clone.getPlayer(s.ai.getId()),"DISCONNECTED");LabRecorder.beforeLeave(clone,clone.getPlayer(s.ai.getId()));
        r.finish(s.game,false);assertFalse(store().events(s.game.getId().toString()).stream().anyMatch(e->"PLAYER_EXIT".equals(e.kind)));
    }
    @Test public void rollbackRemovesExitReasonAndCountersFromDiscardedBranch() throws Exception {
        ForcedPassPriorityTest.Scenario s=scenario(); LabRecorder r=LabRecorder.start(s.game,store());LabRecorder.mulliganFinished(s.game);
        int b=s.game.bookmarkState();
        s.ai.addCounters(CounterType.POISON.createInstance(10),s.ai.getId(),null,s.game);
        try(LabRecorder.ExitContext c=LabRecorder.exitContext(s.game,s.ai,"POISON",null)){LabRecorder.beforeLeave(s.game,s.ai);}
        assertNotNull(s.game.restoreState(b,"undo"));
        Player restored=s.game.getPlayer(s.ai.getId());assertEquals(0,restored.getCountersCount(CounterType.POISON));
        restored.concede(s.game); s.game.checkConcede(false); s.game.getPlayer(s.players.get(1).getId()).won(s.game); s.game.checkConcede(false);
        java.lang.reflect.Field winner=GameImpl.class.getDeclaredField("winnerId");winner.setAccessible(true);winner.set(s.game,s.players.get(1).getId());
        r.finish(s.game,true);
        LabGame data=store().game(s.game.getId().toString());assertEquals(data.exclusions.toString(),"COMPLETED",data.status); LabTrace trace=LabTrace.read(data,store());
        assertFalse(data.eligible());
        assertFalse(trace.events.stream().anyMatch(e->"POISON".equals(e.exitReason)));
        assertTrue(trace.notes.toString(), trace.events.stream().anyMatch(e->"CONCEDE".equals(e.exitReason)&&Integer.valueOf(0).equals(e.poison)));
    }
    @Test public void combatDamageUsesSpecificDamageEventFlag() throws Exception {
        ForcedPassPriorityTest.Scenario s=scenario();LabRecorder r=LabRecorder.start(s.game,store());
        for(boolean combat:new boolean[]{true,false}) s.game.fireEvent(new DamagedPlayerEvent(s.ai.getId(),null,s.players.get(1).getId(),2,combat));
        r.finish(s.game,false);List<Boolean> flags=new ArrayList<>();
        for(LabGame.Event e:store().events(s.game.getId().toString()))if("DAMAGED_PLAYER".equals(e.kind))flags.add(e.combat);
        assertEquals(Arrays.asList(true,false),flags);
    }
    @Test public void measuresDecisionAndWaitWithoutIncludingCloneOrOtherThreads() throws Exception {
        ForcedPassPriorityTest.Scenario s=scenario();LabRecorder r=LabRecorder.start(s.game,store());
        assertEquals(0,LabRecorder.beginResponseWait(s.game));
        long start=LabRecorder.beginDecision(s.game,s.ai);assertNotEquals(0,start);
        assertEquals(0,LabRecorder.beginResponseWait(s.game.copy()));
        java.util.concurrent.atomic.AtomicLong other=new java.util.concurrent.atomic.AtomicLong(-1);
        Thread t=new Thread(()->other.set(LabRecorder.beginResponseWait(s.game)));t.start();t.join();assertEquals(0,other.get());
        long wait=LabRecorder.beginResponseWait(s.game);Thread.sleep(5);LabRecorder.endResponseWait(s.game,wait);
        LabRecorder.endDecision(s.game,s.ai,"PRIORITY",start);assertEquals(0,LabRecorder.beginResponseWait(s.game));r.finish(s.game,false);
        LabGame.Timing timing=store().game(s.game.getId().toString()).telemetry.ai.values().iterator().next();
        assertEquals(1,timing.calls);assertEquals(1,timing.measuredCalls);assertTrue(timing.totalResponseWaitNanos>0);
        assertTrue(timing.totalResponseWaitNanos<=timing.totalNanos);assertTrue(timing.cpuMeasuredCalls==0||timing.cpuMeasuredCalls==1);
    }
}

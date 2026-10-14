package mage.player.ai;

import mage.game.GameImpl;
import mage.lab.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.util.*;
import static org.junit.Assert.*;

public class LabDrawDiagnosticsTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder();
    @Test public void ruleDrawHasAssignmentAndExitReasonForEveryPlayer() throws Exception {
        ForcedPassPriorityTest.Scenario s = new ForcedPassPriorityTest.Scenario(4, 1, 0);
        LabStore store = new LabStore(folder.getRoot().toPath());
        LabRecorder r = LabRecorder.start(s.game, store); LabRecorder.mulliganFinished(s.game);
        s.game.setDraw(s.ai.getId()); s.game.checkConcede(false); r.finish(s.game, true);
        List<LabGame.Event> events = store.events(s.game.getId().toString());
        assertEquals(4, events.stream().filter(e -> "DRAW_ASSIGNED".equals(e.kind)
                && "RULE_EFFECT_DRAW".equals(e.drawReason)).count());
        assertEquals(4, events.stream().filter(e -> "PLAYER_EXIT".equals(e.kind)
                && "RULE_EFFECT_DRAW".equals(e.exitReason)).count());
    }
    @Test public void choicesAndProposalSurviveSerializationButSimulationsAreIgnored() throws Exception {
        ForcedPassPriorityTest.Scenario s = new ForcedPassPriorityTest.Scenario(4, 1, 0);
        LabStore store = new LabStore(folder.getRoot().toPath());
        LabRecorder r = LabRecorder.start(s.game, store);
        LabRecorder.drawEvent(s.game, "DRAW_PROPOSED", s.ai.getId(), null,
                "REPEATED_STACK_SEQUENCE", null, null);
        LabRecorder.drawEvent(s.game, "DRAW_RESPONSE", s.ai.getId(), null,
                "REPEATED_STACK_SEQUENCE", "HUMAN_EXPLICIT", false);
        LabRecorder.drawEvent(s.game.createSimulationForAI(), "DRAW_RESPONSE", s.ai.getId(), null,
                "REPEATED_STACK_SEQUENCE", "PLAYER_CHOICE", true);
        r.finish(s.game, false);
        List<LabGame.Event> events = store.events(s.game.getId().toString());
        assertEquals(1, events.stream().filter(e -> "DRAW_RESPONSE".equals(e.kind)).count());
        LabGame.Event answer = events.stream().filter(e -> "DRAW_RESPONSE".equals(e.kind)).findFirst().get();
        assertEquals(Boolean.FALSE, answer.drawAccepted); assertEquals("HUMAN_EXPLICIT", answer.drawResponseOrigin);
        assertEquals(Integer.valueOf(0), answer.stackSize);
    }
}

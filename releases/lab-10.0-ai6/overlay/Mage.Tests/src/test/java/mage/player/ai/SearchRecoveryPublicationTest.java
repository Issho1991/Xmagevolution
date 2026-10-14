package mage.player.ai;

import mage.abilities.Ability;
import mage.abilities.PlayLandAbility;
import mage.cards.Card;
import mage.constants.Zone;
import mage.game.Game;
import mage.player.ai.score.GameStateEvaluator2;
import org.junit.Test;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

public class SearchRecoveryPublicationTest {
    @Test public void timeoutPrefersTheCompletedCandidateToAShallowFallback() throws Exception {
        DecisionRecoveryAITest fixture = new DecisionRecoveryAITest();
        ForcedPassPriorityTest.Scenario s = fixture.scenario();
        Card card = fixture.land(s);
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1), finished = new CountDownLatch(1);
        s.put(new DecisionRecoveryAITest.SlowSpell(s.ai.getId(), entered, release, finished, true), s.ai, Zone.HAND);
        s.ai.setMaxThinkTimeSecs(1);
        Game sim = s.ai.createSimulation(s.game);
        s.ai.currentScore = GameStateEvaluator2.evaluate(s.ai.getId(), s.game).getTotalScore();
        s.ai.root = new SimulationNode2(null, sim, s.ai.maxDepth, s.ai.getId());
        try {
            s.ai.addActionsTimed();
            assertEquals(0, entered.getCount());
            assertNull(s.ai.root);
            assertNotNull(s.ai.interruptedDecision);
            assertTrue(s.ai.interruptedDecision.completed);
            Ability selected = s.ai.interruptedDecision.abilities().getFirst();
            assertTrue(selected instanceof PlayLandAbility);
            assertEquals(card.getId(), selected.getSourceId());
            assertTrue(((PlayLandAbility) selected).canActivate(s.ai.getId(), s.game).canActivate());
        } finally {
            release.countDown();
            assertTrue(finished.await(3, TimeUnit.SECONDS));
        }
    }

    @Test public void publishedChoiceIsDetachedAndCannotChangeAfterClose() {
        ForcedPassPriorityTest.Scenario s = new ForcedPassPriorityTest.Scenario(4, 0, 0);
        UUID original = UUID.randomUUID();
        PlayLandAbility action = new PlayLandAbility("Swamp"); action.setSourceId(original);
        SimulationNode2 node = new SimulationNode2(null, s.game.copy(), action, 1, s.ai.getId());
        node.setScore(10);
        SearchProgress progress = new SearchProgress();
        progress.completed(node);
        SearchProgress.Decision decision = progress.close();
        action.setSourceId(UUID.randomUUID());
        progress.completed(node);
        assertEquals(original, decision.abilities().getFirst().getSourceId());
        decision.abilities().getFirst().setSourceId(UUID.randomUUID());
        assertEquals(original, progress.close().abilities().getFirst().getSourceId());
    }

    @Test public void nodeCountersAreIsolatedAcrossConcurrentSearches() throws Exception {
        ForcedPassPriorityTest.Scenario s = new ForcedPassPriorityTest.Scenario(4, 0, 0);
        Game copy = s.game.copy();
        SimulationNode2.resetCount();
        new SimulationNode2(null, s.game, 1, s.ai.getId());
        AtomicInteger workerCount = new AtomicInteger();
        Thread worker = new Thread(() -> {
            SimulationNode2.resetCount();
            for (int i = 0; i < 4; i++) new SimulationNode2(null, copy, 1, s.ai.getId());
            workerCount.set(SimulationNode2.getCount());
        });
        worker.start(); worker.join(3000);
        assertFalse(worker.isAlive());
        assertEquals(4, workerCount.get());
        assertEquals(1, SimulationNode2.getCount());
    }
}

package mage.player.ai;

import mage.abilities.Ability;
import mage.abilities.SpellAbility;
import mage.abilities.common.PassAbility;
import mage.abilities.common.SimpleActivatedAbility;
import mage.abilities.costs.mana.GenericManaCost;
import mage.abilities.effects.OneShotEffect;
import mage.cards.Card;
import mage.cards.CardSetInfo;
import mage.cards.decks.Deck;
import mage.cards.l.LavaDart;
import mage.cards.l.LightningBolt;
import mage.cards.m.Memnite;
import mage.cards.m.MoggFanatic;
import mage.cards.basiclands.Mountain;
import mage.constants.*;
import mage.game.FreeForAll;
import mage.game.FreeForAllMatch;
import mage.game.Game;
import mage.game.GameOptions;
import mage.game.match.MatchOptions;
import mage.game.mulligan.MulliganType;
import mage.game.permanent.PermanentCard;
import mage.game.stack.StackAbility;
import mage.game.turn.PreCombatMainPhase;
import mage.game.turn.PreCombatMainStep;
import mage.players.Player;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

/** Uses real game copies, playable discovery and search, without the card database. */
public class ForcedPassPriorityTest {

    static class Scenario {
        final FreeForAll game;
        final List<ComputerPlayer7> players = new ArrayList<>();
        final AtomicInteger simulatedResolutions = new AtomicInteger();
        final ComputerPlayer7 ai;

        Scenario(int playerCount, int permanentsPerPlayer, int stackSize) {
            this(playerCount, permanentsPerPlayer, stackSize, RangeOfInfluence.ALL);
        }

        Scenario(int playerCount, int permanentsPerPlayer, int stackSize, RangeOfInfluence range) {
            game = new FreeForAll(MultiplayerAttackOption.MULTIPLE,
                    range, MulliganType.GAME_DEFAULT.getMulligan(0), 30, 7);
            game.setNumPlayers(playerCount);
            game.setGameOptions(new GameOptions());
            FreeForAllMatch match = new FreeForAllMatch(new MatchOptions("forced-pass regression", "FreeForAll", true));
            for (int i = 0; i < playerCount; i++) {
                ComputerPlayer7 player = new ComputerPlayer7("Player" + i, range, 6);
                players.add(player);
                Deck deck = new Deck();
                game.addPlayer(player, deck);
                match.addPlayer(player, deck);
                player.init(game);
            }
            ai = players.get(0);
            for (ComputerPlayer7 player : players) {
                player.updateRange(game);
                for (int i = 0; i < permanentsPerPlayer; i++) {
                    put(new Memnite(player.getId(), info("Memnite")), player, Zone.BATTLEFIELD);
                }
            }
            game.getState().setActivePlayerId(ai.getId());
            game.getState().setPriorityPlayerId(ai.getId());
            game.getPlayerList().setCurrent(ai.getId());
            PreCombatMainPhase phase = new PreCombatMainPhase();
            phase.setStep(new PreCombatMainStep());
            game.getTurn().setPhase(phase);
            UUID owner = players.get(1).getId();
            for (int i = 0; i < stackSize; i++) {
                Ability ability = new SimpleActivatedAbility(Zone.BATTLEFIELD,
                        new ResolutionProbe(simulatedResolutions), new GenericManaCost(0));
                ability.setControllerId(owner);
                ability.setSourceId(owner);
                game.getStack().push(new StackAbility(ability, owner));
            }
        }

        void put(Card card, Player player, Zone zone) {
            game.loadCards(Collections.singleton(card), player.getId());
            game.setZone(card.getId(), zone);
            if (zone == Zone.BATTLEFIELD) {
                game.getBattlefield().addPermanent(new PermanentCard(card, player.getId(), game));
            } else if (zone == Zone.HAND) {
                player.getHand().add(card);
            } else if (zone == Zone.GRAVEYARD) {
                player.getGraveyard().add(card);
            } else {
                throw new IllegalArgumentException("Unsupported test zone " + zone);
            }
        }

        SimulationNode2 search(boolean decisionRoot) {
            Game sim = ai.createSimulation(game);
            SimulationNode2.resetCount();
            ai.currentScore = mage.player.ai.score.GameStateEvaluator2.evaluate(ai.getId(), sim).getTotalScore();
            ai.root = new SimulationNode2(null, sim, ai.maxDepth, ai.getId());
            SimulationNode2 node = decisionRoot ? ai.root : new SimulationNode2(ai.root, sim, ai.maxDepth, ai.getId());
            ai.addActions(node, ai.maxDepth, Integer.MIN_VALUE, Integer.MAX_VALUE);
            assertFalse("Search must choose an action", node.children.isEmpty());
            return node.children.get(0);
        }
    }

    private static CardSetInfo info(String name) {
        return new CardSetInfo(name, "TST", "1", Rarity.COMMON);
    }

    private static class ResolutionProbe extends OneShotEffect {
        private final AtomicInteger counter;

        ResolutionProbe(AtomicInteger counter) {
            super(Outcome.Neutral);
            this.counter = counter;
            this.staticText = "Regression test stack item";
        }

        ResolutionProbe(ResolutionProbe effect) {
            super(effect);
            this.counter = effect.counter; // intentional shared test instrumentation
        }

        @Override
        public boolean apply(Game game, Ability source) {
            if (game.isSimulation()) {
                counter.incrementAndGet();
            }
            return true;
        }

        @Override
        public ResolutionProbe copy() {
            return new ResolutionProbe(this);
        }
    }

    @Test
    public void forcedPassPreservesRealGameWithoutResolvingStack() {
        Scenario scenario = new Scenario(4, 10, 12);
        String originalState = scenario.game.getState().getValue(true);
        SimulationNode2 selected = scenario.search(true);
        assertTrue(selected.abilities.get(0) instanceof PassAbility);
        assertEquals("No hypothetical stack resolution is needed for a forced root pass",
                0, scenario.simulatedResolutions.get());
        assertEquals(12, scenario.game.getStack().size());
        assertEquals(originalState, scenario.game.getState().getValue(true));
        assertFalse(scenario.ai.isPassed());
        assertTrue("Never reuse a hypothetical future action after this pass", selected.children.isEmpty());
        assertEquals("Search depth is unchanged", 6, scenario.ai.maxDepth);
        assertEquals("Search node budget is unchanged", 5000, scenario.ai.maxNodes);
        assertEquals("Think time is unchanged", 18, scenario.ai.maxThinkTimeSecs);
    }

    @Test
    public void forcedPassBelowRootStillResolvesStackForEarlierDecisions() {
        Scenario scenario = new Scenario(4, 2, 3);
        SimulationNode2 selected = scenario.search(false);
        assertTrue(selected.abilities.get(0) instanceof PassAbility);
        assertTrue("The result of a nested pass is necessary to compare earlier choices",
                scenario.simulatedResolutions.get() > 0);
        assertEquals(3, scenario.game.getStack().size());
    }

    @Test
    public void freeSpellIsStillConsidered() {
        Scenario scenario = new Scenario(2, 0, 0);
        Memnite card = new Memnite(scenario.ai.getId(), info("Memnite"));
        scenario.put(card, scenario.ai, Zone.HAND);
        SimulationNode2 selected = scenario.search(true);
        assertTrue(selected.abilities.get(0) instanceof SpellAbility);
        assertEquals(card.getId(), selected.abilities.get(0).getSourceId());
        assertEquals(1, scenario.ai.getHand().size());
    }

    @Test
    public void instantResponseOnOpponentsTurnIsStillConsidered() {
        Scenario scenario = new Scenario(2, 0, 1);
        Player opponent = scenario.players.get(1);
        scenario.game.getState().setActivePlayerId(opponent.getId());
        opponent.setLife(3, scenario.game, null);
        LightningBolt bolt = new LightningBolt(scenario.ai.getId(), info("Lightning Bolt"));
        scenario.put(bolt, scenario.ai, Zone.HAND);
        scenario.put(new Mountain(scenario.ai.getId(), info("Mountain")), scenario.ai, Zone.BATTLEFIELD);
        SimulationNode2 selected = scenario.search(true);
        assertEquals(bolt.getId(), selected.abilities.get(0).getSourceId());
        assertEquals(opponent.getId(), selected.abilities.get(0).getFirstTarget());
        assertEquals(3, opponent.getLife());
        assertEquals(1, scenario.game.getStack().size());
    }

    @Test
    public void nonManaSacrificeAbilityIsStillConsidered() {
        Scenario scenario = new Scenario(2, 0, 1);
        Player opponent = scenario.players.get(1);
        opponent.setLife(1, scenario.game, null);
        MoggFanatic fanatic = new MoggFanatic(scenario.ai.getId(), info("Mogg Fanatic"));
        scenario.put(fanatic, scenario.ai, Zone.BATTLEFIELD);
        SimulationNode2 selected = scenario.search(true);
        assertEquals(fanatic.getId(), selected.abilities.get(0).getSourceId());
        assertEquals(opponent.getId(), selected.abilities.get(0).getFirstTarget());
    }

    @Test
    public void graveyardFlashbackWithNoManaIsStillConsidered() {
        Scenario scenario = new Scenario(2, 0, 1);
        Player opponent = scenario.players.get(1);
        opponent.setLife(1, scenario.game, null);
        LavaDart dart = new LavaDart(scenario.ai.getId(), info("Lava Dart"));
        scenario.put(dart, scenario.ai, Zone.GRAVEYARD);
        scenario.put(new Mountain(scenario.ai.getId(), info("Mountain")), scenario.ai, Zone.BATTLEFIELD);
        SimulationNode2 selected = scenario.search(true);
        assertEquals(dart.getId(), selected.abilities.get(0).getSourceId());
        // Flashback casts an inner spell; its damage target is not on the outer ability.
        assertEquals(mage.player.ai.score.GameStateEvaluator2.WIN_GAME_SCORE, selected.getScore());
        assertEquals(1, scenario.ai.getGraveyard().size());
    }

    /** Same scenario and assertions on original and patched classes; timing is informational. */
    @Test
    public void benchmarkCrowdedFourPlayerForcedPass() {
        int warmups = 5;
        int runs = 15;
        long[] elapsed = new long[runs];
        int resolutions = 0;
        for (int i = -warmups; i < runs; i++) {
            Scenario scenario = new Scenario(4, 15, 12);
            long start = System.nanoTime();
            SimulationNode2 selected = scenario.search(true);
            long duration = System.nanoTime() - start;
            assertTrue(selected.abilities.get(0) instanceof PassAbility);
            assertEquals(12, scenario.game.getStack().size());
            if (i >= 0) {
                elapsed[i] = duration;
                resolutions += scenario.simulatedResolutions.get();
            }
        }
        java.util.Arrays.sort(elapsed);
        System.out.printf(java.util.Locale.ROOT,
                "FORCED_PASS_BENCHMARK players=4 permanents=60 stack=12 runs=%d median_ms=%.3f simulated_resolutions=%d%n",
                runs, elapsed[runs / 2] / 1_000_000.0, resolutions);
    }
}

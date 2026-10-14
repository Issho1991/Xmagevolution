package mage.player.ai;

import mage.MageInt;
import mage.abilities.Ability;
import mage.abilities.keyword.*;
import mage.cards.CardImpl;
import mage.cards.CardSetInfo;
import mage.cards.j.JaceBeleren;
import mage.constants.CardType;
import mage.constants.Rarity;
import mage.constants.Zone;
import mage.counters.CounterType;
import mage.game.permanent.Permanent;
import mage.game.permanent.PermanentCard;
import mage.player.ai.util.CombatInfo;
import mage.player.ai.util.CombatUtil;
import mage.player.ai.util.SurviveInfo;
import mage.player.ai.score.GameStateEvaluator2;
import mage.players.Player;
import org.junit.Test;

import java.util.*;

import static org.junit.Assert.*;

/** Tactical regression fixtures: actual rules engine and CPU, no card database or hidden hands. */
public class CombatDecisionAITest {
    private static class Creature extends CardImpl {
        Creature(UUID owner, String name, int power, int toughness, Ability... abilities) {
            super(owner, new CardSetInfo(name, "TST", "1", Rarity.COMMON), new CardType[]{CardType.CREATURE}, "{2}");
            this.power = new MageInt(power);
            this.toughness = new MageInt(toughness);
            addAbility(HasteAbility.getInstance());
            for (Ability ability : abilities) addAbility(ability);
        }
        private Creature(Creature card) { super(card); }
        @Override public Creature copy() { return new Creature(this); }
    }

    private static class Scenario extends ForcedPassPriorityTest.Scenario {
        Scenario(int count) {
            super(count, 0, 0);
            game.initGameDefaultWatchers();
            for (Player player : players) player.setLife(30, game, null);
            game.getCombat().setAttacker(ai.getId());
            game.getCombat().setDefenders(game);
        }
        Permanent creature(Player owner, String name, int power, int toughness, Ability... abilities) {
            Creature card = new Creature(owner.getId(), name, power, toughness, abilities);
            put(card, owner, Zone.BATTLEFIELD);
            Permanent permanent = game.getPermanent(card.getId());
            for (Ability ability : permanent.getAbilities()) game.getState().addAbility(ability, null, permanent);
            game.applyEffects();
            return permanent;
        }
        void attack(Permanent attacker, Player defender) {
            assertTrue(game.getCombat().addAttackerToCombat(attacker.getId(), defender.getId(), game));
        }
        CombatInfo blocks(Permanent... attackers) {
            return CombatUtil.blockWithGoodTrade2(game, Arrays.asList(attackers),
                    new ArrayList<>(players.get(1).getAvailableBlockers(game)));
        }
    }

    @Test public void evasionUsesLegalBlocksInsteadOfPowerOnly() {
        Scenario s = new Scenario(2);
        Permanent attacker = s.creature(s.ai, "Unblockable", 2, 2, new CantBeBlockedSourceAbility());
        Permanent blocker = s.creature(s.players.get(1), "Large blocker", 8, 8);
        assertFalse(blocker.canBlock(attacker.getId(), s.game));
        s.ai.selectAttackers(s.game, s.ai.getId());
        assertTrue("A creature that cannot be blocked should attack", attacker.isAttacking());
    }

    @Test public void deathtouchDoesNotSurviveFirstStrike() {
        Scenario s = new Scenario(2);
        Permanent attacker = s.creature(s.ai, "Deathtouch", 1, 1, DeathtouchAbility.getInstance());
        s.creature(s.players.get(1), "First strike", 2, 2, FirstStrikeAbility.getInstance());
        s.ai.selectAttackers(s.game, s.ai.getId());
        assertFalse("Deathtouch must not justify a death before dealing damage", attacker.isAttacking());
    }

    @Test public void flyingStillAttacksPastGroundBlocker() {
        Scenario s = new Scenario(2);
        Permanent attacker = s.creature(s.ai, "Flying", 2, 2, FlyingAbility.getInstance());
        s.creature(s.players.get(1), "Ground", 8, 8);
        s.ai.selectAttackers(s.game, s.ai.getId());
        assertTrue(attacker.isAttacking());
    }

    @Test public void highPowerDoesNotHelpIfFirstStrikeKillsBeforeDamage() {
        Scenario s = new Scenario(2);
        Permanent attacker = s.creature(s.ai, "Fragile attacker", 6, 1);
        s.creature(s.players.get(1), "First strike", 2, 2, FirstStrikeAbility.getInstance());
        s.ai.selectAttackers(s.game, s.ai.getId());
        assertFalse(attacker.isAttacking());
    }

    @Test public void deathtouchThreatCanBeKeptForDefence() {
        Scenario s = new Scenario(2);
        Permanent attacker = s.creature(s.ai, "Deathtouch", 1, 1, DeathtouchAbility.getInstance());
        s.creature(s.players.get(1), "Large blocker", 8, 8);
        s.ai.selectAttackers(s.game, s.ai.getId());
        assertFalse("An opponent can decline this trade; keep the only deterrent to an 8/8 attack", attacker.isAttacking());
    }

    @Test public void flyingDoesNotAttackIntoReach() {
        Scenario s = new Scenario(2);
        Permanent attacker = s.creature(s.ai, "Flying", 2, 2, FlyingAbility.getInstance());
        s.creature(s.players.get(1), "Reach", 8, 8, ReachAbility.getInstance());
        s.ai.selectAttackers(s.game, s.ai.getId());
        assertFalse(attacker.isAttacking());
    }

    @Test public void menaceDoesNotWasteOnlyBlockerNeededElsewhere() {
        Scenario s = new Scenario(2);
        Permanent menace = s.creature(s.ai, "Menace", 3, 3, new MenaceAbility());
        Permanent normal = s.creature(s.ai, "Normal", 2, 2);
        Permanent blocker = s.creature(s.players.get(1), "Blocker", 4, 4);
        s.attack(menace, s.players.get(1)); s.attack(normal, s.players.get(1));
        assertEquals(2, menace.getMinBlockedBy());
        CombatInfo info = s.blocks(menace, normal);
        assertFalse("Do not assign an illegal single block", info.getCombat().containsKey(menace));
        assertEquals(Collections.singletonList(blocker), info.getCombat().get(normal));
    }

    @Test public void menaceStillGetsTwoLegalBlockers() {
        Scenario s = new Scenario(2);
        Permanent menace = s.creature(s.ai, "Menace", 2, 2, new MenaceAbility());
        s.creature(s.players.get(1), "Blocker A", 4, 4);
        s.creature(s.players.get(1), "Blocker B", 3, 3);
        s.attack(menace, s.players.get(1));
        assertEquals(2, s.blocks(menace).getCombat().get(menace).size());
    }

    @Test public void multiplayerDoesNotUseOurBlockerForAnotherPlayer() {
        Scenario s = new Scenario(4);
        Permanent otherAttack = s.creature(s.ai, "Attack elsewhere", 3, 3);
        Permanent ourAttack = s.creature(s.ai, "Attack here", 2, 2);
        Permanent blocker = s.creature(s.players.get(1), "Our blocker", 4, 4);
        s.attack(otherAttack, s.players.get(2)); s.attack(ourAttack, s.players.get(1));
        // Force the unrelated, larger attack first: permanent.canBlock alone allows it.
        assertTrue(blocker.canBlock(otherAttack.getId(), s.game));
        CombatInfo info = s.blocks(otherAttack, ourAttack);
        assertFalse(info.getCombat().containsKey(otherAttack));
        assertEquals(Collections.singletonList(blocker), info.getCombat().get(ourAttack));
        s.players.get(1).selectBlockers(null, s.game, s.players.get(1).getId());
        assertTrue(s.game.getCombat().findGroup(ourAttack.getId()).getBlockers().contains(blocker.getId()));
    }

    @Test public void multiplayerScoresThePlayerActuallyInDanger() {
        Scenario s = new Scenario(4);
        Player defender = s.players.get(1);
        defender.setLife(2, s.game, null);
        // Deliberately list an unrelated player as the only nominal possible defender.
        // Actual combat groups, not this candidate set, identify the attacked player.
        Permanent attacker = s.creature(s.ai, "Lethal", 3, 3);
        Permanent blocker = s.creature(defender, "Chump", 1, 1);
        s.attack(attacker, defender);
        s.game.getCombat().getDefenders().clear();
        s.game.getCombat().getDefenders().add(s.players.get(2).getId());
        assertEquals(Collections.singletonList(blocker), s.blocks(attacker).getCombat().get(attacker));
        assertEquals("Analysis must not deal real damage", 2, defender.getLife());
        assertNotNull(s.game.getPermanent(blocker.getId()));
    }

    @Test public void doubleStrikeLethalMustBeBlocked() {
        Scenario s = new Scenario(2);
        Player defender = s.players.get(1);
        defender.setLife(3, s.game, null);
        Permanent attacker = s.creature(s.ai, "Double strike", 2, 2, DoubleStrikeAbility.getInstance());
        Permanent blocker = s.creature(defender, "Valuable fragile blocker", 6, 1);
        s.attack(attacker, defender);
        assertEquals(Collections.singletonList(blocker), s.blocks(attacker).getCombat().get(attacker));
    }

    @Test public void multiplayerDoesNotInventLethalOnAnUnrelatedPlayer() {
        Scenario s = new Scenario(4);
        Player defender = s.players.get(1);
        s.players.get(2).setLife(1, s.game, null);
        Permanent attacker = s.creature(s.ai, "First strike", 3, 3, FirstStrikeAbility.getInstance());
        s.creature(defender, "Valuable fragile blocker", 6, 1);
        s.attack(attacker, defender);
        s.game.getCombat().getDefenders().clear();
        s.game.getCombat().getDefenders().add(s.players.get(2).getId());
        assertTrue("Another player's life must not force a pointless sacrifice", s.blocks(attacker).getCombat().isEmpty());
    }

    @Test public void attackOnPlaneswalkerDoesNotDealDamageToItsController() {
        Scenario s = new Scenario(2);
        Player defender = s.players.get(1);
        defender.setLife(1, s.game, null);
        JaceBeleren card = new JaceBeleren(defender.getId(), new CardSetInfo("Jace Beleren", "TST", "1", Rarity.MYTHIC));
        s.put(card, defender, Zone.BATTLEFIELD);
        Permanent walker = s.game.getPermanent(card.getId());
        walker.addCounters(CounterType.LOYALTY.createInstance(5), defender.getId(), null, s.game);
        Permanent attacker = s.creature(s.ai, "First strike", 2, 2, FirstStrikeAbility.getInstance());
        s.creature(defender, "Valuable fragile blocker", 6, 1);
        s.game.getCombat().getDefenders().clear();
        s.game.getCombat().setDefenders(s.game);
        assertTrue(s.game.getCombat().addAttackerToCombat(attacker.getId(), walker.getId(), s.game));
        assertTrue("Losing two loyalty is not lethal damage to the player", s.blocks(attacker).getCombat().isEmpty());
        SurviveInfo outcome = CombatUtil.willItSurviveSimple(s.game, s.ai.getId(), defender.getId(), attacker,
                defender.getAvailableBlockers(s.game).get(0));
        assertTrue("The unblocked branch must score loyalty damage, not a player loss",
                outcome.getDiffNonblockingScore() > GameStateEvaluator2.LOSE_GAME_SCORE / 2);
        assertEquals(1, defender.getLife());
        assertEquals(5, walker.getCounters(s.game).getCount(CounterType.LOYALTY));
    }

    @Test public void firstStrikeOnlyDealsOneHitWhenUnblocked() {
        Scenario s = new Scenario(2);
        Player defender = s.players.get(1);
        defender.setLife(3, s.game, null);
        Permanent attacker = s.creature(s.ai, "First strike", 2, 2, FirstStrikeAbility.getInstance());
        Permanent blocker = s.creature(defender, "Valuable fragile blocker", 6, 1);
        s.attack(attacker, defender);
        assertTrue("Do not invent lethal damage from first strike", s.blocks(attacker).getCombat().isEmpty());
    }

    @Test public void noCombatHasNoBlockAssignments() {
        Scenario s = new Scenario(4);
        s.game.getCombat().getDefenders().clear();
        assertTrue(s.blocks().getCombat().isEmpty());
    }

    /** Optional reproducible timing probe. Setup is outside the measured decision. */
    public static void main(String[] args) {
        List<Long> nanos = new ArrayList<>();
        for (int trial = 0; trial < 35; trial++) {
            Scenario s = new Scenario(4);
            for (Player player : s.players) {
                player.setLife(1000, s.game, null); // keep this outside the all-in lethal shortcut
                for (int i = 0; i < 20; i++) {
                    s.creature(player, "Creature " + i, 2 + i % 4, 2 + i % 4,
                            i % 3 == 0 ? FlyingAbility.getInstance() : HasteAbility.getInstance());
                }
            }
            long started = System.nanoTime();
            s.ai.selectAttackers(s.game, s.ai.getId());
            long elapsed = System.nanoTime() - started;
            if (trial >= 5) nanos.add(elapsed);
        }
        Collections.sort(nanos);
        System.out.printf(Locale.ROOT,
                "attack selection: players=4 creatures=80 measured=30 medianMs=%.3f p95Ms=%.3f maxMs=%.3f%n",
                nanos.get(15) / 1e6, nanos.get(28) / 1e6, nanos.get(29) / 1e6);
    }
}

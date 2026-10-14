package org.mage.test.game.ends;

import mage.constants.PhaseStep;
import mage.constants.Zone;
import org.junit.Test;
import org.mage.test.serverside.base.CardTestMultiPlayerBaseWithRangeAll;
import static org.junit.Assert.*;

/** Reduced four-player positions from APP-DRAW-20260927-001, not full replays. */
public class LabFiniteTriggerDrawTest extends CardTestMultiPlayerBaseWithRangeAll {
    @Test
    public void myrelSixteenSoldiersWithTwoWardensAndHeliodFinishes() {
        setLife(playerA, 30); setLife(playerB, 30); setLife(playerC, 30); setLife(playerD, 30);
        addCard(Zone.BATTLEFIELD, playerA, "Myrel, Shield of Argive");
        addCard(Zone.BATTLEFIELD, playerA, "Elite Vanguard", 15);
        addCard(Zone.BATTLEFIELD, playerB, "Soul Warden", 2);
        addCard(Zone.BATTLEFIELD, playerB, "Heliod, Sun-Crowned");
        attack(1, playerA, "Myrel, Shield of Argive", playerD);
        setStopAt(1, PhaseStep.END_COMBAT);
        execute();
        assertFalse("A finite set of Myrel triggers must not end the game", currentGame.hasEnded());
        assertPermanentCount(playerA, "Soldier Token", 16);
        assertLife(playerB, 62);
        assertFalse(currentGame.isADraw());
    }

    @Test
    public void twoDiscardsArchfiendNestWardenHeliodFinishes() {
        setLife(playerA, 30); setLife(playerB, 30); setLife(playerC, 30); setLife(playerD, 30);
        addCard(Zone.BATTLEFIELD, playerA, "Mountain");
        addCard(Zone.BATTLEFIELD, playerA, "Archfiend of Ifnir");
        addCard(Zone.BATTLEFIELD, playerA, "Nest of Scarabs");
        addCard(Zone.HAND, playerA, "Faithless Looting");
        addCard(Zone.HAND, playerA, "Mountain", 2);
        addCard(Zone.BATTLEFIELD, playerB, "Hill Giant", 7);
        addCard(Zone.BATTLEFIELD, playerC, "Hill Giant", 7);
        addCard(Zone.BATTLEFIELD, playerD, "Soul Warden");
        addCard(Zone.BATTLEFIELD, playerD, "Glorious Anthem");
        addCard(Zone.BATTLEFIELD, playerD, "Heliod, Sun-Crowned");
        castSpell(1, PhaseStep.PRECOMBAT_MAIN, playerA, "Faithless Looting");
        setChoice(playerA, "Mountain"); setChoice(playerA, "Mountain");
        setStopAt(1, PhaseStep.BEGIN_COMBAT);
        execute();
        assertFalse("A finite discard/counter/token chain must not end the game", currentGame.hasEnded());
        assertPermanentCount(playerA, "Insect Token", 30);
        assertLife(playerD, 60);
        assertFalse(currentGame.isADraw());
    }
}

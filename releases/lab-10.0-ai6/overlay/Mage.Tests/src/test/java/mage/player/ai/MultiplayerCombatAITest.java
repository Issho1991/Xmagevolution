package mage.player.ai;

import mage.MageInt;
import mage.abilities.Ability;
import mage.abilities.keyword.*;
import mage.cards.CardImpl;
import mage.cards.CardSetInfo;
import mage.constants.*;
import mage.counters.CounterType;
import mage.game.permanent.Permanent;
import mage.player.ai.score.GameStateEvaluator2;
import mage.player.ai.util.CombatInfo;
import mage.player.ai.util.CombatUtil;
import mage.players.Player;
import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class MultiplayerCombatAITest {
    static class Creature extends CardImpl {
        Creature(UUID owner,String name,int p,int t,Ability... abilities){
            super(owner,new CardSetInfo(name,"TST","1",Rarity.COMMON),new CardType[]{CardType.CREATURE},"{2}");
            power=new MageInt(p);toughness=new MageInt(t);addAbility(HasteAbility.getInstance());for(Ability a:abilities)addAbility(a);
        }
        Creature(Creature c){super(c);}
        public Creature copy(){return new Creature(this);}
    }
    static class Scenario extends ForcedPassPriorityTest.Scenario {
        Scenario(int n){super(n,0,0);game.initGameDefaultWatchers();for(Player p:players)p.setLife(30,game,null);game.getCombat().setAttacker(ai.getId());game.getCombat().setDefenders(game);}
        Permanent creature(Player owner,String name,int p,int t,Ability... abilities){
            Creature c=new Creature(owner.getId(),name,p,t,abilities);put(c,owner,Zone.BATTLEFIELD);Permanent perm=game.getPermanent(c.getId());
            for(Ability a:perm.getAbilities())game.getState().addAbility(a,null,perm);game.applyEffects();return perm;
        }
        Permanent attacker(String name,int p,int t,Ability... abilities){Permanent a=creature(ai,name,p,t,abilities);assertTrue(game.getCombat().addAttackerToCombat(a.getId(),players.get(1).getId(),game));return a;}
        Permanent blocker(String name,int p,int t,Ability... abilities){return creature(players.get(1),name,p,t,abilities);}
        CombatInfo blocks(Permanent... attackers){return CombatUtil.blockWithGoodTrade2(game,Arrays.asList(attackers),new ArrayList<>(players.get(1).getAvailableBlockers(game)));}
    }
    @Test public void twoThreeThreesKillFiveFive(){
        Scenario s=new Scenario(2);Permanent a=s.attacker("Five",5,5);s.blocker("A",3,3);s.blocker("B",3,3);
        assertEquals(2,s.blocks(a).getCombat().get(a).size());
    }
    @Test public void threeBlockersCanMakeTheOnlyFavourableTrade(){
        Scenario s=new Scenario(2);Permanent a=s.attacker("Six",6,6);s.blocker("A",2,3);s.blocker("B",2,3);s.blocker("C",2,3);
        assertEquals(3,s.blocks(a).getCombat().get(a).size());
    }
    @Test public void losingTwoCreaturesWithoutKillingAttackerIsRejected(){
        Scenario s=new Scenario(2);Permanent a=s.attacker("Eight",8,8);s.blocker("A",3,3);s.blocker("B",3,3);
        assertTrue(s.blocks(a).getCombat().isEmpty());
    }
    @Test public void blockersAreSplitToSurviveTwoLethalAttackers(){
        Scenario s=new Scenario(2);s.players.get(1).setLife(5,s.game,null);Permanent a=s.attacker("A",5,5),b=s.attacker("B",5,5);s.blocker("X",3,3);s.blocker("Y",3,3);
        CombatInfo plan=s.blocks(a,b);assertEquals(1,plan.getCombat().get(a).size());assertEquals(1,plan.getCombat().get(b).size());
        assertNotEquals(plan.getCombat().get(a).get(0).getId(),plan.getCombat().get(b).get(0).getId());
    }
    @Test public void firstStrikeInvalidatesAnApparentDoubleBlockTrade(){
        Scenario s=new Scenario(2);Permanent a=s.attacker("First",5,5,FirstStrikeAbility.getInstance());s.blocker("A",3,3);s.blocker("B",3,3);
        assertTrue(s.blocks(a).getCombat().isEmpty());
    }
    @Test public void doubleStrikeAndIndestructibleAreNotOrdinaryFiveFives(){
        for(Ability ability:new Ability[]{DoubleStrikeAbility.getInstance(),IndestructibleAbility.getInstance()}){
            Scenario s=new Scenario(2);Permanent a=s.attacker("Special",5,5,ability);s.blocker("A",3,3);s.blocker("B",3,3);assertTrue(s.blocks(a).getCombat().isEmpty());
        }
    }
    @Test public void maximumOneBlockerIsRespected(){
        Scenario s=new Scenario(2);Permanent a=s.attacker("One blocker",5,5);s.blocker("A",3,3);s.blocker("B",3,3);a.setMaxBlockedBy(1);
        assertTrue(s.blocks(a).getCombat().isEmpty());
    }
    @Test public void infectAtPositiveLifeStillNeedsABlock(){
        Scenario s=new Scenario(2);s.players.get(1).addCounters(CounterType.POISON.createInstance(9),s.ai.getId(),null,s.game);
        Permanent a=s.attacker("Infect",2,2,InfectAbility.getInstance());s.blocker("Fragile",5,1);assertEquals(1,s.blocks(a).getCombat().get(a).size());
    }
    @Test public void multiBlockDoesNotMutateTheOriginalPosition(){
        Scenario s=new Scenario(4);Permanent a=s.attacker("Five",5,5);s.blocker("A",3,3);s.blocker("B",3,3);String before=s.game.getState().getValue(true);
        s.blocks(a);assertEquals(before,s.game.getState().getValue(true));
    }
    @Test public void laterOpponentChangesTheStateScore(){
        Scenario s=new Scenario(4);int before=GameStateEvaluator2.evaluate(s.ai.getId(),s.game).getTotalScore();
        s.creature(s.players.get(3),"Later threat",8,8);assertTrue(GameStateEvaluator2.evaluate(s.ai.getId(),s.game).getTotalScore()<before);
    }
    @Test public void movingAnEnemyBoardBetweenSeatsDoesNotChangeScore(){
        int[] scores=new int[3];for(int seat=1;seat<=3;seat++){
            Scenario s=new Scenario(4);s.creature(s.players.get(seat),"Threat",8,8);scores[seat-1]=GameStateEvaluator2.evaluate(s.ai.getId(),s.game).getTotalScore();
        }assertEquals(scores[0],scores[1]);assertEquals(scores[1],scores[2]);
    }
    @Test public void oneOpponentAtZeroLifeIsNotWinningTheTable(){
        Scenario s=new Scenario(4);s.players.get(1).setLife(0,s.game,null);int score=GameStateEvaluator2.evaluate(s.ai.getId(),s.game).getTotalScore();
        assertTrue(Math.abs(score)<GameStateEvaluator2.WIN_GAME_SCORE/2);
    }
    @Test public void attackTargetsThreatRegardlessOfSeat(){
        for(int seat=1;seat<=3;seat++){
            Scenario s=new Scenario(4);Permanent a=s.creature(s.ai,"Evasive",2,2,new CantBeBlockedSourceAbility());
            s.creature(s.players.get(seat),"Dominant",8,8);s.ai.selectAttackers(s.game,s.ai.getId());
            assertEquals(s.players.get(seat).getId(),s.game.getCombat().getDefenderId(a.getId()));
        }
    }
    @Test public void defenderIsKeptWhenAnAttackWouldExposeLethal(){
        Scenario s=new Scenario(3);s.ai.setLife(5,s.game,null);Permanent a=s.creature(s.ai,"Defender",4,4);
        s.creature(s.players.get(2),"Lethal next turn",5,5);s.ai.selectAttackers(s.game,s.ai.getId());assertFalse(a.isAttacking());
    }
    @Test public void vigilancePreservesDefenceWhileAttacking(){
        Scenario s=new Scenario(3);s.ai.setLife(5,s.game,null);Permanent a=s.creature(s.ai,"Vigilant",4,4,VigilanceAbility.getInstance());
        s.creature(s.players.get(2),"Lethal next turn",5,5);s.ai.selectAttackers(s.game,s.ai.getId());assertTrue(a.isAttacking());
    }
    @Test public void deathtouchCanAttackWhenAnotherCreatureKeepsDefence(){
        Scenario s=new Scenario(2);Permanent a=s.creature(s.ai,"Deathtouch",1,1,DeathtouchAbility.getInstance());
        s.creature(s.ai,"Defence",8,9,mage.abilities.keyword.DefenderAbility.getInstance());s.blocker("Large",8,8);
        s.ai.selectAttackers(s.game,s.ai.getId());assertTrue(a.isAttacking());
    }
    @Test public void attackerRespectsAnOpponentDoubleBlock(){
        Scenario s=new Scenario(2);Permanent a=s.creature(s.ai,"Five",5,5);s.blocker("A",3,3);s.blocker("B",3,3);s.ai.selectAttackers(s.game,s.ai.getId());assertFalse(a.isAttacking());
    }
    @Test public void defenderIsKeptAgainstLethalPoisonAtThirtyLife(){
        Scenario s=new Scenario(3);s.ai.addCounters(CounterType.POISON.createInstance(9),s.ai.getId(),null,s.game);
        Permanent a=s.creature(s.ai,"Reserve",4,4);s.creature(s.players.get(2),"Poison threat",1,1,InfectAbility.getInstance());
        s.ai.selectAttackers(s.game,s.ai.getId());assertFalse(a.isAttacking());
    }
    @Test public void safeEliminationTakesPriorityOverOrdinaryDamage(){
        Scenario s=new Scenario(4);s.players.get(2).setLife(2,s.game,null);Permanent a=s.creature(s.ai,"Evasive",3,3,new CantBeBlockedSourceAbility(),VigilanceAbility.getInstance());
        s.creature(s.players.get(3),"Dominant",8,8);s.ai.selectAttackers(s.game,s.ai.getId());assertEquals(s.players.get(2).getId(),s.game.getCombat().getDefenderId(a.getId()));
    }
}

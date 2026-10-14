package mage.player.ai.util;

import mage.abilities.keyword.*;
import mage.game.Game;
import mage.game.permanent.Permanent;
import mage.player.ai.score.GameStateEvaluator2;
import java.util.*;

/** Bounded, public-information combat estimate. No engine state or hidden hands are changed.
 * Handles normal combat keywords; replacement effects and combat triggers still require
 * the engine. This is an estimate for ranking plans, never a replacement for rules checks. */
public final class CombatForecast {
    public static final class Result {
        public boolean attackerDies;
        public int lifeDamage, poisonDamage, attackerLifeGain, defenderLifeGain;
        public final Set<UUID> deadBlockers = new HashSet<>();
        public int exchange(Game game, Permanent attacker, List<Permanent> blockers) {
            int score = attackerDies ? value(attacker, game) : 0;
            for (Permanent b : blockers) if (deadBlockers.contains(b.getId())) score -= value(b, game);
            return score;
        }
    }
    public static int value(Permanent p, Game game) {
        return Math.max(100, GameStateEvaluator2.evaluatePermanent(p, game, false));
    }
    private static boolean has(Permanent p, mage.abilities.Ability ability) {
        return p.getAbilities().contains(ability);
    }
    private static final class Body {
        final Permanent permanent;
        int power, toughness, damage;
        boolean dead, touched;
        Body(Permanent p, Game game) { permanent=p; toughness=p.getToughness().getValue(); power=game.getCombat().useToughnessForDamage(p,game)?toughness:p.getPower().getValue(); damage=p.getDamage(); }
        boolean strikes(boolean first) {
            return !dead && (has(permanent, DoubleStrikeAbility.getInstance())
                    || first == has(permanent, FirstStrikeAbility.getInstance()));
        }
        int lethal(Body from) { return has(from.permanent, DeathtouchAbility.getInstance()) ? 1 : Math.max(0,toughness-damage); }
        void hit(int amount, Body from, Game game) {
            if (amount <= 0 || permanent.hasProtectionFrom(from.permanent,game)) return;
            if (has(from.permanent, InfectAbility.getInstance()) || has(from.permanent, WitherAbility.getInstance())) {
                power-=amount; toughness-=amount;
            } else damage+=amount;
            touched |= has(from.permanent,DeathtouchAbility.getInstance());
        }
        void check() { dead |= toughness <= 0 || (!has(permanent,IndestructibleAbility.getInstance()) && (damage>=toughness || touched)); }
    }
    public static Result resolve(Game game, Permanent attacker, List<Permanent> blockers) {
        List<List<Permanent>> orders = new ArrayList<>();
        if (blockers.size() <= 3) permutations(new ArrayList<>(blockers),0,orders);
        else {
            List<Permanent> sorted=new ArrayList<>(blockers);
            sorted.sort(Comparator.comparingInt(p -> value(p,game))); orders.add(sorted);
            List<Permanent> reverse=new ArrayList<>(sorted);Collections.reverse(reverse);orders.add(reverse);
        }
        Result worst=null; int worstScore=Integer.MAX_VALUE;
        // The attacker chooses damage assignment; never assume a helpful assignment.
        for (List<Permanent> order:orders) {
            Result r=ordered(game,attacker,order);
            int score=r.exchange(game,attacker,blockers)-r.lifeDamage*150-r.poisonDamage*500;
            if (worst==null || score<worstScore) { worst=r; worstScore=score; }
        }
        return worst;
    }
    private static void permutations(List<Permanent> p,int index,List<List<Permanent>> result) {
        if(index==p.size()){result.add(new ArrayList<>(p));return;}
        for(int i=index;i<p.size();i++){Collections.swap(p,index,i);permutations(p,index+1,result);Collections.swap(p,index,i);}
    }
    private static Result ordered(Game game,Permanent attacker,List<Permanent> blockers) {
        Body a=new Body(attacker,game);List<Body> bs=new ArrayList<>();for(Permanent b:blockers)bs.add(new Body(b,game));
        Result r=new Result();boolean trample=has(attacker,TrampleAbility.getInstance());
        for(boolean first:new boolean[]{true,false}) {
            if(a.dead)break;
            int[] outgoing=new int[bs.size()], incoming=new int[bs.size()];
            int remaining=a.strikes(first)?Math.max(0,a.power):0;
            int last=-1;for(int i=0;i<bs.size();i++)if(!bs.get(i).dead)last=i;
            for(int i=0;i<bs.size();i++) {
                Body b=bs.get(i);if(b.dead)continue;
                outgoing[i]=(!trample && i==last)?remaining:Math.min(remaining,b.lethal(a));
                remaining-=outgoing[i];incoming[i]=b.strikes(first)?Math.max(0,b.power):0;
            }
            if(blockers.isEmpty() || trample) {
                if(has(attacker,InfectAbility.getInstance()))r.poisonDamage+=remaining;else r.lifeDamage+=remaining;
                if(has(attacker,LifelinkAbility.getInstance()))r.attackerLifeGain+=remaining;
            }
            // All damage in a step is assigned using the pre-damage powers.
            for(int i=0;i<bs.size();i++) {
                Body b=bs.get(i);if(b.dead)continue;
                if(!b.permanent.hasProtectionFrom(attacker,game) && has(attacker,LifelinkAbility.getInstance()))r.attackerLifeGain+=outgoing[i];
                if(!attacker.hasProtectionFrom(b.permanent,game) && has(b.permanent,LifelinkAbility.getInstance()))r.defenderLifeGain+=incoming[i];
                b.hit(outgoing[i],a,game);a.hit(incoming[i],b,game);
            }
            a.check();for(Body b:bs)b.check();
        }
        r.attackerDies=a.dead;for(Body b:bs)if(b.dead)r.deadBlockers.add(b.permanent.getId());
        return r;
    }
    private CombatForecast() { }
}

package mage.player.ai.util;

import mage.abilities.keyword.DefenderAbility;
import mage.abilities.keyword.VigilanceAbility;
import mage.counters.CounterType;
import mage.game.Game;
import mage.game.permanent.Permanent;
import mage.player.ai.score.ArtificialScoringSystem;
import mage.players.Player;
import java.util.*;

/** Compares bounded attack plans against all opponents, including the cost of lost defence. */
public final class AttackPlanner {
    public static final class Plan {
        public final Map<Permanent,UUID> attacks=new LinkedHashMap<>();
        public double score;
        Plan copy(){Plan p=new Plan();p.attacks.putAll(attacks);p.score=score;return p;}
    }
    private final Game game;
    private final Player player;
    private final List<Player> opponents=new ArrayList<>();
    private final Map<UUID,Double> threats=new HashMap<>();
    private final Map<String,CombatForecast.Result> forecasts=new HashMap<>();
    private final Map<UUID,Integer> values=new HashMap<>();
    private final Map<UUID,List<Permanent>> enemyBlockers=new HashMap<>(), enemyAttackers=new HashMap<>();
    private final Map<String,List<List<Permanent>>> blockOptions=new HashMap<>();
    private final Map<String,Boolean> blockLegal=new HashMap<>();
    private final double baselineRisk;

    private AttackPlanner(Game game,Player player) {
        this.game=game;this.player=player;double maximum=1;
        for(UUID id:game.getOpponents(player.getId(),true))if(player.hasOpponent(id,game)){
            Player o=game.getPlayer(id);opponents.add(o);double threat=0;
            for(Permanent p:game.getBattlefield().getAllActivePermanents(id))threat+=value(p);
            threats.put(id,threat);maximum=Math.max(maximum,threat);
            enemyBlockers.put(id,new ArrayList<>(o.getAvailableBlockers(game)));
            List<Permanent> attacks=new ArrayList<>();
            for(Permanent p:game.getBattlefield().getAllActivePermanents(id))
                if(p.isCreature(game) && p.getPower().getValue()>0 && !p.getAbilities().contains(DefenderAbility.getInstance()))attacks.add(p);
            attacks.sort(Comparator.comparingInt((Permanent p)->p.getPower().getValue()).reversed());enemyAttackers.put(id,attacks);
        }
        for(Player o:opponents)threats.put(o.getId(),0.5+0.5*threats.get(o.getId())/maximum);
        baselineRisk=risk(new Plan(),Collections.emptySet(),Collections.emptySet());
    }
    public static Plan choose(Game game,Player player) { return new AttackPlanner(game,player).choose(); }
    private int value(Permanent p){return values.computeIfAbsent(p.getId(),id->CombatForecast.value(p,game));}
    private CombatForecast.Result forecast(Permanent a,List<Permanent> bs){
        StringBuilder key=new StringBuilder(a.getId().toString());for(Permanent b:bs)key.append('/').append(b.getId());
        return forecasts.computeIfAbsent(key.toString(),k->CombatForecast.resolve(game,a,bs));
    }
    private boolean canBlock(Permanent b,Permanent a){
        String key=b.getId()+"/"+a.getId();return blockLegal.computeIfAbsent(key,k->b.canBlock(a.getId(),game));
    }
    private List<List<Permanent>> groups(Permanent attacker,Player defender){
        String key=attacker.getId()+"/"+defender.getId();
        return blockOptions.computeIfAbsent(key,k->BlockPlanner.groups(game,attacker,enemyBlockers.get(defender.getId()),48));
    }
    private Plan choose() {
        Plan best=new Plan();
        Map<UUID,List<Permanent>> targets=new LinkedHashMap<>();
        for(Player o:opponents) {
            List<Permanent> available=new ArrayList<>(player.getAvailableAttackers(o.getId(),game));
            available.removeIf(a->a.getPower().getValue()<=0 && !game.getCombat().useToughnessForDamage(a,game));
            available.sort(Comparator.comparingInt((Permanent a)->value(a)).reversed().thenComparing(p->p.getId().toString()));
            targets.put(o.getId(),available);
            for(Permanent p:game.getBattlefield().getAllActivePermanents()) {
                if((p.isPlaneswalker(game)||p.isBattle(game)) && p.canBeAttacked(null,o.getId(),game))targets.put(p.getId(),available);
            }
        }
        // Full attacks catch group lethals; removing individual creatures finds useful reserves.
        for(Map.Entry<UUID,List<Permanent>> entry:targets.entrySet()) {
            Plan plan=new Plan();for(Permanent a:entry.getValue())plan.attacks.put(a,entry.getKey());
            plan.score=score(plan);if(plan.score>best.score)best=plan.copy();
            for(int step=0;step<Math.min(12,entry.getValue().size());step++) {
                Plan reduced=null;
                for(Permanent a:new ArrayList<>(plan.attacks.keySet())) {
                    Plan candidate=plan.copy();candidate.attacks.remove(a);candidate.score=score(candidate);
                    if(reduced==null || candidate.score>reduced.score)reduced=candidate;
                }
                if(reduced==null)break;
                plan=reduced;if(plan.score>best.score)best=plan.copy();
            }
        }
        // A second plan grows by marginal utility and may split attacks among players.
        Plan split=new Plan();
        List<Permanent> all=new ArrayList<>(player.getAvailableAttackers(game));
        all.sort(Comparator.comparingInt((Permanent a)->value(a)).reversed().thenComparing(p->p.getId().toString()));
        for(Permanent a:all) {
            Plan next=split;
            for(Map.Entry<UUID,List<Permanent>> entry:targets.entrySet())if(entry.getValue().contains(a)) {
                Plan candidate=split.copy();candidate.attacks.put(a,entry.getKey());candidate.score=score(candidate);
                if(candidate.score>next.score)next=candidate;
            }
            split=next;
        }
        if(split.score>best.score)best=split;
        return best;
    }
    private double score(Plan plan) {
        if(plan.attacks.isEmpty())return 0;
        double score=0;Set<UUID> eliminated=new HashSet<>(), killed=new HashSet<>();
        // A defender shares blockers between attacks on themselves and their permanents.
        for(Player opponent:opponents) {
            Set<Permanent> blockers=new HashSet<>(enemyBlockers.get(opponent.getId()));
            int life=0,poison=0,lifeGain=0;
            int expectedLife=0,expectedPoison=0;
            for(Map.Entry<Permanent,UUID> entry:plan.attacks.entrySet())if(entry.getValue().equals(opponent.getId())){
                CombatForecast.Result free=forecast(entry.getKey(),Collections.emptyList());expectedLife+=free.lifeDamage;expectedPoison+=free.poisonDamage;
            }
            for(Map.Entry<Permanent,UUID> entry:plan.attacks.entrySet()) {
                UUID target=entry.getValue();Permanent defending=game.getPermanent(target);
                if(!target.equals(opponent.getId()) && (defending==null || !defending.canBeAttacked(null,opponent.getId(),game)))continue;
                Permanent attacker=entry.getKey();
                List<Permanent> chosen=Collections.emptyList();CombatForecast.Result outcome=forecast(attacker,chosen);
                CombatForecast.Result free=outcome;boolean playerTarget=target.equals(opponent.getId());
                int bestDefence=defenceScore(outcome,attacker,chosen,opponent,playerTarget,expectedLife,expectedPoison,lifeGain);
                for(List<Permanent> group:groups(attacker,opponent)) {
                    if(!blockers.containsAll(group))continue;
                    CombatForecast.Result r=forecast(attacker,group);int defence=defenceScore(r,attacker,group,opponent,playerTarget,
                            expectedLife-free.lifeDamage+r.lifeDamage,expectedPoison-free.poisonDamage+r.poisonDamage,lifeGain);
                    if(defence>bestDefence){bestDefence=defence;chosen=group;outcome=r;}
                }
                if(playerTarget){expectedLife+=outcome.lifeDamage-free.lifeDamage;expectedPoison+=outcome.poisonDamage-free.poisonDamage;}
                blockers.removeAll(chosen);
                if(outcome.attackerDies){score-=value(attacker);killed.add(attacker.getId());}
                for(Permanent b:chosen)if(outcome.deadBlockers.contains(b.getId())){score+=value(b)*threats.get(opponent.getId());killed.add(b.getId());}
                if(target.equals(opponent.getId())){life+=outcome.lifeDamage;poison+=outcome.poisonDamage;lifeGain+=outcome.defenderLifeGain;}
                else if(defending!=null){
                    int counters=defending.getCounters(game).getCount(defending.isPlaneswalker(game)?CounterType.LOYALTY:CounterType.DEFENSE);
                    int damage=outcome.lifeDamage+outcome.poisonDamage;
                    score+=Math.min(counters,damage)*100+(damage>=counters?value(defending):0);
                }
                score+=outcome.attackerLifeGain*100;
            }
            int after=opponent.getLife()-(opponent.isLifeTotalCanChange()?life:0)+lifeGain;
            score+=(ArtificialScoringSystem.getLifeScore(opponent.getLife())-ArtificialScoringSystem.getLifeScore(after)+life*40+poison*600)*threats.get(opponent.getId());
            if(opponent.canLose(game) && (after<=0 || poison+opponent.getCountersCount(CounterType.POISON)>=10)) {
                eliminated.add(opponent.getId());score+=15000;
            }
        }
        if(eliminated.size()==opponents.size() && !opponents.isEmpty())return score+10000000;
        score-=Math.max(0,risk(plan,eliminated,killed)-baselineRisk);
        return score;
    }
    private int defenceScore(CombatForecast.Result r,Permanent attacker,List<Permanent> blockers,Player defender,boolean playerTarget,int life,int poison,int gain) {
        int exchange=r.attackerDies?value(attacker):0;
        for(Permanent b:blockers)if(r.deadBlockers.contains(b.getId()))exchange-=value(b);
        return exchange+(playerTarget?BlockPlanner.survival(defender,life,poison,gain+r.defenderLifeGain,game):-r.lifeDamage*100);
    }
    private double risk(Plan plan,Set<UUID> eliminated,Set<UUID> killed) {
        List<Permanent> reserves=new ArrayList<>(player.getAvailableBlockers(game));
        reserves.removeIf(p->killed.contains(p.getId()) || (plan.attacks.containsKey(p) && !p.getAbilities().contains(VigilanceAbility.getInstance())));
        double sum=0,maximum=0;boolean poisonLethal=false;
        for(Player opponent:opponents)if(!eliminated.contains(opponent.getId())) {
            List<Permanent> defence=new ArrayList<>(reserves);
            // Next turn includes untapping; currently tapped creatures remain threats.
            List<Permanent> attackers=new ArrayList<>(enemyAttackers.get(opponent.getId()));
            attackers.removeIf(p->killed.contains(p.getId()));
            int life=0,poison=0,unblockedLife=0,unblockedPoison=0;
            for(Permanent a:attackers){CombatForecast.Result r=forecast(a,Collections.emptyList());unblockedLife+=r.lifeDamage;unblockedPoison+=r.poisonDamage;}
            boolean lethal=unblockedLife>=player.getLife() || unblockedPoison+player.getCountersCount(CounterType.POISON)>=10;
            for(Permanent a:attackers) {
                CombatForecast.Result unblocked=forecast(a,Collections.emptyList());
                Permanent chosen=null;CombatForecast.Result best=unblocked;int bestReduction=0;
                for(Permanent b:defence)if(a.getMinBlockedBy()<=1 && canBlock(b,a)) {
                    CombatForecast.Result r=forecast(a,Collections.singletonList(b));
                    int reduction=(unblocked.lifeDamage-r.lifeDamage)*(lethal?5000:100)+(unblocked.poisonDamage-r.poisonDamage)*(lethal?10000:400);
                    if(r.attackerDies)reduction+=value(a)/2;
                    if(r.deadBlockers.contains(b.getId()))reduction-=value(b);
                    if(reduction>bestReduction){bestReduction=reduction;chosen=b;best=r;}
                }
                if(chosen!=null)defence.remove(chosen);
                life+=best.lifeDamage;poison+=best.poisonDamage;
            }
            double danger=life+poison*Math.max(3.0,player.getLife()/10.0);
            poisonLethal |= poison>0 && poison+player.getCountersCount(CounterType.POISON)>=10;
            sum+=danger;maximum=Math.max(maximum,danger);
        }
        // Don't assume every enemy coordinates an all-out attack, but don't ignore them either.
        double damage=maximum+0.35*(sum-maximum);
        double risk=damage*160;
        if(player.canLose(game) && (damage>=player.getLife() || poisonLethal))risk+=1000000+Math.max(0,damage-player.getLife())*1000;
        return risk;
    }
}

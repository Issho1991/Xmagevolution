package mage.player.ai.util;

import mage.counters.CounterType;
import mage.game.Game;
import mage.game.permanent.Permanent;
import mage.player.ai.score.ArtificialScoringSystem;
import mage.players.Player;
import java.util.*;

/** Beam search across the whole combat, with bounded pairs/triples and no game copies. */
public final class BlockPlanner {
    private static final int WIDTH=24, CANDIDATES=96;
    private static final class Option {
        final Permanent attacker; final List<Permanent> blockers; final CombatForecast.Result result;
        final boolean playerTarget; final int exchange;
        Option(Game game,Permanent a,List<Permanent> bs) {
            attacker=a;blockers=bs;result=CombatForecast.resolve(game,a,bs);
            playerTarget=game.getPlayer(game.getCombat().getDefenderId(a.getId()))!=null;
            exchange=result.exchange(game,a,bs);
        }
    }
    private static final class Plan {
        final Set<UUID> used=new HashSet<>();final List<Option> options=new ArrayList<>();
        int life,poison,exchange,gain,score;
        Plan copy(){Plan p=new Plan();p.used.addAll(used);p.options.addAll(options);p.life=life;p.poison=poison;p.exchange=exchange;p.gain=gain;return p;}
    }
    public static CombatInfo choose(Game game,List<Permanent> attackers,List<Permanent> blockers) {
        CombatInfo result=new CombatInfo();if(attackers.isEmpty() || blockers.isEmpty())return result;
        UUID defenderId=blockers.get(0).getControllerId();Player defender=game.getPlayer(defenderId);
        if(defender==null)return result;
        List<Permanent> relevant=new ArrayList<>();
        for(Permanent a:attackers)if(defenderId.equals(game.getCombat().getDefendingPlayerId(a.getId(),game)))relevant.add(a);
        relevant.sort(Comparator.comparingInt((Permanent a)->CombatForecast.value(a,game)).reversed().thenComparing(p->p.getId().toString()));
        Plan start=new Plan();Map<UUID,Option> unblocked=new HashMap<>();
        for(Permanent a:relevant){Option o=new Option(game,a,Collections.emptyList());unblocked.put(a.getId(),o);if(o.playerTarget){start.life+=o.result.lifeDamage;start.poison+=o.result.poisonDamage;}}
        List<Plan> beam=Collections.singletonList(start);
        for(Permanent a:relevant) {
            List<Option> options=new ArrayList<>();options.add(unblocked.get(a.getId()));
            for(List<Permanent> bs:groups(game,a,blockers,CANDIDATES))options.add(new Option(game,a,bs));
            List<Plan> next=new ArrayList<>();
            for(Plan p:beam)for(Option o:options){
                boolean conflict=false;for(Permanent b:o.blockers)if(p.used.contains(b.getId())){conflict=true;break;}if(conflict)continue;
                Plan q=p.copy();q.options.add(o);for(Permanent b:o.blockers)q.used.add(b.getId());
                Option free=unblocked.get(a.getId());
                if(o.playerTarget){q.life+=o.result.lifeDamage-free.result.lifeDamage;q.poison+=o.result.poisonDamage-free.result.poisonDamage;}
                else q.exchange+=(free.result.lifeDamage-o.result.lifeDamage)*100;
                q.exchange+=o.exchange;q.gain+=o.result.defenderLifeGain;
                q.score=q.exchange+survival(defender,q.life,q.poison,q.gain,game);
                next.add(q);
            }
            next.sort(Comparator.comparingInt((Plan p)->p.score).reversed().thenComparingInt(p->p.used.size()));
            beam=new ArrayList<>(next.subList(0,Math.min(WIDTH,next.size())));
        }
        if(!beam.isEmpty())for(Option o:beam.get(0).options)for(Permanent b:o.blockers)result.addPair(o.attacker,b);
        return result;
    }
    static int survival(Player p,int lifeDamage,int poison,int gain,Game game) {
        int life=p.getLife()-(p.isLifeTotalCanChange()?lifeDamage:0)+gain;
        int score=(ArtificialScoringSystem.getLifeScore(life)-ArtificialScoringSystem.getLifeScore(p.getLife()))/2;
        int totalPoison=p.getCountersCount(CounterType.POISON)+poison;
        score-=poison*600;
        if(p.canLose(game)) {
            if(life<=0)score-=1000000+Math.max(0,-life)*1000;
            if(totalPoison>=10)score-=1000000+(totalPoison-10)*1000;
        }
        return score;
    }
    public static List<List<Permanent>> groups(Game game,Permanent attacker,List<Permanent> blockers,int limit) {
        List<Permanent> legal=new ArrayList<>();
        for(Permanent b:blockers)if(b.canBlock(attacker.getId(),game))legal.add(b);
        legal.sort(Comparator.comparingInt((Permanent b)->CombatForecast.value(b,game)).thenComparing(p->p.getId().toString()));
        int min=Math.max(1,attacker.getMinBlockedBy()), max=attacker.getMaxBlockedBy();
        if(max<=0)max=Integer.MAX_VALUE;
        List<List<Permanent>> result=new ArrayList<>();
        if(min==1)for(Permanent b:legal){result.add(Collections.singletonList(b));if(result.size()>=limit)return result;}
        // Preserve a few strong blockers as well as the cheapest candidates.
        List<Permanent> pool=new ArrayList<>(legal.subList(0,Math.min(8,legal.size())));
        for(int i=Math.max(8,legal.size()-4);i<legal.size();i++)if(!pool.contains(legal.get(i)))pool.add(legal.get(i));
        if(min<=2 && max>=2)for(int i=0;i<pool.size();i++)for(int j=i+1;j<pool.size();j++) {
            result.add(Arrays.asList(pool.get(i),pool.get(j)));if(result.size()>=limit)return result;
        }
        if(min<=3 && max>=3)for(int i=0;i<Math.min(8,pool.size());i++)for(int j=i+1;j<Math.min(8,pool.size());j++)for(int k=j+1;k<Math.min(8,pool.size());k++) {
            result.add(Arrays.asList(pool.get(i),pool.get(j),pool.get(k)));if(result.size()>=limit)return result;
        }
        if(min>3 && min<=max && legal.size()>=min)result.add(new ArrayList<>(legal.subList(0,min)));
        return result;
    }
    private BlockPlanner() { }
}

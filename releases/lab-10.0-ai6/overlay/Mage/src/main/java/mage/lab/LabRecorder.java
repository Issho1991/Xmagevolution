package mage.lab;

import mage.cards.Card;
import mage.abilities.Ability;
import mage.counters.CounterType;
import mage.game.events.DamagedEvent;
import mage.MageObject;
import mage.game.events.GameEvent;
import mage.game.events.ZoneChangeEvent;
import mage.game.match.Match;
import mage.game.match.MatchPlayer;
import com.google.gson.Gson;
import java.io.BufferedWriter;
import mage.game.Game;
import mage.game.events.TableEvent;
import mage.game.permanent.Permanent;
import mage.players.Player;
import mage.constants.PhaseStep;
import org.apache.log4j.Logger;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Observes the original server game only. Does not copy, evaluate or mutate engine state. */
public final class LabRecorder {
    public static final String BUILD = "XMage Lab 10.0 · AI revisione 6";
    private static final Logger LOG = Logger.getLogger(LabRecorder.class);
    private static final Map<UUID, LabRecorder> ACTIVE = new ConcurrentHashMap<>();
    private static final Gson COMPACT = new Gson();
    private static final int MAX_EVENTS = 20000, MAX_TRACE_CHARS = 16 * 1024 * 1024;
    private final Game observedGame;
    private final long began = System.nanoTime();
    private BufferedWriter writer;
    private long traceChars;
    private boolean playing;
    private final Set<UUID> departing = new HashSet<>();
    private final LabStore store;
    private final LabGame record = new LabGame();
    private final Set<String> seen = new HashSet<>();
    private boolean finished;
    private int lastSampleTurn;
    private final Map<Integer, Checkpoint> bookmarks = new HashMap<>(), turns = new HashMap<>();
    private final BitSet invalidSamples = new BitSet();
    private final Map<UUID, String[]> pendingExits = new HashMap<>();
    // Network/concession requests live outside the game state and survive internal state restoration.
    private final Map<UUID, String[]> requestedExits = new HashMap<>();
    private static final ThreadLocal<ExitContext> EXIT_CONTEXT = new ThreadLocal<>();

    public static final class ExitContext implements AutoCloseable {
        private final Game game;
        private final UUID player;
        private final String reason, source;
        private final ExitContext previous;
        private ExitContext(Game game, UUID player, String reason, String source) {
            this.game = game; this.player = player; this.reason = reason; this.source = source;
            this.previous = EXIT_CONTEXT.get(); EXIT_CONTEXT.set(this);
        }
        @Override public void close() {
            if (previous == null) EXIT_CONTEXT.remove(); else EXIT_CONTEXT.set(previous);
        }
    }

    /** Scope surrounds the engine operation; a prevented loss is never recorded as an exit. */
    public static ExitContext exitContext(Game game, Player player, String reason, Ability source) {
        if (game == null || game.isSimulation()) return null;
        return new ExitContext(game, player.getId(), reason, source == null ? null : name(game, source.getSourceId()));
    }

    public static void lost(Game game, Player player) {
        LabRecorder r = active(game);
        if (r == null) return;
        synchronized (r) { r.pendingExits.putIfAbsent(player.getId(), exitDetails(game, player)); }
    }

    /** Record decisions at their source, also in the server log if recording is off. */
    public static void drawEvent(Game game, String kind, UUID player, UUID source,
                                 String reason, String origin, Boolean accepted) {
        if (game == null || game.isSimulation()) return;
        LOG.info("Laboratorio " + kind + " game=" + game.getId() + " player=" + player
                + " reason=" + reason + " origin=" + origin + " accepted=" + accepted
                + " stack=" + game.getStack().size());
        LabRecorder r = active(game);
        if (r == null) return;
        synchronized (r) {
            try {
                LabGame.Event e = r.newEvent(game, kind, player);
                e.sourceId = id(source); e.sourceName = name(game, source);
                e.drawReason = reason; e.drawResponseOrigin = origin; e.drawAccepted = accepted;
                e.stackSize = game.getStack().size(); e.stackSources = new ArrayList<>();
                for (mage.game.stack.StackObject item : game.getStack()) {
                    if (e.stackSources.size() >= 20) break;
                    e.stackSources.add(id(item.getSourceId()) + ":" + name(game, item.getSourceId()));
                }
                r.append(e); r.flushTrace();
            } catch (Exception ex) { r.traceFailure(ex); }
        }
    }

    public static void drew(Game game, Player player) {
        String[] reason = exitDetails(game, player);
        drawEvent(game, "DRAW_ASSIGNED", player.getId(), null, reason[0], null, null);
        LabRecorder r = active(game);
        if (r != null) synchronized (r) { r.pendingExits.put(player.getId(), reason); }
    }

    /** Pending server removal: emitted only if the player actually leaves. Safe across async quit tasks. */
    public static void requestedExit(Game game, Player player, String reason) {
        LabRecorder r = active(game);
        if (r == null || player == null || player.hasLeft()) return;
        synchronized (r) { r.requestedExits.putIfAbsent(player.getId(), new String[]{reason, null}); }
    }

    private static String[] exitDetails(Game game, Player player) {
        ExitContext c = EXIT_CONTEXT.get();
        return c != null && c.game == game && c.player.equals(player.getId())
                ? new String[]{c.reason, c.source} : new String[]{"UNSPECIFIED_RULE_OR_EFFECT", null};
    }

    /** Server confirmed a connection-related removal, not merely a transient disconnect. */
    public static void disconnectedExit(Game game, Player player) {
        try (ExitContext context = exitContext(game, player, "DISCONNECTED", null)) { beforeLeave(game, player); }
    }

    /** Lightweight positions in the append-only observation logs; no engine state is retained. */
    public static final class Checkpoint {
        final long event;
        final int sample;
        final boolean playing;
        Checkpoint(long event, int sample, boolean playing) { this.event = event; this.sample = sample; this.playing = playing; }
    }

    public static void bookmark(Game game, int bookmark) {
        LabRecorder r = active(game);
        if (r != null) synchronized (r) { r.bookmarks.put(bookmark, r.checkpoint()); }
    }
    private Checkpoint checkpoint() { return new Checkpoint(record.telemetry.events, record.samples.size(), playing); }
    public static Checkpoint bookmarkCheckpoint(Game game, int bookmark) {
        LabRecorder r = active(game);
        if (r == null) return null;
        synchronized (r) { return r.bookmarks.get(bookmark); }
    }
    public static void discardBookmarks(Game game, int fromInclusive) {
        LabRecorder r = active(game);
        if (r != null) synchronized (r) { r.bookmarks.keySet().removeIf(key -> key >= fromInclusive); }
    }
    public static void saveTurn(Game game, int oldestTurn) {
        LabRecorder r = active(game);
        if (r != null) synchronized (r) {
            r.turns.keySet().removeIf(key -> key < oldestTurn);
            r.turns.put(game.getTurnNum(), r.checkpoint());
        }
    }
    public static void turnRestored(Game game, int turn) {
        LabRecorder r = active(game);
        if (r != null) synchronized (r) {
            r.restore(game, r.turns.get(turn), "TURN_ROLLBACK", "Ritorno all’inizio del turno " + turn);
            r.turns.keySet().removeIf(key -> key > turn);
            r.bookmarks.clear();
            // The engine saves a fresh copy at the restored start of turn.
            r.turns.put(turn, r.checkpoint());
            r.exclude("Partita con rollback"); // Explicit replay stays out of comparative win statistics.
        }
    }

    private LabRecorder(Game game, LabStore store) { this.observedGame = game; this.store = store; }

    private static LabRecorder active(Game game) {
        if (game == null || game.isSimulation() || ACTIVE.isEmpty()) return null;
        LabRecorder recorder = ACTIVE.get(game.getId());
        return recorder != null && recorder.observedGame == game && !recorder.finished ? recorder : null;
    }

    public static LabGame.MatchInfo matchInfo(Match match) {
        LabGame.MatchInfo info = new LabGame.MatchInfo();
        info.id = match.getId().toString();
        info.round = match.getNumGames();
        info.winsNeeded = match.getWinsNeeded();
        for (MatchPlayer player : match.getPlayers()) {
            String id = player.getPlayer().getId().toString();
            info.names.put(id, player.getName());
            info.scoreBefore.put(id, player.getWins());
        }
        return info;
    }

    /** Read the engine's settled match score, after endGame has awarded wins. */
    public static void matchFinished(Match match) {
        Game game = match.getGame();
        if (game == null || game.isSimulation() || game.getOptions().laboratoryMatch == null) return;
        try {
            LabStore store = new LabStore(LabStore.defaultRoot());
            LabGame data = store.game(game.getId().toString());
            if (data.match == null || !match.getId().toString().equals(data.match.id)) return;
            data.match.resultRecorded = true;
            data.match.finished = match.getEndTime() != null;
            data.match.scoreAfter.clear();
            data.match.winners.clear();
            for (MatchPlayer player : match.getPlayers()) {
                String id = player.getPlayer().getId().toString();
                data.match.scoreAfter.put(id, player.getWins());
                if (player.isMatchWinner()) data.match.winners.add(id);
            }
            store.saveGame(data);
            if (data.match.finished) {
                List<String> ids = new ArrayList<>();
                for (Game played : match.getGames()) ids.add(played.getId().toString());
                LabReviewService.scheduleMatch(store, data.match.id, ids);
            }
        } catch (Exception e) { LOG.error("Laboratorio: punteggio incontro non salvato", e); }
    }

    private void warning(String message) {
        if (!record.telemetry.warnings.contains(message)) record.telemetry.warnings.add(message);
    }

    private LabGame.Event newEvent(Game game, String kind, UUID playerId) {
        LabGame.Event event = new LabGame.Event();
        event.kind = kind;
        event.stage = playing ? "PLAY" : "MULLIGAN";
        event.elapsedMillis = (System.nanoTime() - began) / 1000000L;
        event.globalTurn = game.getTurnNum();
        event.phase = game.getStep() == null ? "SETUP" : game.getStep().getType().name();
        event.activePlayer = id(game.getActivePlayerId());
        event.playerId = id(playerId);
        return event;
    }

    private static String id(UUID value) { return value == null ? null : value.toString(); }
    private static String name(Game game, UUID id) {
        if (id == null) return null;
        MageObject object = game.getObject(id);
        if (object == null) object = game.getCard(id);
        return object == null ? null : object.getName();
    }

    private void append(LabGame.Event event) {
        if (writer == null) return;
        event.sequence = record.telemetry.events + 1;
        String line = COMPACT.toJson(event);
        if (event.sequence > MAX_EVENTS || traceChars + line.length() + 1 > MAX_TRACE_CHARS) {
            warning("Limite della traccia raggiunto: eventi successivi non salvati; totali IA continuano.");
            closeTrace();
            return;
        }
        try {
            writer.write(line); writer.newLine();
            record.telemetry.events++;
            traceChars += line.length() + 1;
            if (event.sequence % 128 == 0) writer.flush();
        } catch (IOException e) { traceFailure(e); }
    }

    private void traceFailure(Exception e) {
        warning("Errore di registrazione eventi: traccia parziale.");
        LOG.error("Laboratorio: traccia parziale per " + record.id, e);
        closeTrace();
    }

    private void flushTrace() {
        if (writer != null) try { writer.flush(); } catch (IOException e) { traceFailure(e); }
    }

    private void closeTrace() {
        BufferedWriter old = writer; writer = null;
        if (old != null) try { old.close(); } catch (IOException e) {
            warning("Chiusura della traccia non riuscita: dati parziali.");
            LOG.error("Laboratorio: chiusura traccia", e);
        }
    }

    /** Only completed individual engine events. Batch wrappers and attempted actions are ignored. */
    public static void event(Game game, GameEvent source) {
        LabRecorder recorder = active(game);
        if (recorder == null) return;
        switch (source.getType()) {
            case ZONE_CHANGE: case SPELL_CAST: case LAND_PLAYED: case DREW_CARD:
            case DISCARDED_CARD: case MILLED_CARD: case DAMAGED_PLAYER: case GAINED_LIFE: case LOST_LIFE:
            case DECLARED_ATTACKERS: case DECLARED_BLOCKERS:
                break;
            default: return;
        }
        synchronized (recorder) {
            try {
                LabGame.Event event = recorder.newEvent(game, source.getType().name(), source.getPlayerId());
                event.targetId = id(source.getTargetId()); event.sourceId = id(source.getSourceId());
                event.targetName = name(game, source.getTargetId()); event.sourceName = name(game, source.getSourceId());
                event.amount = source.getAmount();
                if(source.getType()==GameEvent.EventType.DECLARED_ATTACKERS || source.getType()==GameEvent.EventType.DECLARED_BLOCKERS)
                    combatSnapshot(game,event);
                if(source.getType()==GameEvent.EventType.SPELL_CAST) {
                    mage.game.stack.Spell spell=game.getStack().getSpell(source.getTargetId());
                    if(spell!=null) {
                        event.spellTargets=new LinkedHashMap<>();event.targetControllers=new LinkedHashMap<>();
                        mage.abilities.SpellAbility ability=spell.getSpellAbility();
                        for(UUID modeId:ability.getModes().getSelectedModes()) {
                            for(mage.target.Target target:ability.getModes().get(modeId).getTargets())for(UUID targetId:target.getTargets()) {
                                event.spellTargets.put(id(targetId),name(game,targetId));
                                Permanent permanent=game.getPermanent(targetId);
                                if(permanent!=null)event.targetControllers.put(id(targetId),id(permanent.getControllerId()));
                            }
                        }
                    }
                }
                if (source instanceof DamagedEvent) event.combat = ((DamagedEvent) source).isCombatDamage();
                if (source instanceof ZoneChangeEvent) {
                    ZoneChangeEvent zone = (ZoneChangeEvent) source;
                    event.from = zone.getFromZone() == null ? null : zone.getFromZone().name();
                    event.to = zone.getToZone() == null ? null : zone.getToZone().name();
                    if (zone.getTarget() != null) event.targetName = zone.getTarget().getName();
                }
                // A count is a number of events, not amount of life or number of cards evaluated as useful.
                recorder.record.telemetry.counts.computeIfAbsent(event.playerId == null ? "UNKNOWN" : event.playerId,
                        key -> new TreeMap<>()).merge(event.stage + ":" + event.kind, 1L, Long::sum);
                recorder.append(event);
            } catch (Exception e) { recorder.traceFailure(e); }
        }
    }

    public static void hand(Game game, Player player, String kind) {
        LabRecorder recorder = active(game);
        if (recorder == null) return;
        synchronized (recorder) {
            try {
                LabGame.Event event = recorder.newEvent(game, kind, player.getId());
                event.hand = new TreeMap<>(); event.handCards = new LinkedHashMap<>();
                event.amount = player.getHand().size();
                for (UUID cardId : player.getHand()) {
                    if (event.handCards.size() >= 250) { recorder.warning("Mano di mulligan oltre 250 carte: elenco parziale."); break; }
                    String cardName = name(game, cardId);
                    if (cardName == null) cardName = "Nome non disponibile";
                    event.hand.merge(cardName, 1, Integer::sum);
                    event.handCards.put(cardId.toString(), cardName);
                }
                recorder.append(event);
                recorder.flushTrace();
            } catch (Exception e) { recorder.traceFailure(e); }
        }
    }

    private static void combatSnapshot(Game game, LabGame.Event event) {
        event.battlefield=new ArrayList<>();event.combatAssignments=new ArrayList<>();event.battlefieldComplete=true;
        event.combatPlayers=new LinkedHashMap<>();
        for(Player player:game.getPlayers().values())if(player.isInGame())
            event.combatPlayers.put(id(player.getId()),Arrays.asList(player.getLife(),player.getCountersCount(CounterType.POISON)));
        for(Permanent p:game.getBattlefield().getAllActivePermanents()) {
            if(event.battlefield.size()>=160){event.battlefieldComplete=false;break;}
            LabGame.CombatPermanent c=new LabGame.CombatPermanent();
            c.id=id(p.getId());c.name=p.getName();c.controller=id(p.getControllerId());
            c.power=p.getPower().getValue();c.toughness=p.getToughness().getValue();c.damage=p.getDamage();
            c.tapped=p.isTapped();c.summoningSickness=p.hasSummoningSickness();c.counters=new LinkedHashMap<>();
            for(mage.counters.Counter counter:p.getCounters(game).values())c.counters.put(counter.getName(),counter.getCount());
            c.abilities=new ArrayList<>();int chars=0;
            for(String rule:p.getAbilities(game).getRules(game,p)){
                if(chars+rule.length()>2000){event.battlefieldComplete=false;break;}
                c.abilities.add(rule);chars+=rule.length();
            }
            event.battlefield.add(c);
        }
        for(mage.game.combat.CombatGroup group:game.getCombat().getGroups())for(UUID attacker:group.getAttackers()) {
            LabGame.CombatAssignment a=new LabGame.CombatAssignment();a.attacker=id(attacker);a.defender=id(group.getDefenderId());a.blockers=new ArrayList<>();
            for(UUID blocker:group.getBlockers())a.blockers.add(id(blocker));event.combatAssignments.add(a);
        }
    }

    public static void mulliganFinished(Game game) {
        LabRecorder recorder = active(game);
        if (recorder != null) synchronized (recorder) { recorder.playing = true; recorder.flushTrace(); }
    }

    /** Elapsed, thread CPU and explicit response waits in the original AI call. */
    public static long beginDecision(Game game, Player player) {
        return player.isHuman() || !player.isGameUnderControl() || active(game) == null ? 0 : LabTiming.begin(game);
    }

    public static LabSearchTiming searchSubmitted() { return LabTiming.searchSubmitted(); }

    public static long beginResponseWait(Game game) { return LabTiming.beginResponseWait(game); }
    public static void endResponseWait(Game game, long start) { LabTiming.endResponseWait(game, start); }

    public static void endDecision(Game game, Player player, String operation, long start) {
        if (start == 0) return;
        LabTiming.Reading reading = LabTiming.end(game, start);
        long duration = reading == null ? Math.max(0, System.nanoTime() - start) : reading.elapsedNanos;
        LabRecorder recorder = active(game);
        if (recorder == null || player.isHuman()) return;
        synchronized (recorder) {
            try {
                String playerId = player.getId().toString();
                LabGame.Timing timing = recorder.record.telemetry.ai.computeIfAbsent(playerId + ":" + operation, key -> {
                    LabGame.Timing value = new LabGame.Timing(); value.playerId = playerId; value.operation = operation;
                    value.measurementVersion = 3; return value;
                });
                timing.calls++; timing.totalNanos += duration; timing.maxNanos = Math.max(timing.maxNanos, duration);
                if (reading != null) {
                    timing.measuredCalls++;
                    timing.totalWallMillis += Math.max(0, reading.wallMillis);
                    timing.maxWallMillis = Math.max(timing.maxWallMillis, reading.wallMillis);
                    timing.totalResponseWaitNanos += reading.waitNanos;
                    timing.searchCalls+=reading.searchCalls;timing.searchCpuMeasured+=reading.searchCpuMeasured;
                    timing.searchCpuNanos+=reading.searchCpuNanos;timing.searchWaitNanos+=reading.searchWaitNanos;
                    timing.searchQueueNanos+=reading.searchQueueNanos;timing.searchTimeouts+=reading.searchTimeouts;
                    timing.searchPartial+=reading.searchPartial;timing.searchFailures+=reading.searchFailures;
                    if (reading.cpuNanos != null) {
                        timing.cpuMeasuredCalls++; timing.totalCpuNanos += reading.cpuNanos;
                        timing.maxCpuNanos = Math.max(timing.maxCpuNanos, reading.cpuNanos);
                        timing.totalNonCpuNanos += Math.max(0, duration - reading.cpuNanos);
                    }
                    if (reading.clockDiscontinuity) timing.clockDiscontinuityCalls++;
                    if (reading.longNonCpu) timing.longNonCpuCalls++;
                }
                if (duration >= 100000000L) timing.slowCalls++;
                if (duration >= 100000000L || (reading != null && (reading.clockDiscontinuity || reading.searchTimeouts > 0 || reading.searchFailures > 0))) {
                    LabGame.Event event = recorder.newEvent(game, "AI_" + operation, player.getId());
                    event.durationNanos = duration;
                    if (reading != null) {
                        event.serverHeapBeforeBytes=reading.serverHeapBeforeBytes;
                        event.serverHeapAfterBytes=reading.serverHeapAfterBytes;
                        event.serverHeapMaxBytes=reading.serverHeapMaxBytes;
                        event.serverGcCollectionMillis=reading.serverGcCollectionMillis;
                        event.cpuNanos = reading.cpuNanos; event.wallMillis = reading.wallMillis;
                        event.responseWaitNanos = reading.waitNanos;
                        event.clockDiscontinuity = reading.clockDiscontinuity; event.longNonCpu = reading.longNonCpu;
                        event.searchCalls=reading.searchCalls;event.searchCpuMeasured=reading.searchCpuMeasured;
                        event.searchCpuNanos=reading.searchCpuNanos;event.searchWaitNanos=reading.searchWaitNanos;
                        event.searchQueueNanos=reading.searchQueueNanos;event.searchTimeouts=reading.searchTimeouts;
                        event.searchPartial=reading.searchPartial;event.searchFailures=reading.searchFailures;
                    }
                    recorder.append(event);
                }
            } catch (Exception e) { recorder.traceFailure(e); }
        }
    }

    public static void beforeLeave(Game game, Player player) {
        LabRecorder recorder = active(game);
        if (recorder == null) return;
        synchronized (recorder) {
            if (!recorder.departing.add(player.getId())) return;
            try {
                String[] ruleReason = recorder.pendingExits.remove(player.getId());
                String[] reason = recorder.requestedExits.remove(player.getId());
                if (reason == null) reason = ruleReason;
                if (reason == null) reason = exitDetails(game, player);
                LabGame.Event exit = recorder.newEvent(game, "PLAYER_EXIT", player.getId());
                exit.exitReason = reason[0]; exit.exitSource = reason[1];
                exit.life = player.getLife(); exit.poison = player.getCountersCount(CounterType.POISON);
                recorder.append(exit); recorder.flushTrace();
                if (recorder.record.samples.size() >= 1000) { recorder.exclude("Limite di 1000 campioni raggiunto"); return; }
                recorder.capture(game, "PRE_ELIMINATION");
                LabGame.Sample sample = recorder.record.samples.get(recorder.record.samples.size() - 1);
                sample.departingPlayer = player.getId().toString();
                sample.exitReason = reason[0]; sample.exitSource = reason[1];
                recorder.store.appendSample(recorder.record.id, sample);
                recorder.flushTrace();
            } catch (Exception e) {
                recorder.exclude("Errore di registrazione: dati parziali");
                LOG.error("Laboratorio: stato prima dell'uscita non salvato", e);
            }
        }
    }

    public static LabRecorder startConfigured(Game game) {
        if (!Boolean.getBoolean("xmage.lab.record") || game.isSimulation()) return null;
        try { return start(game, new LabStore(LabStore.defaultRoot())); }
        catch (Exception e) { LOG.error("Laboratorio: registrazione non avviata per " + game.getId(), e); return null; }
    }

    public static LabRecorder start(Game game, LabStore store) throws IOException {
        if (game.isSimulation()) throw new IllegalArgumentException("Le simulazioni interne non sono partite del laboratorio.");
        LabRecorder recorder = new LabRecorder(game, store);
        LabGame data = recorder.record;
        data.id = game.getId().toString();
        data.build = BUILD;
        data.match = game.getOptions().laboratoryMatch;
        data.telemetry = new LabGame.Telemetry();
        data.startedAt = System.currentTimeMillis();
        data.mode = game.getGameType().getName();
        data.range = game.getRangeOfInfluence().name();
        data.attack = game.getAttackOption().name();
        data.startingLife = game.getStartingLife();
        data.startingHandSize = game.getStartingHandSize();
        data.mulligan = game.getMulligan().getClass().getSimpleName();
        data.freeMulligans = game.getMulligan().getFreeMulligans();
        if (game.getOptions().testMode) recorder.exclude("Modalità di test del motore");
        if (game.getOptions().skipInitShuffling) recorder.exclude("Mescolamento iniziale disattivato");
        if (game.getOptions().planeChase || !game.getOptions().perPlayerEmblemCards.isEmpty()
                || !game.getOptions().globalEmblemCards.isEmpty()) recorder.exclude("Regole aggiuntive: confronto non supportato");
        int seat = 0;
        for (Player player : game.getPlayers().values()) {
            LabGame.Participant participant = new LabGame.Participant();
            participant.id = player.getId().toString();
            participant.name = player.getName();
            participant.human = player.isHuman();
            participant.seat = ++seat;
            participant.pilot = player instanceof LabPlayerProfile ? ((LabPlayerProfile) player).getLaboratoryProfile()
                    : player.getClass().getName();
            if (participant.human) participant.pilot += " / " + player.getName();
            if (player.getMatchPlayer() != null && player.getMatchPlayer().getDeck() != null
                    && !player.getMatchPlayer().getDeck().getCards().isEmpty()) {
                LabDeck deck = store.saveDeck(LabDeck.create(player.getMatchPlayer().getDeck().prepareCardsOnlyDeck(), "Partita " + data.id));
                participant.revisionId = deck.id;
                participant.compositionId = deck.compositionId;
                participant.deckName = deck.deck.getName();
            } else recorder.exclude("Lista non disponibile per " + player.getName());
            data.players.add(participant);
        }
        store.saveGame(data);
        try { recorder.writer = store.eventWriter(data.id); }
        catch (IOException e) { recorder.warning("Traccia eventi non disponibile: " + e.getClass().getSimpleName()); }
        ACTIVE.put(game.getId(), recorder);
        game.addTableEventListener(event -> {
            if (event.getGame() != game || game.isSimulation()) return;
            if (event.getEventType() == TableEvent.EventType.UPDATE) recorder.observeSafely(game);
            else if (event.getEventType() == TableEvent.EventType.ERROR) recorder.exclude("Errore segnalato dal motore");
        });
        return recorder;
    }

    /** Unknown-boundary fallback retained for callers without a recorded bookmark. */
    public static void stateRestored(Game game) { stateRestored(game, null, null); }

    public static void stateRestored(Game game, Checkpoint checkpoint, String context) {
        LabRecorder r = active(game);
        if (r != null) synchronized (r) { r.restore(game, checkpoint, "undo".equals(context) ? "PLAYER_UNDO" : "ACTION_RESTORE", context); }
    }

    private void restore(Game game, Checkpoint checkpoint, String kind, String context) {
        record.telemetry.stateRestored = true;
        if ("PLAYER_UNDO".equals(kind)) exclude("Partita con undo del giocatore");
        if (context != null && context.startsWith("Game error:")) exclude("Errore segnalato dal motore");
        LabGame.Event marker = newEvent(game, "STATE_RESTORED", game.getActivePlayerId());
        marker.restoreKind = kind;
        marker.restoreContext = context == null ? null : context.substring(0, Math.min(context.length(), 500));
        if (checkpoint != null && checkpoint.event <= record.telemetry.events && checkpoint.sample <= record.samples.size()) {
            marker.restoredAfterEvent = checkpoint.event;
            marker.restoredAfterSample = checkpoint.sample;
            marker.samplesBeforeRestore = record.samples.size();
            invalidSamples.set(checkpoint.sample, record.samples.size());
            seen.clear(); departing.clear(); pendingExits.clear();
            for (int i = 0; i < record.samples.size(); i++) if (!invalidSamples.get(i)) {
                LabGame.Sample sample = record.samples.get(i);
                if ("PRE_ELIMINATION".equals(sample.phase) && sample.departingPlayer != null) departing.add(UUID.fromString(sample.departingPlayer));
                else seen.add(sample.globalTurn + ":" + sample.activePlayer + ":" + sample.phase);
            }
            lastSampleTurn = game.getTurnNum();
            playing = checkpoint.playing;
            marker.stage = playing ? "PLAY" : "MULLIGAN";
        } else {
            exclude("Ripristino senza punto di registrazione disponibile");
            warning("Annullamento senza confini registrati: conteggi delle carte non utilizzabili.");
        }
        append(marker);
        flushTrace();
    }

    private synchronized void exclude(String reason) {
        if (!record.exclusions.contains(reason)) record.exclusions.add(reason);
    }

    private void observeSafely(Game game) {
        try { observe(game); }
        catch (Exception e) {
            exclude("Errore di registrazione: dati parziali");
            LOG.error("Laboratorio: campione non salvato per " + record.id, e);
        }
    }

    public synchronized void observe(Game game) throws IOException {
        if (finished || game.isSimulation() || game != observedGame) return;
        if (game.executingRollback()) return; // Wait for the successful engine restore, not a network request.
        if (game.getStartingPlayerId() != null) record.startingPlayer = game.getStartingPlayerId().toString();
        if (game.getStep() == null || game.getActivePlayerId() == null) return;
        PhaseStep phase = game.getStep().getType();
        if (phase != PhaseStep.PRECOMBAT_MAIN && phase != PhaseStep.END_TURN) return;
        if (game.getTurnNum() < lastSampleTurn) exclude("Sequenza dei turni riavviata o non verificabile");
        lastSampleTurn = game.getTurnNum();
        String key = game.getTurnNum() + ":" + game.getActivePlayerId() + ":" + phase.name();
        if (!seen.add(key)) return;
        if (record.samples.size() >= 1000) { exclude("Limite di 1000 campioni raggiunto"); return; }
        capture(game, phase.name());
        store.appendSample(record.id, record.samples.get(record.samples.size() - 1));
        flushTrace();
    }

    private void capture(Game game, String phase) {
        LabGame.Sample sample = new LabGame.Sample();
        sample.sequence = record.samples.size() + 1L;
        sample.globalTurn = game.getTurnNum();
        sample.activePlayer = String.valueOf(game.getActivePlayerId());
        sample.phase = phase;
        Map<UUID, int[]> battlefield = new HashMap<>();
        for (Permanent permanent : game.getBattlefield().getAllPermanents()) {
            if (!permanent.isPhasedIn()) continue;
            int[] counts = battlefield.computeIfAbsent(permanent.getControllerId(), id -> new int[2]);
            if (permanent.isLand(game)) counts[0]++;
            if (permanent.isCreature(game)) counts[1]++;
        }
        for (Player player : game.getPlayers().values()) {
            LabGame.Position position = new LabGame.Position();
            position.playerId = player.getId().toString();
            position.inGame = !player.hasLeft();
            position.ownTurns = player.getTurns();
            position.life = player.getLife();
            position.poison = player.getCountersCount(CounterType.POISON);
            position.handSize = player.getHand().size();
            position.librarySize = player.getLibrary().size();
            position.graveyardSize = player.getGraveyard().size();
            position.landsPlayed = player.getLandsPlayed();
            int[] counts = battlefield.getOrDefault(player.getId(), new int[2]);
            position.lands = counts[0]; position.creatures = counts[1];
            int read = 0;
            for (UUID cardId : player.getHand()) {
                if (++read > 250) { exclude("Mano con oltre 250 carte: elenco parziale"); break; }
                Card card = game.getCard(cardId);
                if (card != null) position.hand.merge(card.getName(), 1, Integer::sum);
            }
            sample.players.add(position);
        }
        record.samples.add(sample);
    }

    /** Called after game.start returned, when winner/loser resolution is complete, before cleanup. */
    public synchronized void finish(Game game, boolean returnedNormally) {
        if (finished || game != observedGame) return;
        finished = true;
        ACTIVE.remove(game.getId(), this);
        try {
            record.endedAt = System.currentTimeMillis();
            if (game.getStartingPlayerId() != null) record.startingPlayer = game.getStartingPlayerId().toString();
            boolean allResolved = true;
            for (LabGame.Participant participant : record.players) {
                Player player = game.getPlayer(UUID.fromString(participant.id));
                if (player == null) { allResolved = false; continue; }
                participant.result = player.hasWon() ? "WIN" : player.hasDrew() ? "DRAW" : player.hasLost() ? "LOSS" : "UNKNOWN";
                allResolved &= !"UNKNOWN".equals(participant.result);
                if (player.hasTimerTimeout() || player.hasIdleTimeout()) exclude("Partita con timeout");
            }
            record.status = returnedNormally && game.getState().isGameOver() && allResolved ? "COMPLETED" : "INTERRUPTED";
            record.drawn = game.isADraw();
            if (record.drawn && record.players.stream().noneMatch(p -> "DRAW".equals(p.result))) {
                exclude("Pareggio della partita con esiti individuali non risolti");
            }
            if (!returnedNormally) exclude("Partita interrotta o errore di esecuzione");
            if (record.startingPlayer == null) exclude("Posizione iniziale non disponibile");
            if (record.samples.size() < 1000) capture(game, "FINAL");
            closeTrace();
            record.telemetry.complete = "COMPLETED".equals(record.status) && record.telemetry.warnings.isEmpty();
            store.saveGame(record);
        } catch (Exception e) { LOG.error("Laboratorio: risultato non salvato per " + record.id, e); }
        finally { closeTrace(); }
    }
}

package mage.lab;

import java.util.*;

/** Plain data only: never retains engine objects or mutable game state. */
public final class LabGame {
    public int schema = 5;
    public String id, build, mode, range, attack, mulligan, status = "IN_PROGRESS", startingPlayer;
    public int startingLife, startingHandSize, freeMulligans;
    public boolean drawn;
    public long startedAt, endedAt;
    public final List<String> exclusions = new ArrayList<>();
    public final List<Participant> players = new ArrayList<>();
    public final List<Sample> samples = new ArrayList<>();

    public MatchInfo match;
    // Null on schema 1: unavailable must never be reported as a measured zero.
    public Telemetry telemetry;

    public static final class MatchInfo {
        public String id;
        public int round, winsNeeded;
        public boolean resultRecorded, finished;
        public final Map<String, Integer> scoreBefore = new LinkedHashMap<>();
        public final Map<String, Integer> scoreAfter = new LinkedHashMap<>();
        public final Map<String, String> names = new LinkedHashMap<>();
        public final List<String> winners = new ArrayList<>();
    }

    public static final class Telemetry {
        public long events;
        public boolean complete, stateRestored;
        public final List<String> warnings = new ArrayList<>();
        public final Map<String, Timing> ai = new LinkedHashMap<>();
        public final Map<String, Map<String, Long>> counts = new LinkedHashMap<>();
    }

    public static final class Timing {
        public String playerId, operation;
        public long calls, totalNanos, maxNanos, slowCalls;
        // Absent/zero on old data: never treat missing CPU measurements as zero CPU.
        public int measurementVersion;
        public long measuredCalls, cpuMeasuredCalls, totalCpuNanos, maxCpuNanos;
        public long totalWallMillis, maxWallMillis, totalNonCpuNanos, totalResponseWaitNanos;
        public long clockDiscontinuityCalls, longNonCpuCalls;
        public long searchCalls, searchCpuMeasured, searchCpuNanos, searchWaitNanos, searchQueueNanos;
        public long searchTimeouts, searchPartial, searchFailures;
    }

    /** A selected engine event, not a complete replay or an evaluation of a card. */
    public static final class Event {
        public long sequence, elapsedMillis, durationNanos;
        public int globalTurn, amount;
        public String kind, stage, phase, activePlayer, playerId, targetId, targetName, sourceId, sourceName, from, to;
        // Schema 3: exact raw-log boundaries of a successful restore (exclusive lower bounds).
        public Long restoredAfterEvent;
        public Integer restoredAfterSample, samplesBeforeRestore;
        public String restoreKind, restoreContext;
        public Boolean combat;
        public Long cpuNanos, wallMillis, responseWaitNanos;
        public Long serverHeapBeforeBytes, serverHeapAfterBytes, serverHeapMaxBytes, serverGcCollectionMillis;
        public Boolean clockDiscontinuity, longNonCpu;
        public Long searchCalls, searchCpuMeasured, searchCpuNanos, searchWaitNanos, searchQueueNanos;
        public Long searchTimeouts, searchPartial, searchFailures;
        public String exitReason, exitSource;
        // Additive fields: absent in older schema-5 reports.
        public String drawReason, drawResponseOrigin;
        public Boolean drawAccepted;
        public Integer stackSize;
        public List<String> stackSources;
        public Integer life, poison;
        public List<CombatPermanent> battlefield;
        public List<CombatAssignment> combatAssignments;
        public Map<String, String> spellTargets, targetControllers;
        public Map<String, List<Integer>> combatPlayers;
        public Boolean battlefieldComplete;
        public Map<String, Integer> hand;
        public Map<String, String> handCards;
    }

    public static final class CombatPermanent {
        public String id, name, controller;
        public int power, toughness, damage;
        public boolean tapped, summoningSickness;
        public Map<String,Integer> counters;
        public List<String> abilities;
    }
    public static final class CombatAssignment {
        public String attacker, defender;
        public List<String> blockers;
    }

    public static final class Participant {
        public String id, name, pilot, revisionId, compositionId, deckName, result = "UNKNOWN";
        public int seat;
        public boolean human;
    }

    public static final class Sample {
        public long sequence;
        public int globalTurn;
        public String activePlayer, phase, departingPlayer, exitReason, exitSource;
        public final List<Position> players = new ArrayList<>();
    }

    public static final class Position {
        public String playerId;
        public Boolean inGame;
        public Integer poison;
        public int ownTurns, life, handSize, librarySize, graveyardSize, lands, creatures, landsPlayed;
        public final SortedMap<String, Integer> hand = new TreeMap<>();
    }

    public boolean eligible() { return "COMPLETED".equals(status) && exclusions.isEmpty(); }

    public String context(Participant subject) {
        List<String> opponents = new ArrayList<>();
        for (Participant player : players) {
            if (!player.id.equals(subject.id)) opponents.add("posto " + player.seat + ":" + player.compositionId + ":" + player.pilot);
        }
        Collections.sort(opponents);
        // Seat and starting position are intentionally retained, not silently pooled.
        int starterSeat = -1;
        for (Participant player : players) if (player.id.equals(startingPlayer)) starterSeat = player.seat;
        return String.join(" | ", String.valueOf(build), String.valueOf(mode), players.size() + " giocatori",
                startingLife + " vite", "mano iniziale " + startingHandSize, String.valueOf(range), String.valueOf(attack), String.valueOf(mulligan),
                "mulligan gratuiti " + freeMulligans, subject.pilot, "posto " + subject.seat,
                "inizia posto " + starterSeat, opponents.toString());
    }
}

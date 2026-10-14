package mage.lab;

import mage.game.Game;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;

/** Original decision thread only; never samples or changes the AI search. */
final class LabTiming {
    interface Clock {
        long nanoTime();
        long wallMillis();
        long cpuNanos();
    }
    static final class SystemClock implements Clock {
        private final ThreadMXBean bean = bean();
        private static ThreadMXBean bean() {
            try { return ManagementFactory.getThreadMXBean(); }
            catch (SecurityException e) { return null; }
        }
        public long nanoTime() { return System.nanoTime(); }
        public long wallMillis() { return System.currentTimeMillis(); }
        public long cpuNanos() {
            try {
                if (bean == null || !bean.isCurrentThreadCpuTimeSupported()) return -1;
                if (!bean.isThreadCpuTimeEnabled()) bean.setThreadCpuTimeEnabled(true);
                return bean.getCurrentThreadCpuTime();
            } catch (UnsupportedOperationException | SecurityException e) { return -1; }
        }
    }
    static final class Reading {
        long elapsedNanos, wallMillis, waitNanos;
        Long cpuNanos;
        Long serverHeapBeforeBytes, serverHeapAfterBytes, serverHeapMaxBytes, serverGcCollectionMillis;
        boolean clockDiscontinuity, longNonCpu;
        long searchCalls, searchCpuMeasured, searchCpuNanos, searchWaitNanos, searchQueueNanos, searchTimeouts, searchPartial, searchFailures;
    }
    static final class Decision {
        final Game game;
        final Clock clock;
        final long nano, wall, cpu;
        final Decision previous;
        long waitNanos;
        final java.util.List<LabSearchTiming> searches = new java.util.ArrayList<>();
        Decision(Game game, Clock clock, Decision previous) {
            this.game = game; this.clock = clock; this.previous = previous;
            nano = clock.nanoTime(); wall = clock.wallMillis(); cpu = clock.cpuNanos();
        }
        Reading finish() {
            Reading r=reading(nano, wall, cpu, clock.nanoTime(), clock.wallMillis(), clock.cpuNanos(), waitNanos);
            for(LabSearchTiming search:searches) synchronized(search) {
                r.searchCalls++;
                if (r.serverHeapBeforeBytes == null) r.serverHeapBeforeBytes=search.heapBeforeBytes;
                r.serverHeapAfterBytes=search.heapAfterBytes;r.serverHeapMaxBytes=search.heapMaxBytes;
                // A decision normally has one search; collection time is process-wide.
                if (search.gcCollectionMillis != null) {
                    if (r.serverGcCollectionMillis == null) r.serverGcCollectionMillis=0L;
                    r.serverGcCollectionMillis+=search.gcCollectionMillis;
                }
                if(search.cpuNanos!=null){r.searchCpuMeasured++;r.searchCpuNanos+=search.cpuNanos;}
                r.searchWaitNanos+=search.waitNanos;r.searchQueueNanos+=search.queueNanos;
                if("TIMEOUT".equals(search.outcome))r.searchTimeouts++;
                if(search.partial)r.searchPartial++;
                if(!"COMPLETED".equals(search.outcome)&&!"TIMEOUT".equals(search.outcome))r.searchFailures++;
            }
            if(r.searchCalls>0)r.longNonCpu=r.searchCpuMeasured==r.searchCalls && r.cpuNanos!=null
                    && r.elapsedNanos>=60000000000L
                    && r.elapsedNanos-r.cpuNanos-r.searchCpuNanos-r.waitNanos>=r.elapsedNanos*0.8;
            return r;
        }
    }
    private static final ThreadLocal<Decision> CURRENT = new ThreadLocal<>();
    private static final Clock CLOCK = new SystemClock();
    static long begin(Game game) {
        Decision d = new Decision(game, CLOCK, CURRENT.get()); CURRENT.set(d); return d.nano;
    }
    static LabSearchTiming searchSubmitted() {
        Decision d=CURRENT.get();if(d==null)return null;
        LabSearchTiming search=new LabSearchTiming();d.searches.add(search);return search;
    }
    static Reading end(Game game, long token) {
        Decision d = CURRENT.get();
        if (d == null || d.game != game || d.nano != token) return null;
        try { return d.finish(); }
        finally { if (d.previous == null) CURRENT.remove(); else CURRENT.set(d.previous); }
    }
    static long beginResponseWait(Game game) {
        Decision d = CURRENT.get();
        return d != null && d.game == game ? d.clock.nanoTime() : 0;
    }
    static void endResponseWait(Game game, long start) {
        Decision d = CURRENT.get();
        if (start != 0 && d != null && d.game == game) d.waitNanos += Math.max(0, d.clock.nanoTime() - start);
    }
    static Reading reading(long nano0, long wall0, long cpu0, long nano1, long wall1, long cpu1, long wait) {
        Reading r = new Reading();
        r.elapsedNanos = Math.max(0, nano1 - nano0);
        r.wallMillis = wall1 - wall0;
        r.waitNanos = Math.min(r.elapsedNanos, Math.max(0, wait));
        if (cpu0 >= 0 && cpu1 >= cpu0) r.cpuNanos = cpu1 - cpu0;
        // This is evidence of a clock/suspend discontinuity, not proof of sleep.
        r.clockDiscontinuity = r.wallMillis < 0 || Math.abs(r.wallMillis - r.elapsedNanos / 1000000L) > 2000;
        r.longNonCpu = r.elapsedNanos >= 60000000000L && r.cpuNanos != null
                && r.elapsedNanos - Math.min(r.elapsedNanos, r.cpuNanos) - r.waitNanos >= r.elapsedNanos * 0.8;
        return r;
    }
    private LabTiming() { }
}

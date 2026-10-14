package mage.lab;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.lang.management.GarbageCollectorMXBean;
import java.util.List;

/** Cross-thread measurement captured at return, including a partial CPU sample on timeout. */
public final class LabSearchTiming {
    private static final ThreadMXBean CPU=ManagementFactory.getThreadMXBean();
    private static final List<GarbageCollectorMXBean> GC = gcBeans();
    private static List<GarbageCollectorMXBean> gcBeans() {
        try { return ManagementFactory.getGarbageCollectorMXBeans(); }
        catch (SecurityException e) { return java.util.Collections.emptyList(); }
    }
    final long submitted=System.nanoTime();
    // Server JVM measurements, not client/Windows RAM and not allocated bytes.
    final long heapBeforeBytes = usedHeap();
    final long gcBeforeMillis = gcMillis();
    long heapAfterBytes, heapMaxBytes;
    Long gcCollectionMillis;
    private static long usedHeap() {
        Runtime r = Runtime.getRuntime();
        return Math.max(0, r.totalMemory() - r.freeMemory());
    }
    private static long gcMillis() {
        try {
            if (GC.isEmpty()) return -1;
            long total = 0;
            for (GarbageCollectorMXBean gc : GC) {
                long value = gc.getCollectionTime();
                if (value < 0) return -1;
                total += value;
            }
            return total;
        } catch (SecurityException | UnsupportedOperationException e) { return -1; }
    }
    long started, finished, returned, worker, cpuStart=-1;
    long queueNanos, waitNanos, workerNanos;
    Long cpuNanos;
    String outcome;
    boolean partial;
    private static long cpu(long id) {
        try {
            if(!CPU.isThreadCpuTimeSupported())return -1;
            if(!CPU.isThreadCpuTimeEnabled())CPU.setThreadCpuTimeEnabled(true);
            return CPU.getThreadCpuTime(id);
        } catch(SecurityException | UnsupportedOperationException e){return -1;}
    }
    public synchronized void started() {
        worker=Thread.currentThread().getId();started=System.nanoTime();cpuStart=cpu(worker);
    }
    public synchronized void finished() {
        if(returned!=0)return; // Do not retroactively change a timed-out decision's saved values.
        finished=System.nanoTime();sampleCpu();
    }
    private void sampleCpu(){long end=cpu(worker);cpuNanos=cpuStart>=0&&end>=cpuStart?end-cpuStart:null;}
    public synchronized void returned(String result) {
        if(returned!=0)return;
        heapAfterBytes=usedHeap();heapMaxBytes=Runtime.getRuntime().maxMemory();
        long gcAfterMillis=gcMillis();
        gcCollectionMillis=gcBeforeMillis>=0 && gcAfterMillis>=gcBeforeMillis ? gcAfterMillis-gcBeforeMillis : null;
        returned=System.nanoTime();outcome=result;waitNanos=Math.max(0,returned-submitted);
        queueNanos=Math.max(0,(started==0?returned:started)-submitted);
        partial=started!=0&&finished==0;
        if(started==0){cpuNanos=0L;workerNanos=0;}
        else {if(partial)sampleCpu();workerNanos=Math.max(0,(finished==0?returned:finished)-started);}
    }
}

package mage.lab;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LabTimingTest {
    private static final long S = 1000000000L;
    @Test void elapsedCpuAndResponseWaitRemainSeparate() {
        LabTiming.Reading r = LabTiming.reading(1, 1000, 10, 1 + 10*S, 11000, 10+2*S, 6*S);
        assertEquals(10*S, r.elapsedNanos); assertEquals(Long.valueOf(2*S), r.cpuNanos);
        assertEquals(6*S, r.waitNanos); assertFalse(r.clockDiscontinuity); assertFalse(r.longNonCpu);
    }
    @Test void unavailableCpuIsUnknownNotZero() {
        LabTiming.Reading r = LabTiming.reading(0, 0, -1, 3600*S, 3600000, -1, 0);
        assertNull(r.cpuNanos); assertFalse(r.longNonCpu);
    }
    @Test void disabledCounterMidDecisionIsUnknown() {
        assertNull(LabTiming.reading(0, 0, 900, S, 1000, 50, 0).cpuNanos);
    }
    @Test void clockJumpDoesNotBecomeCpuTime() {
        LabTiming.Reading r = LabTiming.reading(0, 0, 0, S, 3600000, S/2, 0);
        assertTrue(r.clockDiscontinuity); assertEquals(S, r.elapsedNanos); assertEquals(3600000, r.wallMillis);
    }
    @Test void backwardsWallClockIsVisibleAndElapsedStillValid() {
        LabTiming.Reading r = LabTiming.reading(0, 10000, 0, S, 1000, S/2, 0);
        assertEquals(-9000, r.wallMillis); assertTrue(r.clockDiscontinuity); assertEquals(S, r.elapsedNanos);
    }
    @Test void longCpuBoundCallIsNotMarkedAsPause() {
        assertFalse(LabTiming.reading(0, 0, 0, 120*S, 120000, 119*S, 0).longNonCpu);
    }
    @Test void longLowCpuCallIsAmbiguousUnlessExplainedByRecordedWait() {
        assertTrue(LabTiming.reading(0, 0, 0, 120*S, 120000, S, 0).longNonCpu);
        assertFalse(LabTiming.reading(0, 0, 0, 120*S, 120000, S, 118*S).longNonCpu);
    }
    @Test void waitIsClampedToDecisionAndNestedScopesRestoreOuter() {
        assertEquals(S, LabTiming.reading(0,0,0,S,1000,0,2*S).waitNanos);
        assertEquals(0, LabTiming.beginResponseWait(null));
        long outer = LabTiming.begin(null), inner = LabTiming.begin(null);
        assertNull(LabTiming.end(null, inner+1));
        assertNotNull(LabTiming.end(null, inner)); assertNotEquals(0, LabTiming.beginResponseWait(null));
        assertNotNull(LabTiming.end(null, outer)); assertEquals(0, LabTiming.beginResponseWait(null));
    }
    @Test void legacyTimingDoesNotInventCpuOrSuspension() {
        LabGame.Timing t = new LabGame.Timing(); t.calls=1; t.totalNanos=t.maxNanos=14000*S;
        String text=LabDiagnostics.timing(t);
        assertTrue(text.contains("CPU e attese non disponibili")); assertTrue(text.contains("non si distingue calcolo"));
        assertEquals("motivo non registrato",LabDiagnostics.exit(null,null));
        assertEquals("veleno non registrato",LabDiagnostics.poison(null));
    }
    @Test void cpuCoverageAndAmbiguousCountsAreReported() {
        LabGame.Timing t = new LabGame.Timing(); t.measurementVersion=2; t.calls=3; t.measuredCalls=3; t.cpuMeasuredCalls=2;
        t.totalCpuNanos=S; t.clockDiscontinuityCalls=1; t.longNonCpuCalls=1;
        String text=LabDiagnostics.timing(t);
        assertTrue(text.contains("copertura 2/3")); assertTrue(text.contains("Discontinuità degli orologi: 1"));
        assertTrue(LabDiagnostics.TIMING_NOTE.contains("non prova da solo"));
    }
    @Test void searchMemoryMeasurementsAreFrozenAtReturnAndAbsentOnLegacy() {
        LabSearchTiming search = new LabSearchTiming();
        search.started(); search.returned("TIMEOUT");
        long after = search.heapAfterBytes;
        Long gc = search.gcCollectionMillis;
        assertTrue(search.heapBeforeBytes >= 0);
        assertTrue(after >= 0);
        assertTrue(search.heapMaxBytes > 0);
        assertTrue(gc == null || gc >= 0);
        search.finished(); search.returned("COMPLETED");
        assertEquals(after, search.heapAfterBytes); assertEquals(gc, search.gcCollectionMillis);
        LabGame.Event legacy = new com.google.gson.Gson().fromJson("{\"kind\":\"AI_PRIORITY\"}", LabGame.Event.class);
        assertNull(legacy.serverHeapAfterBytes); assertNull(legacy.serverGcCollectionMillis);
    }

}

package net.i2p.router;

import static org.junit.Assert.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

/**
 * Tests the pure cross-parameter decision helpers {@link Tuner#findInversions}
 * and {@link Tuner#rttFloorMs}, the parts of the invariant check that need no
 * router.
 *
 * <p>Each Tuner parameter hill-climbs against its own stat inside its own
 * {@code [min, max]} range, so nothing in a single parameter can see that
 * {@code minResendDelay} climbed to 5000 while {@code maxResendDelay} sank to
 * 1000. Streaming then clamps every RTO into that inverted pair, which parked
 * the retransmit timer below one RTT and retransmitted the whole pipe. The pair
 * table and the RTT floor are what make that state reachable-and-repairable.
 *
 * @since 0.9.72+
 */
public class TunerInvariantTest {

    private static Map<String, Integer> values(Object... kv) {
        Map<String, Integer> m = new HashMap<String, Integer>();
        for (int i = 0; i < kv.length; i += 2)
            m.put((String) kv[i], (Integer) kv[i + 1]);
        return m;
    }

    private static final String[][] PAIR = {{"lo", "hi"}};

    // ----- findInversions -----

    @Test
    public void orderedPairIsNotAViolation() {
        assertTrue(Tuner.findInversions(PAIR, values("lo", 300, "hi", 20000)).isEmpty());
    }

    @Test
    public void equalBoundsAreNotAViolation() {
        assertTrue(Tuner.findInversions(PAIR, values("lo", 1000, "hi", 1000)).isEmpty());
    }

    @Test
    public void floorAboveCeilingIsAViolation() {
        List<String[]> bad = Tuner.findInversions(PAIR, values("lo", 5000, "hi", 1000));
        assertEquals(1, bad.size());
        assertSame(PAIR[0], bad.get(0));
    }

    @Test
    public void missingMemberIsSkippedNotFlagged() {
        // a param whose subsystem is not up yet reads as absent, not as broken
        assertTrue(Tuner.findInversions(PAIR, values("lo", 5000)).isEmpty());
        assertTrue(Tuner.findInversions(PAIR, values("hi", 1000)).isEmpty());
        assertTrue(Tuner.findInversions(PAIR, values()).isEmpty());
    }

    @Test
    public void everyViolationInTheTableIsReported() {
        String[][] table = {{"a", "b"}, {"b", "c"}, {"c", "d"}};
        Map<String, Integer> m = values("a", 10, "b", 5, "c", 20, "d", 1);
        List<String[]> bad = Tuner.findInversions(table, m);
        assertEquals(2, bad.size());
        assertSame(table[0], bad.get(0));
        assertSame(table[2], bad.get(1));
    }

    /**
     * The shipped table names the pairs that actually broke: the streaming
     * resend-delay pair, and the UDP RTO ladder.
     */
    @Test
    public void shippedTableCoversTheStreamingResendPair() {
        Map<String, Integer> m = values("i2p.streaming.minResendDelay", 5000,
                                        "i2p.streaming.maxResendDelay", 1000);
        assertEquals(1, Tuner.findInversions(Tuner.ORDER_INVARIANTS, m).size());
    }

    @Test
    public void shippedTableCoversTheUdpRtoLadder() {
        Map<String, Integer> m = values("udp.peer.minRTO", 2000,
                                        "udp.peer.initRTO", 250,
                                        "udp.peer.maxRTO", 3000);
        List<String[]> bad = Tuner.findInversions(Tuner.ORDER_INVARIANTS, m);
        assertEquals(1, bad.size());
        assertEquals("udp.peer.minRTO", bad.get(0)[0]);
        assertEquals("udp.peer.initRTO", bad.get(0)[1]);
    }

    @Test
    public void shippedTableIsWellFormed() {
        for (String[] pair : Tuner.ORDER_INVARIANTS) {
            assertEquals(2, pair.length);
            assertNotNull(pair[0]);
            assertNotNull(pair[1]);
            assertFalse("a pair cannot order a param against itself",
                        pair[0].equals(pair[1]));
        }
    }

    /**
     * A healthy router's live values must not trip the table, or the check would
     * reset working parameters every cycle.
     */
    @Test
    public void factoryDefaultsSatisfyTheTable() {
        Map<String, Integer> m = values("i2p.streaming.minResendDelay", 300,
                                        "i2p.streaming.maxResendDelay", 20000,
                                        "i2p.streaming.maxRTO", 8000,
                                        "i2p.streaming.initialResendDelay", 1000,
                                        "udp.peer.minRTO", 500,
                                        "udp.peer.initRTO", 500,
                                        "udp.peer.maxRTO", 30000);
        assertTrue(Tuner.findInversions(Tuner.ORDER_INVARIANTS, m).isEmpty());
    }

    // ----- rttFloorMs -----

    @Test
    public void noStatMeansNoFloor() {
        assertEquals(0, Tuner.rttFloorMs(Double.NaN, 1.5));
    }

    @Test
    public void zeroOrNegativeRttMeansNoFloor() {
        assertEquals(0, Tuner.rttFloorMs(0, 1.5));
        assertEquals(0, Tuner.rttFloorMs(-868, 1.5));
    }

    @Test
    public void unusableMultiplierMeansNoFloor() {
        assertEquals(0, Tuner.rttFloorMs(868, 0));
        assertEquals(0, Tuner.rttFloorMs(868, -1));
    }

    @Test
    public void floorScalesTheRttByTheMultiplier() {
        assertEquals(1302, Tuner.rttFloorMs(868, 1.5));
        assertEquals(868, Tuner.rttFloorMs(868, 1.0));
        assertEquals(1736, Tuner.rttFloorMs(868, 2.0));
    }

    @Test
    public void floorStaysInRangeForAnAbsurdRtt() {
        assertEquals(Integer.MAX_VALUE, Tuner.rttFloorMs(1e12, 1.5));
    }
}

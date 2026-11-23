package net.i2p.router;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * Tests the pure inbound-buffer cap decision
 * {@link Tuner#maxInboundBufferTarget}, extracted from
 * {@code MaxInboundBufferParam.computeTarget}.
 *
 * <p>The parameter was bound to {@code stream.con.lifetimeBytesReceived}, whose
 * event count is simply how many streams closed - 1,230 per minute on this
 * router - and the code read that count as "choking", so the flag was never
 * false and the per-connection cap sat on its 64 MB ceiling: live, Max Inbound
 * Buffer 67,108,864, a delta of +58,720,256 over the 8 MB default, against a
 * 4 GiB heap with 1,024 stream slots. It now reads
 * {@code stream.chokeSizeBegin}, and counts a choke as pressure only when the
 * outbound queue behind it is deep.
 *
 * @since 0.9.72+
 */
public class TunerInboundBufferTargetTest {

    /**
     * The inbound-buffer cases. Every case calls the static decision, so the
     * instance holds no state and needs no router or streaming connection.
     */
    public TunerInboundBufferTargetTest() {
    }

    /** The parameter's floor, 8 MB. */
    private static final int MIN = 8 * 1024 * 1024;

    /** The parameter's ceiling, 64 MB. */
    private static final int MAX = 64 * 1024 * 1024;

    /** The parameter's step, 8 MB. */
    private static final int STEP = 8 * 1024 * 1024;

    /** The factory default the cap eases back toward. */
    private static final int DEFAULT = 8 * 1024 * 1024;

    /** A mid-range cap to re-evaluate. */
    private static final int BASE = 16 * 1024 * 1024;

    /** A loss rate below the lossy threshold. */
    private static final double CLEAN = 0.0d;

    /** A loss rate at or above the lossy threshold. */
    private static final double LOSSY = Tuner.STREAM_LOSSY_RATE;

    /** Heap pressure with room to grow. */
    private static final double HEAP_FREE = 0.2d;

    /** Heap pressure above the tight threshold. */
    private static final double HEAP_TIGHT = 0.9d;

    /**
     * The decision with this parameter's own bounds filled in.
     *
     * @param current the cap being re-evaluated, in bytes
     * @param chokeEvents {@code stream.chokeSizeBegin} events per 60s
     * @param chokeDepth average queue depth at those chokes, in messages
     * @param lossRate streaming retransmission rate
     * @param memPressure heap pressure ratio 0.0-1.0
     * @return the new cap, already clamped to [MIN, MAX]
     */
    private static int cap(int current, double chokeEvents, double chokeDepth,
                           double lossRate, double memPressure) {
        return Tuner.maxInboundBufferTarget(current, MIN, MAX, STEP, DEFAULT,
                                            chokeEvents, chokeDepth, lossRate,
                                            memPressure);
    }

    /**
     * The cap with no choke signal at all and a clean path.
     *
     * @param current the cap being re-evaluated, in bytes
     * @return the new cap for those inputs, already clamped to [MIN, MAX]
     */
    private static int target(int current) {
        return cap(current, 0.0d, 0.0d, CLEAN, HEAP_FREE);
    }

    /**
     * The defect. Streams do begin choking here - 27 times a minute - but the
     * queue behind each choke averages one message, which is a momentary pause,
     * not receive pressure. Under the old code the count alone grew the cap,
     * and the stat it read was the closed-stream count, always non-zero.
     */
    @Test
    public void shallowChokeDoesNotGrowTheCap() {
        // live here: 27 chokes/min at an average queue depth of 1 message
        assertEquals(DEFAULT, cap(BASE, 27.2d, 1.0d, CLEAN, HEAP_FREE));
    }

    /**
     * A choke with a deep queue behind it is real pressure and grows the cap by
     * half its current value, the fast climb the parameter was written for.
     */
    @Test
    public void deepChokeGrowsTheCap() {
        int atThreshold = BASE + BASE / 2;
        assertEquals(atThreshold,
                     cap(BASE, 27.2d, Tuner.CHOKE_PRESSURE_MESSAGES,
                         CLEAN, HEAP_FREE));
        // the live peak-period queue depth at a choke
        assertEquals(atThreshold, cap(BASE, 27.2d, 96.4d, CLEAN, HEAP_FREE));
    }

    /**
     * The boundary sits on the Tuner's own depth constant so the case cannot
     * drift from it.
     */
    @Test
    public void depthBoundaryIsTheThreshold() {
        assertEquals(BASE + BASE / 2,
                     cap(BASE, 1.0d, Tuner.CHOKE_PRESSURE_MESSAGES,
                         CLEAN, HEAP_FREE));
        assertEquals(DEFAULT,
                     cap(BASE, 1.0d, Tuner.CHOKE_PRESSURE_MESSAGES - 1,
                         CLEAN, HEAP_FREE));
    }

    /** Growth stops at the ceiling the streaming setter accepts. */
    @Test
    public void growthStopsAtTheCeiling() {
        assertEquals(MAX, cap(MAX, 100.0d, 96.4d, LOSSY, HEAP_FREE));
    }

    /**
     * A lossy path wants more in-flight capacity while the heap has room, and
     * must not get it while the heap is tight - the per-connection cap times
     * the stream slots is what over-commits the heap.
     */
    @Test
    public void lossGrowsOnlyWhileTheHeapHasRoom() {
        assertEquals(BASE + BASE / 2, cap(BASE, 0.0d, 0.0d, LOSSY, HEAP_FREE));
        assertEquals(DEFAULT, cap(BASE, 0.0d, 0.0d, LOSSY, HEAP_TIGHT));
    }

    /**
     * With the pressure gone the cap comes back down one step per pass toward
     * the factory default, and never below it.
     */
    @Test
    public void easesBackToTheDefaultAndStops() {
        assertEquals(MAX - STEP, target(MAX));
        assertEquals(DEFAULT, target(DEFAULT + STEP / 2));
        assertEquals(DEFAULT, target(DEFAULT));
        assertEquals(MIN, target(MIN));
    }

    /**
     * No stats yet - nothing measured - is not pressure, and does not push the
     * cap up out of the default.
     */
    @Test
    public void unmeasuredStatsAreNotPressure() {
        double nan = Double.NaN;
        assertEquals(DEFAULT, cap(DEFAULT, nan, nan, nan, HEAP_FREE));
    }
}

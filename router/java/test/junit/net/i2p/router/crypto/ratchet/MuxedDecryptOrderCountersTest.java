package net.i2p.router.crypto.ratchet;

import static org.junit.Assert.*;
import static org.mockito.Mockito.mock;

import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;

/**
 * Tests the counters behind the slow-decrypt ordering.
 * <p>
 * preferRatchet() both decides and rolls the sampling window over, and it did
 * both without a shadow of a doubt: read both counters, then set both to zero.
 * Any decrypt recorded between the read and the reset was thrown away, so under
 * concurrent decrypts the mix the ordering is supposed to track quietly went
 * missing. Worse, the PQ ratio log line called preferRatchet() from inside a
 * String.format, so printing a status line consumed the rollover and reported a
 * preference that the next decrypt would not act on.
 * <p>
 * The window now rolls over only if nothing was recorded since the value the
 * decision was taken on, and reporting reads the counters without touching them.
 *
 * @since 0.9.72+
 */
public class MuxedDecryptOrderCountersTest {

    /**
     * A hybrid with mocked delegates - the counters live in the muxer itself.
     *
     * @return a fresh MuxedPQSKM
     */
    private static MuxedPQSKM pqMuxed() {
        return new MuxedPQSKM(mock(RatchetSKM.class), mock(RatchetSKM.class));
    }

    /**
     * Read one of the muxer's counters.
     *
     * @param muxed the instance to read
     * @param name the counter's field name
     * @return its current value
     */
    private static int counter(Object muxed, String name) throws Exception {
        Field field = muxed.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return ((AtomicInteger) field.get(muxed)).get();
    }

    /**
     * A window is only restarted when both paths have said something and the
     * pair has actually filled up. A one-sided flood must not wipe a window that
     * still describes the other path.
     */
    @Test
    public void testRestartNeedsBothSidesAndAFullWindow() {
        int restart = MuxedSKM.RESTART_COUNTERS;
        int floor = restart / 10;
        assertTrue(MuxedSKM.needsRestart(floor + 10, restart - floor - 9));
        assertFalse("one side too quiet to be meaningful", MuxedSKM.needsRestart(floor, restart));
        assertFalse("other side too quiet", MuxedSKM.needsRestart(restart, floor));
        assertFalse("window not full yet", MuxedSKM.needsRestart(floor + 10, floor + 10));
        // Both thresholds are strict: at the floor, or with the sum exactly at
        // the window size, the counters keep running.
        assertFalse(MuxedSKM.needsRestart(floor, restart - floor + 1));
        assertFalse(MuxedSKM.needsRestart(floor + 1, restart - floor - 1));
        assertTrue(MuxedSKM.needsRestart(floor + 1, restart - floor));
    }

    /**
     * The ordering discounts the slower path by its factor, at the boundary.
     */
    @Test
    public void testOrderingBoundaryPerFactor() {
        int elg = MuxedSKM.ELG_SLOW_FACTOR;
        assertTrue(MuxedSKM.preferRatchet(100 / elg, 100));
        assertFalse(MuxedSKM.preferRatchet(100 / elg - 1, 100));

        int pq = MuxedPQSKM.PQ_SLOW_FACTOR;
        assertTrue(MuxedPQSKM.preferRatchet(100 / pq, 100));
        assertFalse(MuxedPQSKM.preferRatchet(100 / pq - 1, 100));

        // Integer division means a small other-side count is discounted away
        // entirely, so the ratchet keeps its default first place.
        assertTrue(MuxedSKM.preferRatchet(0, elg - 1));
    }

    /**
     * Rolling a counter over only takes if nothing was recorded since the value
     * the decision was taken on. This is the lost-count fix: the old set(0)
     * discarded whatever a concurrent decrypt had added in the meantime.
     */
    @Test
    public void testRollOverPreservesALateRecord() {
        AtomicInteger counter = new AtomicInteger(510);
        // The decision was taken on 500; a decrypt landed in between.
        assertFalse(MuxedSKM.rollOver(counter, 500));
        assertEquals("a recorded decrypt was discarded", 510, counter.get());

        assertTrue(MuxedSKM.rollOver(counter, 510));
        assertEquals(0, counter.get());
    }

    /**
     * The same contract on the PQ muxer, whose counters are the ones the ratio
     * log line used to reset.
     */
    @Test
    public void testPQRollOverSameContract() {
        AtomicInteger counter = new AtomicInteger(7);
        assertFalse(MuxedPQSKM.rollOver(counter, 6));
        assertEquals(7, counter.get());
        assertTrue(MuxedPQSKM.rollOver(counter, 7));
        assertEquals(0, counter.get());
    }

    /**
     * Reporting the current preference must not consume the rollover. A log line
     * that reset the counters both lost the window and printed a decision the
     * next decrypt would not make.
     */
    @Test
    public void testReportingLeavesTheWindowAlone() throws Exception {
        MuxedPQSKM m = pqMuxed();
        for (int i = 0; i < 60; i++) {
            m.reportDecryptResult(true, true);
        }
        for (int i = 0; i < 450; i++) {
            m.reportDecryptResult(false, true);
        }
        int ec = counter(m, "_ecCounter");
        int pq = counter(m, "_pqCounter");
        assertEquals(60, ec);
        assertEquals(450, pq);

        boolean expected = MuxedPQSKM.preferRatchet(ec, pq);
        for (int i = 0; i < 5; i++) {
            assertEquals("reporting changed its own answer", expected, m.preferRatchetNow());
        }

        assertEquals("reporting reset the ratchet counter", ec, counter(m, "_ecCounter"));
        assertEquals("reporting reset the PQ counter", pq, counter(m, "_pqCounter"));
    }

    /**
     * The real decision does roll the window over, so the ordering keeps
     * tracking recent traffic instead of a session-total ratio.
     */
    @Test
    public void testDecisionRollsTheWindowOver() throws Exception {
        MuxedPQSKM m = pqMuxed();
        for (int i = 0; i < 60; i++) {
            m.reportDecryptResult(true, true);
        }
        for (int i = 0; i < 450; i++) {
            m.reportDecryptResult(false, true);
        }

        assertTrue(m.preferRatchet());

        assertEquals(0, counter(m, "_ecCounter"));
        assertEquals(0, counter(m, "_pqCounter"));
    }
}

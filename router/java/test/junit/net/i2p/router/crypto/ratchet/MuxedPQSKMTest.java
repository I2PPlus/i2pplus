package net.i2p.router.crypto.ratchet;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;

import org.junit.Test;

/**
 * Tests the counter and ordering logic of {@link MuxedPQSKM}, including the
 * ratio-log checkpoint fix: failures must not advance the counters (the old
 * condition matched total == 0 on every failure and flooded the log), and
 * preferRatchet() must adapt to the reported success mix.
 *
 * The checkpoint path (every 25th success) is exercised for the no-throw
 * contract; the log line itself is not asserted.
 */
public class MuxedPQSKMTest {

    private MuxedPQSKM muxed() {
        return new MuxedPQSKM(mock(RatchetSKM.class), mock(RatchetSKM.class));
    }

    /** Failures do not change counters, so ordering stays at the default. */
    @Test
    public void testFailuresDoNotAdvanceCounters() {
        MuxedPQSKM m = muxed();
        for (int i = 0; i < 100; i++) {
            m.reportDecryptResult(false, false);
            m.reportDecryptResult(true, false);
        }
        // ec 0 >= pq 0/2 -> ratchet first, unchanged by pure failure traffic
        assertTrue(m.preferRatchet());
    }

    /** PQ successes flip the ordering to PQ-first. */
    @Test
    public void testPQSuccessesAffectOrdering() {
        MuxedPQSKM m = muxed();
        for (int i = 0; i < 20; i++)
            m.reportDecryptResult(false, true);
        // pq 20, ec 0: 0 >= 10 is false -> PQ first
        assertFalse(m.preferRatchet());
    }

    /** The 25th success hits the log checkpoint without throwing. */
    @Test
    public void testCheckpointDoesNotThrow() {
        MuxedPQSKM m = muxed();
        for (int i = 0; i < 25; i++)
            m.reportDecryptResult(i % 2 == 0, true);
        // also exercise the failure path interleaved
        for (int i = 0; i < 25; i++)
            m.reportDecryptResult(i % 2 == 0, false);
    }

    /** Both sides above threshold past the restart point zeroes counters. */
    @Test
    public void testCounterRestart() {
        MuxedPQSKM m = muxed();
        for (int i = 0; i < 60; i++)
            m.reportDecryptResult(true, true);
        for (int i = 0; i < 450; i++)
            m.reportDecryptResult(false, true);
        // ec 60 > 50, pq 450 > 50, total 510 > 500 -> restart and true
        assertTrue(m.preferRatchet());
        // counters were zeroed by the restart
        assertTrue(m.preferRatchet());
    }
}

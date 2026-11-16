package net.i2p.client.streaming.impl;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * Tests the cadence of the per-resend INFO line.
 *
 * <p>{@link Connection#shouldLogResend(int)} decides which resends get a log
 * line. One line per send per packet was the single largest producer of log
 * volume in this layer: 123,962 lines in one 22-minute rotation of a live
 * router's log, 71% of them from one connection, with the tail at 181 sends of a
 * single packet. The first sends of a packet are where a stall first becomes
 * visible, so those are logged in full and then every tenth send, which keeps the
 * growing count reportable at a tenth of the volume.
 *
 * @since 0.9.72+
 */
public class ResendLogCadenceTest {

    /**
     * One instance per test method, as JUnit builds a fresh test object for each
     * one; the cadence decision is pure, so there is nothing to set up.
     *
     * @since 0.9.72+
     */
    public ResendLogCadenceTest() {}

    /** The first three sends of a packet are always logged. */
    @Test
    public void testFirstSendsLogged() {
        assertTrue(Connection.shouldLogResend(1));
        assertTrue(Connection.shouldLogResend(2));
        assertTrue(Connection.shouldLogResend(3));
    }

    /** A send that is not a multiple of ten in between is skipped. */
    @Test
    public void testMiddleSendsSkipped() {
        for (int n = 4; n <= 9; n++)
            assertFalse("sends=" + n, Connection.shouldLogResend(n));
        for (int n = 11; n <= 19; n++)
            assertFalse("sends=" + n, Connection.shouldLogResend(n));
    }

    /** Every tenth send is logged, so the count keeps reporting as it grows. */
    @Test
    public void testEveryTenthLogged() {
        for (int n = 10; n <= 200; n += 10)
            assertTrue("sends=" + n, Connection.shouldLogResend(n));
    }

    /**
     * The measured worst case: the packet that was resent 181 times produced 21
     * lines instead of 181, and the last logged count before the cap is still
     * close to the real one.
     */
    @Test
    public void testMeasuredWorstCaseVolume() {
        int logged = 0;
        for (int n = 1; n <= 181; n++)
            if (Connection.shouldLogResend(n))
                logged++;
        assertEquals(21, logged);
        assertTrue(Connection.shouldLogResend(180));
        assertFalse(Connection.shouldLogResend(181));
    }

    /** A zero or negative send count is logged rather than silently dropped. */
    @Test
    public void testNonPositiveSendsLogged() {
        assertTrue(Connection.shouldLogResend(0));
        assertTrue(Connection.shouldLogResend(-1));
    }
}

package net.i2p.client.streaming.impl;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * Tests the gate in front of the RetransmitEvent window cut,
 * {@link Connection#cutsWindowOnLoss(boolean, int)}.
 *
 * <p>The cut is a congestion response, so it needs a measured congestion. A
 * connection that has never had a packet acknowledged has completed no round
 * trip, still holds its configured initial window, and has an empty bandwidth
 * estimate — cutting it because a SYN went unacked throws away a ramp that has
 * not started. The live symptom was an eepsite asset fetch whose connection
 * logged "Cutting SlowStartThreshold and Window" 1.8 s before
 * "Connection opened while waiting ... Took: 3117ms", so the stream began its
 * data phase below its initial window and, with ssthresh pinned to that cut
 * window, in congestion avoidance from the first byte.
 *
 * <p>The second half of the defect is the strike the cut records: a connect
 * that retried its SYN once entered the loss pass with
 * {@code _lossStrikes} already raised, so the first genuine data loss took the
 * 3/4 tier instead of the intended 7/8 one. The tier pair below pins what that
 * spurious strike costs, and the gate is what prevents it being recorded.
 *
 * <p>{@link Connection#cutsWindowOnLoss(boolean, int)} is static and stateless:
 * testable without a running router.
 *
 * @since 0.9.72+
 */
public class ConnectionWindowCutDecisionTest {

    /**
     * Test case carrying the window-cut gate cases for one streaming
     * connection. Every case calls a static helper, so the instance itself
     * holds no state and needs no router.
     */
    public ConnectionWindowCutDecisionTest() {
    }

    /** Connect phase: nothing acknowledged yet, so there is no congestion to cut. */
    @Test
    public void testConnectPhaseNeverCuts() {
        assertFalse(Connection.cutsWindowOnLoss(false, 1));
        assertFalse(Connection.cutsWindowOnLoss(false, 2));
        assertFalse(Connection.cutsWindowOnLoss(false, 3));
        assertFalse(Connection.cutsWindowOnLoss(false, 12));
    }

    /**
     * Established connection, first real loss: cut. Send count 2 is included
     * because a tail loss probe re-sends the head of line and bumps the count
     * without there being a real loss.
     */
    @Test
    public void testEstablishedFirstLossCuts() {
        assertTrue(Connection.cutsWindowOnLoss(true, 1));
        assertTrue(Connection.cutsWindowOnLoss(true, 2));
    }

    /** Established connection, already cut once by this loss: do not cut again. */
    @Test
    public void testEstablishedRepeatResendDoesNotRecut() {
        assertFalse(Connection.cutsWindowOnLoss(true, 3));
        assertFalse(Connection.cutsWindowOnLoss(true, 4));
    }

    /**
     * The gate must agree with the connect-phase test the same pass uses for
     * RTO backoff, or a pass could halve the RTO while declining to treat the
     * event as congestion. Both read {@code _highestAckedThrough >= 0}, which
     * is false while the counter holds its -1 initial value and true from the
     * first acknowledged sequence number 0 onward. Pin that boundary.
     */
    @Test
    public void testGateMatchesForwardProgressBoundary() {
        long initial = -1L;      // Connection._highestAckedThrough initial value
        long firstAck = 0L;      // sequence number of the first ACKed packet
        assertFalse(Connection.cutsWindowOnLoss(initial >= 0, 2));
        assertTrue(Connection.cutsWindowOnLoss(firstAck >= 0, 2));
    }

    /**
     * What a spurious connect-phase strike costs: the second tier gives up a
     * further eighth of a window that was never used. This is the regression
     * the gate removes, measured through the graduated profile rather than
     * through the connection's private counter.
     */
    @Test
    public void testSpuriousStrikeCostsAnEighthOfTheWindow() {
        int initial = 128;
        int firstRealLoss = Connection.graduatedLossWindow(1, initial);
        int lossAfterASynRetry = Connection.graduatedLossWindow(2, initial);
        assertEquals(112, firstRealLoss);
        assertEquals(96, lossAfterASynRetry);
        assertEquals(16, firstRealLoss - lossAfterASynRetry);
    }

    /**
     * A connect that retried its SYN must not be pushed into the halving tier
     * either: two spurious strikes would open the stream at half its initial
     * window before a byte of data has been exchanged.
     */
    @Test
    public void testTwoSpuriousStrikesWouldHalveTheWindow() {
        assertEquals(64, Connection.graduatedLossWindow(3, 128));
    }
}

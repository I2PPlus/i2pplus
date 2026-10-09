package net.i2p.router.transport.udp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Tests for how a UDP send round decides how long to wait.
 *
 * <p>The production symptom was a router sitting at a full extra core with no packets going
 * out. {@link PacketPusher} is the only thing that calls {@code getNextVolley}, so the spin
 * had to be in there, and the only loop in it that could run unblocked was the end-of-round
 * branch taken when the round's smallest reported delay was zero. A peer reports zero when
 * its retransmit timer has elapsed or it is holding an over-age message, so a single such
 * peer - a normal, transient state - was enough to pin the round minimum at zero for the
 * whole pool and keep it re-scanning flat out.
 *
 * <p>These pin the decision rather than the timing, so they do not depend on a router, a
 * socket, or catching a burst.
 */
public class OutboundMessageFragmentsDecisionTest {

    private static final int UNSET = Integer.MAX_VALUE;

    /** No peer has reported anything yet. */
    @Test
    public void testUnsetRoundTakesTheFirstDelay() {
        assertEquals(25, OutboundMessageFragments.foldSendDelay(UNSET, 25));
    }

    /** The smallest positive delay in the round is the one that matters. */
    @Test
    public void testSmallestPositiveDelayWins() {
        assertEquals(25, OutboundMessageFragments.foldSendDelay(100, 25));
        assertEquals(25, OutboundMessageFragments.foldSendDelay(25, 100));
    }

    /**
     * A peer asking for a prompt retry must not become "no wait at all".
     *
     * <p>This is the regression. Zero has to land on the floor, not on zero, because the
     * round test is {@code nextSendDelay > 0}.
     */
    @Test
    public void testZeroBecomesTheFloorNotZero() {
        int folded = OutboundMessageFragments.foldSendDelay(UNSET, 0);
        assertEquals(OutboundMessageFragments.MIN_WAIT_MS, folded);
        assertTrue("a zero delay must not leave the round with nothing to wait for",
                   folded > 0);
    }

    /** One peer's zero must not cancel the wait another peer asked for. */
    @Test
    public void testZeroDoesNotCancelAnotherPeersDelay() {
        int folded = OutboundMessageFragments.foldSendDelay(50, 0);
        assertEquals(OutboundMessageFragments.MIN_WAIT_MS, folded);
        assertTrue("a zero delay must not suppress a real delay", folded > 0);
    }

    /** A negative delay is meaningless input and is treated like zero, not believed. */
    @Test
    public void testNegativeDelayIsTreatedAsZero() {
        assertEquals(OutboundMessageFragments.MIN_WAIT_MS,
                     OutboundMessageFragments.foldSendDelay(UNSET, -1));
    }

    /** Every wait has a floor, so a round that allocated nothing cannot become a spin. */
    @Test
    public void testRoundWaitIsFloored() {
        assertEquals(OutboundMessageFragments.MIN_WAIT_MS, OutboundMessageFragments.roundWaitMs(0));
        assertEquals(OutboundMessageFragments.MIN_WAIT_MS, OutboundMessageFragments.roundWaitMs(3));
    }

    /** A real delay longer than the floor is honoured. */
    @Test
    public void testRoundWaitHonoursRealDelay() {
        assertEquals(120, OutboundMessageFragments.roundWaitMs(120));
    }

    /** One peer asking for a long wait must not stall the pool past the ceiling. */
    @Test
    public void testRoundWaitIsCapped() {
        assertEquals(500, OutboundMessageFragments.roundWaitMs(UNSET));
        assertEquals(500, OutboundMessageFragments.roundWaitMs(99999));
    }

    /**
     * The end-to-end shape of the bug, over a round of realistic peers.
     *
     * <p>Two peers are throttled and report real delays; a third reports zero. The round
     * must still produce a wait. Before the fix the zero won, the round ended with nothing
     * to wait for, and the loop re-scanned all three forever.
     */
    @Test
    public void testOneZeroPeerCannotMakeTheRoundSpin() {
        int min = UNSET;
        min = OutboundMessageFragments.foldSendDelay(min, 80);
        min = OutboundMessageFragments.foldSendDelay(min, 0);
        min = OutboundMessageFragments.foldSendDelay(min, 45);
        assertTrue("round ended with no wait, so the loop spins", min > 0);
        assertEquals(OutboundMessageFragments.MIN_WAIT_MS,
                     OutboundMessageFragments.roundWaitMs(min));
    }

    /** A round where every peer reports zero still waits rather than spinning. */
    @Test
    public void testAllZeroPeersStillWait() {
        int min = UNSET;
        for (int i = 0; i < 5; i++) {
            min = OutboundMessageFragments.foldSendDelay(min, 0);
        }
        assertTrue("every peer reporting zero must not mean no wait", min > 0);
    }
}

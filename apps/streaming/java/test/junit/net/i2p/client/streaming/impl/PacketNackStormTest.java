package net.i2p.client.streaming.impl;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Tests the pure storm gate {@link PacketLocal#isNackStorm}, the first half of
 * the fast-retransmit decision.
 *
 * <p>The decision itself is the most consequential branch in loss recovery, and
 * in the standard debug setup it printed nothing at all: both outcomes were
 * DEBUG on a logger the shipped {@code logger.config} does not enable. It is
 * now counted in {@code stream.fastRetransmit} and
 * {@code stream.fastRetransmitDeclined}, and the action is logged throttled at
 * INFO. For the declined counter to mean "we chose not to act" rather than
 * "every NACK we ever got", the three cases that are not a decision at all -
 * below the threshold, already retransmitted, connection gone - must be sorted
 * out before the trust test, which is what this pins.
 *
 * @since 0.9.72+
 */
public class PacketNackStormTest {

    /**
     * The storm-gate cases. Every case calls the static predicate, so the
     * instance holds no state and needs no packet, connection or session.
     */
    public PacketNackStormTest() {
    }

    /**
     * Below the threshold there is no storm and nothing to decide. The old code
     * logged "not resending on the NACK storm" for these, about a storm that
     * had not happened, and counted them as if a decision had been made.
     */
    @Test
    public void belowTheThresholdIsNotAStorm() {
        int threshold = Connection.FAST_RETRANSMIT_THRESHOLD;
        assertTrue(threshold > 1);
        for (int i = 0; i < threshold; i++)
            assertFalse("NACK " + i, PacketLocal.isNackStorm(i, false, true));
    }

    /**
     * The boundary sits on the threshold itself, read from its declaration so
     * the case cannot drift from it.
     */
    @Test
    public void theBoundaryIsTheThresholdItself() {
        int threshold = Connection.FAST_RETRANSMIT_THRESHOLD;
        assertTrue(PacketLocal.isNackStorm(threshold, false, true));
        assertFalse(PacketLocal.isNackStorm(threshold - 1, false, true));
    }

    /**
     * A packet already fast-retransmitted cannot trigger a second storm: the
     * trigger is one-shot per packet, and re-counting later NACKs as declines
     * would make the declined stat track the peer's NACK rate instead of our
     * decisions.
     */
    @Test
    public void alreadyRetransmittedIsNotADecision() {
        int threshold = Connection.FAST_RETRANSMIT_THRESHOLD;
        assertFalse(PacketLocal.isNackStorm(threshold, true, true));
        assertFalse(PacketLocal.isNackStorm(threshold + 7, true, true));
    }

    /**
     * With the connection gone there is nothing to retransmit through, and the
     * RTT the trust test needs is not readable either.
     */
    @Test
    public void noConnectionIsNotADecision() {
        int threshold = Connection.FAST_RETRANSMIT_THRESHOLD;
        assertFalse(PacketLocal.isNackStorm(threshold, false, false));
        assertFalse(PacketLocal.isNackStorm(threshold + 7, false, false));
    }

    /**
     * A storm stays a storm as the NACK count climbs - the peer keeps asking
     * and the decision is still owed until the packet is retransmitted.
     */
    @Test
    public void laterNacksStillCount() {
        int threshold = Connection.FAST_RETRANSMIT_THRESHOLD;
        assertTrue(PacketLocal.isNackStorm(threshold * 4, false, true));
    }
}

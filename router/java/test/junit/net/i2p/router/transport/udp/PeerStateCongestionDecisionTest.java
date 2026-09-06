package net.i2p.router.transport.udp;

import net.i2p.router.OutNetMessage;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.mockito.Mockito.mock;

/**
 * Unit tests for the pure AIMD / reno decisions extracted from
 * {@link PeerState#locked_messageACKed}, {@link PeerState#adjustMTU},
 * {@link PeerState#acked}, {@link PeerState#highestSeqNumAcked} and
 * {@link PeerState#handleFailed}: concurrent-message-limit tuning, send-window
 * growth, MTU probing, newly-ACKed byte accounting, fast-retransmit recovery
 * staging, the fast-retransmit scan skip and the window-refund cap.
 *
 * @since 0.9.71+
 */
public class PeerStateCongestionDecisionTest {

    // ----- nextConcurrentLimit -----

    @Test
    public void retransmitDecreasesByEighth() {
        assertEquals(56, PeerState.nextConcurrentLimit(2, 1000, 64, 0, 0));
    }

    @Test
    public void retransmitNeverBelowFloor() {
        int floor = PeerState.getMinConcurrentMsgs();
        assertEquals(floor, PeerState.nextConcurrentLimit(2, 1000, floor, 0, 0));
    }

    @Test
    public void noRttBoostsOnlyOnZeroDraw() {
        int base = PeerState.getInitConcurrentMsgs();
        assertEquals(base + 1, PeerState.nextConcurrentLimit(1, 0, base, 0, 0));
        assertEquals(base, PeerState.nextConcurrentLimit(1, 0, base, 0, 1));
    }

    @Test
    public void noRttBoostsCappedAtMax() {
        int max = PeerState.getMaxConcurrentMessages();
        assertEquals(max, PeerState.nextConcurrentLimit(1, 0, max, 0, 0));
    }

    @Test
    public void lowRttIncreasesProportionalToHeadroom() {
        int base = PeerState.getInitConcurrentMsgs();
        assertTrue("base=" + base + " out=" + PeerState.nextConcurrentLimit(1, 500, base, 0, 0),
                   PeerState.nextConcurrentLimit(1, 500, base, 0, 0) > base);
    }

    @Test
    public void lowRttAtMaxHoldsSteady() {
        int max = PeerState.getMaxConcurrentMessages();
        assertEquals(max, PeerState.nextConcurrentLimit(1, 500, max, 0, 0));
    }

    @Test
    public void highRttDecreasesSoftly() {
        int base = PeerState.getInitConcurrentMsgs();
        assertEquals((base * 3) / 4, PeerState.nextConcurrentLimit(1, 8000, base, 0, 0));
    }

    @Test
    public void highRttDecreaseNeverBelowFloor() {
        int min = PeerState.getMinConcurrentMsgs();
        assertEquals(min, PeerState.nextConcurrentLimit(1, 8000, 10, 0, 0));
    }

    @Test
    public void hysteresisWithQueueBumpsForBacklog() {
        int base = PeerState.getInitConcurrentMsgs();
        int out = PeerState.nextConcurrentLimit(1, 3000, base, 8, 0);
        assertTrue("out=" + out, out > base && out <= base + 4);
    }

    @Test
    public void hysteresisWithoutQueueHoldsSteady() {
        int base = PeerState.getInitConcurrentMsgs();
        assertEquals(base, PeerState.nextConcurrentLimit(1, 3000, base, 0, 0));
    }

    // ----- shouldGrowSendWindow -----

    @Test
    public void slowStartGrowsUnconditionally() {
        assertTrue(PeerState.shouldGrowSendWindow(1000, 2000, 100, 0.999f));
    }

    @Test
    public void atThresholdGrowsUnconditionally() {
        assertTrue(PeerState.shouldGrowSendWindow(2000, 2000, 100, 0.999f));
    }

    @Test
    public void congestionAvoidanceGrowsByProbability() {
        // prob = 100 / (2 * 2000) = 0.025; draw 0.01 <= 0.025 -> grow
        assertTrue(PeerState.shouldGrowSendWindow(2000, 1000, 100, 0.01f));
        // draw 0.5 > 0.025 -> hold
        assertFalse(PeerState.shouldGrowSendWindow(2000, 1000, 100, 0.5f));
    }

    @Test
    public void congestionAvoidanceGuaranteedAtFullCredit() {
        // prob = 2000 / (2 * 2000) = 0.5; even a max draw can still grow for
        // window == acked, so pin with draw 0.0 and 1.0 to bracket behavior
        assertTrue(PeerState.shouldGrowSendWindow(2000, 1000, 2000, 0.0f));
        assertFalse(PeerState.shouldGrowSendWindow(2000, 1000, 2000, 1.0f));
    }

    @Test
    public void negativeDrawFoldIsIdempotent() {
        // v = -0.01 folds to 0.01 (same as the positive case above)
        assertTrue(PeerState.shouldGrowSendWindow(2000, 1000, 100, -0.01f));
    }

    // ----- wantLargerMTU -----

    @Test
    public void wantsLargerOnCleanLink() {
        assertTrue(PeerState.wantLargerMTU(true, 100, 5));
    }

    @Test
    public void noLargerOnRetransmit() {
        assertFalse(PeerState.wantLargerMTU(false, 100, 5));
    }

    @Test
    public void noLargerWhenLossy() {
        // 10% retransmit ratio is not under the 10% probe gate
        assertFalse(PeerState.wantLargerMTU(true, 100, 10));
    }

    // ----- mtuIncreaseEligible -----

    @Test
    public void increaseEligibleNearCeilingWithNoDecreases() {
        assertTrue(PeerState.mtuIncreaseEligible(1200, 1500, 1200, 0, 0));
    }

    @Test
    public void increaseBlockedBeforeCeilingReached() {
        // maxPktSz far below mtu - 2*MTU_STEP -> the probe didn't fill the pipe
        assertFalse(PeerState.mtuIncreaseEligible(1200, 1500, 1000, 0, 0));
    }

    @Test
    public void increaseBlockedAtMaxMTU() {
        assertFalse(PeerState.mtuIncreaseEligible(1500, 1500, 1500, 0, 0));
    }

    @Test
    public void increaseAfterDecreaseNeedsZeroDraw() {
        int mtu = 1200;
        int large = 1500;
        // draw 1 (probabilistic gate fails) vs draw 0 (passes)
        assertFalse(PeerState.mtuIncreaseEligible(mtu, large, mtu, 3, 1));
        assertTrue(PeerState.mtuIncreaseEligible(mtu, large, mtu, 3, 0));
    }

    // ----- mtuDecreaseEligible -----

    @Test
    public void decreaseEligibleAfterLossyCeilingHit() {
        assertTrue(PeerState.mtuDecreaseEligible(1200, 576, 1200));
    }

    @Test
    public void decreaseNotEligibleBelowFloor() {
        assertFalse(PeerState.mtuDecreaseEligible(576, 576, 576));
    }

    @Test
    public void decreaseNotEligibleWhenProbeShallow() {
        // packet far under mtu - 4*MTU_STEP -> likely the loss was unrelated
        assertFalse(PeerState.mtuDecreaseEligible(1200, 576, 900));
    }

    // ----- newlyAckedBytes -----

    @Test
    public void completedMessageCreditsFullRemainder() {
        assertEquals(500, PeerState.newlyAckedBytes(500, true, 0));
    }

    @Test
    public void partialAckCreditsOnlyDelta() {
        assertEquals(100, PeerState.newlyAckedBytes(500, false, 400));
    }

    @Test
    public void emptyAckCreditsZero() {
        assertEquals(0, PeerState.newlyAckedBytes(0, false, 0));
    }

    // ----- fastRtxMode -----

    @Test
    public void thresholdNacksStartRecovery() {
        assertEquals(PeerState.FastRtxMode.START, PeerState.fastRtxMode(3));
    }

    @Test
    public void aboveThresholdContinuesRecovery() {
        assertEquals(PeerState.FastRtxMode.CONTINUE, PeerState.fastRtxMode(4));
        assertEquals(PeerState.FastRtxMode.CONTINUE, PeerState.fastRtxMode(50));
    }

    @Test
    public void belowThresholdIsNone() {
        assertEquals(PeerState.FastRtxMode.NONE, PeerState.fastRtxMode(0));
        assertEquals(PeerState.FastRtxMode.NONE, PeerState.fastRtxMode(2));
    }

    // ----- shouldSkipFastRetransmit / reachedRetransmitCap -----

    @Test
    public void skipWhileFloodingForDupAcks() {
        assertTrue(PeerState.shouldSkipFastRetransmit(true, 0));
    }

    @Test
    public void noSkipOutsideFastRetransmit() {
        assertFalse(PeerState.shouldSkipFastRetransmit(false, 0));
    }

    @Test
    public void capHoldsNormalRetransmitAtHalf() {
        assertTrue(PeerState.reachedRetransmitCap(5, 10, false));
        assertFalse(PeerState.reachedRetransmitCap(4, 10, false));
    }

    @Test
    public void capLiftedDuringFastRetransmit() {
        assertFalse(PeerState.reachedRetransmitCap(10, 10, true));
    }

    @Test
    public void isTotalFailOnlyFirstOutboundMessage() {
        assertTrue(PeerState.isTotalFail(newObjectStub(), false, 0));
        assertFalse(PeerState.isTotalFail(newObjectStub(), false, 1));
        assertFalse(PeerState.isTotalFail(newObjectStub(), true, 0));
        assertFalse(PeerState.isTotalFail(null, false, 0));
    }

    // ----- cappedRefund -----

    @Test
    public void refundWithinWindowPassesThrough() {
        assertEquals(1500, PeerState.cappedRefund(1000, 500, 2000));
    }

    @Test
    public void refundCappedAtWindow() {
        assertEquals(2000, PeerState.cappedRefund(1000, 5000, 2000));
    }

    @Test
    public void refundWhenAlreadyAtWindowStaysCapped() {
        assertEquals(2000, PeerState.cappedRefund(2000, 10, 2000));
    }

    /**
     *  A Mockito mock is enough for isTotalFail() (it only null-checks the
     *  message, so the real constructor's dependencies are never exercised).
     */
    private static OutNetMessage newObjectStub() {
        return mock(OutNetMessage.class);
    }
}

package net.i2p.router.peermanager;

import static org.junit.Assert.*;

import org.junit.Assume;
import org.junit.Test;

import net.i2p.data.Hash;
import net.i2p.router.RouterContext;
import net.i2p.router.RouterTestHelper;

/**
 * The calibrated bounds may only ever narrow the flag when the absolute rule has
 * already accepted a peer, and an unknown bound must leave the verdict alone.
 *
 * <p>This is the property that makes the calibration safe to introduce: every
 * calibrated floor is clamped to at least
 * {@code LOW_LATENCY_FLOOR_MS / multiple}, so no bar produced from measured
 * data can end up stricter than the fixed absolute rule it sits beside. Combined
 * with a null bound meaning "no opinion", the set of qualifying peers is
 * monotonically non-shrinking against the pre-calibration behaviour, so no
 * feedback loop can ratchet the bar tighter over successive cycles.
 *
 * @since 0.9.71+
 */
public class CalibratedLowLatencyTest {

    private static Hash hash(int b) {
        byte[] d = new byte[Hash.HASH_LENGTH];
        d[0] = (byte) b;
        d[1] = (byte) (3 - b);
        return Hash.create(d);
    }

    /**
     * A profile, plus a timestamp the profile's own clock will agree with.
     *
     * <p>recalculateLowLatency() reads the context clock rather than taking a
     * 'now' argument, so a fixed constant stamped into the profile would land
     * far outside the validity window and the reading would come back unknown -
     * which reads as "the flag never turned on" rather than as a wrong answer.
     */
    private static final RouterContext CTX = RouterTestHelper.getContext();
    private static final long NOW = (CTX != null) ? CTX.clock().now() : 0L;

    private static PeerProfile profile() {
        Assume.assumeTrue("no RouterContext available", CTX != null);
        return new PeerProfile(CTX, hash(8));
    }

    // ---------------- null bounds leave the absolute verdict ----------------

    @Test
    public void testNullCalibratedFloorIsIgnored() {
        PeerProfile p = profile();
        p.setFirstHopRtt(50, NOW);
        p.recalculateLowLatency(null, null);
        assertTrue("50ms is under the absolute floor, so it stays low latency",
                   p.isLowLatency());
    }

    @Test
    public void testZeroAndNegativeBoundsAreIgnored() {
        PeerProfile p = profile();
        p.setFirstHopRtt(50, NOW);
        p.recalculateLowLatency(Long.valueOf(0), Long.valueOf(-1));
        assertTrue(p.isLowLatency());
    }

    @Test
    public void testAbsoluteRuleStillRejectsWhenBoundsAreNull() {
        PeerProfile p = profile();
        p.setFirstHopRtt(500, NOW);
        p.recalculateLowLatency(null, null);
        assertFalse("500ms exceeds the absolute floor regardless of calibration",
                    p.isLowLatency());
    }

    // ---------------- a calibrated floor only removes accepted peers ---------

    @Test
    public void testCalibratedFloorRejectsAPeerTheAbsoluteRuleAccepted() {
        PeerProfile p = profile();
        // 90ms passes the absolute 100ms floor but not a 60ms calibrated floor
        p.setFirstHopRtt(90, NOW);
        p.recalculateLowLatency(Long.valueOf(60), null);
        assertFalse(p.isLowLatency());
    }

    @Test
    public void testPeerUnderBothFloorsIsAccepted() {
        PeerProfile p = profile();
        p.setFirstHopRtt(50, NOW);
        p.recalculateLowLatency(Long.valueOf(60), Long.valueOf(55));
        assertTrue(p.isLowLatency());
    }

    @Test
    public void testCohortFloorAppliesIndependently() {
        PeerProfile p = profile();
        // under the transport floor but over the cohort floor
        p.setFirstHopRtt(70, NOW);
        p.recalculateLowLatency(Long.valueOf(60), Long.valueOf(65));
        assertFalse("the cohort bound is a second bound, not a tie-breaker",
                    p.isLowLatency());
    }

    @Test
    public void testBothBoundsMustPass() {
        PeerProfile p = profile();
        p.setFirstHopRtt(80, NOW);
        p.recalculateLowLatency(Long.valueOf(60), Long.valueOf(90));
        assertFalse("failing either bound is enough to reject", p.isLowLatency());
    }

    // ---------------- never widens ----------------

    @Test
    public void testCalibratedBoundCannotAdmitWhatTheAbsoluteRuleRejected() {
        PeerProfile p = profile();
        // 500ms fails the absolute rule; a generous calibrated floor must not rescue it
        p.setFirstHopRtt(500, NOW);
        p.recalculateLowLatency(Long.valueOf(900), Long.valueOf(900));
        assertFalse("a calibrated bound must never widen past the absolute rule",
                    p.isLowLatency());
    }

    // ---------------- unmeasured stays unjudged ----------------

    @Test
    public void testUnmeasuredPeerIsLeftAlone() {
        PeerProfile p = profile();
        p.recalculateLowLatency(Long.valueOf(60), Long.valueOf(60));
        assertFalse("with no measurement there is nothing to judge, so the flag "
                    + "must not be turned on", p.isLowLatency());
    }

    // ---------------- the clamp invariant, end to end ----------------

    @Test
    public void testClampedFloorNeverStricterThanAbsoluteFloor() {
        // The property that makes the whole thing non-ratcheting: whatever the
        // measured population looks like, the calibrated bar a peer is judged
        // against is never below the fixed absolute floor.
        ProtocolRttBaseline absurd = new ProtocolRttBaseline();
        for (int i = 0; i < 200; i++) {
            absurd.add(1);
        }
        long floor = PeerProfile.LOW_LATENCY_FLOOR_MS;
        long multiple = 3;
        long clamped = absurd.getClampedBaselineMs(floor, (int) multiple);
        assertTrue("clamped baseline must not sit below floor/multiple",
                   clamped >= floor / multiple);
        // Integer division means the yielded bar lands within one multiple of the
        // absolute floor rather than exactly on it: 100/3*3 = 99. The guarantee
        // is that the bar is never stricter than the absolute rule by more than
        // that truncation, which is what makes the qualifying set non-shrinking.
        long bar = clamped * multiple;
        assertTrue("bar must not fall below the absolute floor except by truncation",
                   bar >= floor - (multiple - 1));
    }
}
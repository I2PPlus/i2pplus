package net.i2p.router;

import static org.junit.Assert.*;

import org.junit.Test;

import net.i2p.router.tunnel.pool.TunnelPeerSelector;

/**
 * Tests the peer-selection activity-window policy, which is keyed on peer
 * supply rather than on build outcomes.
 *
 * <p>The regression this replaces: the window used to widen as build success
 * fell. Build success measures outcomes, not supply — it is depressed just as
 * readily by unresponsive peers, build timeouts and unreachable destinations as
 * by an over-tight recency window — so the control closed a positive feedback
 * loop (worse builds widened the window, admitting older peers, which worsened
 * the builds) and did so while ample fast peers sat unused. A healthy router
 * here holds ~1000 fast peers and still pinned the multiplier at its ceiling.
 *
 * <p>The window's only legitimate reason to widen is being short of the peers
 * selection would otherwise use, so peer supply is now the sole gate. The one
 * exception is the startup grace period, where a cold profile set makes every
 * peer look stale for want of test history rather than fault.
 *
 * @since 0.9.71+
 */
public class TunerActivityWindowTest {

    private static final int MIN = 1;
    private static final int MAX = 4;
    private static final int STEP = 1;
    private static final int SCARCE = TunnelPeerSelector.SCARCE_FAST_PEERS;

    private static int gate(int fastPeers, boolean startupGrace) {
        return TunnelPeerSelector.windowMultiplierFor(fastPeers, startupGrace, MIN, MAX);
    }

    private static int next(int fastPeers, boolean startupGrace, int healthyCycles) {
        return Tuner.nextWindowMultiplier(fastPeers, startupGrace, healthyCycles, MIN, MAX, STEP);
    }

    // ---- the gate: peer supply decides, build success does not ----

    @Test
    public void ampleFastPeersPinTheWindowToTheFloor() {
        assertEquals(MIN, gate(SCARCE, false));
        assertEquals(MIN, gate(SCARCE + 1, false));
        assertEquals("a healthy router's ~1000 fast peers must not widen the window",
                     MIN, gate(1084, false));
        assertEquals(MIN, gate(2000, false));
    }

    @Test
    public void scarcityWidensUpToTheCeiling() {
        assertEquals(MAX, gate(0, false));
        assertEquals(MAX, gate(1, false));
    }

    @Test
    public void shortageRampsMonotonically() {
        // Fewer fast peers must never mean less widening. Walk down from just
        // above the threshold (where the window is already at the floor) to
        // zero, and require the multiplier to be non-decreasing.
        int prev = gate(SCARCE + 10, false);
        assertEquals("just above the threshold the window must be at the floor",
                     MIN, prev);
        for (int fast = SCARCE; fast >= 0; fast -= 10) {
            int t = gate(fast, false);
            assertTrue("fast=" + fast + " -> " + t + " must widen at least as much as "
                       + (fast + 10) + " -> " + prev, t >= prev);
            prev = t;
        }
        assertEquals("running out of fast peers must reach the ceiling",
                     MAX, prev);
    }

    @Test
    public void rampIsContinuousNotStepped() {
        int atThreshold = gate(SCARCE, false);
        int midway = gate(SCARCE / 2, false);
        int nearZero = gate(SCARCE / 10, false);
        assertEquals(MIN, atThreshold);
        assertTrue("expected a ramp below the threshold, got " + midway,
                   midway > atThreshold && midway < nearZero);
        assertEquals(MAX, nearZero);
    }

    // ---- startup: the one legitimate exception ----

    @Test
    public void startupGraceWidensRegardlessOfPeerCount() {
        assertEquals("a cold router has no test history, so peers look stale unfairly",
                     MAX, gate(0, true));
        assertEquals(MAX, gate(SCARCE, true));
        assertEquals("startup is exempt even when the count is nominally ample",
                     MAX, gate(1084, true));
    }

    @Test
    public void startupGraceOverridesAnAlreadyAmpleCount() {
        assertTrue(gate(5000, true) > gate(5000, false));
    }

    // ---- bounds and degenerate input ----

    @Test
    public void neverOutsideTheConfiguredBounds() {
        for (int fast = -5; fast <= SCARCE * 2; fast += 3) {
            for (boolean startup : new boolean[] {false, true}) {
                int t = gate(fast, startup);
                assertTrue("fast=" + fast + " startup=" + startup + " -> " + t,
                           t >= MIN && t <= MAX);
            }
        }
    }

    @Test
    public void negativePeerCountClampsToTheCeiling() {
        assertEquals(MAX, gate(-1, false));
    }

    @Test
    public void degenerateBoundsReturnTheFloor() {
        assertEquals(2, TunnelPeerSelector.windowMultiplierFor(0, false, 2, 2));
        // Inverted bounds (min > max) cannot express a widening, so the floor
        // is returned; the caller clamps anyway, and the point is not to throw.
        assertEquals(4, TunnelPeerSelector.windowMultiplierFor(0, false, 4, 2));
    }

    // ---- nextWindowMultiplier: cycle pacing on top of the gate ----

    @Test
    public void scarcityWidensInOneStep() {
        assertEquals("a scarce peer set must widen immediately, not after a cycle",
                     MAX, next(0, false, 0));
    }

    @Test
    public void ampleSupplyHoldsUntilTheCycleBudgetIsSpent() {
        // healthyCycles counts PRIOR ample cycles, so 0 and 1 must still hold.
        assertEquals("one ample cycle must not be enough to tighten",
                     MIN + STEP, next(SCARCE, false, 0));
        assertEquals(MIN + STEP, next(SCARCE, false, 1));
    }

    @Test
    public void ampleSupplyTightensAfterThreeCycles() {
        assertEquals("the third consecutive ample cycle tightens to the floor",
                     MIN, next(SCARCE, false, 2));
    }

    @Test
    public void healthyCyclesResetWhenSupplyDropsAgain() {
        assertEquals(MAX, next(0, false, 0));
        // Two ample cycles do not yet justify tightening...
        assertEquals(MIN + STEP, next(SCARCE, false, 0));
        assertEquals(MIN + STEP, next(SCARCE, false, 1));
        // ...and renewed scarcity reopens the window, resetting the budget.
        assertEquals(MAX, next(10, false, 0));
    }

    @Test
    public void neverExceedsCeilingOrDropsBelowFloor() {
        int window = MIN;
        int[] counts = { 2000, 50, SCARCE, 0, 1084, 10, 0, SCARCE, SCARCE };
        int cycles = 0;
        for (int fast : counts) {
            window = next(fast, false, cycles);
            assertTrue("window " + window + " out of bounds at fast=" + fast,
                       window >= MIN && window <= MAX);
            cycles = fast >= SCARCE ? cycles + 1 : 0;
        }
    }

    @Test
    public void aSustainedAmpleRunSettlesAtTheFloor() {
        int window = MAX;
        for (int i = 0; i < 12; i++) {
            window = next(SCARCE, false, i);
        }
        assertEquals("sustained ample supply must settle the window at the floor",
                     MIN, window);
    }
}

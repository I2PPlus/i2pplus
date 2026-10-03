package net.i2p.client.streaming.impl;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * Decision tests for {@link Connection#initialSsthresh}, the clamp that keeps a
 * new connection's slow-start threshold at or below its window ceiling.
 *
 * <p>The invariant matters because {@code ConnectionPacketHandler.adjustWindow()}
 * chooses its growth path on {@code window < ssthresh}. When the Tuner raises
 * {@code maxSlowStartWindow} above {@code maxWindowSize} the two cross, slow
 * start never ends before the ceiling stops growth, and the deficit-multiplier
 * recovery path in congestion avoidance becomes unreachable — leaving only the
 * slow-start growth rate to govern every window change.
 *
 * @since 0.9.71+
 */
public class ConnectionSsthreshTest {

    // ---- the reported production configuration ----

    /** maxWindowSize=768 (the SystemVersion.isSlow() default), initialWindowSize=128. */
    @Test
    public void testSlowStartCapAboveCeilingIsClamped() {
        // maxSlowStartWindow=2048, maxWindowSize=768, initialWindowSize=128
        assertEquals(768, Connection.initialSsthresh(2048, 768, 128));
    }

    /**
     * The regression this clamp exists for: without it ssthresh (2048) exceeds
     * the ceiling (768), so {@code window < ssthresh} is permanently true.
     */
    @Test
    public void testResultNeverExceedsCeilingBase() {
        int ssthresh = Connection.initialSsthresh(8192, 512, 128);
        assertTrue("ssthresh must not exceed the pre-BDP ceiling base",
                   ssthresh <= Math.max(512, 128));
    }

    // ---- the ceiling base is max(globalMax, initialWindowSize) ----

    /** initialWindowSize above the Tuner global raises the base, not lowers it. */
    @Test
    public void testInitialWindowSizeLargerThanGlobalRaisesBase() {
        assertEquals(1024, Connection.initialSsthresh(2048, 768, 1024));
    }

    /** With no BDP headroom the cap itself is below the base and wins. */
    @Test
    public void testCapWinsWhenBelowBase() {
        assertEquals(256, Connection.initialSsthresh(256, 2048, 128));
    }

    /** Equal inputs leave the value unchanged. */
    @Test
    public void testEqualInputsUnchanged() {
        assertEquals(768, Connection.initialSsthresh(768, 768, 128));
    }

    // ---- degenerate inputs must never yield a non-positive threshold ----

    /**
     * A zero threshold would put the connection straight into linear congestion
     * avoidance at window=1, where the deficit multiplier is ~1 and the window
     * can never ramp.
     */
    @Test
    public void testZeroInputsYieldAtLeastOne() {
        assertEquals(1, Connection.initialSsthresh(0, 0, 0));
    }

    @Test
    public void testNegativeInputsYieldAtLeastOne() {
        assertEquals(1, Connection.initialSsthresh(-5, -5, -5));
    }

    /** A zero global window must not clamp below the initial window. */
    @Test
    public void testZeroGlobalKeepsInitialWindow() {
        assertEquals(128, Connection.initialSsthresh(2048, 0, 128));
    }

    /** An absent initial window must not clamp below the Tuner global. */
    @Test
    public void testZeroInitialKeepsGlobal() {
        assertEquals(768, Connection.initialSsthresh(2048, 768, 0));
    }
}

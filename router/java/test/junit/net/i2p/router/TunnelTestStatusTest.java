package net.i2p.router;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Tests for the {@link TunnelTestStatus} classification predicates that the
 * capacity-counting and endpoint-selection paths share.
 *
 * <p>These exist because the consumers previously disagreed: the scan gates
 * excluded both {@code FAILED} and {@code FAILING} while the usable-count
 * helpers excluded only {@code FAILING}, so a pool holding nothing but dead
 * tunnels reported full capacity and suppressed its own pre-build. Pinning the
 * predicates here keeps that drift from returning.
 *
 * <p>The domain split the predicates encode: {@code FAILED} is dead and
 * terminal for traffic-proof rehabilitation, {@code FAILING} is transient and
 * may return to {@code GOOD}.
 *
 * @since 0.9.71+
 */
public class TunnelTestStatusTest {

    @Test
    public void testOnlyFailedIsDead() {
        assertTrue(TunnelTestStatus.FAILED.isDead());
        for (TunnelTestStatus ts : TunnelTestStatus.values()) {
            if (ts != TunnelTestStatus.FAILED) {
                assertFalse(ts.name() + " must not be dead", ts.isDead());
            }
        }
    }

    @Test
    public void testOnlyFailingIsTransient() {
        assertTrue(TunnelTestStatus.FAILING.isFailing());
        for (TunnelTestStatus ts : TunnelTestStatus.values()) {
            if (ts != TunnelTestStatus.FAILING) {
                assertFalse(ts.name() + " must not be failing", ts.isFailing());
            }
        }
    }

    @Test
    public void testUnusableIsExactlyTheTwoMarkedStates() {
        assertTrue(TunnelTestStatus.FAILED.isUnusable());
        assertTrue(TunnelTestStatus.FAILING.isUnusable());
        for (TunnelTestStatus ts : TunnelTestStatus.values()) {
            if (ts != TunnelTestStatus.FAILED && ts != TunnelTestStatus.FAILING) {
                assertFalse(ts.name() + " must be usable", ts.isUnusable());
            }
        }
    }

    /** A healthy tunnel is neither dead nor failing, and stays capacity. */
    @Test
    public void testHealthyTunnelIsUsable() {
        assertFalse(TunnelTestStatus.GOOD.isDead());
        assertFalse(TunnelTestStatus.GOOD.isFailing());
        assertFalse(TunnelTestStatus.GOOD.isUnusable());
    }

    /** An unmeasured tunnel is not capacity-worthy but is not marked dead. */
    @Test
    public void testUntestedIsNotMarked() {
        assertFalse(TunnelTestStatus.UNTESTED.isDead());
        assertFalse(TunnelTestStatus.UNTESTED.isFailing());
        assertFalse(TunnelTestStatus.UNTESTED.isUnusable());
    }
}

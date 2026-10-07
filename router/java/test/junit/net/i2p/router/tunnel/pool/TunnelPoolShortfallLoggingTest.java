package net.i2p.router.tunnel.pool;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Severity classification for the "not enough leases" report.
 *
 * <p>The report used to fire on every shortfall, so a working 6/8 LeaseSet logged
 * identically to a crippled 1/8 one. Measured over a 14 minute window that made 56%
 * of the lines non-actionable. These tests pin the half-target line the pool's own
 * "thin pool" fast-path already uses, so the two signals agree.
 *
 * @since 0.9.71+
 */
public class TunnelPoolShortfallLoggingTest {

    /** The regression shape: 6/8 and 7/8 were being reported as shortfalls. */
    @Test
    public void nearCompleteSetsAreNotMaterial() {
        assertFalse(TunnelPool.isMateriallyShort(7, 8));
        assertFalse(TunnelPool.isMateriallyShort(6, 8));
        assertFalse(TunnelPool.isMateriallyShort(4, 8));
    }

    @Test
    public void belowHalfIsMaterial() {
        assertTrue(TunnelPool.isMateriallyShort(3, 8));
        assertTrue(TunnelPool.isMateriallyShort(2, 8));
        assertTrue(TunnelPool.isMateriallyShort(1, 8));
        assertTrue(TunnelPool.isMateriallyShort(1, 4));
        assertTrue(TunnelPool.isMateriallyShort(1, 3));
        // exactly half is not below half
        assertFalse(TunnelPool.isMateriallyShort(1, 2));
        assertFalse(TunnelPool.isMateriallyShort(2, 4));
    }

    /**
     * The boundary, restated independently of how it is implemented: a set is material
     * exactly when it holds fewer than half of the target, i.e. at most
     * {@code (wanted - 1) / 2} leases. The pool's thin-pool fast-path calls this same
     * helper, so the two signals cannot drift apart - an earlier version compared
     * against integer-divided {@code target / 2} and so disagreed for odd targets.
     */
    @Test
    public void theLineIsExactlyBelowHalf() {
        for (int wanted = 1; wanted <= 32; wanted++) {
            int highestMaterial = (wanted - 1) / 2;
            for (int leases = 0; leases <= wanted; leases++) {
                assertEquals("wanted=" + wanted + " leases=" + leases,
                             leases <= highestMaterial,
                             TunnelPool.isMateriallyShort(leases, wanted));
            }
        }
    }

    /** No target means no shortfall to classify, but an empty set against any target is one. */
    @Test
    public void zeroTargetIsNeverMaterial() {
        assertFalse(TunnelPool.isMateriallyShort(0, 0));
        assertTrue(TunnelPool.isMateriallyShort(0, 1));
        assertTrue(TunnelPool.isMateriallyShort(0, 2));
    }

    /**
     * The degenerate case the shared definition fixes: a target of 1 with nothing healthy
     * is the worst state there is, and the old integer-division fast-path could not see it.
     */
    @Test
    public void targetOfOneStillTriggers() {
        assertTrue(TunnelPool.isMateriallyShort(0, 1));
        assertFalse(TunnelPool.isMateriallyShort(1, 1));
    }

    @Test
    public void usableMinimumRoundsUp() {
        assertEquals(4, TunnelPool.usableLeaseMinimum(8));
        assertEquals(2, TunnelPool.usableLeaseMinimum(4));
        assertEquals(1, TunnelPool.usableLeaseMinimum(1));
        assertEquals(3, TunnelPool.usableLeaseMinimum(5));
        assertEquals(0, TunnelPool.usableLeaseMinimum(0));
    }

    /** The reported floor is consistent with the materiality line it explains. */
    @Test
    public void reportedMinimumIsConsistentWithTheLine() {
        for (int wanted = 2; wanted <= 32; wanted++) {
            int min = TunnelPool.usableLeaseMinimum(wanted);
            assertFalse("the reported minimum must not itself be material",
                        TunnelPool.isMateriallyShort(min, wanted));
            if (min > 0) {
                assertTrue("one below the reported minimum must be material",
                           TunnelPool.isMateriallyShort(min - 1, wanted));
            }
        }
    }
}

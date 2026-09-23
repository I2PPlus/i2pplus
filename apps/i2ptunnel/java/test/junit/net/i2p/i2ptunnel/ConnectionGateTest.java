package net.i2p.i2ptunnel;

import static org.junit.Assert.*;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;

/**
 * Tests the per-tunnel concurrent-connection admission gate in
 * I2PTunnelServer: acquireConnectionSlot() / releaseConnectionSlot().
 *
 * @since 0.9.71+
 */
public class ConnectionGateTest {

    /**
     * Null/empty config falls back to the built-in default cap.
     */
    @Test
    public void testResolveDefaultWhenUnset() {
        assertEquals(I2PTunnelClientBase.DEFAULT_MAX_CONNECTIONS,
                     I2PTunnelClientBase.resolveMaxConnections(null));
    }

    /**
     * A valid positive integer config is honored verbatim.
     */
    @Test
    public void testResolveHonorsPositiveConfig() {
        assertEquals(128, I2PTunnelClientBase.resolveMaxConnections("128"));
        assertEquals(1, I2PTunnelClientBase.resolveMaxConnections("1"));
    }

    /**
     * Zero/negative/garbage config is rejected so a typo or an explicit "disable"
     * cannot turn the flood-shedding cap off (an unbounded cap is the bug we are fixing).
     */
    @Test
    public void testResolveRejectsZeroNegativeAndGarbage() {
        assertEquals(I2PTunnelClientBase.DEFAULT_MAX_CONNECTIONS,
                     I2PTunnelClientBase.resolveMaxConnections("0"));
        assertEquals(I2PTunnelClientBase.DEFAULT_MAX_CONNECTIONS,
                     I2PTunnelClientBase.resolveMaxConnections("-5"));
        assertEquals(I2PTunnelClientBase.DEFAULT_MAX_CONNECTIONS,
                     I2PTunnelClientBase.resolveMaxConnections("abc"));
    }

    /**
     * Cap of 0 (unlimited) always admits and never touches the counter.
     */
    @Test
    public void testUnlimitedAlwaysAdmits() {
        AtomicInteger active = new AtomicInteger();
        for (int i = 0; i < 10000; i++) {
            assertTrue("unlimited must admit", I2PTunnelServer.acquireConnectionSlot(0, active));
        }
        assertEquals("unlimited must not count reservations", 0, active.get());
    }

    /**
     * Negative cap behaves like unlimited (defensive clamp on config parse).
     */
    @Test
    public void testNegativeCapUnlimited() {
        AtomicInteger active = new AtomicInteger();
        assertTrue(I2PTunnelServer.acquireConnectionSlot(-5, active));
        assertEquals(0, active.get());
    }

    /**
     * Under the cap every acquire succeeds and the active count tracks reservations.
     */
    @Test
    public void testAcquireUpToCap() {
        AtomicInteger active = new AtomicInteger();
        for (int i = 1; i <= 4; i++) {
            assertTrue("acquire #" + i + " must succeed", I2PTunnelServer.acquireConnectionSlot(4, active));
            assertEquals(i, active.get());
        }
    }

    /**
     * The (cap+1)th acquire is rejected and leaves the count at the cap.
     */
    @Test
    public void testRejectOverCap() {
        AtomicInteger active = new AtomicInteger();
        int cap = 4;
        for (int i = 0; i < cap; i++) {
            assertTrue(I2PTunnelServer.acquireConnectionSlot(cap, active));
        }
        assertFalse("over cap must be rejected", I2PTunnelServer.acquireConnectionSlot(cap, active));
        assertEquals("rejected acquire must not count", cap, active.get());
    }

    /**
     * Releasing a slot makes room for the next acquire.
     */
    @Test
    public void testReleaseFreesSlot() {
        AtomicInteger active = new AtomicInteger();
        int cap = 2;
        assertTrue(I2PTunnelServer.acquireConnectionSlot(cap, active));
        assertTrue(I2PTunnelServer.acquireConnectionSlot(cap, active));
        assertFalse(I2PTunnelServer.acquireConnectionSlot(cap, active));
        I2PTunnelServer.releaseConnectionSlot(active);
        assertTrue("released slot must be reusable", I2PTunnelServer.acquireConnectionSlot(cap, active));
        assertEquals(2, active.get());
    }

    /**
     * The gate tolerates an unbalanced release (e.g. a cap configured on mid-flight
     * while unlimited connections are live): the counter clamps, never goes negative.
     */
    @Test
    public void testReleaseClampsAtZero() {
        AtomicInteger active = new AtomicInteger();
        I2PTunnelServer.releaseConnectionSlot(active);
        I2PTunnelServer.releaseConnectionSlot(active);
        assertEquals("release must clamp at zero", 0, active.get());
        // still admits afterwards
        assertTrue(I2PTunnelServer.acquireConnectionSlot(1, active));
    }

    /**
     * Full cycle: admit, use, release, repeat stays balanced at the cap.
     */
    @Test
    public void testCycleBalanced() {
        AtomicInteger active = new AtomicInteger();
        int cap = 3;
        for (int i = 0; i < 1000; i++) {
            assertTrue(I2PTunnelServer.acquireConnectionSlot(cap, active));
            assertEquals(1, active.get());
            I2PTunnelServer.releaseConnectionSlot(active);
            assertEquals(0, active.get());
        }
    }

    // =====================================================================
    // Queue-depth admission gate (shouldRejectOnQueue)
    // =====================================================================

    /** Disabled gate (capacity or threads <= 0) never rejects. */
    @Test
    public void testQueueGateDisabled() {
        assertFalse(I2PTunnelServer.shouldRejectOnQueue(10000, 0, 0, 8));
        assertFalse(I2PTunnelServer.shouldRejectOnQueue(10000, 2048, 0, 0));
        assertFalse(I2PTunnelServer.shouldRejectOnQueue(10000, -1, -1, -1));
    }

    /** Under both thresholds: admit. */
    @Test
    public void testQueueGateAdmitsWhenUnderThresholds() {
        assertFalse(I2PTunnelServer.shouldRejectOnQueue(0, 2048, 4, 8));
        assertFalse(I2PTunnelServer.shouldRejectOnQueue(10, 2048, 4, 8));
        assertFalse(I2PTunnelServer.shouldRejectOnQueue(32, 2048, 8, 8)); // == threads*4, not >
        // Low backlog relative to a larger pool: well under both thresholds.
        assertFalse(I2PTunnelServer.shouldRejectOnQueue(100, 2048, 4, 32));
    }

    /** Queue at/over 90% of capacity: reject (would hit AbortPolicy anyway). */
    @Test
    public void testQueueGateRejectsNearCapacity() {
        assertTrue(I2PTunnelServer.shouldRejectOnQueue(1844, 2048, 4, 8));  // 90%
        assertTrue(I2PTunnelServer.shouldRejectOnQueue(2048, 2048, 4, 8));  // full
        assertTrue(I2PTunnelServer.shouldRejectOnQueue(5000, 2048, 4, 8));  // over
    }

    /** Backlog more than 4x the pool: reject even if capacity remains. */
    @Test
    public void testQueueGateRejectsWhenBacklogExceedsThreads() {
        assertTrue(I2PTunnelServer.shouldRejectOnQueue(33, 2048, 8, 8));   // > 8*4
        assertTrue(I2PTunnelServer.shouldRejectOnQueue(1000, 2048, 8, 8));
        // but not when equal to threads*4
        assertFalse(I2PTunnelServer.shouldRejectOnQueue(32, 2048, 8, 8));
    }

    /** parseServerThreadOverride: null / garbage / below-floor -> -1; valid passthrough. */
    @Test
    public void testParseServerThreadOverride() {
        assertEquals(-1, I2PTunnelServer.parseServerThreadOverride(null));
        assertEquals(-1, I2PTunnelServer.parseServerThreadOverride(""));
        assertEquals(-1, I2PTunnelServer.parseServerThreadOverride("abc"));
        assertEquals(-1, I2PTunnelServer.parseServerThreadOverride("1"));
        assertEquals(-1, I2PTunnelServer.parseServerThreadOverride("0"));
        assertEquals(-1, I2PTunnelServer.parseServerThreadOverride("-5"));
        assertEquals(2, I2PTunnelServer.parseServerThreadOverride("2"));
        assertEquals(64, I2PTunnelServer.parseServerThreadOverride("64"));
        assertEquals(64, I2PTunnelServer.parseServerThreadOverride(" 64 "));
        assertEquals(16384, I2PTunnelServer.parseServerThreadOverride("16384"));
    }
}

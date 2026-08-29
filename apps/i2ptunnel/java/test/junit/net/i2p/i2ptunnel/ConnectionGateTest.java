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
}
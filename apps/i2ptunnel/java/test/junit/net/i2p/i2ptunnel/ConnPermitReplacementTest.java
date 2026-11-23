package net.i2p.i2ptunnel;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import net.i2p.data.Hash;
import org.junit.Test;

/**
 * The per-destination concurrent-connection permit bookkeeping in
 * {@link I2PTunnelHTTPClient}: what a request that replaces its I2P socket owes
 * the destination it was holding a permit for.
 *
 * <p>An I2P-keepalive request carries its permit across the request loop, and
 * the loop boundary hands it back only when the socket is dropped outright. A
 * socket that is instead replaced - another destination, another port, or a
 * failed health check - reaches the acquire with the old permit still held, and
 * the code assigned the new destination to the holder without releasing the old
 * one. The previous destination stayed counted for the life of the browser
 * connection, and a destination that flapped walked one slot per replacement to
 * the cap, where every further request is refused with "too many concurrent
 * connections".
 *
 * <p>The permit map is static and the helpers are stateless, so the cases run
 * without a running proxy. Each uses its own destination to stay independent of
 * the others.
 *
 * @since 0.9.72+
 */
public class ConnPermitReplacementTest {

    /**
     * Test case carrying the permit bookkeeping cases. Every case calls a static
     * helper, so the instance holds no state and needs no router.
     */
    public ConnPermitReplacementTest() {
    }

    /**
     * A destination distinguished from every other one in this class by its last
     * byte, so the shared static permit map cannot let one case see another's.
     *
     * @param b the last byte of the hash, unique per case
     * @return a 32-byte Hash, non-null
     */
    private static Hash dest(int b) {
        byte[] d = new byte[32];
        d[31] = (byte) b;
        return new Hash(d);
    }

    /**
     * The fixed sequence: a request replacing its socket hands the held permit
     * back, then takes one for the new destination. Neither destination is left
     * counted for a socket it no longer has.
     */
    @Test
    public void testReplacementHandsTheHeldPermitBack() {
        Hash old = dest(1);
        Hash next = dest(2);
        assertTrue(I2PTunnelHTTPClient.tryAcquireConnPermit(old));
        assertEquals(1, I2PTunnelHTTPClient.activeConnPermits(old));

        I2PTunnelHTTPClient.releaseConnPermit(old);
        assertTrue(I2PTunnelHTTPClient.tryAcquireConnPermit(next));

        assertEquals(0, I2PTunnelHTTPClient.activeConnPermits(old));
        assertEquals(1, I2PTunnelHTTPClient.activeConnPermits(next));
        I2PTunnelHTTPClient.releaseConnPermit(next);
        assertEquals(0, I2PTunnelHTTPClient.activeConnPermits(next));
    }

    /**
     * The defect, stated as the sequence that caused it: acquire for the new
     * destination while the old one is still held, and release only the new one.
     * The old destination keeps a slot that no socket occupies, and nothing ever
     * gives it back.
     */
    @Test
    public void testOverwritingTheHolderLeaksASlot() {
        Hash old = dest(3);
        Hash next = dest(4);
        assertTrue(I2PTunnelHTTPClient.tryAcquireConnPermit(old));
        // What the unfixed replacement did: take the second permit, overwrite the
        // holder, and release only what the holder names.
        assertTrue(I2PTunnelHTTPClient.tryAcquireConnPermit(next));
        I2PTunnelHTTPClient.releaseConnPermit(next);

        assertEquals("the destination that was replaced is still counted",
                     1, I2PTunnelHTTPClient.activeConnPermits(old));
        // Clean up the leaked slot so the case does not poison the shared map.
        I2PTunnelHTTPClient.releaseConnPermit(old);
        assertEquals(0, I2PTunnelHTTPClient.activeConnPermits(old));
    }

    /**
     * A destination that flaps walks the cap one leaked slot at a time: each
     * replacement takes a permit for the same destination and the previous one is
     * never handed back, so the count climbs with the number of replacements and
     * not with the number of live sockets. The cap is per destination, which is
     * why the leak survives a long session before it bites - leaking against one
     * eepsite does not stop the proxy reaching another.
     */
    @Test
    public void testRepeatedLeakReachesTheCap() {
        Hash flapping = dest(5);
        for (int i = 0; i < I2PTunnelHTTPClient.MAX_CONNS_PER_DEST; i++) {
            assertTrue("replacement " + i + " should still be admitted",
                       I2PTunnelHTTPClient.tryAcquireConnPermit(flapping));
            // and the permit the previous socket held is dropped on the floor
        }
        // One permit held per replacement, and the cap is now reached with a
        // single live socket against the destination.
        assertEquals(I2PTunnelHTTPClient.MAX_CONNS_PER_DEST,
                     I2PTunnelHTTPClient.activeConnPermits(flapping));
        assertFalse(I2PTunnelHTTPClient.tryAcquireConnPermit(flapping));
        for (int i = 0; i < I2PTunnelHTTPClient.MAX_CONNS_PER_DEST; i++) {
            I2PTunnelHTTPClient.releaseConnPermit(flapping);
        }
        assertEquals(0, I2PTunnelHTTPClient.activeConnPermits(flapping));
    }

    /** The cap is enforced per destination, not across all of them. */
    @Test
    public void testCapIsPerDestination() {
        Hash first = dest(6);
        Hash second = dest(7);
        for (int i = 0; i < I2PTunnelHTTPClient.MAX_CONNS_PER_DEST; i++) {
            assertTrue(I2PTunnelHTTPClient.tryAcquireConnPermit(first));
        }
        assertFalse(I2PTunnelHTTPClient.tryAcquireConnPermit(first));
        assertTrue("a different destination is unaffected",
                   I2PTunnelHTTPClient.tryAcquireConnPermit(second));
        I2PTunnelHTTPClient.releaseConnPermit(second);
        for (int i = 0; i < I2PTunnelHTTPClient.MAX_CONNS_PER_DEST; i++) {
            I2PTunnelHTTPClient.releaseConnPermit(first);
        }
        assertEquals(0, I2PTunnelHTTPClient.activeConnPermits(first));
    }

    /**
     * Releasing more than was taken cannot drive the count negative or wedge the
     * destination: an extra release on one exit path plus the one in the finally
     * block is the shape this gate has to survive.
     */
    @Test
    public void testExtraReleasesDoNotCorruptTheCount() {
        Hash d = dest(8);
        assertTrue(I2PTunnelHTTPClient.tryAcquireConnPermit(d));
        I2PTunnelHTTPClient.releaseConnPermit(d);
        I2PTunnelHTTPClient.releaseConnPermit(d);
        I2PTunnelHTTPClient.releaseConnPermit(d);
        assertEquals(0, I2PTunnelHTTPClient.activeConnPermits(d));
        assertTrue("still admits after being released too many times",
                   I2PTunnelHTTPClient.tryAcquireConnPermit(d));
        I2PTunnelHTTPClient.releaseConnPermit(d);
    }

    /**
     * A null destination is the fail-open case: it always acquires and is never
     * counted, so a request whose destination hash cannot be worked out is not
     * gated.
     */
    @Test
    public void testNullDestinationAlwaysAcquiresAndNeverCounts() {
        for (int i = 0; i < I2PTunnelHTTPClient.MAX_CONNS_PER_DEST + 4; i++) {
            assertTrue(I2PTunnelHTTPClient.tryAcquireConnPermit(null));
        }
        assertEquals(0, I2PTunnelHTTPClient.activeConnPermits(null));
        I2PTunnelHTTPClient.releaseConnPermit(null);
        assertEquals(0, I2PTunnelHTTPClient.activeConnPermits(null));
    }
}

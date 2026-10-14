package net.i2p.router.transport.ntcp;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Tests whether an idle-close stays attributable to this router.
 *
 * <p>The point of the memory is that a peer whose session this transport closed is
 * not a peer that went away, and only we know the difference. The window is what
 * keeps that true: a close too old says nothing about a loss seen now, and a close
 * that never happened is not an attribution either.
 *
 * @since 0.9.71+
 */
public class FailsafeCloseMemoryTest {

    // ---- failsafeCloseStillRemembered ----

    /** A close inside the window is still attributable. */
    @Test
    public void testInsideWindowIsRemembered() {
        long closed = 1_000_000L;
        assertTrue(NTCPTransport.failsafeCloseStillRemembered(closed, closed, 600_000L));
        assertTrue(NTCPTransport.failsafeCloseStillRemembered(closed, closed + 599_999L, 600_000L));
    }

    /** Exactly at the window edge the close stops being attributable. */
    @Test
    public void testWindowEdgeIsNotRemembered() {
        long closed = 1_000_000L;
        assertFalse(NTCPTransport.failsafeCloseStillRemembered(closed, closed + 600_000L, 600_000L));
        assertFalse(NTCPTransport.failsafeCloseStillRemembered(closed, closed + 900_000L, 600_000L));
    }

    /**
     * A close in the future must not be reported: clock skew is not evidence, and
     * an always-true window would attribute every loss to this router.
     */
    @Test
    public void testFutureCloseIsNotRemembered() {
        long closed = 1_000_000L;
        assertFalse(NTCPTransport.failsafeCloseStillRemembered(closed, closed - 1L, 600_000L));
    }

    /** A never-happened close is not an attribution. */
    @Test
    public void testZeroCloseIsNotRemembered() {
        assertFalse(NTCPTransport.failsafeCloseStillRemembered(0L, 1_000_000L, 600_000L));
        assertFalse(NTCPTransport.failsafeCloseStillRemembered(-1L, 1_000_000L, 600_000L));
    }

    /**
     * A non-positive window must not remember anything, so a caller passing a
     * nonsensical value cannot get attributions it did not ask for.
     */
    @Test
    public void testNonPositiveWindowRemembersNothing() {
        long closed = 1_000_000L;
        assertFalse(NTCPTransport.failsafeCloseStillRemembered(closed, closed + 1L, 0L));
        assertFalse(NTCPTransport.failsafeCloseStillRemembered(closed, closed + 1L, -1L));
    }

    /**
     * The pool asks with a ten-minute window; a close three minutes old must still
     * be attributable when the walk finds the zombie.
     */
    @Test
    public void testTenMinuteWindowCoversThePoolsWalk() {
        long closed = 1_000_000L;
        assertTrue(NTCPTransport.failsafeCloseStillRemembered(closed, closed + 3 * 60_000L,
                                                              10 * 60_000L));
    }
}

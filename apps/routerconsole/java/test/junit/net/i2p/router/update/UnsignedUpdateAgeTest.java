package net.i2p.router.update;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * An unsigned router update is dated only by the Last-Modified header of the
 * server that sent it, so the date has to be checked against the last update
 * accepted, or the same server can hand back an older build and keep handing it
 * back. The decision is a pure static, so it is tested without a running
 * router.
 *
 * @since 0.9.72+
 */
public class UnsignedUpdateAgeTest {

    /** A reference point well past the epoch, so 0 still means "never". */
    private static final long NOW = 1_800_000_000_000L;
    /** One hour. */
    private static final long HOUR = 60 * 60 * 1000L;

    /** Holds no state; each case calls the decision directly. */
    public UnsignedUpdateAgeTest() {}

    /** With nothing accepted yet, anything the server offers is accepted. */
    @Test
    public void firstUpdateIsAccepted() {
        assertTrue(ConsoleUpdateManager.isUnsignedUpdateNewer(NOW - HOUR, 0, NOW));
    }

    /** An update dated after the last one accepted is accepted. */
    @Test
    public void newerUpdateIsAccepted() {
        assertTrue(ConsoleUpdateManager.isUnsignedUpdateNewer(NOW, NOW - HOUR, NOW));
    }

    /**
     * The downgrade: a server that answers with an older Last-Modified than the
     * update already installed is refused, rather than restaging an older build
     * and winding the download clock back for the next pass.
     */
    @Test
    public void olderUpdateIsRefused() {
        assertFalse(ConsoleUpdateManager.isUnsignedUpdateNewer(NOW - HOUR, NOW, NOW));
    }

    /**
     * The same date is not a newer build. Accepting it would let a server
     * re-stage the file the router is already running on every pass.
     */
    @Test
    public void sameDateIsRefused() {
        assertFalse(ConsoleUpdateManager.isUnsignedUpdateNewer(NOW, NOW, NOW));
    }

    /**
     * A server that sends no Last-Modified is dated now, so it is accepted
     * against anything already accepted in the past.
     */
    @Test
    public void missingDateFallsBackToTheCurrentTime() {
        assertTrue(ConsoleUpdateManager.isUnsignedUpdateNewer(0, NOW - HOUR, NOW));
        assertTrue(ConsoleUpdateManager.isUnsignedUpdateNewer(-1, 0, NOW));
    }

    /**
     * A server claiming a build from beyond the current time, against an
     * equally impossible date already accepted, is not believed.
     */
    @Test
    public void missingDateAgainstAFutureAcceptedDateIsRefused() {
        assertFalse(ConsoleUpdateManager.isUnsignedUpdateNewer(0, NOW + HOUR, NOW));
    }
}

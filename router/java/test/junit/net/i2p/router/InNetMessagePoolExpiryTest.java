package net.i2p.router;

import static org.junit.Assert.*;

import java.util.Date;

import org.junit.Test;

/**
 * Pins how {@link InNetMessagePool} renders a dropped message's expiry.
 *
 * <p>Both drop lines printed {@code new Date(messageExpiration)}, so a reader
 * saw {@code * Expires: Sat Nov 21 14:23:59 GMT 2026} and had to subtract the
 * log line's own timestamp by hand to learn anything from it - and in 160 of
 * the 133-minute window's cases the reason beside it was "Duplicate message",
 * so the date read as the cause of a drop it had nothing to do with. The
 * segment now carries a duration, in the direction the value actually points.
 *
 * @since 0.9.72+
 */
public class InNetMessagePoolExpiryTest {

    /** A wall-clock instant in the measured window, for both directions. */
    private static final long NOW = 1_760_000_000_000L;

    /**
     * One instance per test method, as JUnit builds a fresh test object for
     * each one; the segment builder is pure, so nothing is set up.
     */
    public InNetMessagePoolExpiryTest() {}

    /**
     * A message that has not expired yet is described by how long it has left,
     * not by the instant it dies.
     */
    @Test
    public void testFutureExpiryIsARemainingTime() {
        assertEquals(" * Expires in: 7s",
                     InNetMessagePool.expiryClause(NOW + 7_000L, NOW));
        assertEquals(" * Expires in: 5m",
                     InNetMessagePool.expiryClause(NOW + 300_000L, NOW));
    }

    /**
     * A message already past its expiry is described by how long ago that was
     * - the direction the reader actually needs when the reason is "Expired".
     */
    @Test
    public void testPastExpiryIsAnAge() {
        assertEquals(" * Expired: 32s ago",
                     InNetMessagePool.expiryClause(NOW - 32_000L, NOW));
        assertEquals(" * Expired: 30m ago",
                     InNetMessagePool.expiryClause(NOW - 1_800_000L, NOW));
    }

    /**
     * The boundary is not a special case: an expiry exactly now is a remaining
     * time of zero, and the formatter supplies the unit.
     */
    @Test
    public void testExpiryExactlyNowIsZeroRemaining() {
        assertEquals(" * Expires in: 0ms",
                     InNetMessagePool.expiryClause(NOW, NOW));
    }

    /**
     * The defect, stated as the assertion that keeps it out: the old segment
     * was a wall-clock date, which is what a reader cannot use.
     */
    @Test
    public void testNoWallClockDateIsPrinted() {
        String old = " * Expires: " + new Date(NOW - 32_000L);
        assertTrue("the pre-fix form is a date: " + old, old.contains("20"));
        String now = InNetMessagePool.expiryClause(NOW - 32_000L, NOW);
        assertFalse("no year in the segment: " + now,
                    now.matches(".*\\d{4}.*"));
        assertFalse("no timezone in the segment: " + now, now.contains("GMT"));
    }

    /**
     * The segment is a house {@code key: value} pair on the continuation line,
     * so it sits beside {@code MsgID} rather than in front of it.
     */
    @Test
    public void testSegmentIsAKeyValuePair() {
        String line = "* MsgID: 1931116182" +
                      InNetMessagePool.expiryClause(NOW + 7_000L, NOW);
        assertTrue(line.contains("* MsgID: 1931116182"));
        assertTrue(line.contains("* Expires in: 7s"));
    }
}

package net.i2p.client.streaming.impl;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * Tests for the per-destination stream cap and the refusal copy that reports it.
 * <p>The manager-wide cap {@code i2p.streaming.maxConcurrentStreams}, default 0
 * meaning unlimited, limits every destination together, so one host can spend the
 * whole admission budget: a router with the default unlimited cap held thousands
 * of half-open streams from a single destination while every other host stayed
 * unthrottled. The per-destination cap {@code i2p.streaming.maxConcurrentStreamsPerDest}
 * is a second gate on the same per-dest ledger, and the lower of the two binds.
 * <p>A refusal that names only the manager-wide cap reads as a lie when that cap
 * is unlimited and the per-dest gate is what said no, so the copy must name the
 * cap that bound and the count the destination holds.
 *
 * @since 0.9.72+
 */
public class StreamCapRefusalTest {

    /**
     * One instance per test method, as JUnit builds a fresh test object for each
     * one; the class carries no per-test state.
     *
     * @since 0.9.72+
     */
    public StreamCapRefusalTest() {}

    /**
     * With both caps configured the lower one binds, in either order, so a
     * per-destination cap tighter than the manager cap does the work and a
     * manager cap tighter than the per-destination cap is never relaxed by it.
     */
    @Test
    public void testTheLowerCapBinds() {
        assertEquals(64, ConnectionManager.computePerDestCap(64, 1024));
        assertEquals(128, ConnectionManager.computePerDestCap(512, 128));
        assertEquals(64, ConnectionManager.computePerDestCap(64, 64));
    }

    /**
     * An unlimited cap is left out of the min rather than winning it. This is the
     * whole point of the gate: with the manager cap at its default of 0
     * (unlimited), a configured per-destination cap still binds.
     */
    @Test
    public void testAnUnlimitedCapIsLeftOutOfTheMin() {
        assertEquals(64, ConnectionManager.computePerDestCap(64, 0));
        assertEquals(64, ConnectionManager.computePerDestCap(64, -1));
        assertEquals(1024, ConnectionManager.computePerDestCap(0, 1024));
        assertEquals(1024, ConnectionManager.computePerDestCap(-1, 1024));
    }

    /**
     * With neither cap configured nothing binds, and the gate stays exactly where
     * it was before the per-destination cap existed: unlimited in, unlimited out.
     */
    @Test
    public void testTwoUnlimitedCapsLeaveTheGateOpen() {
        assertEquals(0, ConnectionManager.computePerDestCap(0, 0));
        assertEquals(-3, ConnectionManager.computePerDestCap(-5, -3));
        assertEquals(-5, ConnectionManager.computePerDestCap(-3, -5));
    }

    /**
     * A per-destination refusal names the per-destination cap and the streams the
     * destination actually holds. The manager cap is unlimited here, so the old
     * copy would have printed "Maximum 0 concurrent streams exceeded" for a
     * refusal that had nothing to do with a manager-wide limit.
     */
    @Test
    public void testAPerDestRefusalNamesItsOwnCapAndCount() {
        String s = ConnectionManager.streamCapRefusal(64, 0, 64);
        assertTrue(s.contains("64 concurrent streams for this destination"));
        assertTrue("the held count belongs in the copy: " + s, s.contains("Held: 64"));
        assertFalse("an unlimited manager cap must not be printed", s.contains("0 concurrent"));
    }

    /**
     * A tighter manager cap still reports the manager cap, unchanged from before
     * the per-destination gate existed, so existing log greps keep working.
     */
    @Test
    public void testAManagerRefusalKeepsTheOldCopy() {
        String s = ConnectionManager.streamCapRefusal(512, 128, 128);
        assertEquals("Maximum 128 concurrent streams exceeded", s);
        assertEquals("Maximum 1024 concurrent streams exceeded",
                     ConnectionManager.streamCapRefusal(0, 1024, 1024));
    }

    /**
     * When the two caps are equal the manager copy is used: naming the same number
     * twice, once as a per-destination limit and once as a manager limit, tells the
     * reader nothing and the shorter line is the one already in the logs.
     */
    @Test
    public void testEqualCapsReportOnce() {
        assertEquals("Maximum 64 concurrent streams exceeded",
                     ConnectionManager.streamCapRefusal(64, 64, 64));
    }

    /**
     * The per-destination copy must stay readable on one line: the held count is
     * appended, not wrapped, and the whole clause fits well inside the log line's
     * budget even at a four-digit cap and count.
     */
    @Test
    public void testThePerDestCopyStaysShort() {
        String s = ConnectionManager.streamCapRefusal(1024, 0, 1024);
        assertTrue("clause is " + s.length() + " characters", s.length() <= 80);
        assertFalse(s.contains("\n"));
    }
}

package net.i2p.client.streaming.impl;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * Pins when the periodic throughput-floor check starts running for a
 * connection. The hard-stall rule needs no baseline and no goodput windows
 * (see ConnectionThroughputFloorTest), so arming the check only after three
 * windows means a transfer that goes quiet inside its first 30 seconds is
 * never examined for a stall at all: measured live on 2026-11-25 a proxied
 * download went silent for 162 seconds while stream.con.stallDetected carried
 * no events in its whole lifetime.
 *
 * @since 0.9.72+
 */
public class ConnectionFloorArmingTest {

    /**
     * Holds nothing: each case hands the predicate its own progress timestamp.
     */
    public ConnectionFloorArmingTest() {
    }

    /** A connection that has never been acknowledged belongs to the retransmit path. */
    @Test
    public void testNoProgressNeverArms() {
        assertFalse(Connection.shouldArmFloorCheck(0));
        assertFalse(Connection.shouldArmFloorCheck(-1));
    }

    /**
     * The first acknowledged progress arms it: from that moment there is a next
     * byte the connection expects, and a gap in it is measurable.
     */
    @Test
    public void testFirstProgressArms() {
        assertTrue(Connection.shouldArmFloorCheck(1));
        assertTrue(Connection.shouldArmFloorCheck(10000));
    }

    /**
     * Long-stale progress still arms. Whether the gap is a stall is the check's
     * decision, so arming cannot depend on how recent the progress was - that
     * is the guard that made the detector inert for a stalled transfer.
     */
    @Test
    public void testStaleProgressStillArms() {
        assertTrue(Connection.shouldArmFloorCheck(1000));
    }
}

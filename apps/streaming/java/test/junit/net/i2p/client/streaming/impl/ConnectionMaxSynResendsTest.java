package net.i2p.client.streaming.impl;

import static org.junit.Assert.*;

import org.junit.Before;
import org.junit.Test;

/**
 * Pins the static SYN give-up floor and its clamp.
 *
 * <p>The default (12 sends) is deliberately wider than i2pd's 10-send cap: the
 * budget is window-scaled by {@link Connection#computeSynResendBudget(int, int, long)}
 * so the SYN retransmit never tears a connection down before
 * {@link Connection#waitForConnect(int)} reports its accurate error. The clamp
 * keeps adaptive tuning inside the same guarantees.
 *
 * @since 0.9.xx
 */
public class ConnectionMaxSynResendsTest {

    /** Reset the static before each case — adaptive tuning may have moved it. */
    @Before
    public void restoreDefault() {
        Connection.setMaxSynResends(12);
    }

    /** The un-tuned floor is 12 sends. */
    @Test
    public void testDefaultMaxSynResends() {
        assertEquals(12, Connection.getMaxSynResendsStatic());
    }

    /** Tuning may drop the floor to 3 but never below (keep a minimal probe). */
    @Test
    public void testSetFloorClearsLow() {
        Connection.setMaxSynResends(1);
        assertEquals(3, Connection.getMaxSynResendsStatic());
        Connection.setMaxSynResends(8);
        assertEquals(8, Connection.getMaxSynResendsStatic());
    }

    /** Tuning may raise the floor to 16 but never above. */
    @Test
    public void testSetFloorClearsHigh() {
        Connection.setMaxSynResends(50);
        assertEquals(16, Connection.getMaxSynResendsStatic());
        Connection.setMaxSynResends(14);
        assertEquals(14, Connection.getMaxSynResendsStatic());
    }
}

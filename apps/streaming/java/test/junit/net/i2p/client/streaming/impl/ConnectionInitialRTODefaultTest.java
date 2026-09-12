package net.i2p.client.streaming.impl;

import static org.junit.Assert.*;

import org.junit.Before;
import org.junit.Test;

/**
 * Pins the pre-measurement initial RTO default and its clamp.
 *
 * <p>The default must be high enough that a no-evidence SYN retransmit gives a
 * slow-but-alive hidden-service path its full round trip (i2pd's INITIAL_RTO is
 * 9000ms), and the clamp must never let the Tuner push the value outside a
 * sane retransmit window.
 *
 * @since 0.9.xx
 */
public class ConnectionInitialRTODefaultTest {

    /** Reset the static before each case — the Tuner may have moved it. */
    @Before
    public void restoreDefault() {
        ConnectionOptions.setInitialRTO(9000);
    }

    /** The un-tuned default is 9000ms, matching i2pd's INITIAL_RTO. */
    @Test
    public void testDefaultInitialRTO() {
        assertEquals(9000, ConnectionOptions.getInitialRTO());
    }

    /** The Tuner may tighten the value within [500, 30000]. */
    @Test
    public void testSetInitialRTOClearsLow() {
        ConnectionOptions.setInitialRTO(200);
        assertEquals(500, ConnectionOptions.getInitialRTO());
        ConnectionOptions.setInitialRTO(1000);
        assertEquals(1000, ConnectionOptions.getInitialRTO());
    }

    /** The Tuner may widen the value within [500, 30000]. */
    @Test
    public void testSetInitialRTOClearsHigh() {
        ConnectionOptions.setInitialRTO(40000);
        assertEquals(30000, ConnectionOptions.getInitialRTO());
        ConnectionOptions.setInitialRTO(15000);
        assertEquals(15000, ConnectionOptions.getInitialRTO());
    }
}

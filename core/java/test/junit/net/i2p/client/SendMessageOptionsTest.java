package net.i2p.client;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * SendMessageOptions fresh-connection marker: set/get round trip and the
 * router-side static decode of the packed flags field.
 *
 * @since 0.9.72+
 */
public class SendMessageOptionsTest {

    @Test
    public void testFreshConnectionDecode() {
        assertTrue(SendMessageOptions.getFreshConnection(0x0800));
        assertFalse(SendMessageOptions.getFreshConnection(0));
        // unrelated flags must not trip the bit
        assertFalse("lease set bit must not trip the marker", SendMessageOptions.getFreshConnection(0x0100));
        assertFalse("best-effort bit must not trip the marker", SendMessageOptions.getFreshConnection(0x0200));
        assertFalse("guaranteed bit must not trip the marker", SendMessageOptions.getFreshConnection(0x0400));
        // marker survives packing alongside other flags
        assertTrue(SendMessageOptions.getFreshConnection(0x0800 | 0x0100 | 0x000f));
    }

    @Test
    public void testFreshConnectionRoundTrip() {
        SendMessageOptions o = new SendMessageOptions();
        assertFalse(o.getFreshConnection());
        o.setFreshConnection(true);
        assertTrue(o.getFreshConnection());
        assertTrue(SendMessageOptions.getFreshConnection(o.getFlags()));
        o.setFreshConnection(false);
        assertFalse(o.getFreshConnection());
        assertFalse(SendMessageOptions.getFreshConnection(o.getFlags()));
    }
}
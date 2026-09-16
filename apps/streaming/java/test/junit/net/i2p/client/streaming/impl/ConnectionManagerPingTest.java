package net.i2p.client.streaming.impl;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import net.i2p.client.I2PSession;
import org.junit.Test;

/**
 * Tests for {@link ConnectionManager} subsession-aware ping overloads.
 *
 * @since 0.9.72+
 */
public class ConnectionManagerPingTest {

    /** Verify the subsession ping overloads exist and are callable. */
    @Test
    public void testSubsessionPingMethodsExist() throws Exception {
        // Use reflection to verify the methods exist on ConnectionManager
        Class<?> cm = ConnectionManager.class;
        // ping(Destination, I2PSession, int, int, long)
        cm.getMethod("ping", net.i2p.data.Destination.class, I2PSession.class, int.class, int.class, long.class);
        // ping(Destination, I2PSession, int, int, long, byte[])
        cm.getMethod("ping", net.i2p.data.Destination.class, I2PSession.class, int.class, int.class, long.class, byte[].class);
        // If we get here, the methods exist
        assertTrue(true);
    }
}

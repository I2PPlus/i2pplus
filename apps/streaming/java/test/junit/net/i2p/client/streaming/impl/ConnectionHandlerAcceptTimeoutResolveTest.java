package net.i2p.client.streaming.impl;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * Tests the override-vs-config precedence for the SYN accept-queue timeout in
 * {@link ConnectionHandler#resolveAcceptTimeout(int, int)}.
 *
 * <p>The accept timeout is now read from the live configuration on every SYN
 * (so a router.config change applies without a restart).  An explicit override
 * set via {@code setAcceptTimeout(int)} — e.g. per-manager
 * {@code I2PSocketManagerFull.setAcceptTimeout(long)} — takes precedence, and a
 * negative override clears it to fall back to the configured value.
 *
 * @since 0.9.71+
 */
public class ConnectionHandlerAcceptTimeoutResolveTest {

    private static final int CONFIGURED = 60 * 1000;
    private static final int OVERRIDE = 30 * 1000;

    /** No override (default sentinel -1) uses the live configured value. */
    @Test
    public void testDefaultUsesConfigured() {
        assertEquals(CONFIGURED, ConnectionHandler.resolveAcceptTimeout(-1, CONFIGURED));
    }

    /** Explicit override wins over the configured value. */
    @Test
    public void testOverrideWins() {
        assertEquals(OVERRIDE, ConnectionHandler.resolveAcceptTimeout(OVERRIDE, CONFIGURED));
    }

    /** Zero is a valid override (immediate refusal), not "unset". */
    @Test
    public void testZeroOverrideIsSet() {
        assertEquals(0, ConnectionHandler.resolveAcceptTimeout(0, CONFIGURED));
    }

    /** Clearing the override (negative) returns to the live configured value. */
    @Test
    public void testClearReturnsToConfigured() {
        assertEquals(CONFIGURED, ConnectionHandler.resolveAcceptTimeout(-1, CONFIGURED));
    }

    /** Any negative value counts as "not set", per the sentinel contract. */
    @Test
    public void testAnyNegativeIsUnset() {
        assertEquals(CONFIGURED, ConnectionHandler.resolveAcceptTimeout(-100, CONFIGURED));
    }
}

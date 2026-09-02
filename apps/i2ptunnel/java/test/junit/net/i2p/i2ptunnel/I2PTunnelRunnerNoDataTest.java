package net.i2p.i2ptunnel;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * Unit tests for the no-data failure decision behind the "empty response"
 * bug class: a transfer that completes without any upstream bytes must signal
 * its failure callback so the HTTP client proxy can write a 5xx to the browser
 * instead of closing the socket with nothing (which the browser surfaces as
 * {@code NS_ERROR_NET_EMPTY_RESPONSE}).
 *
 * @since 0.9.62
 */
public class I2PTunnelRunnerNoDataTest {

    @Test
    public void testFiresWhenNothingReceived() {
        assertTrue(I2PTunnelRunner.shouldFireNoDataFailure(0L, false));
    }

    @Test
    public void testFiresEvenWithSentBody() {
        // A POST body was sent upstream, but no response bytes came back:
        // that is still an empty/failed transfer.
        assertTrue(I2PTunnelRunner.shouldFireNoDataFailure(0L, false));
    }

    @Test
    public void testDoesNotFireWhenUpstreamBytesReceived() {
        assertFalse(I2PTunnelRunner.shouldFireNoDataFailure(1L, false));
        assertFalse(I2PTunnelRunner.shouldFireNoDataFailure(1024L, false));
    }

    @Test
    public void testDoesNotFireWhenAlreadyHandled() {
        assertFalse(I2PTunnelRunner.shouldFireNoDataFailure(0L, true));
    }

    @Test
    public void testBothGuardConditionsTogether() {
        assertFalse(I2PTunnelRunner.shouldFireNoDataFailure(10L, true));
        assertFalse(I2PTunnelRunner.shouldFireNoDataFailure(0L, true));
    }
}

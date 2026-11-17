package net.i2p.i2ptunnel;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The second, empty I2P stream the proxy can open next to each GET/HEAD is
 * asked for, not assumed.
 * <p>
 * Opening it for every request made the destination see two streams per
 * request, the second idle until the first ended, 16 streams for 8 requests on
 * the lab network, closed 27-73 ms after it opened. That is a per-request
 * fingerprint of the proxy for every eepsite and outproxy, and it doubles what
 * each request costs the server, which ties a handler on an I2P+ server tunnel
 * until the header timeout. The stream is only there to save a cold handshake
 * when a response comes back empty or a body has to be resumed, so it is off
 * unless the tunnel asks for it.
 *
 * @since 0.9.72+
 */
public class SpeculativeWarmTest {

    /** Reads the option; it holds no state. */
    public SpeculativeWarmTest() {}

    /** An unset option leaves the second stream closed. */
    @Test
    public void unsetMeansNoSecondStream() {
        assertFalse(I2PTunnelHTTPClient.parseSpeculativeWarm(""));
        assertFalse(I2PTunnelHTTPClient.parseSpeculativeWarm(null));
    }

    /** Blank and unparseable text leave the second stream closed too. */
    @Test
    public void blankAndGarbageMeanNoSecondStream() {
        assertFalse(I2PTunnelHTTPClient.parseSpeculativeWarm("   "));
        assertFalse(I2PTunnelHTTPClient.parseSpeculativeWarm("yes"));
        assertFalse(I2PTunnelHTTPClient.parseSpeculativeWarm("false"));
    }

    /** Only an explicit true opens the second stream. */
    @Test
    public void explicitTrueAsksForTheSecondStream() {
        assertTrue(I2PTunnelHTTPClient.parseSpeculativeWarm("true"));
        assertTrue(I2PTunnelHTTPClient.parseSpeculativeWarm(" true "));
    }
}

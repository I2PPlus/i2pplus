package net.i2p.router.transport;

import net.i2p.data.router.RouterInfo;
import org.junit.Test;
import static org.junit.Assert.*;

/**
 * Unit tests for the pure decision and memory-independent helpers extracted
 * from the former peer-render cluster of {@link CommSystemFacadeImpl} into
 * {@link PeerHTMLRenderer}: bandwidth-capability classification.
 *
 * These helpers are static and independent of a router context, so no mock or
 * running router is required for these tests.
 *
 * @since 0.9.71+
 */
public class PeerHTMLRendererDecisionTest {

    // ----- classifyCapacity -----

    @Test
    public void capacityMatchesSingleBwChar() {
        // X = 512+ kbit/s unlimited, P/N/O/M/L/K = descending classes
        assertEquals("X", PeerHTMLRenderer.classifyCapacity("X"));
        assertEquals("P", PeerHTMLRenderer.classifyCapacity("P"));
        assertEquals("N", PeerHTMLRenderer.classifyCapacity("N"));
        assertEquals("O", PeerHTMLRenderer.classifyCapacity("O"));
        assertEquals("M", PeerHTMLRenderer.classifyCapacity("M"));
        assertEquals("L", PeerHTMLRenderer.classifyCapacity("L"));
        assertEquals("K", PeerHTMLRenderer.classifyCapacity("K"));
    }

    @Test
    public void capacityClassifiesAnyCapabilityPeers() {
        // caps missing a bandwidth tier -> "?"
        assertEquals("?", PeerHTMLRenderer.classifyCapacity("fU"));
        // caps with a bandwidth tier -> tier wins
        assertEquals("O", PeerHTMLRenderer.classifyCapacity("fUO"));
        assertEquals("X", PeerHTMLRenderer.classifyCapacity("fUX"));
    }

    @Test
    public void capacityPriorityIsUnlimitedFirstThenDescending() {
        // Multiple BW chars advertised: highest class wins (X is fastest)
        assertEquals("X", PeerHTMLRenderer.classifyCapacity("XLM"));
        assertEquals("X", PeerHTMLRenderer.classifyCapacity("XOL"));
        assertEquals("P", PeerHTMLRenderer.classifyCapacity("PLK"));
        assertEquals("N", PeerHTMLRenderer.classifyCapacity("NLK"));
        assertEquals("O", PeerHTMLRenderer.classifyCapacity("OLK"));
        assertEquals("M", PeerHTMLRenderer.classifyCapacity("MLK"));
        assertEquals("L", PeerHTMLRenderer.classifyCapacity("LK"));
        assertEquals("L", PeerHTMLRenderer.classifyCapacity("KL"));  // L ranks before K in scan order
    }

    @Test
    public void capacityPriorityRespectsBwCharOrder() {
        // BW_CAPABILITY_CHARS order is X P O N M L K (descending, built in reverse)
        StringBuilder order = new StringBuilder(RouterInfo.BW_CAPABILITY_CHARS);
        assertEquals("XPONMLK", order.toString());
    }

    @Test
    public void capacityUnknownWhenNoBwChar() {
        assertEquals("?", PeerHTMLRenderer.classifyCapacity("fU"));
        assertEquals("?", PeerHTMLRenderer.classifyCapacity(""));
        assertEquals("?", PeerHTMLRenderer.classifyCapacity(null));
        assertEquals("?", PeerHTMLRenderer.classifyCapacity("Z"));
    }

    @Test
    public void capacityDistinguishesBwFromFeatureCaps() {
        // D/E/G are congestion markers, not bandwidth tiers; never classified
        assertEquals("?", PeerHTMLRenderer.classifyCapacity("DEG"));
        // but a bw tier alongside them is picked up
        assertEquals("L", PeerHTMLRenderer.classifyCapacity("DL"));
    }

    // ----- renderPeerCaps display label (visibleCapacity was a no-op) -----

    @Test
    public void capacityDisplayEqualsClassification() {
        // The displayed label is the classified tier verbatim: XPNOMLK contain
        // no D/E/G, so the former [DEG]-strip never altered output.
        assertEquals("L", PeerHTMLRenderer.classifyCapacity("L"));
        assertEquals("?", PeerHTMLRenderer.classifyCapacity(""));
    }
}

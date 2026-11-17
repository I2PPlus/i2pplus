package net.i2p.router.web.helpers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The address the ban table takes out of a ban cause's parentheses.
 * <p>
 * A "Blocklist (1.2.3.4:5678)" or "Sybil Analysis (...)" cause carries an
 * address there, and the renderer cut the text between the first pair of
 * parentheses out of the cause and wrote it into the table's IP column. The
 * same parentheses have also carried a peer's own version string, which is up
 * to 255 bytes of whatever it chose to write - the console's CSP stops a script
 * in that text but not markup, and the text was written out raw, so a crafted
 * RouterInfo put its own markup into the console origin's ban table. Only text
 * that parses as an address is used now.
 *
 * @since 0.9.72+
 */
public class BanlistRendererCauseAddressTest {

    /** Runs the address decisions; it holds no state. */
    public BanlistRendererCauseAddressTest() {}

    /** A plain IPv4 with a port is the value the cause is there to carry. */
    @Test
    public void anIPv4WithAPortIsUsed() {
        assertEquals("1.2.3.4:5678", BanlistRenderer.causeAddress("Blocklist (1.2.3.4:5678)"));
    }

    /** A bare IPv4 is used as well. */
    @Test
    public void aBareIPv4IsUsed() {
        assertEquals("1.2.3.4", BanlistRenderer.causeAddress("Blocklist (1.2.3.4)"));
    }

    /** Both IPv6 forms the address helpers accept are used. */
    @Test
    public void anIPv6IsUsed() {
        assertEquals("2001:db8::1", BanlistRenderer.causeAddress("Blocklist (2001:db8::1)"));
        assertEquals("[2001:db8::1]:5678", BanlistRenderer.causeAddress("Blocklist ([2001:db8::1]:5678)"));
    }

    /**
     * The version a peer publishes for itself is not an address, and this is
     * the payload from the finding: the markup stops at the first parenthesis,
     * which is what the old code cut out and rendered.
     */
    @Test
    public void aPeerVersionIsNotAnAddress() {
        String cause = "Sybil Analysis (0.9.71.<img src=x onerror=alert(1)>) (75 points)";
        assertNull(cause, BanlistRenderer.causeAddress(cause));
    }

    /** The points tally the cause now carries is not an address either. */
    @Test
    public void aPointsTallyIsNotAnAddress() {
        assertNull(BanlistRenderer.causeAddress("Sybil Analysis (75 points)"));
        assertNull(BanlistRenderer.causeAddress("Sybil Analysis (48.75 points)"));
    }

    /** A port that is not a port makes the whole value unusable. */
    @Test
    public void aBadPortMakesTheValueUnusable() {
        assertNull(BanlistRenderer.causeAddress("Blocklist (1.2.3.4:70000)"));
        assertNull(BanlistRenderer.causeAddress("Blocklist (1.2.3.4:http)"));
    }

    /** Text that is not an address at all is not used. */
    @Test
    public void arbitraryTextIsNotAnAddress() {
        assertNull(BanlistRenderer.causeAddress("Blocklist (unknown)"));
        assertNull(BanlistRenderer.causeAddress("Blocklist ()"));
        assertNull(BanlistRenderer.causeAddress("Blocklist no parentheses"));
        assertNull(BanlistRenderer.causeAddress(null));
    }

    /**
     * The address has to follow the label. A cause that opens with the
     * parentheses has nothing to take out of them - the old code took the value
     * from position 0 as well.
     */
    @Test
    public void theAddressFollowsTheLabel() {
        assertNull(BanlistRenderer.causeAddress("(1.2.3.4) Blocklist"));
    }

    /** The address test itself, at the boundaries of what is an address. */
    @Test
    public void theAddressTestAcceptsAddressesOnly() {
        assertTrue(BanlistRenderer.isAddress("1.2.3.4"));
        assertTrue(BanlistRenderer.isAddress("1.2.3.4:1"));
        assertTrue(BanlistRenderer.isAddress("1.2.3.4:65535"));
        assertTrue(BanlistRenderer.isAddress("[::1]:5678"));
        assertFalse(BanlistRenderer.isAddress("0.9.71.<img src=x>"));
        assertFalse(BanlistRenderer.isAddress("1.2.3.4:65536"));
        assertFalse(BanlistRenderer.isAddress("1.2.3.4:0"));
        assertFalse(BanlistRenderer.isAddress("999.2.3.4"));
        assertFalse(BanlistRenderer.isAddress(""));
        assertFalse(BanlistRenderer.isAddress(null));
    }
}

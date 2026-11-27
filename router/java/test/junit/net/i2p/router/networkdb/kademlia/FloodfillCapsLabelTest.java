package net.i2p.router.networkdb.kademlia;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Locale;
import java.util.Properties;

import org.junit.Test;

import net.i2p.data.router.RouterInfo;

/**
 * The capability string NetDb validation reads, and the floodfill flag inside it.
 *
 * <p>A capability letter is case-significant: the floodfill flag is the
 * lower-case 'f', and every other capability the router reads is written upper
 * case, so an upper-case 'F' names nothing. Validation used to fold the peer's
 * capability string to upper case before handing it to the checks, so the
 * floodfill test was false for every peer - and a floodfill banned by the XG or
 * the country rule was logged, and written into the ban's own cause, as an
 * ordinary router. The console's ban table marks floodfills by reading that
 * cause, so the mislabel reached the operator's screen as well as the log.
 *
 * @since 0.9.72+
 */
public class FloodfillCapsLabelTest {

    /** Builds a RouterInfo carrying one capability option. */
    public FloodfillCapsLabelTest() {}

    /**
     * A RouterInfo carrying only a capabilities option.
     *
     * @param caps the capabilities option value, a string of single-letter caps
     * @return a key-less RouterInfo for the checks to read
     */
    private static RouterInfo ri(String caps) {
        Properties opts = new Properties();
        opts.setProperty(RouterInfo.PROP_CAPABILITIES, caps);
        RouterInfo ri = new RouterInfo();
        ri.setOptions(opts);
        return ri;
    }

    /**
     * The string the checks are given is the peer's own, letter for letter - the
     * floodfill flag only survives if it is.
     */
    @Test
    public void testCapsAreReadAsThePeerWroteThem() {
        assertEquals("PRf", KademliaNetworkDatabaseFacade.capsAsWritten(ri("PRf")));
        assertEquals("PRSfKLU", KademliaNetworkDatabaseFacade.capsAsWritten(ri("PRSfKLU")));
        assertEquals("PRF", KademliaNetworkDatabaseFacade.capsAsWritten(ri("PRF")));
    }

    /**
     * A peer that declared no capabilities is the empty string, never null - the
     * callers print the string in the ban log as well as test it.
     */
    @Test
    public void testNoCapsIsAnEmptyString() {
        assertEquals("", KademliaNetworkDatabaseFacade.capsAsWritten(new RouterInfo()));
        assertEquals("", KademliaNetworkDatabaseFacade.capsAsWritten(null));
    }

    /**
     * The floodfill flag is the lower-case 'f' anywhere in the string, and an
     * upper-case 'F' names no capability the router reads, so it must not read
     * as a floodfill.
     */
    @Test
    public void testFloodfillFlagIsTheLowercaseF() {
        assertTrue(KademliaNetworkDatabaseFacade.isFloodfillCaps("PRf"));
        assertTrue(KademliaNetworkDatabaseFacade.isFloodfillCaps("PRSfKLU"));
        assertFalse(KademliaNetworkDatabaseFacade.isFloodfillCaps("PRF"));
        assertFalse(KademliaNetworkDatabaseFacade.isFloodfillCaps("PRSFKLU"));
        assertFalse(KademliaNetworkDatabaseFacade.isFloodfillCaps(""));
        assertFalse(KademliaNetworkDatabaseFacade.isFloodfillCaps(null));
    }

    /**
     * The trap that made the flag invisible: an upper-case fold turns the
     * floodfill's 'f' into an 'F' that names nothing, leaving a floodfill
     * indistinguishable from a router that declared no floodfill at all, which
     * is why the checks are handed the string as written.
     */
    @Test
    public void testFoldedCapsLoseTheFloodfillFlag() {
        String caps = KademliaNetworkDatabaseFacade.capsAsWritten(ri("PRf"));
        assertTrue(KademliaNetworkDatabaseFacade.isFloodfillCaps(caps));
        String foldedUS = caps.toUpperCase(Locale.US);
        String foldedTR = caps.toUpperCase(new Locale("tr", "TR"));
        assertFalse(KademliaNetworkDatabaseFacade.isFloodfillCaps(foldedUS));
        assertFalse(KademliaNetworkDatabaseFacade.isFloodfillCaps(foldedTR));
    }
}

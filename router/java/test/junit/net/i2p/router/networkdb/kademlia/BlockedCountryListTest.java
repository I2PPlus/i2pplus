package net.i2p.router.networkdb.kademlia;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Locale;
import java.util.Set;

import org.junit.Test;

/**
 * The country codes of router.blockCountries, as the set the NetDb blocking
 * check looks a peer's country up in.
 *
 * <p>The GeoIP answer that is tested against the set is lower case, so the
 * configured codes are folded to lower case to meet it - and a fold that follows
 * the JVM's default locale changes only this side of the comparison. Under a
 * Turkish default locale an operator's "IL" becomes a dotless ı, the entry
 * matches no country the lookup can return, and the ban for Israel reads as if
 * it were in force while blocking nothing.
 *
 * @since 0.9.72+
 */
public class BlockedCountryListTest {

    /** The parsing is a static one over the property; no router needed. */
    public BlockedCountryListTest() {}

    /**
     * The codes are folded and the spaces around the separators dropped, so the
     * set holds what the lookup can answer.
     */
    @Test
    public void testCodesAreFoldedAndTrimmed() {
        Set<String> codes = KademliaNetworkDatabaseFacade.parseCountryList("US, IL");
        assertEquals(2, codes.size());
        assertTrue(codes.contains("us"));
        assertTrue(codes.contains("il"));
    }

    /**
     * Empty fields from a sloppy list - a trailing comma, two commas together -
     * are dropped rather than kept as a code nothing matches.
     */
    @Test
    public void testEmptyFieldsAreDropped() {
        Set<String> codes = KademliaNetworkDatabaseFacade.parseCountryList("US,,IL,");
        assertEquals(2, codes.size());
        assertFalse(codes.contains(""));
        assertTrue(codes.contains("il"));
    }

    /**
     * No list configured is an empty set, not a set holding the empty string,
     * and never null - the caller tests the set for every RouterInfo it stores.
     */
    @Test
    public void testNoListIsAnEmptySet() {
        assertTrue(KademliaNetworkDatabaseFacade.parseCountryList("").isEmpty());
        assertTrue(KademliaNetworkDatabaseFacade.parseCountryList(null).isEmpty());
    }

    /**
     * Under a Turkish default locale the codes have to fold to the ASCII the
     * lookup answers with, or the configured ban matches nothing at all.
     */
    @Test
    public void testCodesFoldTheSameUnderTurkishLocale() {
        Locale saved = Locale.getDefault();
        try {
            Locale.setDefault(new Locale("tr", "TR"));
            Set<String> codes = KademliaNetworkDatabaseFacade.parseCountryList("IL, FI, US");
            assertEquals(3, codes.size());
            assertTrue(codes.contains("il"));
            assertTrue(codes.contains("fi"));
            assertTrue(codes.contains("us"));
        } finally {
            Locale.setDefault(saved);
        }
    }
}

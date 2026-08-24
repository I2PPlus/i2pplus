package org.klomp.snark.web;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * Tests for I2PSnarkServlet.prettyAnnounce: known tracker announce
 * rewriting. Matching must be literal - the base32 keys contain dots that
 * a regex implementation would treat as metacharacters.
 *
 * @since 0.9.71+
 */
public class PrettyAnnounceTest {

    private static String announceFor(String host) {
        return "http://" + host + "/announce";
    }

    @Test
    public void testUnknownTrackerPassesThrough() {
        String in = "http://some-unknown-tracker.example.i2p/announce";
        assertEquals(in, I2PSnarkServlet.prettyAnnounce(in));
    }

    @Test
    public void testEveryKnownEntryRewrites() {
        // pin each table entry: the key inside an announce URL becomes its name
        for (String[] tracker : I2PSnarkServlet.KNOWN_TRACKERS) {
            String in = announceFor(tracker[0]);
            String out = I2PSnarkServlet.prettyAnnounce(in);
            assertEquals(announceFor(tracker[1]), out);
        }
    }

    @Test
    public void testMultipleTrackersRewritten() {
        // two known keys in one string (multi-tier announce list flattened)
        String k0 = I2PSnarkServlet.KNOWN_TRACKERS[3][0];
        String k1 = I2PSnarkServlet.KNOWN_TRACKERS[4][0];
        String in = announceFor(k0) + "\n" + announceFor(k1);
        String out = I2PSnarkServlet.prettyAnnounce(in);
        assertFalse(out.contains(k0));
        assertFalse(out.contains(k1));
        assertTrue(out.contains(I2PSnarkServlet.KNOWN_TRACKERS[3][1]));
        assertTrue(out.contains(I2PSnarkServlet.KNOWN_TRACKERS[4][1]));
    }

    @Test
    public void testLiteralDotsOnlyMatch() {
        // regex semantics would match any char for '.'; literal must not
        String bogus = announceFor(I2PSnarkServlet.KNOWN_TRACKERS[3][0].replace(".", "X"));
        assertEquals(bogus, I2PSnarkServlet.prettyAnnounce(bogus));
    }

    @Test
    public void testHTMLStripped() {
        String out = I2PSnarkServlet.prettyAnnounce("http://a<b>host</b>.i2p/announce");
        assertFalse(out.contains("<b>"));
    }

    @Test
    public void testTableKeysUnique() {
        // duplicate keys would double-replace and corrupt output
        // (display names intentionally repeat: three postman keys -> one name)
        for (int i = 0; i < I2PSnarkServlet.KNOWN_TRACKERS.length; i++) {
            for (int j = i + 1; j < I2PSnarkServlet.KNOWN_TRACKERS.length; j++) {
                assertNotEquals(I2PSnarkServlet.KNOWN_TRACKERS[i][0],
                                I2PSnarkServlet.KNOWN_TRACKERS[j][0]);
            }
        }
    }
}

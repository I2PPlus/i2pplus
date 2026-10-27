package org.klomp.snark.web;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

/**
 * Tests for the tracker display helpers of the torrent details page: the label
 * a tracker pill shows, the link it carries, the fragment that composes them,
 * and the split of an announce list into the I2P trackers that are shown and
 * the non-I2P ones that are only counted.
 *
 * @since 0.9.72+
 */
public class TrackerDisplayTest {

    /** A real b32 address of a known tracker, used to pin the "leave it alone" rule. */
    private static final String B32 = "nfrjvknwcw47itotkzmk6mdlxmxfxsxhbhlr5ozhlsuavcogv4hq";

    /** A base-64 destination long enough (516 chars) to need a b32 conversion. */
    private static final String B64 = longString(516, 'A');

    /**
     * Runs the checks on the tracker display helpers.
     */
    public TrackerDisplayTest() {
    }

    /**
     * A string of the requested length, for host-length thresholds.
     *
     * @param len the length wanted
     * @param c the character to fill with
     * @return a string of exactly len characters
     */
    private static String longString(int len, char c) {
        StringBuilder buf = new StringBuilder(len);
        for (int i = 0; i < len; i++) {buf.append(c);}
        return buf.toString();
    }

    /**
     * Count non-overlapping occurrences of a tag.
     *
     * @param s the html to count in
     * @param tag the exact text to count, e.g. "&lt;/a&gt;"
     * @return how many times it occurs
     */
    private static int count(String s, String tag) {
        int n = 0;
        for (int i = s.indexOf(tag); i >= 0; i = s.indexOf(tag, i + tag.length())) {n++;}
        return n;
    }

    // ---- labels ----

    /** An I2P host loses its scheme, path, port and boilerplate prefix. */
    @Test
    public void testShortensI2PPrefixes() {
        assertEquals("postman", I2PSnarkServlet.shortTrackerLabel("http://tracker2.postman.i2p/announce.php"));
        assertEquals("skank", I2PSnarkServlet.shortTrackerLabel("http://opentracker.skank.i2p/a"));
        assertEquals("dg2", I2PSnarkServlet.shortTrackerLabel("http://opentracker.dg2.i2p:6969/announce"));
        assertEquals("insulaocculta", I2PSnarkServlet.shortTrackerLabel("http://tracker.insulaocculta.i2p/announce"));
        assertEquals("sigmatracker", I2PSnarkServlet.shortTrackerLabel("http://sigmatracker.i2p/announce"));
        assertEquals("lyoko", I2PSnarkServlet.shortTrackerLabel("http://lyoko.i2p/announce"));
    }

    /**
     * The prefix strip has to run before the "UDP " label is added, and the
     * port has to go: the shipped order left "UDP opentracker.dg2" where the
     * http trackers showed "dg2".
     */
    @Test
    public void testUdpLabelShortensLikeHttp() {
        assertEquals("UDP dg2", I2PSnarkServlet.shortTrackerLabel("udp://opentracker.dg2.i2p:6969/announce"));
        assertEquals("UDP insulaocculta",
                     I2PSnarkServlet.shortTrackerLabel("udp://tracker.insulaocculta.i2p:6969/announce"));
        assertEquals("UDP skank", I2PSnarkServlet.shortTrackerLabel("udp://opentracker.skank.i2p/announce"));
    }

    /**
     * A clearnet tracker keeps its full host, so it is visibly not one of the
     * I2P pills, and no prefix of it is trimmed away.
     */
    @Test
    public void testClearnetLabelNotShortened() {
        assertEquals("tracker.example.com", I2PSnarkServlet.shortTrackerLabel("http://tracker.example.com/announce"));
        assertEquals("UDP tracker.coppersurfer.tk",
                     I2PSnarkServlet.shortTrackerLabel("udp://tracker.coppersurfer.tk:6969/announce"));
        assertEquals("tracker.opentrackr.org",
                     I2PSnarkServlet.shortTrackerLabel("https://tracker.opentrackr.org:1337/announce"));
    }

    /** A b32 address is already unique, so it is shown whole, not stripped to a suffix. */
    @Test
    public void testB32HostLeftAlone() {
        assertEquals(B32 + ".b32.i2p", I2PSnarkServlet.shortTrackerLabel("http://" + B32 + ".b32.i2p/announce.php"));
    }

    /** A host over 67 characters is cut to 40, an ellipsis, then the last 8. */
    @Test
    public void testLongLabelTruncated() {
        String label = I2PSnarkServlet.shortTrackerLabel("http://" + longString(70, 'a') + ".i2p/announce");
        assertTrue(label, label.startsWith(longString(40, 'a')));
        // the joining entity must survive escaping, or the page shows "&hellip;"
        assertTrue(label, label.contains("&hellip;"));
        assertFalse(label, label.contains("&amp;hellip;"));
        assertTrue(label, label.endsWith(longString(8, 'a')));
    }

    /** A null or empty announce URL gives an empty label, not a crash. */
    @Test
    public void testEmptyLabel() {
        assertEquals("", I2PSnarkServlet.shortTrackerLabel(null));
        assertEquals("", I2PSnarkServlet.shortTrackerLabel(""));
    }

    /** The label is html, so a hostile announce URL has to arrive escaped. */
    @Test
    public void testLabelEscaped() {
        assertEquals("x&lt;i&gt;", I2PSnarkServlet.shortTrackerLabel("http://x<i>.i2p/announce"));
        String label = I2PSnarkServlet.shortTrackerLabel("http://tracker.a<b.i2p/announce");
        assertFalse(label, label.contains("<"));
    }

    /** A scheme-less announce still loses its port and path. */
    @Test
    public void testNoScheme() {
        assertEquals("tracker.example.com", I2PSnarkServlet.shortTrackerLabel("tracker.example.com:80/announce"));
    }

    // ---- links ----

    /** An I2P tracker links to its home page: path dropped, port kept. */
    @Test
    public void testI2PHomes() {
        assertEquals("http://tracker2.postman.i2p/", I2PSnarkServlet.trackerHomeHref("http://tracker2.postman.i2p/announce.php"));
        assertEquals("http://opentracker.dg2.i2p:6969/", I2PSnarkServlet.trackerHomeHref("udp://opentracker.dg2.i2p:6969/announce"));
        assertEquals("http://skank.i2p/", I2PSnarkServlet.trackerHomeHref("http://skank.i2p"));
    }

    /**
     * The console must not hand the browser a clearnet url for a torrent that
     * is only announced to over I2P, so a non-I2P host gets no anchor at all.
     */
    @Test
    public void testClearnetGetsNoLink() {
        assertNull(I2PSnarkServlet.trackerHomeHref("http://tracker.example.com/announce"));
        assertNull(I2PSnarkServlet.trackerHomeHref("udp://tracker.coppersurfer.tk:6969/announce"));
        assertNull(I2PSnarkServlet.trackerHomeHref("ftp://skank.i2p/announce"));
        assertNull(I2PSnarkServlet.trackerHomeHref(null));
    }

    /** A full base-64 destination is the caller's b32 conversion, not a link. */
    @Test
    public void testB64DestDefersToB32() {
        assertNull(I2PSnarkServlet.trackerHomeHref("udp://" + B64 + ":6969/announce"));
    }

    // ---- fragments ----

    /**
     * The shipped code opened an anchor for a udp tracker and closed it only
     * when a details-page link existed, so the anchor swallowed every tracker
     * pill that followed it in the row.
     */
    @Test
    public void testUdpFragmentClosesItsAnchor() {
        String f = I2PSnarkServlet.trackerFragment("udp://opentracker.dg2.i2p:6969/announce",
                                                   null, "http://opentracker.dg2.i2p:6969/");
        assertEquals("<a href=\"http://opentracker.dg2.i2p:6969/\" target=_blank>UDP dg2</a>", f);
    }

    /** With nothing to link to, the label stands alone, with no tags at all. */
    @Test
    public void testUnlinkedFragmentHasNoTags() {
        assertEquals("tracker.example.com",
                     I2PSnarkServlet.trackerFragment("http://tracker.example.com/announce", null, null));
    }

    /** A details-page opening tag wins over the home page, and is closed once. */
    @Test
    public void testDetailsTagWins() {
        String f = I2PSnarkServlet.trackerFragment("http://tracker2.postman.i2p/announce.php",
                                                   "<a class=tLink href=\"x\">", "http://tracker2.postman.i2p/");
        assertEquals("<a class=tLink href=\"x\">postman</a>", f);
    }

    /** Every announce, linked or not, has to leave the tags balanced. */
    @Test
    public void testFragmentsAlwaysBalance() {
        List<String> announces = Arrays.asList("http://tracker2.postman.i2p/announce.php",
                                               "udp://opentracker.dg2.i2p:6969/announce",
                                               "udp://tracker.coppersurfer.tk:6969/announce",
                                               "http://tracker.example.com/announce",
                                               "udp://" + B64 + ":6969/announce",
                                               "http://" + B32 + ".b32.i2p/announce.php",
                                               "", "ftp://skank.i2p/announce");
        for (String a : announces) {
            String f = I2PSnarkServlet.trackerFragment(a, null, I2PSnarkServlet.trackerHomeHref(a));
            assertEquals(a, count(f, "<a"), count(f, "</a>"));
            assertEquals(a, 0, count(f, "<span"));
        }
    }

    // ---- announce list split ----

    /** Tiers repeat trackers, so the row shows each one once, in first-seen order. */
    @Test
    public void testSplitsAndDedupes() {
        List<List<String>> alist = Arrays.asList(
                Arrays.asList("http://tracker2.postman.i2p/announce.php", "http://tracker.example.com/announce"),
                Arrays.asList("http://tracker2.postman.i2p/announce.php", "http://opentracker.skank.i2p/a"));
        assertEquals(Arrays.asList("http://tracker2.postman.i2p/announce.php", "http://opentracker.skank.i2p/a"),
                     I2PSnarkServlet.i2pTrackers(alist, false));
        assertEquals(1, I2PSnarkServlet.nonI2PTrackerCount(alist, false));
    }

    /** A udp tracker is only usable, and so only shown, when the config allows it. */
    @Test
    public void testUdpGatedByConfig() {
        List<List<String>> alist = Arrays.asList(Arrays.asList("udp://opentracker.dg2.i2p:6969/announce"));
        assertEquals(Collections.emptyList(), I2PSnarkServlet.i2pTrackers(alist, false));
        assertEquals(1, I2PSnarkServlet.nonI2PTrackerCount(alist, false));
        assertEquals(alist.get(0), I2PSnarkServlet.i2pTrackers(alist, true));
        assertEquals(0, I2PSnarkServlet.nonI2PTrackerCount(alist, true));
    }

    /** A null announce list, and a null tier inside one, are not crashes. */
    @Test
    public void testNullListAndTiers() {
        assertEquals(Collections.emptyList(), I2PSnarkServlet.i2pTrackers(null, true));
        assertEquals(0, I2PSnarkServlet.nonI2PTrackerCount(null, true));
        List<List<String>> alist = new ArrayList<>();
        alist.add(null);
        alist.add(Collections.singletonList("http://opentracker.skank.i2p/a"));
        assertEquals(Collections.singletonList("http://opentracker.skank.i2p/a"),
                     I2PSnarkServlet.i2pTrackers(alist, true));
        assertEquals(0, I2PSnarkServlet.nonI2PTrackerCount(alist, true));
    }

    /** The two halves of the split have to account for every distinct announce. */
    @Test
    public void testPartitionCoversEveryEntry() {
        List<List<String>> alist = Arrays.asList(
                Arrays.asList("http://tracker2.postman.i2p/announce.php", "http://tracker.example.com/announce"),
                Arrays.asList("udp://opentracker.dg2.i2p:6969/announce", "udp://tracker.coppersurfer.tk:6969/announce"),
                Collections.singletonList("http://tracker.example.com/announce"));
        for (boolean udp : new boolean[] {false, true}) {
            int total = I2PSnarkServlet.i2pTrackers(alist, udp).size() + I2PSnarkServlet.nonI2PTrackerCount(alist, udp);
            // 4 distinct announces, one of them repeated in a second tier
            assertEquals("udp=" + udp, 4, total);
        }
    }

    /** A torrent with no announce list at all is not a crash, and shows nothing. */
    @Test
    public void testEmptyTiers() {
        List<List<String>> alist = Collections.emptyList();
        assertEquals(Collections.emptyList(), I2PSnarkServlet.i2pTrackers(alist, true));
        assertEquals(0, I2PSnarkServlet.nonI2PTrackerCount(alist, true));
    }

    // ---- grouping the pills by host, and saying which transport is in use ----

    /**
     * The two URLs a tracker that answers on both transports is listed as share
     * one host, so they group into one pill.
     */
    @Test
    public void testHostGroupsTheTwoTransports() {
        assertEquals("tracker2.postman.i2p",
                     I2PSnarkServlet.trackerHost("http://tracker2.postman.i2p/announce.php"));
        assertEquals(I2PSnarkServlet.trackerHost("http://tracker2.postman.i2p/announce.php"),
                     I2PSnarkServlet.trackerHost("udp://tracker2.postman.i2p:6969/announce"));
        assertEquals(I2PSnarkServlet.trackerHost("http://opentracker.dg2.i2p/announce"),
                     I2PSnarkServlet.trackerHost("udp://OPENTRACKER.dg2.i2p:6969/"));
        assertEquals(B32 + ".b32.i2p", I2PSnarkServlet.trackerHost("udp://" + B32 + ".b32.i2p:6969/a"));
    }

    /**
     * A URL with no scheme is keyed as given, lower-cased, since there is no
     * host to take out of it without guessing.
     */
    @Test
    public void testHostOfNoScheme() {
        assertEquals("tracker.example.com:80/announce",
                     I2PSnarkServlet.trackerHost("Tracker.Example.com:80/announce"));
    }

    /**
     * The pill for a host links the URL of the transport we are announcing over,
     * so the link and the badge agree, and falls back to the first of the group
     * when the host is listed only over the other one.
     */
    @Test
    public void testPicksTheTransportInUse() {
        List<String> both = Arrays.asList("http://tracker2.postman.i2p/announce.php",
                                          "udp://tracker2.postman.i2p:6969/announce");
        assertEquals(both.get(1), I2PSnarkServlet.pickTrackerURL(both, true));
        assertEquals(both.get(0), I2PSnarkServlet.pickTrackerURL(both, false));
        List<String> shuffled = Arrays.asList(both.get(1), both.get(0));
        assertEquals(shuffled.get(0), I2PSnarkServlet.pickTrackerURL(shuffled, true));
        // listed over one transport only: that is what is used, badge or no badge
        List<String> httpOnly = Collections.singletonList("http://tracker2.postman.i2p/announce.php");
        assertEquals(httpOnly.get(0), I2PSnarkServlet.pickTrackerURL(httpOnly, true));
        assertEquals(httpOnly.get(0), I2PSnarkServlet.pickTrackerURL(httpOnly, false));
        List<String> withNulls = Arrays.asList(null, "udp://opentracker.dg2.i2p:6969/announce", null);
        assertEquals("udp://opentracker.dg2.i2p:6969/announce", I2PSnarkServlet.pickTrackerURL(withNulls, true));
        assertEquals("udp://opentracker.dg2.i2p:6969/announce", I2PSnarkServlet.pickTrackerURL(withNulls, false));
    }

    /**
     * A pill that carries its own transport badge asks the label not to repeat
     * it, so the pill reads "dg2 UDP" and not "UDP dg2 UDP".
     */
    @Test
    public void testLabelWithoutTheTransport() {
        assertEquals("UDP dg2", I2PSnarkServlet.shortTrackerLabel("udp://opentracker.dg2.i2p:6969/announce"));
        assertEquals("dg2", I2PSnarkServlet.shortTrackerLabel("udp://opentracker.dg2.i2p:6969/announce", false));
        assertEquals("postman", I2PSnarkServlet.shortTrackerLabel("http://tracker2.postman.i2p/announce.php", false));
        // a long host is truncated the same way, minus the prefix
        String with = I2PSnarkServlet.shortTrackerLabel("udp://" + longString(70, 'a') + ".i2p/announce");
        String without = I2PSnarkServlet.shortTrackerLabel("udp://" + longString(70, 'a') + ".i2p/announce", false);
        assertTrue(with, with.startsWith("UDP "));
        assertFalse(without, without.startsWith("UDP "));
        assertEquals(with.substring(4), without);
    }

    /**
     * The fragment keeps its tags balanced with the transport left out of the
     * label, which is what the grouped pills ask for.
     */
    @Test
    public void testFragmentWithoutTheTransport() {
        String f = I2PSnarkServlet.trackerFragment("udp://opentracker.dg2.i2p:6969/announce", null,
                                                   "http://opentracker.dg2.i2p:6969/", false);
        assertEquals("<a href=\"http://opentracker.dg2.i2p:6969/\" target=_blank>dg2</a>", f);
        assertEquals(1, count(f, "<a"));
        assertEquals(1, count(f, "</a>"));
        String plain = I2PSnarkServlet.trackerFragment("udp://" + B32 + ".b32.i2p:6969/announce", null, null, false);
        assertEquals(B32 + ".b32.i2p", plain);
        assertEquals(0, count(plain, "<a"));
        assertEquals(0, count(plain, "</a>"));
    }
}

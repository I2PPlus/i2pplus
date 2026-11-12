package org.klomp.snark;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * Tests for the one shape every snark fetch log uses to name a request: the
 * tracker's host and the torrent's short info hash. An announce URL runs to 760
 * characters, 528 of them the base64 destination we send in ip=, and the fetch
 * path logged it whole - a DEBUG line no reader can scan, compare or grep, so
 * neither half of the URL is ever shown as written.
 *
 * <p>The info hash, peer id and destination below are random, not a real
 * torrent's; only their shapes and lengths are pinned.
 *
 * @since 0.9.72+
 */
public class FetchLogFormatTest {

    /**
     * Test instance; every test calls a static formatter method, so the instance
     * carries no state.
     */
    public FetchLogFormatTest() {
    }

    /** A tracker announce as the open trackers take it. */
    private static final String ANNOUNCE =
            "http://opentracker.bt.i2p/a?info_hash=%b1%d2%b8%e9%ddW%cc%5c%2ab%5f%b8%f3"
            + "k%f7%0a%03%9c%3d&peer_id=-Z41ab9cd1234-00000000123456789012";

    /** Postman's b32 announce URL, which the fetch rewrites to a hostname. */
    private static final String POSTMAN =
            "http://ahsplxkbhemefwvvml7qovzl5a2b5xo5i7lyai7ntdunvcyfdtna.b32.i2p"
            + "/announce?info_hash=%01%02%03%04";

    /** A full announce, shaped like the ones the log carried. */
    private static final String FULL_ANNOUNCE =
            "http://opentracker.bt.i2p/a?info_hash=%76%7c%bd%d2%79%77%74%6e%07%d5%7a%2b%f3%0b"
            + "%b8%81%c0%fb%8a%e1&peer_id=%23%87%38%6f%69%28%8e%95%2b%c3%b9%7a%a1%da%74%99%3d%e"
            + "6%0d%2b&port=6881&ip=4u2l_tQeldZ22ck5POPxpxMC6ZbXANKt-1WxoS3E4D2hYPZMp5qw-fR3i5u"
            + "4--463iY7G3GnEPguoDXxekdM8ad3vi7a~10VaAsJVaEha~n-n7NrmsDG2P5d69uZnFXLCOZH6vb2MAZ"
            + "ZcXHYVFlJ7W7i9WNwpzU3PdDrUTVbj2-eeT9w20KKGOp0m2uHLNHDkUbm~QhT65dzCpMD4XYBJC8sbAn"
            + "qZuIMUN81mS2E_qyLf5BjFti34pq9seU6zk78U1_uk6dXIsSk22SMqv8oZjUNF4t5GC02zuBp~wW7xew"
            + "_QTY0nmx_ixwDRsumr1nZ~jRAgGhH7jXowbbbmxKiZL7uzI8L2rE5T4LydaiPMIN0ljGif1WgZpVPElG"
            + "abNd4hEMwQxEuukp8QhG~Wd5UmojpF1FZe4UW6CyB1nZzXKKYxAcFp1Xtqd19vkc6J93AHpaSgfEJPhP"
            + "Fg-hh-WJF1x6ijgZOpRoGQlYsQwpH9z6pln5v~U9VwttYtBz8M6OUDS47~JunRmXr.i2p&uploaded=0"
            + "&downloaded=0&left=0&compact=1&numwant=0";

    /** The full announce's info hash, in the short form the log uses. */
    private static final String FULL_IH = "767cbdd2";

    /** Only the host survives - no path, no query. */
    @Test
    public void hostOnlyNoPathNoQuery() {
        assertEquals("opentracker.bt.i2p", I2PSnarkUtil.trackerHost(ANNOUNCE));
    }

    /** A URL written without a scheme still names its host. */
    @Test
    public void schemelessUrlIsStillNamed() {
        assertEquals("opentracker.bt.i2p", I2PSnarkUtil.trackerHost("opentracker.bt.i2p/a"));
    }

    /** A port written with the host stays with it. */
    @Test
    public void portStaysWithTheHost() {
        assertEquals("1.2.3.4:8080", I2PSnarkUtil.trackerHost("http://1.2.3.4:8080/announce"));
    }

    /** A query that starts without a slash still ends the host. */
    @Test
    public void queryWithoutASlashStillEndsTheHost() {
        assertEquals("host", I2PSnarkUtil.trackerHost("http://host?info_hash=%01%02%03%04"));
    }

    /** A b32 tracker the name table knows is shown by its hostname. */
    @Test
    public void knownB32TrackerIsNamedByItsHostname() {
        // the log must show the same name the announce site shows for the same
        // tracker, not the 60-character b32 the URL carries
        assertEquals("tracker2.postman.i2p", I2PSnarkUtil.trackerHost(POSTMAN));
    }

    /** A missing URL has no host to name. */
    @Test
    public void missingUrlHasNoHostToName() {
        assertEquals("none", I2PSnarkUtil.trackerHost(null));
    }

    /** A b32 tracker the name table does not know becomes the bracketed short form. */
    @Test
    public void unknownB32TrackerBecomesTheBracketedShortForm() {
        // a b32 host the name table does not know stays 52 characters of base32,
        // which no reader compares by eye and which pushes every line naming it past
        // the width limit; the bracketed eight-character form is what the console log
        // viewer links, and it matches how a destination is named everywhere else
        String url = "http://" + UNKNOWN_B32 + ".b32.i2p/announce?info_hash=%01%02%03%04";
        assertEquals("Tracker: [" + UNKNOWN_B32.substring(0, 8) + "] * Torrent: 01020304",
                     I2PSnarkUtil.fetchTarget(url));
        assertEquals("[" + UNKNOWN_B32.substring(0, 8) + "]", I2PSnarkUtil.trackerHost(url));
    }

    /** A b32 address with no entry in the name table. */
    private static final String UNKNOWN_B32 =
            "zzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzz";

    /** A URL with no host is named in prose, never as an empty string. */
    @Test
    public void emptyHostIsNotAnEmptyName() {
        assertEquals("none", I2PSnarkUtil.trackerHost("http://"));
    }

    /** The info hash comes out of the query as eight hex characters. */
    @Test
    public void infoHashComesOutOfTheQueryAsEightHex() {
        assertEquals("b1d2b8e9", I2PSnarkUtil.fetchInfoHash(ANNOUNCE));
    }

    /** The info hash ends at the next parameter. */
    @Test
    public void infoHashStopsAtTheNextParameter() {
        // peer_id follows, and it is 20 raw bytes that can hold any character
        assertEquals("b1d2b8e9",
                     I2PSnarkUtil.fetchInfoHash("http://h/a?info_hash=%b1%d2%b8%e9&peer_id=x"));
    }

    /** A plus in the query decodes as a space. */
    @Test
    public void plusDecodesAsASpace() {
        assertEquals("6120620a", I2PSnarkUtil.fetchInfoHash("http://h/a?info_hash=a+b%0a%0b"));
    }

    /** A query with no info_hash names no torrent. */
    @Test
    public void noInfoHashParamNamesNoTorrent() {
        assertNull(I2PSnarkUtil.fetchInfoHash("http://host/a"));
        assertNull(I2PSnarkUtil.fetchInfoHash(null));
    }

    /** A malformed escape is not guessed into a hash. */
    @Test
    public void malformedEscapeNamesNoTorrent() {
        assertNull(I2PSnarkUtil.fetchInfoHash("http://h/a?info_hash=%zz%0a%0b%0c"));
    }

    /** A truncated escape is not guessed into a hash. */
    @Test
    public void truncatedEscapeNamesNoTorrent() {
        assertNull(I2PSnarkUtil.fetchInfoHash("http://h/a?info_hash=%0a%0"));
    }

    /** Too few bytes to identify a torrent names no torrent. */
    @Test
    public void tooFewBytesToIdentifyANamedTorrent() {
        assertNull(I2PSnarkUtil.fetchInfoHash("http://h/a?info_hash=%0a%0b"));
    }

    /** The tail names the tracker and the torrent, nothing else. */
    @Test
    public void targetNamesTheTrackerAndTheTorrent() {
        assertEquals("Tracker: opentracker.bt.i2p * Torrent: b1d2b8e9",
                     I2PSnarkUtil.fetchTarget(ANNOUNCE));
    }

    /** With no info hash the tail leaves the torrent out. */
    @Test
    public void targetLeavesOutTheTorrentWhenThereIsNone() {
        // a torrent-file fetch has no info hash to name yet, and a malformed one
        // is not invented into a hash
        assertEquals("Tracker: host", I2PSnarkUtil.fetchTarget("http://host/torrent.torrent"));
        assertEquals("Tracker: host", I2PSnarkUtil.fetchTarget("http://host/a?info_hash=%zz"));
    }

    /** A missing URL still gets a tracker segment, named as none. */
    @Test
    public void targetForAMissingUrlStillNamesTheTracker() {
        assertEquals("Tracker: none", I2PSnarkUtil.fetchTarget(null));
    }

    /** A full announce collapses to the tracker and the torrent. */
    @Test
    public void aFullAnnounceBecomesTheTrackerAndTheTorrent() {
        assertEquals("Tracker: opentracker.bt.i2p * Torrent: " + FULL_IH,
                     I2PSnarkUtil.fetchTarget(FULL_ANNOUNCE));
    }

    /** None of the URL bulk - the ip= blob or the query - reaches the line. */
    @Test
    public void ourDestinationNeverReachesTheLine() {
        String tail = I2PSnarkUtil.fetchTarget(FULL_ANNOUNCE);
        assertTrue("no ip= blob: " + tail, !tail.contains("ip="));
        assertTrue("no info hash blob: " + tail, !tail.contains("info_hash"));
        assertTrue("short enough to read: " + tail.length(), tail.length() < 60);
    }

    /** The URL the log used to print is pinned as unreadably long. */
    @Test
    public void theUrlReallyIsTooLongToRead() {
        // pinned so the claim in the class javadoc stays honest: the ip= value
        // alone is nearly ten times longer than the whole tail we now emit
        assertEquals(760, FULL_ANNOUNCE.length());
        int start = FULL_ANNOUNCE.indexOf("&ip=") + 4;
        int end = FULL_ANNOUNCE.indexOf('&', start);
        assertEquals(528, FULL_ANNOUNCE.substring(start, end).length());
    }

    /** A rewritten announce names only the proxy in front of it. */
    @Test
    public void theRewrittenFormCollapsesToTheProxyHost() {
        // rewriteAnnounce() moves the b32 host into the path, so a URL in that
        // form names only the i2p proxy. Pinned: the fetch sites log the original
        // url, never the rewritten one, so this stays off the log.
        assertEquals("Tracker: i2p * Torrent: " + FULL_IH,
                     I2PSnarkUtil.fetchTarget("http://i2p/abc.b32.i2p/announce"
                                              + "?info_hash=%76%7c%bd%d2%79%77%74%6e%07%d5%7a%2b"
                                              + "%f3%0b%b8%81%c0%fb%8a%e1"));
    }
}

package net.i2p.i2psnark;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;
import static org.junit.Assert.*;

import net.i2p.data.Base32;
import net.i2p.data.Base64;
import net.i2p.data.DataHelper;
import net.i2p.data.Hash;

import org.klomp.snark.I2PSnarkUtil;
import org.klomp.snark.Peer;
import org.klomp.snark.PeerID;
import org.klomp.snark.MetaInfo;
import org.klomp.snark.SnarkLogFormatter;
import org.klomp.snark.bencode.InvalidBEncodingException;

/**
 * Pins the identity shapes, the list layout and the request tally of the snark log
 * formatter: the bracketed eight-character forms the console log viewer links, the
 * one line a list of at most four identities gets with the rest counted behind a
 * "(+N more)" tail, the zero-count form an empty list has to fall back to, the
 * continuation line a second list in the same message gets, and the per-piece tally
 * that replaces a peer's raw request list.
 *
 * @since 0.9.72+
 */
public class SnarkLogFormatterTest {

    /**
     * Test instance; every test calls a static formatter method, so the instance carries
     * no state.
     */
    public SnarkLogFormatterTest() {
    }

    /** A hash whose bytes vary with the seed, so two of them never collide. */
    private static Hash hash(int seed) {
        byte[] buf = new byte[32];
        for (int i = 0; i < buf.length; i++) {
            buf[i] = (byte) ((seed * 31 + i * 7 + 1) & 0xff);
        }
        return new Hash(buf);
    }

    /** A list of n distinct destination hashes. */
    private static List<Hash> hashes(int n) {
        List<Hash> rv = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            rv.add(hash(i));
        }
        return rv;
    }

    /**
     * A destination is the eight-character short base32 form the console linkifier
     * parses, bracketed so it becomes a link to the lease set.
     */
    @Test
    public void testDestIsEightCharShortBase32InBrackets() {
        Hash h = hash(3);
        String s = SnarkLogFormatter.dest(h);
        assertEquals('[' + h.toShortBase32() + ']', s);
        // the linkifier in refreshLogs.js only parses a bracketed eight-character token
        assertEquals(10, s.length());
        assertTrue(s.matches("\\[[a-zA-Z0-9~\\-]{8}\\]"));
    }

    /** A missing destination is named in prose, not printed as null. */
    @Test
    public void testDestOfNullIsNamedNone() {
        assertEquals("none", SnarkLogFormatter.dest((Hash) null));
    }

    /** A DHT node is the six-character base64 router form, bracketed for the linkifier. */
    @Test
    public void testNodeIsEightCharBase64InBrackets() {
        Hash h = hash(5);
        String s = SnarkLogFormatter.node(h);
        assertEquals('[' + h.toBase64().substring(0, 8) + ']', s);
        assertEquals(10, s.length());
    }

    /** A missing node is named in prose, not printed as null. */
    @Test
    public void testNodeOfNullIsNamedNone() {
        assertEquals("none", SnarkLogFormatter.node(null));
    }

    /**
     * A torrent infohash is eight hex characters with no brackets - it is not a
     * destination, so the linkifier must not try to resolve it.
     */
    @Test
    public void testInfoHashIsEightHexUnbracketed() {
        byte[] ih = new byte[20];
        for (int i = 0; i < ih.length; i++) {
            ih[i] = (byte) (i + 0xa0);
        }
        String s = SnarkLogFormatter.infoHash(ih);
        assertEquals(I2PSnarkUtil.toHex(ih).substring(0, 8), s);
        assertEquals(8, s.length());
        assertFalse(s.contains("["));
    }

    /** A short or missing infohash is named in prose rather than truncated to garbage. */
    @Test
    public void testInfoHashOfMissingValueIsNamedNone() {
        assertEquals("none", SnarkLogFormatter.infoHash(null));
        assertEquals("none", SnarkLogFormatter.infoHash(new byte[3]));
    }

    /**
     * The key form and the display form agree, so a torrent named in one log line can be
     * found in another by the same string.
     */
    @Test
    public void testInfoHashKeyMatchesTheHashItEncodes() {
        byte[] ih = new byte[20];
        ih[0] = 1;
        ih[19] = 2;
        assertEquals(SnarkLogFormatter.infoHash(ih),
                     SnarkLogFormatter.infoHashKey(Base64.encode(ih)));
        assertEquals("none", SnarkLogFormatter.infoHashKey(null));
    }

    /** A short list rides on the label line, because a list does not earn a line alone. */
    @Test
    public void testAShortListIsOneSegmentOnItsLabelLine() {
        List<Hash> l = hashes(3);
        String s = SnarkLogFormatter.destList(l, "Peers", 3);
        // one line, no indented continuation - a list does not earn a line of its own
        assertEquals(" * Peers (3): " + SnarkLogFormatter.dest(l.get(0)) + ' '
                     + SnarkLogFormatter.dest(l.get(1)) + ' ' + SnarkLogFormatter.dest(l.get(2)),
                     s);
        assertFalse(s, s.contains("\n"));
    }

    /** No list length, however large, puts an identity on a line of its own. */
    @Test
    public void testEveryListIsOneLineHoweverLong() {
        for (int n : new int[] {1, 4, 10, 11, 27, 400}) {
            String s = SnarkLogFormatter.destList(hashes(n), "Peers", n);
            assertFalse(n + " entries: " + s, s.contains("\n"));
        }
    }

    /** Four identities is the cap, and the rest are counted inline behind them. */
    @Test
    public void testAListStopsAtFourEntriesAndCountsTheRestOnTheSameLine() {
        String s = SnarkLogFormatter.destList(hashes(27), "Now to try", 27);
        assertEquals(4, countEntries(s));
        // the tail sits behind the last identity, not on a line of its own
        assertTrue(s, s.endsWith(" (+23 more)"));
        // the label still reports the whole list, not what is printed
        assertTrue(s, s.startsWith(" * Now to try (27): "));
    }

    /** A list that fits under the cap needs no tail at all. */
    @Test
    public void testAListAtTheCapNeedsNoTail() {
        String s = SnarkLogFormatter.destList(hashes(4), "Peers", 4);
        assertEquals(4, countEntries(s));
        assertFalse(s, s.contains("more)"));
    }

    /**
     * The caller's count and the iterable's size can differ, so the tail has to account
     * for whichever is larger.
     */
    @Test
    public void testDestListCountsWhatItDoesNotShow() {
        String s = SnarkLogFormatter.destList(hashes(20), "Peers", 30);
        assertTrue(s, s.endsWith(" (+26 more)"));
    }

    /**
     * Four identities, the label and the tail are the most a list can ever be, whatever
     * the tracker or the routing table holds - and the budget has to leave room for the
     * caller's lead-in on the same line.
     */
    @Test
    public void testACappedListIsBoundedInWidth() {
        String s = SnarkLogFormatter.destList(hashes(400), "Not tried", 400);
        assertTrue(s.length() + " " + s, s.length() <= 80);
    }

    /**
     * The caller ends its lead-in at "->", so the space the segment returns is what puts
     * one space between the arrow and the first word.
     */
    @Test
    public void testAfterArrowLeavesOneSpaceBehindTheArrow() {
        List<Hash> l = hashes(2);
        String seg = SnarkLogFormatter.destList(l, "Peers", 2);
        String after = SnarkLogFormatter.afterArrow(seg);
        assertEquals(" Peers (2): " + SnarkLogFormatter.dest(l.get(0)) + ' '
                     + SnarkLogFormatter.dest(l.get(1)), after);
        assertFalse(after, after.startsWith("*"));
        assertFalse(after, after.startsWith("\n"));
    }

    /**
     * A second list in the same message goes on a continuation line of its own - only
     * one list can ride the arrow, and two capped lists plus their labels and the
     * caller's lead-in still run past the width limit on one line.
     */
    @Test
    public void testOnOwnLinePutsTheSegmentOnAContinuationLine() {
        List<Hash> l = hashes(2);
        String seg = SnarkLogFormatter.destList(l, "Peers", 2);
        String own = SnarkLogFormatter.onOwnLine(seg);
        assertEquals("\n* Peers (2): " + SnarkLogFormatter.dest(l.get(0)) + ' '
                     + SnarkLogFormatter.dest(l.get(1)), own);
        assertTrue(own, own.startsWith("\n* "));
        // the zero-count form of an empty list moves too, and keeps its own line
        assertEquals("\n* Peers: 0", SnarkLogFormatter.onOwnLine(" * Peers: 0"));
    }

    /**
     * A tracker written as a base32 address becomes the bracketed eight-character form
     * the console linkifier parses; a named tracker keeps the name a reader acts on.
     */
    @Test
    public void testHostShortensABase32AddressButKeepsAName() {
        // 52 base32 characters of a Hash, the form a private tracker's URL carries
        String b32 = Base32.encode(hash(21).getData());
        assertEquals(52, b32.length());
        String s = SnarkLogFormatter.host(b32 + ".b32.i2p");
        assertEquals('[' + b32.substring(0, 8) + ']', s);
        // the same address written without the .b32 marker
        assertEquals(s, SnarkLogFormatter.host(b32 + ".i2p"));
        // names stay names, however they end
        assertEquals("sigmatracker.i2p", SnarkLogFormatter.host("sigmatracker.i2p"));
        assertEquals("tracker2.postman.i2p", SnarkLogFormatter.host("tracker2.postman.i2p"));
        assertEquals("example.com", SnarkLogFormatter.host("example.com"));
        assertEquals("none", SnarkLogFormatter.host(null));
    }

    /**
     * A torrent name is user data of no fixed length, so it gets a budget like any
     * other value, cut with a marker that says the name went past the line.
     */
    @Test
    public void testNameCutsAnOverlongTorrentNameWithAMarker() {
        String shortName = "Autodesk 3D Studio Max 8";
        assertEquals(shortName, SnarkLogFormatter.name(shortName));
        String longName = "Some Really Long Scene Release Name Of A Torrent 2026 x264";
        String s = SnarkLogFormatter.name(longName);
        // the budget is 24 characters, pinned here so widening it is a decision
        assertEquals(24, s.length());
        assertTrue(s, s.endsWith("..."));
        assertEquals(longName.substring(0, 21) + "...", s);
        assertEquals("none", SnarkLogFormatter.name(null));
    }

    /**
     * An empty list is a zero count, not a label with nothing under it and not a
     * sentence - {@code "Peers 0 destinations"} was neither a key nor readable.
     */
    @Test
    public void testEmptyListIsAZeroCountNotAnEmptyLabel() {
        assertEquals(" * Peers: 0",
                     SnarkLogFormatter.destList(Collections.<Hash> emptyList(), "Peers", 0));
        assertEquals(" * Now to try: 0",
                     SnarkLogFormatter.nodeList(Collections.<Hash> emptyList(), "Now to try", 0));
        // a null list is the same case, and must not emit a label with nothing under it
        assertEquals(" * Peers: 0", SnarkLogFormatter.destList(null, "Peers", 0));
    }

    /** Nodes are named by the router form, destinations by the lease-set form. */
    @Test
    public void testNodeListNamesNodesNotDestinations() {
        Hash h = hash(9);
        String s = SnarkLogFormatter.nodeList(Collections.singletonList(h), "Tried", 1);
        assertTrue(s.contains('[' + h.toBase64().substring(0, 8) + ']'));
        assertFalse(s.contains('[' + h.toShortBase32() + ']'));
    }

    /**
     * A peer is named by the same ten characters a destination gets, so the linkifier
     * parses a peer and a destination identically.
     *
     * @throws InvalidBEncodingException on a peer id the fixture cannot build
     */
    @Test
    public void testPeerIsItsDestHashShortFormInBrackets() throws InvalidBEncodingException {
        PeerID pid = peerId(11);
        String s = SnarkLogFormatter.peer(pid);
        assertEquals(SnarkLogFormatter.dest(new Hash(pid.getDestHash())), s);
        assertTrue(s, s.matches("\\[[a-zA-Z0-9~\\-]{8}\\]"));
    }

    /**
     * A BEP 19 webseed's dest hash is a hash of its URL, not an I2P destination, so
     * brackets would render a link to a lease set that does not exist.
     *
     * @throws InvalidBEncodingException on a peer id the fixture cannot build
     */
    @Test
    public void testWebSeedPeerIsNamedInProse() throws InvalidBEncodingException {
        PeerID pid = peerId(12);
        // the BEP 19 marker WebPeer.IDBytes carries, spelled out here
        // because WebPeer is package-private
        pid.setID(DataHelper.getASCII("WebSeedBEP19"));
        String s = SnarkLogFormatter.peer(pid);
        assertEquals("webseed " + Base32.encode(pid.getDestHash()).substring(0, 8), s);
        assertFalse(s, s.contains("["));
    }

    /**
     * A torrent named from its metadata is cut to the name budget, and a torrent
     * whose metadata has not arrived yet is named in prose - the wantPiece() path
     * runs before the metadata arrives for a magnet, so a null there is normal.
     */
    @Test
    public void torrentFromMetadataIsCappedOrNamedNone() {
        assertEquals("none", SnarkLogFormatter.torrent((MetaInfo) null));
        // the same 24-character budget name() applies, so every line naming the
        // same torrent is the same width
        assertEquals(24, SnarkLogFormatter
                       .name("Some Really Long Scene Release Name Of A Torrent 2026").length());
    }

    /** A missing peer is named in prose, not printed as null. */
    @Test
    public void testNullPeerIsNamedNone() {
        assertEquals("none", SnarkLogFormatter.peer((PeerID) null));
        assertEquals("none", SnarkLogFormatter.peer((Peer) null));
    }

    /** The tally names the pieces the blocks came from, each with its block count. */
    @Test
    public void testRequestTallyNamesEachPieceAndItsBlocks() {
        Map<Integer, Integer> m = new LinkedHashMap<>();
        m.put(Integer.valueOf(27), Integer.valueOf(16));
        m.put(Integer.valueOf(40), Integer.valueOf(9));
        assertEquals(" Piece 27: 16 blocks * Piece 40: 9 blocks",
                     SnarkLogFormatter.requestTally(m));
    }

    /** One block of one piece reads as one block of one piece. */
    @Test
    public void testRequestTallyIsSingularForOneBlock() {
        Map<Integer, Integer> m = new LinkedHashMap<>();
        m.put(Integer.valueOf(27), Integer.valueOf(1));
        assertEquals(" Piece 27: 1 block", SnarkLogFormatter.requestTally(m));
    }

    /** No requests says so in prose, it is not an empty tail after an arrow. */
    @Test
    public void testRequestTallyNamesNothingForNoRequests() {
        assertEquals(" no blocks",
                     SnarkLogFormatter.requestTally(new LinkedHashMap<Integer, Integer>()));
    }

    /**
     * Two pieces is the naming cap, and the tail counts the rest so the reader can
     * still add up how many pieces are in flight.
     */
    @Test
    public void testRequestTallyStopsAtTwoPiecesAndCountsTheRest() {
        Map<Integer, Integer> m = new LinkedHashMap<>();
        for (int i = 0; i < 12; i++) {
            m.put(Integer.valueOf(i), Integer.valueOf(2));
        }
        String s = SnarkLogFormatter.requestTally(m);
        assertEquals(2, countPieces(s));
        assertTrue(s, s.endsWith("(+10 more)"));
    }

    /**
     * Four-digit piece numbers and three-digit block counts are the widest a tally
     * gets, and the tally shares its line with the caller's lead-in - the whole
     * emitted line, lead-in included, has to stay inside the logging guide's 100
     * columns, which is why the cap is two pieces and there is no totals lead.
     */
    @Test
    public void testNoRequestTallyLineRunsPastTheWidthLimit() {
        Map<Integer, Integer> m = new LinkedHashMap<>();
        for (int i = 0; i < 12; i++) {
            m.put(Integer.valueOf(1000 + i), Integer.valueOf(1000));
        }
        // the real peer form is the bracketed eight-character hash, 10 characters
        String line = "Requesting from [abcdefghi] ->" + SnarkLogFormatter.requestTally(m);
        assertTrue(line.length() + " " + line, line.length() <= 100);
        assertFalse(line, line.contains("\n"));
    }

    /** Pieces named by a tally line. */
    private static int countPieces(String line) {
        int n = 0;
        for (int i = line.indexOf("Piece "); i >= 0; i = line.indexOf("Piece ", i + 1)) {
            n++;
        }
        return n;
    }

    /**
     * A PeerID with a dest hash that varies with the seed, and no peer id.
     *
     * @param seed the byte pattern to build the id from
     * @return the peer id, with no peer id set
     * @throws InvalidBEncodingException on a buffer the peer id cannot parse
     */
    private static PeerID peerId(int seed) throws InvalidBEncodingException {
        byte[] buf = new byte[32];
        for (int i = 0; i < buf.length; i++) {
            buf[i] = (byte) ((seed * 31 + i * 7 + 1) & 0xff);
        }
        return new PeerID(buf, (I2PSnarkUtil) null);
    }

    /** Entries on one list line, each a bracketed identity. */
    private static int countEntries(String line) {
        int n = 0;
        for (int i = 0; i < line.length() - 1; i++) {
            if (line.charAt(i) == '[' && line.charAt(i + 9) == ']') {
                n++;
                i += 9;
            }
        }
        return n;
    }
}

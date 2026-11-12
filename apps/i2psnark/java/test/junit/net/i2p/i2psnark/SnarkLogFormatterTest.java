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
import org.klomp.snark.SnarkLogFormatter;
import org.klomp.snark.bencode.InvalidBEncodingException;

/**
 * Pins the identity shapes, the list layout and the request tally of the snark log
 * formatter: the bracketed eight-character forms the console log viewer links, the
 * one line a list of at most ten identities gets with the rest counted behind a
 * "(+N more)" tail, the sentence form an empty list has to fall back to, and the
 * per-piece tally that replaces a peer's raw request list.
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

    /** Ten identities is the cap, and the rest are counted inline behind them. */
    @Test
    public void testAListStopsAtTenEntriesAndCountsTheRestOnTheSameLine() {
        String s = SnarkLogFormatter.destList(hashes(27), "Now to try", 27);
        assertEquals(10, countEntries(s));
        // the tail sits behind the last identity, not on a line of its own
        assertTrue(s, s.endsWith(" (+17 more)"));
        // the label still reports the whole list, not what is printed
        assertTrue(s, s.startsWith(" * Now to try (27): "));
    }

    /** A list that fits under the cap needs no tail at all. */
    @Test
    public void testAListAtTheCapNeedsNoTail() {
        String s = SnarkLogFormatter.destList(hashes(10), "Peers", 10);
        assertEquals(10, countEntries(s));
        assertFalse(s, s.contains("more)"));
    }

    /**
     * The caller's count and the iterable's size can differ, so the tail has to account
     * for whichever is larger.
     */
    @Test
    public void testDestListCountsWhatItDoesNotShow() {
        String s = SnarkLogFormatter.destList(hashes(20), "Peers", 30);
        assertTrue(s, s.endsWith(" (+20 more)"));
    }

    /**
     * Ten identities, the label and the tail are the most a list can ever be, whatever
     * the tracker or the routing table holds.
     */
    @Test
    public void testACappedListIsBoundedInWidth() {
        String s = SnarkLogFormatter.destList(hashes(400), "Not tried", 400);
        assertTrue(s.length() + " " + s, s.length() <= 145);
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

    /** An empty list becomes a sentence, never a label with nothing under it. */
    @Test
    public void testEmptyListIsASentenceNotAnEmptyLabel() {
        assertEquals(" * Peers 0 destinations",
                     SnarkLogFormatter.destList(Collections.<Hash> emptyList(), "Peers", 0));
        assertEquals(" * Now to try 0 nodes",
                     SnarkLogFormatter.nodeList(Collections.<Hash> emptyList(), "Now to try", 0));
        // a null list is the same case, and must not emit a label with nothing under it
        assertEquals(" * Peers 0 destinations", SnarkLogFormatter.destList(null, "Peers", 0));
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

    /** A missing peer is named in prose, not printed as null. */
    @Test
    public void testNullPeerIsNamedNone() {
        assertEquals("none", SnarkLogFormatter.peer((PeerID) null));
        assertEquals("none", SnarkLogFormatter.peer((Peer) null));
    }

    /** The tally leads with the total, then names the pieces the blocks came from. */
    @Test
    public void testRequestTallyTotalsThenNamesEachPiece() {
        Map<Integer, Integer> m = new LinkedHashMap<>();
        m.put(Integer.valueOf(27), Integer.valueOf(16));
        m.put(Integer.valueOf(40), Integer.valueOf(9));
        assertEquals(" 25 blocks across 2 pieces * Piece 27: 16 blocks * Piece 40: 9 blocks",
                     SnarkLogFormatter.requestTally(m));
    }

    /** The total is the sum of the pieces named, and both sides of it agree. */
    @Test
    public void testRequestTallyAgreesOnItsTotals() {
        Map<Integer, Integer> m = new LinkedHashMap<>();
        m.put(Integer.valueOf(3), Integer.valueOf(1));
        m.put(Integer.valueOf(9), Integer.valueOf(1));
        String s = SnarkLogFormatter.requestTally(m);
        assertTrue(s, s.startsWith(" 2 blocks across 2 pieces"));
        assertTrue(s, s.contains("Piece 3: 1 block"));
        assertTrue(s, s.contains("Piece 9: 1 block"));
    }

    /** One block of one piece reads as one block of one piece. */
    @Test
    public void testRequestTallyIsSingularForOneOfEach() {
        Map<Integer, Integer> m = new LinkedHashMap<>();
        m.put(Integer.valueOf(27), Integer.valueOf(1));
        assertEquals(" 1 block across 1 piece * Piece 27: 1 block",
                     SnarkLogFormatter.requestTally(m));
    }

    /** No requests is a total with no pieces named, not an empty label. */
    @Test
    public void testRequestTallyNamesNothingForNoRequests() {
        assertEquals(" 0 blocks across 0 pieces",
                     SnarkLogFormatter.requestTally(new LinkedHashMap<Integer, Integer>()));
    }

    /** Eight pieces is the cap for naming, and the total still counts every one. */
    @Test
    public void testRequestTallyStopsAtEightPiecesAndCountsTheRest() {
        Map<Integer, Integer> m = new LinkedHashMap<>();
        for (int i = 0; i < 12; i++) {
            m.put(Integer.valueOf(i), Integer.valueOf(2));
        }
        String s = SnarkLogFormatter.requestTally(m);
        assertTrue(s, s.startsWith(" 24 blocks across 12 pieces"));
        assertEquals(8, countPieces(s));
        assertTrue(s, s.endsWith("(+4 more)"));
    }

    /** Four-digit piece numbers and block counts are the widest a tally gets. */
    @Test
    public void testNoRequestTallyLineRunsPastTheWidthLimit() {
        Map<Integer, Integer> m = new LinkedHashMap<>();
        for (int i = 0; i < 12; i++) {
            m.put(Integer.valueOf(1000 + i), Integer.valueOf(1000));
        }
        for (String line : SnarkLogFormatter.requestTally(m).split("\n")) {
            assertTrue(line.length() + " " + line, line.length() <= 90);
        }
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

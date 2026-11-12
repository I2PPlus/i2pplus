package net.i2p.i2psnark;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

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
 * Pins the identity shapes and the sub-list layout of the snark log formatter:
 * the bracketed eight-character forms the console log viewer links, the eight
 * entries per sub-list line, the "(+N more)" tail, and the sentence form an
 * empty list has to fall back to.
 *
 * @since 0.9.72+
 */
public class SnarkLogFormatterTest {

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

    @Test
    public void testDestIsEightCharShortBase32InBrackets() {
        Hash h = hash(3);
        String s = SnarkLogFormatter.dest(h);
        assertEquals('[' + h.toShortBase32() + ']', s);
        // the linkifier in refreshLogs.js only parses a bracketed eight-character token
        assertEquals(10, s.length());
        assertTrue(s.matches("\\[[a-zA-Z0-9~\\-]{8}\\]"));
    }

    @Test
    public void testDestOfNullIsNamedNone() {
        assertEquals("none", SnarkLogFormatter.dest((Hash) null));
    }

    @Test
    public void testNodeIsEightCharBase64InBrackets() {
        Hash h = hash(5);
        String s = SnarkLogFormatter.node(h);
        assertEquals('[' + h.toBase64().substring(0, 8) + ']', s);
        assertEquals(10, s.length());
    }

    @Test
    public void testNodeOfNullIsNamedNone() {
        assertEquals("none", SnarkLogFormatter.node(null));
    }

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

    @Test
    public void testInfoHashOfMissingValueIsNamedNone() {
        assertEquals("none", SnarkLogFormatter.infoHash(null));
        assertEquals("none", SnarkLogFormatter.infoHash(new byte[3]));
    }

    @Test
    public void testInfoHashKeyMatchesTheHashItEncodes() {
        byte[] ih = new byte[20];
        ih[0] = 1;
        ih[19] = 2;
        assertEquals(SnarkLogFormatter.infoHash(ih),
                     SnarkLogFormatter.infoHashKey(Base64.encode(ih)));
        assertEquals("none", SnarkLogFormatter.infoHashKey(null));
    }

    @Test
    public void testAShortListStaysOnTheLabelLine() {
        List<Hash> l = hashes(3);
        String s = SnarkLogFormatter.destList(l, "Peers", 3);
        // one line, no indented continuation - a list that fits does not earn a
        // line of its own
        assertEquals(" * Peers (3): " + SnarkLogFormatter.dest(l.get(0)) + ' '
                     + SnarkLogFormatter.dest(l.get(1)) + ' ' + SnarkLogFormatter.dest(l.get(2)),
                     s);
        assertFalse(s, s.contains("\n"));
    }

    @Test
    public void testFourDestinationsStillFitOnTheLabelLine() {
        // the budget is set so a tracker's usual four-peer list costs one line,
        // not two - the space-efficiency rule
        String s = SnarkLogFormatter.destList(hashes(4), "Peers", 4);
        assertFalse(s, s.contains("\n"));
        assertEquals(4, countEntries(s));
    }

    @Test
    public void testAListThatWouldOverflowTheLineWraps() {
        // five ten-character identities plus the label is already 65 characters,
        // past the budget, so it goes onto indented lines instead
        String s = SnarkLogFormatter.destList(hashes(5), "Peers", 5);
        String[] lines = wrappedLines(s);
        assertEquals(Arrays.toString(lines), 2, lines.length);
        assertEquals("* Peers (5):", lines[0]);
        assertEquals(5, countEntries(lines[1]));
        assertTrue(lines[1], lines[1].startsWith("     "));
    }

    @Test
    public void testALongListWrapsEightToALineUnderItsLabel() {
        String s = SnarkLogFormatter.destList(hashes(12), "Peers", 12);
        String[] lines = wrappedLines(s);
        // label, then 8 + 4 entries
        assertEquals(3, lines.length);
        assertEquals("* Peers (12):", lines[0]);
        assertEquals(8, countEntries(lines[1]));
        assertEquals(4, countEntries(lines[2]));
        assertTrue(lines[1], lines[1].startsWith("     "));
    }

    @Test
    public void testAListStopsAtSixteenEntriesAndCountsTheRest() {
        String s = SnarkLogFormatter.destList(hashes(20), "Peers", 20);
        String[] lines = wrappedLines(s);
        // label, 8 + 8, then the tail naming what is not shown
        assertEquals(4, lines.length);
        assertEquals(16, countEntries(lines[1]) + countEntries(lines[2]));
        assertEquals("     (+4 more)", lines[3]);
        // the label still reports the whole list, not what is printed
        assertEquals("* Peers (20):", lines[0]);
    }

    @Test
    public void testDestListCountsWhatItDoesNotShow() {
        // the label reports the caller's count and the iterable may hold a
        // different number - the tail has to account for whichever is larger
        String s = SnarkLogFormatter.destList(hashes(20), "Peers", 30);
        String[] lines = s.split("\n");
        assertEquals("     (+14 more)", lines[lines.length - 1]);
    }

    @Test
    public void testNoListLineRunsPastTheWidthLimit() {
        for (int n : new int[] {1, 2, 3, 4, 8, 9, 16, 17, 40}) {
            String s = SnarkLogFormatter.destList(hashes(n), "Peers", n);
            for (String line : s.split("\n")) {
                assertTrue(n + " entries: " + line.length() + ' ' + line,
                           line.length() <= 92);
            }
        }
    }

    @Test
    public void testAfterArrowLeavesOneSpaceBehindTheArrow() {
        List<Hash> l = hashes(2);
        String inline = SnarkLogFormatter.destList(l, "Peers", 2);
        // the caller ends its lead-in at "->", so the space the segment returns is
        // what puts one space between the arrow and the first word
        assertEquals(" Peers (2): " + SnarkLogFormatter.dest(l.get(0)) + ' '
                     + SnarkLogFormatter.dest(l.get(1)), SnarkLogFormatter.afterArrow(inline));
        // the wrapped form is led by a newline instead, and it comes off too
        String wrapped = SnarkLogFormatter.destList(hashes(12), "Peers", 12);
        String after = SnarkLogFormatter.afterArrow(wrapped);
        assertEquals(" " + wrapped.substring(3), after);
        assertFalse(after, after.startsWith("*"));
        assertFalse(after, after.startsWith("\n"));
    }

    @Test
    public void testEmptyListIsASentenceNotAnEmptyLabel() {
        assertEquals(" * Peers 0 destinations",
                     SnarkLogFormatter.destList(Collections.<Hash> emptyList(), "Peers", 0));
        assertEquals(" * Now to try 0 nodes",
                     SnarkLogFormatter.nodeList(Collections.<Hash> emptyList(), "Now to try", 0));
        // a null list is the same case, and must not emit a label with nothing under it
        assertEquals(" * Peers 0 destinations", SnarkLogFormatter.destList(null, "Peers", 0));
    }

    @Test
    public void testNodeListNamesNodesNotDestinations() {
        Hash h = hash(9);
        String s = SnarkLogFormatter.nodeList(Collections.singletonList(h), "Tried", 1);
        assertTrue(s.contains('[' + h.toBase64().substring(0, 8) + ']'));
        assertFalse(s.contains('[' + h.toShortBase32() + ']'));
    }

    @Test
    public void testPeerIsItsDestHashShortFormInBrackets() throws InvalidBEncodingException {
        PeerID pid = peerId(11);
        String s = SnarkLogFormatter.peer(pid);
        // the same ten characters dest() gives, so a peer and a destination are
        // named identically and the linkifier parses both
        assertEquals(SnarkLogFormatter.dest(new Hash(pid.getDestHash())), s);
        assertTrue(s, s.matches("\\[[a-zA-Z0-9~\\-]{8}\\]"));
    }

    @Test
    public void testWebSeedPeerIsNamedInProse() throws InvalidBEncodingException {
        // a BEP 19 webseed's dest hash is a hash of its URL, not an I2P
        // destination, so brackets would render a link to a lease set that does
        // not exist
        PeerID pid = peerId(12);
        // the BEP 19 marker WebPeer.IDBytes carries, spelled out here
        // because WebPeer is package-private
        pid.setID(DataHelper.getASCII("WebSeedBEP19"));
        String s = SnarkLogFormatter.peer(pid);
        assertEquals("webseed " + Base32.encode(pid.getDestHash()).substring(0, 8), s);
        assertFalse(s, s.contains("["));
    }

    @Test
    public void testNullPeerIsNamedNone() {
        assertEquals("none", SnarkLogFormatter.peer((PeerID) null));
        assertEquals("none", SnarkLogFormatter.peer((Peer) null));
    }

    /** A PeerID with a dest hash that varies with the seed, and no peer id. */
    private static PeerID peerId(int seed) throws InvalidBEncodingException {
        byte[] buf = new byte[32];
        for (int i = 0; i < buf.length; i++) {
            buf[i] = (byte) ((seed * 31 + i * 7 + 1) & 0xff);
        }
        return new PeerID(buf, (I2PSnarkUtil) null);
    }

    /**
     * Lines of a wrapped sub-list, with the leading " * " segment separator the
     * formatter puts on the front of it removed so the label is the first line.
     */
    private static String[] wrappedLines(String s) {
        assertTrue(s, s.startsWith("\n* "));
        return s.substring(1).split("\n");
    }

    /** Entries on one sub-list line, each a bracketed identity. */
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

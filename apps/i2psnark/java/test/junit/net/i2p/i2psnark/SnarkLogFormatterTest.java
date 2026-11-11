package net.i2p.i2psnark;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.Test;
import static org.junit.Assert.*;

import net.i2p.data.Base64;
import net.i2p.data.Hash;

import org.klomp.snark.I2PSnarkUtil;
import org.klomp.snark.SnarkLogFormatter;

/**
 * Pins the identity shapes and the sub-list layout of the snark log formatter:
 * the bracketed eight-character forms the console log viewer links, the eight
 * entries per sub-list line, the "(+N more)" tail, and the sentence form an
 * empty list has to fall back to.
 *
 * @since 0.9.73+
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
    public void testDestListLabelsThenIndentsTheEntries() {
        List<Hash> l = hashes(3);
        StringBuilder expected = new StringBuilder("\n* Peers (3):\n     ");
        for (int i = 0; i < l.size(); i++) {
            if (i > 0) {expected.append(' ');}
            expected.append(SnarkLogFormatter.dest(l.get(i)));
        }
        assertEquals(expected.toString(), SnarkLogFormatter.destList(l, "Peers", 3));
    }

    @Test
    public void testDestListPutsEightEntriesOnALine() {
        String s = SnarkLogFormatter.destList(hashes(20), "Peers", 20);
        String[] lines = s.split("\n");
        // label line, then 8 + 8 + 4 entries
        assertEquals(5, lines.length);
        assertEquals("* Peers (20):", lines[1]);
        assertEquals(8, countEntries(lines[2]));
        assertEquals(8, countEntries(lines[3]));
        assertEquals(4, countEntries(lines[4]));
        assertTrue(lines[2].startsWith("     "));
    }

    @Test
    public void testDestListCountsWhatItDoesNotShow() {
        String s = SnarkLogFormatter.destList(hashes(20), "Peers", 30);
        String[] lines = s.split("\n");
        assertEquals("     (+10 more)", lines[lines.length - 1]);
    }

    @Test
    public void testEmptyListIsASentenceNotAnEmptyLabel() {
        assertEquals("\n* Peers 0 destinations",
                     SnarkLogFormatter.destList(Collections.<Hash> emptyList(), "Peers", 0));
        assertEquals("\n* Now to try 0 nodes",
                     SnarkLogFormatter.nodeList(Collections.<Hash> emptyList(), "Now to try", 0));
        // a null list is the same case, and must not emit a label with nothing under it
        assertEquals("\n* Peers 0 destinations", SnarkLogFormatter.destList(null, "Peers", 0));
    }

    @Test
    public void testNodeListNamesNodesNotDestinations() {
        Hash h = hash(9);
        String s = SnarkLogFormatter.nodeList(Collections.singletonList(h), "Tried", 1);
        assertTrue(s.contains('[' + h.toBase64().substring(0, 8) + ']'));
        assertFalse(s.contains('[' + h.toShortBase32() + ']'));
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

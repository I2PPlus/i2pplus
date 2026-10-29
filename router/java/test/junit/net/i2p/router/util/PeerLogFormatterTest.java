package net.i2p.router.util;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.i2p.data.Hash;

import org.junit.Test;

/**
 * Pins the peer sub-list layout: label line, five-space indent, at most sixteen
 * hashes per line, and the "more" tail. The layout is what the peer selectors
 * emit, so it is tested here rather than at each call site.
 *
 * @since 0.9.72+
 */
public class PeerLogFormatterTest {

    /**
     * One instance per test method, as JUnit builds a fresh test object for
     * each one; the class carries no per-test state, only the pure formatting
     * helpers the assertions call.
     *
     * @since 0.9.72+
     */
    public PeerLogFormatterTest() {}

    /** A 32-byte hash filled with the byte {@code n}, so a test can name the peer it expects. */
    private static Hash hash(int n) {
        byte[] data = new byte[Hash.HASH_LENGTH];
        Arrays.fill(data, (byte) n);
        return new Hash(data);
    }

    /** n distinct hashes, filled 1 through n, in order. */
    private static List<Hash> hashes(int n) {
        List<Hash> rv = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {rv.add(hash(i + 1));}
        return rv;
    }

    /** The 6-character base64 form of {@code hash(n)}, the form the formatter prints. */
    private static String shortHash(int n) {
        return hash(n).toBase64().substring(0, 6);
    }

    /** One hash, on its own indented line under the label. */
    @Test
    public void testSingleEntryShape() {
        String s = PeerLogFormatter.peerList(hashes(1), "Excluding", 1);
        assertEquals("\n* Excluding (1):\n     [" + shortHash(1) + ']', s);
    }

    /** Entries share a line, separated by one space, so the hashes stay in one column. */
    @Test
    public void testEntriesShareOneLine() {
        String s = PeerLogFormatter.peerList(hashes(3), "Matched", 3);
        assertEquals("\n* Matched (3):\n     [" + shortHash(1) + "] [" + shortHash(2) + "] [" + shortHash(3) + ']', s);
    }

    /** Sixteen is the last count that fits on the first entry line. */
    @Test
    public void testSixteenOnOneLine() {
        String s = PeerLogFormatter.peerList(hashes(16), "Good", 16);
        String[] lines = splitLines(s);
        assertEquals(2, lines.length);
        assertEquals("* Good (16):", lines[0]);
        assertEquals(16, countHashes(lines[1]));
    }

    /** The seventeenth entry opens a second indented line, not a wider first one. */
    @Test
    public void testSeventeenthEntryWraps() {
        String s = PeerLogFormatter.peerList(hashes(17), "Good", 17);
        String[] lines = splitLines(s);
        assertEquals(3, lines.length);
        assertEquals(16, countHashes(lines[1]));
        assertEquals(1, countHashes(lines[2]));
    }

    /** Thirty-two fills exactly two entry lines and does not open a third. */
    @Test
    public void testThirtyTwoFillsTwoLines() {
        String s = PeerLogFormatter.peerList(hashes(32), "Good", 32);
        String[] lines = splitLines(s);
        assertEquals(3, lines.length);
        assertEquals(16, countHashes(lines[1]));
        assertEquals(16, countHashes(lines[2]));
    }

    /** Thirty-three opens the third entry line. */
    @Test
    public void testThirtyThreeOpensThirdEntryLine() {
        String s = PeerLogFormatter.peerList(hashes(33), "Good", 33);
        String[] lines = splitLines(s);
        assertEquals(4, lines.length);
        assertEquals(1, countHashes(lines[3]));
    }

    /**
     * Every entry line is indented by five spaces and carries no bullet, so the
     * block reads as one group under its label.
     */
    @Test
    public void testEntryLinesAreIndentedNotBulleted() {
        String s = PeerLogFormatter.peerList(hashes(20), "Ignoring", 20);
        String[] lines = splitLines(s);
        for (int i = 1; i < lines.length; i++) {
            assertTrue("line " + i + " is not indented: [" + lines[i] + ']', lines[i].startsWith("     "));
            assertFalse("line " + i + " carries a bullet: [" + lines[i] + ']', lines[i].contains("*"));
        }
    }

    /** The colon closes the label, before the count in parentheses. */
    @Test
    public void testLabelLineShape() {
        String s = PeerLogFormatter.peerList(hashes(2), "Tunnel", 2);
        assertEquals("\n* Tunnel (2):\n     [" + shortHash(1) + "] [" + shortHash(2) + ']', s);
    }

    /** A cap names that many peers, then reports the rest as a count. */
    @Test
    public void testCapAddsMoreTail() {
        String s = PeerLogFormatter.peerList(hashes(30), "Ignoring", 30, 16);
        String[] lines = splitLines(s);
        assertEquals(3, lines.length);
        assertEquals(16, countHashes(lines[1]));
        assertEquals("     (+14 more)", lines[2]);
    }

    /** A cap that is not reached adds no tail. */
    @Test
    public void testCapNotReachedAddsNoTail() {
        String s = PeerLogFormatter.peerList(hashes(5), "Ignoring", 5, 16);
        assertEquals(2, splitLines(s).length);
        assertFalse(s.contains("more)"));
    }

    /** A cap of exactly eight lands on one line plus the tail. */
    @Test
    public void testCapOfEight() {
        String s = PeerLogFormatter.peerList(hashes(9), "Ignoring", 9, 8);
        String[] lines = splitLines(s);
        assertEquals(3, lines.length);
        assertEquals(8, countHashes(lines[1]));
        assertEquals("     (+1 more)", lines[2]);
    }

    /** The count in parentheses is the full size, not the number named. */
    @Test
    public void testCapKeepsTheFullCount() {
        String s = PeerLogFormatter.peerList(hashes(30), "Ignoring", 30, 16);
        assertTrue(s.startsWith("\n* Ignoring (30):\n"));
    }

    /** A cap of zero means no cap. */
    @Test
    public void testZeroCapShowsAll() {
        String s = PeerLogFormatter.peerList(hashes(20), "Good", 20, 0);
        assertFalse(s.contains("more)"));
        assertEquals(20, countHashes(s));
    }

    /** Null peers become a sentence, so a caller can append unconditionally. */
    @Test
    public void testNullPeers() {
        assertEquals("\n* Excluding 0 peers", PeerLogFormatter.peerList(null, "Excluding", 0));
        assertEquals("\n* Tunnel 0 peers", PeerLogFormatter.peerListOrdered(null, "Tunnel", 0));
    }

    /** An empty list becomes a sentence, never a colon with nothing after it. */
    @Test
    public void testEmptyPeers() {
        List<Hash> none = Collections.emptyList();
        assertEquals("\n* Excluding 0 peers", PeerLogFormatter.peerList(none, "Excluding", 0));
        assertEquals("\n* Tunnel 0 peers", PeerLogFormatter.peerListOrdered(none, "Tunnel", 0));
    }

    /** A null entry is skipped, and the rest stay laid out from the first column. */
    @Test
    public void testNullEntrySkipped() {
        List<Hash> peers = hashes(2);
        peers.add(1, null);
        String s = PeerLogFormatter.peerList(peers, "Good", 2);
        assertEquals("\n* Good (2):\n     [" + shortHash(1) + "] [" + shortHash(2) + ']', s);
    }

    /**
     * The exclusion helper states the count as a sentence when there is nothing
     * to name, with no colon and nothing after it.
     */
    @Test
    public void testExcludingEmptyForm() {
        assertEquals("\n* Excluding 0 peers", PeerLogFormatter.excluding(null, 0));
        assertEquals("\n* Excluding 0 peers", PeerLogFormatter.excluding(Collections.<Hash> emptyList(), 0));
        assertEquals("\n* Excluding (1):\n     [" + shortHash(1) + ']', PeerLogFormatter.excluding(hashes(1), 1));
    }

    /** The ordered variant joins entries with an arrow, for a gateway-to-endpoint list. */
    @Test
    public void testOrderedJoinsWithArrow() {
        String s = PeerLogFormatter.peerListOrdered(hashes(3), "Tunnel", 3);
        String want = "\n* Tunnel (3):\n     [" + shortHash(1) + "] -> [" + shortHash(2) + "] -> [" + shortHash(3) + ']';
        assertEquals(want, s);
    }

    /** The ordered variant wraps at sixteen, and the arrow does not start a line. */
    @Test
    public void testOrderedWrapsAtSixteen() {
        String s = PeerLogFormatter.peerListOrdered(hashes(17), "Tunnel", 17);
        String[] lines = splitLines(s);
        assertEquals(3, lines.length);
        assertEquals(15, countArrows(lines[1]));
        assertTrue(lines[2].startsWith("     [" + shortHash(17) + ']'));
    }

    /** The tally leads with the total, then the reasons on one bullet line. */
    @Test
    public void testReasonsTally() {
        Map<String, Integer> counts = new LinkedHashMap<>();
        counts.put("unreachable", 5);
        counts.put("no-routerinfo", 2);
        assertEquals("\n* Excluded: 7\n* Unreachable: 5 * No RouterInfo: 2",
                     PeerLogFormatter.reasonsTally(counts));
    }

    /** No reasons render as nothing. */
    @Test
    public void testReasonsTallyEmpty() {
        assertEquals("", PeerLogFormatter.reasonsTally(null));
        assertEquals("", PeerLogFormatter.reasonsTally(Collections.<String, Integer> emptyMap()));
    }

    /** A long tally wraps, so no bullet line runs past the logging guide's width. */
    @Test
    public void testReasonsTallyWraps() {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (int i = 0; i < 12; i++) {counts.put("reason-number-" + i, i + 1);}
        String[] lines = splitLines(PeerLogFormatter.reasonsTally(counts));
        assertEquals("* Excluded: 78", lines[0]);
        int pairs = 0;
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i];
            assertTrue("not a bullet line: [" + line + ']', line.startsWith("* "));
            assertTrue("line over 90 characters: [" + line + ']', line.length() + 2 <= 90);
            pairs += 1 + line.split(" \\* ").length - 1;
        }
        assertEquals(12, pairs);
    }

    /** A reason with no registered label is shown as recorded, so it stays greppable. */
    @Test
    public void testReasonLabelFallback() {
        assertEquals("Unreachable", PeerLogFormatter.reasonLabel("unreachable"));
        assertEquals("No RouterInfo", PeerLogFormatter.reasonLabel("no-routerinfo"));
        assertEquals("some-new-reason", PeerLogFormatter.reasonLabel("some-new-reason"));
    }

    /**
     * The rendered lines, with the leading newline of the block dropped so
     * index 0 is the label line.
     *
     * @param s the rendered block
     * @return one element per emitted line
     */
    private static String[] splitLines(String s) {
        return s.substring(1).split("\n");
    }

    /**
     * No rendering ends in a colon. A label line is only emitted when peers
     * follow it, and the empty case is a sentence.
     */
    @Test
    public void testNoDanglingColon() {
        List<List<Hash>> cases = new ArrayList<>();
        cases.add(null);
        cases.add(Collections.<Hash> emptyList());
        cases.add(Arrays.asList((Hash) null));
        cases.add(hashes(1));
        cases.add(hashes(20));
        int[] counts = {0, 0, 0, 1, 20};
        for (int i = 0; i < cases.size(); i++) {
            Iterable<Hash> peers = cases.get(i);
            int count = counts[i];
            for (String s : new String[] {PeerLogFormatter.peerList(peers, "Selected", count),
                                          PeerLogFormatter.peerList(peers, "Ignoring", count, 16),
                                          PeerLogFormatter.peerListOrdered(peers, "Tunnel", count),
                                          PeerLogFormatter.excluding(peers, count)}) {
                String last = s.substring(s.lastIndexOf('\n') + 1);
                assertFalse("ends in a colon: [" + last + ']', last.endsWith(":"));
            }
        }
    }

    /** Count the bracketed hashes in a rendered block. */
    private static int countHashes(String s) {
        int n = 0;
        for (int i = s.indexOf('['); i >= 0; i = s.indexOf('[', i + 1)) {n++;}
        return n;
    }

    /** Count the entry separators in one rendered line. */
    private static int countArrows(String s) {
        int n = 0;
        for (int i = s.indexOf(" -> "); i >= 0; i = s.indexOf(" -> ", i + 1)) {n++;}
        return n;
    }
}

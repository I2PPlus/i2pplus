package org.klomp.snark;

import static org.junit.Assert.*;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

/**
 * Tests for MetaInfo parsing of torrent names and file paths that real releases
 * carry: spaces, underscores, colons, dots, UTF-8, and very long names. Also
 * covers the BEP 47 padding-file attribute list, which must stay index-aligned
 * with the files list for every mix of padding and regular files.
 *
 * @since 0.9.71+
 */
public class MetaInfoFilenameTest {

    private static final int PIECE_LENGTH = 16384;
    private static final long TOTAL_LENGTH = 30000L;

    /** Assemble a bencoded single-file torrent. */
    private static byte[] singleFileTorrent(String name) {
        // Keys sorted canonically so the rebuilt info hash matches.
        byte[] nameBytes = name.getBytes(StandardCharsets.UTF_8);
        StringBuilder head = new StringBuilder(256);
        head.append('d');
        head.append("8:announce").append("19:http://tracker.test");
        head.append("4:info").append('d');
        head.append("6:length").append('i').append(TOTAL_LENGTH).append('e');
        head.append("4:name").append(nameBytes.length).append(':');
        StringBuilder mid = new StringBuilder(128);
        mid.append("12:piece length").append('i').append(PIECE_LENGTH).append('e');
        mid.append("6:pieces").append("40:");
        return assemble(head.toString(), nameBytes, mid.toString());
    }

    /** Assemble a bencoded multi-file torrent. */
    private static byte[] multiFileTorrent(String name, List<List<String>> paths, List<Long> lengths, List<String> attrs) {
        byte[] nameBytes = name.getBytes(StandardCharsets.UTF_8);
        StringBuilder head = new StringBuilder(512);
        head.append('d');
        head.append("8:announce").append("19:http://tracker.test");
        head.append("4:info").append('d');
        head.append("5:files").append('l');
        for (int i = 0; i < paths.size(); i++) {
            head.append('d');
            String attr = attrs != null ? attrs.get(i) : null;
            if (attr != null) {
                head.append("4:attr").append(attr.length()).append(':').append(attr);
            }
            head.append("6:length").append('i').append(lengths.get(i)).append('e');
            head.append("4:path").append('l');
            for (String component : paths.get(i)) {
                byte[] c = component.getBytes(StandardCharsets.UTF_8);
                head.append(c.length).append(':').append(component);
            }
            head.append('e');
            head.append('e');
        }
        head.append('e');
        head.append("4:name").append(nameBytes.length).append(':');
        StringBuilder mid = new StringBuilder(128);
        mid.append("12:piece length").append('i').append(PIECE_LENGTH).append('e');
        mid.append("6:pieces").append("40:");
        return assemble(head.toString(), nameBytes, mid.toString());
    }

    /** head + name + mid + 40 zero piece hashes + outer/info closers. */
    private static byte[] assemble(String head, byte[] nameBytes, String mid) {
        byte[] headBytes = head.getBytes(StandardCharsets.ISO_8859_1);
        byte[] midBytes = mid.getBytes(StandardCharsets.ISO_8859_1);
        byte[] tail = "ee".getBytes(StandardCharsets.ISO_8859_1);
        byte[] hashes = new byte[40];
        byte[] rv = new byte[headBytes.length + nameBytes.length + midBytes.length + hashes.length + tail.length];
        int off = 0;
        System.arraycopy(headBytes, 0, rv, off, headBytes.length);
        off += headBytes.length;
        System.arraycopy(nameBytes, 0, rv, off, nameBytes.length);
        off += nameBytes.length;
        System.arraycopy(midBytes, 0, rv, off, midBytes.length);
        off += midBytes.length;
        System.arraycopy(hashes, 0, rv, off, hashes.length);
        off += hashes.length;
        System.arraycopy(tail, 0, rv, off, tail.length);
        return rv;
    }

    private static MetaInfo parse(byte[] torrent) throws Exception {
        return new MetaInfo(new ByteArrayInputStream(torrent));
    }

    @Test
    public void testNameWithSpacesAndUnderscore() throws Exception {
        MetaInfo mi = parse(singleFileTorrent("Zephyr 9_ Wandering Stars"));
        assertEquals("Zephyr 9_ Wandering Stars", mi.getName());
    }

    @Test
    public void testNameWithColon() throws Exception {
        MetaInfo mi = parse(singleFileTorrent("Zephyr 9: Wandering Stars"));
        assertEquals("Zephyr 9: Wandering Stars", mi.getName());
    }

    @Test
    public void testNameSceneStyle() throws Exception {
        MetaInfo mi = parse(singleFileTorrent("Nebula_Runner.Odyssey.2024.1080p.WEBRip.x265-GROUP.mkv"));
        assertEquals("Nebula_Runner.Odyssey.2024.1080p.WEBRip.x265-GROUP.mkv", mi.getName());
    }

    @Test
    public void testNameUtf8() throws Exception {
        MetaInfo mi = parse(singleFileTorrent("Zephyr 9 \u2013 Wandering Stars"));
        assertEquals("Zephyr 9 \u2013 Wandering Stars", mi.getName());
        MetaInfo cyr = parse(singleFileTorrent("\u0417\u0432\u0451\u0437\u0434\u043d\u044b\u0439 \u043f\u0443\u0442\u044c"));
        assertEquals("\u0417\u0432\u0451\u0437\u0434\u043d\u044b\u0439 \u043f\u0443\u0442\u044c", cyr.getName());
    }

    @Test
    public void testNameLeadingDot() throws Exception {
        MetaInfo mi = parse(singleFileTorrent(".hidden"));
        assertEquals(".hidden", mi.getName());
    }

    @Test
    public void testNameDotAndSpace() throws Exception {
        assertEquals(".", parse(singleFileTorrent(".")).getName());
        assertEquals(" ", parse(singleFileTorrent(" ")).getName());
    }

    @Test
    public void testNameVeryLong() throws Exception {
        StringBuilder sb = new StringBuilder(500);
        for (int i = 0; i < 500; i++) {
            sb.append('a');
        }
        MetaInfo mi = parse(singleFileTorrent(sb.toString()));
        assertEquals(sb.toString(), mi.getName());
    }

    @Test
    public void testMultiFilePathsWithSpaces() throws Exception {
        List<List<String>> paths = new ArrayList<>();
        paths.add(Arrays.asList("Zephyr 9_ Wandering Stars", "episode 1.mkv"));
        paths.add(Arrays.asList("Zephyr 9_ Wandering Stars", "bonus material", "featurette 1.mp4"));
        paths.add(Arrays.asList("Zephyr 9_ Wandering Stars", "cover art.jpg"));
        List<Long> lengths = Arrays.asList(20000L, 5000L, 1000L);
        MetaInfo mi = parse(multiFileTorrent("Zephyr 9_ Wandering Stars", paths, lengths, null));
        assertNotNull(mi.getFiles());
        assertEquals(3, mi.getFiles().size());
        assertEquals("Zephyr 9_ Wandering Stars", mi.getFiles().get(0).get(0));
        assertEquals("episode 1.mkv", mi.getFiles().get(0).get(1));
        assertEquals("bonus material", mi.getFiles().get(1).get(1));
        assertEquals("featurette 1.mp4", mi.getFiles().get(1).get(2));
        assertEquals(26000L, mi.getTotalLength());
        assertEquals(3, mi.getLengths().size());
    }

    /**
     * Regression: a torrent with padding files (BEP 47 "attr") on more than one
     * file. The parser used to drop every attr-bearing file after the first,
     * leaving the attribute list shorter than the files list, so
     * isPaddingFile() on a later file threw "Index 4 out of bounds for length 4"
     * during add. Padding files are typically the last entries, and many
     * releases carry one pad per media file, so two or more pads are common.
     */
    @Test
    public void testMultiplePaddingFiles() throws Exception {
        List<List<String>> paths = new ArrayList<>();
        paths.add(Arrays.asList("Zephyr 9_ Wandering Stars", "disk1.pad"));
        paths.add(Arrays.asList("Zephyr 9_ Wandering Stars", "Zephyr 9_ Wandering Stars.mkv"));
        paths.add(Arrays.asList("Zephyr 9_ Wandering Stars", "sample.mkv"));
        paths.add(Arrays.asList("Zephyr 9_ Wandering Stars", "cover.jpg"));
        paths.add(Arrays.asList("Zephyr 9_ Wandering Stars", "disk2.pad"));
        List<Long> lengths = Arrays.asList(42L, 20000L, 5000L, 1000L, 43L);
        List<String> attrs = Arrays.asList("p", null, null, null, "p");
        MetaInfo mi = parse(multiFileTorrent("Zephyr 9_ Wandering Stars", paths, lengths, attrs));
        assertTrue(mi.isPaddingFile(0));
        assertFalse(mi.isPaddingFile(1));
        assertFalse(mi.isPaddingFile(2));
        assertFalse(mi.isPaddingFile(3));
        assertTrue(mi.isPaddingFile(4));
        // re-serialization must keep the attribute list aligned
        MetaInfo reparsed = parse(mi.getTorrentData());
        assertTrue(reparsed.isPaddingFile(0));
        assertTrue(reparsed.isPaddingFile(4));
        assertFalse(reparsed.isPaddingFile(2));
    }

    @Test
    public void testConsecutivePaddingFiles() throws Exception {
        List<List<String>> paths = new ArrayList<>();
        paths.add(Arrays.asList("dir", "a.mkv"));
        paths.add(Arrays.asList("dir", "pad1.pad"));
        paths.add(Arrays.asList("dir", "pad2.pad"));
        paths.add(Arrays.asList("dir", "b.mkv"));
        List<Long> lengths = Arrays.asList(20000L, 42L, 43L, 5000L);
        List<String> attrs = Arrays.asList(null, "p", "p", null);
        MetaInfo mi = parse(multiFileTorrent("dir", paths, lengths, attrs));
        assertFalse(mi.isPaddingFile(0));
        assertTrue(mi.isPaddingFile(1));
        assertTrue(mi.isPaddingFile(2));
        assertFalse(mi.isPaddingFile(3));
        MetaInfo reparsed = parse(mi.getTorrentData());
        assertTrue(reparsed.isPaddingFile(1));
        assertTrue(reparsed.isPaddingFile(2));
        assertFalse(reparsed.isPaddingFile(3));
    }

    @Test
    public void testSinglePaddingFile() throws Exception {
        List<List<String>> paths = new ArrayList<>();
        paths.add(Arrays.asList("dir", "a.mkv"));
        paths.add(Arrays.asList("dir", "pad.pad"));
        paths.add(Arrays.asList("dir", "b.mkv"));
        List<Long> lengths = Arrays.asList(20000L, 42L, 5000L);
        List<String> attrs = Arrays.asList(null, "p", null);
        MetaInfo mi = parse(multiFileTorrent("dir", paths, lengths, attrs));
        assertFalse(mi.isPaddingFile(0));
        assertTrue(mi.isPaddingFile(1));
        assertFalse(mi.isPaddingFile(2));
        MetaInfo reparsed = parse(mi.getTorrentData());
        assertTrue(reparsed.isPaddingFile(1));
    }

    @Test
    public void testPaddingFileOutOfRange() throws Exception {
        List<List<String>> paths = new ArrayList<>();
        paths.add(Arrays.asList("dir", "a.mkv"));
        List<Long> lengths = Arrays.asList(30000L);
        MetaInfo mi = parse(multiFileTorrent("dir", paths, lengths, null));
        assertFalse(mi.isPaddingFile(-1));
        assertFalse(mi.isPaddingFile(1));
        assertFalse(mi.isPaddingFile(2));
    }
}

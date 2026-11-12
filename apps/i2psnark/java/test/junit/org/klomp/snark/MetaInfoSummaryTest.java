package org.klomp.snark;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

import org.klomp.snark.bencode.BEValue;

/**
 * Tests for the info-dictionary summary that replaced the per-entry BEValue dump:
 * the name leads the message, the measurements follow on one continuation line,
 * every value carries its unit, nothing is bracketed, and a key that says nothing
 * (a public torrent's "private") is left out.
 *
 * @since 0.9.72+
 */
public class MetaInfoSummaryTest {

    /** A single-file info dictionary as a torrent writer would build it. */
    private static Map<String, BEValue> singleFile() {
        Map<String, BEValue> info = new HashMap<>();
        info.put("name", new BEValue("OReillyProgrammingEbooks.tar.gz".getBytes()));
        info.put("piece length", new BEValue(Integer.valueOf(32768)));
        // 930 pieces, 20 bytes of hash each
        info.put("pieces", new BEValue(new byte[930 * 20]));
        info.put("length", new BEValue(Long.valueOf(30447527)));
        return info;
    }

    /** The message as the call site builds it. */
    private static String message(Map<String, BEValue> info) {
        return "Torrent info dictionary built for: " + MetaInfo.dictName(info)
               + "\n* " + MetaInfo.infoSummary(info);
    }

    @Test
    public void nullAndEmptySayNothing() {
        assertEquals("none", MetaInfo.infoSummary(null));
        assertEquals("none", MetaInfo.infoSummary(new HashMap<String, BEValue>()));
        assertEquals("none", MetaInfo.dictName(null));
    }

    @Test
    public void messageIsTwoLines() {
        String[] lines = message(singleFile()).split("\n");
        assertEquals(Arrays.toString(lines), 2, lines.length);
        assertEquals("Torrent info dictionary built for: OReillyProgrammingEbooks.tar.gz",
                     lines[0]);
        assertEquals("* Piece Length: 32768 bytes * Pieces: 930 * Length: 30447527 bytes",
                     lines[1]);
    }

    @Test
    public void measurementsAreOneRunOfPairs() {
        assertEquals("Piece Length: 32768 bytes * Pieces: 930 * Length: 30447527 bytes",
                     MetaInfo.infoSummary(singleFile()));
    }

    @Test
    public void nameIsTakenOutSeparately() {
        assertEquals("OReillyProgrammingEbooks.tar.gz", MetaInfo.dictName(singleFile()));
        assertEquals("none", MetaInfo.dictName(new HashMap<String, BEValue>()));
    }

    @Test
    public void publicTorrentDoesNotSaySo() {
        Map<String, BEValue> info = singleFile();
        assertFalse(MetaInfo.infoSummary(info).contains("Private"));
        // an explicit "private = 0" is still a public torrent
        info.put("private", new BEValue(Integer.valueOf(0)));
        assertFalse(MetaInfo.infoSummary(info).contains("Private"));
    }

    @Test
    public void privateTorrentSaysSo() {
        Map<String, BEValue> info = singleFile();
        info.put("private", new BEValue(Integer.valueOf(1)));
        assertTrue(MetaInfo.infoSummary(info).contains("Private: true"));
    }

    @Test
    public void multiFileTorrentCountsItsFiles() {
        Map<String, BEValue> info = new HashMap<>();
        info.put("name", new BEValue("album".getBytes()));
        info.put("piece length", new BEValue(Integer.valueOf(65536)));
        info.put("pieces", new BEValue(new byte[40]));
        List<BEValue> files = new ArrayList<>();
        for (String path : Arrays.asList("disc1.flac", "disc2.flac")) {
            Map<String, BEValue> file = new HashMap<>();
            file.put("path", new BEValue(Arrays.asList(new BEValue(path.getBytes()))));
            file.put("length", new BEValue(Long.valueOf(12345)));
            files.add(new BEValue(file));
        }
        info.put("files", new BEValue(files));
        // no top-level "length" key in a multi-file dictionary, so no total size
        assertEquals("Piece Length: 65536 bytes * Pieces: 2 * Files: 2",
                     MetaInfo.infoSummary(info));
    }

    @Test
    public void nothingIsBracketed() {
        // the console log viewer parses a bracketed token as a destination, and a
        // byte count or a file name is not one
        String s = message(singleFile());
        assertFalse(s, s.contains("["));
        assertFalse(s, s.contains("]"));
    }

    @Test
    public void unknownKeysFallBackToTheirNames() {
        Map<String, BEValue> info = new HashMap<>();
        info.put("merkle", new BEValue(new byte[4]));
        info.put("nodes", new BEValue(new byte[4]));
        assertEquals("merkle, nodes", MetaInfo.infoSummary(info));
    }
}

package org.klomp.snark.web;

import static org.junit.Assert.*;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

/**
 * Tests for I2PSnarkServlet.findCompleteFile: the Storage-metadata lookup
 * that replaced the recursive filesystem walk for the audio-playlist check.
 *
 * @since 0.9.71+
 */
public class FindCompleteFileTest {

    private static final List<String> NAMES = Arrays.asList(
        "MyTorrent/readme.txt",
        "MyTorrent/music/a.mp3",
        "MyTorrent/music/b.flac",
        "MyTorrent/music/sub/c.ogg",
        "MyTorrent/video/v.mkv",
        "MyTorrent/.pad/dummy");

    // index-aligned remaining bytes: 0 == complete
    private static final long[] REMAINING = {0, 0, 4096, 0, 2048, 0};

    private static final java.util.function.Predicate<String> AUDIO =
        n -> n.endsWith(".mp3") || n.endsWith(".flac") || n.endsWith(".ogg");

    @Test
    public void testRootFindsDeepCompleteAudio() {
        assertEquals("MyTorrent/music/a.mp3",
                     I2PSnarkServlet.findCompleteFile(NAMES, REMAINING, "", AUDIO));
    }

    @Test
    public void testSubdirectoryPrefixFilters() {
        // entries outside the prefix are ignored even when they match first;
        // within the prefix, the first complete audio wins (a.mp3 here)
        assertEquals("MyTorrent/music/a.mp3",
                     I2PSnarkServlet.findCompleteFile(NAMES, REMAINING, "MyTorrent/music/", AUDIO));
    }

    @Test
    public void testIncompleteFilesSkipped() {
        // a.mp3 is complete but first; b.flac is incomplete and must be passed over
        List<String> names = Arrays.asList("t/x.mp3", "t/y.flac");
        long[] rem = {0, 999};
        assertEquals("t/x.mp3", I2PSnarkServlet.findCompleteFile(names, rem, "t/", AUDIO));
    }

    @Test
    public void testNoMatchReturnsNull() {
        assertNull(I2PSnarkServlet.findCompleteFile(NAMES, REMAINING, "MyTorrent/nope/", AUDIO));
        // video is complete but not audio per the predicate
        assertNull(I2PSnarkServlet.findCompleteFile(
                Collections.singletonList("MyTorrent/video/v.mkv"),
                new long[] {0}, "MyTorrent/video/", AUDIO));
    }

    @Test
    public void testEmptyList() {
        assertNull(I2PSnarkServlet.findCompleteFile(Collections.<String>emptyList(),
                                                    new long[0], "", AUDIO));
    }

    @Test
    public void testShortCircuitsOnFirstMatch() {
        // order dependence pins the early-return contract
        List<String> names = Arrays.asList("t/first.mp3", "t/second.mp3");
        long[] rem = {0, 0};
        assertEquals("t/first.mp3", I2PSnarkServlet.findCompleteFile(names, rem, "t/", AUDIO));
    }
}

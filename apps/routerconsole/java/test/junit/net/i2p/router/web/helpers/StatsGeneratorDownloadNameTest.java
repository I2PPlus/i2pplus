package net.i2p.router.web.helpers;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Pins the filename offered for a stat's XML export.
 * <p>The name went into the download attribute with only its dots replaced, so a
 * per-tunnel stat named after a nickname carried brackets, spaces and whatever the
 * nickname held - a double quote closed the attribute early and the rest of the
 * markup became the filename. Underscores stand in for the separators and anything
 * HTML-significant is escaped, while a plain stat name still reads the way it did.
 */
public class StatsGeneratorDownloadNameTest {

    /** What the link has always offered for a normal stat, unchanged. */
    @Test
    public void plainNameKeepsItsShape() {
        assertEquals("bw_sendRate", StatsGenerator.downloadName("bw.sendRate"));
        assertEquals("stream_connectSuccessRate",
                     StatsGenerator.downloadName("stream.connectSuccessRate"));
    }

    /** The space in a "stat rate" name is a separator, not part of a filename. */
    @Test
    public void separatorsBecomeUnderscores() {
        assertEquals("tunnel_participating_InBps",
                     StatsGenerator.downloadName("tunnel.participating InBps"));
    }

    /** Brackets are harmless inside a quoted attribute and stay readable. */
    @Test
    public void aTunnelNicknameSurvives() {
        assertEquals("[skank_i2p]_InBps", StatsGenerator.downloadName("[skank.i2p] InBps"));
    }

    /**
     * The defect: a quote in the nickname ended the download attribute, so the
     * filename stopped at the quote and the trailing markup was parsed as attributes.
     */
    @Test
    public void aQuoteCannotCloseTheAttribute() {
        String name = StatsGenerator.downloadName("[a\"b] InBps");
        assertEquals("[a&quot;b]_InBps", name);
        assertEquals("no raw quote may reach the attribute", -1, name.indexOf('"'));
    }

    /** An ampersand in a nickname is an entity introducer, so it must be escaped. */
    @Test
    public void markupCharactersAreEscaped() {
        assertEquals("a&amp;b_InBps", StatsGenerator.downloadName("a&b InBps"));
        assertEquals("[a&lt;b]_InBps", StatsGenerator.downloadName("[a<b] InBps"));
        String name = StatsGenerator.downloadName("[a&b] InBps");
        assertEquals(-1, name.indexOf('<'));
        assertEquals(-1, name.indexOf('"'));
    }
}

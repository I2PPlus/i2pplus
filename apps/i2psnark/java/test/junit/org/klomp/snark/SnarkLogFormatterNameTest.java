package org.klomp.snark;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * Pins the name budget {@link SnarkLogFormatter} applies to a torrent name.
 * <p>A torrent name is user data of no fixed length, and it is the one value on a
 * torrent line that cannot be shortened by rewording the rest of the line. Snark
 * now hands the capped form not only to its log lines but to the standalone app's
 * terminal output as well, where a translated warning and an uncapped name
 * together ran past 200 characters on a line that cannot wrap - so the cap is
 * what keeps that output readable, and it has to stay a cap rather than turn into
 * a truncation that loses the part of the name the operator recognises.
 *
 * @since 0.9.72+
 */
public class SnarkLogFormatterNameTest {

    /** Runs the name checks; the class carries no per-test state. */
    public SnarkLogFormatterNameTest() {}

    /** A scene release name of the kind that started this: long, and full of tags. */
    private static final String SCENE_NAME =
            "Ubuntu.22.04.1.LTS.Desktop.amd64.iso-20220107-SceneRelease";

    /**
     * A torrent whose metadata has not arrived is named by the bare word for
     * "nothing", not by an empty string a log line would render as a gap.
     */
    @Test
    public void nullNameIsNamedNone() {
        assertEquals(SnarkLogFormatter.NONE, SnarkLogFormatter.name(null));
        assertEquals(SnarkLogFormatter.NONE, SnarkLogFormatter.torrent((MetaInfo) null));
    }

    /**
     * A name inside the budget is returned exactly as written, so nothing is cut
     * from a name the line already has room for.
     */
    @Test
    public void nameInsideTheBudgetIsUnchanged() {
        String shortName = "ubuntu-22.04-desktop.iso";
        assertEquals(shortName, SnarkLogFormatter.name(shortName));
        String exact = repeat('x', SnarkLogFormatter.NAME_CHARS);
        assertEquals("a name of exactly the budget must not be cut", exact,
                     SnarkLogFormatter.name(exact));
    }

    /**
     * A name one character over the budget is cut to the budget, dots included -
     * the marker is paid for out of the budget, not added on top of it.
     */
    @Test
    public void cutNameStaysInsideTheBudget() {
        String over = repeat('x', SnarkLogFormatter.NAME_CHARS + 1);
        String cut = SnarkLogFormatter.name(over);
        assertEquals("the cut name plus its marker must fit the budget",
                     SnarkLogFormatter.NAME_CHARS, cut.length());
        assertTrue("the marker marks the cut: " + cut, cut.endsWith("..."));
    }

    /**
     * Every name longer than the budget comes back no wider than the budget, at
     * each length just over it and well past it, so no length slips through with a
     * name the line cannot carry.
     */
    @Test
    public void nothingOverTheBudgetEscapes() {
        for (int len = SnarkLogFormatter.NAME_CHARS + 1; len <= SnarkLogFormatter.NAME_CHARS + 90;
             len++) {
            String cut = SnarkLogFormatter.name(repeat('y', len));
            assertEquals("length " + len, SnarkLogFormatter.NAME_CHARS, cut.length());
        }
    }

    /**
     * A scene release name is cut to the budget but keeps its leading characters,
     * which are the part an operator scans for. Cutting from the end of a long name
     * instead of the start would leave a line that fits and names nothing.
     */
    @Test
    public void sceneReleaseNameIsCutButStaysRecognisable() {
        String cut = SnarkLogFormatter.name(SCENE_NAME);
        assertTrue("the name is over the budget to begin with",
                   SCENE_NAME.length() > SnarkLogFormatter.NAME_CHARS);
        assertEquals(SnarkLogFormatter.NAME_CHARS, cut.length());
        assertTrue("the cut keeps the start of the name: " + cut,
                   SCENE_NAME.startsWith(cut.substring(0, cut.length() - 3)));
        assertTrue("the cut is marked: " + cut, cut.endsWith("..."));
    }

    /**
     * A bracketed tag in a torrent name loses its brackets but keeps its word. The
     * console log viewer links any bracketed value of the right length to a router,
     * so {@code [WEBRip]} would become a link to a router that does not exist.
     */
    @Test
    public void bracketedTagStaysAsPlainText() {
        String name = "[WEBRip] Some Show S01E01";
        String cut = SnarkLogFormatter.name(name);
        assertFalse("no brackets survive for the linkifier to match: " + cut,
                    cut.contains("["));
        assertTrue("the word itself is kept: " + cut, cut.contains("WEBRip"));
    }

    /**
     * Builds a name of {@code len} copies of {@code c}.
     *
     * @param c the character to repeat
     * @param len how many times to repeat it
     * @return the name, exactly {@code len} characters long
     */
    private static String repeat(char c, int len) {
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++)
            sb.append(c);
        return sb.toString();
    }
}

package net.i2p.router.sybil;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The cause recorded for a router the Sybil analysis blocks.
 * <p>
 * The cause is shown in the console's ban table, and the renderer also searches
 * it for an address. It used to carry the blocked RouterInfo's version string
 * as well, in parentheses, and that string is whatever the peer wrote about
 * itself: up to 255 bytes, with markup in it, reaching the console origin's
 * table. The cause names the analysis and the score and nothing else.
 *
 * @since 0.9.72+
 */
public class SybilBanCauseTest {

    /** Runs the cause decisions; it holds no state. */
    public SybilBanCauseTest() {}

    /** The cause names the analysis and the score that blocked the peer. */
    @Test
    public void theCauseNamesTheAnalysisAndTheScore() {
        String cause = Analysis.banCause(75);
        assertTrue(cause, cause.startsWith("Sybil Analysis ("));
        assertTrue(cause, cause.endsWith(" points)"));
        assertTrue(cause, cause.contains("75"));
    }

    /**
     * Nothing in the cause is markup or an attribute delimiter, whatever the
     * score, so the table can render it as text.
     */
    @Test
    public void theCauseCarriesNoMarkup() {
        double[] scores = { Analysis.DEFAULT_BLOCK_THRESHOLD, 75, 100.5, 1234.5 };
        for (double score : scores) {
            String cause = Analysis.banCause(score);
            assertFalse(cause, cause.contains("<"));
            assertFalse(cause, cause.contains(">"));
            assertFalse(cause, cause.contains("\""));
            assertFalse(cause, cause.contains("&"));
        }
    }

    /**
     * The cause has exactly one pair of parentheses, the points tally. The
     * renderer takes the text between the first pair for an address, and a
     * second pair is where the peer's version used to sit.
     */
    @Test
    public void theCauseHasOneParenthesisedGroup() {
        String cause = Analysis.banCause(75);
        assertEquals(cause, 1, count(cause, '('));
        assertEquals(cause, 1, count(cause, ')'));
    }

    /**
     * Counts occurrences of one character.
     *
     * @param s the string to scan
     * @param c the character to count
     * @return how many times it occurs
     */
    private static int count(String s, char c) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == c) {n++;}
        }
        return n;
    }
}

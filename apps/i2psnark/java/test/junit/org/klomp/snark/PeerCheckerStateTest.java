package org.klomp.snark;

import org.junit.Test;
import static org.junit.Assert.*;

/**
 * The words the peer check pass logs for the four choke flags. A reader has to be
 * able to tell whose half of the pair each clause describes, and no clause may be a
 * bare true or false, which the console renders as a glyph.
 *
 * @since 0.9.72+
 */
public class PeerCheckerStateTest {

    /**
     * Test instance; every test builds a clause and reads it back.
     */
    public PeerCheckerStateTest() {
    }

    /** Our half names both what we want and what we do to the peer. */
    @Test
    public void ourHalfNamesOurWantAndOurChoke() {
        assertEquals("We want pieces from it, we are choking it",
                     PeerCheckerTask.ourState(true, true));
        assertEquals("We want pieces from it, we have unchoked it",
                     PeerCheckerTask.ourState(true, false));
        assertEquals("We want nothing from it, we are choking it",
                     PeerCheckerTask.ourState(false, true));
        assertEquals("We want nothing from it, we have unchoked it",
                     PeerCheckerTask.ourState(false, false));
    }

    /** Their half names both what they want and what they do to us. */
    @Test
    public void theirHalfNamesTheirWantAndTheirChoke() {
        assertEquals("It wants pieces from us, it is choking us",
                     PeerCheckerTask.theirState(true, true));
        assertEquals("It wants pieces from us, it has unchoked us",
                     PeerCheckerTask.theirState(true, false));
        assertEquals("It wants nothing from us, it is choking us",
                     PeerCheckerTask.theirState(false, true));
        assertEquals("It wants nothing from us, it has unchoked us",
                     PeerCheckerTask.theirState(false, false));
    }

    /**
     * Neither clause is a bare boolean or a yes/no word, and each says who acts:
     * ours speaks of it, theirs speaks of us.
     */
    @Test
    public void neitherHalfIsABooleanOrAmbiguousAboutTheActor() {
        for (boolean want : new boolean[] {true, false}) {
            for (boolean choke : new boolean[] {true, false}) {
                String ours = PeerCheckerTask.ourState(want, choke);
                String theirs = PeerCheckerTask.theirState(want, choke);
                for (String s : new String[] {ours, theirs}) {
                    assertFalse(s, s.contains("true"));
                    assertFalse(s, s.contains("false"));
                    assertFalse(s, s.endsWith(": yes"));
                    assertFalse(s, s.endsWith(": no"));
                }
                assertTrue(ours, ours.endsWith("it"));
                assertTrue(theirs, theirs.endsWith("us"));
            }
        }
    }
}

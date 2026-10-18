package net.i2p.router.web;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Pins the boundedness of {@link CSSHelper}'s User-Agent memo (LEAK-01). The
 * cache is keyed on a client-supplied User-Agent, so an unbounded map would
 * grow with every unique UA the console ever serves. These tests prove the
 * replacement LRU caps the map and still returns correct decisions.
 *
 * @since 0.9.71+
 */
public class CSSHelperUACacheTest {

    /** A UA the small-browser scan treats as a full browser (IFrame allowed). */
    private static final String BIG_UA =
            "Mozilla/5.0 (X11; Linux x86_64; rv:120.0) Gecko/20100101 Firefox/120.0";
    /** A UA the small-browser scan treats as small (IFrame denied). */
    private static final String SMALL_UA = "Lynx/2.8.9rel.1 libwww-FM/2.14";

    /** A small browser is denied an IFrame, a full browser is allowed one. */
    @Test
    public void testDecisionCorrect() {
        assertTrue("a full browser should be allowed an IFrame",
                   CSSHelper.cachedAllowIFrame(BIG_UA));
        assertFalse("a text browser should be denied an IFrame",
                    CSSHelper.cachedAllowIFrame(SMALL_UA));
    }

    /** Repeated lookups of the same UA must not grow the cache. */
    @Test
    public void testRepeatDoesNotGrow() {
        int before = CSSHelper.uaCacheSize();
        for (int i = 0; i < 50; i++) {
            CSSHelper.cachedAllowIFrame(BIG_UA);
        }
        assertEquals("the same UA must be memoised once", before, CSSHelper.uaCacheSize());
    }

    /**
     * The leak regression: feeding more distinct User-Agents than the cap must
     * not grow the map past {@link CSSHelper#MAX_UA_CACHE}.
     */
    @Test
    public void testBoundedUnderManyDistinctUAs() {
        for (int i = 0; i < CSSHelper.MAX_UA_CACHE * 10; i++) {
            CSSHelper.cachedAllowIFrame("Mozilla/5.0 distinct-agent-" + i);
        }
        assertTrue("UA cache must stay bounded at MAX_UA_CACHE (" + CSSHelper.MAX_UA_CACHE + ")",
                   CSSHelper.uaCacheSize() <= CSSHelper.MAX_UA_CACHE);
    }
}

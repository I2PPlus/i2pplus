package net.i2p.router.web.helpers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Locale;

import org.junit.Test;

/**
 * The filter the stats page and the CSV export match stat names against.
 *
 * <p>Both sides of that match are ASCII - the names as registered and the text
 * typed into the filter box - and the match is case-insensitive, so both are
 * folded to lower case. A fold that follows the JVM's default locale makes the
 * answer depend on the machine: under a Turkish default locale an upper-case I
 * folds to a dotless ı, and a filter naming a stat that contains one matches
 * nothing. The page then comes back empty, which reads as "no such stat" rather
 * than as a filter that was never applied.
 *
 * @since 0.9.72+
 */
public class StatsGeneratorFilterTest {

    /** The filter decisions are static; no context or stat manager needed. */
    public StatsGeneratorFilterTest() {}

    /**
     * The filter is folded, so a name typed in any case finds the stat.
     */
    @Test
    public void testFilterIsFoldedToLowerCase() {
        assertEquals("bandwidth", StatsGenerator.lowerFilter("Bandwidth"));
        assertEquals("bw.sendrate", StatsGenerator.lowerFilter("BW.SendRate"));
        assertEquals("", StatsGenerator.lowerFilter(""));
    }

    /**
     * A stat name is matched against the folded filter with both sides folded,
     * so a name registered in mixed case is found by a lowercase filter.
     */
    @Test
    public void testStatNameMatchesFoldedFilter() {
        assertTrue(StatsGenerator.matchesFilter("router.Bandwidth", "bandwidth"));
        assertTrue(StatsGenerator.matchesFilter("router.uptime", "route"));
        assertFalse(StatsGenerator.matchesFilter("router.uptime", "bandwidth"));
    }

    /**
     * Under a Turkish default locale the filter text is where the fold bites:
     * "Client".toLowerCase() is "cıent", which no stat name contains, so the
     * filter silently matches nothing.
     */
    @Test
    public void testFilterMatchesUnderTurkishLocale() {
        Locale saved = Locale.getDefault();
        try {
            Locale.setDefault(new Locale("tr", "TR"));
            assertEquals("client", StatsGenerator.lowerFilter("Client"));
            assertTrue(StatsGenerator.matchesFilter("router.clientTime",
                                                    StatsGenerator.lowerFilter("Client")));
            assertTrue(StatsGenerator.matchesFilter("router.installationId",
                                                    StatsGenerator.lowerFilter("InstallationID")));
        } finally {
            Locale.setDefault(saved);
        }
    }

    /**
     * The stat name is folded the same way, so a name registered with an
     * upper-case I is still found under a Turkish default locale.
     */
    @Test
    public void testStatNameFoldsUnderTurkishLocale() {
        Locale saved = Locale.getDefault();
        try {
            Locale.setDefault(new Locale("tr", "TR"));
            assertTrue(StatsGenerator.matchesFilter("router.InstallationID", "installationid"));
            assertTrue(StatsGenerator.matchesFilter("tunnel.buildTimeInMs", "timein"));
            assertFalse(StatsGenerator.matchesFilter("router.uptime", "bandwidth"));
        } finally {
            Locale.setDefault(saved);
        }
    }
}

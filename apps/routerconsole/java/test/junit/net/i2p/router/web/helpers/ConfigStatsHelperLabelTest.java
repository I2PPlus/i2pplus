package net.i2p.router.web.helpers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import org.junit.Test;

/**
 * Tests for {@link ConfigStatsHelper#statLabel(String)}.
 *
 * <p>The configstats page used to cut a stat name at its first dot and then run the
 * abbreviation chain over what was left. That reads correctly for a subsystem path such
 * as {@code tunnel.buildTimeout} and destroys a pool's name, which is bracketed and
 * dotted inside the brackets: four services published four labels, all of them
 * "i2p] InBps". These tests pin both halves of the rule: a subsystem prefix goes, a
 * tunnel's name does not.
 *
 * @since 0.9.71+
 */
public class ConfigStatsHelperLabelTest {

    /** The subsystem a dotted stat name lives under is dropped; the section states it. */
    @Test
    public void testSubsystemPrefixIsDropped() {
        assertEquals("fastPeers", ConfigStatsHelper.statLabel("router.fastPeers"));
        assertEquals("buildTimeout", ConfigStatsHelper.statLabel("tunnel.buildTimeout"));
        assertEquals("sendRate", ConfigStatsHelper.statLabel("bw.sendRate"));
    }

    /** The regression: a pool's dots belong to its name, so nothing after them is cut. */
    @Test
    public void testDottedPoolNameIsShownWhole() {
        assertEquals("[harry.i2p] InBps", ConfigStatsHelper.statLabel("[harry.i2p] InBps"));
        assertEquals("[update.skank] OutBps", ConfigStatsHelper.statLabel("[update.skank] OutBps"));
    }

    /** Four dotted services must never collapse onto one shared label. */
    @Test
    public void testFourServicesKeepFourLabels() {
        String[] pools = {"[harry.i2p] InBps", "[skank.i2p] InBps",
                          "[wall.i2p] InBps", "[zzzmirror.i2p] InBps"};
        for (int i = 0; i < pools.length; i++) {
            String label = ConfigStatsHelper.statLabel(pools[i]);
            assertEquals(pools[i], label);
            for (int j = i + 1; j < pools.length; j++) {
                assertNotEquals(label + " collided with " + pools[j],
                                label, ConfigStatsHelper.statLabel(pools[j]));
            }
        }
    }

    /** Abbreviations aimed at stat names must not reach into a tunnel's own name. */
    @Test
    public void testDottedPoolNameResistsTheAbbreviationChain() {
        // "con." is stripped from subsystem paths; "console" is a plausible tunnel name.
        assertEquals("[console.i2p] InBps", ConfigStatsHelper.statLabel("[console.i2p] InBps"));
        assertEquals("[foo.data.i2p] OutBps", ConfigStatsHelper.statLabel("[foo.data.i2p] OutBps"));
    }

    /** A pool without a dot still gets the abbreviation it has always had. */
    @Test
    public void testNoDotPoolNameIsStillAbbreviated() {
        assertEquals("[Expl] InBps", ConfigStatsHelper.statLabel("[Exploratory] InBps"));
        assertEquals("[Purokishi] OutBps", ConfigStatsHelper.statLabel("[Purokishi] OutBps"));
    }

    /** The participating rates are shortened after their subsystem is dropped. */
    @Test
    public void testParticipatingIsShortened() {
        assertEquals("part InBps", ConfigStatsHelper.statLabel("tunnel.participating InBps"));
        assertEquals("part OutBps", ConfigStatsHelper.statLabel("tunnel.participating OutBps"));
    }

    /** A null name renders as nothing rather than throwing in the page. */
    @Test
    public void testNullIsEmpty() {
        assertEquals("", ConfigStatsHelper.statLabel(null));
    }
}

package net.i2p.router.networkdb.kademlia;

import static org.junit.Assert.*;

import net.i2p.router.networkdb.kademlia.PersistentDataStore.RejectDecision;
import net.i2p.router.networkdb.kademlia.PersistentDataStore.RejectRule;
import net.i2p.router.networkdb.kademlia.PersistentDataStore.RiFlags;

import org.junit.Test;

/**
 * What a RouterInfo the store will not persist earns: a ban, a disconnect, a
 * deleted file, or nothing.
 * <p>
 * The ban switches - {@code router.banlistInvalidVersion} and
 * {@code router.banlistLU} - decide whether the identity joins the banlist. The
 * disconnect and the file removal follow from what the peer published, so they
 * hold with the switch off as well. When the disconnect was nested inside the
 * switch, switching a ban off also kept the session connected: with
 * banlistInvalidVersion off a peer publishing an impossible version kept its
 * session indefinitely, and with banlistLU off a low-tier unreachable peer fell
 * through to the plain unreachable rule and was never disconnected at all.
 * <p>
 * No router, no banlist, no timer: the decision is a pure function of the
 * classification flags and the two switches.
 *
 * @since 0.9.72+
 */
public class PersistentDataStoreRejectDecisionTest {

    /** How long after the rejection the session is dropped, as the store schedules it. */
    private static final long DISCONNECT_DELAY = 11L*60*1000;

    /** The fabricated-version ban. */
    private static final long INVALID_VERSION_BAN = 24L*60*60*1000;

    /** The low-tier-unreachable ban. */
    private static final long LU_BAN = 60L*60*1000;

    /**
     * One instance per test method, as JUnit builds a fresh test object for each one.
     *
     * @since 0.9.72+
     */
    public PersistentDataStoreRejectDecisionTest() {}

    /**
     * With the fabricated-version ban off the RouterInfo is still rejected and the
     * session is still dropped - the switch is about the banlist, not the session.
     */
    @Test
    public void testAFabricatedVersionIsRejectedWithTheBanSwitchedOff() {
        RejectDecision d = decide(flags(null, false, false, true, false, false),
                                                              false, false);
        assertEquals(RejectRule.INVALID_VERSION, d._rule);
        assertFalse(d._ban);
        assertTrue("the session was left connected with the ban switched off", d._disconnect);
        assertEquals("Invalid version", d._disconnectReason);
        assertEquals(DISCONNECT_DELAY, d._disconnectDelayMs);
        assertTrue(d._delete);
    }

    /**
     * With the switch on, the same peer also takes the 24-hour ban.
     */
    @Test
    public void testAFabricatedVersionIsBannedWithTheSwitchOn() {
        RejectDecision d = decide(flags(null, false, false, true, false, false),
                                                              true, false);
        assertTrue(d._ban);
        assertEquals(INVALID_VERSION_BAN, d._banMs);
        assertTrue(d._disconnect);
        assertTrue(d._delete);
    }

    /**
     * The same for a low-tier unreachable peer: the ban is switched off, the
     * disconnect is not. It used to fall through to the plain unreachable rule,
     * which deletes the file and never drops the session.
     */
    @Test
    public void testALowTierUnreachablePeerIsDisconnectedWithTheBanSwitchedOff() {
        RejectDecision d = decide(flags("KU", true, false, false, false, false),
                                                              false, false);
        assertEquals(RejectRule.LOW_TIER_UNREACHABLE, d._rule);
        assertFalse(d._ban);
        assertTrue("a low-tier unreachable peer was left connected with the ban switched off",
                   d._disconnect);
        assertEquals("LU Router", d._disconnectReason);
        assertEquals(DISCONNECT_DELAY, d._disconnectDelayMs);
        assertTrue(d._delete);
    }

    /**
     * With the LU ban on, both low tiers are banned for an hour as well, and a
     * peer that publishes no reachability flag at all counts as unreachable.
     */
    @Test
    public void testBothLowTiersAreBannedWithTheSwitchOn() {
        for (String caps : new String[] {"KIU", "LI", "L"}) {
            RejectDecision d = decide(flags(caps, false, false, false, false, false),
                                                                  false, true);
            assertEquals(caps, RejectRule.LOW_TIER_UNREACHABLE, d._rule);
            assertTrue(caps, d._ban);
            assertEquals(caps, LU_BAN, d._banMs);
            assertTrue(caps, d._disconnect);
            assertTrue(caps, d._delete);
        }
    }

    /**
     * A peer that is merely unreachable, and not in a low bandwidth tier, is not
     * disconnected by this path - it is only left off disk. That is the rule the
     * low-tier case used to be mistaken for.
     */
    @Test
    public void testAPlainUnreachablePeerIsOnlyLeftOffDisk() {
        RejectDecision d = decide(flags("PI", true, false, false, false, false),
                                                              true, true);
        assertEquals(RejectRule.UNREACHABLE, d._rule);
        assertFalse(d._ban);
        assertFalse(d._disconnect);
        assertTrue(d._delete);
    }

    /**
     * A slow tier and an old RouterInfo are left off disk and nothing else.
     */
    @Test
    public void testSlowTierAndOldAreOnlyLeftOffDisk() {
        RejectDecision slow = decide(flags("KR", false, true, false, true, false),
                                                                 true, true);
        assertEquals(RejectRule.SLOW_TIER, slow._rule);
        assertFalse(slow._ban);
        assertFalse(slow._disconnect);
        assertTrue(slow._delete);

        RejectDecision old = decide(flags("PR", false, true, false, false, true),
                                                                true, true);
        assertEquals(RejectRule.TOO_OLD, old._rule);
        assertFalse(old._ban);
        assertFalse(old._disconnect);
        assertTrue(old._delete);
    }

    /**
     * A RouterInfo older than the one on disk earns nothing at all: the file stays.
     */
    @Test
    public void testANewerLocalCopyKeepsTheFile() {
        RejectDecision d = decide(flags("PR", false, true, false, false, false),
                                                              true, true);
        assertEquals(RejectRule.NONE, d._rule);
        assertFalse(d._ban);
        assertFalse(d._disconnect);
        assertFalse(d._delete);
    }

    /**
     * The K tier on its own, the bandwidth class the unified predicate newly
     * covers and the one the luBanKTier count is keyed on. A peer publishing both
     * low tiers is not counted, or the widening would be counted twice.
     */
    @Test
    public void testKTierOnlyIsWhatTheCounterCounts() {
        assertTrue(PersistentDataStore.isKTierOnly("K"));
        assertTrue(PersistentDataStore.isKTierOnly("KIU"));
        assertFalse(PersistentDataStore.isKTierOnly("L"));
        assertFalse(PersistentDataStore.isKTierOnly("LIU"));
        assertFalse(PersistentDataStore.isKTierOnly("KL"));
        assertFalse(PersistentDataStore.isKTierOnly("PI"));
        assertFalse(PersistentDataStore.isKTierOnly(""));
        assertFalse(PersistentDataStore.isKTierOnly(null));
    }

    /**
     * The store's decision, named short so each case fits on its own line.
     *
     * @param f the classification flags for the peer
     * @param invalidVersionBanOn {@code router.banlistInvalidVersion}
     * @param luBanOn {@code router.banlistLU}
     * @return what the peer earned, never null
     */
    private static RejectDecision decide(RiFlags f, boolean invalidVersionBanOn, boolean luBanOn) {
        return PersistentDataStore.rejectDecision(f, invalidVersionBanOn, luBanOn);
    }

    /**
     * The classification flags for a peer, with only what the decision reads set.
     *
     * @param caps the capability string, null for none
     * @param unreachable true if the peer was marked unreachable
     * @param reachable true if the peer published a reachability flag
     * @param invalidVersion true if the published version is above every release
     * @param slow true if the bandwidth tier is dropped at the current netdb size
     * @param old true if the RouterInfo predates the oldest version the store keeps
     * @return the flags to decide from, never null
     */
    private static RiFlags flags(String caps, boolean unreachable, boolean reachable,
                                 boolean invalidVersion, boolean slow, boolean old) {
        return new RiFlags("0.9.71", caps, null, null, null, false, unreachable, reachable,
                           old, invalidVersion, false, slow, false, false);
    }
}

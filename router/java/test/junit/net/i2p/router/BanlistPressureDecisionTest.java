package net.i2p.router;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

import net.i2p.data.Hash;

/**
 * The pressure relief the banlist applies to itself when the count of bans
 * gets high, and the ceiling that bounds the list at all. Both are decisions
 * about which bans may be shortened or dropped, so the boundary values are
 * pinned here.
 * <p>
 * The pass that halves every long ban above a count threshold used to include
 * the permanent ones: seven passes turned a 181-day ban into a soft one and
 * eight took the hostility off it, so 8,001 RouterInfos with fresh keys and an
 * unspecified network — the cheapest ban there is, at 30 days each — undid the
 * signed feed's bans and the operator's own in under an hour. Shortening the
 * flood is the point of the pass; undoing what the flood is standing on is
 * not.
 *
 * @since 0.9.72+
 */
public class BanlistPressureDecisionTest {

    /** The instant every case is judged at. */
    private static final long NOW = 1_700_000_000_000L;

    /** Runs the decisions; it holds no state. */
    public BanlistPressureDecisionTest() {}

    // ---- isPermanentBan -----------------------------------------------------

    /** The permanent class is exempt from the pressure relief. */
    @Test
    public void foreverBanIsPermanent() {
        assertTrue(Banlist.isPermanentBan(Banlist.BANLIST_DURATION_FOREVER));
    }

    /**
     * The cheapest ban a peer can buy for itself — one RouterInfo with an
     * unspecified network — is not, which is what keeps the relief able to
     * shorten a flood.
     */
    @Test
    public void cheapFloodBanIsNotPermanent() {
        assertFalse(Banlist.isPermanentBan(Banlist.BANLIST_DURATION_NO_NETWORK));
        assertFalse(Banlist.isPermanentBan(Banlist.BANLIST_DURATION_PARTIAL));
    }

    /** The threshold sits between the cheapest flood ban and a permanent one. */
    @Test
    public void permanentThresholdSeparatesTheTwoClasses() {
        long cheapest = Banlist.BANLIST_DURATION_NO_NETWORK;
        long permanent = Banlist.BANLIST_DURATION_FOREVER;
        assertFalse(Banlist.isPermanentBan(cheapest));
        assertTrue(Banlist.isPermanentBan(permanent));
        // anything the relief exempts has to be out of reach of a peer that
        // only wants to fill the list up
        assertTrue(Banlist.BANLIST_PERMANENT_REMAINING > cheapest);
        assertTrue(Banlist.BANLIST_PERMANENT_REMAINING <= permanent);
    }

    // ---- halvedExpiry -------------------------------------------------------

    /** The aggressive pass leaves a permanent ban's expiry exactly where it was. */
    @Test
    public void aggressivePassSparesPermanentBans() {
        long expireOn = NOW + Banlist.BANLIST_DURATION_FOREVER;
        assertEquals(expireOn, Banlist.halvedExpiry(expireOn, NOW));
    }

    /** A permanent ban survives as many passes as the flood keeps bringing. */
    @Test
    public void permanentBanSurvivesRepeatedPasses() {
        long expireOn = NOW + Banlist.BANLIST_DURATION_FOREVER;
        for (int i = 0; i < 12; i++) {
            expireOn = Banlist.halvedExpiry(expireOn, NOW);
        }
        long remaining = expireOn - NOW;
        assertEquals(Banlist.BANLIST_DURATION_FOREVER, remaining);
        assertTrue("still a hard ban", Banlist.isPermanentBan(remaining));
    }

    /** The flood's own 30-day bans are halved, which is what the pass is for. */
    @Test
    public void aggressivePassHalvesCheapBans() {
        long expireOn = NOW + Banlist.BANLIST_DURATION_NO_NETWORK;
        assertEquals(NOW + Banlist.BANLIST_DURATION_NO_NETWORK / 2,
                     Banlist.halvedExpiry(expireOn, NOW));
    }

    /** A ban at the cutoff is not over it, so the pass leaves it alone. */
    @Test
    public void aggressivePassStartsAboveTheCutoff() {
        assertEquals(NOW + Banlist.BAN_HALVE_CUTOFF,
                     Banlist.halvedExpiry(NOW + Banlist.BAN_HALVE_CUTOFF, NOW));
        assertEquals(NOW + (Banlist.BAN_HALVE_CUTOFF + 1) / 2,
                     Banlist.halvedExpiry(NOW + Banlist.BAN_HALVE_CUTOFF + 1, NOW));
    }

    // ---- scaledExpiry -------------------------------------------------------

    /** A ban inside the eligible window is scaled by the count. */
    @Test
    public void proportionalPassScalesEligibleBans() {
        long expireOn = NOW + 2 * Banlist.BAN_REDUCTION_MAX / 3;
        assertEquals(NOW + Banlist.BAN_REDUCTION_MAX / 6,
                     Banlist.scaledExpiry(expireOn, NOW, 0.25));
    }

    /** Scaling never takes a ban below half the eligibility floor. */
    @Test
    public void proportionalPassFloorsAtHalfTheMinimum() {
        long expireOn = NOW + Banlist.BAN_REDUCTION_MIN;
        assertEquals(NOW + Banlist.BAN_REDUCTION_MIN / 2,
                     Banlist.scaledExpiry(expireOn, NOW, 0.25));
    }

    /**
     * Outside the window nothing is scaled: a short ban is already cheap, and
     * a long one is either permanent or about to be halved by the aggressive
     * pass instead.
     */
    @Test
    public void proportionalPassSparesBansOutsideTheWindow() {
        long shortBan = NOW + Banlist.BAN_REDUCTION_MIN - 1;
        assertEquals(shortBan, Banlist.scaledExpiry(shortBan, NOW, 0.25));
        long week = NOW + Banlist.BAN_REDUCTION_MAX;
        assertEquals(week, Banlist.scaledExpiry(week, NOW, 0.25));
        long forever = NOW + Banlist.BANLIST_DURATION_FOREVER;
        assertEquals(forever, Banlist.scaledExpiry(forever, NOW, 0.25));
    }

    // ---- the ceiling --------------------------------------------------------

    /**
     * On a desktop router the ceiling is above the count at which the duration
     * relief starts, so the relief gets to shorten a flood before bans start
     * being dropped outright. A slow router holds fewer bans than that — it
     * bounds the list by dropping the newest of them instead of shortening
     * them, which is what upstream does with its smaller map.
     */
    @Test
    public void ceilingMatchesTheRouterItRunsOn() {
        assertTrue(Banlist.MAX_BAN_ENTRIES > Banlist.BAN_COUNT_HIGH);
        assertTrue(Banlist.MAX_BAN_ENTRIES_SLOW > 0);
        assertTrue(Banlist.MAX_BAN_ENTRIES_SLOW < Banlist.MAX_BAN_ENTRIES);
        int ceiling = Banlist.maxBanEntries();
        assertTrue(ceiling == Banlist.MAX_BAN_ENTRIES || ceiling == Banlist.MAX_BAN_ENTRIES_SLOW);
    }

    /** Trimming stops short of the ceiling, so new bans can still get in. */
    @Test
    public void trimTargetLeavesRoomForNewBans() {
        int target = Banlist.pruneTarget(Banlist.MAX_BAN_ENTRIES);
        assertTrue(target < Banlist.MAX_BAN_ENTRIES);
        assertTrue(target > Banlist.MAX_BAN_ENTRIES / 2);
        assertEquals(Banlist.MAX_BAN_ENTRIES - Banlist.MAX_BAN_ENTRIES / 4, target);
    }

    /** The trim drops the newest bans first and never a permanent one. */
    @Test
    public void trimDropsTheNewestCheapBansFirst() {
        Map<Hash, Banlist.Entry> entries = new LinkedHashMap<Hash, Banlist.Entry>();
        long cheap = Banlist.BANLIST_DURATION_NO_NETWORK;
        long permanent = Banlist.BANLIST_DURATION_FOREVER;
        Banlist.Entry oldCheap = entry(NOW + cheap, NOW - 10 * 60 * 1000L);
        Banlist.Entry newCheap = entry(NOW + cheap, NOW - 60 * 1000L);
        Banlist.Entry oldPermanent = entry(NOW + permanent, NOW - 24 * 60 * 60 * 1000L);
        entries.put(hash((byte) 1), oldCheap);
        entries.put(hash((byte) 2), newCheap);
        entries.put(hash((byte) 3), oldPermanent);

        List<Map.Entry<Hash, Banlist.Entry>> droppable = Banlist.droppableBans(entries, NOW);
        assertEquals("the permanent ban is not a candidate", 2, droppable.size());
        assertSame(newCheap, droppable.get(0).getValue());
        assertSame(oldCheap, droppable.get(1).getValue());
    }

    /** A list with nothing droppable is trimmed by nothing. */
    @Test
    public void trimHasNoCandidatesWhenEveryBanIsPermanent() {
        Map<Hash, Banlist.Entry> entries = new LinkedHashMap<Hash, Banlist.Entry>();
        entries.put(hash((byte) 4), entry(NOW + Banlist.BANLIST_DURATION_FOREVER, NOW - 1000L));
        assertTrue(Banlist.droppableBans(entries, NOW).isEmpty());
    }

    /**
     * Builds an entry with the two fields the trim reads.
     *
     * @param expireOn when the ban expires
     * @param addedOn when the ban was first recorded
     * @return an entry carrying only the expiry and the start time, which is
     *         all the trim looks at
     */
    private static Banlist.Entry entry(long expireOn, long addedOn) {
        Banlist.Entry e = new Banlist.Entry();
        e.expireOn = expireOn;
        e.addedOn = addedOn;
        return e;
    }

    /**
     * A distinct hash for one entry of the map.
     *
     * @param b the byte every position of the hash is filled with
     * @return a hash filled with that byte, so each call with a different byte
     *         is a different peer
     */
    private static Hash hash(byte b) {
        byte[] data = new byte[Hash.HASH_LENGTH];
        Arrays.fill(data, b);
        return new Hash(data);
    }
}

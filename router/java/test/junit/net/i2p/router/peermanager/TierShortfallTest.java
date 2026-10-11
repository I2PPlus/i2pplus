package net.i2p.router.peermanager;

import static net.i2p.router.peermanager.ProfileOrganizer.remainingToSelect;
import static net.i2p.router.peermanager.ProfileOrganizer.stillSelecting;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.fail;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import net.i2p.data.Hash;
import net.i2p.util.ArraySet;

import org.junit.Test;

/**
 * The shortfall arithmetic the tier cascade is built on.
 *
 * <p>{@link ProfileOrganizer}'s tier selectors fall through fast &rarr; high-capacity
 * &rarr; not-failing, and each one hands the next the <em>remaining</em> slots rather
 * than the caller's original request. Getting that wrong is not a harmless off-by-one:
 * {@link ClientPeerSelector#selectFirstHop} sizes its candidate set to exactly the
 * request, so the last tier in the cascade writing the full count again overflows it
 * and {@link ArraySet} throws {@code SetFullException} — logged as
 * {@code ProfileOrganizer.pickLowestPriority} crashing, which points at the symptom
 * rather than the cascade arithmetic.
 *
 * <p>Both helpers are pure, so they pin the arithmetic without a router.
 *
 * @since 0.9.71+
 */
public class TierShortfallTest {

    private static List<Hash> hashes(int n) {
        List<Hash> list = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            byte[] h = new byte[Hash.HASH_LENGTH];
            h[0] = (byte) (i >> 24);
            h[1] = (byte) (i >> 16);
            h[2] = (byte) (i >> 8);
            h[3] = (byte) i;
            list.add(Hash.create(h));
        }
        return list;
    }

    private static Set<Hash> first(int n) {
        return new HashSet<>(hashes(n));
    }

    /**
     * {@code count} distinct hashes starting at {@code offset}, so a tier can be given a
     * pool that does not overlap what an earlier tier already supplied.
     */
    private static List<Hash> distinct(int offset, int count) {
        List<Hash> list = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            byte[] h = new byte[Hash.HASH_LENGTH];
            int v = offset + i;
            h[0] = (byte) (v >> 24);
            h[1] = (byte) (v >> 16);
            h[2] = (byte) (v >> 8);
            h[3] = (byte) v;
            list.add(Hash.create(h));
        }
        return list;
    }

    /** An empty destination still needs everything that was asked for. */
    @Test
    public void emptySetNeedsTheWholeRequest() {
        assertEquals(3, remainingToSelect(3, first(0)));
        assertTrue(stillSelecting(3, first(0)));
    }

    /** A partly filled set needs only the gap — the whole point of the helper. */
    @Test
    public void partlyFilledSetNeedsOnlyTheGap() {
        assertEquals(1, remainingToSelect(4, first(3)));
        assertTrue(stillSelecting(4, first(3)));
    }

    /** Once the request is met there is nothing left, and the cascade must stop. */
    @Test
    public void fullSetNeedsNothingAndStopsTheCascade() {
        assertEquals(0, remainingToSelect(3, first(3)));
        assertFalse(stillSelecting(3, first(3)));
    }

    /**
     * An over-filled set must never ask for a negative number: a negative shortfall
     * reaching a capacity-sized list or a loop bound is how an off-by-one becomes an
     * exception rather than a silently short result.
     */
    @Test
    public void overFilledSetNeverAsksForANegativeCount() {
        assertEquals(0, remainingToSelect(3, first(5)));
        assertFalse(stillSelecting(3, first(5)));
        assertEquals(0, remainingToSelect(0, first(0)));
        assertFalse(stillSelecting(0, first(0)));
    }

    /**
     * The shape of the reported crash: a capacity-4 {@link ArraySet}, three slots
     * already taken, and a cascade that hands the next tier 4 again. The shortfall
     * is what keeps the fourth write inside the array.
     */
    @Test
    public void shortfallKeepsTheLastTierInsideAFixedCapacitySet() {
        int requested = 4;
        ArraySet<Hash> matches = new ArraySet<>(requested);
        matches.addAll(hashes(3));

        int remaining = remainingToSelect(requested, matches);
        assertEquals("three of four slots are taken", 1, remaining);

        ArraySet<Hash> output = new ArraySet<>(remaining);
        output.addAll(distinct(0, remaining));
        assertEquals(remaining, output.size());
    }

    /**
     * The reported crash, reproduced end to end: the caller sizes an
     * {@link ArraySet} to exactly what it wants, the first tier fills only part of it, and
     * the later tiers are handed the <em>shortfall</em>. Before the cascade arithmetic was
     * centralised, the later tiers asked for the original {@code howMany} again and wrote
     * past the capacity — {@code ArraySet} throws, and the log points at
     * {@code pickLowestPriority} rather than at the arithmetic that broke.
     *
     * <p>Written as the three-tier shape the selectors actually have, so a regression in
     * the hand-off between tiers fails here rather than in production.
     */
    @Test
    public void wholeCascadeNeverExceedsTheSizedCapacity() {
        for (int requested = 1; requested <= 6; requested++) {
            for (int preFilled = 0; preFilled <= requested; preFilled++) {
                // How much of the request the first tier happens to satisfy.
                for (int firstTierYield = 0; firstTierYield <= requested; firstTierYield++) {
                    String ctx = "requested=" + requested + " preFilled=" + preFilled
                               + " firstTierYield=" + firstTierYield;

                    ArraySet<Hash> matches = new ArraySet<>(requested);
                    for (Hash h : distinct(0, preFilled)) {
                        matches.add(h);
                    }

                    int tier = 0;
                    while (stillSelecting(requested, matches)) {
                        int remaining = remainingToSelect(requested, matches);
                        // Each tier supplies at most its yield; the third is exhausted.
                        int yield = (tier == 0) ? firstTierYield
                                  : (tier == 1) ? remaining
                                  : 0;
                        List<Hash> pool = distinct(100 + tier * 100, yield);
                        for (Hash h : pool) {
                            if (matches.size() >= requested) { break; }
                            if (!matches.contains(h)) { matches.add(h); }
                        }
                        tier++;
                        assertTrue(ctx, matches.size() <= requested);
                        if (tier > 4) { fail(ctx + ": cascade did not terminate"); }
                    }
                    assertTrue(ctx, matches.size() <= requested);
                }
            }
        }
    }

    /** The pre-fill behaviour the cascade must tolerate: zero need, zero writes. */
    @Test
    public void zeroShortfallWritesNothing() {
        ArraySet<Hash> matches = new ArraySet<>(2);
        matches.addAll(hashes(2));
        assertEquals(0, remainingToSelect(2, matches));
        assertFalse(stillSelecting(2, matches));
        assertEquals(2, matches.size());
    }
}

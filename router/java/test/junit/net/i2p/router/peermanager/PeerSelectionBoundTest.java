package net.i2p.router.peermanager;

import static net.i2p.router.peermanager.ProfileOrganizer.pickLowestPriority;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import net.i2p.data.Hash;
import net.i2p.util.ArraySet;

import org.junit.Test;

/**
 * The write bound in {@link ProfileOrganizer#pickLowestPriority}.
 *
 * <p>Several peer-selection callers allocate a fixed-capacity {@link ArraySet} sized to exactly
 * the number of peers they request, and hand that same number to this method as
 * {@code howMany}. {@link ArraySet} throws {@code SetFullException} when written past capacity, so
 * the bound {@code picks <= howMany} is what keeps those callers from throwing at runtime:
 *
 * <ul>
 * <li>{@code ClientPeerSelector} builds four fallback sets as {@code new ArraySet<>(needed)} and
 *     passes {@code needed} as {@code howMany}
 * <li>{@code ExploratoryPeerSelector} builds {@code new ArraySet<>(1)} and asks for one peer
 * </ul>
 *
 * <p>That bound used to be implicit in a private method on a context-heavy class, so nothing could
 * check it without a running router. It is pinned here instead, on {@link Hash} values only, so
 * the test needs no {@code RouterContext} and cannot be skipped the way a
 * {@code PeerProfile}-based test silently can.
 *
 * @since 0.9.71+
 */
public class PeerSelectionBoundTest {

    /** The bound that keeps every {@code new ArraySet<>(needed)} caller safe. */
    private static final int HOW_MANY = 3;

    private static List<Hash> cands(int n) {
        List<Hash> list = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            byte[] h = new byte[Hash.HASH_LENGTH];
            // vary the first four bytes so every hash is distinct and reproducible
            h[0] = (byte) (i >> 24);
            h[1] = (byte) (i >> 16);
            h[2] = (byte) (i >> 8);
            h[3] = (byte) i;
            list.add(Hash.create(h));
        }
        return list;
    }

    private static float[] priorities(int n) {
        float[] p = new float[n];
        for (int i = 0; i < n; i++) { p[i] = i + 1.0f; }
        return p;
    }

    /** The invariant itself: many candidates, few requested, only the few are written. */
    @Test
    public void picksNeverExceedHowMany() {
        Set<Hash> matches = new HashSet<>();
        int picked = pickLowestPriority(cands(5000), priorities(5000), HOW_MANY, matches);
        assertEquals(HOW_MANY, picked);
        assertEquals(HOW_MANY, matches.size());
    }

    /**
     * The form the fallback callers actually use: a set whose capacity is exactly the requested
     * count. Overflowing it would throw rather than return a wrong answer, so the loop runs
     * against a real {@link ArraySet} of that capacity to prove it stays inside.
     */
    @Test
    public void doesNotOverflowAnExactlySizedArraySet() {
        int needed = 3;
        ArraySet<Hash> matches = new ArraySet<>(needed);
        pickLowestPriority(cands(5000), priorities(5000), needed, matches);
        assertEquals(needed, matches.size());
    }

    /** A capacity-1 set, as ExploratoryPeerSelector builds, asking for one peer. */
    @Test
    public void doesNotOverflowACapacityOneArraySet() {
        ArraySet<Hash> matches = new ArraySet<>(1);
        pickLowestPriority(cands(200), priorities(200), 1, matches);
        assertEquals(1, matches.size());
    }

    /** Asking for more peers than exist yields every candidate and no exception. */
    @Test
    public void cannotPickMoreThanExist() {
        List<Hash> cands = cands(4);
        Set<Hash> matches = new HashSet<>();
        int picked = pickLowestPriority(cands, priorities(4), 50, matches);
        assertEquals(4, picked);
        assertEquals(new HashSet<>(cands), matches);
    }

    /** A duplicate pick would defeat the point of the set and inflate the caller's count. */
    @Test
    public void picksAreDistinct() {
        Set<Hash> matches = new HashSet<>();
        pickLowestPriority(cands(1000), priorities(1000), 8, matches);
        assertEquals(8, matches.size());
    }

    /** Ties everywhere must not collapse the result or spin on one candidate. */
    @Test
    public void equalPrioritiesStillYieldHowManyDistinctPicks() {
        float[] flat = new float[50];
        Set<Hash> matches = new HashSet<>();
        int picked = pickLowestPriority(cands(50), flat, 6, matches);
        assertEquals(6, picked);
        assertEquals(6, matches.size());
    }

    /** The chosen peers must be real candidates, not something invented by the loop. */
    @Test
    public void picksComeFromTheCandidateSet() {
        Set<Hash> pool = new HashSet<>(cands(200));
        Set<Hash> matches = new HashSet<>();
        pickLowestPriority(cands(200), priorities(200), HOW_MANY, matches);
        assertTrue(pool.containsAll(matches));
    }

    /** Lowest priority wins, which is the whole point of the method. */
    @Test
    public void picksInAscendingPriorityOrder() {
        List<Hash> cands = cands(5);
        // the loop reorders candidates in place to prevent re-picking, so the
        // expectation has to be captured before the call rather than read back after
        Hash lowest = cands.get(4);
        Set<Hash> matches = new HashSet<>();
        pickLowestPriority(cands, new float[] {5f, 4f, 3f, 2f, 1f}, 1, matches);
        assertEquals(1, matches.size());
        assertTrue("the lowest priority should win", matches.contains(lowest));
    }

    // ---- degenerate inputs --------------------------------------------------

    @Test
    public void askingForNothingPicksNothing() {
        Set<Hash> matches = new HashSet<>();
        assertEquals(0, pickLowestPriority(cands(100), priorities(100), 0, matches));
        assertTrue(matches.isEmpty());
    }

    /** A negative request is nonsense but must not become an index error or a huge write. */
    @Test
    public void negativeHowManyPicksNothing() {
        Set<Hash> matches = new HashSet<>();
        assertEquals(0, pickLowestPriority(cands(100), priorities(100), -5, matches));
        assertTrue(matches.isEmpty());
    }

    @Test
    public void emptyCandidatesPickNothing() {
        Set<Hash> matches = new HashSet<>();
        assertEquals(0, pickLowestPriority(new ArrayList<Hash>(), new float[0], 3, matches));
        assertTrue(matches.isEmpty());
    }

    @Test
    public void singleCandidateIsPicked() {
        Set<Hash> only = new HashSet<>(cands(1));
        Set<Hash> matches = new HashSet<>();
        assertEquals(1, pickLowestPriority(new ArrayList<>(only), priorities(1), 3, matches));
        assertEquals(only, matches);
    }

    /**
     * The fallback callers pass a set they may already have partially filled, so the return
     * value must reflect entries genuinely added rather than entries merely considered.
     */
    @Test
    public void returnValueCountsOnlyNewAdditions() {
        List<Hash> cands = cands(10);
        // priorities ascend, so index 0 is the lowest and is certainly among the first three picks
        Hash best = cands.get(0);
        Set<Hash> matches = new HashSet<>();
        matches.add(best);
        int picked = pickLowestPriority(cands, priorities(10), 3, matches);
        assertEquals("the pre-seeded best peer was already present", 2, picked);
        assertEquals(3, matches.size());
    }

    /**
     * Restate the bound across a range of request sizes, against both set types the callers use,
     * to catch any size where it silently breaks.
     */
    @Test
    public void boundHoldsAcrossRequestSizes() {
        for (int howMany = 0; howMany <= 12; howMany++) {
            for (int candidateCount : new int[] {1, 2, 5, 12, 13, 100}) {
                int expected = Math.min(howMany, candidateCount);
                String ctx = "howMany=" + howMany + " candidates=" + candidateCount;
                Set<Hash> asHashSet = new HashSet<>();
                assertEquals(ctx, expected,
                    pickLowestPriority(cands(candidateCount), priorities(candidateCount), howMany, asHashSet));
                assertEquals(ctx, expected, asHashSet.size());
                if (expected > 0) {
                    ArraySet<Hash> asArraySet = new ArraySet<>(expected);
                    pickLowestPriority(cands(candidateCount), priorities(candidateCount), howMany, asArraySet);
                    assertEquals(ctx, expected, asArraySet.size());
                }
            }
        }
    }
}

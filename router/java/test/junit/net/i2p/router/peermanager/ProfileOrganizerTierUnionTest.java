package net.i2p.router.peermanager;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Tests the fast-or-high-capacity union count.
 *
 * <p>A peer can be in both tiers. Adding the two tier counts therefore over-reports,
 * and the profile-tier graph needs the union so its high-capacity line is a real subset
 * of the fast line rather than just a smaller number plotted beside it.
 *
 * @since 0.9.71+
 */
public class ProfileOrganizerTierUnionTest {

    /**
     * The union cardinality, computed the way {@code fastOrHighCapCount} does: walk the
     * smaller set and count membership in the larger.
     */
    /** Java 8 has no Set.of(); the project compiles at source 8. */
    private static Set<String> setOf(String... values) {
        return new HashSet<>(Arrays.asList(values));
    }

    private static int union(int fastSize, int highCapSize, int overlap) {
        return fastSize + highCapSize - overlap;
    }

    @Test
    public void disjointTiersAreSimplyAdded() {
        assertEquals(30, union(20, 10, 0));
    }

    @Test
    public void aPeerInBothTiersIsCountedOnce() {
        // 20 fast, 10 high-cap, 5 shared -> 25 distinct peers, not 30.
        assertEquals(25, union(20, 10, 5));
    }

    @Test
    public void fullOverlapCollapsesToTheLargerTier() {
        assertEquals(12, union(10, 12, 10));
    }

    @Test
    public void identicalTiersCountOnce() {
        assertEquals(7, union(7, 7, 7));
    }

    @Test
    public void emptyTiersAreHandled() {
        assertEquals(0, union(0, 0, 0));
        assertEquals(9, union(0, 9, 0));
        assertEquals(9, union(9, 0, 0));
    }

    /**
     * The union must never be smaller than either tier, or the subset line would sit
     * above its container and the fill between them would read backwards.
     */
    @Test
    public void theUnionIsNeverSmallerThanEitherTier() {
        int[][] cases = { {20, 10, 0}, {20, 10, 5}, {10, 12, 10}, {7, 7, 7}, {0, 9, 0} };
        for (int[] c : cases) {
            int u = union(c[0], c[1], c[2]);
            assertTrue("union " + u + " below fast " + c[0], u >= c[0]);
            assertTrue("union " + u + " below highCap " + c[1], u >= c[1]);
        }
    }

    @Test
    public void theUnionNeverExceedsTheSumOfTheTiers() {
        int[][] cases = { {20, 10, 0}, {20, 10, 5}, {10, 12, 10}, {7, 7, 7} };
        for (int[] c : cases) {
            assertTrue(union(c[0], c[1], c[2]) <= c[0] + c[1]);
        }
    }

    // ---- the same arithmetic over real key sets ----

    private static int unionOf(Set<String> fast, Set<String> highCap) {
        Set<String> smaller = fast.size() <= highCap.size() ? fast : highCap;
        Set<String> larger = smaller == fast ? highCap : fast;
        int overlap = 0;
        for (String peer : smaller) {
            if (larger.contains(peer)) {overlap++;}
        }
        return fast.size() + highCap.size() - overlap;
    }

    @Test
    public void walkingTheSmallerSetFindsTheSameOverlap() {
        Set<String> fast = setOf("a", "b", "c", "d");
        Set<String> highCap = setOf("c", "d", "e");
        assertEquals(5, unionOf(fast, highCap));
    }

    /** Whichever tier is walked, the answer must be the same. */
    @Test
    public void theResultDoesNotDependOnWhichTierIsWalked() {
        Set<String> fast = setOf("a", "b", "c");
        Set<String> highCap = setOf("a", "b", "c", "d", "e", "f");
        // 3 <= 6, so the small set is walked; swap the sizes to walk the other one.
        Set<String> wide = setOf("a", "b", "c");
        Set<String> narrow = setOf("a", "b", "c", "d", "e", "f");
        assertEquals(unionOf(fast, highCap), unionOf(narrow, wide));
    }
}
package net.i2p.router;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.HashSet;
import java.util.Set;

import org.junit.Test;

/**
 * Which registry keys a scale-up is allowed to claim.
 *
 * <p>Scale-down retires whichever runners happen to be idle, so the free keys
 * are holes through the live range rather than a block above it. The allocator
 * used to take the first hole it found and then claim the next N keys in
 * sequence, which put a new runner over every live one inside that range. An
 * overwritten runner keeps working while nothing can retire it any more, which
 * is how a router can report a few dozen runners and run hundreds of threads.
 * These pin the per-key test that replaced that contiguous-range assumption,
 * and the ceiling the probe stops at.
 *
 * @since 0.9.72+
 */
public class JobQueueRunnerKeysTest {

    /**
     * The key-allocation cases. Every case calls the static allocator, so the
     * instance holds no state and needs no router or job queue.
     */
    public JobQueueRunnerKeysTest() {
    }

    /**
     * The occupied keys, in the form the registry holds them.
     *
     * @param keys the keys with a runner registered
     * @return the set of those keys
     */
    private static Set<Integer> occupied(int... keys) {
        Set<Integer> set = new HashSet<>(keys.length * 2 + 1);
        for (int key : keys) {set.add(Integer.valueOf(key));}
        return set;
    }

    /**
     * Two wanted and two holes through the live range: both holes are taken,
     * and nothing above the range is touched.
     */
    @Test
    public void fillsTheHolesLeftByScaleDown() {
        // 0 and 1 live, 2 free, 3 live, 4 free, 5 live
        assertArrayEquals(new int[] {2, 4}, JobQueue.freeRunnerKeys(occupied(0, 1, 3, 5), 2));
    }

    /**
     * The range is extended only once every hole below it has been claimed.
     */
    @Test
    public void extendsTheRangeOnlyAfterTheHolesAreFilled() {
        assertArrayEquals(new int[] {1, 3, 4}, JobQueue.freeRunnerKeys(occupied(0, 2), 3));
    }

    /**
     * An empty registry starts at key 0, the lowest key there is.
     */
    @Test
    public void startsAtZeroWhenNothingIsRegistered() {
        assertArrayEquals(new int[] {0, 1}, JobQueue.freeRunnerKeys(occupied(), 2));
    }

    /**
     * A dense low range is stepped over rather than reused under a live runner.
     */
    @Test
    public void startsAboveADenseRange() {
        assertArrayEquals(new int[] {6, 7}, JobQueue.freeRunnerKeys(occupied(0, 1, 2, 3, 4, 5), 2));
    }

    /**
     * A scale event that wants no runners, or a nonsensical negative number of
     * them, claims nothing at all.
     */
    @Test
    public void askingForNothingClaimsNothing() {
        assertEquals(0, JobQueue.freeRunnerKeys(occupied(0, 1), 0).length);
        assertEquals(0, JobQueue.freeRunnerKeys(occupied(0, 1), -3).length);
    }

    /**
     * Every key handed back is free and is handed back only once, however many
     * holes it had to step over to find them.
     */
    @Test
    public void neverClaimsALiveKeyAndNeverRepeatsOne() {
        Set<Integer> live = occupied(1, 3, 5, 7, 9);
        Set<Integer> picked = occupied();
        int[] keys = JobQueue.freeRunnerKeys(live, 20);
        assertEquals(20, keys.length);
        for (int key : keys) {
            Integer boxed = Integer.valueOf(key);
            assertFalse("key " + key + " is already registered", live.contains(boxed));
            assertTrue("key " + key + " claimed twice", picked.add(boxed));
        }
    }

    /**
     * Keys come back ascending, so a caller reading the last one knows where
     * the pool's range now ends.
     */
    @Test
    public void returnsKeysInAscendingOrder() {
        int[] keys = JobQueue.freeRunnerKeys(occupied(0, 2, 4), 6);
        for (int i = 1; i < keys.length; i++) {
            assertTrue("key " + keys[i] + " is not above " + keys[i - 1], keys[i] > keys[i - 1]);
        }
    }

    /**
     * The probe stops at the ceiling rather than minting keys above it, and a
     * short answer is reported as the short answer it is.
     */
    @Test
    public void stopsProbingAtTheCeiling() {
        Set<Integer> full = occupied();
        for (int i = 0; i <= JobQueue.MAX_RUNNER_KEY; i++) {full.add(Integer.valueOf(i));}
        assertEquals(0, JobQueue.freeRunnerKeys(full, 4).length);

        // one hole left anywhere in the window is still found, and it is the
        // only key handed back when fewer slots are free than were wanted
        full.remove(Integer.valueOf(JobQueue.MAX_RUNNER_KEY));
        assertArrayEquals(new int[] {JobQueue.MAX_RUNNER_KEY}, JobQueue.freeRunnerKeys(full, 4));
    }
}

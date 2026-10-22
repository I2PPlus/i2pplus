package net.i2p.router.tunnel.pool;

import static org.junit.Assert.*;

import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import net.i2p.data.Hash;

import org.junit.Test;

/**
 * Tests the housekeeping pass over the first-hop failure history.
 * <p>
 * The map is written whenever a build fails at its first hop, and its only
 * removal was the lookup that consults it. A peer that fails once and then
 * leaves the network is never consulted again, so its entry stayed for the
 * router's uptime and the map grew with every departed peer. The sweep is
 * capped and time-based, so what matters is not just that stale entries go but
 * that live penalties do not - dropping one re-admits a peer that is still
 * failing.
 *
 * @since 0.9.72+
 */
public class FirstHopFailurePruneTest {

    private static final long COOLDOWN = 2 * 60 * 1000L;
    private static final long NOW = 10 * 60 * 60 * 1000L;
    private static final int CAP = 8;

    /**
     * Deterministic 32-byte hash with every byte set to the given value.
     *
     * @param val the byte value to fill with
     * @return a hash usable as a map key
     */
    private static Hash hash(int val) {
        byte[] b = new byte[Hash.HASH_LENGTH];
        Arrays.fill(b, (byte) val);
        Hash h = new Hash();
        h.setData(b);
        return h;
    }

    /**
     * A history holding n entries, each with 3 failures last seen at when.
     *
     * @param n number of entries
     * @param when last-failure timestamp to store
     * @return a fresh map
     */
    private static Map<Hash, long[]> history(int n, long when) {
        Map<Hash, long[]> m = new ConcurrentHashMap<>();
        for (int i = 0; i < n; i++) {
            m.put(hash(i + 1), new long[] {3, when});
        }
        return m;
    }

    /**
     * Inside the cap the map is cheap to carry, so the sweep must not run at
     * all - a sweep on every build pass is the anti-pattern this avoids.
     */
    @Test
    public void testWithinTheCapNothingIsTouched() {
        Map<Hash, long[]> m = history(CAP, NOW - COOLDOWN - 1);

        assertEquals(0, BuildExecutor.pruneFirstHopFailures(m, NOW, COOLDOWN, CAP));
        assertEquals(CAP, m.size());
    }

    /**
     * Past the cap, the peers that can no longer affect a selection go and the
     * ones still inside the cooldown window stay.
     */
    @Test
    public void testOverTheCapDropsOnlyExpiredEntries() {
        Map<Hash, long[]> m = history(CAP, NOW);
        m.put(hash(50), new long[] {2, NOW - COOLDOWN - 5000});
        m.put(hash(51), new long[] {2, NOW - 1000});

        assertEquals(1, BuildExecutor.pruneFirstHopFailures(m, NOW, COOLDOWN, CAP));
        assertNull("departed peer kept", m.get(hash(50)));
        assertNotNull("live peer pruned", m.get(hash(51)));
        assertEquals(CAP + 1, m.size());
    }

    /**
     * The cap is a cap, not a target: at the cap nothing runs, one past it the
     * sweep runs.
     */
    @Test
    public void testCapBoundary() {
        Map<Hash, long[]> at = history(CAP, NOW - COOLDOWN - 1);
        assertEquals(0, BuildExecutor.pruneFirstHopFailures(at, NOW, COOLDOWN, CAP));
        assertEquals(CAP, at.size());

        Map<Hash, long[]> over = history(CAP + 1, NOW - COOLDOWN - 1);
        assertEquals(CAP + 1, BuildExecutor.pruneFirstHopFailures(over, NOW, COOLDOWN, CAP));
        assertTrue(over.isEmpty());
    }

    /**
     * An entry exactly one cooldown old is still usable and stays; one
     * millisecond past is not. The lookup uses the same comparison, so a prune
     * that disagreed with it would drop penalties the selector still honours.
     */
    @Test
    public void testCooldownBoundaryIsInclusive() {
        Map<Hash, long[]> m = history(CAP, NOW);
        m.put(hash(100), new long[] {3, NOW - COOLDOWN});
        m.put(hash(101), new long[] {3, NOW - COOLDOWN - 1});

        assertEquals(1, BuildExecutor.pruneFirstHopFailures(m, NOW, COOLDOWN, CAP));
        assertNotNull("entry at the window edge pruned", m.get(hash(100)));
        assertNull("entry past the window kept", m.get(hash(101)));
    }

    /**
     * An entry created by the lookup but never written carries no timestamp, so
     * it reads as long expired and goes with the rest.
     */
    @Test
    public void testNeverWrittenEntryIsDropped() {
        Map<Hash, long[]> m = history(CAP, NOW);
        m.put(hash(60), new long[] {0, 0});

        assertEquals(1, BuildExecutor.pruneFirstHopFailures(m, NOW, COOLDOWN, CAP));
        assertNull(m.get(hash(60)));
    }

    /**
     * A surviving entry keeps the penalty it had earned - the sweep must not
     * reset counts while it is clearing the map.
     */
    @Test
    public void testSurvivingEntriesKeepTheirCount() {
        Map<Hash, long[]> m = history(CAP, NOW);
        m.put(hash(70), new long[] {7, NOW - 500});
        m.put(hash(71), new long[] {1, NOW - COOLDOWN - 1});

        BuildExecutor.pruneFirstHopFailures(m, NOW, COOLDOWN, CAP);

        assertArrayEquals(new long[] {7, NOW - 500}, m.get(hash(70)));
    }

    /**
     * A burst of distinct first-hop failures inside one cooldown window is live
     * data, so the sweep leaves it alone even past the cap. The map overshoots
     * for the length of one window and comes back down after it.
     */
    @Test
    public void testAFreshBurstIsNotTrimmed() {
        Map<Hash, long[]> m = history(4 * CAP, NOW);

        assertEquals(0, BuildExecutor.pruneFirstHopFailures(m, NOW, COOLDOWN, CAP));
        assertEquals(4 * CAP, m.size());
    }
}

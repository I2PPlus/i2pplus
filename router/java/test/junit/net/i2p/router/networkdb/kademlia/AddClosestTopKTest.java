package net.i2p.router.networkdb.kademlia;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;

import net.i2p.data.Hash;
import net.i2p.kademlia.XORComparator;

import org.junit.Test;

/**
 * Tests the candidate widening of {@link IterativeSearchJob}: it must add the
 * closest peers by XOR distance, and nothing else.
 * <p>
 * The selection runs on the first failed lookup of a key, over the whole netdb,
 * so it was rewritten from a full sort to a bounded top-k. That rewrite is only
 * safe if the set it produces is identical, which is what the brute-force
 * comparison below pins - a heap that keeps the wrong end of the distance range
 * still returns exactly cap entries, so a size check alone would pass.
 *
 * @since 0.9.72+
 */
public class AddClosestTopKTest {

    private static Hash hash(int seed) {
        byte[] b = new byte[Hash.HASH_LENGTH];
        b[0] = (byte) seed;
        b[1] = (byte) (seed >> 8);
        b[2] = (byte) (seed >> 16);
        return new Hash(b);
    }

    private static Hash random(Random r) {
        byte[] b = new byte[Hash.HASH_LENGTH];
        r.nextBytes(b);
        return new Hash(b);
    }

    /**
     * The cap closest, computed the slow obvious way, to compare against.
     *
     * @param all candidates, with us and key already excluded
     * @param rkey routing key
     * @param cap how many to take
     * @return the cap closest to rkey
     */
    private static Set<Hash> bruteForceClosest(List<Hash> all, Hash rkey, int cap) {
        List<Hash> sorted = new ArrayList<>(all);
        sorted.sort(new XORComparator<>(rkey));
        return new HashSet<>(sorted.subList(0, Math.min(cap, sorted.size())));
    }

    /**
     * Against a brute-force sort over a netdb-sized population, the bounded
     * selection must return exactly the same peers - not merely the same count.
     */
    @Test
    public void testSelectsExactlyTheClosestCapPeers() {
        Random r = new Random(0x5eedL);
        Hash rkey = random(r);
        Hash us = random(r);
        Hash key = random(r);
        List<Hash> all = new ArrayList<>();
        all.add(us);
        all.add(key);
        for (int i = 0; i < 500; i++) {all.add(random(r));}

        int cap = 25;
        Set<Hash> out = new TreeSet<>(new XORComparator<>(rkey));
        IterativeSearchJob.addClosest(out, all, rkey, us, key, cap);

        List<Hash> eligible = new ArrayList<>(all);
        eligible.remove(us);
        eligible.remove(key);
        assertEquals(cap, out.size());
        assertEquals("must pick the same peers a full sort would",
                     bruteForceClosest(eligible, rkey, cap), out);
    }

    /**
     * Us and the lookup target are excluded even when they are the two closest
     * hashes to the routing key - asking ourselves, or the peer that already
     * failed to answer, is the whole reason the exclusion exists.
     */
    @Test
    public void testUsAndTargetExcludedEvenWhenClosest() {
        Hash rkey = hash(1);
        Hash us = hash(1);      // distance 0
        Hash key = hash(1);     // distance 0
        List<Hash> all = new ArrayList<>();
        all.add(us);
        all.add(key);
        for (int i = 2; i < 20; i++) {all.add(hash(i));}

        Set<Hash> out = new HashSet<>();
        IterativeSearchJob.addClosest(out, all, rkey, us, key, 5);

        assertFalse(out.contains(us));
        assertFalse(out.contains(key));
        assertEquals(5, out.size());
    }

    /**
     * A cap at or above the population adds everyone but us and the target -
     * the bounded heap must not drop entries once it stops overflowing.
     */
    @Test
    public void testCapAbovePopulationAddsAllEligible() {
        Hash rkey = hash(0);
        Hash us = hash(7);
        Hash key = hash(8);
        List<Hash> all = new ArrayList<>();
        all.add(us);
        all.add(key);
        for (int i = 1; i < 12; i++) {all.add(hash(i));}

        Set<Hash> out = new HashSet<>();
        IterativeSearchJob.addClosest(out, all, rkey, us, key, 100);

        // hash(1)..hash(11) less us and key
        assertEquals(9, out.size());
        assertFalse(out.contains(us));
        assertFalse(out.contains(key));
    }

    /**
     * A non-positive cap must add nothing. The caller passes twice the search
     * limit, so this is a guard rather than a live path - but a heap built with
     * a zero capacity is an IllegalArgumentException waiting to happen.
     */
    @Test
    public void testNonPositiveCapAddsNothing() {
        Hash rkey = hash(0);
        Hash us = hash(1);
        Hash key = hash(2);
        List<Hash> all = new ArrayList<>();
        for (int i = 3; i < 13; i++) {all.add(hash(i));}

        Set<Hash> out = new HashSet<>();
        IterativeSearchJob.addClosest(out, all, rkey, us, key, 0);
        IterativeSearchJob.addClosest(out, all, rkey, us, key, -5);
        assertTrue(out.isEmpty());
    }

    /**
     * An empty netdb (a client DB, or before initialization) must be a no-op,
     * not a failure - widening is a last resort on a search that is already
     * failing.
     */
    @Test
    public void testEmptyPopulationAddsNothing() {
        Set<Hash> out = new HashSet<>();
        IterativeSearchJob.addClosest(out, new ArrayList<>(), hash(0), hash(1), hash(2), 10);
        assertTrue(out.isEmpty());
    }
}

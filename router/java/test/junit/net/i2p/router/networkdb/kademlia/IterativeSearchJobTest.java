package net.i2p.router.networkdb.kademlia;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;

import org.junit.Test;

import net.i2p.data.Hash;
import net.i2p.kademlia.XORComparator;

public class IterativeSearchJobTest {

    @Test
    public void testSkipsWhenAlternativesRemain() {
        assertTrue(IterativeSearchJob.shouldSkipPeer(true, false, false, 2));
        assertTrue(IterativeSearchJob.shouldSkipPeer(false, true, false, 5));
        assertTrue(IterativeSearchJob.shouldSkipPeer(false, false, true, 3));
        assertTrue(IterativeSearchJob.shouldSkipPeer(true, true, true, 10));
    }

    @Test
    public void testNeverSkipsLastCandidate() {
        assertFalse(IterativeSearchJob.shouldSkipPeer(true, false, false, 1));
        assertFalse(IterativeSearchJob.shouldSkipPeer(false, true, false, 1));
        assertFalse(IterativeSearchJob.shouldSkipPeer(false, false, true, 1));
        assertFalse(IterativeSearchJob.shouldSkipPeer(true, true, true, 1));
    }

    @Test
    public void testNeverSkipsOnNoSoftSignals() {
        assertFalse(IterativeSearchJob.shouldSkipPeer(false, false, false, 100));
        assertFalse(IterativeSearchJob.shouldSkipPeer(false, false, false, 1));
    }

    @Test
    public void testZeroRemainingNeverSkips() {
        assertFalse(IterativeSearchJob.shouldSkipPeer(true, false, false, 0));
        assertFalse(IterativeSearchJob.shouldSkipPeer(true, true, true, 0));
    }

    /**
     * Beta-confirmation widening: the closest peers by XOR from the routing
     * key are added, us and the target are excluded, and the cap holds.
     *
     * @since 0.9.71+
     */
    @Test
    public void testAddClosestPrefersNearestAndCaps() {
        Random r = new Random(42);
        Hash rkey = random(r), us = random(r), key = random(r);
        List<Hash> all = new ArrayList<>();
        all.add(us);
        all.add(key);
        for (int i = 0; i < 50; i++) all.add(random(r));
        Set<Hash> out = new TreeSet<>(new XORComparator<>(rkey));
        IterativeSearchJob.addClosest(out, all, rkey, us, key, 10);
        assertEquals(10, out.size());
        assertFalse(out.contains(us));
        assertFalse(out.contains(key));
        // the 10 added must be the 10 closest of the 50 candidates
        List<Hash> sorted = new ArrayList<>(all);
        sorted.remove(us);
        sorted.remove(key);
        sorted.sort(new XORComparator<>(rkey));
        for (int i = 0; i < 10; i++) assertTrue(out.contains(sorted.get(i)));
    }

    private static Hash random(Random r) {
        byte[] b = new byte[Hash.HASH_LENGTH];
        r.nextBytes(b);
        return new Hash(b);
    }
}

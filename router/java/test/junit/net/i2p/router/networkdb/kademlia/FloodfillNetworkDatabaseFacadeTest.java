package net.i2p.router.networkdb.kademlia;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import net.i2p.data.Hash;

import org.junit.Test;

public class FloodfillNetworkDatabaseFacadeTest {

    private static Hash hash(int n) {
        byte[] data = new byte[Hash.HASH_LENGTH];
        data[0] = (byte) n;
        return Hash.create(data);
    }

    @Test
    public void testPrefersNonRecentlyQueried() {
        List<Hash> shuffled = new ArrayList<>();
        Hash h1 = hash(1);
        Hash h2 = hash(2);
        Hash h3 = hash(3);
        Hash h4 = hash(4);
        shuffled.add(h1);
        shuffled.add(h2);
        shuffled.add(h3);
        shuffled.add(h4);
        Set<Hash> recentlyQueried = new HashSet<>();
        recentlyQueried.add(h1);
        recentlyQueried.add(h3);

        Set<Hash> rv = FloodfillNetworkDatabaseFacade.selectStoreParticipants(shuffled, 2, recentlyQueried);
        assertEquals(2, rv.size());
        assertTrue(rv.contains(h2));
        assertTrue(rv.contains(h4));
    }

    @Test
    public void testTopsUpWithRecentlyQueried() {
        List<Hash> shuffled = new ArrayList<>();
        Hash h1 = hash(1);
        Hash h2 = hash(2);
        shuffled.add(h1);
        shuffled.add(h2);
        Set<Hash> recentlyQueried = new HashSet<>();
        recentlyQueried.add(h1);
        recentlyQueried.add(h2);

        Set<Hash> rv = FloodfillNetworkDatabaseFacade.selectStoreParticipants(shuffled, 2, recentlyQueried);
        assertEquals(2, rv.size());
        assertTrue(rv.contains(h1));
        assertTrue(rv.contains(h2));
    }

    @Test
    public void testPartialTopUp() {
        List<Hash> shuffled = new ArrayList<>();
        Hash h1 = hash(1);
        Hash h2 = hash(2);
        Hash h3 = hash(3);
        shuffled.add(h1);
        shuffled.add(h2);
        shuffled.add(h3);
        Set<Hash> recentlyQueried = new HashSet<>();
        recentlyQueried.add(h1);
        recentlyQueried.add(h3);

        Set<Hash> rv = FloodfillNetworkDatabaseFacade.selectStoreParticipants(shuffled, 3, recentlyQueried);
        assertEquals(3, rv.size());
        assertTrue(rv.contains(h1));
        assertTrue(rv.contains(h2));
        assertTrue(rv.contains(h3));
    }

    @Test
    public void testCapsAtConcurrent() {
        List<Hash> shuffled = new ArrayList<>();
        Set<Hash> all = new HashSet<>();
        for (int i = 1; i <= 10; i++) {
            Hash h = hash(i);
            shuffled.add(h);
            all.add(h);
        }

        Set<Hash> rv = FloodfillNetworkDatabaseFacade.selectStoreParticipants(shuffled, 3, Collections.<Hash>emptySet());
        assertEquals(3, rv.size());
        assertTrue(all.containsAll(rv));
    }

    @Test
    public void testEmptyInput() {
        Set<Hash> rv = FloodfillNetworkDatabaseFacade.selectStoreParticipants(
            Collections.<Hash>emptyList(), 3, Collections.<Hash>emptySet());
        assertTrue(rv.isEmpty());
    }

    @Test
    public void testZeroConcurrent() {
        List<Hash> shuffled = new ArrayList<>();
        shuffled.add(hash(1));
        shuffled.add(hash(2));
        Set<Hash> rv = FloodfillNetworkDatabaseFacade.selectStoreParticipants(
            shuffled, 0, Collections.<Hash>emptySet());
        assertTrue(rv.isEmpty());
    }

    @Test
    public void testConcurrentAboveSizeReturnsAll() {
        List<Hash> shuffled = new ArrayList<>();
        Hash h1 = hash(1);
        Hash h2 = hash(2);
        shuffled.add(h1);
        shuffled.add(h2);

        Set<Hash> rv = FloodfillNetworkDatabaseFacade.selectStoreParticipants(
            shuffled, 10, Collections.<Hash>emptySet());
        assertEquals(2, rv.size());
        assertTrue(rv.contains(h1));
        assertTrue(rv.contains(h2));
    }

    @Test
    public void testNoDuplicatesInResult() {
        List<Hash> shuffled = new ArrayList<>();
        for (int i = 1; i <= 6; i++) {
            shuffled.add(hash(i));
        }
        Set<Hash> recentlyQueried = new HashSet<>();
        recentlyQueried.add(hash(1));
        recentlyQueried.add(hash(2));

        Set<Hash> rv = FloodfillNetworkDatabaseFacade.selectStoreParticipants(shuffled, 4, recentlyQueried);
        assertEquals(4, rv.size());
    }
}
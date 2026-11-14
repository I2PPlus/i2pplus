package org.klomp.snark.dht;

import static org.junit.Assert.*;

import java.util.List;
import net.i2p.I2PAppContext;
import net.i2p.data.Hash;
import org.junit.Test;

/**
 * Tests the DHT tracker BEP 33 bloom filter caching: filters are built once per torrent and
 * reused until they age out, the seed/non-seed split matches the stored peers, and torrents
 * without peers yield no filters.
 */
public class DHTTrackerTest {

    private static final InfoHash IH = new InfoHash(new byte[20]);

    /**
     * One instance per test method, as JUnit builds a fresh test object for each one.
     */
    public DHTTrackerTest() {}

    /**
     * An InfoHash distinct from {@link #IH}.
     *
     * @return a fresh info hash differing from {@link #IH} in the first byte
     */
    private static InfoHash otherHash() {
        byte[] b = new byte[20];
        b[0] = 1;
        return new InfoHash(b);
    }

    /**
     * Filters are built from the stored seed/peer split and cached until they age out.
     *
     * @throws Exception if the 200 ms ageing sleep is interrupted
     */
    @Test
    public void testBloomFilterCache() throws Exception {
        DHTTracker tracker = new DHTTracker(I2PAppContext.getGlobalContext(), 150);
        byte[] seed = new byte[32];
        seed[0] = 1;
        byte[] peer = new byte[32];
        peer[0] = 2;
        tracker.announce(IH, new Hash(seed), true);
        tracker.announce(IH, new Hash(peer), false);
        BloomFilter[] filters = tracker.getBloomFilters(IH);
        assertNotNull(filters);
        assertTrue(filters[0].contains(seed)); // seed filter has the seed
        assertFalse(filters[0].contains(peer));
        assertTrue(filters[1].contains(peer)); // peer filter has the leecher
        assertFalse(filters[1].contains(seed));
        // Cached: a swarm change within the window is not picked up
        tracker.announce(IH, new Hash(new byte[32]), false);
        assertSame(filters[0], tracker.getBloomFilters(IH)[0]);
        // Aged out: the swarm change is picked up by a rebuild
        Thread.sleep(200);
        BloomFilter[] rebuilt = tracker.getBloomFilters(IH);
        assertNotNull(rebuilt);
        assertNotSame(filters[0], rebuilt[0]);
    }

    /** Torrents without stored peers yield no filters and no cached entry. */
    @Test
    public void testBloomFilterEmpty() {
        DHTTracker tracker = new DHTTracker(I2PAppContext.getGlobalContext(), 60000);
        assertNull(tracker.getBloomFilters(otherHash()));
        byte[] peer = new byte[32];
        peer[0] = 3;
        tracker.announce(otherHash(), new Hash(peer), false);
        assertNotNull(tracker.getBloomFilters(otherHash()));
        tracker.unannounce(otherHash(), new Hash(peer));
        assertNull(tracker.getBloomFilters(otherHash()));
    }

    /** Global cap is a base allowance plus a per-torrent budget. */
    @Test
    public void testGetMaxPeers() {
        assertEquals(DHTTracker.MAX_PEERS, DHTTracker.getMaxPeers(0));
        assertEquals(DHTTracker.MAX_PEERS + DHTTracker.MAX_PEERS_PER_TORRENT, DHTTracker.getMaxPeers(1));
        assertEquals(
                DHTTracker.MAX_PEERS + 10 * DHTTracker.MAX_PEERS_PER_TORRENT,
                DHTTracker.getMaxPeers(10));
        assertTrue(DHTTracker.getMaxPeers(200) > DHTTracker.MAX_PEERS);
    }

    /** Expiry longens the sparser the store: more headroom, less churn. */
    @Test
    public void testGetMaxExpireTimeTiers() {
        long minute = 60 * 1000L;
        long hour = 60 * minute;
        assertEquals(4 * hour, DHTTracker.getMaxExpireTime(DHTTracker.MAX_PEERS_PER_TORRENT));
        assertEquals(2 * hour, DHTTracker.getMaxExpireTime(2 * DHTTracker.MAX_PEERS_PER_TORRENT - 1));
        assertEquals(1 * hour, DHTTracker.getMaxExpireTime(4 * DHTTracker.MAX_PEERS_PER_TORRENT - 1));
        assertEquals(30 * minute, DHTTracker.getMaxExpireTime(4 * DHTTracker.MAX_PEERS_PER_TORRENT));
        // Never below the MIN_EXPIRE_TIME floor once tightening kicks in
        assertEquals(4 * hour, DHTTracker.getMaxExpireTime(0));
    }

    /**
     * getPeers() returns a mutable list even when empty: KRPC removes the
     * requesting peer from the result, and an immutable emptyList() threw
     * UnsupportedOperationException on every inbound get_peers for a torrent
     * with no stored peers.
     */
    @Test
    public void testGetPeersResultIsMutable() {
        DHTTracker tracker = new DHTTracker(I2PAppContext.getGlobalContext());
        // no entry for this torrent
        List<Hash> empty = tracker.getPeers(otherHash(), 10, false);
        assertTrue(empty.isEmpty());
        empty.remove(new Hash(new byte[32])); // must not throw
        // entry exists but max <= 0
        tracker.announce(IH, new Hash(new byte[32]), false);
        List<Hash> capped = tracker.getPeers(IH, 0, false);
        assertTrue(capped.isEmpty());
        capped.remove(new Hash(new byte[32])); // must not throw
        // non-empty result is mutable too
        List<Hash> peers = tracker.getPeers(IH, 10, false);
        assertEquals(1, peers.size());
        peers.remove(0);
    }

    /**
     * A peer the checker re-announces every pass stays one entry, and its seed flag only
     * ever moves one way. The checker announces us on every pass, so the same peer with
     * the same seed flag is not news and is not logged; the announce only says anything
     * when the peer joins the torrent's list or starts seeding it, and both of those
     * judgements read this state.
     */
    @Test
    public void testAReannouncedPeerStaysOneEntryAndOnlyBecomesASeed() {
        DHTTracker tracker = new DHTTracker(I2PAppContext.getGlobalContext(), 0);
        byte[] raw = new byte[32];
        raw[1] = 7;
        Hash hash = new Hash(raw);
        tracker.announce(IH, hash, false);
        tracker.announce(IH, hash, false);
        BloomFilter[] leech = tracker.getBloomFilters(IH);
        assertNotNull(leech);
        assertFalse("a leech is not in the seed filter", leech[0].contains(raw));
        assertTrue("a leech is in the peer filter", leech[1].contains(raw));
        // the same peer announces as a seed: still one entry, and the seed filter gains it
        tracker.announce(IH, hash, true);
        assertTrue("the peer became a seed", tracker.getBloomFilters(IH)[0].contains(raw));
        // a later announce that does not know the seed status must not undo it
        tracker.announce(IH, hash, false);
        assertTrue("false must not trump true", tracker.getBloomFilters(IH)[0].contains(raw));
        assertEquals("one entry for the peer", 1, tracker.getPeers(IH, 10, false).size());
    }
}

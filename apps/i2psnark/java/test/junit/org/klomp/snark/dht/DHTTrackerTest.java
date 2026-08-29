package org.klomp.snark.dht;

import static org.junit.Assert.*;

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

    /** An InfoHash distinct from {@link #IH}. */
    private static InfoHash otherHash() {
        byte[] b = new byte[20];
        b[0] = 1;
        return new InfoHash(b);
    }

    /** Filters are built from the stored seed/peer split and cached until they age out. */
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
}

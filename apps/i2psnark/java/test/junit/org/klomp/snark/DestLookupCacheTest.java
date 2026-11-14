package org.klomp.snark;

import static org.junit.Assert.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import net.i2p.I2PAppContext;
import net.i2p.client.I2PClientFactory;
import net.i2p.data.Base32;
import net.i2p.data.Destination;
import net.i2p.data.Hash;
import org.junit.Test;

/**
 * Tests for the cache of <code>.b32.i2p</code> destination lookups. A base32 address is only a
 * hash, so resolving it costs up to fifteen seconds of whatever thread asked, and the tracker,
 * the DHT and PEX keep re-advertising the same peers for the life of a torrent - so without a
 * cache every dial to a hash-only peer id pays for the same lookup over again. A miss is kept
 * too, briefly, because it costs what a hit costs.
 *
 * @since 0.9.72+
 */
public class DestLookupCacheTest {

    /**
     * One instance per test method, as JUnit builds a fresh test object for each one.
     *
     * @since 0.9.72+
     */
    public DestLookupCacheTest() {}

    /**
     * A distinct hash for each number, for filling the cache without generating keys.
     *
     * @param i the number to make a hash out of
     * @return a 20-byte hash that differs for every i
     */
    private static Hash hash(int i) {
        byte[] b = new byte[Hash.HASH_LENGTH];
        b[0] = (byte) (i >> 24);
        b[1] = (byte) (i >> 16);
        b[2] = (byte) (i >> 8);
        b[3] = (byte) i;
        return Hash.create(b);
    }

    /**
     * A generated destination, for the one test that needs a real object to cache.
     *
     * @return a destination with a fresh key pair
     * @throws Exception if the keys cannot be generated
     */
    private static Destination destination() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        I2PClientFactory.createClient().createDestination(out);
        return Destination.create(new ByteArrayInputStream(out.toByteArray()));
    }

    /**
     * A destination we resolved is held for hours; a hash that did not resolve is held for
     * minutes, so a peer that comes back under a new key is dialable again soon.
     *
     * @throws Exception if the destination's keys cannot be generated
     */
    @Test
    public void testAResolvedDestinationIsHeldLongerThanAMiss() throws Exception {
        assertTrue(I2PSnarkUtil.destCacheTtl(destination())
                   > I2PSnarkUtil.destCacheTtl(null));
    }

    /**
     * An answer is used up to its expiry and not after it, and the expiry instant itself is
     * already stale.
     */
    @Test
    public void testAnAnswerStopsBeingUsedWhenItExpires() {
        long now = 1_700_000_000_000L;
        assertTrue(I2PSnarkUtil.destCacheFresh(now + 1, now));
        assertFalse(I2PSnarkUtil.destCacheFresh(now, now));
        assertFalse(I2PSnarkUtil.destCacheFresh(now - 1, now));
    }

    /**
     * A base32 address names the hash it carries; anything else has no hash to cache under.
     */
    @Test
    public void testOnlyABase32AddressHasAHash() {
        Hash h = hash(0x11);
        String b32 = Base32.encode(h.getData()) + ".b32.i2p";
        assertEquals(h, I2PSnarkUtil.b32Hash(b32));
        assertNull(I2PSnarkUtil.b32Hash(h.toBase32() + ".i2p"));
        assertNull(I2PSnarkUtil.b32Hash("zzzz.b32.i2p"));
        assertNull(I2PSnarkUtil.b32Hash(b32.substring(1)));
    }

    /**
     * A cached destination comes back without another lookup: this util has no session, so a
     * miss would go to the naming service and come back as nothing.
     *
     * @throws Exception if the destination's keys cannot be generated
     */
    @Test
    public void testACachedDestinationIsReusedWithoutALookup() throws Exception {
        I2PSnarkUtil util = new I2PSnarkUtil(I2PAppContext.getGlobalContext());
        Destination dest = destination();
        Hash h = dest.calculateHash();
        // the util compares against its own clock, so the entry is written at that clock's
        // time rather than a fixed instant
        long now = System.currentTimeMillis();
        util.cacheDestination(h, dest, now);
        String b32 = Base32.encode(h.getData()) + ".b32.i2p";
        assertSame(dest, util.getDestination(b32));
    }

    /**
     * A miss is cached too, so the same unresolvable hash is not waited on again while the
     * answer is fresh.
     */
    @Test
    public void testAMissIsCachedAndReused() {
        I2PSnarkUtil util = new I2PSnarkUtil(I2PAppContext.getGlobalContext());
        Hash h = hash(0x22);
        long now = 1_700_000_000_000L;
        util.cacheDestination(h, null, now);
        String b32 = Base32.encode(h.getData()) + ".b32.i2p";
        assertNull(util.getDestination(b32));
        assertEquals(1, util.destCacheSize());
    }

    /**
     * Expired answers are dropped once the cache is over its cap, so a torrent shown a long
     * tail of dead peers cannot grow it for the life of the router.
     */
    @Test
    public void testExpiredAnswersAreDroppedWhenTheCacheIsFull() {
        I2PSnarkUtil util = new I2PSnarkUtil(I2PAppContext.getGlobalContext());
        long now = System.currentTimeMillis();
        // over the cap, every one of them written two hours ago, so every one of them is
        // already past its expiry by the time the last write sweeps the map
        for (int i = 0; i < I2PSnarkUtil.DEST_CACHE_MAX; i++) {
            util.cacheDestination(hash(i), null, now - 2 * 60 * 60 * 1000L);
        }
        assertEquals(I2PSnarkUtil.DEST_CACHE_MAX, util.destCacheSize());
        util.cacheDestination(hash(I2PSnarkUtil.DEST_CACHE_MAX), null, now);
        assertEquals("the sweep kept expired answers", 1, util.destCacheSize());
    }
}

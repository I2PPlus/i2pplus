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
 * Tests for the cache of <code>.b32.i2p</code> destination lookups. A base32 address is only
 * a hash, so resolving it costs up to a minute of whatever thread asked, and the tracker, the
 * DHT and PEX keep re-advertising the same peers for the life of a torrent - so without a
 * cache every dial to a hash-only peer id pays for the same lookup over again. A miss is kept
 * too, because it costs what a hit costs, but it is asked about four times on a doubling
 * backoff before it is left alone: a peer whose destination is published a moment after we
 * first ask becomes dialable inside the backoff, while a peer that has simply gone away is not
 * asked about five times a minute.
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
     * @return a 32-byte hash that differs for every i
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
     * seconds or minutes, so a peer that comes back under a new key is dialable again soon.
     *
     * @throws Exception if the destination's keys cannot be generated
     */
    @Test
    public void testAResolvedDestinationIsHeldLongerThanAMiss() throws Exception {
        for (int attempts = 0; attempts < I2PSnarkUtil.DEST_RETRY_ATTEMPTS; attempts++) {
            assertTrue(I2PSnarkUtil.destCacheTtl(destination(), attempts)
                       > I2PSnarkUtil.destCacheTtl(null, attempts));
        }
    }

    /**
     * A miss is asked about four times, each wait double the last, and the four attempts all
     * land inside five minutes of the first one - the peer above is dialable again within that
     * window rather than a quarter of an hour later.
     */
    @Test
    public void testAMissIsAskedAboutFourTimesOnADoublingBackoff() {
        long base = I2PSnarkUtil.DEST_RETRY_BASE_MS;
        assertEquals(base, I2PSnarkUtil.destCacheTtl(null, 0));
        assertEquals(base * 2, I2PSnarkUtil.destCacheTtl(null, 1));
        assertEquals(base * 4, I2PSnarkUtil.destCacheTtl(null, 2));
        // the last attempt is followed by the quiet period, not another wait
        assertEquals(I2PSnarkUtil.DEST_CACHE_NEGATIVE_MS,
                     I2PSnarkUtil.destCacheTtl(null, I2PSnarkUtil.DEST_RETRY_ATTEMPTS - 1));

        // when the four attempts happen, relative to the first one
        long asked = 0;
        for (int attempts = 0; attempts < I2PSnarkUtil.DEST_RETRY_ATTEMPTS - 1; attempts++) {
            asked += I2PSnarkUtil.destCacheTtl(null, attempts);
        }
        assertTrue("last attempt at " + asked + "ms",
                   asked < I2PSnarkUtil.DEST_CACHE_NEGATIVE_MS);
    }

    /**
     * The attempt number counts up while the backoff runs and goes back to 0 once the quiet
     * period starts, so the cycle repeats instead of staying at the longest wait.
     */
    @Test
    public void testTheBackoffRestartsAfterTheQuietPeriod() {
        assertEquals(1, I2PSnarkUtil.destCacheAttempts(0));
        assertEquals(2, I2PSnarkUtil.destCacheAttempts(1));
        assertEquals(0, I2PSnarkUtil.destCacheAttempts(I2PSnarkUtil.DEST_RETRY_ATTEMPTS - 1));
    }

    /**
     * The entry written for a miss carries the attempt number the next lookup continues from,
     * so the waits double across the cycle instead of every attempt starting at the shortest
     * wait.
     */
    @Test
    public void testTheCacheCarriesTheBackoffAcrossTheWaits() {
        I2PSnarkUtil util = new I2PSnarkUtil(I2PAppContext.getGlobalContext());
        Hash h = hash(0x33);
        long now = 1_700_000_000_000L;
        int attempts = 0;
        assertEquals(-1, util.destCacheAttemptsFor(h));
        for (int i = 0; i < I2PSnarkUtil.DEST_RETRY_ATTEMPTS; i++) {
            // the entry the previous attempt left behind is what tells this one where the
            // backoff stands, even after its wait has run out
            if (i > 0) {
                assertEquals(i, util.destCacheAttemptsFor(h));
            }
            util.cacheDestination(h, null, now, attempts);
            attempts = I2PSnarkUtil.destCacheAttempts(attempts);
            now += I2PSnarkUtil.destCacheTtl(null, i);
        }
        // the last attempt is followed by the quiet period, and its entry starts over
        assertEquals(0, util.destCacheAttemptsFor(h));
    }

    /**
     * A resolved destination does not carry a backoff forward - the next lookup about it
     * starts at the first attempt in case it stops resolving.
     *
     * @throws Exception if the destination's keys cannot be generated
     */
    @Test
    public void testAResolvedDestinationResetsTheBackoff() throws Exception {
        I2PSnarkUtil util = new I2PSnarkUtil(I2PAppContext.getGlobalContext());
        Hash h = hash(0x44);
        long now = 1_700_000_000_000L;
        util.cacheDestination(h, null, now, 2);
        assertEquals(3, util.destCacheAttemptsFor(h));
        util.cacheDestination(h, destination(), now, 3);
        assertEquals(0, util.destCacheAttemptsFor(h));
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
     * One lookup waits long enough for one floodfill round trip and no longer. The router does
     * not run its search for the whole wait: it ends as soon as the floodfills it asked have
     * answered, measured at 1-5 s over 111 lookups on this router, so a longer wait only holds
     * the dial thread. The quiet period after the attempts are used up stays shorter than the
     * old flat hold.
     */
    @Test
    public void testTheLookupBudgetAndTheQuietPeriod() {
        // at least the router's own window for one floodfill to answer (8s), so a
        // round trip can complete, and well under the deadline the router itself uses
        assertTrue(I2PSnarkUtil.DEST_LOOKUP_TIMEOUT_MS >= 8 * 1000L);
        assertTrue(I2PSnarkUtil.DEST_LOOKUP_TIMEOUT_MS <= 15 * 1000L);
        // four attempts inside the quiet period, so a peer that comes back is dialable again
        assertTrue(I2PSnarkUtil.DEST_CACHE_NEGATIVE_MS <= 5 * 60 * 1000L);
        assertTrue(I2PSnarkUtil.DEST_CACHE_NEGATIVE_MS
                   > I2PSnarkUtil.DEST_RETRY_BASE_MS * (I2PSnarkUtil.DEST_RETRY_ATTEMPTS - 1));
    }

    /**
     * A hash held as unresolvable reports how much of the wait is left, so the dial path can
     * leave a peer alone instead of starting a connection that dies on the cached answer. A
     * resolved hash, one whose wait has run out, and one never asked about are all dialable.
     *
     * @throws Exception if the destination's keys cannot be generated
     */
    @Test
    public void testAnUnresolvedHashReportsTheWaitLeft() throws Exception {
        I2PSnarkUtil util = new I2PSnarkUtil(I2PAppContext.getGlobalContext());
        // the util reads its own clock, so entries are written at that clock's time
        long now = System.currentTimeMillis();

        Hash miss = hash(0x55);
        util.cacheDestination(miss, null, now, 0);
        long quiet = util.destLookupQuiet(miss);
        assertTrue("quiet was " + quiet,
                   quiet > 0 && quiet <= I2PSnarkUtil.DEST_RETRY_BASE_MS);

        Destination dest = destination();
        Hash hit = dest.calculateHash();
        util.cacheDestination(hit, dest, now, 0);
        assertEquals(0, util.destLookupQuiet(hit));

        // the wait has run out: the next attempt is due, not deferred
        Hash stale = hash(0x66);
        util.cacheDestination(stale, null, now - I2PSnarkUtil.DEST_RETRY_BASE_MS - 1, 0);
        assertEquals(0, util.destLookupQuiet(stale));

        // never asked about at all
        assertEquals(0, util.destLookupQuiet(hash(0x77)));
        assertEquals(0, util.destLookupQuiet(null));
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
        util.cacheDestination(h, dest, now, 0);
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
        long now = System.currentTimeMillis();
        util.cacheDestination(h, null, now, 0);
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
        int cap = util.destCacheMax();
        // over the cap, every one of them written two hours ago, so every one of them is
        // already past its expiry by the time the last write sweeps the map
        for (int i = 0; i < cap; i++) {
            util.cacheDestination(hash(i), null, now - 2 * 60 * 60 * 1000L, 0);
        }
        assertEquals(cap, util.destCacheSize());
        util.cacheDestination(hash(cap), null, now, 0);
        assertEquals("the sweep kept expired answers", 1, util.destCacheSize());
    }

    /**
     * The cache is sized from the heap, not fixed: a floor for a small heap, then a two
     * hundredth of it, and a ceiling so no heap size can make it cost more than 16 MB of
     * destinations. Two thousand torrents name some 25,000 destinations, and a cache stuck at
     * the floor re-asks the router about all of them.
     */
    @Test
    public void testTheCacheIsSizedFromTheHeap() {
        // a heap too small to hold even the floor is still given the floor
        assertEquals(I2PSnarkUtil.DEST_CACHE_MIN, I2PSnarkUtil.destCacheMax(0));
        assertEquals(I2PSnarkUtil.DEST_CACHE_MIN,
                     I2PSnarkUtil.destCacheMax(16 * 1024 * 1024L));
        // 768 MB of heap, a two hundredth of it, at 1 KB an entry
        assertEquals(3932, I2PSnarkUtil.destCacheMax(768L * 1024 * 1024));
        // a heap that would buy more than the ceiling is capped, so the cache cannot OOM us
        assertEquals(I2PSnarkUtil.DEST_CACHE_LIMIT,
                     I2PSnarkUtil.destCacheMax(64L * 1024 * 1024 * 1024));
        assertTrue("the ceiling covers a 2000-torrent swarm's hashes",
                   I2PSnarkUtil.DEST_CACHE_LIMIT >= 16384);
    }

    /**
     * The answers the DHT and the dial path share. A resolved destination is handed back;
     * a "nothing" is not - what it hands back is the wait that has to pass before asking
     * again - and recording an answer continues the ladder instead of restarting it, so
     * one dead peer costs one search and one wait rather than one per subsystem.
     *
     * @throws Exception if the destination's keys cannot be generated
     */
    @Test
    public void testTheSharedCacheAnswersBothCallers() throws Exception {
        I2PSnarkUtil util = new I2PSnarkUtil(I2PAppContext.getGlobalContext());
        Hash h = hash(7);
        assertNull(util.cachedDest(h));
        assertEquals(0, util.destLookupQuiet(h));
        assertNull("a null hash is tolerated by both halves", util.cachedDest(null));
        assertEquals(0, util.destLookupQuiet(null));

        Destination dest = destination();
        Hash resolved = dest.calculateHash();
        util.noteDest(resolved, dest);
        assertSame(dest, util.cachedDest(resolved));
        assertEquals("a resolved destination is never held back", 0,
                     util.destLookupQuiet(resolved));

        util.noteDest(h, null);
        assertNull("a nothing answer hands back no destination", util.cachedDest(h));
        long first = util.destLookupQuiet(h);
        assertTrue(first > 0);
        // the second miss waits longer than the first: the ladder continues across
        // whoever asked, which is the point of one cache serving both callers
        util.noteDest(h, null);
        assertTrue("the backoff must continue", util.destLookupQuiet(h) > first);
    }

    /**
     * A resolved destination is held for the lease-set lifetime, not hours. The key
     * outlives that, the leases inside it do not: the router publishes its leases for
     * ten minutes, so a longer entry has snark dialling peers whose lease sets the
     * router has itself dropped. Measured over 9 hours, 113 of 128 cached peers were
     * re-dialled more than 30 minutes after the lookup, mean gap 254 minutes.
     */
    @Test
    public void testAResolvedDestinationExpiresWithTheLeaseSet() {
        assertEquals("the positive TTL is the spec lease-set lifetime",
                     10 * 60 * 1000L, I2PSnarkUtil.DEST_CACHE_POSITIVE_MS);
        assertTrue("and it is far below the six hours that hid the staleness",
                   I2PSnarkUtil.DEST_CACHE_POSITIVE_MS < 60 * 60 * 1000L);
    }

    /**
     * A connect failure drops the answer. The destination is what failed to open a
     * stream, so keeping it means every later dial reuses an address the router has
     * also lost, routing the connect into the router's LeaseSet lookup path instead of
     * saving the lookup the cache exists to save.
     *
     * @throws Exception if the destination's keys cannot be generated
     */
    @Test
    public void testAFailedConnectDropsTheCachedDestination() throws Exception {
        I2PSnarkUtil util = new I2PSnarkUtil(I2PAppContext.getGlobalContext());
        Destination dest = destination();
        Hash h = dest.calculateHash();
        util.noteDest(h, dest);
        assertSame(dest, util.cachedDest(h));
        util.invalidateDest(h);
        assertNull("the next dial must re-ask rather than reuse the dead answer",
                   util.cachedDest(h));
        assertEquals("the entry is removed, not merely unreadable", 0, util.destCacheSize());
        // nothing is held back for the hash: the next dial asks again straight away
        assertEquals(0, util.destLookupQuiet(h));
    }

    /**
     * Dropping an answer is safe on the edges: a null hash is tolerated, a hash nothing
     * is cached for changes nothing, and a negative entry's backoff ladder is left for
     * the lookup path to manage rather than reset here.
     */
    @Test
    public void testInvalidateToleratesNullAndUnknownHashes() {
        I2PSnarkUtil util = new I2PSnarkUtil(I2PAppContext.getGlobalContext());
        util.invalidateDest(null);
        util.invalidateDest(hash(91));
        assertEquals(0, util.destCacheSize());
        long now = I2PAppContext.getGlobalContext().clock().now();
        Hash h = hash(92);
        util.cacheDestination(h, null, now, 2);
        int ladder = util.destCacheAttemptsFor(h);
        util.invalidateDest(h);
        assertEquals(0, util.destCacheSize());
        assertEquals("a fresh miss is simply gone, not carried over", -1,
                     util.destCacheAttemptsFor(h));
        util.cacheDestination(h, null, now, ladder);
        assertTrue("the ladder can be resumed by the caller that re-asks",
                   util.destLookupQuiet(h) > 0);
    }
}

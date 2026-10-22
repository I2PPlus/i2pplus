package net.i2p.router.transport.udp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Field;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.junit.BeforeClass;
import org.junit.Test;

import net.i2p.data.Hash;
import net.i2p.router.RouterContext;
import net.i2p.router.RouterTestHelper;
import net.i2p.util.RandomSource;

/**
 * Eviction decisions for the per-peer cap on partially-received inbound
 * messages. Without a cap, a peer that sends one fragment per distinct message
 * ID grows the map - and the pooled fragment buffers behind it - for the life of
 * the connection, because the same-message-ID expiry branch never fires.
 */
public class PeerStateInboundEvictionTest {

    private static RouterContext ctx;

    @BeforeClass
    public static void setUp() {
        ctx = RouterTestHelper.newContext();
    }

    private static InboundMessageState state(long id, int frag) throws Exception {
        byte[] data = new byte[64];
        RandomSource.getInstance().nextBytes(data, 0, data.length);
        byte[] h = new byte[Hash.HASH_LENGTH];
        RandomSource.getInstance().nextBytes(h, 0, h.length);
        return new InboundMessageState(ctx, id, new Hash(h), data, 0, data.length, frag, false);
    }

    /** Backdate the first-fragment time so the state is past MAX_RECEIVE_TIME. */
    private static void backdate(InboundMessageState st, long ms) throws Exception {
        Field f = InboundMessageState.class.getDeclaredField("_receiveBegin");
        f.setAccessible(true);
        f.setLong(st, ctx.clock().now() - ms);
    }

    /** An empty map has nothing to evict, and must not throw. */
    @Test
    public void testEmptyMapYieldsNull() {
        ConcurrentMap<Long, InboundMessageState> map = new ConcurrentHashMap<>();
        assertNull(PeerState.evictInboundMessage(map));
        assertTrue(map.isEmpty());
    }

    /** Expired entries are dead weight and go before any live one. */
    @Test
    public void testExpiredEvictedBeforeLive() throws Exception {
        ConcurrentMap<Long, InboundMessageState> map = new ConcurrentHashMap<>();
        InboundMessageState live = state(1L, 0);
        InboundMessageState dead = state(2L, 0);
        backdate(dead, 20 * 1000L);
        map.put(live.getMessageId(), live);
        map.put(dead.getMessageId(), dead);

        InboundMessageState victim = PeerState.evictInboundMessage(map);

        assertSame("the expired entry must be the one dropped", dead, victim);
        assertEquals(1, map.size());
        assertTrue("evicted state must release its pooled buffers", dead.isReleased());
        assertFalse("the live entry must survive", live.isReleased());
        assertNotNull(map.get(1L));
    }

    /** With nothing expired, the entry waiting longest is the one least likely to finish. */
    @Test
    public void testOldestLiveEvictedWhenNoneExpired() throws Exception {
        ConcurrentMap<Long, InboundMessageState> map = new ConcurrentHashMap<>();
        InboundMessageState oldest = state(10L, 0);
        InboundMessageState middle = state(11L, 0);
        InboundMessageState newest = state(12L, 0);
        backdate(oldest, 8 * 1000L);
        backdate(middle, 4 * 1000L);
        // newest stays at now
        map.put(middle.getMessageId(), middle);
        map.put(newest.getMessageId(), newest);
        map.put(oldest.getMessageId(), oldest);

        InboundMessageState victim = PeerState.evictInboundMessage(map);

        assertSame(oldest, victim);
        assertEquals(2, map.size());
        assertNull(map.get(10L));
        assertNotNull(map.get(11L));
        assertNotNull(map.get(12L));
    }

    /** Repeated eviction drains one entry at a time and never goes past empty. */
    @Test
    public void testEvictionDrainsOneAtATime() throws Exception {
        ConcurrentMap<Long, InboundMessageState> map = new ConcurrentHashMap<>();
        for (long i = 0; i < 5; i++)
            map.put(i, state(i, 0));

        for (int i = 0; i < 5; i++) {
            assertNotNull("eviction " + i + " should find a victim", PeerState.evictInboundMessage(map));
            assertEquals(4 - i, map.size());
        }
        assertNull(PeerState.evictInboundMessage(map));
        assertTrue(map.isEmpty());
    }

    /** Every evicted state must have released its buffers, or the cap saves nothing. */
    @Test
    public void testEveryVictimReleasesBuffers() throws Exception {
        ConcurrentMap<Long, InboundMessageState> map = new ConcurrentHashMap<>();
        for (long i = 0; i < 4; i++)
            map.put(i, state(i, 0));

        for (int i = 0; i < 4; i++) {
            InboundMessageState victim = PeerState.evictInboundMessage(map);
            assertNotNull(victim);
            assertTrue("victim must release its fragment buffers", victim.isReleased());
        }
    }

    /** The cap is a compile-time constant; pin it so a widening is a deliberate act. */
    @Test
    public void testCapIsPositive() {
        assertTrue(PeerState.MAX_INBOUND_MESSAGES > 0);
        assertTrue(PeerState.MAX_INBOUND_MESSAGES <= 1024);
    }

    /** A single-entry map evicts that entry rather than returning null. */
    @Test
    public void testSingleEntryEvicted() throws Exception {
        ConcurrentMap<Long, InboundMessageState> map = new ConcurrentHashMap<>();
        InboundMessageState only = state(7L, 3);
        map.put(only.getMessageId(), only);

        assertSame(only, PeerState.evictInboundMessage(map));
        assertTrue(map.isEmpty());
    }

    /** Eviction must not disturb the identity of the survivors. */
    @Test
    public void testSurvivorsUnchanged() throws Exception {
        ConcurrentMap<Long, InboundMessageState> map = new ConcurrentHashMap<>();
        InboundMessageState a = state(1L, 0);
        InboundMessageState b = state(2L, 0);
        backdate(a, 9 * 1000L);
        map.put(a.getMessageId(), a);
        map.put(b.getMessageId(), b);

        PeerState.evictInboundMessage(map);

        assertEquals(1, map.size());
        assertSame(b, map.get(2L));
        assertFalse(b.isReleased());
    }
}

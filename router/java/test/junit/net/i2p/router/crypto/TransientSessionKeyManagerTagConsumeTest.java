package net.i2p.router.crypto;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.After;
import org.junit.Test;

import net.i2p.I2PAppContext;
import net.i2p.data.SessionKey;
import net.i2p.data.SessionTag;

import static org.junit.Assert.*;

/**
 * Inbound tag consumption for the legacy ElGamal-AES sessions (C-1). A tag is
 * taken out of the inbound map and the tag set it came from is updated, and
 * that pair used to happen under one monitor for the whole map - which put
 * every legacy decrypt in the router behind the same lock. The map operation is
 * atomic on its own and the tag set has its own monitor, so the behaviour has
 * to survive the change: a tag is delivered exactly once, and a tag set that
 * collides with another key is dropped on both sides.
 */
public class TransientSessionKeyManagerTagConsumeTest {

    /** Five minutes, the same order the real tag lifetime. */
    private static final long EXPIRE_MS = 5 * 60 * 1000L;

    /** Every manager this test built, so tearDown() can shut each one down. */
    private final List<TransientSessionKeyManager> _managers = new ArrayList<TransientSessionKeyManager>();

    /**
     * A manager for one test. Its cleanup event reschedules itself on the shared
     * SimpleTimer2, so one left running keeps firing after the test that built
     * it is gone.
     *
     * @return a manager on the global context, tracked for shutdown
     */
    private TransientSessionKeyManager manager() {
        TransientSessionKeyManager skm =
            new TransientSessionKeyManager(I2PAppContext.getGlobalContext());
        _managers.add(skm);
        return skm;
    }

    /**
     * Marks every manager built by this test dead, so its cleanup event stops
     * rescheduling on the shared timer.
     */
    @After
    public void tearDown() {
        for (TransientSessionKeyManager skm : _managers) {
            skm.shutdown();
        }
        _managers.clear();
    }

    /**
     * A tag is good once: the decrypt that consumes it gets the key, and the
     * next one asking for the same tag gets nothing.
     */
    @Test
    public void testEachTagIsConsumedOnce() {
        TransientSessionKeyManager skm = manager();
        SessionKey key = key((byte) 1);
        SessionTag one = tag((byte) 10);
        SessionTag two = tag((byte) 11);
        skm.tagsReceived(key, setOf(one, two), EXPIRE_MS);

        assertEquals(key, skm.consumeTag(one));
        assertNull("a replayed tag was accepted again", skm.consumeTag(one));
        assertEquals(key, skm.consumeTag(two));
        assertNull(skm.consumeTag(tag((byte) 99)));
    }

    /**
     * Many threads consuming the tags of one tag set at once: every tag has to
     * be delivered to exactly one of them, no matter which one wins.
     */
    @Test
    public void testConcurrentConsumesDeliverEachTagExactlyOnce() throws Exception {
        TransientSessionKeyManager skm = manager();
        SessionKey key = key((byte) 2);
        final int tagCount = 64;
        final List<SessionTag> tags = new ArrayList<SessionTag>(tagCount);
        for (int i = 0; i < tagCount; i++) {
            tags.add(tag((byte) i));
        }
        skm.tagsReceived(key, new HashSet<SessionTag>(tags), EXPIRE_MS);

        final Map<SessionTag, AtomicInteger> deliveries = new ConcurrentHashMap<SessionTag, AtomicInteger>();
        final int threads = 8;
        final CountDownLatch go = new CountDownLatch(1);
        List<Thread> workers = new ArrayList<Thread>(threads);
        for (int t = 0; t < threads; t++) {
            Thread th = new Thread() {
                @Override
                public void run() {
                    try {
                        go.await();
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    for (SessionTag st : tags) {
                        if (skm.consumeTag(st) != null) {
                            deliveries.putIfAbsent(st, new AtomicInteger());
                            deliveries.get(st).incrementAndGet();
                        }
                    }
                }
            };
            workers.add(th);
            th.start();
        }
        go.countDown();
        for (Thread th : workers) {
            th.join(10000);
            assertFalse("a consumer thread hung", th.isAlive());
        }

        for (SessionTag st : tags) {
            AtomicInteger count = deliveries.get(st);
            assertNotNull("tag never delivered", count);
            assertEquals("tag delivered " + count.get() + " times", 1, count.get());
        }
    }

    /**
     * Two tag sets for different keys sharing one tag is a replay: both sets
     * are dropped, so neither key can be used again.
     */
    @Test
    public void testADuplicateTagForAnotherKeyDropsBothSets() {
        TransientSessionKeyManager skm = manager();
        SessionTag shared = tag((byte) 5);
        SessionTag first = tag((byte) 6);
        SessionTag second = tag((byte) 7);
        skm.tagsReceived(key((byte) 3), setOf(shared, first), EXPIRE_MS);
        skm.tagsReceived(key((byte) 4), setOf(shared, second), EXPIRE_MS);

        assertNull("the shared tag still resolved", skm.consumeTag(shared));
        assertNull("the first set survived the collision", skm.consumeTag(first));
        assertNull("the second set survived the collision", skm.consumeTag(second));
    }

    /**
     * The same tag arriving twice for the same key is a re-send, not a replay,
     * and must not throw away the tag sets.
     */
    @Test
    public void testADuplicateTagForTheSameKeyIsIgnored() {
        TransientSessionKeyManager skm = manager();
        SessionKey key = key((byte) 5);
        SessionTag shared = tag((byte) 8);
        skm.tagsReceived(key, setOf(shared), EXPIRE_MS);
        skm.tagsReceived(key, setOf(shared, tag((byte) 9)), EXPIRE_MS);

        assertEquals(key, skm.consumeTag(shared));
    }

    /**
     * @return a manager on the global context
     */
    /**
     * @param b the fill byte, so two calls with different values give different
     *          keys
     * @return a 32-byte session key
     */
    private static SessionKey key(byte b) {
        byte[] data = new byte[32];
        data[0] = b;
        data[31] = b;
        return new SessionKey(data);
    }

    /**
     * @param b the tag's first byte, the rest zero, so tags are reproducible
     * @return a session tag
     */
    private static SessionTag tag(byte b) {
        byte[] data = new byte[32];
        data[0] = b;
        return new SessionTag(data);
    }

    /**
     * @param tags the tags to collect
     * @return a mutable set of them, as tagsReceived expects
     */
    private static Set<SessionTag> setOf(SessionTag... tags) {
        Set<SessionTag> rv = new HashSet<SessionTag>(tags.length * 2);
        Collections.addAll(rv, tags);
        return rv;
    }
}

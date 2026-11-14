package org.klomp.snark.dht;

import static org.junit.Assert.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.i2p.I2PAppContext;
import net.i2p.client.I2PClientFactory;
import net.i2p.client.I2PSession;
import net.i2p.data.Destination;
import net.i2p.data.Hash;
import org.junit.Test;

/**
 * Pins that the DHT asks snark's destination cache before it searches for a node's
 * destination, and puts what it learns back there. A {@code .b32.i2p} destination is only
 * a hash, and resolving it is a network-database search of up to a minute. The DHT used to
 * keep its answer only on the node it had looked up, and no negative answer at all, so a
 * node whose destination cannot be resolved was searched for again on every query aimed at
 * it - 3,348 dropped queries in a four hour window, each behind a search that had already
 * failed - while the dial path kept its own separate answers for the same hashes.
 *
 * @since 0.9.72+
 */
public class DestCacheSharingTest {

    private static final I2PAppContext CTX = I2PAppContext.getGlobalContext();

    /**
     * Each test builds its own KRPC, session and cache, so the instance carries no state
     * between them.
     */
    public DestCacheSharingTest() {}

    /**
     * A destination cache that records what it was asked and answers from fixed values.
     */
    private static final class RecordingCache implements DestCache {

        /** What {@link #cachedDest} hands back, null when nothing resolved is held. */
        Destination resolved;

        /** What {@link #destLookupQuiet} reports, 0 to ask now. */
        long quiet;

        /** The hashes the DHT asked about, in the order it asked. */
        final List<String> asks = new ArrayList<>();

        /** What the DHT recorded, null answer included - "it did not resolve" is an answer. */
        final Map<Hash, Destination> noted = new HashMap<>();

        /**
         * An empty cache: nothing resolved, nothing held back.
         */
        RecordingCache() {}

        @Override
        public Destination cachedDest(Hash h) {
            asks.add("cached");
            return resolved;
        }

        @Override
        public long destLookupQuiet(Hash h) {
            asks.add("quiet");
            return quiet;
        }

        @Override
        public void noteDest(Hash h, Destination dest) {
            asks.add("note");
            noted.put(h, dest);
        }
    }

    /**
     * A session that records whether a destination search ran, and answers it with nothing.
     */
    private static final class RecordingSession {

        /** Our own destination, generated fresh so the node id differs per run. */
        final Destination dest;

        /** Names of the session methods that were called. */
        final List<String> calls = new ArrayList<>();

        /** The session under test, a proxy answering only what KRPC asks. */
        final I2PSession session;

        /**
         * Generates a fresh destination and wraps it in a session proxy that reports
         * itself closed, so the KRPC built on it never sends anything.
         */
        RecordingSession() {
            try {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                I2PClientFactory.createClient().createDestination(out);
                dest = Destination.create(new ByteArrayInputStream(out.toByteArray()));
            } catch (Exception e) {
                throw new RuntimeException("dest gen failed", e);
            }
            session =
                    (I2PSession)
                            Proxy.newProxyInstance(
                                    I2PSession.class.getClassLoader(),
                                    new Class<?>[] {I2PSession.class},
                                    (proxy, method, args) -> {
                                        String name = method.getName();
                                        calls.add(name);
                                        if (name.equals("getMyDestination")) {return dest;}
                                        if (name.equals("isClosed")) {return Boolean.TRUE;}
                                        Class<?> rt = method.getReturnType();
                                        if (rt == boolean.class) {return Boolean.FALSE;}
                                        if (rt == int.class) {return Integer.valueOf(0);}
                                        if (rt == long.class) {return Long.valueOf(0);}
                                        return null;
                                    });
        }

        /**
         * Whether the session was asked to search the network database for a destination.
         *
         * @return true if a search ran
         */
        boolean searched() {
            return calls.contains("lookupDest");
        }
    }

    /**
     * A node whose node id matches a hash and port the way {@code NodeInfo} requires:
     * the first four bytes are the hash's, the next two are the hash's XOR the port.
     *
     * @param h the node's destination hash
     * @param port the UDP port it was last heard from on
     * @return a node that will accept a destination hashing to {@code h}
     */
    private static NodeInfo nodeFor(Hash h, int port) {
        byte[] hb = h.getData();
        byte[] nb = new byte[NID.HASH_LENGTH];
        System.arraycopy(hb, 0, nb, 0, 4);
        nb[4] = (byte) (hb[4] ^ (port >> 8));
        nb[5] = (byte) (hb[5] ^ port);
        System.arraycopy(hb, 6, nb, 6, Math.min(hb.length - 6, nb.length - 6));
        return new NodeInfo(new NID(nb), h, port);
    }

    /**
     * A destination the cache already holds goes straight onto the node, and no search
     * runs - the same answer the dial path paid for is what the DHT uses.
     */
    @Test
    public void testAResolvedDestinationIsTakenFromTheCache() {
        RecordingSession sess = new RecordingSession();
        RecordingCache cache = new RecordingCache();
        cache.resolved = sess.dest;
        KRPC krpc = new KRPC(CTX, "i2psnark", sess.session, cache);
        try {
            // a node whose hash is that destination's, so the destination fits it
            NodeInfo node = nodeFor(sess.dest.calculateHash(), 7000);
            assertTrue(krpc.lookupDest(node));
            assertSame(sess.dest, node.getDestination());
            assertFalse("the cache answer must save the search", sess.searched());
        } finally {
            krpc.stop();
        }
    }

    /**
     * A hash the cache still holds as unresolvable is not searched for again: the answer
     * is the backoff, and the swarm keeps naming the same dead node for the life of the
     * torrent.
     */
    @Test
    public void testAHashHeldAsUnresolvableIsNotSearchedAgain() {
        RecordingSession sess = new RecordingSession();
        RecordingCache cache = new RecordingCache();
        cache.quiet = 5 * 60 * 1000L;
        KRPC krpc = new KRPC(CTX, "i2psnark", sess.session, cache);
        try {
            NodeInfo node = KRPCFilterTest.validNode((byte) 42, 7000);
            assertFalse(krpc.lookupDest(node));
            assertNull(node.getDestination());
            assertFalse("a held answer must save the search", sess.searched());
            assertTrue(cache.asks.contains("quiet"));
        } finally {
            krpc.stop();
        }
    }

    /**
     * With nothing cached the search runs, and whatever it answered - including "nothing"
     * - goes back into the shared cache, so the next query for this node and the next dial
     * of this peer share one wait instead of paying for two searches.
     */
    @Test
    public void testASearchIsRecordedInTheSharedCache() {
        RecordingSession sess = new RecordingSession();
        RecordingCache cache = new RecordingCache();
        KRPC krpc = new KRPC(CTX, "i2psnark", sess.session, cache);
        try {
            NodeInfo node = KRPCFilterTest.validNode((byte) 43, 7001);
            assertFalse(krpc.lookupDest(node));
            assertTrue("an unknown hash must still be searched for", sess.searched());
            assertEquals(1, cache.noted.size());
            assertTrue("the negative answer is an answer worth keeping",
                       cache.noted.containsKey(node.getHash()));
            assertNull(cache.noted.get(node.getHash()));
        } finally {
            krpc.stop();
        }
    }

    /**
     * A KRPC built without a cache still works, searching and storing the answer on the
     * node as it always did - the sharing is an addition, not a requirement.
     */
    @Test
    public void testAKrpcWithoutACacheStillSearches() {
        RecordingSession sess = new RecordingSession();
        KRPC krpc = new KRPC(CTX, "i2psnark", sess.session);
        try {
            NodeInfo node = KRPCFilterTest.validNode((byte) 44, 7002);
            assertFalse(krpc.lookupDest(node));
            assertTrue(sess.searched());
        } finally {
            krpc.stop();
        }
    }
}

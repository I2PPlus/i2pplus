package org.klomp.snark.dht;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import net.i2p.I2PAppContext;
import net.i2p.client.I2PClientFactory;
import net.i2p.client.I2PSession;
import net.i2p.data.Destination;
import net.i2p.data.Hash;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Peers may echo our own node entry back to us, since we include our NID in every
 * query, and a remote may even persist our entry and re-advertise it. These tests
 * verify that an entry equal to ourselves is never stored in the routing table,
 * never returned by findClosest, and is filtered from candidate lists, so that we
 * can never query, respond to, or announce to ourselves. Near-misses - the same
 * hash with a different NID or port - are distinct nodes and are kept.
 *
 * @since 0.9.71+
 */
public class KRPCFilterTest {

    /** The app context, whose config dir holds the router's persisted DHT cache. */
    private static final I2PAppContext CTX = I2PAppContext.getGlobalContext();

    /**
     * Test instance; each test builds its own KRPC and node fixtures, so the instance
     * carries no state between them.
     */
    public KRPCFilterTest() {
    }

    /** A fake I2PSession that records listener registrations. */
    static final class FakeSession {
        /** Our own destination, generated fresh so the node id differs per run. */
        final Destination dest;
        /** Names of the session methods that were called, for the assertions on them. */
        final List<String> calls = new ArrayList<>();
        /** The session under test, a proxy answering only what KRPC asks. */
        final I2PSession session;

        /**
         * Generates a fresh destination and wraps it in a session proxy that reports
         * itself closed, so the KRPC built on it never sends anything.
         */
        FakeSession() {
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
                                        if (name.equals("getMyDestination")) {
                                            return dest;
                                        }
                                        if (name.equals("isClosed")) {
                                            return Boolean.TRUE;
                                        }
                                        Class<?> rt = method.getReturnType();
                                        if (rt == boolean.class) {
                                            return Boolean.FALSE;
                                        }
                                        if (rt == int.class) {
                                            return Integer.valueOf(0);
                                        }
                                        if (rt == long.class) {
                                            return Long.valueOf(0);
                                        }
                                        return null;
                                    });
        }
    }

    /**
     * Reads a field from the object or the first of its superclasses that declares it,
     * for reaching the routing table and node ids KRPC keeps to itself.
     *
     * @param obj the object to read from
     * @param name the field's name
     * @return the field's current value
     * @throws NoSuchFieldException if no class in the chain declares that field
     * @throws IllegalAccessException if the field cannot be made readable
     */
    static Object getField(Object obj, String name) throws Exception {
        Class<?> cls = obj.getClass();
        while (cls != null) {
            try {
                Field f = cls.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(obj);
            } catch (NoSuchFieldException nsfe) {
                cls = cls.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }

    /**
     * A byte array counting up from the given value, the fixture's stand-in for a real
     * node id or destination hash.
     *
     * @param start the first byte of the ramp
     * @param len how many bytes to fill
     * @return the ramp, {@code len} bytes long
     */
    private static byte[] fill(byte start, int len) {
        byte[] b = new byte[len];
        for (int i = 0; i < b.length; i++) {
            b[i] = (byte) (start + i);
        }
        return b;
    }

    /**
     * A node id ramp of the length the DHT uses.
     *
     * @param start the first byte of the ramp
     * @return {@link NID#HASH_LENGTH} bytes counting up from start
     */
    private static byte[] nid(byte start) {
        return fill(start, NID.HASH_LENGTH);
    }

    /**
     * A destination hash ramp.
     *
     * @param start the first byte of the ramp
     * @return 32 bytes counting up from start
     */
    private static byte[] hash(byte start) {
        return fill(start, 32);
    }

    /**
     * An exact copy of the given node info, equal but a different object.
     *
     * @param n the node info to duplicate
     * @return a new NodeInfo equal to n, for self-echo identity checks
     */
    private static NodeInfo copy(NodeInfo n) {
        return new NodeInfo(new NID(n.getNID().getData()), n.getHash(), n.getPort());
    }

    /**
     * A valid NodeInfo for the given hash seed and port. The NID must match the
     * hash/port derivation enforced by {@link org.klomp.snark.dht.NodeInfo#verify}: first 4 NID bytes
     * equal the hash, bytes 4-5 are the hash bytes xored with the port.
     *
     * @param hashStart the first byte of the ramp that fills the 32-byte node
     *                  hash, the rest counting up from it
     * @param port the peer's UDP port, also folded into the NID bytes
     * @return a NodeInfo whose NID passes {@link org.klomp.snark.dht.NodeInfo#verify}
     */
    static NodeInfo validNode(byte hashStart, int port) {
        byte[] hb = hash(hashStart);
        byte[] nb = new byte[NID.HASH_LENGTH];
        System.arraycopy(hb, 0, nb, 0, 4);
        nb[4] = (byte) (hb[4] ^ (port >> 8));
        nb[5] = (byte) (hb[5] ^ port);
        System.arraycopy(hb, 6, nb, 6, Math.min(hb.length - 6, nb.length - 6));
        return new NodeInfo(new NID(nb), Hash.create(hb), port);
    }

    /**
     * RemoveSelf drops entries equal to ourselves and keeps near-misses: the
     * same hash with a different NID and port, and unrelated nodes. The input
     * list is not modified.
     */
    @Test
    public void testRemoveSelf() {
        NodeInfo self = validNode((byte) 2, 1234);
        NodeInfo exact = copy(self);
        NodeInfo sameHashDiffPort = validNode((byte) 2, 9876);
        NodeInfo other = validNode((byte) 34, 5678);
        List<NodeInfo> nodes = Arrays.asList(exact, sameHashDiffPort, other);

        List<NodeInfo> rv = KRPC.removeSelf(nodes, self);

        assertEquals(2, rv.size());
        assertTrue(rv.contains(sameHashDiffPort));
        assertTrue(rv.contains(other));
        assertFalse(rv.contains(exact));
        assertFalse(rv.contains(self));
        assertEquals(3, nodes.size());
    }

    /**
     * HeardAbout and heardFrom return our own node info for a self echo without
     * storing it, and findClosest never returns an entry equal to ourselves. The
     * assertions are about membership, not size: the constructor loads the router's
     * persisted DHT cache and its cleaner runs, so the table's size is not ours to pin.
     *
     * @throws Exception if reflection on the KRPC private node fields fails
     */
    @Test
    public void testSelfNeverStoredNorReturned() throws Exception {
        FakeSession sess = new FakeSession();
        KRPC krpc = new KRPC(CTX, "i2psnark", sess.session);
        try {
            NodeInfo self =
                    (NodeInfo)
                            getField(krpc, "_myNodeInfo");
            assertNotNull(self);
            Object nodes = getField(krpc, "_knownNodes");
            java.util.Map<?, ?> map = (java.util.Map<?, ?>) getField(nodes, "_nodeMap");

            // heardAbout of a self echo returns our own node info, stores nothing
            NodeInfo heard = krpc.heardAbout(copy(self));
            assertEquals(self, heard);
            assertFalse("a self echo was stored", map.containsKey(self.getNID()));

            // heardFrom likewise
            java.lang.reflect.Method m = krpc.getClass().getDeclaredMethod("heardFrom", NodeInfo.class);
            m.setAccessible(true);
            NodeInfo heardF = (NodeInfo) m.invoke(krpc, copy(self));
            assertEquals(self, heardF);
            assertFalse("a self echo was stored", map.containsKey(self.getNID()));

            // real nodes are stored
            NodeInfo a = validNode((byte) 42, 7000);
            NodeInfo b = validNode((byte) 52, 7001);
            krpc.heardAbout(a);
            krpc.heardAbout(b);
            assertTrue(map.containsKey(a.getNID()));
            assertTrue(map.containsKey(b.getNID()));

            // findClosest never returns self, even for a target near ourselves
            List<NodeInfo> closest = krpc.findClosest(nid((byte) 1), 30);
            assertFalse(closest.isEmpty());
            for (NodeInfo n : closest) {
                assertFalse("findClosest returned ourselves: " + n, n.equals(self));
            }
        } finally {
            krpc.stop();
        }
    }
}

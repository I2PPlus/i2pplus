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
 * Peers may echo our own node entry back to us (we include our NID in every
 * query), and a remote may even persist our entry and re-advertise it. These
 * tests verify that an entry equal to ourselves is never stored in the routing
 * table, never returned by findClosest, and is filtered from candidate lists,
 * so that we can never query, respond to, or announce to ourselves. Near-misses
 * (same hash with a different NID or port) are distinct nodes and are kept.
 *
 * @since 0.9.71+
 */
public class KRPCFilterTest {

    private static final I2PAppContext CTX = I2PAppContext.getGlobalContext();

    /** A fake I2PSession that records listener registrations. */
    private static final class FakeSession {
        final Destination dest;
        final List<String> calls = new ArrayList<>();
        final I2PSession session;

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

    private static Object getField(Object obj, String name) throws Exception {
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

    private static byte[] fill(byte start, int len) {
        byte[] b = new byte[len];
        for (int i = 0; i < b.length; i++) {
            b[i] = (byte) (start + i);
        }
        return b;
    }

    private static byte[] nid(byte start) {
        return fill(start, NID.HASH_LENGTH);
    }

    private static byte[] hash(byte start) {
        return fill(start, 32);
    }

    /** An exact copy of the given node info, equal but a different object. */
    private static NodeInfo copy(NodeInfo n) {
        return new NodeInfo(new NID(n.getNID().getData()), n.getHash(), n.getPort());
    }

    /**
     * A valid NodeInfo for the given hash seed and port. The NID must match the
     * hash/port derivation enforced by NodeInfo.verify(): first 4 NID bytes
     * equal the hash, bytes 4-5 are the hash bytes xored with the port.
     */
    private static NodeInfo validNode(byte hashStart, int port) {
        byte[] hb = hash(hashStart);
        byte[] nb = new byte[NID.HASH_LENGTH];
        System.arraycopy(hb, 0, nb, 0, 4);
        nb[4] = (byte) (hb[4] ^ (port >> 8));
        nb[5] = (byte) (hb[5] ^ port);
        System.arraycopy(hb, 6, nb, 6, Math.min(hb.length - 6, nb.length - 6));
        return new NodeInfo(new NID(nb), Hash.create(hb), port);
    }

    /**
     * removeSelf drops entries equal to ourselves and keeps near-misses: the
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
     * heardAbout and heardFrom return our own node info for a self echo without
     * storing it, and findClosest never returns an entry equal to ourselves.
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
            Object map = getField(nodes, "_nodeMap");

            // heardAbout of a self echo returns our own node info, stores nothing
            NodeInfo heard = krpc.heardAbout(copy(self));
            assertEquals(self, heard);
            assertEquals(0, ((java.util.Map<?, ?>) map).size());

            // heardFrom likewise
            java.lang.reflect.Method m = krpc.getClass().getDeclaredMethod("heardFrom", NodeInfo.class);
            m.setAccessible(true);
            NodeInfo heardF = (NodeInfo) m.invoke(krpc, copy(self));
            assertEquals(self, heardF);
            assertEquals(0, ((java.util.Map<?, ?>) map).size());

// real nodes are stored
            NodeInfo a = validNode((byte) 42, 7000);
            NodeInfo b = validNode((byte) 52, 7001);
            krpc.heardAbout(a);
            krpc.heardAbout(b);
            assertEquals(2, ((java.util.Map<?, ?>) map).size());

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

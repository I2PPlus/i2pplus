package org.klomp.snark.dht;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import net.i2p.I2PAppContext;
import net.i2p.client.I2PClientFactory;
import net.i2p.client.I2PSession;
import net.i2p.data.ByteArray;
import net.i2p.data.Destination;
import net.i2p.data.Hash;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Tests that a TorrentKRPC runs on its own identity: its own query and response ports,
 * its own NID, and its own token maps, while sharing the routing table, tracker, and
 * blacklist with the main instance. The token tests verify the isolation property that
 * matters for correctness: a token issued by one torrent's destination cannot be used
 * to announce a peer on another torrent's destination, which would make remote nodes
 * store the wrong peer hash for a torrent.
 *
 * @since 0.9.71+
 */
public class TorrentKRPCTest {

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
                dest =
                        Destination.create(
                                new ByteArrayInputStream(out.toByteArray()));
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
                                        if (name.equals("addMuxedSessionListener")) {
                                            calls.add("add:" + args[1] + ":" + args[2]);
                                            return null;
                                        }
                                        if (name.equals("removeListener")) {
                                            calls.add("rm:" + args[0] + ":" + args[1]);
                                            return null;
                                        }
                                        if (name.equals("sendMessage")) {
                                            return Boolean.FALSE;
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

    private static Destination newDest() {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            I2PClientFactory.createClient().createDestination(out);
            return Destination.create(new ByteArrayInputStream(out.toByteArray()));
        } catch (Exception e) {
            throw new RuntimeException("dest gen failed", e);
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

    private static void invoke(Object obj, String name, Class<?>[] types, Object[] args)
            throws Exception {
        Class<?> cls = obj.getClass();
        while (cls != null) {
            try {
                Method m = cls.getDeclaredMethod(name, types);
                m.setAccessible(true);
                m.invoke(obj, args);
                return;
            } catch (NoSuchMethodException nsme) {
                cls = cls.getSuperclass();
            }
        }
        throw new NoSuchMethodException(name);
    }

    /**
     * A TorrentKRPC has its own ports, NID, and token maps, and registers its listeners
     * on its own session and ports, but shares the routing table, tracker, and blacklist
     * with the main instance.
     */
    @Test
    public void testIdentityIsolation() throws Exception {
        FakeSession mainSess = new FakeSession();
        KRPC main = new KRPC(CTX, "i2psnark", mainSess.session);
        try {
            FakeSession sess1 = new FakeSession();
            FakeSession sess2 = new FakeSession();
            TorrentKRPC t1 = new TorrentKRPC(CTX, main, sess1.session);
            TorrentKRPC t2 = new TorrentKRPC(CTX, main, sess2.session);

            // own ports
            int q1 = ((Integer) getField(t1, "_qPort")).intValue();
            int q2 = ((Integer) getField(t2, "_qPort")).intValue();
            int r1 = ((Integer) getField(t1, "_rPort")).intValue();
            int r2 = ((Integer) getField(t2, "_rPort")).intValue();
            assertTrue(q1 != q2);
            assertEquals(q1 + 1, r1);
            assertEquals(q2 + 1, r2);

            // own NID
            byte[] id1 = (byte[]) getField(t1, "_myID");
            byte[] id2 = (byte[]) getField(t2, "_myID");
            assertFalse(Arrays.equals(id1, id2));

            // own token maps
            assertNotSame(getField(t1, "_outgoingTokens"), getField(t2, "_outgoingTokens"));
            assertNotSame(getField(t1, "_incomingTokens"), getField(t2, "_incomingTokens"));
            assertNotSame(getField(t1, "_outgoingTokens"), getField(main, "_outgoingTokens"));
            assertNotSame(getField(t1, "_incomingTokens"), getField(main, "_incomingTokens"));

            // own listener registrations on own ports
            assertTrue(sess1.calls.contains("add:" + I2PSession.PROTO_DATAGRAM_RAW + ":" + r1));
            assertTrue(sess1.calls.contains("add:" + I2PSession.PROTO_DATAGRAM + ":" + q1));
            assertTrue(sess2.calls.contains("add:" + I2PSession.PROTO_DATAGRAM_RAW + ":" + r2));
            assertTrue(sess2.calls.contains("add:" + I2PSession.PROTO_DATAGRAM + ":" + q2));
            assertFalse(mainSess.calls.contains("add:" + I2PSession.PROTO_DATAGRAM_RAW + ":" + r1));

            // shared state
            assertSame(getField(main, "_knownNodes"), getField(t1, "_knownNodes"));
            assertSame(getField(main, "_knownNodes"), getField(t2, "_knownNodes"));
            assertSame(getField(main, "_tracker"), getField(t1, "_tracker"));
            assertSame(getField(main, "_blacklist"), getField(t1, "_blacklist"));
        } finally {
            main.stop();
        }
    }

    /**
     * A token issued for one torrent's destination cannot be used to announce a peer on
     * another torrent's destination; both torrents share the same tracker, so the peer
     * must end up in the tracker only when the announce uses the right token.
     */
    @Test
    public void testTokenIsolation() throws Exception {
        FakeSession mainSess = new FakeSession();
        KRPC main = new KRPC(CTX, "i2psnark", mainSess.session);
        try {
            FakeSession sess1 = new FakeSession();
            FakeSession sess2 = new FakeSession();
            TorrentKRPC t1 = new TorrentKRPC(CTX, main, sess1.session);
            TorrentKRPC t2 = new TorrentKRPC(CTX, main, sess2.session);
            DHTTracker tracker = (DHTTracker) getField(t1, "_tracker");
            InfoHash ih = new InfoHash(new byte[20]);
            NodeInfo nInfo = new NodeInfo(newDest(), 40000);
            MsgID msgID = new MsgID(CTX);

            // a get_peers to t1 issues a token in t1's map
            invoke(
                    t1,
                    "receiveGetPeers",
                    new Class<?>[] {MsgID.class, NodeInfo.class, InfoHash.class, boolean.class},
                    new Object[] {msgID, nInfo, ih, Boolean.FALSE});
            Map<?, ?> tokens1 = (Map<?, ?>) getField(t1, "_outgoingTokens");
            Map<?, ?> tokens2 = (Map<?, ?>) getField(t2, "_outgoingTokens");
            assertEquals(1, tokens1.size());
            assertEquals(0, tokens2.size());
            byte[] token = ((ByteArray) tokens1.keySet().iterator().next()).getData();

            // the announce with t1's token on t2 must be rejected: no tracker entry
            invoke(
                    t2,
                    "receiveAnnouncePeer",
                    new Class<?>[] {MsgID.class, InfoHash.class, byte[].class, boolean.class},
                    new Object[] {new MsgID(CTX), ih, token, Boolean.FALSE});
            assertEquals(0, tracker.getPeers(ih, 100, false).size());

            // the same announce on t1 must be accepted: the peer lands in the shared tracker
            invoke(
                    t1,
                    "receiveAnnouncePeer",
                    new Class<?>[] {MsgID.class, InfoHash.class, byte[].class, boolean.class},
                    new Object[] {new MsgID(CTX), ih, token, Boolean.FALSE});
            List<Hash> peers = tracker.getPeers(ih, 100, false);
            assertEquals(1, peers.size());
            assertEquals(nInfo.getHash(), peers.get(0));
        } finally {
            main.stop();
        }
    }

    /** start() and stop() register and unregister the listeners and are idempotent. */
    @Test
    public void testStartStop() throws Exception {
        FakeSession mainSess = new FakeSession();
        KRPC main = new KRPC(CTX, "i2psnark", mainSess.session);
        try {
            FakeSession sess = new FakeSession();
            TorrentKRPC t = new TorrentKRPC(CTX, main, sess.session);
            int q = ((Integer) getField(t, "_qPort")).intValue();
            int r = ((Integer) getField(t, "_rPort")).intValue();

            // idempotent start
            t.start();
            assertEquals(
                    1,
                    count(sess.calls, "add:" + I2PSession.PROTO_DATAGRAM_RAW + ":" + r));

            // stop unregisters
            t.stop();
            assertTrue(sess.calls.contains("rm:" + I2PSession.PROTO_DATAGRAM + ":" + q));
            assertTrue(sess.calls.contains("rm:" + I2PSession.PROTO_DATAGRAM_RAW + ":" + r));
            assertFalse(((Boolean) getField(t, "_isRunning")).booleanValue());

            // idempotent stop
            t.stop();

            // restart
            t.start();
            assertTrue(((Boolean) getField(t, "_isRunning")).booleanValue());
            t.stop();
        } finally {
            main.stop();
        }
    }

    private static int count(List<String> calls, String prefix) {
        int rv = 0;
        for (String c : calls) {
            if (c.startsWith(prefix)) {
                rv++;
            }
        }
        return rv;
    }
}

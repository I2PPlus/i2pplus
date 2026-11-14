package org.klomp.snark.dht;

import static org.junit.Assert.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;

import net.i2p.I2PAppContext;
import net.i2p.client.I2PClientFactory;
import net.i2p.client.I2PSession;
import net.i2p.data.Destination;

import org.junit.Test;
import org.klomp.snark.bencode.BDecoder;
import org.klomp.snark.bencode.BEncoder;
import org.klomp.snark.bencode.BEValue;

/**
 * Pins where a received KRPC datagram is worked on. The session's listener thread used to
 * dissect, bdecode, update the routing table and send the reply itself, so a get_peers
 * flood kept that thread busy for the whole session - the torrent's streaming shares it.
 * Now the listener only queues, one worker does the work in arrival order, and a datagram
 * that arrives while the worker is behind is dropped and counted rather than queued past
 * the bound.
 *
 * @since 0.9.72+
 */
public class RxQueueTest {

    private static final I2PAppContext CTX = I2PAppContext.getGlobalContext();

    /** The thread name the receive worker starts with. */
    private static final String RX_THREAD = "DHTReceive";

    /**
     * One instance per test method, as JUnit builds a fresh test object for each one.
     *
     * @since 0.9.72+
     */
    public RxQueueTest() {}

    /**
     * A fake session that hands out queued datagrams and records the replies it is asked
     * to send, along with the thread that asked. A gate lets a test hold the receive
     * worker inside one send while it fills the queue.
     *
     * @since 0.9.72+
     */
    private static final class FakeSession {

        /** Our own destination, the one the KRPC identifies itself by. */
        final Destination dest;
        /** Datagrams returned by receiveMessage(), in order. */
        final List<byte[]> inbox = new ArrayList<>();
        /** One entry per send: the sending thread's name, a pipe, then the reply's message id. */
        final List<String> sends = new ArrayList<>();
        /** Held open while the receive worker is inside a send, null to let sends through. */
        volatile CountDownLatch gate;
        /** True once a send has been entered. */
        volatile boolean inSend;
        final I2PSession session;

        FakeSession() {
            dest = newDest();
            session =
                    (I2PSession)
                            Proxy.newProxyInstance(
                                    I2PSession.class.getClassLoader(),
                                    new Class<?>[] {I2PSession.class},
                                    (proxy, method, args) -> {
                                        String name = method.getName();
                                        if ("getMyDestination".equals(name)) {
                                            return dest;
                                        }
                                        if ("receiveMessage".equals(name)) {
                                            synchronized (inbox) {
                                                return inbox.isEmpty() ? null : inbox.remove(0);
                                            }
                                        }
                                        if ("sendMessage".equals(name)) {
                                            recordSend(args);
                                            return Boolean.FALSE;
                                        }
                                        if ("isClosed".equals(name) || "isOffline".equals(name)) {
                                            return Boolean.FALSE;
                                        }
                                        Class<?> rt = method.getReturnType();
                                        if (rt == boolean.class) return Boolean.FALSE;
                                        if (rt == int.class) return Integer.valueOf(0);
                                        if (rt == long.class) return Long.valueOf(0);
                                        return null;
                                    });
        }

        /** Queues one datagram for the next messageAvailable() call to read. */
        void offer(byte[] datagram) {
            synchronized (inbox) {
                inbox.add(datagram);
            }
        }

        /** Records the sending thread and the reply's message id, then waits for the gate. */
        private void recordSend(Object[] args) throws InterruptedException {
            sends.add(Thread.currentThread().getName() + "|" + msgId((byte[]) args[1]));
            inSend = true;
            CountDownLatch latch = gate;
            if (latch != null) {
                latch.await();
            }
        }

        /** Waits until the receive worker is blocked inside a send. */
        boolean awaitInSend(long millis) throws InterruptedException {
            long end = System.currentTimeMillis() + millis;
            while (!inSend && System.currentTimeMillis() < end) {
                Thread.sleep(10);
            }
            return inSend;
        }

        /** Waits for n replies to have gone out. */
        boolean awaitSends(int n, long millis) throws InterruptedException {
            long end = System.currentTimeMillis() + millis;
            while (sends.size() < n && System.currentTimeMillis() < end) {
                Thread.sleep(10);
            }
            return sends.size() >= n;
        }
    }

    /**
     * Generates a destination, so a reply can be sent to a node that is not ourselves.
     *
     * @return a fresh destination
     */
    private static Destination newDest() {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            I2PClientFactory.createClient().createDestination(out);
            return Destination.create(new ByteArrayInputStream(out.toByteArray()));
        } catch (Exception e) {
            throw new RuntimeException("dest gen failed", e);
        }
    }

    /**
     * One announce_peer query, sent on the unsigned port so it needs no I2P datagram
     * wrapper. It answers to the token we handed out by the earlier get_peers, which is
     * what makes the worker send a pong back and give the test something to observe.
     *
     * @param msgId the KRPC message id to echo back in the reply
     * @param token the token stored by storeOutgoingToken()
     * @return the bencoded query to hand to messageAvailable()
     */
    private static byte[] announce(String msgId, byte[] token) {
        Map<String, Object> map = new HashMap<>();
        map.put("t", msgId);
        map.put("y", "q");
        map.put("q", "announce_peer");
        Map<String, Object> args = new HashMap<>();
        args.put("id", new byte[NID.HASH_LENGTH]);
        args.put("info_hash", new byte[InfoHash.HASH_LENGTH]);
        args.put("port", 1234);
        args.put("token", token);
        map.put("a", args);
        return BEncoder.bencode(map);
    }

    /**
     * The message id of an outgoing reply, so the order replies went out in can be checked.
     *
     * @param payload the bencoded reply
     * @return the id, or "?" if it could not be read
     */
    private static String msgId(byte[] payload) {
        try {
            Map<String, BEValue> map =
                    new BDecoder(new ByteArrayInputStream(payload)).bdecodeMap().getMap();
            return new String(map.get("t").getBytes(), "UTF-8");
        } catch (Throwable e) {
            return "?";
        }
    }

    /**
     * Read a private field of a KRPC, the way the other DHT tests do.
     *
     * @param obj the object to read
     * @param name the field name
     * @return the field value
     * @throws Exception if the field is not present or not accessible
     */
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

    /**
     * Hands one announce query to the listener entry point.
     *
     * @param krpc the instance receiving it
     * @param sess the fake session holding the datagram
     * @param msgId the KRPC message id
     * @param token the token the query answers to
     */
    private static void deliver(KRPC krpc, FakeSession sess, String msgId, byte[] token) {
        sess.offer(announce(msgId, token));
        krpc.messageAvailable(
                sess.session, 0, 0, I2PSession.PROTO_DATAGRAM_RAW, 1234, krpc._rPort);
    }

    /**
     * A node whose token we stored, so the announce is accepted and answered.
     *
     * @param krpc the instance to store it on
     * @return the token the node will quote back
     */
    private static byte[] handOutToken(KRPC krpc) {
        NodeInfo node = new NodeInfo(newDest(), 1234);
        Token token = new Token(CTX);
        assertTrue(krpc.storeOutgoingToken(token, node));
        return token.getData();
    }

    /**
     * The reply to a received query goes out on the receive worker, not on the thread that
     * handed the datagram over. That is the whole point of the queue: the caller is the
     * session's listener thread, shared with the torrent's streaming.
     *
     * @throws Exception if the worker cannot be waited for
     */
    @Test
    public void testDatagramIsHandledOffTheListenerThread() throws Exception {
        FakeSession sess = new FakeSession();
        KRPC krpc = new KRPC(CTX, "i2psnark", sess.session);
        try {
            byte[] token = handOutToken(krpc);
            deliver(krpc, sess, "aa", token);
            // the caller returned without having sent anything itself
            assertTrue(sess.awaitSends(1, 3000));
            String sent = sess.sends.get(0);
            assertTrue("sent on " + sent, sent.startsWith(RX_THREAD));
            assertEquals(RX_THREAD + "|aa", sent);
        } finally {
            krpc.stop();
        }
    }

    /**
     * The worker takes datagrams off the queue in the order they arrived, so a node's
     * queries are answered in sequence and the routing table sees them that way too.
     *
     * @throws Exception if the worker cannot be waited for
     */
    @Test
    public void testQueuedDatagramsKeepTheirOrder() throws Exception {
        FakeSession sess = new FakeSession();
        KRPC krpc = new KRPC(CTX, "i2psnark", sess.session);
        try {
            byte[] token = handOutToken(krpc);
            sess.gate = new CountDownLatch(1);
            String[] ids = {"a1", "a2", "a3"};
            for (String id : ids) {
                deliver(krpc, sess, id, token);
            }
            // the worker is now held inside the first reply, with two more queued
            assertTrue(sess.awaitInSend(3000));
            sess.gate.countDown();
            assertTrue(sess.awaitSends(ids.length, 5000));
            List<String> got = sess.sends.subList(0, ids.length);
            for (int i = 0; i < ids.length; i++) {
                assertEquals(RX_THREAD + "|" + ids[i], got.get(i));
            }
        } finally {
            krpc.stop();
        }
    }

    /**
     * Once the queue is full the listener drops and counts rather than queueing, so the
     * backlog of remote-triggered allocations stays bounded and the listener thread is
     * never made to wait.
     *
     * @throws Exception if the queue cannot be read or the worker cannot be waited for
     */
    @Test
    public void testAFullQueueDropsAndCounts() throws Exception {
        FakeSession sess = new FakeSession();
        KRPC krpc = new KRPC(CTX, "i2psnark", sess.session);
        try {
            byte[] token = handOutToken(krpc);
            BlockingQueue<?> queue = (BlockingQueue<?>) getField(krpc, "_rxQueue");
            sess.gate = new CountDownLatch(1);
            deliver(krpc, sess, "hold", token);
            // one datagram is in the worker's hands, so the queue itself is empty
            assertTrue(sess.awaitInSend(3000));
            int extra = 5;
            for (int i = 0; i < KRPC.RX_QUEUE + extra; i++) {
                deliver(krpc, sess, "q" + i, token);
            }
            assertEquals(KRPC.RX_QUEUE, queue.size());
            assertEquals(extra, krpc.getRxDropped());
            sess.gate.countDown();
            assertTrue(sess.awaitSends(KRPC.RX_QUEUE + 1, 10000));
            assertEquals(extra, krpc.getRxDropped());
        } finally {
            krpc.stop();
        }
    }

    /**
     * The bound is small enough that a flooded torrent cannot grow the backlog without
     * bound, and large enough to ride out a normal burst of replies to our own queries.
     */
    @Test
    public void testTheQueueBoundIsSmall() {
        assertTrue(KRPC.RX_QUEUE > 8);
        assertTrue(KRPC.RX_QUEUE <= 256);
    }

    /**
     * The worker is a single thread, so the routing table, the token maps and the reply
     * sends stay written by one thread as they were when the listener did the work, and it
     * is gone once the DHT is stopped.
     *
     * @throws Exception if the worker field cannot be read
     */
    @Test
    public void testThereIsOneReceiveWorker() throws Exception {
        FakeSession sess = new FakeSession();
        KRPC krpc = new KRPC(CTX, "i2psnark", sess.session);
        try {
            Thread worker = (Thread) getField(krpc, "_receiver");
            assertNotNull(worker);
            assertTrue(worker.isAlive());
            assertTrue(worker.getName().startsWith(RX_THREAD));
            assertTrue(worker.isDaemon());
            krpc.stop();
            assertNull(getField(krpc, "_receiver"));
            worker.join(2000);
            assertFalse(worker.isAlive());
        } finally {
            krpc.stop();
        }
    }
}

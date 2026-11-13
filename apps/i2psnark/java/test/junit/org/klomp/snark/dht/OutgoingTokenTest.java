package org.klomp.snark.dht;

import static org.junit.Assert.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.Map;

import net.i2p.I2PAppContext;
import net.i2p.client.I2PClientFactory;
import net.i2p.client.I2PSession;
import net.i2p.data.Destination;

import org.junit.Test;

/**
 * Pins what happens to a get_peers token when the outbound token map is already full. The
 * map is bounded, and when it was full the token was simply not stored - but the response
 * still went out carrying it, so the node was told it could announce back to us and then
 * had its announce_peer refused as an unknown token. A torrent refusing its own served
 * swarm is worse than a torrent that never offered the token.
 *
 * @since 0.9.72+
 */
public class OutgoingTokenTest {

    private static final I2PAppContext CTX = I2PAppContext.getGlobalContext();

    /**
     * One instance per test method, as JUnit builds a fresh test object for each one.
     *
     * @since 0.9.72+
     */
    public OutgoingTokenTest() {}

    /** A fake I2PSession good enough to construct a KRPC. */
    private static final class FakeSession {
        final Destination dest;
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
                                        if ("getMyDestination".equals(method.getName())) {
                                            return dest;
                                        }
                                        Class<?> rt = method.getReturnType();
                                        if (rt == boolean.class) return Boolean.FALSE;
                                        if (rt == int.class) return Integer.valueOf(0);
                                        if (rt == long.class) return Long.valueOf(0);
                                        return null;
                                    });
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
        Field f = obj.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(obj);
    }

    /**
     * The map-bound decision: a token fits while the map is below its cap and does not
     * once it is at or over it.
     */
    @Test
    public void testTokenRoomBoundaries() {
        int cap = KRPC.MAX_OUTBOUND_TOKENS;
        assertTrue(KRPC.tokenRoom(0, cap));
        assertTrue(KRPC.tokenRoom(cap - 1, cap));
        assertFalse(KRPC.tokenRoom(cap, cap));
        assertFalse(KRPC.tokenRoom(cap + 1, cap));
    }

    /**
     * A token that is stored can be looked up again, which is what lets the announce that
     * comes back with it be traced to the node it was handed to.
     *
     * @throws Exception if the token map cannot be read
     */
    @Test
    public void testStoredTokenIsRetrievable() throws Exception {
        FakeSession sess = new FakeSession();
        KRPC krpc = new KRPC(CTX, "i2psnark", sess.session);
        try {
            NodeInfo node = new NodeInfo(sess.dest, 1234);
            Token token = new Token(CTX);
            assertTrue(krpc.storeOutgoingToken(token, node));
            @SuppressWarnings("unchecked")
            Map<Token, NodeInfo> map = (Map<Token, NodeInfo>) getField(krpc, "_outgoingTokens");
            assertSame(node, map.get(token));
        } finally {
            krpc.stop();
        }
    }

    /**
     * Once the map is at its bound, storing reports false rather than silently dropping
     * the token, so the caller knows not to hand the token out. The map never grows past
     * its bound however many tokens are offered.
     *
     * @throws Exception if the token map cannot be read
     */
    @Test
    public void testFullMapRefusesFurtherTokens() throws Exception {
        FakeSession sess = new FakeSession();
        KRPC krpc = new KRPC(CTX, "i2psnark", sess.session);
        try {
            NodeInfo node = new NodeInfo(sess.dest, 1234);
            @SuppressWarnings("unchecked")
            Map<Token, NodeInfo> map = (Map<Token, NodeInfo>) getField(krpc, "_outgoingTokens");
            int cap = KRPC.MAX_OUTBOUND_TOKENS;
            int stored = 0;
            // one past the bound, so the refusal is reached rather than the boundary
            for (int i = 0; i <= cap; i++) {
                if (krpc.storeOutgoingToken(new Token(CTX), node)) {
                    stored++;
                }
            }
            assertEquals(cap, stored);
            assertEquals(cap, map.size());
        } finally {
            krpc.stop();
        }
    }

    /**
     * A refused token is not left half-stored: the map is unchanged by a store that
     * reported false, so the count a caller reads afterwards is the count that was really
     * handed out.
     *
     * @throws Exception if the token map cannot be read
     */
    @Test
    public void testRefusedTokenLeavesTheMapUnchanged() throws Exception {
        FakeSession sess = new FakeSession();
        KRPC krpc = new KRPC(CTX, "i2psnark", sess.session);
        try {
            NodeInfo node = new NodeInfo(sess.dest, 1234);
            @SuppressWarnings("unchecked")
            Map<Token, NodeInfo> map = (Map<Token, NodeInfo>) getField(krpc, "_outgoingTokens");
            for (int i = 0; i < KRPC.MAX_OUTBOUND_TOKENS; i++) {
                assertTrue(krpc.storeOutgoingToken(new Token(CTX), node));
            }
            Token extra = new Token(CTX);
            assertFalse(krpc.storeOutgoingToken(extra, node));
            assertEquals(KRPC.MAX_OUTBOUND_TOKENS, map.size());
            assertNull(map.get(extra));
        } finally {
            krpc.stop();
        }
    }
}

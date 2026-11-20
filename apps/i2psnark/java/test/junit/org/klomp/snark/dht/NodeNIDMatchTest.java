package org.klomp.snark.dht;

import static org.junit.Assert.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

import net.i2p.I2PAppContext;
import net.i2p.client.I2PClientFactory;
import net.i2p.data.Destination;
import net.i2p.data.Hash;

import org.junit.Test;
import org.klomp.snark.bencode.BEValue;

/**
 * A node id a peer named itself with is tested against the sender's own hash and port,
 * rather than thrown at.
 *
 * <p>The sender's destination is authenticated by the session that carried the datagram,
 * so the hash is the trustworthy half of the pair and the id is what may be wrong. The
 * query path used to build the node and let the constructor's check throw, which reached
 * the receive loop as an unnamed "NID/Hash mismatch" stack trace and spent the
 * once-a-minute trace allowance a real parse failure needs. {@link NodeInfo#nidMatches}
 * is that same check as an answer instead of an exception, and {@link NodeInfo#nidPort}
 * says which port a mismatched id was computed for - the only half of a wrong id a
 * reader can act on.
 *
 * <p>These also pin that the refactor left the constructor's own check exactly as it was.
 *
 * @since 0.9.72+
 */
public class NodeNIDMatchTest {

    /**
     * One instance per test method, as JUnit builds a fresh test object for each one.
     */
    public NodeNIDMatchTest() {}

    /** Supplies the random bytes a generated NID fills past the hash and port. */
    private static final I2PAppContext CTX = I2PAppContext.getGlobalContext();

    /** The DHT query port used by the cases that are not about the port. */
    private static final int PORT = 23060;

    /**
     * A destination hash with distinct bytes, so a one-byte change anywhere in it or in a
     * node id is visible to the checks.
     *
     * @param b the first byte, the rest counting up from it
     * @return a 32-byte hash
     */
    private static Hash hash(int b) {
        byte[] h = new byte[Hash.HASH_LENGTH];
        for (int i = 0; i < h.length; i++)
            h[i] = (byte) (b + i);
        return Hash.create(h);
    }

    /**
     * The id the routing table would hold for a hash and port matches that hash and port,
     * and the constructor accepts the same pair.
     */
    @Test
    public void testAGeneratedIDMatchesWhatItWasMadeFor() {
        Hash h = hash(1);
        NID nid = NodeInfo.generateNID(h, PORT, CTX.random());
        assertTrue(NodeInfo.nidMatches(nid, h, PORT));
        new NodeInfo(nid, h, PORT);
    }

    /**
     * An id made for one port is not the id for another, and the port it does name is
     * reported rather than lost. Both halves of the port matter: the low byte alone, and
     * a port that reaches into the high byte.
     */
    @Test
    public void testAnIDForAnotherPortDoesNotMatchAndSaysWhich() {
        Hash h = hash(2);
        for (int made : new int[] { 1, 80, 255, 256, 23060, 65534 }) {
            NID nid = NodeInfo.generateNID(h, made, CTX.random());
            for (int asked : new int[] { 1, 80, 255, 256, 23060, 65534 }) {
                if (asked == made) {
                    assertTrue(made + " vs " + asked, NodeInfo.nidMatches(nid, h, asked));
                } else {
                    assertFalse(made + " vs " + asked, NodeInfo.nidMatches(nid, h, asked));
                }
            }
            assertEquals(made, NodeInfo.nidPort(nid, h));
        }
    }

    /**
     * An id made for another destination does not match, and the mismatch is visible in
     * the four bytes that carry the hash, not in the port.
     */
    @Test
    public void testAnIDForAnotherDestinationDoesNotMatch() {
        Hash h = hash(3);
        Hash other = hash(90);
        NID nid = NodeInfo.generateNID(other, PORT, CTX.random());
        assertFalse(NodeInfo.nidMatches(nid, h, PORT));
        // the port bits still line up, which is what lets the caller tell the two faults
        // apart and print the port it did name
        assertEquals(PORT, NodeInfo.nidPort(nid, other));
    }

    /**
     * A single byte changed in the part of the id that carries the hash breaks the match,
     * at the first byte and at the last of the four.
     */
    @Test
    public void testOneWrongHashByteBreaksTheMatch() {
        Hash h = hash(4);
        NID nid = NodeInfo.generateNID(h, PORT, CTX.random());
        for (int i : new int[] { 0, 1, 2, 3 }) {
            byte[] data = nid.getData().clone();
            data[i] ^= 0x40;
            assertFalse("byte " + i, NodeInfo.nidMatches(new NID(data), h, PORT));
        }
        // past the four hash bytes and the two port bytes the id is random, so changing
        // it there must not break the match
        byte[] data = nid.getData().clone();
        data[6] ^= 0x40;
        data[NID.HASH_LENGTH - 1] ^= 0x40;
        assertTrue(NodeInfo.nidMatches(new NID(data), h, PORT));
    }

    /**
     * The constructor still refuses a pair the predicate refuses, so moving the check out
     * of it did not loosen what a node may hold.
     */
    @Test
    public void testTheConstructorStillRefusesAMismatchedPair() {
        Hash h = hash(5);
        NID nid = NodeInfo.generateNID(hash(77), PORT, CTX.random());
        try {
            new NodeInfo(nid, h, PORT);
            fail("accepted an id of another destination");
        } catch (IllegalArgumentException iae) {
            // expected
        }
        NID wrongPort = NodeInfo.generateNID(h, 9999, CTX.random());
        try {
            new NodeInfo(wrongPort, h, PORT);
            fail("accepted an id of another port");
        } catch (IllegalArgumentException iae) {
            // expected
        }
    }

    /**
     * Generates a destination to be somebody else's sender, so the query under test is
     * never the self-echo the routing table filters on its own.
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
     * Hands KRPC one query as its receive loop would, with the node id the peer named
     * itself with and the destination the session authenticated.
     *
     * @param krpc the instance to query
     * @param from the sender's destination
     * @param nid the id to claim
     * @param method the query method
     * @throws Exception if the call itself failed, including a
     *                   {@link java.lang.reflect.InvocationTargetException} carrying
     *                   whatever the query handler threw - which is what the live fault
     *                   looked like
     */
    private static void receiveQuery(KRPC krpc, Destination from, NID nid, String method)
            throws Exception {
        Method m = KRPC.class.getDeclaredMethod("receiveQuery", MsgID.class, Destination.class,
                                             int.class, String.class, Map.class);
        m.setAccessible(true);
        Map<String, BEValue> args = new HashMap<>();
        args.put("id", new BEValue(nid.getData()));
        m.invoke(krpc, new MsgID(CTX), from, PORT, method, args);
    }

    /**
     * The routing table's map, reached by reflection because nothing outside the DHT holds
     * it.
     *
     * @param krpc the instance to look inside
     * @return the map of stored node ids to nodes
     * @throws Exception if the field cannot be reached
     */
    private static Map<?, ?> nodeMap(KRPC krpc) throws Exception {
        Object nodes = KRPCFilterTest.getField(krpc, "_knownNodes");
        return (Map<?, ?>) KRPCFilterTest.getField(nodes, "_nodeMap");
    }

    /**
     * A query whose node id belongs to another destination is refused where it arrives,
     * not thrown out of the query handler. The live fault: the router logged
     * "Processing a received DHT message failed * Error: NID/Hash mismatch" repeatedly,
     * each with a stack trace ending at the node constructor the query handler calls, and
     * that message named neither the node nor the method and spent the once-a-minute trace
     * allowance a real parse failure needs.
     */
    @Test
    public void testAQueryNamingAnotherDestinationIsRefusedNotThrown() {
        KRPCFilterTest.FakeSession fs = new KRPCFilterTest.FakeSession();
        KRPC krpc = new KRPC(CTX, "i2psnark", fs.session);
        try {
            Destination peer = newDest();
            NID wrong = NodeInfo.generateNID(hash(60), PORT, CTX.random());
            receiveQuery(krpc, peer, wrong, "ping");
            assertFalse("stored a node id that was refused", nodeMap(krpc).containsKey(wrong));
        } catch (java.lang.reflect.InvocationTargetException ite) {
            fail("the query handler threw: " + ite.getCause());
        } catch (Exception e) {
            fail("the query could not be handed over: " + e);
        } finally {
            krpc.stop();
        }
    }

    /**
     * The guard does not refuse a query that names itself correctly: the same call with
     * the id that destination hashes to reaches the routing table.
     */
    @Test
    public void testAQueryNamingItselfCorrectlyIsStillAccepted() {
        KRPCFilterTest.FakeSession fs = new KRPCFilterTest.FakeSession();
        KRPC krpc = new KRPC(CTX, "i2psnark", fs.session);
        try {
            Destination peer = newDest();
            Hash h = peer.calculateHash();
            NID right = NodeInfo.generateNID(h, PORT, CTX.random());
            receiveQuery(krpc, peer, right, "ping");
            assertTrue("dropped a node id that was valid", nodeMap(krpc).containsKey(right));
        } catch (Exception e) {
            fail("a valid query did not reach the routing table: " + e);
        } finally {
            krpc.stop();
        }
    }
}

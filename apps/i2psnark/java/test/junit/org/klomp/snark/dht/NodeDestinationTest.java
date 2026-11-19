package org.klomp.snark.dht;

import static org.junit.Assert.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

import net.i2p.I2PAppContext;
import net.i2p.client.I2PClientFactory;
import net.i2p.data.Destination;
import net.i2p.data.Hash;

import org.junit.Test;

/**
 * A DHT node whose destination is not the one its hash names is skipped, not thrown.
 *
 * <p>Both sources of that mismatch are somebody else's bad data: a peer's node list can
 * pair a node id with a destination belonging to another hash, and snark's shared
 * destination cache can still hold an entry that outlived its key. The setter threw
 * through the caller, and the caller is the peer-fetch thread partway through a walk,
 * so one bad node cost the torrent every peer the rest of that walk would have found -
 * and the reason went to stderr, where nothing in the router log would show it.
 *
 * <p>The rejection also used to print the two hashes with {@code was:} and {@code new:}
 * labels, which rendered identically for every reader who could not tell that the
 * comparison had in fact failed.
 */
public class NodeDestinationTest {

    /**
     * One instance per test method, as JUnit builds a fresh test object for each one.
     */
    public NodeDestinationTest() {}

    /** The context the KRPC takes its log manager from. */
    private static final I2PAppContext CTX = I2PAppContext.getGlobalContext();

    /** The reply port every node here is built with. */
    private static final int PORT = 23060;

    /**
     * A node whose id is the id the routing table would hold for that hash and port, so
     * the node's own {@code verify()} accepts it and only the destination is in question.
     *
     * @param hash the destination hash the node names
     * @return the node, with no destination yet
     */
    private static NodeInfo node(Hash hash) {
        byte[] hb = hash.getData();
        // a NID is 20 bytes, the same length as an InfoHash
        byte[] nb = new byte[20];
        System.arraycopy(hb, 0, nb, 0, 4);
        nb[4] = (byte) (hb[4] ^ (PORT >> 8));
        nb[5] = (byte) (hb[5] ^ PORT);
        System.arraycopy(hb, 6, nb, 6, Math.min(hb.length - 6, nb.length - 6));
        return new NodeInfo(new NID(nb), hash, PORT);
    }

    /**
     * @return a freshly generated destination, with its own hash
     */
    private static Destination destination() {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            I2PClientFactory.createClient().createDestination(out);
            return Destination.create(new ByteArrayInputStream(out.toByteArray()));
        } catch (Exception e) {
            throw new RuntimeException("dest gen failed", e);
        }
    }

    /**
     * @return a DHT to ask about attaching, over a session that never sends
     */
    private static KRPC krpc() {
        return new KRPC(CTX, "i2psnark", new KRPCFilterTest.FakeSession().session);
    }

    /**
     * The destination a node's hash names is attached, and the node becomes contactable.
     */
    @Test
    public void matchingDestinationIsAttached() {
        Destination dest = destination();
        NodeInfo n = node(dest.calculateHash());
        assertTrue(krpc().attachDestination(n, dest));
        assertSame(dest, n.getDestination());
    }

    /**
     * A destination belonging to another hash is refused, and the node is left exactly as
     * it was - unresolved, and still usable for the rest of the walk.
     */
    @Test
    public void mismatchedDestinationIsRefusedAndTheNodeSurvives() {
        Destination dest = destination();
        byte[] other = new byte[Hash.HASH_LENGTH];
        for (int i = 0; i < other.length; i++) {
            other[i] = (byte) ((i * 37 + 11) & 0xff);
        }
        NodeInfo n = node(Hash.create(other));
        assertFalse(krpc().attachDestination(n, dest));
        assertNull("the refused node lost its state", n.getDestination());
    }

    /**
     * The refusal is a return value, not an exception: the peer-fetch thread that meets it
     * is mid-walk, and an exception there ends the walk for the whole torrent.
     */
    @Test
    public void refusalDoesNotEscapeToTheCaller() {
        Destination dest = destination();
        byte[] other = new byte[Hash.HASH_LENGTH];
        other[0] = 1;
        NodeInfo n = node(Hash.create(other));
        try {
            krpc().attachDestination(n, dest);
        } catch (RuntimeException re) {
            fail("the mismatch escaped as " + re);
        }
    }

    /**
     * The rejection names the node and both hashes in full, so a reader can tell which
     * peer sent the bad entry and which of the two hashes is wrong.
     */
    @Test
    public void theRejectionNamesBothHashes() {
        Destination dest = destination();
        Hash other = Hash.create(new byte[Hash.HASH_LENGTH]);
        NodeInfo n = node(other);
        try {
            n.setDestination(dest);
            fail("A destination of another hash was accepted");
        } catch (IllegalArgumentException iae) {
            String msg = iae.getMessage();
            assertTrue("the node's own hash is missing: " + msg, msg.contains(other.toBase64()));
            assertTrue("the destination's hash is missing: " + msg,
                       msg.contains(dest.calculateHash().toBase64()));
        }
    }

    /**
     * A node that already has a destination keeps it: the first answer wins, and a later
     * one is dropped rather than replacing it.
     */
    @Test
    public void aResolvedNodeKeepsItsFirstDestination() {
        Destination dest = destination();
        NodeInfo n = node(dest.calculateHash());
        assertTrue(krpc().attachDestination(n, dest));
        Destination second = destination();
        assertTrue("a resolved node reported a failure", krpc().attachDestination(n, second));
        assertSame(dest, n.getDestination());
    }
}

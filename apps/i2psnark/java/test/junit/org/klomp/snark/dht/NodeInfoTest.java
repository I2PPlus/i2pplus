package org.klomp.snark.dht;

import static org.junit.Assert.*;

import org.junit.Test;

import net.i2p.data.Base32;
import net.i2p.data.Hash;

/**
 * Pins how a DHT node renders in a log line: its short id and its port, and nothing
 * else. The form the node used to print, {@code [xxxxxxxx]:23060 (unknown dest)},
 * was inherited by every message that named a node, so a message with an aside of
 * its own read as two adjacent parentheticals, and the destination state was
 * reported whether or not it had anything to do with the event.
 *
 * @since 0.9.72+
 */
public class NodeInfoTest {

    /**
     * Test instance; every test builds a node and reads its form back.
     */
    public NodeInfoTest() {
    }

    /**
     * A node whose id is the id the routing table would hold for that hash and port,
     * built the way {@code KRPCFilterTest} builds one so {@code verify()} accepts it.
     *
     * @param port the port the node is reached on
     * @return a node with no destination, the state a walked node is normally in
     */
    private static NodeInfo node(int port) {
        byte[] hb = new byte[Hash.HASH_LENGTH];
        for (int i = 0; i < hb.length; i++) {
            hb[i] = (byte) ((i * 37 + 11) & 0xff);
        }
        // a NID is 20 bytes, the same length as an InfoHash
        byte[] nb = new byte[20];
        System.arraycopy(hb, 0, nb, 0, 4);
        nb[4] = (byte) (hb[4] ^ (port >> 8));
        nb[5] = (byte) (hb[5] ^ port);
        System.arraycopy(hb, 6, nb, 6, Math.min(hb.length - 6, nb.length - 6));
        return new NodeInfo(new NID(nb), Hash.create(hb), port);
    }

    /** A node is its base32 id and its port, the form a destination grep finds. */
    @Test
    public void nodeIsItsShortIdAndPort() {
        NodeInfo n = node(23060);
        assertEquals(Base32.encode(n.getNID().getData()).substring(0, 8) + ":23060",
                     n.toString());
    }

    /**
     * No destination aside, so a message that has a parenthetical of its own does not
     * end up with two.
     */
    @Test
    public void noAsideAboutTheDestinationIsAppended() {
        String s = node(19478).toString();
        assertFalse(s, s.contains("("));
        assertFalse(s, s.contains("dest"));
        // the destination really is unknown here, and that is the site's business
        assertNull(node(19478).getDestination());
    }

    /**
     * The id and the port and nothing else - no brackets, because a node id is not a
     * destination and the console log viewer resolves every bracketed eight-character
     * base32 token as a lease set.
     */
    @Test
    public void idAndPortAndNothingElse() {
        String s = node(40000).toString();
        assertEquals(0, s.length() - s.replace("[", "").length());
        assertEquals(0, s.length() - s.replace("]", "").length());
        assertTrue(s, s.matches("[a-z2-7]{8}:\\d+"));
    }
}

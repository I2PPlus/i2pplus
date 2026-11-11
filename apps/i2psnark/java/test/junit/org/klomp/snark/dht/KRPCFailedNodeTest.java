package org.klomp.snark.dht;

import java.lang.reflect.Method;
import java.util.Set;
import net.i2p.I2PAppContext;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * The iterative get_peers and find_node walks try a bounded number of nodes, one at a
 * time, and each attempt waits seconds for a reply that may never come. A node that has
 * already failed to answer a long run of queries is on the blacklist, and spending one
 * of those attempts, and that waiting time, on it is what the walk should not do. These
 * tests pin the check the walk uses, and that any contact puts the node back in play.
 *
 * @since 0.9.72+
 */
public class KRPCFailedNodeTest {

    private static final I2PAppContext CTX = I2PAppContext.getGlobalContext();

    /**
     * Fixture group for the failed-node check; it holds no state.
     */
    public KRPCFailedNodeTest() {}

    /**
     * A blacklisted node is recognised as recently failed, a node that is not on the
     * blacklist is not, and hearing from a blacklisted node clears it.
     *
     * @throws Exception if the KRPC internals cannot be reached
     */
    @Test
    public void testBlacklistedNodeIsRecognised() throws Exception {
        KRPCFilterTest.FakeSession sess = new KRPCFilterTest.FakeSession();
        KRPC krpc = new KRPC(CTX, "i2psnark", sess.session);
        try {
            NodeInfo deaf = KRPCFilterTest.validNode((byte) 42, 7000);
            NodeInfo answering = KRPCFilterTest.validNode((byte) 52, 7001);
            assertFalse(krpc.recentlyFailed(deaf));
            assertFalse(krpc.recentlyFailed(answering));

            @SuppressWarnings("unchecked")
            Set<NID> blacklist = (Set<NID>) KRPCFilterTest.getField(krpc, "_blacklist");
            blacklist.add(deaf.getNID());
            assertTrue(krpc.recentlyFailed(deaf));
            assertFalse(krpc.recentlyFailed(answering));

            // any contact at all takes it off again, so a node that starts
            // answering is tried again by the next lookup
            Method heardFrom = krpc.getClass().getDeclaredMethod("heardFrom", NodeInfo.class);
            heardFrom.setAccessible(true);
            heardFrom.invoke(krpc, deaf);
            assertFalse(krpc.recentlyFailed(deaf));
        } finally {
            krpc.stop();
        }
    }

    /**
     * The blacklist is keyed on the node id, so a node that reappears with the same id
     * after a restart is still recognised as the one that was failing.
     *
     * @throws Exception if the KRPC internals cannot be reached
     */
    @Test
    public void testBlacklistFollowsTheNodeId() throws Exception {
        KRPCFilterTest.FakeSession sess = new KRPCFilterTest.FakeSession();
        KRPC krpc = new KRPC(CTX, "i2psnark", sess.session);
        try {
            NodeInfo deaf = KRPCFilterTest.validNode((byte) 62, 7002);
            @SuppressWarnings("unchecked")
            Set<NID> blacklist = (Set<NID>) KRPCFilterTest.getField(krpc, "_blacklist");
            blacklist.add(new NID(deaf.getNID().getData()));
            assertTrue(krpc.recentlyFailed(deaf));
            blacklist.remove(deaf.getNID());
            assertFalse(krpc.recentlyFailed(deaf));
        } finally {
            krpc.stop();
        }
    }
}

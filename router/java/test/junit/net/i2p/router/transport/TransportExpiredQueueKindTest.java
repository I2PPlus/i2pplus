package net.i2p.router.transport;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.HashSet;
import java.util.Set;

import net.i2p.data.i2np.DataMessage;
import net.i2p.data.i2np.DatabaseLookupMessage;
import net.i2p.data.i2np.DatabaseSearchReplyMessage;
import net.i2p.data.i2np.DatabaseStoreMessage;
import net.i2p.data.i2np.DeliveryStatusMessage;
import net.i2p.data.i2np.GarlicMessage;
import net.i2p.data.i2np.OutboundTunnelBuildReplyMessage;
import net.i2p.data.i2np.ShortTunnelBuildMessage;
import net.i2p.data.i2np.TunnelBuildMessage;
import net.i2p.data.i2np.TunnelBuildReplyMessage;
import net.i2p.data.i2np.TunnelGatewayMessage;
import net.i2p.data.i2np.VariableTunnelBuildMessage;
import net.i2p.data.i2np.VariableTunnelBuildReplyMessage;

import org.junit.Test;

/**
 * Which per-kind statistic a message that expired in the outbound queue counts
 * under.
 * <p>
 * Pins {@link TransportImpl#expiredQueueStatName(int)}: the totals say that a
 * few dozen messages a minute die with most of their window unused, and the
 * buckets are what makes that actionable, because the fix for an expired build
 * request (queue priority) is not the fix for an expired build reply (nothing).
 * A message type that is not classified lands in the catch-all rather than
 * going uncounted.
 *
 * @since 0.9.72+
 */
public class TransportExpiredQueueKindTest {

    /**
     * Calls the classifier and holds nothing of its own.
     */
    public TransportExpiredQueueKindTest() {
    }

    /**
     * The three build request forms are one kind: all three are our own tunnel
     * being built, and all three starving means the pool is not refilling.
     */
    @Test
    public void testBuildRequestsShareABucket() {
        assertBucket("transport.expiredOnQueueBuild", TunnelBuildMessage.MESSAGE_TYPE,
                     VariableTunnelBuildMessage.MESSAGE_TYPE, ShortTunnelBuildMessage.MESSAGE_TYPE);
    }

    /**
     * The three reply forms are a separate kind: an expired reply delays a
     * stranger's tunnel build, not ours, and is not evidence about our own pool.
     */
    @Test
    public void testBuildRepliesShareABucket() {
        assertBucket("transport.expiredOnQueueBuildReply", TunnelBuildReplyMessage.MESSAGE_TYPE,
                     VariableTunnelBuildReplyMessage.MESSAGE_TYPE,
                     OutboundTunnelBuildReplyMessage.MESSAGE_TYPE);
    }

    /**
     * Payload kinds together: what a client or transit stream is carrying, which
     * is what a transport that cannot drain its queue is really dropping.
     */
    @Test
    public void testPayloadSharesABucket() {
        assertBucket("transport.expiredOnQueueData", DataMessage.MESSAGE_TYPE,
                     GarlicMessage.MESSAGE_TYPE, TunnelGatewayMessage.MESSAGE_TYPE);
    }

    /**
     * NetDb traffic is its own kind: an expired Lookup or Store is our own
     * routing work losing the queue, and it competes with the payload above.
     */
    @Test
    public void testNetDbSharesABucket() {
        assertBucket("transport.expiredOnQueueNetDb", DatabaseLookupMessage.MESSAGE_TYPE,
                     DatabaseStoreMessage.MESSAGE_TYPE, DatabaseSearchReplyMessage.MESSAGE_TYPE);
    }

    /**
     * Anything else - a DeliveryStatus, a Blame, a PeerTest, a type this router
     * does not know - is counted rather than dropped, so the buckets add up to
     * the total the count stat reports.
     */
    @Test
    public void testUnknownTypesFallIntoTheCatchAll() {
        assertBucket("transport.expiredOnQueueOther", DeliveryStatusMessage.MESSAGE_TYPE, 0, 17, 31, 999);
    }

    /**
     * Every type id a message can carry is classified, and the classifier only
     * ever returns names the constructor registers. A sixth bucket added here
     * without a matching createRequiredRateStat() shows up as a mismatch.
     */
    @Test
    public void testEveryTypeIdFeedsARegisteredStat() {
        Set<String> registered = new HashSet<>(6);
        registered.add("transport.expiredOnQueueBuild");
        registered.add("transport.expiredOnQueueBuildReply");
        registered.add("transport.expiredOnQueueData");
        registered.add("transport.expiredOnQueueNetDb");
        registered.add("transport.expiredOnQueueOther");
        Set<String> returned = new HashSet<>(6);
        for (int type = 0; type <= 31; type++) {
            String name = TransportImpl.expiredQueueStatName(type);
            assertTrue("type " + type + " -> " + name, registered.contains(name));
            returned.add(name);
        }
        assertEquals("every registered bucket is reachable", registered, returned);
    }

    /**
     * Asserts each given type id classifies under the named stat.
     *
     * @param expected the stat name the kinds must feed
     * @param types the I2NP message type ids to check
     */
    private static void assertBucket(String expected, int... types) {
        for (int type : types) {
            assertEquals("type " + type, expected, TransportImpl.expiredQueueStatName(type));
        }
    }
}

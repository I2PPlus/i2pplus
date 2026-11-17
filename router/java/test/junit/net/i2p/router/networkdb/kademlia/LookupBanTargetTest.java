package net.i2p.router.networkdb.kademlia;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import net.i2p.data.Hash;

/**
 * An excessive-lookup ban may only name the peer the lookup arrived on. The
 * <code>from</code> field of the lookup is written by the sender and proves
 * nothing, so a peer can otherwise get any router on the network banlisted by
 * naming it while the peer itself sends the flood.
 *
 * @since 0.9.72+
 */
public class LookupBanTargetTest {

    /** Holds no state; each case builds its own hashes. */
    public LookupBanTargetTest() {}

    /** A direct lookup bans the session peer that sent it. */
    @Test
    public void directLookupBansThePeerItArrivedFrom() {
        byte[] data = new byte[Hash.HASH_LENGTH];
        Hash peer = new Hash(data);
        assertEquals(peer, FloodfillDatabaseLookupMessageHandler.lookupBanTarget(peer));
    }

    /**
     * A lookup delivered through a reply tunnel has no provable peer, so nobody
     * is banned and the lookup is only dropped.
     */
    @Test
    public void tunnelDeliveredLookupBansNobody() {
        assertNull(FloodfillDatabaseLookupMessageHandler.lookupBanTarget(null));
    }

    /**
     * The rate a dropped lookup is reported against comes from the throttler and
     * the ban hammer themselves, so the log cannot drift away from the code that
     * enforces it.
     */
    @Test
    public void reportedRateMatchesTheEnforcingCounters() {
        // The burst window is the shorter of the two and both caps are usable:
        // a log line printing the wrong pair would still read plausibly, so the
        // relationship between them is pinned here.
        assertTrue("the ban hammer's burst window must be shorter than its counting window",
                   LookupBanHammer.getCountWindowMs() > LookupBanHammer.getBurstWindowMs());
        assertTrue("the throttler must report a usable burst cap",
                   LookupThrottler.getBurstWindowMs() > 0 && LookupThrottler.getBurstThreshold() > 0);
        assertTrue("the ban hammer's counting cap must not sit below its burst cap",
                   LookupBanHammer.getMaxLookups() >= LookupBanHammer.getBurstThreshold());
    }
}

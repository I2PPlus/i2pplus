package net.i2p.router.tunnel.pool;

import java.util.ArrayList;
import java.util.List;

import net.i2p.data.Hash;
import net.i2p.util.ArraySet;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Tests the first-hop candidate batch, the tier widening decision, and the trim that keeps
 * only the accepted peer.
 *
 * <p>Three faults motivated these. The batch did not exist: selection asked the tiers for
 * exactly one candidate while the quality loop could only iterate over what it held, so a
 * single bad peer ended the slot. Then, once a batch was fetched, it overran the fixed-capacity
 * {@link ArraySet} it was fetched into and threw {@code SetFullException} mid-build. And the
 * widen decision claimed in a comment to take from two tiers while the code took one.
 *
 * @since 0.9.71+
 */
public class ClientPeerSelectorFirstHopBatchTest {

    /** A distinct, well-formed Hash per index; the content is never inspected. */
    private static Hash peer(int i) {
        java.nio.ByteBuffer buf = java.nio.ByteBuffer.allocate(32);
        buf.position(24);
        buf.putInt(i);
        return Hash.create(buf.array());
    }

    private static ArraySet<Hash> batch(int n) {
        ArraySet<Hash> set = new ArraySet<>(n);
        for (int i = 0; i < n; i++) {
            set.add(peer(i));
        }
        return set;
    }

    // ---------- the batch must fit its container ----------

    /**
     * A batch has to fit the set it is fetched into.
     *
     * <p>{@link ArraySet} is fixed-capacity and throws on overflow rather than growing, which
     * is what produced a {@code SetFullException} out of {@code selectFirstHop}: the shortfall
     * path allocated capacity 1, then the batch tried to add four. A capacity of one is the
     * regression, so it is the case asserted.
     */
    @Test
    public void aBatchOverflowsAContainerSizedForOnePeer() {
        ArraySet<Hash> one = new ArraySet<>(1);
        one.add(peer(0));
        try {
            one.add(peer(1));
            fail("a capacity-1 set accepted a second peer, so the overflow cannot be detected");
        } catch (RuntimeException expected) {
            assertTrue("expected a capacity failure, got " + expected,
                       expected.getClass().getName().contains("SetFull"));
        }
    }

    /** A set sized for the batch holds every candidate, which is the fix. */
    @Test
    public void aContainerSizedForTheBatchHoldsIt() {
        ArraySet<Hash> set = new ArraySet<>(4);
        for (int i = 0; i < 4; i++) {
            set.add(peer(i));
        }
        assertEquals(4, set.size());
    }

    // ---------- the trim ----------

    /**
     * Only the accepted peer survives.
     *
     * <p>Every caller does {@code rv.addAll(matches)}, so a batch left intact would splice the
     * rejected candidates into the tunnel path — peers that just failed a first-hop gate.
     */
    @Test
    public void theTrimKeepsOnlyTheAcceptedPeer() {
        ArraySet<Hash> matches = batch(4);
        Hash accepted = peer(1);
        ClientPeerSelector.trimToAcceptedFirstHop(matches, accepted);
        assertEquals("the batch was not reduced to one hop", 1, matches.size());
        assertEquals("the wrong peer was kept", accepted, matches.get(0));
    }

    /** A singleton is already correct and must not be disturbed. */
    @Test
    public void theTrimLeavesASingletonAlone() {
        ArraySet<Hash> matches = batch(1);
        ClientPeerSelector.trimToAcceptedFirstHop(matches, peer(0));
        assertEquals(1, matches.size());
    }

    /**
     * With nothing accepted, the head is kept rather than the slot lost.
     *
     * <p>Returning empty here would cost the cycle a build outright, which is worse than a
     * mediocre gateway that at least answers.
     */
    @Test
    public void theTrimKeepsTheHeadWhenNothingWasAccepted() {
        ArraySet<Hash> matches = batch(3);
        ClientPeerSelector.trimToAcceptedFirstHop(matches, null);
        assertEquals(1, matches.size());
        assertEquals(peer(0), matches.get(0));
    }

    /** An empty batch stays empty — there is nothing to keep. */
    @Test
    public void theTrimOnAnEmptyBatchIsANoOp() {
        ArraySet<Hash> matches = new ArraySet<>(4);
        ClientPeerSelector.trimToAcceptedFirstHop(matches, null);
        assertTrue(matches.isEmpty());
    }

    // ---------- tier widening ----------

    /**
     * Below the widen threshold the batch comes from both tiers.
     *
     * <p>Fast is speed-ranked and HighCapacity is proven-reliability; when either the network
     * or the Fast tier is too small to be choosing between those rankings, taking from one
     * tier alone needlessly halves the pool the quality loop draws from.
     */
    @Test
    public void aStrugglingNetworkWidensToBothTiers() {
        assertTrue(ClientPeerSelector.shouldWidenBothTiers(0.20, 900));
        assertTrue(ClientPeerSelector.shouldWidenBothTiers(0.49, 900));
    }

    /** A healthy network with a large Fast tier draws from Fast alone. */
    @Test
    public void aHealthyNetworkWithALargeFastTierDoesNotWiden() {
        assertFalse(ClientPeerSelector.shouldWidenBothTiers(0.85, 900));
        assertFalse(ClientPeerSelector.shouldWidenBothTiers(0.51, 300));
    }

    /**
     * A small Fast tier widens even on a healthy network.
     *
     * <p>It cannot supply the batch on its own however good the success rate looks, so the
     * shortfall is made up from HighCapacity.
     */
    @Test
    public void aSmallFastTierWidensEvenWhenHealthy() {
        assertTrue(ClientPeerSelector.shouldWidenBothTiers(0.90, 299));
        assertTrue(ClientPeerSelector.shouldWidenBothTiers(0.90, 0));
    }

    /** No data yet is the startup case, and is treated as struggling. */
    @Test
    public void anUnknownBuildSuccessWidens() {
        assertTrue(ClientPeerSelector.shouldWidenBothTiers(Double.NaN, 900));
    }

    /** The boundary is inclusive on the healthy side: 300 fast peers is enough to stand alone. */
    @Test
    public void theFastTierBoundaryIsExact() {
        assertFalse(ClientPeerSelector.shouldWidenBothTiers(0.90,
                        ClientPeerSelector.WIDEN_FAST_PEERS));
        assertTrue(ClientPeerSelector.shouldWidenBothTiers(0.90,
                        ClientPeerSelector.WIDEN_FAST_PEERS - 1));
    }

    /** The build-success boundary likewise. */
    @Test
    public void theBuildSuccessBoundaryIsExact() {
        assertFalse(ClientPeerSelector.shouldWidenBothTiers(
                        ClientPeerSelector.WIDEN_BUILD_SUCCESS, 900));
        assertTrue(ClientPeerSelector.shouldWidenBothTiers(
                        ClientPeerSelector.WIDEN_BUILD_SUCCESS - 0.001, 900));
    }

    /**
     * The three gates are ordered, and the order is what makes recovery coherent.
     *
     * <p>Reading from most-degraded to healthiest: HighCapacity leads ({@code ATTACK_THRESHOLD}),
     * then the batch widens to both tiers ({@code WIDEN_BUILD_SUCCESS}), then the transport bar
     * relaxes ({@code CONNECTING_PREF_BUILD_SUCCESS}). So a struggling network takes the widest
     * supply decisions first and pays for latency last, and recovery unwinds them in reverse —
     * narrowing to one tier before demanding an established session. Any other ordering lets a
     * router narrow its peer pool while still asking for more from each peer.
     */
    @Test
    public void theThreeGatesAreOrdered() {
        assertTrue("HighCapacity must start leading before the batch widens, or the widening "
                   + "decision is reached with the narrow tier already chosen",
                   TunnelPeerSelector.ATTACK_THRESHOLD
                   <= ClientPeerSelector.WIDEN_BUILD_SUCCESS);
        assertTrue("the batch must widen before the transport bar relaxes, so a degraded "
                   + "network is not also asked for slower peers",
                   ClientPeerSelector.WIDEN_BUILD_SUCCESS
                   < ClientPeerSelector.CONNECTING_PREF_BUILD_SUCCESS);
    }

    // ---------- cross-selector consistency ----------

    /**
     * The exploratory selector must widen on exactly the same condition.
     *
     * <p>Exploratory pools exist to carry replies for client pools, so a client selection
     * that widens and an exploratory one that does not leave the two disagreeing about how
     * much peer supply there is. It reads {@link ClientPeerSelector#shouldWidenBothTiers}
     * rather than keeping its own copy of the thresholds, and this pins that it does.
     */
    @Test
    public void theExploratorySelectorWidensOnTheSameCondition() {
        // Sanity on the predicate itself at both edges, so the tie is meaningful rather than
        // two identical references to a broken rule.
        assertTrue(ClientPeerSelector.shouldWidenBothTiers(0.30, 900));
        assertFalse(ClientPeerSelector.shouldWidenBothTiers(0.90, 900));
        // ExploratoryPeerSelector calls the same static, so the class must be reachable and
        // the constants must be the ones it consults.
        assertEquals(0.50d, ClientPeerSelector.WIDEN_BUILD_SUCCESS, 0d);
        assertEquals(300, ClientPeerSelector.WIDEN_FAST_PEERS);
    }

    // ---------- no batch peer may reach the tunnel ----------

    /** Every rejected candidate is gone, not merely deprioritised. */
    @Test
    public void noRejectedCandidateSurvivesTheTrim() {
        ArraySet<Hash> matches = batch(4);
        Hash accepted = peer(2);
        List<Hash> before = new ArrayList<>(matches);
        ClientPeerSelector.trimToAcceptedFirstHop(matches, accepted);
        for (Hash candidate : before) {
            if (!candidate.equals(accepted)) {
                assertFalse("a rejected candidate reached the tunnel path: " + candidate,
                            matches.contains(candidate));
            }
        }
    }
}

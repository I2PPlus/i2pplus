package net.i2p.router.peermanager;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.AbstractMap;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import net.i2p.data.Hash;
import net.i2p.data.router.RouterAddress;
import net.i2p.data.router.RouterInfo;
import net.i2p.router.CommSystemFacade;
import net.i2p.router.NetworkDatabaseFacade;
import net.i2p.router.RouterContext;
import net.i2p.router.util.MaskedIPSet;
import net.i2p.util.OrderedProperties;

import org.junit.Test;

/**
 * Tests the /n subnet-diversity gate that keeps one peer per masked subnet, per
 * port and per family out of a tunnel.
 *
 * <p>Motivation: the gate's result was inverted when the {@code MaskedIPSet}
 * build was inlined into the selection loop. The old code built the peer's keys
 * in a local set, tested {@code containsAny} against the accumulator, then added
 * them; folding the collision test into the adds returned "collision" where the
 * five call sites all read "allowed", so the first peer of every subnet was
 * rejected and only a peer colliding with an already-claimed fingerprint got
 * through. The same fold also let a peer collide with itself, because a router
 * normally publishes two transports on one address.
 *
 * <p>Also pins the key space. The gate compares a peer's fingerprints against
 * keys claimed by other peers, so if the two sides ever build keys differently
 * they never match and the gate silently stops rejecting anything.
 *
 * <p>And it pins who may claim. Only a peer that passes claims its fingerprints,
 * as mainline does. The 0.9.71+ rewrite also claimed a rejected peer's keys, to
 * make the outcome order-independent, but the accumulator is shared across a
 * whole tunnel build, so that claim outlives the candidate that made it: each
 * pass claimed every candidate it sampled — 256 per hop — and the last hop of a
 * build found zero selectable peers while the tier reported itself full. What
 * reaches that accumulator is pinned too: only the peers the cascade actually
 * selected, never a candidate that merely lost the priority pick.
 *
 * <p>The direction-pinning pair matters on its own: a peer on a fresh subnet
 * must pass and a peer sharing a fingerprint with an accepted peer must fail.
 * Asserting only the second would also pass under the inverted polarity.
 *
 * @see ProfileOrganizerSelectionCostTest
 * @since 0.9.71+
 */
public class ProfileOrganizerSubnetDiversityTest {

    /**
     * One instance per test method, as JUnit builds a fresh test object for each
     * one; the class carries no per-test state, only the fixtures the gate is
     * called with.
     *
     * @since 0.9.72+
     */
    public ProfileOrganizerSubnetDiversityTest() {}

    private static final int MASK = 2;

    private static Hash hash(int b) {
        byte[] data = new byte[32];
        data[0] = (byte) b;
        return new Hash(data);
    }

    /**
     * A RouterInfo publishing one address per host, all on the same port, plus an
     * optional family.
     *
     * @param port the port every published NTCP2 address listens on
     * @param family the family name to publish, or null to set no family option
     * @param hosts the hosts to publish, one NTCP2 address per entry
     * @return a router info carrying those addresses and its family option
     */
    private static RouterInfo ri(int port, String family, String... hosts) {
        Set<RouterAddress> addrs = new HashSet<>(hosts.length + 1);
        for (String host : hosts) {
            OrderedProperties props = new OrderedProperties();
            props.setProperty("host", host);
            props.setProperty("port", String.valueOf(port));
            addrs.add(new RouterAddress("NTCP2", props, 10));
        }
        RouterInfo info = new RouterInfo();
        info.setAddresses(addrs);
        Properties opts = new Properties();
        if (family != null)
            opts.setProperty("family", family);
        info.setOptions(opts);
        return info;
    }

    private static RouterContext ctx(RouterInfo info, byte[] commIP) {
        RouterContext ctx = mock(RouterContext.class);
        CommSystemFacade comm = mock(CommSystemFacade.class);
        NetworkDatabaseFacade netDb = mock(NetworkDatabaseFacade.class);
        when(ctx.commSystem()).thenReturn(comm);
        when(ctx.netDb()).thenReturn(netDb);
        when(comm.getIP(any(Hash.class))).thenReturn(commIP);
        when(netDb.lookupLocallyWithoutValidation(any(Hash.class))).thenReturn(info);
        return ctx;
    }

    // ---- gate direction ----

    /** The case the inverted polarity got wrong: a peer on a fresh subnet passes. */
    @Test
    public void freshSubnetPasses() {
        RouterContext c = ctx(ri(1024, null, "1.2.3.4"), null);
        assertTrue(ProfileOrganizer.notRestricted(c, hash(1), new MaskedIPSet(), MASK));
    }

    /** A second peer in the same /16 as an accepted peer is rejected. */
    @Test
    public void sameSubnetIsRejected() {
        RouterContext c1 = ctx(ri(1024, null, "1.2.3.4"), null);
        RouterContext c2 = ctx(ri(2048, null, "1.2.4.5"), null);
        MaskedIPSet ipSet = new MaskedIPSet();
        assertTrue(ProfileOrganizer.notRestricted(c1, hash(1), ipSet, MASK));
        assertFalse(ProfileOrganizer.notRestricted(c2, hash(2), ipSet, MASK));
    }

    /** A peer in another /16 is unaffected by the first one's claim. */
    @Test
    public void differentSubnetPasses() {
        RouterContext c1 = ctx(ri(1024, null, "1.2.3.4"), null);
        RouterContext c2 = ctx(ri(2048, null, "5.6.7.8"), null);
        MaskedIPSet ipSet = new MaskedIPSet();
        assertTrue(ProfileOrganizer.notRestricted(c1, hash(1), ipSet, MASK));
        assertTrue(ProfileOrganizer.notRestricted(c2, hash(2), ipSet, MASK));
    }

    /**
     * Two transports on one address overlap by design, so a dual-stack peer must
     * not be rejected by its own fingerprint. This is what folding the collision
     * test into the adds broke: the second add hit a key the peer had just added.
     */
    @Test
    public void dualTransportOnOneAddressDoesNotSelfCollide() {
        RouterInfo info = ri(30000, null, "9.9.9.9", "9.9.9.9");
        assertTrue(ProfileOrganizer.notRestricted(ctx(info, null), hash(1), new MaskedIPSet(), MASK));
    }

    // ---- what counts as the same fingerprint ----

    /** Different subnets but a shared port still collide, the upstream rule. */
    @Test
    public void sharedPortIsRejected() {
        RouterContext c1 = ctx(ri(30000, null, "1.2.3.4"), null);
        RouterContext c2 = ctx(ri(30000, null, "5.6.7.8"), null);
        MaskedIPSet ipSet = new MaskedIPSet();
        assertTrue(ProfileOrganizer.notRestricted(c1, hash(1), ipSet, MASK));
        assertFalse(ProfileOrganizer.notRestricted(c2, hash(2), ipSet, MASK));
    }

    /** Family is a fingerprint of its own, independent of address. */
    @Test
    public void sharedFamilyIsRejected() {
        RouterContext c1 = ctx(ri(1024, "eeps", "1.2.3.4"), null);
        RouterContext c2 = ctx(ri(2048, "eeps", "5.6.7.8"), null);
        MaskedIPSet ipSet = new MaskedIPSet();
        assertTrue(ProfileOrganizer.notRestricted(c1, hash(1), ipSet, MASK));
        assertFalse(ProfileOrganizer.notRestricted(c2, hash(2), ipSet, MASK));
    }

    /**
     * The comm system's record of the peer's IP counts even when the published
     * address carries none, so a peer already seen on the wire is gated.
     */
    @Test
    public void commSystemIpIsPartOfTheFingerprint() {
        MaskedIPSet ipSet = new MaskedIPSet();
        assertTrue(ProfileOrganizer.notRestricted(ctx(ri(1024, null, "1.2.3.4"), null), hash(1), ipSet, MASK));
        RouterInfo noIp = new RouterInfo();
        noIp.setAddresses(new HashSet<>(0));
        noIp.setOptions(new Properties());
        assertFalse(ProfileOrganizer.notRestricted(ctx(noIp, new byte[] {1, 2, 99, 4}), hash(2), ipSet, MASK));
    }

    // ---- who gets to claim a fingerprint ----

    /**
     * A rejected peer claims nothing. The accumulator is shared across a whole
     * tunnel build — one {@link MaskedIPSet} handed to every hop and every
     * fallback tier — so a key claimed by a peer that was never used stays
     * excluded for the rest of the build. Claiming on rejection is what starved
     * the live router: each pass claimed every candidate it sampled, 256 per hop,
     * and the last hop of the build found "IP gate: 764" with zero candidates.
     */
    @Test
    public void rejectedPeerDoesNotClaimItsKeys() {
        Set<String> claimed = new HashSet<>();
        Set<String> first = new HashSet<>();
        first.add(".aa");
        first.add("p1");
        Set<String> second = new HashSet<>();
        second.add(".aa");
        second.add(".bb");
        assertTrue(ProfileOrganizer.tryClaimKeys(claimed, first));
        assertFalse(ProfileOrganizer.tryClaimKeys(claimed, second));
        assertEquals(2, claimed.size());
        assertFalse(claimed.contains(".bb"));
    }

    /**
     * The first peer of a subnet claims it, and a peer rejected against that
     * claim leaves everything else it owns free for the next hop or the next
     * pass. Order therefore matters, and that is acceptable: the scan starts at
     * a random offset each pass, so which peer claims a subnet varies by build.
     */
    @Test
    public void firstClaimerWinsAndRejectionsLeaveTheRestFree() {
        Set<String> claimed = new HashSet<>();
        Set<String> b = new HashSet<>();
        b.add(".aa");
        b.add(".bb");
        Set<String> a = new HashSet<>();
        a.add(".aa");
        Set<String> c = new HashSet<>();
        c.add(".cc");
        assertTrue(ProfileOrganizer.tryClaimKeys(claimed, b));
        assertFalse(ProfileOrganizer.tryClaimKeys(claimed, a));
        assertTrue(ProfileOrganizer.tryClaimKeys(claimed, c));
        assertEquals(3, claimed.size());
    }

    /**
     * The live failure, end to end: hop 1 takes a peer, hop 2's candidate is
     * rejected for sharing that peer's port, and hop 3 still finds a peer in the
     * subnet hop 2's rejected candidate sat in. Under the claim-on-rejection rule
     * hop 3 was excluded by a peer no tunnel ever used.
     */
    @Test
    public void rejectedCandidateLeavesItsSubnetUsableForLaterHops() {
        MaskedIPSet ipSet = new MaskedIPSet();
        assertTrue(ProfileOrganizer.notRestricted(ctx(ri(1024, null, "1.2.3.4"), null), hash(1), ipSet, MASK));
        // Rejected on the shared port, not on its address
        assertFalse(ProfileOrganizer.notRestricted(ctx(ri(1024, null, "9.9.9.9"), null), hash(2), ipSet, MASK));
        assertTrue(ProfileOrganizer.notRestricted(ctx(ri(2048, null, "9.9.5.5"), null), hash(3), ipSet, MASK));
    }

    /** Nothing known about the peer means nothing to collide with. */
    @Test
    public void noFingerprintsPasses() {
        assertTrue(ProfileOrganizer.tryClaimKeys(new HashSet<>(), new HashSet<>()));
        assertTrue(ProfileOrganizer.notRestricted(ctx(new RouterInfo(), null), hash(1), new MaskedIPSet(), MASK));
    }

    // ---- what reaches the build's accumulator ----

    /**
     * A candidate that passed the gate but lost the priority pick leaves no mark
     * on the accumulator, so the next hop of the same build may still use its
     * subnet. Only the peers the cascade actually selected are merged back.
     */
    @Test
    public void onlySelectedCandidatesReachTheAccumulator() {
        Set<String> ipSet = new HashSet<>();
        List<Set<String>> candidateKeys = Arrays.asList(keys(".aa", "p1"), keys(".bb", "p2"), keys(".cc"));
        List<Map.Entry<Hash, PeerProfile>> candidates = Arrays.asList(entry(1), entry(2), entry(3));
        Set<Hash> selected = new HashSet<>();
        selected.add(hash(1));
        ProfileOrganizer.mergeSelectedFingerprints(ipSet, candidateKeys, candidates, selected);
        assertEquals(2, ipSet.size());
        assertTrue(ipSet.contains(".aa"));
        assertTrue(ipSet.contains("p1"));
        assertFalse(ipSet.contains(".bb"));
        assertFalse(ipSet.contains(".cc"));
    }

    /**
     * The merge adds to what the earlier hops of this build already claimed — it
     * never replaces the accumulator, which would let a later hop reuse a subnet
     * an earlier hop is already tunnelling through.
     */
    @Test
    public void mergeAddsToWhatEarlierHopsClaimed() {
        Set<String> ipSet = keys(".pre", "p0");
        List<Set<String>> candidateKeys = Arrays.asList(keys(".aa"));
        List<Map.Entry<Hash, PeerProfile>> candidates = Arrays.asList(entry(1));
        Set<Hash> selected = new HashSet<>();
        selected.add(hash(1));
        ProfileOrganizer.mergeSelectedFingerprints(ipSet, candidateKeys, candidates, selected);
        assertEquals(3, ipSet.size());
        assertTrue(ipSet.contains(".pre"));
        assertTrue(ipSet.contains(".aa"));
    }

    /** A candidate with nothing to fingerprint holds a null entry, not an NPE. */
    @Test
    public void nullFingerprintsAreSkipped() {
        Set<String> ipSet = new HashSet<>();
        List<Set<String>> candidateKeys = Arrays.asList((Set<String>) null, keys(".dd"));
        List<Map.Entry<Hash, PeerProfile>> candidates = Arrays.asList(entry(1), entry(2));
        Set<Hash> selected = new HashSet<>();
        selected.add(hash(1));
        selected.add(hash(2));
        ProfileOrganizer.mergeSelectedFingerprints(ipSet, candidateKeys, candidates, selected);
        assertEquals(1, ipSet.size());
        assertTrue(ipSet.contains(".dd"));
    }

    /**
     * One candidate's fingerprints, as the scan would have claimed them.
     *
     * @param fingerprints the fingerprints to hold, in no particular order
     * @return a mutable set containing each of them
     */
    private static Set<String> keys(String... fingerprints) {
        return new HashSet<>(Arrays.asList(fingerprints));
    }

    /**
     * A candidate entry; the merge reads only the peer hash, never the profile.
     *
     * @param b selects the peer's hash, see {@link #hash(int)}
     * @return an entry carrying that peer and a null profile
     */
    private static Map.Entry<Hash, PeerProfile> entry(int b) {
        return new AbstractMap.SimpleEntry<Hash, PeerProfile>(hash(b), null);
    }

    // ---- key format ----

    /** One key space: the fingerprints must equal what {@link MaskedIPSet} builds. */
    @Test
    public void keysMatchMaskedIPSet() {
        RouterInfo info = ri(30000, "eeps", "1.2.3.4", "9.9.9.9");
        byte[] commIP = new byte[] {7, 7, 7, 7};
        RouterContext c = ctx(info, commIP);
        Hash peer = hash(1);
        assertEquals(new MaskedIPSet(c, peer, info, MASK), ProfileOrganizer.fingerprints(c, peer, MASK));
    }
}

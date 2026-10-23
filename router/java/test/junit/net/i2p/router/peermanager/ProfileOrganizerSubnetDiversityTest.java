package net.i2p.router.peermanager;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.HashSet;
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
 * <p>The direction-pinning pair matters on its own: a peer on a fresh subnet
 * must pass and a peer sharing a fingerprint with an accepted peer must fail.
 * Asserting only the second would also pass under the inverted polarity.
 *
 * @see ProfileOrganizerSelectionCostTest
 * @since 0.9.71+
 */
public class ProfileOrganizerSubnetDiversityTest {

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

    // ---- order independence ----

    /** A rejected peer claims its keys too, so evaluation order cannot change the outcome. */
    @Test
    public void rejectedPeerStillClaimsItsKeys() {
        Set<String> claimed = new HashSet<>();
        Set<String> first = new HashSet<>();
        first.add(".aa");
        first.add("p1");
        Set<String> second = new HashSet<>();
        second.add(".aa");
        second.add(".bb");
        assertTrue(ProfileOrganizer.tryClaimKeys(claimed, first));
        assertFalse(ProfileOrganizer.tryClaimKeys(claimed, second));
        assertEquals(3, claimed.size());
        assertTrue(claimed.contains(".bb"));
    }

    /**
     * The same three peers evaluated the other way round reject the other peer,
     * never both of them — exactly the property the unconditional claim buys.
     */
    @Test
    public void gateOutcomeDependsOnlyOnTheSharedKey() {
        Set<String> claimed = new HashSet<>();
        Set<String> a = new HashSet<>();
        a.add(".aa");
        Set<String> b = new HashSet<>();
        b.add(".aa");
        b.add(".bb");
        Set<String> c = new HashSet<>();
        c.add(".cc");
        assertTrue(ProfileOrganizer.tryClaimKeys(claimed, b));
        assertFalse(ProfileOrganizer.tryClaimKeys(claimed, a));
        assertTrue(ProfileOrganizer.tryClaimKeys(claimed, c));
    }

    /** Nothing known about the peer means nothing to collide with. */
    @Test
    public void noFingerprintsPasses() {
        assertTrue(ProfileOrganizer.tryClaimKeys(new HashSet<>(), new HashSet<>()));
        assertTrue(ProfileOrganizer.notRestricted(ctx(new RouterInfo(), null), hash(1), new MaskedIPSet(), MASK));
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

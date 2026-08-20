package net.i2p.router.peermanager;

import static org.junit.Assert.*;

import java.lang.reflect.Field;
import java.util.HashSet;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import net.i2p.crypto.EncType;
import net.i2p.crypto.SigType;
import net.i2p.data.Certificate;
import net.i2p.data.Hash;
import net.i2p.data.PublicKey;
import net.i2p.data.SigningPublicKey;
import net.i2p.data.router.RouterAddress;
import net.i2p.data.router.RouterIdentity;
import net.i2p.data.router.RouterInfo;
import net.i2p.router.RouterContext;
import net.i2p.router.RouterTestHelper;
import net.i2p.util.OrderedProperties;

import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 *  Tests for the tier-promotion skip gate in ProfileOrganizer.
 *  Peers are seeded through the dummy netdb so the profiling-exclusion
 *  and netDb-lookup checks behave like a live router.
 *
 *  @since 0.9.71+
 */
public class ProfileOrganizerPromotionTest {

    private static RouterContext _ctx;
    private static ProfileOrganizer _org;

    @BeforeClass
    public static void setUp() throws Exception {
        _ctx = RouterTestHelper.getContext();
        Assume.assumeTrue("No RouterContext available", _ctx != null);
        _org = new ProfileOrganizer(_ctx);
    }

    private static RouterInfo routerInfo(int seed, String caps) {
        RouterInfo info = new RouterInfo();
        RouterIdentity ident = new RouterIdentity();
        byte[] pk = new byte[EncType.ECIES_X25519.getPubkeyLen()];
        pk[pk.length - 1] = (byte) seed;
        ident.setCertificate(new Certificate(Certificate.CERTIFICATE_TYPE_NULL, null));
        ident.setPublicKey(new PublicKey(EncType.ECIES_X25519, pk));
        byte[] spk = new byte[SigType.EdDSA_SHA512_Ed25519.getPubkeyLen()];
        spk[0] = (byte) seed;
        spk[1] = (byte) (seed >> 8);
        ident.setSigningPublicKey(new SigningPublicKey(SigType.EdDSA_SHA512_Ed25519, spk));
        info.setIdentity(ident);
        // floodfill + capability tier, modern version
        Properties opts = new Properties();
        opts.setProperty("caps", caps);
        opts.setProperty("router.version", "0.9.70");
        info.setOptions(opts);
        // a reachable NTCP address so the peer passes the usability filter
        OrderedProperties addrProps = new OrderedProperties();
        addrProps.setProperty("host", "1.2.3.4");
        addrProps.setProperty("port", "1024");
        Set<RouterAddress> addresses = new HashSet<>(1);
        addresses.add(new RouterAddress("NTCP", addrProps, 10));
        info.setAddresses(addresses);
        return info;
    }

    /** Register a router info in the dummy netdb so profiling-exclusion passes. */
    private static void seedNetDb(RouterInfo ri) throws Exception {
        Class<?> c = _ctx.netDb().getClass();
        Field routers = null;
        while (c != null && routers == null) {
            try {
                routers = c.getDeclaredField("_routers");
            } catch (NoSuchFieldException nsf) {
                c = c.getSuperclass();
            }
        }
        if (routers == null) {
            throw new NoSuchFieldException("_routers");
        }
        routers.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<Hash, RouterInfo> m = (Map<Hash, RouterInfo>) routers.get(_ctx.netDb());
        m.put(ri.getIdentity().getHash(), ri);
    }

    /** Build a profile for a peer seeded in the netdb; returns the profile. */
    private static PeerProfile seedProfile(int seed, String caps) throws Exception {
        RouterInfo ri = routerInfo(seed, caps);
        seedNetDb(ri);
        return new PeerProfile(_ctx, ri.getIdentity().getHash());
    }

    private static boolean skips(PeerProfile profile) {
        return _org.skipsPromotion(profile, profile.getPeer(), 0.5);
    }

    @Test
    public void testCleanPeerNotSkipped() throws Exception {
        PeerProfile profile = seedProfile(1, "fO");
        assertFalse(skips(profile));
    }

    @Test
    public void testPeerNotInNetDbSkipped() throws Exception {
        // Build a peer that is never seeded in the netdb — isSelectable fails
        RouterInfo ri = routerInfo(2, "fO");
        PeerProfile profile = new PeerProfile(_ctx, ri.getIdentity().getHash());
        assertTrue(_org.skipsPromotion(profile, profile.getPeer(), 0.5));
    }

    @Test
    public void testHighLatencyPenaltySkipped() throws Exception {
        PeerProfile profile = seedProfile(3, "fO");
        profile.setCapacityBonus(-30);
        assertTrue(skips(profile));
    }

    @Test
    public void testCongestedPeerSkipped() throws Exception {
        PeerProfile profile = seedProfile(4, "fOD");
        assertTrue(skips(profile));
    }

    @Test
    public void testLossyPeerSkipped() throws Exception {
        PeerProfile profile = seedProfile(5, "fO");
        profile.setLossySince(_ctx.clock().now());
        assertTrue(skips(profile));
    }

    @Test
    public void testLowTunnelAcceptanceSkipped() throws Exception {
        PeerProfile profile = seedProfile(6, "fO");
        TunnelHistory th = profile.getTunnelHistory();
        for (int i = 0; i < 30; i++) {
            th.incrementRejected(TunnelHistory.TUNNEL_REJECT_CRIT);
        }
        assertTrue(skips(profile));
    }
}
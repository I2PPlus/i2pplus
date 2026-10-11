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
 * Tests for the tier-promotion skip gate in ProfileOrganizer.
 * Peers are seeded through the dummy netdb so the profiling-exclusion
 * and netDb-lookup checks behave like a live router.
 *
 * @since 0.9.71+
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

    /**
     * Give a profile a clean acceptance record, so the acceptance gate is not the
     * reason a peer built to fail some *other* gate reports.
     */
    private static void acceptAll(PeerProfile profile) {
        TunnelHistory th = profile.getTunnelHistory();
        for (int i = 0; i < 50; i++) { th.incrementAgreedTo(); }
    }

    @Test
    public void testCleanPeerNotSkipped() throws Exception {
        PeerProfile profile = seedProfile(1, "fO");
        // Peers must have tunnel test data to pass isLowTunnelAcceptance
        TunnelHistory th = profile.getTunnelHistory();
        for (int i = 0; i < 50; i++) {
            th.incrementAgreedTo();
        }
        assertFalse(skips(profile));
    }

    @Test
    public void testZeroTunnelHistoryPeerSkipped() throws Exception {
        // Peers with no tunnel test history AND no recent activity are excluded
        PeerProfile profile = seedProfile(7, "fO");
        TunnelHistory th = profile.getTunnelHistory();
        assertEquals(0, th.getLifetimeAgreedTo() + th.getLifetimeRejected());
        assertTrue(skips(profile));
    }

    @Test
    public void testZeroHistoryWithRecentActivityNotSkipped() throws Exception {
        // Zero tunnel history but recently heard from — allow into tiers
        PeerProfile profile = seedProfile(11, "fO");
        TunnelHistory th = profile.getTunnelHistory();
        assertEquals(0, th.getLifetimeAgreedTo() + th.getLifetimeRejected());
        profile.setLastHeardFrom(_ctx.clock().now());
        assertFalse(skips(profile));
    }

    @Test
    public void testLowSampleTunnelHistoryPeerSkipped() throws Exception {
        // Peers with < 50 tunnel requests and low acceptance are excluded
        PeerProfile profile = seedProfile(8, "fO");
        TunnelHistory th = profile.getTunnelHistory();
        for (int i = 0; i < 5; i++) {
            th.incrementRejected(TunnelHistory.TUNNEL_REJECT_CRIT);
        }
        assertTrue(skips(profile));
    }

    @Test
    public void testHighSamplePeerWithGoodAcceptanceNotSkipped() throws Exception {
        // Peers with >= 50 tunnel requests and good acceptance pass
        PeerProfile profile = seedProfile(9, "fO");
        TunnelHistory th = profile.getTunnelHistory();
        for (int i = 0; i < 50; i++) {
            th.incrementAgreedTo();
        }
        assertFalse(skips(profile));
    }

    @Test
    public void testHighSamplePeerWithPoorAcceptanceSkipped() throws Exception {
        // Peers with >= 50 tunnel requests and poor acceptance (< 40%) are excluded
        PeerProfile profile = seedProfile(10, "fO");
        TunnelHistory th = profile.getTunnelHistory();
        for (int i = 0; i < 15; i++) {
            th.incrementAgreedTo();
        }
        for (int i = 0; i < 35; i++) {
            th.incrementRejected(TunnelHistory.TUNNEL_REJECT_CRIT);
        }
        assertTrue(skips(profile));
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

    // ---- the reason must name the gate that actually fired ----

    /**
     * Every gate that skips promotion must be nameable by the reason.
     *
     * <p>Promotion previously logged two recomputed gates, so a peer held back by
     * congestion, loss probation or low tunnel acceptance printed
     * {@code notPeerSelectable=false excessiveFailures=false} — reading "the gates
     * passed" while the peer was in fact skipped. The three below are exactly the
     * cases that were silently misattributed, plus the one that was reported from
     * the wrong object.
     */
    @Test
    public void everyGateNamesItsOwnReason() throws Exception {
        long now = _ctx.clock().now();

        // A peer can fail several gates at once. The reason names the first in a
        // fixed order, so each case below is built to fail exactly one — the
        // tunnel history keeps the acceptance gate out of the way.

        // Congestion capability — was reported as "notPeerSelectable=false".
        PeerProfile congested = seedProfile(4, "fOD");
        acceptAll(congested);
        assertEquals("congested",
                     _org.promotionSkipReason(congested, congested.getPeer(), 0.5, now));
        assertTrue(skips(congested));

        // Loss probation — same misattribution.
        PeerProfile lossy = seedProfile(5, "fO");
        acceptAll(lossy);
        lossy.setLossySince(now);
        assertEquals("lossProbation",
                     _org.promotionSkipReason(lossy, lossy.getPeer(), 0.5, now));
        assertTrue(skips(lossy));

        // Low tunnel acceptance — same misattribution.
        PeerProfile lowAcc = seedProfile(6, "fO");
        TunnelHistory ath = lowAcc.getTunnelHistory();
        for (int i = 0; i < 30; i++) {
            ath.incrementRejected(TunnelHistory.TUNNEL_REJECT_CRIT);
        }
        assertEquals("lowTunnelAcceptance",
                     _org.promotionSkipReason(lowAcc, lowAcc.getPeer(), 0.5, now));
        assertTrue(skips(lowAcc));

        // Excessive lifetime failures — reported, but from a re-probe of the map
        // rather than the profile the caller already held.
        PeerProfile failed = seedProfile(11, "fO");
        TunnelHistory fth = failed.getTunnelHistory();
        for (int i = 0; i < 5; i++) { fth.incrementAgreedTo(); }
        for (int i = 0; i < 51; i++) { fth.incrementFailed(50); }
        assertEquals("excessiveFailures",
                     _org.promotionSkipReason(failed, failed.getPeer(), 0.5, now));
        assertTrue(skips(failed));
    }

    /**
     * A peer that clears every gate has no reason to report, and
     * {@link #skipsPromotion} agrees with the reason: it is null exactly when the
     * boolean is false, so the two cannot drift.
     */
    @Test
    public void reasonIsNullExactlyWhenNotSkipped() throws Exception {
        long now = _ctx.clock().now();
        PeerProfile clean = seedProfile(1, "fO");
        TunnelHistory th = clean.getTunnelHistory();
        for (int i = 0; i < 50; i++) { th.incrementAgreedTo(); }

        assertNull(_org.promotionSkipReason(clean, clean.getPeer(), 0.5, now));
        assertFalse("the reason and the boolean must agree", skips(clean));

        PeerProfile bad = seedProfile(4, "fOD");
        assertNotNull(_org.promotionSkipReason(bad, bad.getPeer(), 0.5, now));
        assertTrue("the reason and the boolean must agree", skips(bad));
    }

    /**
     * {@link #skipsPromotion} and {@link #promotionSkipReason} are the same
     * decision, not two implementations. Swept over the profiles the other tests
     * build so a divergence at any combination fails here.
     */
    @Test
    public void skipsPromotionIsTheReasonDecision() throws Exception {
        long now = _ctx.clock().now();
        PeerProfile[] profiles = {
            seedProfile(1, "fO"), seedProfile(4, "fOD"), seedProfile(5, "fO"),
            seedProfile(6, "fO"),
        };
        for (int i = 0; i < profiles.length; i++) {
            for (double bs : new double[] {0.1, 0.5, 0.9}) {
                String reason = _org.promotionSkipReason(profiles[i], profiles[i].getPeer(), bs, now);
                boolean skipped = _org.skipsPromotion(profiles[i], profiles[i].getPeer(), bs);
                assertEquals("profile " + i + " buildSuccess " + bs +
                             ": reason and boolean disagree",
                             reason != null, skipped);
            }
        }
    }
}

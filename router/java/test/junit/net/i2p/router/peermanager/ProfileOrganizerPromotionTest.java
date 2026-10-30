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

    /**
     * Register a router info in the dummy netdb so profiling-exclusion passes.
     *
     * @param ri the router info to register, keyed by its identity hash
     * @throws Exception if the netdb's {@code _routers} map cannot be reached
     *                   reflectively
     */
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

    /**
     * Build a profile for a peer seeded in the netdb; returns the profile.
     *
     * @param seed written into the identity's key material so each call yields a
     *             distinct peer
     * @param caps capability string for the router info, e.g. {@code "fOD"} marks
     *             a moderately congested floodfill
     * @return a profile for the newly seeded peer
     * @throws Exception if the peer's router info cannot be seeded into the
     *                   dummy netdb
     */
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
     *
     * @param profile the profile whose tunnel history is given 50 agreements
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
    public void testZeroTunnelHistoryPeerIsNotASharedGateSkip() throws Exception {
        // No history and no recent activity is still a reason not to seat a peer,
        // but it is now the tier branches' business rather than the shared gate's.
        // The shared gate answers "is this peer disqualified outright", and a peer
        // nobody has spoken to yet is not disqualified, it is unknown.
        PeerProfile profile = seedProfile(7, "fO");
        TunnelHistory th = profile.getTunnelHistory();
        assertEquals(0, th.getLifetimeAgreedTo() + th.getLifetimeRejected());
        assertFalse(skips(profile));
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
    public void testLowSampleTunnelHistoryPeerSkipsFastButNotHighCap() throws Exception {
        // The two tiers now apply different sample floors, which is the change that
        // lets the high-capacity reservoir fill: 5 rejections is a 0% ratio, but on
        // five samples it is not evidence of anything.
        PeerProfile profile = seedProfile(8, "fO");
        TunnelHistory th = profile.getTunnelHistory();
        for (int i = 0; i < 5; i++) {
            th.incrementRejected(TunnelHistory.TUNNEL_REJECT_CRIT);
        }
        assertTrue("the fast tier refuses a peer it has no history for",
                   ProfileOrganizer.excludedOnAcceptance(0, 5, 1, false));
        assertFalse("high capacity does not judge five samples",
                    ProfileOrganizer.excludedOnAcceptance(0, 5, 50, false));
        assertFalse("and it is the shared skip gate, which is why this peer is not skipped",
                    skips(profile));
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
        assertFalse("acceptance is no longer part of the shared promotion gate",
                    skips(profile));
        assertTrue("but 30% over 50 samples still excludes on both tiers",
                   ProfileOrganizer.excludedOnAcceptance(15, 35, 50, false));
    }

    @Test
    public void testPeerNotInNetDbIsNotASharedGateSkip() throws Exception {
        // Was asserting that an unseeded peer is skipped, and passing — but not for
        // the reason named. promotionSkipReason tests passesBasicGates (banlist,
        // ghost, first-hop cooldown), never netdb presence; the peer was skipping
        // only because the acceptance gate saw no history and no activity. With
        // acceptance moved to the tier branches this is no longer a shared-gate skip,
        // and netdb presence is handled where it always was: by the promotion scan
        // resolving the RouterInfo before it can seat the peer.
        RouterInfo ri = routerInfo(2, "fO");
        PeerProfile profile = new PeerProfile(_ctx, ri.getIdentity().getHash());
        assertFalse(_org.skipsPromotion(profile, profile.getPeer(), 0.5));
    }

    @Test
    public void testHighLatencyPenaltyIsNotAPromotionGate() throws Exception {
        // A capacity penalty does not gate promotion. The fast tier's own admission
        // paths consult speed, latency and throughput directly, so the penalty is a
        // display and selection-order signal rather than an exclusion.
        PeerProfile profile = seedProfile(3, "fO");
        profile.setCapacityBonus(-30);
        assertFalse(skips(profile));
    }

    @Test
    public void testCongestedPeerSkipped() throws Exception {
        PeerProfile profile = seedProfile(4, "fOD");
        assertTrue(skips(profile));
    }

    @Test
    public void testLossyPeerSkipped() throws Exception {
        PeerProfile profile = seedProfile(5, "fO");
        // Probation with the evidence that caused it still on file. A demotion
        // with no transport report behind it is readmitted at once - demanding a
        // report from a peer we send no traffic to is a closed loop - so the
        // report is what makes this peer a probationer.
        long now = _ctx.clock().now();
        profile.setLossRatio(0.5f, now);
        profile.setLossySince(now);
        assertTrue(skips(profile));
    }

    @Test
    public void testLowTunnelAcceptanceExcludedOnBothTiers() throws Exception {
        PeerProfile profile = seedProfile(6, "fO");
        TunnelHistory th = profile.getTunnelHistory();
        for (int i = 0; i < 30; i++) {
            th.incrementRejected(TunnelHistory.TUNNEL_REJECT_CRIT);
        }
        assertTrue("30 rejections on 30 samples is no evidence",
                   ProfileOrganizer.excludedOnAcceptance(0, 30, 1, false));
        assertFalse("high capacity will not judge 30 samples either",
                    ProfileOrganizer.excludedOnAcceptance(0, 30, 50, false));
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
     *
     * @throws Exception if a peer cannot be seeded into the dummy netdb
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

        // Loss probation — same misattribution. The report is what keeps the peer
        // in probation: a demotion with no report behind it is readmitted.
        PeerProfile lossy = seedProfile(5, "fO");
        acceptAll(lossy);
        lossy.setLossRatio(0.5f, now);
        lossy.setLossySince(now);
        assertEquals("lossProbation",
                     _org.promotionSkipReason(lossy, lossy.getPeer(), 0.5, now));
        assertTrue(skips(lossy));

        // Low tunnel acceptance no longer appears here: it left the shared gate
        // because the two tiers need different sample floors, and a gate with one
        // answer can only ever serve one of them.

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
     * {@link #skips} agrees with the reason: it is null exactly when the
     * boolean is false, so the two cannot drift.
     *
     * @throws Exception if a peer cannot be seeded into the dummy netdb
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
     * {@link #skips} and {@link ProfileOrganizer#promotionSkipReason} are the same
     * decision, not two implementations. Swept over the profiles the other tests
     * build so a divergence at any combination fails here.
     *
     * @throws Exception if a peer cannot be seeded into the dummy netdb
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

    /**
     * The tally renders reasons as prose labels with the first letter
     * capitalised; every reason the gate can return must have a label, and
     * an unmapped reason must pass through unchanged rather than vanish.
     */
    @Test
    public void promotionSkipLabelsAreProse() {
        assertEquals("Loss probation", ProfileOrganizer.promotionSkipLabel("lossProbation"));
        assertEquals("Not peer selectable", ProfileOrganizer.promotionSkipLabel("notPeerSelectable"));
        assertEquals("Excessive failures", ProfileOrganizer.promotionSkipLabel("excessiveFailures"));
        assertEquals("Strict country", ProfileOrganizer.promotionSkipLabel("strictCountry"));
        assertEquals("Congested", ProfileOrganizer.promotionSkipLabel("congested"));
        assertEquals("Low tunnel acceptance (fast)",
                     ProfileOrganizer.promotionSkipLabel("lowTunnelAcceptance.fast"));
        assertEquals("Low tunnel acceptance (high cap)",
                     ProfileOrganizer.promotionSkipLabel("lowTunnelAcceptance.highCap"));
        assertEquals("someNewReason", ProfileOrganizer.promotionSkipLabel("someNewReason"));
    }

    /**
     * A promotion is logged as the tier transition that happened, not as a
     * pair of booleans: unchanged membership reports no event. A peer that
     * gained nothing returns null so the caller logs nothing.
     */
    @Test
    public void promotionTransitionNamesOnlyGainedTiers() {
        assertEquals("none -> fast", ProfileOrganizer.promotionTransition(false, false, true, false));
        assertEquals("none -> highCap", ProfileOrganizer.promotionTransition(false, false, false, true));
        assertEquals("highCap -> fast", ProfileOrganizer.promotionTransition(false, true, true, true));
        assertEquals("fast -> highCap", ProfileOrganizer.promotionTransition(true, false, true, true));
        assertEquals("none -> fast + highCap", ProfileOrganizer.promotionTransition(false, false, true, true));
        assertNull(ProfileOrganizer.promotionTransition(false, true, false, true));
        assertNull(ProfileOrganizer.promotionTransition(false, false, false, false));
    }

    /**
     * Default values (zero bonus, live profile) explain nothing and must not
     * be printed; only non-default evidence earns the continuation line.
     */
    @Test
    public void promotionEvidenceOmitsDefaults() {
        assertNull(ProfileOrganizer.promotionEvidence(0, 0, true));
        assertEquals("\n* Capacity bonus: 3", ProfileOrganizer.promotionEvidence(3, 0, true));
        assertEquals("\n* Raw: -2", ProfileOrganizer.promotionEvidence(0, -2, true));
        assertEquals("\n* Capacity bonus: 3 * Raw: 5",
                     ProfileOrganizer.promotionEvidence(3, 5, true));
        String stale = ProfileOrganizer.promotionEvidence(0, 0, false);
        assertTrue("stale copy reported: " + stale,
                   stale != null && stale.contains("No live profile"));
    }
}

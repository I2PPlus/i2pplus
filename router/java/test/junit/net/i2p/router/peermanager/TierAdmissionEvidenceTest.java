package net.i2p.router.peermanager;

import org.junit.Test;

import static org.junit.Assert.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests the sample floor the fast tier needs before it will admit a peer. The
 * fast tier is the one client-tunnel selection draws from, and a peer with no
 * build history is scored on numbers it declared itself: the bandwidth tier it
 * wrote into its own RouterInfo, and the speed value derived from that tier
 * when no throughput has been measured. So a freshly minted identity with a
 * session was a fast-tier member at the first reorganize, with a capacity value
 * of 0. The floor here is the same five-request count the acceptance gate above
 * it already uses, so both ask for the same evidence.
 * <p>
 * High capacity deliberately keeps no such floor: it is the reservoir the fast
 * tier is seeded from and the pool exploratory tunnels are built from, and it
 * is how a peer is offered builds often enough to acquire the history this gate
 * wants. The fifty-request floor below is what acceptance and eviction of high
 * capacity use, not its admission.
 * <p>
 * The other half of the relationship is depth rather than evidence: the fast
 * tier's own ceiling is scaled at 1.5x of reachable peers and the reservoir's at
 * 1x, so the two cross at ordinary router sizes, and every site that sizes the
 * fast tier sends it through {@link ProfileOrganizer#fastFillTargetFor}.
 *
 * No router context is needed: the helper is static.
 *
 * @since 0.9.72+
 */
public class TierAdmissionEvidenceTest {

    /**
     * A profile whose lifetime build counts are the given ones.
     *
     * @param agreed builds the peer agreed to
     * @param rejected builds the peer rejected
     * @return a profile reporting those two lifetime counts
     */
    private static PeerProfile profileWithHistory(long agreed, long rejected) {
        TunnelHistory th = mock(TunnelHistory.class);
        when(th.getLifetimeAgreedTo()).thenReturn(agreed);
        when(th.getLifetimeRejected()).thenReturn(rejected);
        PeerProfile profile = mock(PeerProfile.class);
        when(profile.getTunnelHistory()).thenReturn(th);
        return profile;
    }

    @Test
    public void noProfileOrNoHistoryHasNoSamples() {
        assertFalse(ProfileOrganizer.hasBuildHistory(null, 1));
        PeerProfile profile = mock(PeerProfile.class);
        when(profile.getTunnelHistory()).thenReturn(null);
        assertFalse(ProfileOrganizer.hasBuildHistory(profile, 1));
        assertFalse(ProfileOrganizer.hasBuildHistory(profileWithHistory(0, 0), 1));
    }

    @Test
    public void fastFloorIsFiveSamples() {
        // the floor the fast tier shares with its acceptance gate
        assertEquals(5, ProfileOrganizer.FAST_ACCEPTANCE_SAMPLES);
        assertFalse(ProfileOrganizer.hasBuildHistory(profileWithHistory(4, 0),
                                                     ProfileOrganizer.FAST_ACCEPTANCE_SAMPLES));
        assertTrue(ProfileOrganizer.hasBuildHistory(profileWithHistory(5, 0),
                                                    ProfileOrganizer.FAST_ACCEPTANCE_SAMPLES));
        // agreed and rejected are one sample pool, not two separate floors
        assertTrue(ProfileOrganizer.hasBuildHistory(profileWithHistory(3, 2),
                                                    ProfileOrganizer.FAST_ACCEPTANCE_SAMPLES));
    }

    @Test
    public void aFreshIdentityHasNoSamplesAtAll() {
        // the finding's shape: a minted identity with a session and an X-tier
        // RouterInfo, and nothing this router ever measured
        PeerProfile fresh = profileWithHistory(0, 0);
        assertFalse(ProfileOrganizer.hasBuildHistory(fresh, ProfileOrganizer.FAST_ACCEPTANCE_SAMPLES));
        assertFalse(ProfileOrganizer.hasBuildHistory(fresh, ProfileOrganizer.MIN_TUNNEL_REQUESTS));
    }

    @Test
    public void highCapacityJudgesAcceptanceAndEvictionAtFifty() {
        // admission by free slots stays permissive there; this is the floor its
        // acceptance gate and its eviction pass use
        assertEquals(50, ProfileOrganizer.MIN_TUNNEL_REQUESTS);
        assertFalse(ProfileOrganizer.hasBuildHistory(profileWithHistory(49, 0),
                                                     ProfileOrganizer.MIN_TUNNEL_REQUESTS));
        assertTrue(ProfileOrganizer.hasBuildHistory(profileWithHistory(49, 1),
                                                    ProfileOrganizer.MIN_TUNNEL_REQUESTS));
        assertTrue(ProfileOrganizer.hasBuildHistory(profileWithHistory(50, 0),
                                                    ProfileOrganizer.MIN_TUNNEL_REQUESTS));
    }

    @Test
    public void reservoirIsDeeperThanTheTierItSeeds() {
        // The fast tier's own ceiling does not keep it below the reservoir: the
        // active-peer caps scale fast at 1.5x of reachable peers and high capacity
        // at 1x, so the ceilings cross at roughly 667 reachable peers. Every site
        // that sizes the fast tier therefore passes the depth through
        // fastFillTargetFor, and this pins both halves of that.
        int active = 1221;
        assertTrue("the raw ceilings are the wrong way round at this size",
                   ProfileOrganizer.fastActiveCap(active, 1000) >
                   ProfileOrganizer.highCapActiveCap(active, 1000));
        int highCapDepth = 479;
        assertEquals(478, ProfileOrganizer.fastFillTargetFor(
                                   ProfileOrganizer.fastActiveCap(active, 1000), highCapDepth));
        assertEquals(480, ProfileOrganizer.highCapFillTargetFor(100, 479));
        // and a reservoir deeper than the tier leaves the tier's own target alone
        assertEquals(1000, ProfileOrganizer.fastFillTargetFor(1000, 1500));
    }
}

package net.i2p.router.peermanager;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import net.i2p.data.router.RouterInfo;

import org.junit.Test;

/**
 *  Tests for the pure decision logic behind the promotion hold on a stale
 *  RouterInfo: whether a RouterInfo is too old to promote on, and when a tier is
 *  too short to afford to leave the peer out.
 *
 *  <p>Both predicates are pure so the promotion path's behaviour can be pinned
 *  without a router: the threshold has to be exactly where the RouterInfo age
 *  comparison says it is, or a peer is either promoted while its entry is still
 *  authoritative or held back on an entry that should have been replaced.
 *
 *  @since 0.9.72
 */
public class ProfileOrganizerRefreshDecisionTest {

    private static final long NOW = 2_000_000_000L;
    private static final long MINUTE = 60 * 1000L;
    private static final long HOUR = 60 * MINUTE;
    /** The shipped threshold: one republish interval. */
    private static final long REFRESH_AGE = ProfileOrganizer.DEFAULT_ROUTERINFO_REFRESH_AGE_MS;

    private RouterInfo published(long when) {
        RouterInfo info = mock(RouterInfo.class);
        when(info.getPublished()).thenReturn(Long.valueOf(when));
        return info;
    }

    /** A profile that has shown no life at all. */
    private PeerProfile silent() {
        PeerProfile profile = mock(PeerProfile.class);
        when(profile.getLastSendSuccessful()).thenReturn(Long.valueOf(0L));
        when(profile.getLastHeardFrom()).thenReturn(Long.valueOf(0L));
        when(profile.getLastHeardAbout()).thenReturn(Long.valueOf(0L));
        return profile;
    }

    private PeerProfile heardFrom(long when) {
        PeerProfile profile = silent();
        when(profile.getLastHeardFrom()).thenReturn(Long.valueOf(when));
        return profile;
    }

    // ---- needsRouterInfoRefresh ----

    /** An entry exactly at the threshold is not older than it, so it stands. */
    @Test
    public void exactlyAtThresholdIsNotStale() {
        assertFalse(ProfileOrganizer.needsRouterInfoRefresh(
                published(NOW - REFRESH_AGE), silent(), NOW, REFRESH_AGE));
    }

    /** One millisecond past the threshold is stale. */
    @Test
    public void oneMillisecondPastThresholdIsStale() {
        assertTrue(ProfileOrganizer.needsRouterInfoRefresh(
                published(NOW - REFRESH_AGE - 1), silent(), NOW, REFRESH_AGE));
    }

    /** One millisecond inside the threshold is fresh, in either direction. */
    @Test
    public void oneMillisecondInsideThresholdIsFresh() {
        assertFalse(ProfileOrganizer.needsRouterInfoRefresh(
                published(NOW - REFRESH_AGE + 1), silent(), NOW, REFRESH_AGE));
    }

    /** The default threshold is one hour, not the two-hour selection bar. */
    @Test
    public void defaultThresholdIsOneHour() {
        assertTrue(REFRESH_AGE == HOUR);
        assertFalse(ProfileOrganizer.needsRouterInfoRefresh(
                published(NOW - HOUR + 1), silent(), NOW, REFRESH_AGE));
        assertTrue(ProfileOrganizer.needsRouterInfoRefresh(
                published(NOW - HOUR - 1), silent(), NOW, REFRESH_AGE));
    }

    /** A long-expired entry is stale. */
    @Test
    public void ancientEntryIsStale() {
        assertTrue(ProfileOrganizer.needsRouterInfoRefresh(published(0L), silent(), NOW, REFRESH_AGE));
    }

    /** A peer heard from inside the proof-of-life window is judged on that, not on age. */
    @Test
    public void recentProofOfLifeExemptsTheStaleEntry() {
        PeerProfile profile = heardFrom(NOW - HOUR + 1);
        assertTrue(ProfileOrganizer.hasRecentProofOfLife(profile, NOW));
        assertFalse(ProfileOrganizer.needsRouterInfoRefresh(
                published(NOW - 5 * HOUR), profile, NOW, REFRESH_AGE));
    }

    /** Proof of life is open at its own boundary, matching hasRecentProofOfLife. */
    @Test
    public void proofOfLifeExactlyAtItsBoundaryDoesNotExempt() {
        PeerProfile profile = heardFrom(NOW - HOUR);
        assertFalse(ProfileOrganizer.hasRecentProofOfLife(profile, NOW));
        assertTrue(ProfileOrganizer.needsRouterInfoRefresh(
                published(NOW - 5 * HOUR), profile, NOW, REFRESH_AGE));
    }

    /** No profile at all is no evidence of life, so the entry is stale. */
    @Test
    public void nullProfileIsNotProofOfLife() {
        assertTrue(ProfileOrganizer.needsRouterInfoRefresh(
                published(NOW - 5 * HOUR), null, NOW, REFRESH_AGE));
    }

    /** A missing RouterInfo is missing, not stale: the tier gates already refuse it. */
    @Test
    public void noRouterInfoIsNotDeferred() {
        assertFalse(ProfileOrganizer.needsRouterInfoRefresh(null, silent(), NOW, REFRESH_AGE));
    }

    /** A threshold of zero or less disables the mechanism, and is not a "always stale". */
    @Test
    public void nonPositiveThresholdDisablesTheDeferral() {
        assertFalse(ProfileOrganizer.needsRouterInfoRefresh(
                published(0L), silent(), NOW, 0L));
        assertFalse(ProfileOrganizer.needsRouterInfoRefresh(
                published(0L), silent(), NOW, -1L));
    }

    // ---- shouldDeferPromotionOnStaleRouterInfo ----

    /** A tier at its floor has enough alternatives to wait. */
    @Test
    public void tierAtFloorDefers() {
        assertTrue(ProfileOrganizer.shouldDeferPromotionOnStaleRouterInfo(1000, 1000));
    }

    /** One peer below the floor must be promoted on its stale entry rather than starve. */
    @Test
    public void tierBelowFloorPromotesAnyway() {
        assertFalse(ProfileOrganizer.shouldDeferPromotionOnStaleRouterInfo(999, 1000));
    }

    /** A tier far above the floor keeps deferring. */
    @Test
    public void tierWellAboveFloorDefers() {
        assertTrue(ProfileOrganizer.shouldDeferPromotionOnStaleRouterInfo(5000, 1000));
    }

    /** An empty tier is the one case that must never wait. */
    @Test
    public void emptyTierNeverWaits() {
        assertFalse(ProfileOrganizer.shouldDeferPromotionOnStaleRouterInfo(0, 1000));
    }

    /** A configured minimum of zero means no floor, so every promotion can wait. */
    @Test
    public void zeroFloorAlwaysDefers() {
        assertTrue(ProfileOrganizer.shouldDeferPromotionOnStaleRouterInfo(0, 0));
    }
}

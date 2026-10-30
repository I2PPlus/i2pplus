package net.i2p.router.peermanager;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import net.i2p.data.Hash;
import net.i2p.data.router.RouterInfo;

import org.junit.Test;

/**
 * Tests for the pure decision logic behind the promotion hold on a stale
 * RouterInfo: whether a RouterInfo is too old to promote on, and when a tier is
 * too short to afford to leave the peer out.
 *
 * <p>Both predicates are pure so the promotion path's behaviour can be pinned
 * without a router: the threshold has to be exactly where the RouterInfo age
 * comparison says it is, or a peer is either promoted while its entry is still
 * authoritative or held back on an entry that should have been replaced.
 *
 * @since 0.9.72+
 */
public class ProfileOrganizerRefreshDecisionTest {

    private static final long NOW = 2_000_000_000L;
    private static final long MINUTE = 60 * 1000L;
    private static final long HOUR = 60 * MINUTE;
    private static final long DAY = 24 * HOUR;
    /** The shipped threshold: one republish interval. */
    private static final long REFRESH_AGE = ProfileOrganizer.DEFAULT_ROUTERINFO_REFRESH_AGE_MS;

    private RouterInfo published(long when) {
        RouterInfo info = mock(RouterInfo.class);
        when(info.getPublished()).thenReturn(Long.valueOf(when));
        return info;
    }

    /**
     * A profile that has shown no life at all.
     *
     * @return a mock profile whose three proof-of-life timestamps are all 0, meaning never
     */
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

    // ---- isRouterInfoDueForRefresh ----

    /** The refresh-ahead window is a quarter of the bar it protects. */
    @Test
    public void refreshAheadIsShorterThanTheBar() {
        assertTrue("45 min ahead of a 60 min bar",
                   RouterInfoRefresher.DEFAULT_REFRESH_AHEAD_MS < REFRESH_AGE);
        assertEquals(45 * MINUTE, RouterInfoRefresher.DEFAULT_REFRESH_AHEAD_MS);
    }

    /**
     * The measured case. A peer whose RouterInfo was published 50 minutes ago is
     * past the refresh-ahead window and not yet at the bar, so this is the moment
     * at which the peer can still be kept in the tier.
     */
    @Test
    public void peerTenMinutesFromTheBarIsDue() {
        assertTrue(RouterInfoRefresher.isRouterInfoDueForRefresh(NOW - 50 * MINUTE, NOW,
                                                                 RouterInfoRefresher.DEFAULT_REFRESH_AHEAD_MS));
    }

    /** A recently published RouterInfo needs nothing. */
    @Test
    public void freshRouterInfoIsNotDue() {
        assertFalse(RouterInfoRefresher.isRouterInfoDueForRefresh(NOW - 10 * MINUTE, NOW,
                                                                  RouterInfoRefresher.DEFAULT_REFRESH_AHEAD_MS));
    }

    /** Exactly at the window is due: the comparison is inclusive. */
    @Test
    public void exactlyAtTheWindowIsDue() {
        assertTrue(RouterInfoRefresher.isRouterInfoDueForRefresh(NOW - 45 * MINUTE, NOW, 45 * MINUTE));
        assertFalse("one ms younger is not",
                    RouterInfoRefresher.isRouterInfoDueForRefresh(NOW - 45 * MINUTE + 1, NOW, 45 * MINUTE));
    }

    /**
     * No publish date is the stalest case, not the freshest. A RouterInfo that
     * cannot say when it was published cannot claim to be new.
     */
    @Test
    public void noPublishDateIsDue() {
        assertTrue(RouterInfoRefresher.isRouterInfoDueForRefresh(0, NOW, 45 * MINUTE));
        assertTrue(RouterInfoRefresher.isRouterInfoDueForRefresh(-1, NOW, 45 * MINUTE));
    }

    /**
     * The kill switch: a window of zero or less refreshes nothing, which is the
     * operator's off switch for the whole scheduler.
     */
    @Test
    public void disabledWindowRefreshesNothing() {
        assertFalse(RouterInfoRefresher.isRouterInfoDueForRefresh(NOW - 400 * DAY, NOW, 0));
        assertFalse(RouterInfoRefresher.isRouterInfoDueForRefresh(0, NOW, -1));
    }

    // ---- refreshCooldownElapsed ----

    /** Never issued is elapsed. */
    @Test
    public void neverIssuedIsElapsed() {
        assertTrue(RouterInfoRefresher.refreshCooldownElapsed(0, NOW, 40 * MINUTE));
    }

    /** Inside the cooldown a peer is not asked again. */
    @Test
    public void insideCooldownIsRefused() {
        assertFalse(RouterInfoRefresher.refreshCooldownElapsed(NOW - 10 * MINUTE, NOW, 40 * MINUTE));
        assertTrue("the cooldown is inclusive: at exactly 40 min it has elapsed",
                   RouterInfoRefresher.refreshCooldownElapsed(NOW - 40 * MINUTE, NOW, 40 * MINUTE));
        assertFalse("one ms short of it has not",
                    RouterInfoRefresher.refreshCooldownElapsed(NOW - 40 * MINUTE + 1, NOW, 40 * MINUTE));
    }

    /**
     * Past the cooldown it is asked again. The cooldown is shorter than the
     * refresh-ahead window times two, so a peer that keeps answering keeps its
     * RouterInfo fresh, and one that never answers is asked at most three times
     * per staleness interval rather than every reorganize.
     */
    @Test
    public void pastCooldownIsElapsed() {
        assertTrue(RouterInfoRefresher.refreshCooldownElapsed(NOW - 41 * MINUTE, NOW, 40 * MINUTE));
        assertTrue("the cooldown is shorter than the refresh-ahead window's twice",
                   RouterInfoRefresher.REFRESH_PEER_COOLDOWN_MS < 2 * RouterInfoRefresher.DEFAULT_REFRESH_AHEAD_MS);
    }

    // ---- the schedule ----

    private static Hash hash(int b) {
        byte[] data = new byte[Hash.HASH_LENGTH];
        data[0] = (byte) (b >>> 8);
        data[1] = (byte) b;
        return Hash.create(data);
    }

    /** A peer is queued once, and a repeat request inside the cooldown is refused. */
    @Test
    public void scheduleOncePerCooldown() {
        RouterInfoRefresher ref = new RouterInfoRefresher();
        Hash peer = hash(1);
        assertTrue(ref.scheduleRefresh(peer, NOW));
        assertFalse("a repeat request is refused", ref.scheduleRefresh(peer, NOW));
        assertFalse("and a request a minute later", ref.scheduleRefresh(peer, NOW + MINUTE));
        assertTrue("and the cooldown starts at the drain, not the request",
                   ref.scheduleRefresh(hash(2), NOW));
        ref.takeRefreshBatch(NOW + 2 * MINUTE);
        assertFalse("the peer just issued is cooling from the issue time",
                    ref.scheduleRefresh(peer, NOW + 3 * MINUTE));
        assertTrue("a peer past the cooldown from the issue is eligible again",
                   ref.scheduleRefresh(peer, NOW + 2 * MINUTE + RouterInfoRefresher.REFRESH_PEER_COOLDOWN_MS));
    }

    /**
     * The cooling test agrees with the scheduler, and is the cheap pre-check the
     * caller uses to avoid a database lookup for a peer it would refuse.
     */
    @Test
    public void coolingMatchesTheScheduler() {
        RouterInfoRefresher ref = new RouterInfoRefresher();
        Hash peer = hash(3);
        assertFalse(ref.isRefreshCooling(peer, NOW));
        ref.scheduleRefresh(peer, NOW);
        ref.takeRefreshBatch(NOW);
        assertTrue(ref.isRefreshCooling(peer, NOW + MINUTE));
        assertFalse(ref.isRefreshCooling(hash(4), NOW));
    }

    /** A queued peer is not queued twice, so one reorganize cannot stack requests. */
    @Test
    public void queueDedupesBeforeTheDrain() {
        RouterInfoRefresher ref = new RouterInfoRefresher();
        Hash peer = hash(5);
        assertTrue(ref.scheduleRefresh(peer, NOW));
        assertFalse(ref.scheduleRefresh(peer, NOW));
        assertEquals("one entry", 1, ref.getRefreshPending());
    }

    /** The drain hands over at most one batch, so a scan cannot become a flood. */
    @Test
    public void drainIsCappedAtTheBatch() {
        RouterInfoRefresher ref = new RouterInfoRefresher();
        for (int i = 0; i < 1000; i++) {
            ref.scheduleRefresh(hash(i), NOW);
        }
        assertEquals(1000, ref.getRefreshPending());
        java.util.List<Hash> batch = ref.takeRefreshBatch(NOW);
        assertEquals(RouterInfoRefresher.REFRESH_BATCH, batch.size());
        assertEquals(1000 - RouterInfoRefresher.REFRESH_BATCH, ref.getRefreshPending());
    }

    /**
     * The drain is rate-limited as well as capped: batch per interval is the
     * request rate, and it matches the missing-RouterInfo recovery drain the
     * router already runs, so the two together stay near the existing load.
     */
    @Test
    public void drainIsRateLimited() {
        RouterInfoRefresher ref = new RouterInfoRefresher();
        assertTrue("never drained", ref.mayDrainRefreshes(NOW));
        ref.scheduleRefresh(hash(6), NOW);
        ref.takeRefreshBatch(NOW);
        assertFalse(ref.mayDrainRefreshes(NOW));
        assertFalse(ref.mayDrainRefreshes(NOW + RouterInfoRefresher.REFRESH_MIN_INTERVAL_MS - 1));
        assertTrue(ref.mayDrainRefreshes(NOW + RouterInfoRefresher.REFRESH_MIN_INTERVAL_MS));
        assertEquals("the rate matches the recovery drain",
                     RouterInfoRefresher.MISSING_ROUTERINFO_BATCH, RouterInfoRefresher.REFRESH_BATCH);
        assertEquals("at the same interval",
                     RouterInfoRefresher.MISSING_ROUTERINFO_MIN_INTERVAL_MS,
                     RouterInfoRefresher.REFRESH_MIN_INTERVAL_MS);
    }

    /** Past the queue cap requests are refused and counted, not queued. */
    @Test
    public void queueCapRefusesAndCounts() {
        RouterInfoRefresher ref = new RouterInfoRefresher();
        for (int i = 0; i < RouterInfoRefresher.MAX_REFRESH_QUEUE; i++) {
            assertTrue(ref.scheduleRefresh(hash(i), NOW));
        }
        assertEquals(0, ref.getRefreshDropped());
        assertFalse(ref.scheduleRefresh(hash(90_000), NOW));
        assertEquals(1, ref.getRefreshDropped());
        assertEquals(RouterInfoRefresher.MAX_REFRESH_QUEUE, ref.getRefreshPending());
    }

    /** A null peer is refused rather than queued and looked up later. */
    @Test
    public void nullPeerIsRefused() {
        RouterInfoRefresher ref = new RouterInfoRefresher();
        assertFalse(ref.scheduleRefresh(null, NOW));
        assertEquals(0, ref.getRefreshPending());
    }
}

package net.i2p.router.peermanager;

import static org.junit.Assert.*;

import org.junit.Before;
import org.junit.Test;

import net.i2p.router.RouterContext;
import net.i2p.router.RouterTestHelper;
import net.i2p.util.LogManager;

/**
 * Boundary conditions for the three decisions the tier reorganize makes about depth:
 * whether a peer's acceptance counts as evidence, how deep the high-capacity tier may
 * aim, and how long a peer's history is allowed to age.
 *
 * <p>All three are one-line changes to constants and each one moved the tier
 * population by a large factor when it was wrong, which is why the edges are pinned
 * here rather than left to the counts to reveal.
 *
 * <p>{@code excludedOnAcceptance} and {@code highCapActiveCap} are pure statics and
 * need no context. {@code decayLifetimeCounters} needs a {@link TunnelHistory} but no
 * mock clock: the interval guard compares the caller's {@code now} against a
 * construction-time stamp, so a far-future {@code now} is enough to cross it.
 *
 * @since 0.9.71+
 */
public class TierAdmissionFloorTest {

    private RouterContext _ctx;

    @Before
    public void setUp() {
        _ctx = RouterTestHelper.newContext();
        org.junit.Assume.assumeTrue("No RouterContext available", _ctx != null);
    }

    // ---- excludedOnAcceptance: the sample floor ----

    /**
     * The floor is what makes the two tiers differ. Below it there is no evidence,
     * so neither tier may exclude on acceptance; at or above it both may.
     */
    @Test
    public void testNoEvidenceMeansNoExclusionAtAnyFloor() {
        assertEquals("zero requests is no evidence", false,
                     ProfileOrganizer.excludedOnAcceptance(0, 0, 1, false));
        assertEquals("zero requests at the high-cap floor", false,
                     ProfileOrganizer.excludedOnAcceptance(0, 0, 50, false));
    }

    /**
     * The fast tier's floor is five samples, not one. One is what let a peer leave
     * both tiers for a single refusal while high-capacity admission insisted on
     * fifty: the asymmetry the code documented as "admission is permissive,
     * eviction is strict" had become "admission needs fifty samples, eviction
     * needs one", and on a router whose build success was 52% a refusal is the
     * commonest outcome of asking anybody anything.
     */
    @Test
    public void testFastTierFloorIsFiveSamples() {
        assertEquals(5, ProfileOrganizer.FAST_ACCEPTANCE_SAMPLES);
        assertTrue("a single refusal is not evidence about a peer",
                   !ProfileOrganizer.excludedOnAcceptance(0, 1, ProfileOrganizer.FAST_ACCEPTANCE_SAMPLES, false));
        assertTrue("four refusals are not either",
                   !ProfileOrganizer.excludedOnAcceptance(0, 4, ProfileOrganizer.FAST_ACCEPTANCE_SAMPLES, false));
        assertTrue("five refusals and no acceptance is a non-accepter",
                   ProfileOrganizer.excludedOnAcceptance(0, 5, ProfileOrganizer.FAST_ACCEPTANCE_SAMPLES, false));
        assertTrue("six requests with one accepted is under the 0.20 small-sample bar",
                   ProfileOrganizer.excludedOnAcceptance(1, 5, ProfileOrganizer.FAST_ACCEPTANCE_SAMPLES, false));
        assertFalse("five requests with two accepted is not",
                    ProfileOrganizer.excludedOnAcceptance(2, 3, ProfileOrganizer.FAST_ACCEPTANCE_SAMPLES, false));
    }

    /**
     * Eviction judges on the floor the tier's own admission uses, so a peer cannot
     * be admitted under one bar and evicted under a looser one.
     */
    @Test
    public void testEvictionFloorsMatchTheTiers() {
        assertTrue("the fast floor stays below the high-capacity one, whose admission demands it",
                   ProfileOrganizer.FAST_ACCEPTANCE_SAMPLES < ProfileOrganizer.MIN_TUNNEL_REQUESTS);
        assertTrue("and the fast floor is reachable by a peer with a short history",
                   ProfileOrganizer.FAST_ACCEPTANCE_SAMPLES * 2 < ProfileOrganizer.MIN_TUNNEL_REQUESTS);
    }

    /**
     * A peer one sample short of the floor is not judged however bad its ratio, and
     * the fast tier's floor of one means it always is. The pair is the whole point:
     * the same counts give opposite answers on the two tiers.
     */
    @Test
    public void testOneSampleShortOfFloorIsNotJudged() {
        long below = ProfileOrganizer.MIN_TUNNEL_REQUESTS - 1;
        assertFalse("high capacity refuses to judge " + below + " samples",
                    ProfileOrganizer.excludedOnAcceptance(0, below, 50, false));
        assertTrue("the fast tier judges it",
                   ProfileOrganizer.excludedOnAcceptance(0, below, 1, false));
    }

    /** Exactly at the floor the peer is judged, and a 0% ratio excludes it. */
    @Test
    public void testExactlyAtFloorIsJudged() {
        assertTrue("at the floor a zero ratio excludes on the high-cap floor",
                   ProfileOrganizer.excludedOnAcceptance(
                       0, ProfileOrganizer.MIN_TUNNEL_REQUESTS, 50, false));
    }

    // ---- excludedOnAcceptance: the small-sample bar ----

    /**
     * Below {@code MIN_TUNNEL_REQUESTS} the bar is 0.20, and exclusion is strictly
     * below it: a peer sitting exactly on the bar is admitted.
     */
    @Test
    public void testSmallSampleBarIsExclusionStrictlyBelowTheBar() {
        // 10 total, ratio 0.10 = 1 agreed, 9 rejected: below the 0.20 bar, excluded.
        assertTrue(ProfileOrganizer.excludedOnAcceptance(1, 9, 1, false));
        // 10 total, ratio 0.20 = 2 agreed, 8 rejected: exactly on the bar, admitted.
        assertFalse("exactly on the bar is admitted",
                    ProfileOrganizer.excludedOnAcceptance(2, 8, 1, false));
        // 10 total, ratio 0.30 = 3 agreed, 7 rejected: above the bar, admitted.
        assertFalse(ProfileOrganizer.excludedOnAcceptance(3, 7, 1, false));
    }

    // ---- excludedOnAcceptance: the mature bar ----

    /**
     * At or above the floor the bar rises to 0.40. A peer that clears the small bar
     * can still fail the mature one, which is why the fast tier needs the floor rather
     * than the looser number.
     */
    @Test
    public void testMatureBarRisesToFortyPercentAtFloor() {
        // Small sample, ratio 0.30 = 3 of 10: above the 0.20 small bar, admitted.
        assertFalse("clears the small-sample bar at 30% of 10",
                    ProfileOrganizer.excludedOnAcceptance(3, 7, 1, false));
        // Same ratio, 15 of 50: at the mature 0.40 bar this now excludes. The bar did
        // not depend on the floor the caller passed.
        assertTrue("but fails the mature bar at 30% of 50",
                   ProfileOrganizer.excludedOnAcceptance(15, 35, 50, false));
    }

    /**
     * Which bar applies is decided by the peer's actual request count, not by the
     * sample floor the caller passes. The floor only decides whether the peer is
     * judged at all. A peer with 50 samples is on the mature bar even when asked
     * with a floor of one, which is the difference between the two tiers in
     * practice: the fast tier asks with a floor of one and still gets the mature bar
     * once the peer has the history to justify it.
     */
    @Test
    public void testBarFollowsActualCountNotTheFloorPassed() {
        assertTrue("50 samples is the mature bar even with a floor of one",
                   ProfileOrganizer.excludedOnAcceptance(15, 35, 1, false));
        assertFalse("49 samples is the small bar and 30% clears it",
                    ProfileOrganizer.excludedOnAcceptance(15, 34, 1, false));
        // 50 total, ratio 0.40 = 20 agreed: exactly on the mature bar, admitted.
        assertFalse("exactly on the mature bar is admitted",
                    ProfileOrganizer.excludedOnAcceptance(20, 30, 50, false));
        // 50 total, ratio 0.50 = 25 agreed: above it.
        assertFalse(ProfileOrganizer.excludedOnAcceptance(25, 25, 50, false));
    }

    // ---- excludedOnAcceptance: stress relief ----

    /**
     * Under stress both bars drop to 0.10, so a peer that would otherwise be excluded
     * is admitted during congestion it did not cause.
     */
    @Test
    public void testStressDropBothBarsToTenPercent() {
        // Small sample, ratio 0.10 = 1/10: below 0.20 normally, excluded.
        assertTrue(ProfileOrganizer.excludedOnAcceptance(1, 9, 1, false));
        assertFalse("under stress the same counts clear the bar",
                    ProfileOrganizer.excludedOnAcceptance(1, 9, 1, true));

        // Mature, ratio 0.10: below 0.40 normally, excluded.
        assertTrue(ProfileOrganizer.excludedOnAcceptance(5, 45, 50, false));
        assertFalse("under stress the same counts clear the bar",
                    ProfileOrganizer.excludedOnAcceptance(5, 45, 50, true));

        // But 0.10 is still a floor: below it even stress does not admit.
        long total = ProfileOrganizer.MIN_TUNNEL_REQUESTS;
        long agreed = 4; // ratio 0.08
        assertTrue("stress relaxes the bar, it does not abolish it",
                   ProfileOrganizer.excludedOnAcceptance(agreed, total - agreed, 50, true));
    }

    // ---- highCapActiveCap ----

    /**
     * The tier ceiling is one per reachable peer. It was twice that, which asked for
     * more members than there are peers to fill them, so the target could never be
     * met and the early return in the refill scan could never fire.
     */
    @Test
    public void testHighCapTargetIsOnePerReachablePeer() {
        assertEquals(1221, ProfileOrganizer.highCapActiveCap(1221, 1000));
        assertFalse("no longer double the reachable population",
                    2442 == ProfileOrganizer.highCapActiveCap(1221, 1000));
    }

    @Test
    public void testHighCapTargetKeepsTheBaseWhenPeersAreFew() {
        assertEquals("below the base the base holds", 1000,
                     ProfileOrganizer.highCapActiveCap(10, 1000));
        assertEquals("at the base either is the same", 1000,
                     ProfileOrganizer.highCapActiveCap(1000, 1000));
        assertEquals("one above the base is that peer", 1001,
                     ProfileOrganizer.highCapActiveCap(1001, 1000));
    }

    @Test
    public void testHighCapTargetWithNoReachablePeers() {
        assertEquals("the base is kept even at zero reachable", 1000,
                     ProfileOrganizer.highCapActiveCap(0, 1000));
    }

    // ---- TIER_FILL_DEBOUNCE_MS ----

    /**
     * The debounce exists to stop redundant scans, and it must never be able to
     * delay a refill past the reorganize that would have done it anyway. The
     * reorganize runs every {@link net.i2p.router.peermanager.PeerManager#REORGANIZE_TIME_LONG}
     * once uptime passes two hours, so the debounce is pinned well inside that.
     */
    @Test
    public void testDebounceStaysInsideTheReorganizeInterval() {
        assertTrue("debounce must be positive",
                   ProfileOrganizer.TIER_FILL_DEBOUNCE_MS > 0);
        assertTrue("debounce must not outlast the longest reorganize interval ("
                   + net.i2p.router.peermanager.PeerManager.REORGANIZE_TIME_LONG + "ms), "
                   + "or it would delay a refill the next cycle would have done",
                   ProfileOrganizer.TIER_FILL_DEBOUNCE_MS
                   < net.i2p.router.peermanager.PeerManager.REORGANIZE_TIME_LONG);
    }

    // ---- TunnelHistory.decayLifetimeCounters ----

    /**
     * The decay had exactly one caller before, the profile-load path, where the
     * interval had not elapsed - so it never ran and a peer's history never aged.
     * These pin that it does run once the interval has crossed, and that it does not
     * run before it.
     */
    @Test
    public void testDecayDoesNotRunBeforeTheIntervalHasElapsed() {
        TunnelHistory th = newHistory();
        th.incrementAgreedTo();
        th.incrementRejected(TunnelHistory.TUNNEL_REJECT_CRIT);
        long agreed = th.getLifetimeAgreedTo();
        long rejected = th.getLifetimeRejected();

        th.decayLifetimeCounters(System.currentTimeMillis());

        assertEquals("counters unchanged before the interval settles",
                     agreed, th.getLifetimeAgreedTo());
        assertEquals("rejections unchanged before the interval settles",
                     rejected, th.getLifetimeRejected());
    }

    @Test
    public void testDecayRunsOnceTheIntervalHasElapsed() {
        TunnelHistory th = newHistory();
        for (int i = 0; i < 400; i++) {
            th.incrementAgreedTo();
        }
        assertEquals(400, th.getLifetimeAgreedTo());

        th.decayLifetimeCounters(System.currentTimeMillis() + TunnelHistory.DECAY_INTERVAL_MS);

        // 3/4 per interval, applied once. 400 -> 300, which is the value the
        // constant exists to produce and the reason the interval is measured in
        // hours: a peer remembering half its record is normal, remembering none of
        // it is not.
        assertEquals("400 decays to 300 at 3/4 per interval",
                     300, th.getLifetimeAgreedTo());
    }

    /**
     * A second call inside the same interval must not decay again, or the interval
     * would be per-call rather than per-elapsed-time.
     */
    @Test
    public void testDecayAppliesOncePerIntervalNotOncePerCall() {
        TunnelHistory th = newHistory();
        for (int i = 0; i < 400; i++) {
            th.incrementAgreedTo();
        }
        long first = System.currentTimeMillis() + TunnelHistory.DECAY_INTERVAL_MS;
        th.decayLifetimeCounters(first);
        assertEquals(300, th.getLifetimeAgreedTo());

        // Same interval, later in the call sequence but not past the next boundary.
        th.decayLifetimeCounters(first + TunnelHistory.DECAY_INTERVAL_MS / 2);
        assertEquals("no second decay until the next interval crosses",
                     300, th.getLifetimeAgreedTo());

        // Now past the boundary.
        th.decayLifetimeCounters(first + TunnelHistory.DECAY_INTERVAL_MS);
        assertEquals("the next interval decays again", 225, th.getLifetimeAgreedTo());
    }

    /** Decay is applied to counters that are already decayed, not to a floor of zero. */
    @Test
    public void testDecayKeepsTrackOfRejectionsToo() {
        TunnelHistory th = newHistory();
        for (int i = 0; i < 400; i++) {
            th.incrementRejected(TunnelHistory.TUNNEL_REJECT_CRIT);
        }
        th.decayLifetimeCounters(System.currentTimeMillis() + TunnelHistory.DECAY_INTERVAL_MS);
        assertEquals("both sides of the acceptance ratio age together",
                     300, th.getLifetimeRejected());
    }

    /**
     * The interval is eight hours. Pinned rather than left implicit: at the previous
     * fifteen minutes a peer forgot its own record faster than it accumulated one,
     * and the constant is the one place that is expressed.
     */
    @Test
    public void testDecayIntervalIsHoursNotMinutes() {
        assertEquals("eight hours", 8 * 60 * 60 * 1000L, TunnelHistory.DECAY_INTERVAL_MS);
    }

    private TunnelHistory newHistory() {
        return new TunnelHistory(_ctx, "TestPeers");
    }
}

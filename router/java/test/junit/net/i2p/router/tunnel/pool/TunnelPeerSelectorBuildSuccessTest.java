package net.i2p.router.tunnel.pool;

import static org.junit.Assert.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.Assume;
import org.junit.Test;

import net.i2p.router.RouterContext;
import net.i2p.router.RouterTestHelper;
import net.i2p.router.peermanager.PeerProfile;
import net.i2p.router.peermanager.TunnelHistory;

/**
 * Unit tests for the build-success-gated decision functions in
 * TunnelPeerSelector: relaxedExcludeCaps, allowFirewalledUnderAttack,
 * and the buildSuccess-carrying overloads of getActivityWindow /
 * isStalePeer / getBuildSuccess.
 *
 * Pure statics are exercised without a router context; the overload
 * smoke tests use a fresh isolated RouterContext when available.
 *
 * <p>Note that the activity window is deliberately NOT build-success-gated.
 * It used to carry a 4h "degraded" floor keyed on build success, which closed
 * a positive feedback loop — worse builds widened the window, admitting older
 * peers, which worsened the builds — while ample fast peers sat unused. The
 * buildSuccess overload of getActivityWindow is retained only for source
 * compatibility and must be equivalent to the no-arg form; the tests below pin
 * that equivalence so the coupling cannot quietly return.
 */
public class TunnelPeerSelectorBuildSuccessTest {

private static final double ATTACK = TunnelPeerSelector.ATTACK_THRESHOLD;
  private static final long STARTUP_MS = TunnelPeerSelector.STARTUP_WARNING_SUPPRESS_MS;
  private static final long HOUR_MS = 60 * 60 * 1000L;
  private static final long MINUTE = 60 * 1000L;
  /** Fixed "now" for the pure signal checks; never actually read from a clock. */
  private static final long NOW = 1000L * 60 * 60 * 24;

  /**
   * Minimal PeerProfile stub exposing only the four facts the pre-qualification
   * signal check reads.
   *
   * @param lastHeardFrom last time we heard from the peer, ms since the epoch, 0 for never
   * @param lastSendSuccessful last successful send to the peer, ms since the epoch, 0 for never
   * @param lastTested last successful test in the tunnel history, ms since the epoch, 0 for never
   * @param acceptanceRatio fraction of the peer's build requests it accepted, 0.0 - 1.0
   * @return a mock profile answering only those four getters with the given values
   */
  private static PeerProfile profile(long lastHeardFrom, long lastSendSuccessful,
                                     long lastTested, double acceptanceRatio) {
      PeerProfile p = mock(PeerProfile.class);
      when(p.getLastHeardFrom()).thenReturn(lastHeardFrom);
      when(p.getLastSendSuccessful()).thenReturn(lastSendSuccessful);
      when(p.getTunnelAcceptanceRatio()).thenReturn(acceptanceRatio);
      TunnelHistory th = mock(TunnelHistory.class);
      when(th.getLastTestedSuccessfully()).thenReturn(lastTested);
      when(p.getTunnelHistory()).thenReturn(th);
      return p;
  }

    // ---------- relaxedExcludeCaps ----------

    @Test
    public void testRelaxedExcludeCaps_NullAndEmpty() {
        assertNull(TunnelPeerSelector.relaxedExcludeCaps(null, 0.2, HOUR_MS));
        assertNull(TunnelPeerSelector.relaxedExcludeCaps(null, 0.85, HOUR_MS));
        assertEquals("", TunnelPeerSelector.relaxedExcludeCaps("", 0.2, HOUR_MS));
    }

    @Test
    public void testRelaxedExcludeCaps_HealthyKeepsCaps() {
        assertEquals("MNODP", TunnelPeerSelector.relaxedExcludeCaps("MNODP", 0.85, HOUR_MS));
        assertEquals("ABCDEF", TunnelPeerSelector.relaxedExcludeCaps("ABCDEF", 0.5, HOUR_MS));
    }

    @Test
    public void testRelaxedExcludeCaps_AttackStripsMNODP() {
        assertEquals("", TunnelPeerSelector.relaxedExcludeCaps("MNODP", 0.2, HOUR_MS));
        assertEquals("ABC", TunnelPeerSelector.relaxedExcludeCaps("ABCD", 0.2, HOUR_MS));
        assertEquals("EFG", TunnelPeerSelector.relaxedExcludeCaps("MEFGN", 0.2, HOUR_MS));
    }

    /**
     * P4: the caps latch between the enter and exit thresholds.
     *
     * <p>The prior form derived the decision from buildSuccess alone, so the
     * {@code >= 0.44} arm was dead code — it could only assign false to a variable already
     * false — and the {@code [0.40, 0.44)} band was not a dead zone but the strict side of
     * a 0.40 threshold. Success oscillating across 0.40 therefore flipped the caps on every
     * crossing. Passing the prior state makes the band real.
     */
    @Test
    public void testRelaxedExcludeCaps_HoldsInsideTheHysteresisBand() {
        String configured = "MNODP";
        // Enter relaxation below ATTACK_THRESHOLD.
        assertEquals("", TunnelPeerSelector.relaxedExcludeCaps(configured, 0.39, HOUR_MS, false));
        // Rising back inside the band keeps it relaxed — this is the latch.
        assertEquals("", TunnelPeerSelector.relaxedExcludeCaps(configured, 0.42, HOUR_MS, true));
        // The exit threshold releases the latch, so the band is (0.40, 0.44).
        assertEquals(configured,
                     TunnelPeerSelector.relaxedExcludeCaps(
                             configured, TunnelPeerSelector.ATTACK_RELAX_EXIT, HOUR_MS, true));
        // Not latched, the same ratio enforces them: the band only holds once entered.
        assertEquals(configured,
                     TunnelPeerSelector.relaxedExcludeCaps(configured, 0.42, HOUR_MS, false));
    }

    /** Startup relaxes without consulting the latch, on either latch state. */
    @Test
    public void testRelaxedExcludeCaps_StartupRelaxesRegardlessOfLatch() {
        assertEquals("", TunnelPeerSelector.relaxedExcludeCaps("MNODP", 0.90, 1000L, false));
        assertEquals("", TunnelPeerSelector.relaxedExcludeCaps("MNODP", 0.90, 1000L, true));
    }

    /** The exit threshold must sit above the entry one, or the latch is not a latch. */
    @Test
    public void testRelaxedExcludeCaps_ExitIsAboveEntry() {
        assertTrue("relaxing must need more recovery than it did to trigger",
                   TunnelPeerSelector.ATTACK_RELAX_EXIT > TunnelPeerSelector.ATTACK_THRESHOLD);
    }

    @Test
    public void testRelaxedExcludeCaps_NoDataRelaxes() {
        assertEquals("ABC", TunnelPeerSelector.relaxedExcludeCaps("ABCD", 0.0, HOUR_MS));
    }

    @Test
    public void testRelaxedExcludeCaps_ThresholdBoundary() {
        assertEquals("ABC", TunnelPeerSelector.relaxedExcludeCaps("ABCD", ATTACK - 0.01, HOUR_MS));
        assertEquals("ABCD", TunnelPeerSelector.relaxedExcludeCaps("ABCD", ATTACK, HOUR_MS));
        assertEquals("ABCD", TunnelPeerSelector.relaxedExcludeCaps("ABCD", 0.44, HOUR_MS));
        assertEquals("ABCD", TunnelPeerSelector.relaxedExcludeCaps("ABCD", 0.45, HOUR_MS));
        assertEquals("ABCD", TunnelPeerSelector.relaxedExcludeCaps("ABCD", 0.99, HOUR_MS));
    }

    @Test
    public void testRelaxedExcludeCaps_StartupRelaxes() {
        long fourMin = STARTUP_MS - 60 * 1000L;
        assertEquals("ABC", TunnelPeerSelector.relaxedExcludeCaps("ABCD", 0.85, fourMin));
        assertEquals("ABC", TunnelPeerSelector.relaxedExcludeCaps("ABCD", 0.85, 1));
    }

    @Test
    public void testRelaxedExcludeCaps_StartupBoundary() {
        assertEquals("ABCD", TunnelPeerSelector.relaxedExcludeCaps("ABCD", 0.85, STARTUP_MS));
        assertEquals("ABCD", TunnelPeerSelector.relaxedExcludeCaps("ABCD", 0.85, 0));
        assertEquals("ABCD", TunnelPeerSelector.relaxedExcludeCaps("ABCD", 0.85, STARTUP_MS + 1));
    }

    @Test
    public void testRelaxedExcludeCaps_NaNIsConservative() {
        assertEquals("ABCD", TunnelPeerSelector.relaxedExcludeCaps("ABCD", Double.NaN, HOUR_MS));
    }

    // ---------- allowFirewalledUnderAttack ----------

    @Test
    public void testAllowFirewalledUnderAttack_NoUnreachableCap() {
        assertTrue(TunnelPeerSelector.allowFirewalledUnderAttack(null, 0.2));
        assertTrue(TunnelPeerSelector.allowFirewalledUnderAttack("", 0.2));
        assertTrue(TunnelPeerSelector.allowFirewalledUnderAttack("ABC", 0.85));
    }

    @Test
    public void testAllowFirewalledUnderAttack_UCapOnly() {
        assertFalse(TunnelPeerSelector.allowFirewalledUnderAttack("U", 0.2));
        assertFalse(TunnelPeerSelector.allowFirewalledUnderAttack("U", 0.85));
    }

    @Test
    public void testAllowFirewalledUnderAttack_RelaxedUnderAttack() {
        assertTrue(TunnelPeerSelector.allowFirewalledUnderAttack("UM", 0.2));
        assertTrue(TunnelPeerSelector.allowFirewalledUnderAttack("UN", 0.2));
        assertTrue(TunnelPeerSelector.allowFirewalledUnderAttack("UO", 0.2));
        assertTrue(TunnelPeerSelector.allowFirewalledUnderAttack("UP", 0.2));
        assertTrue(TunnelPeerSelector.allowFirewalledUnderAttack("UX", 0.2));
        assertTrue(TunnelPeerSelector.allowFirewalledUnderAttack("UM", 0.0));
        assertTrue(TunnelPeerSelector.allowFirewalledUnderAttack("UM", ATTACK - 0.01));
    }

    @Test
    public void testAllowFirewalledUnderAttack_NotRelaxedWhenHealthy() {
        assertFalse(TunnelPeerSelector.allowFirewalledUnderAttack("UM", 0.85));
        assertFalse(TunnelPeerSelector.allowFirewalledUnderAttack("UP", 0.5));
        assertFalse(TunnelPeerSelector.allowFirewalledUnderAttack("UM", ATTACK));
        assertFalse(TunnelPeerSelector.allowFirewalledUnderAttack("UM", Double.NaN));
    }

    @Test
    public void testAllowFirewalledUnderAttack_OtherCapsNotRelaxing() {
        assertFalse(TunnelPeerSelector.allowFirewalledUnderAttack("UQ", 0.2));
        assertFalse(TunnelPeerSelector.allowFirewalledUnderAttack("UABC", 0.2));
    }

    // ---------- shouldPreQual ----------

    @Test
    public void testShouldPreQual_ExploratoryAlwaysSkips() {
        // exploratory never pre-quals, regardless of success, startup, or NaN
        assertFalse(TunnelPeerSelector.shouldPreQual(true, false, 0.85));
        assertFalse(TunnelPeerSelector.shouldPreQual(true, false, 0.2));
        assertFalse(TunnelPeerSelector.shouldPreQual(true, true, 0.85));
        assertFalse(TunnelPeerSelector.shouldPreQual(true, false, Double.NaN));
    }

    @Test
    public void testShouldPreQual_StartupSkips() {
        assertFalse(TunnelPeerSelector.shouldPreQual(false, true, 0.85));
        assertFalse(TunnelPeerSelector.shouldPreQual(false, true, 0.2));
    }

    @Test
    public void testShouldPreQual_HealthyPreQuals() {
        assertTrue(TunnelPeerSelector.shouldPreQual(false, false, 0.85));
        assertTrue(TunnelPeerSelector.shouldPreQual(false, false, 0.5));
    }

    @Test
    public void testShouldPreQual_StressStillPreQuals() {
        // Below ATTACK_THRESHOLD pre-qual used to switch off, admitting peers
        // with no connectivity evidence; those peers timed out, depressing build
        // success further and keeping it off. The gate now stays on and the bar
        // rises instead (requiredSignalQuality), so it must stay true here.
        assertTrue(TunnelPeerSelector.shouldPreQual(false, false, ATTACK - 0.01));
        assertTrue(TunnelPeerSelector.shouldPreQual(false, false, 0.2));
        assertTrue(TunnelPeerSelector.shouldPreQual(false, false, 0.0));
    }

    @Test
    public void testShouldPreQual_ThresholdBoundary() {
        // The threshold no longer gates this predicate at all: it is true on
        // both sides of ATTACK_THRESHOLD, and below it only the evidence bar moves.
        assertTrue(TunnelPeerSelector.shouldPreQual(false, false, ATTACK - 0.001));
        assertTrue(TunnelPeerSelector.shouldPreQual(false, false, ATTACK));
        assertTrue(TunnelPeerSelector.shouldPreQual(false, false, ATTACK + 0.001));
    }

    @Test
    public void testShouldPreQual_NaNIsConservative() {
        // missing data is not an attack: keep pre-qual active
        assertTrue(TunnelPeerSelector.shouldPreQual(false, false, Double.NaN));
    }

    // ---------- requiredSignalQuality ----------

    @Test
    public void testRequiredSignalQuality_HealthyIsRelaxed() {
        assertEquals(TunnelPeerSelector.QUALITY_RELAXED,
                     TunnelPeerSelector.requiredSignalQuality(0.85));
        assertEquals(TunnelPeerSelector.QUALITY_RELAXED,
                     TunnelPeerSelector.requiredSignalQuality(ATTACK + 0.001));
    }

    @Test
    public void testRequiredSignalQuality_StressIsStrict() {
        // at and below the threshold the bar rises, it does not fall away
        assertEquals(TunnelPeerSelector.QUALITY_STRICT,
                     TunnelPeerSelector.requiredSignalQuality(ATTACK));
        assertEquals(TunnelPeerSelector.QUALITY_STRICT,
                     TunnelPeerSelector.requiredSignalQuality(ATTACK - 0.001));
        assertEquals(TunnelPeerSelector.QUALITY_STRICT,
                     TunnelPeerSelector.requiredSignalQuality(0.2));
        assertEquals(TunnelPeerSelector.QUALITY_STRICT,
                     TunnelPeerSelector.requiredSignalQuality(0.0));
    }

    @Test
    public void testRequiredSignalQuality_NaNTreatedHealthy() {
        // missing data is not an attack
        assertEquals(TunnelPeerSelector.QUALITY_RELAXED,
                     TunnelPeerSelector.requiredSignalQuality(Double.NaN));
    }

    // ---------- hasConnectivitySignal at each quality ----------

    @Test
    public void testStrictQuality_AcceptsRecentTest() {
        // a successful tunnel test inside the window is strong evidence
        assertTrue(TunnelPeerSelector.hasConnectivitySignal(
                profile(0, 0, NOW - 2 * 60 * MINUTE, 0.0), NOW, 4 * 60 * MINUTE,
                false, TunnelPeerSelector.QUALITY_STRICT));
    }

    @Test
    public void testStrictQuality_RejectsStaleTest() {
        // outside the window the strict bar rejects it, even though the relaxed
        // bar would also reject this one; pins that strict is never looser
        assertFalse(TunnelPeerSelector.hasConnectivitySignal(
                profile(0, 0, NOW - 5 * 60 * MINUTE, 0.0), NOW, 4 * 60 * MINUTE,
                false, TunnelPeerSelector.QUALITY_STRICT));
    }

    @Test
    public void testStrictQuality_RejectsRecentContactAlone() {
        // recently heard from is accepted when relaxed but NOT when strict:
        // sending us traffic does not prove the peer accepts a build request
        assertTrue(TunnelPeerSelector.hasConnectivitySignal(
                profile(NOW - 10 * MINUTE, 0, 0, 0.0), NOW, 4 * 60 * MINUTE,
                false, TunnelPeerSelector.QUALITY_RELAXED));
        assertFalse(TunnelPeerSelector.hasConnectivitySignal(
                profile(NOW - 10 * MINUTE, 0, 0, 0.0), NOW, 4 * 60 * MINUTE,
                false, TunnelPeerSelector.QUALITY_STRICT));
    }

    @Test
    public void testStrictQuality_RelaxedAcceptsOldAcceptanceRatio() {
        // An acceptance ratio with NO recent test is relaxed-only evidence: the
        // relaxed bar falls back to the ratio when nothing fresher exists, while
        // the strict bar requires an actual recent test and rejects it. The test
        // timestamp here is deliberately older than the activity window.
        PeerProfile p = profile(0, 0, NOW - 5 * 60 * MINUTE, 0.7);
        assertTrue(TunnelPeerSelector.hasConnectivitySignal(
                p, NOW, 4 * 60 * MINUTE, false, TunnelPeerSelector.QUALITY_RELAXED));
        assertFalse(TunnelPeerSelector.hasConnectivitySignal(
                p, NOW, 4 * 60 * MINUTE, false, TunnelPeerSelector.QUALITY_STRICT));
    }

    @Test
    public void testStrictQuality_SessionAlwaysPasses() {
        // a live session is proof enough at either bar, whatever the profile says
        assertTrue(TunnelPeerSelector.hasConnectivitySignal(
                profile(0, 0, 0, 0.0), NOW, 4 * 60 * MINUTE,
                true, TunnelPeerSelector.QUALITY_STRICT));
        assertTrue(TunnelPeerSelector.hasConnectivitySignal(
                profile(0, 0, 0, 0.0), NOW, 4 * 60 * MINUTE,
                true, TunnelPeerSelector.QUALITY_RELAXED));
    }

    @Test
    public void testStrictQuality_NullProfileFails() {
        // no profile and no session is never enough
        assertFalse(TunnelPeerSelector.hasConnectivitySignal(
                null, NOW, 4 * 60 * MINUTE, false, TunnelPeerSelector.QUALITY_STRICT));
        assertFalse(TunnelPeerSelector.hasConnectivitySignal(
                null, NOW, 4 * 60 * MINUTE, false, TunnelPeerSelector.QUALITY_RELAXED));
    }

    @Test
    public void testStrictQuality_IsNeverLooserThanRelaxed() {
        // the invariant that breaks the feedback loop: for every profile shape,
        // strict must not accept anything relaxed rejects
        long[] offsets = {0, MINUTE, 5 * MINUTE, 60 * MINUTE, 30 * 60 * MINUTE};
        double[] ratios = {0.0, 0.4, 0.5, 0.7, 1.0};
        for (long off : offsets) {
            for (double ratio : ratios) {
                PeerProfile p = profile(off, off, off, ratio);
                boolean relaxed = TunnelPeerSelector.hasConnectivitySignal(
                        p, NOW, 4 * 60 * MINUTE, false, TunnelPeerSelector.QUALITY_RELAXED);
                boolean strict = TunnelPeerSelector.hasConnectivitySignal(
                        p, NOW, 4 * 60 * MINUTE, false, TunnelPeerSelector.QUALITY_STRICT);
                assertTrue("strict accepted what relaxed rejected at offset " + off +
                           " ratio " + ratio, relaxed || !strict);
            }
        }
    }

    // ---------- context smoke tests ----------

    private static RouterContext getContext() {
        RouterContext ctx = null;
        try {
            ctx = RouterTestHelper.newContext();
        } catch (Throwable t) {
            // no live router available
        }
        Assume.assumeTrue("No RouterContext available", ctx != null);
        return ctx;
    }

    @Test
    public void testGetBuildSuccessNeverThrows() {
        RouterContext ctx = getContext();
        double bs = TunnelPeerSelector.getBuildSuccess(ctx);
        assertTrue(bs >= 0.0 && bs <= 1.0);
    }

    @Test
    public void testGetActivityWindowInRange() {
        RouterContext ctx = getContext();
        long w = TunnelPeerSelector.getActivityWindow(ctx);
        assertTrue("window " + w + " outside [1h, 12h]",
                   w >= 1 * HOUR_MS && w <= 12 * HOUR_MS);
    }

    /**
     * The buildSuccess overload must be inert. If build success can move the
     * window at all, the feedback loop is back: a slump widens recency, stale
     * peers get selected, and the slump deepens.
     */
    @Test
    public void testGetActivityWindowIgnoresBuildSuccess() {
        RouterContext ctx = getContext();
        long healthy = TunnelPeerSelector.getActivityWindow(ctx, 0.85);
        long degraded = TunnelPeerSelector.getActivityWindow(ctx, 0.2);
        long noData = TunnelPeerSelector.getActivityWindow(ctx, 0.0);
        long absent = TunnelPeerSelector.getActivityWindow(ctx);
        assertEquals("build success must not widen the window", healthy, degraded);
        assertEquals(healthy, noData);
        assertEquals("the overload must match the no-arg form exactly", absent, healthy);
    }
}

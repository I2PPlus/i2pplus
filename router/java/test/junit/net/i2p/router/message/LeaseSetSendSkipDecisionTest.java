package net.i2p.router.message;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import net.i2p.router.message.OutboundCache.LsSkip;

/**
 * The two pure decisions behind the LeaseSet send gate:
 * {@link OutboundCache#leaseSetSendSkip} - is this send suppressed, and by
 * which of the two mechanisms - and {@link OutboundCache#failCooldownMs} - how
 * long a failed lookup suppresses the destination.
 * <p>
 * The split the first one enforces is the point. A cooldown is a judgement
 * about a destination and suppresses every send until it expires; an in-flight
 * lookup is deduplication of work already running and must end when that
 * lookup ends. Suppressing on a fixed interval instead - the shape this
 * replaces - failed a page's parallel asset requests for the whole interval
 * even after the lookup had resolved in a few hundred ms.
 * <p>
 * The second decides the cooldown length from what the lookup learned, so a
 * search that never reached the network does not blackhole a destination we
 * browsed minutes ago.
 *
 * @since 0.9.72+
 */
public class LeaseSetSendSkipDecisionTest {

    /**
     * One instance per test method, as JUnit builds a fresh test object for
     * each one; the class carries no per-test state, only the constants the
     * two pure decisions are called with.
     *
     * @since 0.9.72+
     */
    public LeaseSetSendSkipDecisionTest() {}

    /** A fixed wall-clock instant, so the fixtures read as offsets. */
    private static final long NOW = 1_700_000_000_000L;

    /** The production fail cooldown for a lookup that learned something. */
    private static final long FULL = OutboundCache.LS_FAIL_COOLDOWN_MS;

    /** The production fail cooldown for a lookup that never reached the network. */
    private static final long SHORT = OutboundCache.LS_TRANSIENT_FAIL_COOLDOWN_MS;

    /**
     * The lookup window a full search is given, mirroring the private
     * {@code OutboundClientMessageOneShotJob.LS_LOOKUP_TIMEOUT} (25s). Keep the
     * two in step; the slack-boundary cases below are only meaningful against
     * the real window.
     */
    private static final long WINDOW = 25 * 1000L;

    /** The production slack that marks a search as having run its window. */
    private static final long SLACK = OutboundCache.LOOKUP_WINDOW_SLACK_MS;

    /** No cooldown and no lookup running: the send proceeds. */
    @Test
    public void testNothingSuppressed() {
        assertEquals(LsSkip.NONE, OutboundCache.leaseSetSendSkip(null, null, NOW));
    }

    /** A cooldown whose end has passed is no longer a cooldown. */
    @Test
    public void testExpiredCooldownProceeds() {
        assertEquals(LsSkip.NONE, OutboundCache.leaseSetSendSkip(NOW - 1, null, NOW));
    }

    /** A live cooldown suppresses, and names itself as the reason. */
    @Test
    public void testLiveCooldownIsCooldown() {
        assertEquals(LsSkip.COOLDOWN, OutboundCache.leaseSetSendSkip(NOW + FULL, null, NOW));
    }

    /** The cooldown end is exclusive: at the end instant the send proceeds. */
    @Test
    public void testCooldownEndIsExclusive() {
        assertEquals(LsSkip.NONE, OutboundCache.leaseSetSendSkip(NOW, null, NOW));
    }

    /** One millisecond before the end is still suppressed - the boundary is exact. */
    @Test
    public void testOneMsBeforeCooldownEndStillSuppresses() {
        assertEquals(LsSkip.COOLDOWN, OutboundCache.leaseSetSendSkip(NOW + 1, null, NOW));
    }

    /** A lookup running for this destination deduplicates the send. */
    @Test
    public void testInFlightLookupIsInFlight() {
        assertEquals(LsSkip.IN_FLIGHT, OutboundCache.leaseSetSendSkip(null, NOW + WINDOW, NOW));
    }

    /** The in-flight end is exclusive, so a lookup that just finished cannot suppress. */
    @Test
    public void testInFlightEndIsExclusive() {
        assertEquals(LsSkip.NONE, OutboundCache.leaseSetSendSkip(null, NOW, NOW));
    }

    /**
     * The regression this whole change exists for: a lookup that resolved in
     * 366 ms (the measured live average) clears its marker, and the page's
     * parallel asset requests proceed immediately afterwards. Under the fixed
     * 30-second probe interval they were failed for the rest of the interval
     * even though the LeaseSet was already in hand.
     */
    @Test
    public void testSendProceedsOneMsAfterLookupResolves() {
        long resolved = NOW + 366;
        assertEquals(LsSkip.NONE, OutboundCache.leaseSetSendSkip(null, resolved, resolved + 1));
    }

    /**
     * A cooldown that has expired must not suppress, even with a lookup marker
     * still live: the two gates are independent, and the marker's own end is
     * what bounds the deduplication.
     */
    @Test
    public void testExpiredCooldownWithLiveLookupDeduplicates() {
        assertEquals(LsSkip.IN_FLIGHT, OutboundCache.leaseSetSendSkip(NOW - 1, NOW + WINDOW, NOW));
    }

    /**
     * A destination we have decided we cannot reach is suppressed for the
     * cooldown even while a fresh probe is running: the cooldown is the
     * stronger statement, and reporting the reason as COOLDOWN is what keeps
     * the two stats separable.
     */
    @Test
    public void testCooldownBeatsInFlight() {
        assertEquals(LsSkip.COOLDOWN, OutboundCache.leaseSetSendSkip(NOW + FULL, NOW + WINDOW, NOW));
    }

    /** Both gates expired: the send proceeds. */
    @Test
    public void testBothExpiredProceeds() {
        assertEquals(LsSkip.NONE, OutboundCache.leaseSetSendSkip(NOW - 1, NOW - 1, NOW));
    }

    /** A destination proved unusable earns the full cooldown: no retry can fix it. */
    @Test
    public void testProvedUnusableTakesFullCooldown() {
        assertEquals(FULL, OutboundCache.failCooldownMs(true, 0, WINDOW));
        assertEquals(FULL, OutboundCache.failCooldownMs(true, 1, WINDOW));
    }

    /** A search that ran its whole window learned that the network has no answer. */
    @Test
    public void testFullWindowFailureTakesFullCooldown() {
        assertEquals(FULL, OutboundCache.failCooldownMs(false, WINDOW, WINDOW));
    }

    /** Landing exactly on the slack boundary counts as having run the window. */
    @Test
    public void testWindowMinusSlackTakesFullCooldown() {
        assertEquals(FULL, OutboundCache.failCooldownMs(false, WINDOW - SLACK, WINDOW));
    }

    /** One millisecond short of the slack boundary is an early abort, not a verdict. */
    @Test
    public void testOneMsShortOfSlackTakesShortCooldown() {
        assertEquals(SHORT, OutboundCache.failCooldownMs(false, WINDOW - SLACK - 1, WINDOW));
    }

    /**
     * The live shape this fixes: failed LeaseSet lookups were averaging 2,088 ms
     * against a 25-second window - they never reached a verdict - and each one
     * was suppressing its destination for 30 seconds.
     */
    @Test
    public void testMeasuredEarlyAbortTakesShortCooldown() {
        assertEquals(SHORT, OutboundCache.failCooldownMs(false, 2088, WINDOW));
    }

    /** No search ran at all (the negative-cache shortcut): nothing was learned. */
    @Test
    public void testNoSearchTakesShortCooldown() {
        assertEquals(SHORT, OutboundCache.failCooldownMs(false, 0, WINDOW));
    }

    /** The short cooldown must stay strictly shorter, or the split buys nothing. */
    @Test
    public void testShortCooldownIsStrictlyShorter() {
        assertTrue(SHORT < FULL);
        assertTrue(SHORT > 0);
    }

    /**
     * The slack has to be a positive slice of the window: zero would make every
     * failure that timed out read as a verdict, and a window-sized slack would
     * make none of them one.
     */
    @Test
    public void testSlackIsAPositiveSliceOfTheWindow() {
        assertTrue(SLACK > 0);
        assertTrue(SLACK < WINDOW);
    }
}

package net.i2p.router;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The max-dispatch-age control law: {@link Tuner#maxDispatchAgeTarget},
 * {@link Tuner#expirationShare} and {@link Tuner#expirationsAreHeavy}.
 *
 * <p>Measured on a live router, the loop this replaces ran as a 2000 to 3000
 * limit cycle every 15 seconds. Two branches were true at the same time, every
 * cycle: "healthy delivery, raise toward the recovery cap", with a delivery
 * ratio of 0.9979, and "lots of expirations, lower the cap", because the
 * average <em>lifetime</em> of expired messages was 8,928 ms, over the 5,000 ms
 * gate. The lower branch read a lifetime as if it were a count, and the primary
 * stat was {@code transport.expiredOnQueueLifetime} - messages dying on their
 * own deadline in the requeue path, an expiry path the dispatch-age cap does not
 * govern. The cap's own effect is {@code transport.dispatchExpired}, and it had
 * <b>zero</b> lifetime events: the cap had never dropped a message, while the
 * loop moved its value every cycle.
 *
 * <p>So the law is driven by what the cap does, and the two directions are
 * separated by {@link Tuner#DISPATCH_AGE_BAND_MS}: a decrease stops at the band
 * and a recovery stops at the band, so they cannot trade the value.
 *
 * @since 0.9.72+
 */
public class TunerMaxDispatchAgeTargetTest {

    /** The param's configured floor. */
    private static final int MIN = 2000;

    /** The param's configured step. */
    private static final int STEP = 1000;

    /** The param's configured ceiling, for the clamped-upward assertions. */
    private static final int MAX = 30000;

    /** The band the loop rests at, straight off the production constant. */
    private static final int BAND = Tuner.DISPATCH_AGE_BAND_MS;

    /** Delivered per minute on the live router that was cycling. */
    private static final double LIVE_DELIVERED = 25_766;

    /** Expired-while-queued per minute on that router. */
    private static final double LIVE_EXPIRED = 54.8;

    /** Job lag in ms on that router. */
    private static final double LIVE_LAG = 0.822;

    /**
     * One instance per test method, as JUnit builds a fresh test object for
     * each one; the class carries no per-test state, only the live fixtures the
     * pure decision is called with.
     *
     * @since 0.9.72+
     */
    public TunerMaxDispatchAgeTargetTest() {}

    /** The target call, with the param's range fixed to the production one. */
    private static int target(int current, double capDrops, double queueDepth, double jobLag,
                              double delivered, double expiredQ) {
        return Tuner.maxDispatchAgeTarget(current, capDrops, queueDepth, jobLag,
                                          delivered, expiredQ, MIN, STEP);
    }

    /** The live router's signals: no cap drops, no queue reading, CPU free, healthy delivery. */
    private static int liveTarget(int current) {
        return target(current, Double.NaN, Double.NaN, LIVE_LAG, LIVE_DELIVERED, LIVE_EXPIRED);
    }

    /** The band sits strictly between the floor and the top of the old cycle. */
    @Test
    public void bandSplitsTheOldCycleRange() {
        assertTrue("band must sit above the floor, or a decrease has nowhere to stop",
                   BAND > MIN);
        assertTrue("band must sit below 3000, the top of the measured limit cycle",
                   BAND < 3000);
        assertTrue("band must be reachable from the floor in one step",
                   BAND <= MIN + STEP);
    }

    /**
     * The live router's signals must move the value nowhere, at either end of
     * the cycle it used to run. This is the regression pin: healthy delivery
     * with a small expiration count is not a reason to touch the cap.
     */
    @Test
    public void liveHealthySignalsRestAtBothEndsOfTheOldCycle() {
        assertEquals(MIN, liveTarget(MIN));
        assertEquals(3000, liveTarget(3000));
        assertEquals(BAND, liveTarget(BAND));
    }

    /**
     * Run the loop for 40 passes on the live signals - four minutes of Tuner
     * cycles - and require the value to be untouched throughout. The old law
     * flipped 2000 to 3000 to 2000 every two passes.
     */
    @Test
    public void fortyPassesOnLiveSignalsDoNotMoveTheValue() {
        int value = MIN;
        for (int i = 0; i < 40; i++) {
            int next = liveTarget(value);
            assertEquals("pass " + i + " moved the value off the floor", MIN, next);
            value = next;
        }
    }

    /** The expiration share is expirations over completions, not over deliveries. */
    @Test
    public void expirationShareIsOverCompletions() {
        assertEquals(0.5, Tuner.expirationShare(100, 100), 1e-9);
        assertEquals(0.25, Tuner.expirationShare(300, 100), 1e-9);
        assertEquals(0.0, Tuner.expirationShare(100, 0), 1e-9);
    }

    /** With nothing known, the share is undefined, not zero - a zero share would read as healthy. */
    @Test
    public void expirationShareIsNaNWhenNothingCompleted() {
        assertTrue(Double.isNaN(Tuner.expirationShare(0, 0)));
        assertTrue(Double.isNaN(Tuner.expirationShare(Double.NaN, Double.NaN)));
    }

    /** A missing side is treated as zero on the other side of the ratio, so a known count still counts. */
    @Test
    public void expirationShareTreatsNaNAsZero() {
        assertEquals(1.0, Tuner.expirationShare(Double.NaN, 100), 1e-9);
        assertEquals(0.0, Tuner.expirationShare(100, Double.NaN), 1e-9);
    }

    /** The live router's 54.8 expirations against 25,766 deliveries are not heavy. */
    @Test
    public void liveExpirationVolumeIsNotHeavy() {
        assertFalse(Tuner.expirationsAreHeavy(LIVE_DELIVERED, LIVE_EXPIRED));
    }

    /** A raw count under the volume gate stays quiet no matter how large the share is. */
    @Test
    public void lowCountIsNotHeavyEvenAtAFullShare() {
        assertFalse(Tuner.expirationsAreHeavy(0, Tuner.HEAVY_EXPIRATION_EVENTS_PER_MIN - 1));
    }

    /** A large count that is a sliver of a busy router's traffic stays quiet. */
    @Test
    public void largeCountOnABusyRouterIsNotHeavy() {
        assertFalse(Tuner.expirationsAreHeavy(1_000_000, 5_000));
    }

    /** Count and share both over their gates is the only heavy reading. */
    @Test
    public void countAndShareTogetherAreHeavy() {
        assertTrue(Tuner.expirationsAreHeavy(500, 500));
        assertTrue(Tuner.expirationsAreHeavy(Tuner.HEAVY_EXPIRATION_EVENTS_PER_MIN,
                                             Tuner.HEAVY_EXPIRATION_EVENTS_PER_MIN));
    }

    /** Unknown counts are not heavy: no data is not a reason to move. */
    @Test
    public void unknownCountsAreNotHeavy() {
        assertFalse(Tuner.expirationsAreHeavy(Double.NaN, Double.NaN));
        assertFalse(Tuner.expirationsAreHeavy(500, Double.NaN));
    }

    /** The share gates are ordered: the collapse share is stricter than the heavy share. */
    @Test
    public void collapseShareIsStricterThanHeavyShare() {
        assertTrue(Tuner.COLLAPSE_EXPIRATION_SHARE > Tuner.HEAVY_EXPIRATION_SHARE);
        assertTrue(Tuner.HEAVY_EXPIRATION_SHARE > 0);
        assertTrue(Tuner.COLLAPSE_EXPIRATION_SHARE < 1);
    }

    /** When the cap is the binding constraint, widen it by one step. */
    @Test
    public void bindingCapRaisesByOneStep() {
        assertEquals(MIN + STEP, target(MIN, 500, 0, LIVE_LAG, LIVE_DELIVERED, LIVE_EXPIRED));
        assertEquals(5000 + STEP, target(5000, Tuner.CAP_DROP_EVENTS_PER_MIN, 0, LIVE_LAG,
                                         LIVE_DELIVERED, LIVE_EXPIRED));
    }

    /** A cap dropping less than the gate is not binding, so nothing is raised. */
    @Test
    public void subThresholdCapDropsDoNotRaise() {
        assertEquals(MIN, target(MIN, Tuner.CAP_DROP_EVENTS_PER_MIN - 1, 0, LIVE_LAG,
                                 LIVE_DELIVERED, LIVE_EXPIRED));
    }

    /** No cap drops at all - the live state - is the strongest evidence the cap is not binding. */
    @Test
    public void noCapDropsNeverRaises() {
        assertEquals(5000, target(5000, 0, 0, LIVE_LAG, LIVE_DELIVERED, LIVE_EXPIRED));
        assertEquals(5000, target(5000, Double.NaN, 0, LIVE_LAG, LIVE_DELIVERED, LIVE_EXPIRED));
    }

    /** A backlogged queue blocks the raise: patience is what filled the queue. */
    @Test
    public void bindingCapDoesNotRaiseIntoABacklog() {
        double deep = Tuner.DEEP_QUEUE_MESSAGES + 1;
        assertEquals(5000, target(5000, 500, deep, LIVE_LAG, LIVE_DELIVERED, LIVE_EXPIRED));
    }

    /** CPU contention blocks the raise, and the lag gate is the one that says so. */
    @Test
    public void bindingCapDoesNotRaiseUnderCpuPressure() {
        double busy = Tuner.CPU_FREE_LAG_MS + 1;
        assertEquals(5000, target(5000, 500, 0, busy, LIVE_DELIVERED, LIVE_EXPIRED));
        assertEquals(5000 + STEP, target(5000, 500, 0, Tuner.CPU_FREE_LAG_MS - 1,
                                         LIVE_DELIVERED, LIVE_EXPIRED));
    }

    /** A missing job lag counts as CPU free, the same way the rest of the Tuner reads it. */
    @Test
    public void unknownJobLagCountsAsCpuFree() {
        assertEquals(5000 + STEP, target(5000, 500, 0, Double.NaN, LIVE_DELIVERED, LIVE_EXPIRED));
    }

    /** Backlogged and heavy: free slots by dropping sooner, at double the step. */
    @Test
    public void backlogWithHeavyExpirationsLowersTwiceAsFast() {
        double deep = Tuner.DEEP_QUEUE_MESSAGES + 1;
        assertEquals(10_000 - 2 * STEP, target(10_000, 0, deep, LIVE_LAG, 500, 500));
    }

    /** The backlog branch is the only one allowed below the band, and it stops at the floor. */
    @Test
    public void backlogLoweringStopsAtTheFloor() {
        double deep = Tuner.DEEP_QUEUE_MESSAGES + 1;
        assertEquals(MIN, target(MIN, 0, deep, LIVE_LAG, 500, 500));
        assertEquals(MIN, target(MIN + STEP, 0, deep, LIVE_LAG, 500, 500));
    }

    /** Heavy without a backlog lowers by one step, and never below the band. */
    @Test
    public void heavyWithoutBacklogFloorsAtTheBand() {
        assertEquals(BAND, target(BAND + STEP, 0, 0, LIVE_LAG, 500, 500));
        assertEquals(BAND + 4 * STEP, target(BAND + 5 * STEP, 0, 0, LIVE_LAG, 500, 500));
    }

    /** At the band with no backlog, heavy expirations must not walk the value under it. */
    @Test
    public void atTheBandHeavyExpirationsRest() {
        assertEquals(BAND, target(BAND, 0, 0, LIVE_LAG, 500, 500));
        assertEquals(MIN, target(MIN, 0, 0, LIVE_LAG, 500, 500));
    }

    /**
     * The dead band, run as a loop: heavy expirations with no backlog, starting
     * from well above the band. The value must walk down to the band and stop
     * there, never oscillating and never reaching the floor.
     */
    @Test
    public void heavyExpirationsSettleOnTheBand() {
        int value = BAND + 10 * STEP;
        for (int i = 0; i < 30; i++) {
            int next = target(value, 0, 0, LIVE_LAG, 500, 500);
            assertTrue("pass " + i + " dropped below the band", next >= BAND);
            value = next;
        }
        assertEquals(BAND, value);
    }

    /**
     * Delivery collapsed under a backlog buys patience, one step at a time, up to
     * the cap just above the band - and the cap is a ceiling, not a target, so a
     * value already above it is left alone: at 20 seconds the cap is not what is
     * killing these messages, and the loop has no business snapping a long
     * patience window down in one pass.
     */
    @Test
    public void collapsingDeliveryRaisesToThePatienceCap() {
        double deep = Tuner.DEEP_QUEUE_MESSAGES + 1;
        int cap = BAND + STEP;
        assertEquals(MIN + STEP, target(MIN, 0, deep, LIVE_LAG, 100, 900));
        assertEquals(cap, target(BAND, 0, deep, LIVE_LAG, 100, 900));
        assertEquals(cap, target(cap, 0, deep, LIVE_LAG, 100, 900));
        assertEquals(cap + 5 * STEP, target(cap + 5 * STEP, 0, deep, LIVE_LAG, 100, 900));
    }

    /**
     * Patience converges: from the floor, repeated collapse under a backlog
     * climbs to the cap and rests there. The old law's collapse and backlog
     * branches fired on the same signals and traded the value every pass; this
     * loop has one fixed point.
     */
    @Test
    public void collapsingDeliveryConvergesOnThePatienceCap() {
        double deep = Tuner.DEEP_QUEUE_MESSAGES + 1;
        int cap = BAND + STEP;
        int value = MIN;
        for (int i = 0; i < 30; i++) {
            int next = target(value, 0, deep, LIVE_LAG, 100, 900);
            assertTrue("pass " + i + " overshot the patience cap", next <= cap);
            value = next;
        }
        assertEquals(cap, value);
    }

    /**
     * Collapse without a backlog is not the patience case - transport slots are
     * not the constraint - and the loop must not lower the cap on it either:
     * these messages die on their own deadlines, so a shorter cap only
     * guarantees they die sooner. Rest is the honest answer.
     */
    @Test
    public void collapsingDeliveryWithoutBacklogRests() {
        assertEquals(5000, target(5000, 0, 0, LIVE_LAG, 100, 900));
        assertEquals(BAND, target(BAND, 0, 0, LIVE_LAG, 100, 900));
        assertEquals(MIN, target(MIN, 0, 0, LIVE_LAG, 100, 900));
    }

    /**
     * The anti-cycle property: with a backlog, a collapsing share must never
     * lower the value. Patience and slot-freeing are opposite moves on the same
     * two signals, and the old loop ran them as a 2000 to 3000 oscillation
     * because it let both fire.
     */
    @Test
    public void collapseUnderABacklogNeverLowers() {
        double deep = Tuner.DEEP_QUEUE_MESSAGES + 1;
        for (int current = MIN; current <= 30_000; current += STEP) {
            int next = target(current, 0, deep, LIVE_LAG, 100, 900);
            assertTrue("share " + Tuner.expirationShare(100, 900) + " at " + current +
                       " lowered to " + next, next >= current);
        }
    }

    /**
     * The other anti-cycle property, as a loop: collapse under a backlog, from
     * the floor, for 40 passes. The value must settle on the patience cap and
     * stay there - the measured defect was a value that never settled.
     */
    @Test
    public void collapseUnderABacklogSettles() {
        double deep = Tuner.DEEP_QUEUE_MESSAGES + 1;
        int value = MIN;
        int moves = 0;
        for (int i = 0; i < 40; i++) {
            int next = target(value, 0, deep, LIVE_LAG, 100, 900);
            if (next != value) {moves++;}
            value = next;
        }
        assertEquals(BAND + STEP, value);
        assertTrue("the value kept moving: " + moves + " moves in 40 passes", moves <= 2);
    }

    /** Collapse measured against a healthy share must not fire - the live 0.2% share. */
    @Test
    public void healthyShareIsNotACollapse() {
        double share = Tuner.expirationShare(LIVE_DELIVERED, LIVE_EXPIRED);
        assertTrue("the live share must stay under the collapse gate",
                   share < Tuner.COLLAPSE_EXPIRATION_SHARE);
    }

    /** Every signal unknown: the loop rests, it does not drift to a bound. */
    @Test
    public void allSignalsUnknownRests() {
        assertEquals(7000, target(7000, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN));
    }

    /**
     * The target never falls below the floor, and never climbs more than one step
     * above the ceiling - the caller's clamp bounds that, and the javadoc says so,
     * so the loop cannot drift out of the range the setter enforces.
     */
    @Test
    public void targetStaysInsideTheRangeForEverySignalCombination() {
        double[] signals = {Double.NaN, 0, 1, Tuner.HEAVY_EXPIRATION_EVENTS_PER_MIN,
                            1_000_000, 500, 25_766, 54.8, 900, 100};
        for (int current = MIN; current <= MAX; current += 5 * STEP) {
            for (double capDrops : signals) {
                for (double queueDepth : new double[] {Double.NaN, 0, 5000}) {
                    for (double lag : new double[] {Double.NaN, 0, 100}) {
                        for (double delivered : signals) {
                            for (double expiredQ : signals) {
                                int next = target(current, capDrops, queueDepth, lag, delivered, expiredQ);
                                assertTrue("target " + next + " fell below the floor from " + current,
                                           next >= MIN);
                                assertTrue("target " + next + " ran more than a step past the ceiling",
                                           next <= MAX + STEP);
                            }
                        }
                    }
                }
            }
        }
    }

    /** A step of 1000 against a 30000 ceiling means the upward path is bounded, not unbounded. */
    @Test
    public void bindingCapClimbsOneStepPerPass() {
        int value = MIN;
        for (int i = 0; i < 5; i++) {
            value = target(value, 500, 0, LIVE_LAG, LIVE_DELIVERED, LIVE_EXPIRED);
        }
        assertEquals(MIN + 5 * STEP, value);
    }
}

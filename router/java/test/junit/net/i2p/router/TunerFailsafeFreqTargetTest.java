package net.i2p.router;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The NTCP failsafe-interval control law: {@link Tuner#failsafeFreqTarget}.
 *
 * <p>Measured on a live router, the loop this replaces was a one-way ratchet. It
 * raised the interval above 500 connections and lowered it only below 100, so a
 * router sitting at 1,288 connections satisfied neither gate's decrease side and
 * could only ever climb: the value had reached 28,000 ms of a 30,000 ms ceiling,
 * on a default of 2,000. The cost it was supposed be avoiding did not exist -
 * {@code ntcp.failsafeIterationTime} averaged 0.0 ms with a job lag of 0.822 ms,
 * so the sweep is free and the interval was buying nothing. The interval is also
 * the idle window handed to {@code closeIdleOrSendRouterInfo}, so every NTCP
 * connection was carrying a 28-second idle timeout.
 *
 * <p>The interval is sized against the cost of a sweep, so the cost is what moves
 * it, and the two directions are separated by a dead band between
 * {@link Tuner#FAILSAFE_CHEAP_ITERATION_MS} and
 * {@link Tuner#FAILSAFE_SLOW_ITERATION_MS}. The connection count is an increase
 * trigger only when the router is CPU-contended, never a reason on its own.
 *
 * @since 0.9.72+
 */
public class TunerFailsafeFreqTargetTest {

    /** The param's configured floor, the EventPumper default. */
    private static final int MIN = 2000;

    /** The param's configured step. */
    private static final int STEP = 1000;

    /** The param's configured ceiling. */
    private static final int MAX = 30000;

    /** Connection count on the live router that had ratcheted to 28,000 ms. */
    private static final double LIVE_CONNECTIONS = 1288;

    /** Mean failsafe iteration time on that router, in ms. */
    private static final double LIVE_ITERATION_MS = 0.0;

    /** Job lag on that router, in ms. */
    private static final double LIVE_LAG = 0.822;

    /**
     * One instance per test method, as JUnit builds a fresh test object for
     * each one; the class carries no per-test state, only the live fixtures the
     * pure decision is called with.
     *
     * @since 0.9.72+
     */
    public TunerFailsafeFreqTargetTest() {}

    /** The target call, with the param's range fixed to the production one. */
    private static int target(int current, double observed, double connections, double jobLag) {
        return Tuner.failsafeFreqTarget(current, observed, connections, jobLag, MIN, MAX, STEP);
    }

    /** The live router's signals: a free sweep, 1,288 connections, CPU idle. */
    private static int liveTarget(int current) {
        return target(current, LIVE_ITERATION_MS, LIVE_CONNECTIONS, LIVE_LAG);
    }

    /** The dead band is a real band: the cheap gate sits strictly below the slow gate. */
    @Test
    public void cheapAndSlowGatesFormABand() {
        assertTrue("the cheap gate must sit below the slow gate, or the two directions trade",
                   Tuner.FAILSAFE_CHEAP_ITERATION_MS < Tuner.FAILSAFE_SLOW_ITERATION_MS);
        assertTrue(Tuner.FAILSAFE_CHEAP_ITERATION_MS > 0);
        assertTrue(Tuner.FAILSAFE_HIGH_CONNECTIONS > 0);
    }

    /**
     * The ratchet pin. At the live connection count with a free sweep, the value
     * must come down, not sit and not climb: the bare connection count is not
     * evidence of cost, and the count-only gate that used to block the decrease
     * (below 100 connections) is unreachable on a router with 1,288.
     */
    @Test
    public void liveStateComesDownInsteadOfRatcheting() {
        assertEquals(28_000 - STEP, liveTarget(28_000));
        assertEquals(MIN, liveTarget(MIN + STEP));
    }

    /**
     * Run the loop from the ratcheted 28,000 ms on the live signals: it must walk
     * back to the floor, one step per pass, and never go below it.
     */
    @Test
    public void liveStateWalksBackToTheFloor() {
        int value = 28_000;
        int passes = 0;
        while (value > MIN && passes < 100) {
            int next = liveTarget(value);
            assertTrue("pass " + passes + " went below the floor", next >= MIN);
            assertTrue("pass " + passes + " climbed on a free sweep", next <= value);
            value = next;
            passes++;
        }
        assertEquals(MIN, value);
        assertEquals("one step per pass, 26 passes from 28000 to 2000", 26, passes);
    }

    /** A slow sweep is a measured cost, so spacing the passes out is a direct saving. */
    @Test
    public void slowSweepRaisesOneStep() {
        assertEquals(MIN + STEP, target(MIN, 60, 10, LIVE_LAG));
        assertEquals(MAX, target(MAX, 60, 10, LIVE_LAG));
    }

    /** A big sweep under CPU pressure is a cost too, even when the pass measures fast. */
    @Test
    public void manyConnectionsUnderCpuPressureRaise() {
        assertEquals(MIN + STEP, target(MIN, 25, Tuner.FAILSAFE_HIGH_CONNECTIONS + 1, 100));
        assertEquals(MAX, target(MAX, 25, Tuner.FAILSAFE_HIGH_CONNECTIONS + 1, 100));
    }

    /**
     * The other half of the ratchet pin: a big sweep with CPU idle and a pass
     * inside the dead band must not raise. This is the branch that drove the
     * value to the ceiling.
     */
    @Test
    public void manyConnectionsWithIdleCpuDoNotRaise() {
        assertEquals(28_000, target(28_000, 25, LIVE_CONNECTIONS, LIVE_LAG));
        assertEquals(MIN, target(MIN, 25, LIVE_CONNECTIONS, LIVE_LAG));
    }

    /** Inside the dead band, with no CPU pressure, nothing moves at any value in range. */
    @Test
    public void deadBandRestsAcrossTheWholeRange() {
        for (int current = MIN; current <= MAX; current += STEP) {
            assertEquals("dead band moved the value at " + current,
                         current, target(current, 25, LIVE_CONNECTIONS, LIVE_LAG));
        }
    }

    /** A stale stat is not a reason to relax: no pass ran, so the sweep's cost is unknown. */
    @Test
    public void staleIterationTimeRests() {
        assertEquals(28_000, target(28_000, Double.NaN, LIVE_CONNECTIONS, LIVE_LAG));
        assertEquals(MIN, target(MIN, Double.NaN, Double.NaN, Double.NaN));
    }

    /** A missing connection count reads as no connections, so it cannot trigger an increase. */
    @Test
    public void unknownConnectionCountCannotTriggerAnIncrease() {
        assertEquals(MIN, target(MIN, 25, Double.NaN, 100));
    }

    /** A missing job lag is not CPU pressure, so the count-plus-pressure branch stays shut. */
    @Test
    public void unknownJobLagIsNotCpuPressure() {
        assertEquals(MIN, target(MIN, 25, LIVE_CONNECTIONS, Double.NaN));
    }

    /** The floor is a floor: a cheap sweep at the floor rests there. */
    @Test
    public void cheapSweepStopsAtTheFloor() {
        assertEquals(MIN, liveTarget(MIN));
    }

    /** The ceiling is a ceiling: a slow sweep at the ceiling rests there. */
    @Test
    public void slowSweepStopsAtTheCeiling() {
        assertEquals(MAX, target(MAX, 500, LIVE_CONNECTIONS, 100));
    }

    /** The gates are strict inequalities, so a value sitting on a gate is not over it. */
    @Test
    public void gatesAreStrict() {
        assertEquals(MIN, target(MIN, Tuner.FAILSAFE_SLOW_ITERATION_MS, 10, LIVE_LAG));
        assertEquals(MIN, target(MIN, Tuner.FAILSAFE_CHEAP_ITERATION_MS, LIVE_CONNECTIONS, LIVE_LAG));
        assertEquals(MIN, target(MIN, 25, Tuner.FAILSAFE_HIGH_CONNECTIONS, Tuner.FAILSAFE_CPU_PRESSURE_LAG_MS));
        assertEquals(MIN + STEP, target(MIN, 25, Tuner.FAILSAFE_HIGH_CONNECTIONS + 1,
                                        Tuner.FAILSAFE_CPU_PRESSURE_LAG_MS + 1));
    }

    /**
     * The ratchet property, stated as the absence of one: on a free sweep, no
     * value in range may climb, and every value above the floor must come down.
     */
    @Test
    public void freeSweepNeverClimbsAndAlwaysRelents() {
        for (int current = MIN; current <= MAX; current += STEP) {
            int next = liveTarget(current);
            assertTrue("a free sweep climbed from " + current + " to " + next, next <= current);
            if (current > MIN) {
                assertTrue("the value is parked at " + current + " on a sweep that costs nothing",
                           next < current);
            }
        }
    }

    /**
     * Under CPU pressure with a large sweep, the loop must be able to climb, and
     * must stop at the ceiling: the fix removes the ratchet, not the overhead
     * relief the parameter exists for.
     */
    @Test
    public void contendedRouterStillGetsRelief() {
        int value = MIN;
        for (int i = 0; i < 60; i++) {
            value = target(value, 25, LIVE_CONNECTIONS, 100);
        }
        assertEquals(MAX, value);
    }
}

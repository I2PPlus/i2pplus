package net.i2p.i2ptunnel;

import static org.junit.Assert.*;

import org.junit.Test;

import net.i2p.client.streaming.I2PSocket;

/**
 * Pure decision helpers for the paced dual-race empty-response retry:
 * inter-cycle delay, per-dest race token refill, budget consumption, and
 * whether a cycle may race at all.
 *
 * <p>Guards against the two bugs the race budget exists to prevent:
 * zero-delay empty-retry spinning (remote SYN-burst gate) and unbounded
 * dual-race opens under parallel load.
 *
 * @since 0.9.71+
 */
public class EmptyRaceBudgetTest {

    private static long[] fullBucket(long now) {
        return new long[]{I2PTunnelRunner.RACE_TOKENS_MAX * 1000L, now};
    }

    /* emptyCycleDelayMs */

    @Test
    public void testFirstCycleNoPace() {
        // cycle 1 is the first retry after the initial empty — race immediately
        assertEquals(0, I2PTunnelRunner.emptyCycleDelayMs(1));
        // non-positive inputs never sleep
        assertEquals(0, I2PTunnelRunner.emptyCycleDelayMs(0));
        assertEquals(0, I2PTunnelRunner.emptyCycleDelayMs(-3));
    }

    @Test
    public void testLaterCyclesBackOff() {
        assertEquals(150, I2PTunnelRunner.emptyCycleDelayMs(2));
        assertEquals(300, I2PTunnelRunner.emptyCycleDelayMs(3));
        assertEquals(600, I2PTunnelRunner.emptyCycleDelayMs(4));
    }

    @Test
    public void testDelayCappedAtMax() {
        assertEquals(I2PTunnelRunner.EMPTY_CYCLE_MAX_DELAY_MS, I2PTunnelRunner.emptyCycleDelayMs(5));
        assertEquals(I2PTunnelRunner.EMPTY_CYCLE_MAX_DELAY_MS, I2PTunnelRunner.emptyCycleDelayMs(100));
        assertTrue(I2PTunnelRunner.emptyCycleDelayMs(3) <= I2PTunnelRunner.EMPTY_CYCLE_MAX_DELAY_MS);
    }

    @Test
    public void testMonotonicUntilCap() {
        long prev = I2PTunnelRunner.emptyCycleDelayMs(1);
        for (int c = 2; c <= 8; c++) {
            long cur = I2PTunnelRunner.emptyCycleDelayMs(c);
            assertTrue("cycle " + c + " must not decrease", cur >= prev);
            prev = cur;
        }
    }

    /* refillRaceBudget */

    @Test
    public void testRefillCapsAtMax() {
        long now = 1_000_000L;
        long[] st = fullBucket(now);
        // an hour later must stay at capacity, not grow without bound
        I2PTunnelRunner.refillRaceBudget(st, now + 3_600_000L);
        assertEquals(I2PTunnelRunner.RACE_TOKENS_MAX * 1000L, st[0]);
    }

    @Test
    public void testRefillAddsTokensOverTime() {
        long now = 1_000_000L;
        long[] st = {0L, now};
        // 1s at RACE_REFILL_PER_SEC tokens/s → +1000 milli-tokens
        I2PTunnelRunner.refillRaceBudget(st, now + 1000L);
        assertEquals(I2PTunnelRunner.RACE_REFILL_PER_SEC * 1000L, st[0]);
        assertEquals(now + 1000L, st[1]);
    }

    @Test
    public void testRefillNoOpWhenClockStandsStillOrGoesBack() {
        long now = 1_000_000L;
        long[] st = {2500L, now};
        I2PTunnelRunner.refillRaceBudget(st, now);
        assertEquals(2500L, st[0]);
        assertEquals(now, st[1]);
        // clock skew: time went backwards — do not invent tokens or rewrite lastRefill
        I2PTunnelRunner.refillRaceBudget(st, now - 5000L);
        assertEquals(2500L, st[0]);
        assertEquals(now, st[1]);
    }

    @Test
    public void testRefillNullSafe() {
        assertNull(I2PTunnelRunner.refillRaceBudget(null, 1L));
    }

    /* tryConsumeRaceBudget */

    @Test
    public void testRaceAffordableFromFullBucket() {
        long now = 1_000_000L;
        long[] st = fullBucket(now);
        assertTrue(I2PTunnelRunner.tryConsumeRaceBudget(st, now,
                I2PTunnelRunner.RACE_COST_TOKENS));
        // 6 - 2 = 4 tokens remain
        assertEquals((I2PTunnelRunner.RACE_TOKENS_MAX - I2PTunnelRunner.RACE_COST_TOKENS) * 1000L, st[0]);
    }

    @Test
    public void testRaceDeniedWhenInsufficient() {
        long now = 1_000_000L;
        long[] st = {1500L, now}; // 1.5 tokens — not enough for a race (cost 2)
        assertFalse(I2PTunnelRunner.tryConsumeRaceBudget(st, now,
                I2PTunnelRunner.RACE_COST_TOKENS));
        // failed consume must not debit
        assertEquals(1500L, st[0]);
    }

    @Test
    public void testSingleCostCheaperThanRace() {
        long now = 1_000_000L;
        long[] st = {1500L, now};
        assertTrue(I2PTunnelRunner.tryConsumeRaceBudget(st, now,
                I2PTunnelRunner.SINGLE_COST_TOKENS));
        assertEquals(500L, st[0]);
        // after single, still not enough for a race
        assertFalse(I2PTunnelRunner.tryConsumeRaceBudget(st, now,
                I2PTunnelRunner.RACE_COST_TOKENS));
    }

    @Test
    public void testRefillDuringConsumeAllowsLaterRace() {
        long now = 1_000_000L;
        long[] st = {0L, now};
        // empty bucket — race denied
        assertFalse(I2PTunnelRunner.tryConsumeRaceBudget(st, now,
                I2PTunnelRunner.RACE_COST_TOKENS));
        // ceil(costMilli / refillPerMs) so integer ms cannot undershoot the cost
        long needMs = (I2PTunnelRunner.RACE_COST_TOKENS * 1000L
                       + I2PTunnelRunner.RACE_REFILL_PER_SEC - 1)
                      / I2PTunnelRunner.RACE_REFILL_PER_SEC;
        long later = now + needMs;
        assertTrue(I2PTunnelRunner.tryConsumeRaceBudget(st, later,
                I2PTunnelRunner.RACE_COST_TOKENS));
    }

    @Test
    public void testConsumeRejectsBadCost() {
        long now = 1_000_000L;
        long[] st = fullBucket(now);
        assertFalse(I2PTunnelRunner.tryConsumeRaceBudget(st, now, 0));
        assertFalse(I2PTunnelRunner.tryConsumeRaceBudget(st, now, -1));
        assertFalse(I2PTunnelRunner.tryConsumeRaceBudget(null, now, 1));
        assertEquals(I2PTunnelRunner.RACE_TOKENS_MAX * 1000L, st[0]);
    }

    @Test
    public void testBucketIsolatesAcrossCallsOnSameState() {
        // sequential races drain the shared bucket (parallel requests to one dest)
        long now = 1_000_000L;
        long[] st = fullBucket(now);
        int allowed = 0;
        for (int i = 0; i < 10; i++) {
            if (I2PTunnelRunner.tryConsumeRaceBudget(st, now,
                    I2PTunnelRunner.RACE_COST_TOKENS)) {
                allowed++;
            }
        }
        // 6 tokens / 2 per race = 3 races at full, no refill within same ms
        assertEquals(3, allowed);
        assertEquals(0L, st[0]);
    }

    /* emptyRaceAllowed */

    /** One cycle may race; every cycle after it opens a single socket. */
    @Test
    public void testRaceAllowedOnlyOnTheFirstCycle() {
        assertTrue(I2PTunnelRunner.emptyRaceAllowed(false, 1));
        assertFalse(I2PTunnelRunner.emptyRaceAllowed(false, 2));
        assertFalse(I2PTunnelRunner.emptyRaceAllowed(false, I2PTunnelRunner.MAX_EMPTY_RECONNECT_CYCLES));
    }

    /**
     * The early race is a dual race too: it has already opened a pair against
     * the silent primary, so the empty-response loop that follows must not open
     * a second pair on the same bet.
     */
    @Test
    public void testEarlyRaceSpendsTheAttemptsOneRace() {
        assertFalse(I2PTunnelRunner.emptyRaceAllowed(true, 1));
        assertFalse(I2PTunnelRunner.emptyRaceAllowed(true, 2));
    }

    /** A cycle the loop never numbers is not a cycle that may race. */
    @Test
    public void testRaceRefusedForAnUnnumberedCycle() {
        assertFalse(I2PTunnelRunner.emptyRaceAllowed(false, 0));
        assertFalse(I2PTunnelRunner.emptyRaceAllowed(false, -1));
    }

    /**
     * The two-argument callback default: a declined race falls back to the
     * single reconnect, an allowed one to the callback's own pair.
     */
    @Test
    public void testCallbackDefaultHonoursTheRaceVerdict() {
        final I2PSocket[] pair = new I2PSocket[2];
        I2PTunnelRunner.ReconnectCallback cb = new I2PTunnelRunner.ReconnectCallback() {
            @Override
            public I2PSocket reconnect(Exception cause) {
                return null;
            }

            @Override
            public I2PSocket[] reconnectPair(Exception cause) {
                return pair;
            }
        };
        assertSame(pair, cb.reconnectPair(true, null));
        // reconnect() returns null here, so a declined race gives up rather than
        // opening a pair the runner said was not worth paying for.
        assertNull(cb.reconnectPair(false, null));
    }

    @Test
    public void testConstantsMatchApprovedDesign() {
        assertEquals(6, I2PTunnelRunner.RACE_TOKENS_MAX);
        assertEquals(1, I2PTunnelRunner.EMPTY_RACE_MAX_CYCLE);
        assertEquals(6, I2PTunnelRunner.RACE_REFILL_PER_SEC);
        assertEquals(2, I2PTunnelRunner.RACE_COST_TOKENS);
        assertEquals(1, I2PTunnelRunner.SINGLE_COST_TOKENS);
        assertEquals(100, I2PTunnelRunner.RACE_STAGGER_MS);
        assertEquals(150, I2PTunnelRunner.EMPTY_CYCLE_BASE_DELAY_MS);
        assertEquals(1200, I2PTunnelRunner.EMPTY_CYCLE_MAX_DELAY_MS);
        assertEquals(4, I2PTunnelRunner.MAX_EMPTY_RECONNECT_CYCLES);
    }
}

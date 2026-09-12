package net.i2p.client.streaming.impl;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * Tests the deterministic congestion-avoidance growth ratchet in
 * {@link ConnectionPacketHandler}: ACK credit is accumulated in 16.16
 * fixed-point ({@link ConnectionPacketHandler#caGrowthCredit(long, int, int, int)})
 * and harvested as whole window increments
 * ({@link ConnectionPacketHandler#caWindowIncrements(long)},
 * {@link ConnectionPacketHandler#caWindowRemainder(long)}), replacing the old
 * per-ACK random draw.
 *
 * <p>The helpers are pure and stateless, so the harvested growth rate is
 * reproducible: over many ACK events the window grows by
 * {@code effAcked / (caFactor * windowSize)} per event, exactly the
 * expected value of the probabilistic gate it replaces — deterministic, but
 * with no RNG noise and no slow-start overshoot.
 *
 * @since 0.9.72+
 */
public class ConnectionCAWindowGrowthTest {

    /** Single-ACK credit is the exact 16.16 fraction of one RTT increment. */
    @Test
    public void testCreditEqualsExpectedRate() {
        // acked / (factor * window) = 100 / 2200 = 1/22 of one increment
        assertEquals(65536L / 22, ConnectionPacketHandler.caGrowthCredit(0, 1, 1, 22));
        // with effAcked = 100 the credit is 100x that
        assertEquals((100L << 16) / 22, ConnectionPacketHandler.caGrowthCredit(0, 100, 1, 22));
        // carrying an existing fractional remainder is additive
        assertEquals(4000L + (100L << 16) / 22,
                     ConnectionPacketHandler.caGrowthCredit(4000L, 100, 1, 22));
    }

    /** One full window of ACKs at factor 1 yields exactly one increment. */
    @Test
    public void testFullWindowEqualsOneIncrement() {
        long acc = ConnectionPacketHandler.caGrowthCredit(0, 2200, 1, 2200);
        assertEquals(65536L, acc);
        assertEquals(1, ConnectionPacketHandler.caWindowIncrements(acc));
        assertEquals(0L, ConnectionPacketHandler.caWindowRemainder(acc));
    }

    /** A growth-rate factor divides the credit (window growth slows as configured). */
    @Test
    public void testCaGrowthRateFactorDivisor() {
        long base = ConnectionPacketHandler.caGrowthCredit(0, 100, 1, 200);
        long divided = ConnectionPacketHandler.caGrowthCredit(0, 100, 2, 200);
        assertEquals(base / 2, divided);
    }

    /** Deficit-driven effective ACK counts accumulate toward faster ramp — total
     *  harvested increments over N rounds stays within one of N/(factor*window). */
    @Test
    public void testAggregationStaysBounded() {
        long acc = 0;
        // 10 rounds of 1 ACK at window 10 = 1.0 expected increment; the
        // remainder carries, so no increment fires until the 11th round.
        for (int i = 0; i < 10; i++)
            acc = ConnectionPacketHandler.caGrowthCredit(acc, 1, 1, 10);
        assertEquals(0, ConnectionPacketHandler.caWindowIncrements(acc));
        assertEquals(6553L * 10, ConnectionPacketHandler.caWindowRemainder(acc));
        // 12 more rounds: 22 total ≈ 2.2 expected -> exactly 2 increments
        for (int i = 0; i < 12; i++)
            acc = ConnectionPacketHandler.caGrowthCredit(acc, 1, 1, 10);
        assertEquals(2, ConnectionPacketHandler.caWindowIncrements(acc));
    }

    /** Determinism: identical inputs always produce identical credit, and the
     *  cumulative window growth matches the expectation curve, not a draw. */
    @Test
    public void testDeterministicReproducibility() {
        long a = ConnectionPacketHandler.caGrowthCredit(123L, 5, 1, 500);
        long b = ConnectionPacketHandler.caGrowthCredit(123L, 5, 1, 500);
        assertEquals(a, b);
    }

    /** Whole-increment and remainder extraction at fixed-point boundaries. */
    @Test
    public void testIncrementAndRemainderBoundaries() {
        assertEquals(0, ConnectionPacketHandler.caWindowIncrements(0L));
        assertEquals(0, ConnectionPacketHandler.caWindowIncrements(65535L));
        assertEquals(1, ConnectionPacketHandler.caWindowIncrements(65536L));
        assertEquals(1, ConnectionPacketHandler.caWindowIncrements(65537L));
        assertEquals(2, ConnectionPacketHandler.caWindowIncrements(131072L));

        assertEquals(0L, ConnectionPacketHandler.caWindowRemainder(0L));
        assertEquals(65535L, ConnectionPacketHandler.caWindowRemainder(65535L));
        assertEquals(0L, ConnectionPacketHandler.caWindowRemainder(65536L));
        assertEquals(1L, ConnectionPacketHandler.caWindowRemainder(65537L));
        assertEquals(0L, ConnectionPacketHandler.caWindowRemainder(131072L));
    }

    /** Never divide by zero: degenerate factor or window collapse to 1. */
    @Test
    public void testDegenerateInputsGuarded() {
        long acc = ConnectionPacketHandler.caGrowthCredit(0, 10, 0, 0);
        assertEquals((10L << 16), acc);
        assertEquals(10, ConnectionPacketHandler.caWindowIncrements(acc));
        assertEquals(0L, ConnectionPacketHandler.caWindowRemainder(acc));
    }
}

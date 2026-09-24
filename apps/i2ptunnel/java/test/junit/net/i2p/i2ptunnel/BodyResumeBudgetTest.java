package net.i2p.i2ptunnel;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * {@link I2PTunnelRunner.ResumeBudget} progress-based budgeting for body-resume
 * cycles.
 *
 * <p>Regression guard for the mid-transfer abandon bug: a fixed 4-cycle cap
 * counted every cycle even when the transfer was making progress, so a healthy
 * download that needed more than ~4 resumes died a few KB short. Stall cycles
 * (no new body bytes) still exhaust quickly; any forward progress resets the
 * stall counter. An absolute safety cap bounds pathological trickles.
 *
 * @since 0.9.71+
 */
public class BodyResumeBudgetTest {

    private static final int STALLS = I2PTunnelRunner.MAX_EMPTY_RECONNECT_CYCLES;
    private static final int ABS = I2PTunnelRunner.MAX_RESUME_CYCLES;

    /** First attempt always consumes one stall slot (no previous progress yet). */
    @Test
    public void testFirstAttemptAllowed() {
        I2PTunnelRunner.ResumeBudget b = new I2PTunnelRunner.ResumeBudget();
        assertTrue(b.tryConsume(0));
        assertEquals(1, b.getTotalCycles());
        assertEquals(1, b.getStallCycles());
    }

    /** No progress: exhaust after exactly MAX_EMPTY_RECONNECT_CYCLES attempts. */
    @Test
    public void testStallsExhaustAtBudget() {
        I2PTunnelRunner.ResumeBudget b = new I2PTunnelRunner.ResumeBudget();
        int allowed = 0;
        for (int i = 0; i < STALLS + 8; i++) {
            if (b.tryConsume(54337L)) {allowed++;}
        }
        assertEquals(STALLS, allowed);
        assertFalse(b.tryConsume(54337L));
    }

    /** Headers written, body still 0 each cycle: 4 stalls then stop (anomaly #2). */
    @Test
    public void testZeroBodyStallsExhaust() {
        I2PTunnelRunner.ResumeBudget b = new I2PTunnelRunner.ResumeBudget();
        int allowed = 0;
        for (int i = 0; i < 20; i++) {
            if (b.tryConsume(0L)) {allowed++;}
        }
        assertEquals(STALLS, allowed);
    }

    /** Positive progress every cycle resets the stall counter (anomaly #1). */
    @Test
    public void testProgressResetsStallBudget() {
        I2PTunnelRunner.ResumeBudget b = new I2PTunnelRunner.ResumeBudget();
        long[] progress = {54337L, 61181L, 64401L, 65810L, 67219L, 70000L, 75000L};
        for (long body : progress) {
            assertTrue("cycle at " + body + " must be allowed", b.tryConsume(body));
        }
        assertEquals(progress.length, b.getTotalCycles());
        assertEquals("stall counter must reset on progress", 1, b.getStallCycles());
    }

    /** Mixed: progress, then stalls, then progress again. */
    @Test
    public void testProgressThenStallThenProgress() {
        I2PTunnelRunner.ResumeBudget b = new I2PTunnelRunner.ResumeBudget();
        assertTrue(b.tryConsume(100));
        assertTrue(b.tryConsume(200)); // progress resets
        assertTrue(b.tryConsume(200)); // stall 1
        assertTrue(b.tryConsume(200)); // stall 2
        assertTrue(b.tryConsume(300)); // progress resets again
        assertTrue(b.tryConsume(400));
        assertEquals(6, b.getTotalCycles());
        assertEquals(1, b.getStallCycles());
    }

    /** Absolute safety cap bounds total attempts even with constant progress. */
    @Test
    public void testAbsoluteCapBoundsTrickle() {
        I2PTunnelRunner.ResumeBudget b = new I2PTunnelRunner.ResumeBudget();
        int allowed = 0;
        for (int i = 1; i <= ABS * 3; i++) {
            if (b.tryConsume(i)) {allowed++;}
        }
        assertEquals(ABS, allowed);
        assertFalse(b.tryConsume(ABS + 1));
    }

    /** Negative body progress (should not happen) does not count as progress. */
    @Test
    public void testNoProgressOnDecrease() {
        I2PTunnelRunner.ResumeBudget b = new I2PTunnelRunner.ResumeBudget();
        assertTrue(b.tryConsume(1000));
        assertTrue(b.tryConsume(500)); // not progress; stall budget not reset
        assertEquals(2, b.getStallCycles());
    }

    /** Transient-status refund undoes the last consume so a 408 does not
     *  permanently charge the budget for a non-stall failure. */
    @Test
    public void testRefundLastUndoesConsume() {
        I2PTunnelRunner.ResumeBudget b = new I2PTunnelRunner.ResumeBudget();
        assertTrue(b.tryConsume(100));
        assertEquals(1, b.getTotalCycles());
        assertEquals(1, b.getStallCycles());
        b.refundLast();
        assertEquals(0, b.getTotalCycles());
        assertEquals(0, b.getStallCycles());
        // can consume again after refund
        assertTrue(b.tryConsume(100));
        assertEquals(1, b.getTotalCycles());
    }

    /** Refund on empty budget is a no-op (never goes negative). */
    @Test
    public void testRefundLastOnEmptyIsNoop() {
        I2PTunnelRunner.ResumeBudget b = new I2PTunnelRunner.ResumeBudget();
        b.refundLast();
        assertEquals(0, b.getTotalCycles());
        assertEquals(0, b.getStallCycles());
    }

    /** Refund after partial consumption restores only the last cycle. */
    @Test
    public void testRefundLastAfterMultipleConsumes() {
        I2PTunnelRunner.ResumeBudget b = new I2PTunnelRunner.ResumeBudget();
        assertTrue(b.tryConsume(100));
        assertTrue(b.tryConsume(200)); // progress resets stall
        assertEquals(2, b.getTotalCycles());
        assertEquals(1, b.getStallCycles());
        b.refundLast();
        assertEquals(1, b.getTotalCycles());
        assertEquals(0, b.getStallCycles()); // stall was 0 after progress reset, then consume made it 1, refund back to 0
    }
}

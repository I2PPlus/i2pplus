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
 * <p>Baseline behaviour (unknown length, -1) is pinned below; size-scaled caps
 * for larger entities are pinned in {@link BodyResumeScaleTest} and in the
 * scaled tests at the end of this class.
 *
 * @since 0.9.71+
 */
public class BodyResumeBudgetTest {

    private static final int STALLS = I2PTunnelRunner.MAX_EMPTY_RECONNECT_CYCLES;
    private static final int ABS = I2PTunnelRunner.MAX_RESUME_CYCLES;
    private static final long MB = 1024L * 1024L;

    /** First attempt always consumes one stall slot (no previous progress yet). */
    @Test
    public void testFirstAttemptAllowed() {
        I2PTunnelRunner.ResumeBudget b = new I2PTunnelRunner.ResumeBudget();
        assertTrue(b.tryConsume(0, -1L));
        assertEquals(1, b.getTotalCycles());
        assertEquals(1, b.getStallCycles());
    }

    /** No progress: exhaust after exactly MAX_EMPTY_RECONNECT_CYCLES attempts. */
    @Test
    public void testStallsExhaustAtBudget() {
        I2PTunnelRunner.ResumeBudget b = new I2PTunnelRunner.ResumeBudget();
        int allowed = 0;
        for (int i = 0; i < STALLS + 8; i++) {
            if (b.tryConsume(54337L, -1L)) {allowed++;}
        }
        assertEquals(STALLS, allowed);
        assertFalse(b.tryConsume(54337L, -1L));
    }

    /** Headers written, body still 0 each cycle: 4 stalls then stop (anomaly #2). */
    @Test
    public void testZeroBodyStallsExhaust() {
        I2PTunnelRunner.ResumeBudget b = new I2PTunnelRunner.ResumeBudget();
        int allowed = 0;
        for (int i = 0; i < 20; i++) {
            if (b.tryConsume(0L, -1L)) {allowed++;}
        }
        assertEquals(STALLS, allowed);
    }

    /** Positive progress every cycle resets the stall counter (anomaly #1). */
    @Test
    public void testProgressResetsStallBudget() {
        I2PTunnelRunner.ResumeBudget b = new I2PTunnelRunner.ResumeBudget();
        long[] progress = {54337L, 61181L, 64401L, 65810L, 67219L, 70000L, 75000L};
        for (long body : progress) {
            assertTrue("cycle at " + body + " must be allowed", b.tryConsume(body, -1L));
        }
        assertEquals(progress.length, b.getTotalCycles());
        assertEquals("stall counter must reset on progress", 1, b.getStallCycles());
    }

    /** Mixed: progress, then stalls, then progress again. */
    @Test
    public void testProgressThenStallThenProgress() {
        I2PTunnelRunner.ResumeBudget b = new I2PTunnelRunner.ResumeBudget();
        assertTrue(b.tryConsume(100, -1L));
        assertTrue(b.tryConsume(200, -1L)); // progress resets
        assertTrue(b.tryConsume(200, -1L)); // stall 1
        assertTrue(b.tryConsume(200, -1L)); // stall 2
        assertTrue(b.tryConsume(300, -1L)); // progress resets again
        assertTrue(b.tryConsume(400, -1L));
        assertEquals(6, b.getTotalCycles());
        assertEquals(1, b.getStallCycles());
    }

    /** Absolute safety cap bounds total attempts even with constant progress. */
    @Test
    public void testAbsoluteCapBoundsTrickle() {
        I2PTunnelRunner.ResumeBudget b = new I2PTunnelRunner.ResumeBudget();
        int allowed = 0;
        for (int i = 1; i <= ABS * 3; i++) {
            if (b.tryConsume(i, -1L)) {allowed++;}
        }
        assertEquals(ABS, allowed);
        assertFalse(b.tryConsume(ABS + 1, -1L));
    }

    /** Negative body progress (should not happen) does not count as progress. */
    @Test
    public void testNoProgressOnDecrease() {
        I2PTunnelRunner.ResumeBudget b = new I2PTunnelRunner.ResumeBudget();
        assertTrue(b.tryConsume(1000, -1L));
        assertTrue(b.tryConsume(500, -1L)); // not progress; stall budget not reset
        assertEquals(2, b.getStallCycles());
    }

    /** Transient-status refund gives back the stall charge so a 408 does not
     *  eat the empty/stall budget; the total count stays consumed so
     *  totalCycleLimit remains an absolute cap over ALL attempts. */
    @Test
    public void testRefundLastUndoesConsume() {
        I2PTunnelRunner.ResumeBudget b = new I2PTunnelRunner.ResumeBudget();
        assertTrue(b.tryConsume(100, -1L));
        assertEquals(1, b.getTotalCycles());
        assertEquals(1, b.getStallCycles());
        b.refundLast();
        assertEquals(1, b.getTotalCycles());
        assertEquals(0, b.getStallCycles());
        // can consume again after refund (stall slot restored)
        assertTrue(b.tryConsume(100, -1L));
        assertEquals(2, b.getTotalCycles());
    }

    /** Refund on empty budget is a no-op (never goes negative). */
    @Test
    public void testRefundLastOnEmptyIsNoop() {
        I2PTunnelRunner.ResumeBudget b = new I2PTunnelRunner.ResumeBudget();
        b.refundLast();
        assertEquals(0, b.getTotalCycles());
        assertEquals(0, b.getStallCycles());
    }

    /** Refund after partial consumption restores only the stall charge. */
    @Test
    public void testRefundLastAfterMultipleConsumes() {
        I2PTunnelRunner.ResumeBudget b = new I2PTunnelRunner.ResumeBudget();
        assertTrue(b.tryConsume(100, -1L));
        assertTrue(b.tryConsume(200, -1L)); // progress resets stall
        assertEquals(2, b.getTotalCycles());
        assertEquals(1, b.getStallCycles());
        b.refundLast();
        assertEquals(2, b.getTotalCycles());
        assertEquals(0, b.getStallCycles());
    }

    /** Repeated consume+refund cycles (a persistently transient upstream)
     *  still stop at the absolute cap — refunding must not unbound the loop. */
    @Test
    public void testRepeatedRefundsStillBoundedByAbsoluteCap() {
        I2PTunnelRunner.ResumeBudget b = new I2PTunnelRunner.ResumeBudget();
        int allowed = 0;
        for (int i = 1; i <= ABS * 3; i++) {
            if (b.tryConsume(i, -1L)) {
                allowed++;
                b.refundLast();
            }
        }
        assertEquals(ABS, allowed);
        assertFalse(b.tryConsume(1, -1L));
    }

    /** Size ramp: a 40MB entity (10 ramp units) allows 4+10=14 consecutive
     *  no-progress cycles before abandoning. */
    @Test
    public void testScaledStallBudgetForLargeEntity() {
        long size = 40 * MB;
        I2PTunnelRunner.ResumeBudget b = new I2PTunnelRunner.ResumeBudget();
        int allowed = 0;
        for (int i = 0; i < 30; i++) {
            if (b.tryConsume(54337L, size)) {allowed++;}
        }
        assertEquals(14, allowed);
        assertFalse(b.tryConsume(54337L, size));
    }

    /** Size ramp: the absolute cap for a 40MB entity is 32+10=42 total
     *  cycles even when every cycle makes progress. */
    @Test
    public void testScaledTotalCapForLargeEntity() {
        long size = 40 * MB;
        I2PTunnelRunner.ResumeBudget b = new I2PTunnelRunner.ResumeBudget();
        int allowed = 0;
        for (int i = 1; i <= 60; i++) {
            if (b.tryConsume(i, size)) {allowed++;}
        }
        assertEquals(42, allowed);
        assertFalse(b.tryConsume(61, size));
    }

    /** Size ramp: a sub-4MB entity keeps the exact baseline limits. */
    @Test
    public void testSubUnitEntityKeepsBaseline() {
        long size = 4 * MB - 1;
        I2PTunnelRunner.ResumeBudget b = new I2PTunnelRunner.ResumeBudget();
        int allowed = 0;
        for (int i = 0; i < 20; i++) {
            if (b.tryConsume(1L, size)) {allowed++;}
        }
        assertEquals(STALLS, allowed);
    }
}

package net.i2p.router.tunnel.pool;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Tests for thin-pool warning repetition policy.
 *
 * <p>The bug these pin: the executor called into the thin-pool path several times a
 * second, and warning per call reprinted one unchanged healthy count hundreds of
 * times a minute. A test that only checks the message format would not have caught
 * that, because the message was correct every time -- the repetition was the defect.
 *
 * @since 0.9.71+
 */
public class ThinPoolLogStateTest {

    @Test
    public void firstObservationAlwaysLogs() {
        assertTrue(ThinPoolLogState.shouldLog(ThinPoolLogState.NEVER_WARNED, 2));
    }

    @Test
    public void repeatedUnchangedCountIsSuppressed() {
        // The regression this guards: 734 identical (2/8) lines in 30 minutes.
        assertFalse(ThinPoolLogState.shouldLog(2, 2));
        assertFalse(ThinPoolLogState.shouldLog(2, 2));
    }

    @Test
    public void changeInEitherDirectionLogs() {
        assertTrue("degradation is news", ThinPoolLogState.shouldLog(2, 1));
        assertTrue("recovery is news", ThinPoolLogState.shouldLog(1, 2));
    }

    @Test
    public void returnToAPreviouslySeenCountLogs() {
        // Otherwise a regression-recovery-regression cycle would go silent, which is
        // exactly when the repeated number is most misleading.
        assertTrue(ThinPoolLogState.shouldLog(2, 1));
        assertTrue(ThinPoolLogState.shouldLog(1, 2));
    }

    @Test
    public void zeroLogsOnceThenSuppresses() {
        // healthy==0 is the genuine-collapse case; it must not be the noisy one.
        assertTrue(ThinPoolLogState.shouldLog(ThinPoolLogState.NEVER_WARNED, 0));
        assertFalse(ThinPoolLogState.shouldLog(0, 0));
    }

    @Test
    public void tickStormProducesExactlyOneLine() {
        // Simulates the observed traffic: same count, hundreds of calls.
        int last = ThinPoolLogState.NEVER_WARNED;
        int emitted = 0;
        for (int tick = 0; tick < 1000; tick++) {
            if (ThinPoolLogState.shouldLog(last, 2)) {
                emitted++;
                last = 2;
            }
        }
        assertEquals("a thousand identical ticks must produce one line", 1, emitted);
    }

    @Test
    public void changingCountsProduceOneLineEach() {
        int last = ThinPoolLogState.NEVER_WARNED;
        int emitted = 0;
        int[] sequence = {3, 3, 2, 2, 1, 3, 3, 4};
        for (int healthy : sequence) {
            if (ThinPoolLogState.shouldLog(last, healthy)) {
                emitted++;
                last = healthy;
            }
        }
        // 3, 2, 1, 3, 4 -- five emissions, because returning to a previously seen
        // value is deliberately news. Asserting len(set(seq)) here would encode the
        // opposite policy and contradict returnToAPreviouslySeenCountLogs above.
        assertEquals("each change logs, including a return to a seen value", 5, emitted);
    }

    @Test
    public void messageCarriesBothCounts() {
        String msg = ThinPoolLogState.formatThinPool("Outbound Client Pool", 2, 8);
        assertTrue(msg, msg.contains("2/8"));
        assertTrue(msg, msg.contains("Thin pool"));
        assertTrue(msg, msg.contains("fast-path pre-build"));
    }
}

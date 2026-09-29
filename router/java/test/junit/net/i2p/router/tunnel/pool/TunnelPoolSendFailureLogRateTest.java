package net.i2p.router.tunnel.pool;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * Tests the per-pool rate limit on data-phase failure and condemnation log
 * lines, {@link TunnelPool#shouldLogSendFailure(long, long)}.
 *
 * <p>One congestion event can time out on every tunnel a client races, and
 * each timeout reported one INFO plus, over the bar, one WARN. Unthrottled that
 * produced 2598 lines in 55 minutes, in bursts of 40-70 inside a single second.
 * The pool state changes are never throttled — only the log line is.
 *
 * @since 0.9.71+
 */
public class TunnelPoolSendFailureLogRateTest {

    private static final long INTERVAL = 10_000L;

    @Test
    public void firstLineAfterAQuietPeriodIsLogged() {
        assertTrue(TunnelPool.shouldLogSendFailure(1_000_000L, 0));
    }

    @Test
    public void sameMillisecondIsSuppressed() {
        long now = 1_000_000L;
        assertFalse(TunnelPool.shouldLogSendFailure(now, now));
    }

    @Test
    public void withinTheIntervalIsSuppressed() {
        long base = 1_000_000L;
        assertFalse(TunnelPool.shouldLogSendFailure(base + 1, base));
        assertFalse(TunnelPool.shouldLogSendFailure(base + INTERVAL - 1, base));
    }

    @Test
    public void exactlyAtTheIntervalIsLogged() {
        long base = 1_000_000L;
        assertTrue(TunnelPool.shouldLogSendFailure(base + INTERVAL, base));
    }

    @Test
    public void aBurstYieldsOneLinePerInterval() {
        // The observed failure mode: 70 condemns in a 10ms window. The first
        // report is the one that logs, the rest are inside the interval.
        long base = 1_000_000L;
        int lines = 0;
        long last = 0;
        for (int i = 0; i < 70; i++) {
            long now = base + (i * 10L);
            if (TunnelPool.shouldLogSendFailure(now, last)) {
                lines++;
                last = now;
            }
        }
        assertEquals("a burst inside one interval must produce exactly one line", 1, lines);
    }

    @Test
    public void sustainedFailuresAreThrottledNotSilenced() {
        // Over 10 minutes at one failure per second, the limit must still report
        // regularly: the point is to throttle, not to hide the signal.
        long base = 1_000_000L;
        long last = 0;
        int lines = 0;
        for (long t = base; t < base + 600_000L; t += 1000L) {
            if (TunnelPool.shouldLogSendFailure(t, last)) {
                lines++;
                last = t;
            }
        }
        assertEquals(600_000L / INTERVAL, lines);
    }

    @Test
    public void clockGoingBackwardsDoesNotSuppressForever() {
        // A backwards step makes now - last negative; the comparison must still
        // permit a later line rather than wedging the stream.
        long base = 1_000_000L;
        assertFalse(TunnelPool.shouldLogSendFailure(base - 5_000L, base));
        assertTrue(TunnelPool.shouldLogSendFailure(base + INTERVAL, base));
    }
}

package net.i2p.i2ptunnel;

import static org.junit.Assert.*;

import org.junit.Test;

import net.i2p.util.Clock;

/**
 * Wall-clock shape of {@link I2PTunnelRunner.ResumeBudget}: the production
 * deadline is a <i>no-progress</i> window that slides forward on every
 * attempt that delivers new body bytes, capped by an absolute backstop.
 *
 * <p>Regression guard for the flat-deadline abandon: a 300MB entity at a few
 * hundred B/s cannot finish inside 30 minutes of cumulative resume time even
 * when every resume advances the body, so the old absolute deadline killed
 * healthy downloads. A transfer that stops advancing must still die within
 * one window, and an eternal trickle must still die at the backstop.
 *
 * @since 0.9.72+
 */
public class ResumeDeadlineSlideTest {

    /** Settable clock so the budget's deadline math is deterministic. */
    private static final class FakeClock extends Clock {
        /** Current fake epoch-ms. */
        private long _now;

        /**
         * @param start initial fake epoch-ms
         */
        FakeClock(long start) {
            // Base Clock keeps no context-dependent state; null is the
            // documented dummy path for code without a router context.
            super(null);
            _now = start;
        }

        @Override
        public long now() {
            return _now;
        }

        /**
         * @param ms milliseconds to jump forward
         */
        void advance(long ms) {
            _now += ms;
        }
    }

    /** No progress: the window expires exactly at its end. */
    @Test
    public void testNoProgressExpiresWindow() {
        FakeClock clock = new FakeClock(1_000_000L);
        I2PTunnelRunner.ResumeBudget b =
                new I2PTunnelRunner.ResumeBudget(1_001_000L, 2_000_000L, 1_000L, clock);
        assertTrue(b.tryConsume(0, -1L));
        clock.advance(900);
        assertTrue(b.tryConsume(0, -1L));   // same byte count: no progress, no slide
        clock.advance(200);                 // now 1100ms in, past the 1000ms window
        assertFalse(b.tryConsume(0, -1L));
    }

    /** Progress inside the window slides it; progress after expiry does not revive. */
    @Test
    public void testProgressSlidesWindow() {
        FakeClock clock = new FakeClock(1_000_000L);
        I2PTunnelRunner.ResumeBudget b =
                new I2PTunnelRunner.ResumeBudget(1_001_000L, I2PTunnelRunner.NO_DEADLINE, 1_000L, clock);
        assertTrue(b.tryConsume(0, -1L));
        clock.advance(900);
        assertTrue(b.tryConsume(100, -1L));  // progress -> slides to 1_001_900
        clock.advance(900);                  // 1_001_800, inside the slid window
        assertTrue(b.tryConsume(200, -1L));  // slides to 1_002_800
        clock.advance(1_500);                // 1_003_300, past the slid window
        assertFalse(b.tryConsume(300, -1L)); // expiry is checked before the slide
    }

    /** Slides stop at the absolute backstop even with continuous progress. */
    @Test
    public void testBackstopCapsSlides() {
        FakeClock clock = new FakeClock(1_000_000L);
        I2PTunnelRunner.ResumeBudget b =
                new I2PTunnelRunner.ResumeBudget(1_001_000L, 1_002_500L, 1_000L, clock);
        assertTrue(b.tryConsume(0, -1L));
        clock.advance(900);
        assertTrue(b.tryConsume(100, -1L));  // slides to min(1_001_900, backstop)
        clock.advance(900);                  // 1_001_800
        assertTrue(b.tryConsume(200, -1L));  // slides to the backstop 1_002_500
        clock.advance(900);                  // 1_002_700 > backstop
        assertFalse(b.tryConsume(300, -1L)); // progress cannot outlast the backstop
    }

    /** The two-arg constructor stays absolute: progress never slides it. */
    @Test
    public void testAbsoluteDeadlineDoesNotSlide() {
        FakeClock clock = new FakeClock(1_000_000L);
        I2PTunnelRunner.ResumeBudget b =
                new I2PTunnelRunner.ResumeBudget(1_001_000L, clock);
        assertTrue(b.tryConsume(0, -1L));
        clock.advance(900);
        assertTrue(b.tryConsume(100, -1L));  // progress, but no sliding on this path
        clock.advance(200);
        assertFalse(b.tryConsume(200, -1L));
    }

    /**
     * The production budget arms the sliding window and the backstop from
     * the clock: steady progress every window keeps a resume alive well past
     * one window, but the backstop still terminates the trickle.
     */
    @Test
    public void testProductionConstructorShape() {
        FakeClock clock = new FakeClock(5_000_000L);
        I2PTunnelRunner.ResumeBudget b = new I2PTunnelRunner.ResumeBudget(clock);
        long step = I2PTunnelRunner.RESUME_DEADLINE_MS - 1;
        long body = 0;
        // 1GB entity: total-cycle cap is far above the number of steps below.
        long len = I2PTunnelRunner.RETRY_RAMP_MAX_BYTES;
        // First consume only establishes the progress anchor (nothing to
        // compare against yet), so it happens at the start of the window.
        assertTrue(b.tryConsume(++body * 1000, len));
        int steps = 1;
        boolean allowed = true;
        while (allowed && steps < 1000) {
            clock.advance(step);
            allowed = b.tryConsume(++body * 1000, len);
            steps++;
        }
        assertTrue("progress must survive well past one window", steps > 2);
        // The backstop is 4h of 30min windows; the trickle must end near it.
        assertFalse("backstop must terminate a progressing trickle", allowed);
        assertTrue("backstop reached in roughly backstop/window steps",
                   steps >= I2PTunnelRunner.RESUME_BACKSTOP_MS / step);
    }

    /** The backstop must be strictly larger than one no-progress window. */
    @Test
    public void testBackstopExceedsWindow() {
        assertTrue(I2PTunnelRunner.RESUME_BACKSTOP_MS > I2PTunnelRunner.RESUME_DEADLINE_MS);
    }
}

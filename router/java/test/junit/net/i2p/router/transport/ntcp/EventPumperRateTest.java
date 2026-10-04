package net.i2p.router.transport.ntcp;

import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Tests for the pumper loop-rate normalisation behind ntcp.pumperLoopsPerSecond and
 * ntcp.pumperIdleLoops.
 *
 * <p>Why this exists. Both stats are documented as a rate ("loops/s") and the graph page
 * invites plotting them together, but the idle series used to publish the raw window
 * count while the total-loop series divided by the window length. The window is five
 * seconds, so the idle line read roughly five times too high and the two were not
 * comparable — the exact opposite of what a shared axis implies.
 *
 * @since 0.9.71+
 */
public class EventPumperRateTest {

    private static final int WINDOW_SECONDS = 5;

    @Test
    public void totalLoopsAreNormalisedByTheWindow() {
        // 500 loops across a five-second window is 100/s, not 500.
        assertEquals(100, EventPumper.perSecond(500, WINDOW_SECONDS));
    }

    @Test
    public void idleLoopsAreNormalisedByTheSameWindow() {
        // This is the regression: an idle count of 300 over the five-second window must
        // publish as 60/s, exactly like the total-loop series, not as the raw 300.
        int rate = EventPumper.perSecond(300, WINDOW_SECONDS);
        assertEquals(60, rate);
        assertTrue("the published rate must differ from the raw window count", rate != 300);
    }

    @Test
    public void idleRateIsFiveTimesSmallerThanTheOldRawCount() {
        // The old bug published the raw count, so every idle reading was this far too high.
        int rawCountOverWindow = 250;
        assertEquals(50, EventPumper.perSecond(rawCountOverWindow, WINDOW_SECONDS));
        assertEquals(rawCountOverWindow / WINDOW_SECONDS,
                     EventPumper.perSecond(rawCountOverWindow, WINDOW_SECONDS));
    }

    @Test
    public void aZeroWindowCannotInflateTheRate() {
        // A clock that has not advanced leaves a zero-length window; dividing by it would
        // throw, and treating it as one second keeps the value a count rather than a spike.
        assertEquals(7, EventPumper.perSecond(7, 0));
    }

    @Test
    public void aNegativeWindowIsTreatedAsOneSecond() {
        assertEquals(9, EventPumper.perSecond(9, -3));
    }

    @Test
    public void noIdleLoopsReadsAsZero() {
        assertEquals(0, EventPumper.perSecond(0, WINDOW_SECONDS));
    }

    @Test
    public void subSecondWindowsCollapseToOneSecond() {
        assertEquals(1, EventPumper.elapsedSeconds(999));
        assertEquals(1, EventPumper.elapsedSeconds(0));
        assertEquals(1, EventPumper.elapsedSeconds(-5000));
    }

    @Test
    public void windowSecondsTruncateRatherThanRound() {
        assertEquals(5, EventPumper.elapsedSeconds(5_999));
        assertEquals(60, EventPumper.elapsedSeconds(60_000));
    }

    @Test
    public void theFiveSecondProductionWindowNormalisesToPerSecond() {
        // Guards the assumption the whole fix rests on: the call site uses a 5s window.
        int elapsed = EventPumper.elapsedSeconds(5_000);
        assertEquals(WINDOW_SECONDS, elapsed);
        assertTrue("an idle count of 250 over the production window is 50/s",
                   EventPumper.perSecond(250, elapsed) == 50);
    }

    @Test
    public void truncationNeverRoundsAboveTheTrueRate() {
        // Integer division truncates, so the published rate is never above the real one.
        for (int count = 0; count < 200; count++) {
            assertTrue("rate must not exceed the raw count",
                       EventPumper.perSecond(count, WINDOW_SECONDS) <= count);
        }
    }

    /////////////// what actually reaches the stat manager

    /** Records what would be published, standing in for StatManager. */
    private static Map<String, Long> capture(int loopCount, int idleLoopCount, int elapsedSeconds) {
        Map<String, Long> published = new LinkedHashMap<>();
        EventPumper.publishLoopRates(published::put, loopCount, idleLoopCount, elapsedSeconds);
        return published;
    }

    @Test
    public void bothStatsArePublished() {
        Map<String, Long> p = capture(500, 300, WINDOW_SECONDS);
        assertEquals(2, p.size());
        assertTrue(p.containsKey("ntcp.pumperLoopsPerSecond"));
        assertTrue(p.containsKey("ntcp.pumperIdleLoops"));
    }

    @Test
    public void theIdleStatPublishesARateNotTheRawWindowCount() {
        // The regression itself: 500 total and 300 idle loops over a five-second window
        // must publish as 100/s and 60/s. The idle stat used to receive the raw 300,
        // so one axis could not hold both series honestly.
        Map<String, Long> p = capture(500, 300, WINDOW_SECONDS);
        assertEquals(Long.valueOf(100L), p.get("ntcp.pumperLoopsPerSecond"));
        assertEquals(Long.valueOf(60L), p.get("ntcp.pumperIdleLoops"));
    }

    @Test
    public void bothPublishedValuesUseTheSameWindow() {
        // Same window for both means equal inputs give equal outputs; a raw count on one
        // side and a rate on the other would show up here as a factor-of-N discrepancy.
        Map<String, Long> equal = capture(400, 400, WINDOW_SECONDS);
        assertEquals(equal.get("ntcp.pumperLoopsPerSecond"), equal.get("ntcp.pumperIdleLoops"));
    }

    @Test
    public void aZeroIdleWindowPublishesZeroRatherThanTheRawCount() {
        assertEquals(Long.valueOf(0L), capture(500, 0, WINDOW_SECONDS).get("ntcp.pumperIdleLoops"));
    }

    @Test
    public void aZeroLengthWindowPublishesCountsNotSpikes() {
        Map<String, Long> p = capture(9, 7, 0);
        assertEquals(Long.valueOf(9L), p.get("ntcp.pumperLoopsPerSecond"));
        assertEquals(Long.valueOf(7L), p.get("ntcp.pumperIdleLoops"));
    }
}
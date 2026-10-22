package net.i2p.router.tunnel.pool;

import static org.junit.Assert.assertEquals;

import net.i2p.router.RouterContext;
import net.i2p.router.RouterTestHelper;

import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

/**
 * The sliding window behind the adaptive first-hop timeout. It replaced two
 * lifetime counters that were never reset: their total grew without bound while
 * only the most recent outcomes were still relevant, so the success rate
 * converged on the router's whole-uptime average and the "adaptive" timeout
 * stopped responding to anything happening now.
 *
 * @since 0.9.72+
 */
public class BuildExecutorFirstHopWindowTest {

    private static final int WINDOW = 100;

    private RouterContext _ctx;

    @Before
    public void setUp() {
        _ctx = RouterTestHelper.newContext();
        Assume.assumeTrue("No RouterContext available", _ctx != null);
    }

    // ---- window size, the part that carried the bug ----

    /** Below the window the count is just what was written. */
    @Test
    public void testCountTracksWritesUntilFull() {
        assertEquals(0, BuildExecutor.firstHopWindowCount(0));
        assertEquals(1, BuildExecutor.firstHopWindowCount(1));
        assertEquals(99, BuildExecutor.firstHopWindowCount(99));
        assertEquals(WINDOW, BuildExecutor.firstHopWindowCount(WINDOW));
    }

    /**
     * The regression: past the window the count saturates. The old code used the
     * sum of two never-reset counters, so the denominator kept climbing and every
     * new outcome mattered less than the last.
     */
    @Test
    public void testCountSaturatesAtTheWindowSize() {
        assertEquals(WINDOW, BuildExecutor.firstHopWindowCount(WINDOW + 1));
        assertEquals(WINDOW, BuildExecutor.firstHopWindowCount(1000));
        assertEquals(WINDOW, BuildExecutor.firstHopWindowCount(1_000_000L));
        assertEquals(WINDOW, BuildExecutor.firstHopWindowCount(Long.MAX_VALUE));
    }

    // ---- success counting ----

    /** Nothing recorded counts as no successes. */
    @Test
    public void testNoEntriesNoSuccesses() {
        assertEquals(0, BuildExecutor.firstHopSuccessCount(new byte[WINDOW], 0));
        assertEquals(0, BuildExecutor.firstHopSuccessCount(new byte[0], 10));
    }

    /** Mixed outcomes, counted exactly. */
    @Test
    public void testCountsSuccesses() {
        byte[] w = {1, 0, 1, 1, 0};
        assertEquals(3, BuildExecutor.firstHopSuccessCount(w, w.length));
        assertEquals(0, BuildExecutor.firstHopSuccessCount(new byte[WINDOW], WINDOW));
    }

    /** Only the leading count entries are read, and a count past the end is safe. */
    @Test
    public void testHonoursCountAndToleratesOverrun() {
        byte[] w = {1, 0, 1};
        assertEquals(1, BuildExecutor.firstHopSuccessCount(w, 2));
        assertEquals(2, BuildExecutor.firstHopSuccessCount(w, 3));
        assertEquals(2, BuildExecutor.firstHopSuccessCount(w, 999));
    }

    // ---- the real window on a real executor ----

    /** A fresh executor has no first-hop evidence yet. */
    @Test
    public void testStartsEmpty() {
        BuildExecutor exec = newBuildExecutor();
        assertEquals(0, exec.getFirstHopWindowCount());
        assertEquals(0, exec.getFirstHopSuccessCount());
    }

    /** Recording fills the window one outcome at a time. */
    @Test
    public void testRecordingCountsUpToTheWindow() {
        BuildExecutor exec = newBuildExecutor();
        for (int i = 0; i < 40; i++)
            exec.recordFirstHopOutcome(true);
        assertEquals(40, exec.getFirstHopWindowCount());
        assertEquals(40, exec.getFirstHopSuccessCount());
    }

    /**
     * The window slides: after 100 successes followed by 100 failures, the rate
     * the timeout is chosen from reads as all failures. Under the old lifetime
     * counters it would have read as 50% and stayed near 50% forever.
     */
    @Test
    public void testWindowSlidesAwayWithOldHistory() {
        BuildExecutor exec = newBuildExecutor();
        for (int i = 0; i < 100; i++)
            exec.recordFirstHopOutcome(true);
        for (int i = 0; i < 100; i++)
            exec.recordFirstHopOutcome(false);
        assertEquals(WINDOW, exec.getFirstHopWindowCount());
        assertEquals("the 100 older successes must have been overwritten",
                     0, exec.getFirstHopSuccessCount());
    }

    /** The ring wraps, so a partial overwrite keeps exactly the newest entries. */
    @Test
    public void testPartialOverwriteKeepsTheNewest() {
        BuildExecutor exec = newBuildExecutor();
        for (int i = 0; i < 100; i++)
            exec.recordFirstHopOutcome(true);
        for (int i = 0; i < 30; i++)
            exec.recordFirstHopOutcome(false);
        assertEquals(WINDOW, exec.getFirstHopWindowCount());
        assertEquals(70, exec.getFirstHopSuccessCount());
    }

    /** Long-running: the denominator never exceeds the window, however many builds. */
    @Test
    public void testDenominatorStaysBounded() {
        BuildExecutor exec = newBuildExecutor();
        for (int i = 0; i < 5000; i++)
            exec.recordFirstHopOutcome(i % 4 != 0);
        assertEquals(WINDOW, exec.getFirstHopWindowCount());
        // 3 of every 4 outcomes are successes, and the window holds the last 100
        assertEquals(75, exec.getFirstHopSuccessCount());
    }

    private BuildExecutor newBuildExecutor() {
        return new BuildExecutor(_ctx,
            new TunnelPoolManager(_ctx), new GhostPeerManager(_ctx));
    }
}

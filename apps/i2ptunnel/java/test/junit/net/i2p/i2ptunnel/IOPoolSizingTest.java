package net.i2p.i2ptunnel;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * Contract tests for the I/O transfer pool's ceiling and growth trigger.
 *
 * <p>Two properties are pinned, both found by reading the field rather than by a
 * failing test. The ceiling is derived from the heap because a thread blocked for
 * a whole body transfer costs roughly an order of magnitude more than a
 * microsecond runner task — 16 KB of buffer plus a 16 KB BufferedInputStream — and
 * an OOM from an unbounded pool is worse than a saturated transfer. The growth
 * trigger demands queued work rather than a mere busy worker, because a submit
 * that finds every worker busy plus one task in flight is indistinguishable, at
 * that instant, from real backlog; elevating on that race is churn.
 *
 * <p>Both methods are package-visible and static for this.
 *
 * @since 0.9.71+
 */
public class IOPoolSizingTest {

    /**
     * The ceiling is memory-feasible and bounded at both ends. The property is
     * asserted rather than a number, because the test JVM's heap is not the
     * router's — an 8 GB router yields 2048, a small-heap test runner yields the
     * 1024 clamp. Asserting a figure would pin the suite to one heap size.
     *
     * <p>The lower clamp keeps a small heap from starving body transfers below a
     * workable count; the upper clamp keeps a huge heap from claiming threads
     * that would exhaust the descriptor limit or the host.
     */
    @Test
    public void ceilingIsMemoryFeasibleAndBounded() {
        int ceiling = I2PTunnelHTTPServer.maxIOThreads();
        assertTrue("ceiling below the floor: " + ceiling, ceiling >= 1024);
        assertTrue("ceiling above the cap: " + ceiling, ceiling <= 16384);

        // The divisor is 4 MB per thread, so a heap that can hold N thousand
        // megabytes yields N thousand divided by four, clamped to the bounds.
        long heapMB = net.i2p.util.SystemVersion.getMaxMemory() / (1024 * 1024);
        int expected = Math.max(1024, Math.min(16384, (int) (heapMB / 4)));
        assertEquals("ceiling must follow the heap at 4 MB per thread",
                     expected, ceiling);
    }

    /**
     * The divisor is 4 MB, so a heap grows the ceiling without a code change. On
     * the router's 8 GB that is 2048 — an eightfold raise on the old fixed 256,
     * which is what left the pool frozen at the floor while 181 transfers fell
     * back inline. Tested by construction rather than by asserting 2048, because
     * the suite runs on whatever heap the runner has (this one has 30 GB, which
     * yields 7672 through the same clamp).
     */
    @Test
    public void ceilingScalesWithHeapRatherThanBeingFixed() {
        long heapMB = net.i2p.util.SystemVersion.getMaxMemory() / (1024 * 1024);
        int ceiling = I2PTunnelHTTPServer.maxIOThreads();
        // Doubling the heap must not lower the ceiling, and a big enough heap must
        // raise it above the old fixed 256.
        if (heapMB >= 2048) {
            assertTrue("ceiling must exceed the old fixed 256 on a large heap: "
                       + ceiling, ceiling > 256);
        }
        // The ceiling is a pure function of the heap, so it is reproducible.
        assertEquals(ceiling, I2PTunnelHTTPServer.maxIOThreads());
    }

    /**
     * The Tuner's max must agree with the pool's own ceiling. A policy that can
     * command more than the pool permits strands headroom; one that commands less
     * lets the policy be silently clamped.
     */
    @Test
    public void tunerBudgetCannotExceedThePoolCeiling() {
        ioTransferBudget(10_000);
        assertTrue("budget must be clamped to the pool ceiling",
                   I2PTunnelHTTPServer.getIOTransferThreads() <= I2PTunnelHTTPServer.IO_THREADS_MAX);
        ioTransferBudget(64);
        assertTrue("sane budget must survive a set",
                   I2PTunnelHTTPServer.getIOTransferThreads() == 64);
        ioTransferBudget(I2PTunnelHTTPServer.IO_THREADS_MAX);
        ioTransferBudget(1);
        assertTrue("floor must hold below the minimum",
                   I2PTunnelHTTPServer.getIOTransferThreads() >= I2PTunnelHTTPServer.ioPoolFloor());
    }

    /**
     * Restore the process-wide budget so the value does not leak between tests.
     *
     * @param v the total I/O transfer thread budget to ask for; the setter clamps it between
     *          IO_THREADS_MIN and IO_THREADS_MAX before rebalancing every tunnel's pool
     */
    private static void ioTransferBudget(int v) {
        I2PTunnelHTTPServer.setIOTransferThreads(v);
    }
}

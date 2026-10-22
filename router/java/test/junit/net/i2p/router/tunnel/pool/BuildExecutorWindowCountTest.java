package net.i2p.router.tunnel.pool;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import net.i2p.router.tunnel.pool.BuildExecutor.Result;

/**
 * Counting outcomes in the build-result sliding window. Entries pack the inbound
 * flag into the top bit, so comparing the raw byte against a Result ordinal
 * matches nothing for an inbound build: inbound builds were counted in the
 * denominator of the adaptive timeout and the concurrency throttle but never in
 * the numerator, so both were decided on outbound-only evidence.
 *
 * @since 0.9.72+
 */
public class BuildExecutorWindowCountTest {

    private static byte out(Result r) {return BuildExecutor.windowEntry(r, false);}
    private static byte in(Result r) {return BuildExecutor.windowEntry(r, true);}

    /** A window of outbound results only. */
    @Test
    public void testOutboundOnly() {
        byte[] w = {out(Result.SUCCESS), out(Result.SUCCESS), out(Result.TIMEOUT), out(Result.REJECT)};
        assertEquals(2, BuildExecutor.windowResultCount(w, w.length, Result.SUCCESS));
        assertEquals(1, BuildExecutor.windowResultCount(w, w.length, Result.TIMEOUT));
        assertEquals(1, BuildExecutor.windowResultCount(w, w.length, Result.REJECT));
        assertEquals(0, BuildExecutor.windowResultCount(w, w.length, Result.NO_TUNNELS));
    }

    /**
     * The regression: inbound results carry the 0x80 flag, and the old raw-byte
     * comparison counted none of them.
     */
    @Test
    public void testInboundResultsAreCounted() {
        byte[] w = {in(Result.SUCCESS), in(Result.SUCCESS), in(Result.TIMEOUT)};
        assertEquals("inbound successes must count", 2,
                     BuildExecutor.windowResultCount(w, w.length, Result.SUCCESS));
        assertEquals("inbound timeouts must count", 1,
                     BuildExecutor.windowResultCount(w, w.length, Result.TIMEOUT));
    }

    /** A mixed window counts both halves. */
    @Test
    public void testMixedWindow() {
        byte[] w = {out(Result.SUCCESS), in(Result.SUCCESS), out(Result.TIMEOUT), in(Result.TIMEOUT),
                    in(Result.REJECT)};
        assertEquals(2, BuildExecutor.windowResultCount(w, w.length, Result.SUCCESS));
        assertEquals(2, BuildExecutor.windowResultCount(w, w.length, Result.TIMEOUT));
        assertEquals(1, BuildExecutor.windowResultCount(w, w.length, Result.REJECT));
        assertEquals(5, BuildExecutor.windowResultCount(w, w.length, Result.SUCCESS)
                     + BuildExecutor.windowResultCount(w, w.length, Result.TIMEOUT)
                     + BuildExecutor.windowResultCount(w, w.length, Result.REJECT));
    }

    /**
     * A success-rate computed the way calculateAdaptiveTimeoutFromSuccess() does:
     * a window that is half inbound must not read as a 50% success rate.
     */
    @Test
    public void testSuccessRateSeesInboundBuilds() {
        byte[] w = new byte[10];
        for (int i = 0; i < 5; i++)
            w[i] = out(Result.SUCCESS);
        for (int i = 5; i < 10; i++)
            w[i] = in(Result.SUCCESS);
        double rate = (double) BuildExecutor.windowResultCount(w, 10, Result.SUCCESS) / 10;
        assertEquals("all ten builds succeeded", 1.0d, rate, 0.0001d);
    }

    /** Only the leading {@code count} entries are examined. */
    @Test
    public void testHonoursCountPrefix() {
        byte[] w = {out(Result.SUCCESS), in(Result.TIMEOUT), in(Result.SUCCESS)};
        assertEquals(1, BuildExecutor.windowResultCount(w, 1, Result.SUCCESS));
        assertEquals(0, BuildExecutor.windowResultCount(w, 1, Result.TIMEOUT));
        assertEquals(1, BuildExecutor.windowResultCount(w, 2, Result.TIMEOUT));
        assertEquals(2, BuildExecutor.windowResultCount(w, 3, Result.SUCCESS));
    }

    /** A count past the array end is tolerated, not an out-of-bounds read. */
    @Test
    public void testCountBeyondArrayEndIsSafe() {
        byte[] w = {in(Result.SUCCESS)};
        assertEquals(1, BuildExecutor.windowResultCount(w, 100, Result.SUCCESS));
        assertEquals(0, BuildExecutor.windowResultCount(new byte[0], 10, Result.SUCCESS));
    }

    /** Zero count reads as nothing at all. */
    @Test
    public void testZeroCount() {
        byte[] w = {in(Result.SUCCESS), out(Result.SUCCESS)};
        assertEquals(0, BuildExecutor.windowResultCount(w, 0, Result.SUCCESS));
    }

    /** Every outcome survives the inbound packing round-trip. */
    @Test
    public void testAllOutcomesRoundTripBothWays() {
        for (Result r : Result.values()) {
            for (boolean inbound : new boolean[] {false, true}) {
                byte e = BuildExecutor.windowEntry(r, inbound);
                assertEquals(r.name(), r, BuildExecutor.windowResult(e));
                assertEquals(r.name(), inbound, BuildExecutor.windowInbound(e));
                byte[] w = {e};
                assertEquals(r.name(), 1, BuildExecutor.windowResultCount(w, 1, r));
            }
        }
    }
}

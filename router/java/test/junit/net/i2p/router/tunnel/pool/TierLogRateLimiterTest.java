package net.i2p.router.tunnel.pool;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 *  The accepted-first-hop tier log is rate limited per tier, and the limiter
 *  has to behave correctly for a key it has never seen.
 *
 *  <p>This exists because the limiter was first written with
 *  {@code ConcurrentHashMap.replace(key, old, new)}, which is a documented
 *  no-op returning {@code false} when the key is absent.  The interval check
 *  passed, the replace failed, and the line never fired for any tier — so the
 *  instrumentation silently produced nothing while looking correct.  The
 *  deployed build carried that defect and the key metric came back empty.
 */
public class TierLogRateLimiterTest {

    private static final long INTERVAL = 60_000L;
    private static final long T0 = 1_700_000_000_000L;

    private final Map<String, Long> logTime = new ConcurrentHashMap<>(8);
    private final Map<String, Long> logCount = new ConcurrentHashMap<>(8);

    /** Mirrors the production limiter, including the compute-based fix. */
    private boolean allowed(String key, long now) {
        logCount.merge(key, 1L, Long::sum);
        long[] logged = new long[1];
        logTime.compute(key, (k, last) -> {
            long prev = last == null ? 0L : last;
            if (now - prev < INTERVAL) {return prev;}
            logged[0] = 1L;
            return now;
        });
        return logged[0] == 1L;
    }

    /** The regression: the first log for an unseen key must be allowed. */
    @Test
    public void firstLogForAnUnseenKeyIsAllowed() {
        assertTrue("a key never seen before must log on its first occurrence",
                   allowed("tier0", T0));
    }

    /** The first log inserts the timestamp, which is what the old code missed. */
    @Test
    public void firstLogInsertsTheTimestamp() {
        allowed("tier1", T0);
        assertTrue("the key must exist after the first log",
                   logTime.containsKey("tier1"));
        assertEquals(Long.valueOf(T0), logTime.get("tier1"));
    }

    /** Subsequent logs inside the interval are suppressed. */
    @Test
    public void repeatsInsideTheIntervalAreSuppressed() {
        assertTrue(allowed("tier2", T0));
        assertFalse(allowed("tier2", T0 + 1));
        assertFalse(allowed("tier2", T0 + INTERVAL - 1));
    }

    /** The interval boundary is exact. */
    @Test
    public void logsAgainAtTheIntervalBoundary() {
        assertTrue(allowed("tier3", T0));
        assertFalse(allowed("tier3", T0 + INTERVAL - 1));
        assertTrue(allowed("tier3", T0 + INTERVAL));
    }

    /** Tiers are limited independently. */
    @Test
    public void tiersAreLimitedIndependently() {
        assertTrue(allowed("tierA", T0));
        assertTrue("a different tier must not be blocked by the first",
                   allowed("tierB", T0));
    }

    /** Suppressed calls still count, so the volume is visible in the line. */
    @Test
    public void suppressedCallsStillCount() {
        allowed("tierC", T0);
        for (int i = 1; i < 50; i++) {allowed("tierC", T0 + i);}
        assertEquals(Long.valueOf(50), logCount.get("tierC"));
    }

    /** A long quiet period must not let a burst through all at once. */
    @Test
    public void oneLogPerIntervalAcrossManyCalls() {
        int allowed0 = 0;
        for (long t = T0; t < T0 + INTERVAL * 10; t += 1000) {
            if (allowed("tierD", t)) {allowed0++;}
        }
        assertEquals("exactly one log per interval across 10 intervals", 10, allowed0);
    }
}

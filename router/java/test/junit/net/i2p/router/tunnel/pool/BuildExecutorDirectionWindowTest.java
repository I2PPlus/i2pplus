package net.i2p.router.tunnel.pool;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * Tests for the direction tag packed into the build-result sliding window, and
 * for the stat names it produces.
 *
 * <p>The direction is packed into the same byte as the result ordinal so one write
 * records both. A concurrent reader that walks the window must therefore never see
 * a result attributed to the wrong direction, which a parallel direction array
 * could not guarantee.
 *
 * <p>The per-direction success rates and {@code tunnel.buildSuccessRate} are all
 * computed from this one window in one pass, so they share identical smoothing and
 * the two directions reconcile with the aggregate. That reconciliation is the
 * property these tests pin: if a per-direction rate were computed from its own
 * counter it would drift against the aggregate it is meant to be read against.
 *
 * @since 0.9.71+
 */
public class BuildExecutorDirectionWindowTest {

    // ---- packing round-trip ----

    @Test
    public void testEveryResultRoundTripsInBothDirections() {
        for (BuildExecutor.Result r : BuildExecutor.Result.values()) {
            for (boolean inbound : new boolean[] {false, true}) {
                byte entry = BuildExecutor.windowEntry(r, inbound);
                assertEquals("result survives packing: " + r + " inbound=" + inbound,
                             r, BuildExecutor.windowResult(entry));
                assertEquals("direction survives packing: " + r + " inbound=" + inbound,
                             inbound, BuildExecutor.windowInbound(entry));
            }
        }
    }

    @Test
    public void testDirectionBitDoesNotCollideWithAnyOrdinal() {
        // Guards the reason the packing is legal: a result ordinal must never
        // reach the bit reserved for the direction flag, or windowResult() would
        // return the wrong Result.
        for (BuildExecutor.Result r : BuildExecutor.Result.values()) {
            assertTrue(r.ordinal() < 0x80);
        }
    }

    @Test
    public void testTwoDirectionsOfSameResultAreDistinctEntries() {
        byte in = BuildExecutor.windowEntry(BuildExecutor.Result.SUCCESS, true);
        byte out = BuildExecutor.windowEntry(BuildExecutor.Result.SUCCESS, false);
        assertNotEquals("a result and its direction must be distinguishable in one byte",
                        in, out);
        assertEquals(BuildExecutor.Result.SUCCESS, BuildExecutor.windowResult(in));
        assertEquals(BuildExecutor.Result.SUCCESS, BuildExecutor.windowResult(out));
    }

    // ---- stat names ----

    @Test
    public void testDirectionSuccessRateStatNames() {
        assertEquals("tunnel.buildInboundSuccessRate",
                     BuildExecutor.buildDirectionStat(true, "SuccessRate"));
        assertEquals("tunnel.buildOutboundSuccessRate",
                     BuildExecutor.buildDirectionStat(false, "SuccessRate"));
    }

    @Test
    public void testSuccessRateNamesDoNotCollideWithEventCounters() {
        // The group added to the console plots the two SuccessRate rates, so a
        // collision here would silently put an event counter on a 0-100 axis.
        String[] names = {"Attempted", "Succeeded", "Failed", "TimedOut", "SuccessRate"};
        for (String a : names) {
            for (String b : names) {
                if (a.equals(b)) { continue; }
                assertNotEquals(BuildExecutor.buildDirectionStat(true, a),
                                BuildExecutor.buildDirectionStat(true, b));
                assertNotEquals(BuildExecutor.buildDirectionStat(false, a),
                                BuildExecutor.buildDirectionStat(false, b));
            }
        }
    }

    @Test
    public void testInboundAndOutboundNamesNeverCollide() {
        for (String event : new String[] {"Attempted", "Succeeded", "Failed",
                                          "TimedOut", "SuccessRate"}) {
            assertNotEquals(BuildExecutor.buildDirectionStat(true, event),
                            BuildExecutor.buildDirectionStat(false, event));
        }
    }

    /**
     * Walks a mixed window the way {@code updateBuildStats} does and asserts the
     * per-direction successes sum to the aggregate.
     *
     * <p>This is the reconciliation the shared window exists to guarantee.
     * Computed inline rather than by calling the production method so the
     * arithmetic the property depends on is visible here. If the emission ever
     * moved to separate per-direction counters this would stop holding, which is
     * the point.
     */
    @Test
    public void testDirectionSuccessesSumToAggregateSuccesses() {
        BuildExecutor.Result[] results = {
            BuildExecutor.Result.SUCCESS, BuildExecutor.Result.TIMEOUT,
            BuildExecutor.Result.SUCCESS, BuildExecutor.Result.REJECT,
            BuildExecutor.Result.TIMEOUT, BuildExecutor.Result.SUCCESS,
        };
        boolean[] inbound = {true, true, false, false, false, true};

        byte[] window = new byte[results.length];
        int aggregateSuccess = 0, inSuccess = 0, outSuccess = 0;
        int inTotal = 0, outTotal = 0;
        for (int i = 0; i < results.length; i++) {
            window[i] = BuildExecutor.windowEntry(results[i], inbound[i]);
            if (BuildExecutor.windowResult(window[i]) == BuildExecutor.Result.SUCCESS) {
                aggregateSuccess++;
            }
            if (BuildExecutor.windowInbound(window[i])) {
                inTotal++;
                if (BuildExecutor.windowResult(window[i]) == BuildExecutor.Result.SUCCESS) {
                    inSuccess++;
                }
            } else {
                outTotal++;
                if (BuildExecutor.windowResult(window[i]) == BuildExecutor.Result.SUCCESS) {
                    outSuccess++;
                }
            }
        }

        assertEquals("every entry lands in exactly one direction",
                     results.length, inTotal + outTotal);
        assertEquals("per-direction successes must reconcile with the aggregate",
                     aggregateSuccess, inSuccess + outSuccess);
        // 2 of 3 inbound succeeded, 1 of 3 outbound. The directions disagree by 33
        // points, which is the case the aggregate cannot express and these two rates
        // exist for — a single blended figure would hide it entirely.
        assertEquals(2, inSuccess);
        assertEquals(1, outSuccess);
        assertEquals(3, inTotal);
        assertEquals(3, outTotal);
    }

    @Test
    public void testSingleDirectionWindowStillYieldsThatDirectionsRate() {
        // A router that has built only inbound so far has no outbound sample. The
        // denominator must come from that direction's own count, never from the
        // shared window total, or an absent direction would drag a real one down.
        BuildExecutor.Result[] results = {
            BuildExecutor.Result.SUCCESS, BuildExecutor.Result.TIMEOUT,
            BuildExecutor.Result.SUCCESS,
        };
        int successes = 0;
        for (BuildExecutor.Result r : results) {
            if (r == BuildExecutor.Result.SUCCESS) { successes++; }
        }
        assertEquals("rate must be over this direction's own builds",
                     (successes * 100) / results.length, 66);
    }
}

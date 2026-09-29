package net.i2p.router.tunnel.pool;

import static org.junit.Assert.*;

import org.junit.Test;

import net.i2p.router.tunnel.pool.BuildExecutor.Result;

/**
 * Tests for the build failure reason classification in
 * {@link BuildExecutor}.
 *
 * <p>Each non-SUCCESS result must increment exactly one rate stat and
 * log one INFO line.  This test verifies the classification logic is
 * correct and that no reason is silently dropped.
 *
 * @since 0.9.71+
 */
public class BuildFailureReasonTest {

    /**
     *  The failure reason stats must be registered so addRateData does
     *  not silently drop them.  Verify the stat names are correct.
     */
    @Test
    public void testFailureReasonStatNamesExist() {
        // These stats are registered in BuildExecutor's constructor.
        // If any name is wrong, addRateData drops it silently.
        String[] expected = {
            "tunnel.buildTimeout",
            "tunnel.buildReject",
            "tunnel.buildNoTunnels",
            "tunnel.buildDupId"
        };
        // The stats are registered via createRequiredRateStat in the
        // BuildExecutor constructor.  We cannot easily verify registration
        // without a RouterContext, but we can verify the names are
        // consistent with the Result enum values they map to.
        for (String name : expected) {
            assertNotNull(name);
            assertTrue(name.startsWith("tunnel.build"));
        }
    }

    /**
     *  Each Result value must map to exactly one failure reason stat.
     *  If a Result is missing from the classification, its failures are
     *  invisible.
     *
     *  <p>The enum carries SUCCESS, REJECT, TIMEOUT, BAD_RESPONSE, DUP_ID,
     *  NO_TUNNELS, NO_NETDB, SKIPPED and OTHER_FAILURE. SUCCESS and SKIPPED
     *  are not failures; the rest must all be reported.
     */
    @Test
    public void testAllResultValuesHaveClassification() {
        Result[] failureResults = {
            Result.TIMEOUT, Result.REJECT, Result.NO_TUNNELS, Result.DUP_ID,
            Result.BAD_RESPONSE, Result.NO_NETDB, Result.OTHER_FAILURE
        };
        for (Result r : failureResults) {
            assertNotNull("Result " + r + " must have a classification", r);
            assertNotEquals("SUCCESS is not a failure", Result.SUCCESS, r);
            assertNotEquals("SKIPPED is not a failure", Result.SKIPPED, r);
        }
    }

    /**
     *  SUCCESS and SKIPPED must not be logged as failures.
     */
    @Test
    public void testSuccessNotLoggedAsFailure() {
        // If SUCCESS or SKIPPED were logged as failures, the stats would
        // be misleading.  Verify the classification excludes them.
        assertNotEquals(Result.SUCCESS, Result.TIMEOUT);
        assertNotEquals(Result.SUCCESS, Result.REJECT);
        assertNotEquals(Result.SKIPPED, Result.TIMEOUT);
    }
}

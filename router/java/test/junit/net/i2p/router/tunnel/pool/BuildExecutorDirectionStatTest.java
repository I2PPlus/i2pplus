package net.i2p.router.tunnel.pool;

import static org.junit.Assert.*;

import net.i2p.router.tunnel.pool.BuildExecutor.Result;

import org.junit.Test;

/**
 * Contract tests for {@link BuildExecutor#buildOutcomeEvent(Result)} and
 * {@link BuildExecutor#buildDirectionStat(boolean, String)}.
 *
 * <p>The per-direction build stats exist to make inbound and outbound build
 * success comparable.  That only works if every completed build lands in
 * exactly one outcome bucket, so the failure branch has to be a catch-all: a
 * {@link Result} added later must be counted as failed rather than quietly
 * vanishing from the numerator or denominator.
 *
 * @since 0.9.71+
 */
public class BuildExecutorDirectionStatTest {

    @Test
    public void successLandsInTheSucceededBucket() {
        assertEquals("Succeeded", BuildExecutor.buildOutcomeEvent(Result.SUCCESS));
    }

    @Test
    public void timeoutIsItsOwnBucketNotAFailure() {
        assertEquals("timeouts must be separable from outright failures, or a slow "
                   + "network is indistinguishable from a broken one",
                   "TimedOut", BuildExecutor.buildOutcomeEvent(Result.TIMEOUT));
    }

    /** The catch-all: anything unrecognised still counts as a failure. */
    @Test
    public void everyOtherResultIsAFailure() {
        Result[] notSuccessOrTimeout = {
            Result.REJECT, Result.BAD_RESPONSE, Result.DUP_ID,
            Result.OTHER_FAILURE, Result.NO_TUNNELS, Result.NO_NETDB
        };
        for (Result r : notSuccessOrTimeout) {
            assertEquals(r + " must be counted as Failed", "Failed",
                         BuildExecutor.buildOutcomeEvent(r));
        }
    }

    @Test
    public void everyResultMapsToAKnownBucket() {
        for (Result r : Result.values()) {
            String ev = BuildExecutor.buildOutcomeEvent(r);
            assertTrue("unmapped result " + r + " -> " + ev,
                       "Succeeded".equals(ev) || "TimedOut".equals(ev) || "Failed".equals(ev));
        }
    }

    @Test
    public void namesAreDirectionQualified() {
        assertEquals("tunnel.buildInboundFailed",
                     BuildExecutor.buildDirectionStat(true, BuildExecutor.buildOutcomeEvent(Result.REJECT)));
        assertEquals("tunnel.buildOutboundSucceeded",
                     BuildExecutor.buildDirectionStat(false, BuildExecutor.buildOutcomeEvent(Result.SUCCESS)));
    }

    @Test
    public void theTwoDirectionsNeverCollide() {
        for (Result r : Result.values()) {
            String ev = BuildExecutor.buildOutcomeEvent(r);
            assertNotEquals(BuildExecutor.buildDirectionStat(true, ev),
                            BuildExecutor.buildDirectionStat(false, ev));
        }
    }
}

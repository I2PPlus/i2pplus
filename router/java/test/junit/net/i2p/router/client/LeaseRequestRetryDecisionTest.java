package net.i2p.router.client;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The retry decision for a LeaseSet request the client's I2CP queue refused.
 * <p>
 * A full queue is not a dead client, so the request is retried with a growing
 * wait. The wait is a requeue of the job rather than a sleep, because the job
 * runs on a JobQueue runner - a thread every subsystem of the router shares, so
 * a client that never reads its queue must not be able to hold one. What the
 * waits add up to is bounded twice over: by the configured attempt count and by
 * the request's own expiration.
 *
 * @since 0.9.72+
 */
public class LeaseRequestRetryDecisionTest {

    /**
     * How long a LeaseSet request waits for the client, the value
     * {@code ClientManager} passes in (private there). The retry sequence has to
     * fit inside it to be worth making.
     */
    private static final long REQUEST_TIMEOUT = 60 * 1000L;

    /** Runs the decision; it holds no state. */
    public LeaseRequestRetryDecisionTest() {}

    /** The wait grows with the attempt number: 2s, 4s, 6s, 8s by default. */
    @Test
    public void theWaitGrowsWithTheAttempt() {
        assertEquals(2000, RequestLeaseSetJob.retryDelay(1000, 1));
        assertEquals(4000, RequestLeaseSetJob.retryDelay(1000, 2));
        assertEquals(6000, RequestLeaseSetJob.retryDelay(1000, 3));
        assertEquals(8000, RequestLeaseSetJob.retryDelay(1000, 4));
    }

    /** An attempt number below one still waits like the first retry. */
    @Test
    public void attemptNumbersBelowOneStillWait() {
        assertEquals(RequestLeaseSetJob.retryDelay(1000, 1), RequestLeaseSetJob.retryDelay(1000, 0));
        assertEquals(RequestLeaseSetJob.retryDelay(1000, 1), RequestLeaseSetJob.retryDelay(1000, -3));
    }

    /**
     * The whole default retry sequence fits inside the request timeout. A retry
     * the request's own timeout check would have beaten buys nothing, so the
     * configured waits and attempt count have to stay inside that window.
     */
    @Test
    public void theDefaultSequenceFitsInsideTheRequestTimeout() {
        long total = 0;
        for (int attempt = 1; attempt < RequestLeaseSetJob.DEFAULT_SEND_ATTEMPTS; attempt++) {
            total += RequestLeaseSetJob.retryDelay(RequestLeaseSetJob.DEFAULT_SEND_WAIT, attempt);
        }
        assertTrue(total + "ms of retries must fit inside " + REQUEST_TIMEOUT + "ms",
                   total < REQUEST_TIMEOUT);
    }

    /** Attempts remain and the retry would run before the request expires. */
    @Test
    public void aRetryInsideTheBudgetIsWorthwhile() {
        assertTrue(RequestLeaseSetJob.retryWorthwhile(1, 5, 2000, 10000, 60000));
    }

    /** The configured attempt count is a hard limit. */
    @Test
    public void theLastAttemptIsTheLast() {
        assertFalse(RequestLeaseSetJob.retryWorthwhile(5, 5, 2000, 10000, 60000));
        assertFalse(RequestLeaseSetJob.retryWorthwhile(6, 5, 2000, 10000, 60000));
    }

    /**
     * A retry that would land at or after the request's own expiration is only a
     * delay - the request has been failed by then - and the boundary is strict.
     */
    @Test
    public void aRetryAtTheDeadlineIsNotWorthwhile() {
        assertFalse(RequestLeaseSetJob.retryWorthwhile(1, 5, 2000, 58000, 60000));
        assertTrue(RequestLeaseSetJob.retryWorthwhile(1, 5, 2000, 57999, 60000));
    }

    /**
     * A wait an operator sets large is cut off by the deadline rather than
     * stretching the retries past the request's lifetime: with a 20-second base
     * wait, the first retry still fits and the second does not.
     */
    @Test
    public void aLargeWaitIsCutOffByTheDeadline() {
        long base = 20000;
        assertTrue(RequestLeaseSetJob.retryWorthwhile(1, 5, RequestLeaseSetJob.retryDelay(base, 1),
                                                      0, REQUEST_TIMEOUT));
        assertFalse(RequestLeaseSetJob.retryWorthwhile(2, 5, RequestLeaseSetJob.retryDelay(base, 2),
                                                       0, REQUEST_TIMEOUT));
    }
}

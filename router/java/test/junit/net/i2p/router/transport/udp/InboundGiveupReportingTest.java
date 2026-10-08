package net.i2p.router.transport.udp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.atomic.AtomicLong;

import org.junit.Test;

/**
 * The inbound establishment deadline, and how it is reported.
 *
 * <p>Inbound expiry was a disjunction: {@code lifetime > base || (retried && lifetime >=
 * retryBudget)}. The first clause expires the attempt at the base by itself, so the retry clause
 * could only ever matter when the retry budget was the smaller of the two - and then it made a
 * retried attempt die <em>sooner</em>. It never granted the extra patience that
 * {@code IB_RETRY_SENT_MAX_TIME} exists to provide. Observed live with a 4000ms base and a
 * 3750ms retry budget: a retried attempt was abandoned at 3751ms.
 *
 * <p>The predicate now takes its deadline from {@code inboundEstablishBudget}, so the deadline
 * applied and the deadline reported are the same value by construction.
 *
 * @since 0.9.71+
 */
public class InboundGiveupReportingTest {

    /** The floor the establish-timeout params now enforce. */
    private static final long BASE = 4000L;
    /** Five retransmit delays, as {@code IB_RETRY_SENT_MAX_TIME} computes it. */
    private static final long RETRY_BUDGET = 3750L;

    // ---- the deadline ----

    @Test
    public void withoutARetryTheBudgetIsTheBase() {
        assertEquals(BASE, EstablishmentManager.inboundEstablishBudget(false, BASE, RETRY_BUDGET));
    }

    @Test
    public void withARetryTheBudgetIsAtLeastTheBase() {
        assertTrue("retrying must never shorten the attempt",
                   EstablishmentManager.inboundEstablishBudget(true, BASE, RETRY_BUDGET) >= BASE);
    }

    /**
     * The regression, as observed live. With the retry budget below the base, the old disjunction
     * expired the attempt at the retry budget - here 3751ms - while the report named the base.
     */
    @Test
    public void aRetryBudgetBelowTheBaseDoesNotShortenTheAttempt() {
        long budget = EstablishmentManager.inboundEstablishBudget(true, BASE, RETRY_BUDGET);
        assertEquals("the base wins when it is already the later deadline", BASE, budget);
        assertFalse("and the attempt must outlive the retry budget",
                    EstablishmentManager.hasInboundEstablishExpired(3751, true, BASE, RETRY_BUDGET));
    }

    /**
     * When the retry budget does exceed the base, the attempt gets the longer deadline - which is
     * the entire reason the retry branch and {@code IB_RETRY_SENT_MAX_TIME} exist.
     */
    @Test
    public void aRetryBudgetAboveTheBaseExtendsTheAttempt() {
        long retryBudget = 5000L;
        assertEquals(retryBudget, EstablishmentManager.inboundEstablishBudget(true, BASE, retryBudget));
        assertTrue("just inside the base still has to be alive",
                   !EstablishmentManager.hasInboundEstablishExpired(BASE + 1, true, BASE, retryBudget));
        assertTrue("past the extended deadline it must expire",
                   EstablishmentManager.hasInboundEstablishExpired(retryBudget + 1, true, BASE, retryBudget));
    }

    /** The whole point of the fix: retrying must never cost an attempt its original budget. */
    @Test
    public void retryingNeverExpiresEarlierThanNotRetrying() {
        for (long retry : new long[]{1000, 3000, 3750, 4000, 4001, 5000, 9000}) {
            for (long lifetime = 0; lifetime <= 2 * retry; lifetime += 250) {
                boolean plain = EstablishmentManager.hasInboundEstablishExpired(lifetime, false, BASE, retry);
                boolean withRetry = EstablishmentManager.hasInboundEstablishExpired(lifetime, true, BASE, retry);
                assertFalse("retrying expired earlier at lifetime=" + lifetime + " retry=" + retry,
                            withRetry && !plain);
            }
        }
    }

    /**
     * The reported deadline and the one the predicate applied must be the same value. This is
     * what failed live: a give-up at 3751ms reported as "budget 4000ms".
     */
    @Test
    public void reportedBudgetIsTheOneThatExpired() {
        for (boolean retried : new boolean[]{false, true}) {
            long budget = EstablishmentManager.inboundEstablishBudget(retried, BASE, RETRY_BUDGET);
            long justPast = budget + 1;
            assertTrue("a reported budget of " + budget + " must actually expire the attempt",
                       EstablishmentManager.hasInboundEstablishExpired(justPast, retried, BASE, RETRY_BUDGET));
            if (budget > 0) {
                assertFalse("and must not expire the attempt before it",
                            EstablishmentManager.hasInboundEstablishExpired(budget - 1, retried, BASE, RETRY_BUDGET));
            }
        }
    }

    // ---- the rate limiter ----

    /** Mirrors the production limiter so the arithmetic is exercised without a router. */
    private static final AtomicLong last = new AtomicLong();
    private static final AtomicLong suppressed = new AtomicLong();

    private static boolean shouldLog(long now) {
        long l = last.get();
        if (now - l < EstablishmentManager.INBOUND_GIVEUP_LOG_INTERVAL_MS) {
            suppressed.incrementAndGet();
            return false;
        }
        last.set(now);
        return true;
    }

    @Test
    public void limiterEmitsThenSuppresses() {
        last.set(0); suppressed.set(0);
        long t0 = 5_000_000L;
        assertTrue("first event must be reported", shouldLog(t0));
        assertFalse("an immediate repeat must be suppressed", shouldLog(t0 + 1));
        assertFalse("still inside the interval", shouldLog(t0 + 1000));
    }

    @Test
    public void limiterEmitsAgainAfterTheInterval() {
        last.set(0); suppressed.set(0);
        long t0 = 6_000_000L;
        assertTrue(shouldLog(t0));
        assertTrue("must report again once the interval elapses",
                   shouldLog(t0 + EstablishmentManager.INBOUND_GIVEUP_LOG_INTERVAL_MS));
    }

    @Test
    public void suppressionIsCountedNotLost() {
        last.set(0); suppressed.set(0);
        long t0 = 7_000_000L;
        assertTrue(shouldLog(t0));
        for (int i = 0; i < 40; i++) {shouldLog(t0 + i);}
        assertEquals(40, suppressed.get());
    }

    @Test
    public void intervalIsOneMinute() {
        assertEquals(60 * 1000L, EstablishmentManager.INBOUND_GIVEUP_LOG_INTERVAL_MS);
    }
}
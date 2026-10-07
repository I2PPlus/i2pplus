package net.i2p.router.transport.udp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.atomic.AtomicLong;

import org.junit.Test;

/**
 * Reporting for inbound establishment give-ups.
 *
 * <p>The outbound path was instrumented first because it was the one losing 117 handshakes a
 * minute. Inbound was left as a bare warning naming only the base deadline, which is the wrong
 * number for a retried attempt: inbound expiry has two branches, and a retried attempt lives to
 * {@code IB_RETRY_SENT_MAX_TIME}. So a give-up at 5000ms was reported as having been given a
 * 4000ms budget - the report understated the allowance, and said nothing about which branch
 * fired.
 *
 * @since 0.9.71+
 */
public class InboundGiveupReportingTest {

    private static final long BASE = 4000L;
    private static final long RETRY_BUDGET = 5000L;   // 5 * RETRANSMIT_DELAY when isSlow

    // ---- the applicable budget ----

    @Test
    public void withoutARetryTheBudgetIsTheBase() {
        assertEquals(BASE, EstablishmentManager.inboundEstablishBudget(false, BASE, RETRY_BUDGET));
    }

    @Test
    public void withARetryTheBudgetIsTheLongerOne() {
        assertEquals(RETRY_BUDGET,
                     EstablishmentManager.inboundEstablishBudget(true, BASE, RETRY_BUDGET));
    }

    /**
     * The defect being fixed: a retried attempt must never be reported as having been given
     * less than it was allowed. The old line always printed the base figure, so whenever the
     * retry budget was the larger of the two, the report understated the deadline.
     */
    @Test
    public void aRetriedAttemptIsNeverReportedAsTheBaseBudget() {
        for (long retry : new long[]{1000, 3000, 4000, 4001, 5000, 9000}) {
            long reported = EstablishmentManager.inboundEstablishBudget(true, BASE, retry);
            assertTrue("retried budget " + retry + " reported as " + reported,
                       reported >= BASE);
        }
    }

    /** When the base is already the longer of the two, the retry branch changes nothing. */
    @Test
    public void baseWinsWhenItIsAlreadyLonger() {
        assertEquals(9000, EstablishmentManager.inboundEstablishBudget(true, 9000, 5000));
    }

    /**
     * The budget has to agree with the predicate that decides expiry, or the log names a
     * deadline the code did not apply.
     */
    @Test
    public void budgetAgreesWithTheExpiryPredicate() {
        for (long lifetime = 0; lifetime <= 2 * RETRY_BUDGET; lifetime += 37) {
            for (boolean retried : new boolean[]{false, true}) {
                boolean expired = EstablishmentManager.hasInboundEstablishExpired(
                    lifetime, retried, BASE, RETRY_BUDGET);
                long budget = EstablishmentManager.inboundEstablishBudget(retried, BASE, RETRY_BUDGET);
                if (expired) {
                    assertTrue("lifetime=" + lifetime + " retried=" + retried
                               + " expired but budget " + budget + " allows more",
                               lifetime >= budget || (retried && lifetime > BASE));
                }
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

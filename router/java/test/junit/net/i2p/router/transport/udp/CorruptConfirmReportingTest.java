package net.i2p.router.transport.udp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.atomic.AtomicLong;

import org.junit.Test;

/**
 * Reporting for rejected inbound SessionConfirmed messages.
 *
 * <p>This single warning was 9.2% of a full log - about 180 lines a minute - and carried
 * no reason. Two very different causes share the catch: a peer that gave up on the
 * handshake and sent SessionConfirmed anyway, which throws on an already-FAILED state and
 * is ordinary race behaviour, and a genuine key or payload rejection. They need opposite
 * responses, so the reason is now reported and repeats are counted rather than logged.
 *
 * @since 0.9.71+
 */
public class CorruptConfirmReportingTest {

    // ---- the reason string ----

    @Test
    public void reasonCarriesTypeAndMessage() {
        assertEquals("IllegalStateException: bad state",
                     EstablishmentManager.corruptConfirmReason(
                         new IllegalStateException("bad state")));
        assertEquals("IllegalArgumentException: low order key",
                     EstablishmentManager.corruptConfirmReason(
                         new IllegalArgumentException("low order key")));
    }

    @Test
    public void reasonSurvivesAMissingMessage() {
        assertEquals("IllegalStateException",
                     EstablishmentManager.corruptConfirmReason(new IllegalStateException()));
        assertEquals("IllegalStateException",
                     EstablishmentManager.corruptConfirmReason(new IllegalStateException("")));
    }

    @Test
    public void reasonHandlesNull() {
        assertEquals("unknown", EstablishmentManager.corruptConfirmReason(null));
    }

    /** Handshake exceptions can carry long chains; the log line must stay one line. */
    @Test
    public void reasonIsSingleLineAndBounded() {
        StringBuilder sb = new StringBuilder(500);
        for (int i = 0; i < 500; i++) {sb.append('x');}
        String msg = sb.toString();
        String r = EstablishmentManager.corruptConfirmReason(new IllegalStateException(msg));
        assertTrue("must not contain a newline", r.indexOf('\n') < 0 && r.indexOf('\r') < 0);
        assertTrue("must be bounded, was " + r.length(), r.length() < 160);
        assertTrue("should be truncated with an ellipsis", r.endsWith("..."));
    }

    @Test
    public void reasonCollapsesEmbeddedNewlines() {
        String r = EstablishmentManager.corruptConfirmReason(
            new IllegalStateException("line one\nline two\r\nline three"));
        assertTrue(r.indexOf('\n') < 0 && r.indexOf('\r') < 0);
    }

    // ---- the rate limiter ----

    /** Mirrors the production limiter so the arithmetic is exercised without a router. */
    private static final AtomicLong last = new AtomicLong();
    private static final AtomicLong suppressed = new AtomicLong();

    private static boolean shouldLog(long now) {
        long l = last.get();
        if (now - l < EstablishmentManager.CORRUPT_CONFIRM_LOG_INTERVAL_MS) {
            suppressed.incrementAndGet();
            return false;
        }
        last.set(now);
        return true;
    }

    @Test
    public void limiterEmitsThenSuppresses() {
        last.set(0); suppressed.set(0);
        long t0 = 1_000_000L;
        assertTrue("the first event must be reported", shouldLog(t0));
        assertFalse("an immediate repeat must be suppressed", shouldLog(t0 + 1));
        assertFalse("still inside the interval", shouldLog(t0 + 1000));
    }

    @Test
    public void limiterEmitsAgainAfterTheInterval() {
        last.set(0); suppressed.set(0);
        long t0 = 2_000_000L;
        assertTrue(shouldLog(t0));
        assertTrue("must report again once the interval elapses",
                   shouldLog(t0 + EstablishmentManager.CORRUPT_CONFIRM_LOG_INTERVAL_MS));
    }

    /** The suppression is a count, so the volume is still visible without the flood. */
    @Test
    public void suppressionCountIsRetained() {
        last.set(0); suppressed.set(0);
        long t0 = 3_000_000L;
        assertTrue(shouldLog(t0));
        for (int i = 0; i < 50; i++) {shouldLog(t0 + i);}
        assertEquals(50, suppressed.get());
    }

    @Test
    public void intervalIsOneMinute() {
        assertEquals(60 * 1000L, EstablishmentManager.CORRUPT_CONFIRM_LOG_INTERVAL_MS);
    }
}

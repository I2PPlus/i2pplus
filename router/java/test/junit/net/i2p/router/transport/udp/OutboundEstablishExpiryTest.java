package net.i2p.router.transport.udp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Outbound establishment expiry, in {@link EstablishmentManager#hasOutboundEstablishExpired}.
 *
 * <p>The outbound deadline used to be a flat {@code lifetime >= MAX_OB_ESTABLISH_TIME} with
 * no retry grace, while the inbound side could be granted up to
 * {@code IB_RETRY_SENT_MAX_TIME} once a retry had been sent. That made the direction which
 * retransmits three times faster the one held to the shorter patience, and the SSU2 token
 * exchange - one round trip more than SSU1 - runs through it.
 *
 * @since 0.9.71+
 */
public class OutboundEstablishExpiryTest {

    private static final long BASE = 5000L;
    private static final long EXTRA = 2000L;

    private static boolean expired(long lifetime, boolean retried) {
        return EstablishmentManager.hasOutboundEstablishExpired(lifetime, retried, BASE, EXTRA);
    }

    /** Before any resend the deadline is the base, unchanged. */
    @Test
    public void withoutRetryExpireAtBase() {
        assertFalse(expired(BASE - 1, false));
        assertTrue(expired(BASE, false));
        assertTrue(expired(BASE + 1, false));
    }

    /** A resend that also failed buys extra time, and the deadline moves with it. */
    @Test
    public void retryExtendsTheDeadline() {
        assertFalse("a retried attempt is still alive past the base",
                    expired(BASE, true));
        assertFalse(expired(BASE + EXTRA - 1, true));
        assertTrue("and expires at base plus the extension",
                   expired(BASE + EXTRA, true));
    }

    /** The extension is strictly additive: it can only ever lengthen the deadline. */
    @Test
    public void extensionNeverShortens() {
        for (long lifetime = 0; lifetime <= 3 * BASE; lifetime += 37) {
            assertFalse("retrying made an attempt expire sooner at " + lifetime,
                        expired(lifetime, true) && !expired(lifetime, false));
        }
    }

    /**
     * The bug this replaces, stated as a test: with a flat deadline the retry flag made no
     * difference at all, so a peer that had already missed our resend was abandoned at the
     * same instant as one that had never been spoken to.
     */
    @Test
    public void retryFlagActuallyChangesTheOutcome() {
        boolean differs = false;
        for (long lifetime = 0; lifetime <= 2 * BASE; lifetime++) {
            if (expired(lifetime, true) != expired(lifetime, false)) {
                differs = true;
                break;
            }
        }
        assertTrue("the retry flag had no effect anywhere in the range", differs);
    }

    /** A zero extension degrades to the old flat behaviour rather than misbehaving. */
    @Test
    public void zeroExtraDegradesToFlatDeadline() {
        for (long lifetime = 0; lifetime <= 2 * BASE; lifetime += 11) {
            assertEquals("lifetime=" + lifetime,
                         EstablishmentManager.hasOutboundEstablishExpired(lifetime, false, BASE, 0),
                         EstablishmentManager.hasOutboundEstablishExpired(lifetime, true, BASE, 0));
        }
    }

    /** Mirrors the inbound helper's boundary convention at the base deadline. */
    @Test
    public void baseBoundaryIsExclusiveOfOneMillisecond() {
        assertFalse(expired(BASE - 1, false));
        assertTrue(expired(BASE, false));
    }

    /**
     * The window in which the two directions disagree is exactly the extension: a peer
     * beyond the base but short of base+extra is still alive outbound only.
     */
    @Test
    public void disagreementWindowIsExactlyTheExtension() {
        for (long lifetime = 0; lifetime <= 2 * BASE; lifetime++) {
            boolean differs = expired(lifetime, true) != expired(lifetime, false);
            boolean expected = lifetime >= BASE && lifetime < BASE + EXTRA;
            assertEquals("lifetime=" + lifetime, expected, differs);
        }
    }
}

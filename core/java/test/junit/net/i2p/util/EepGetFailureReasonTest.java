package net.i2p.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;
import org.junit.Test;

/**
 * The reason string EepGet prints for a failed attempt. A bare "-> null"
 * carries no information, so a message-less timeout names the inactivity
 * budget that expired, and any other message-less exception falls back to
 * its class name.
 *
 * @since 0.9.72+
 */
public class EepGetFailureReasonTest {

    private static final long THIRTY_S = 30 * 1000L;
    private static final long FIVE_MIN = 5 * 60 * 1000L;

    // ---- failureReason ------------------------------------------------------

    @Test
    public void messageUsedWhenPresent() {
        assertEquals("Connection timed out: SYN not acknowledged",
                     EepGet.failureReason(new IOException("Connection timed out: SYN not acknowledged"), THIRTY_S));
    }

    @Test
    public void bareTimeoutNamesTheBudget() {
        // The common case behind thousands of "Transfer failed -> null" lines:
        // the JDK throws SocketTimeoutException with no message at all.
        assertEquals("SocketTimeoutException: no data for 30s",
                     EepGet.failureReason(new SocketTimeoutException(), THIRTY_S));
        assertEquals("SocketTimeoutException: no data for 300s",
                     EepGet.failureReason(new SocketTimeoutException(), FIVE_MIN));
    }

    @Test
    public void timeoutWithAMessageKeepsIt() {
        assertEquals("Read timed out",
                     EepGet.failureReason(new SocketTimeoutException("Read timed out"), THIRTY_S));
    }

    @Test
    public void otherBareExceptionFallsBackToClassName() {
        assertEquals("InterruptedIOException",
                     EepGet.failureReason(new InterruptedIOException(), THIRTY_S));
        assertEquals("IOException", EepGet.failureReason(new IOException(""), THIRTY_S));
    }

    @Test
    public void reasonIsNeverEmpty() {
        // An anonymous class has no simple name; the full name must be used.
        String reason = EepGet.failureReason(new IOException() {}, THIRTY_S);
        assertFalse(reason.isEmpty());
    }

    // ---- seconds ------------------------------------------------------------

    @Test
    public void wholeSecondsRenderAsSeconds() {
        assertEquals("30s", EepGet.seconds(THIRTY_S));
        assertEquals("1s", EepGet.seconds(1000L));
        assertEquals("300s", EepGet.seconds(FIVE_MIN));
    }

    @Test
    public void subSecondAndPartialSecondKeepMillis() {
        assertEquals("900ms", EepGet.seconds(900L));
        assertEquals("1500ms", EepGet.seconds(1500L));
        assertEquals("0ms", EepGet.seconds(0L));
    }
}

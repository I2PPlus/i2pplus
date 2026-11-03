package net.i2p.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.ConnectException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import org.junit.Test;

/**
 * The reason string EepGet prints for a failed fetch, and the URL label that
 * sits beside it. A bare "-&gt; null" carries no information, and a class name
 * is a fact the reader has to look up, so a message-less socket exception is
 * named in words. A message is used as supplied, cut to a length that keeps one
 * log line one line.
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
        assertEquals("Timed out with no data for 30s",
                     EepGet.failureReason(new SocketTimeoutException(), THIRTY_S));
        assertEquals("Timed out with no data for 300s",
                     EepGet.failureReason(new SocketTimeoutException(), FIVE_MIN));
    }

    @Test
    public void timeoutWithAMessageKeepsIt() {
        assertEquals("Read timed out",
                     EepGet.failureReason(new SocketTimeoutException("Read timed out"), THIRTY_S));
    }

    @Test
    public void bareSocketExceptionsNamedInWords() {
        // 47 of 98 failures in one 4-minute log were a bare InterruptedIOException
        // from our own watchdog; the class name told the reader nothing.
        assertEquals("Interrupted", EepGet.failureReason(new InterruptedIOException(), THIRTY_S));
        assertEquals("Host not found", EepGet.failureReason(new UnknownHostException(), THIRTY_S));
        assertEquals("Connect failed", EepGet.failureReason(new ConnectException(), THIRTY_S));
        assertEquals("Socket error", EepGet.failureReason(new SocketException(), THIRTY_S));
    }

    @Test
    public void otherBareExceptionFallsBackToClassName() {
        assertEquals("IOException", EepGet.failureReason(new IOException(""), THIRTY_S));
        assertEquals("ArrayIndexOutOfBoundsException",
                     EepGet.failureReason(new ArrayIndexOutOfBoundsException(), THIRTY_S));
    }

    @Test
    public void reasonIsNeverEmpty() {
        // An anonymous class has no simple name; the full name must be used.
        String reason = EepGet.failureReason(new IOException() {}, THIRTY_S);
        assertFalse(reason.isEmpty());
    }

    @Test
    public void longMessageIsCut() {
        // A runaway redirect chain puts its whole URL chain in the message.
        StringBuilder buf = new StringBuilder();
        for (int i = 0; i < 40; i++) {
            buf.append("https://example.invalid/");
        }
        String reason = EepGet.failureReason(new IOException(buf.toString()), THIRTY_S);
        assertEquals(123, reason.length());
        assertTrue(reason.endsWith("..."));
        assertTrue(reason.startsWith("https://example.invalid/"));
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

    // ---- urlLabel -----------------------------------------------------------

    @Test
    public void schemeDroppedAndBracketed() {
        // EepGet is HTTP-only, so the scheme is on every entry and identifies
        // nothing; the brackets mark the value as a destination.
        assertEquals("[notbob.i2p/graphs/cats.txt]", EepGet.urlLabel("http://notbob.i2p/graphs/cats.txt"));
        assertEquals("[proxy.example/path]", EepGet.urlLabel("https://proxy.example/path"));
    }

    @Test
    public void infoHashStillRedacted() {
        assertEquals("[tracker.example/a?...]", EepGet.urlLabel("http://tracker.example/a?info_hash=%06%fd&x=1"));
    }

    @Test
    public void urlWithoutASchemePassesThrough() {
        assertEquals("[plain.example/path]", EepGet.urlLabel("plain.example/path"));
    }
}

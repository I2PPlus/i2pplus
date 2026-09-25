package net.i2p.i2ptunnel;

import static org.junit.Assert.*;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.Socket;
import java.util.concurrent.atomic.AtomicBoolean;
import net.i2p.client.streaming.I2PSocket;
import org.junit.Test;

/**
 * Unit tests for the no-data failure decision behind the "empty response"
 * bug class: a transfer that completes without any upstream bytes must signal
 * its failure callback so the HTTP client proxy can write a 5xx to the browser
 * instead of closing the socket with nothing (which the browser surfaces as
 * {@code NS_ERROR_NET_EMPTY_RESPONSE}).
 *
 * @since 0.9.62
 */
public class I2PTunnelRunnerNoDataTest {

    @Test
    public void testFiresWhenNothingReceived() {
        assertTrue(I2PTunnelRunner.shouldFireNoDataFailure(0L, false));
    }

    @Test
    public void testFiresEvenWithSentBody() {
        // A POST body was sent upstream, but no response bytes came back:
        // that is still an empty/failed transfer.
        assertTrue(I2PTunnelRunner.shouldFireNoDataFailure(0L, false));
    }

    @Test
    public void testDoesNotFireWhenUpstreamBytesReceived() {
        assertFalse(I2PTunnelRunner.shouldFireNoDataFailure(1L, false));
        assertFalse(I2PTunnelRunner.shouldFireNoDataFailure(1024L, false));
    }

    @Test
    public void testDoesNotFireWhenAlreadyHandled() {
        assertFalse(I2PTunnelRunner.shouldFireNoDataFailure(0L, true));
    }

    @Test
    public void testBothGuardConditionsTogether() {
        assertFalse(I2PTunnelRunner.shouldFireNoDataFailure(10L, true));
        assertFalse(I2PTunnelRunner.shouldFireNoDataFailure(0L, true));
    }

    /** "GET / HTTP/1.1\r\nHost: x\r\n\r\n" */
    private static byte[] get() {
        return "GET / HTTP/1.1\r\nHost: x\r\n\r\n".getBytes();
    }

    /** "HEAD /path HTTP/1.1\r\n\r\n" */
    private static byte[] head() {
        return "HEAD /path HTTP/1.1\r\n\r\n".getBytes();
    }

    /** "POST /submit HTTP/1.1\r\nContent-Length: 3\r\n\r\nabc" */
    private static byte[] post() {
        return "POST /submit HTTP/1.1\r\nContent-Length: 3\r\n\r\nabc".getBytes();
    }

    /** "PUT /r HTTP/1.1\r\nContent-Length: 0\r\n\r\n" */
    private static byte[] put() {
        return "PUT /r HTTP/1.1\r\nContent-Length: 0\r\n\r\n".getBytes();
    }

    @Test
    public void testRetryableGet() {
        assertTrue(I2PTunnelRunner.isRetryableRequest(get()));
    }

    @Test
    public void testRetryableHead() {
        assertTrue(I2PTunnelRunner.isRetryableRequest(head()));
    }

    @Test
    public void testNotRetryablePost() {
        assertFalse(I2PTunnelRunner.isRetryableRequest(post()));
    }

    @Test
    public void testNotRetryablePut() {
        assertFalse(I2PTunnelRunner.isRetryableRequest(put()));
    }

    @Test
    public void testNotRetryableEmptyOrNull() {
        assertFalse(I2PTunnelRunner.isRetryableRequest(new byte[0]));
        assertFalse(I2PTunnelRunner.isRetryableRequest(null));
        assertFalse(I2PTunnelRunner.isRetryableRequest("DELETE /r HTTP/1.1\r\n\r\n".getBytes()));
    }

    @Test
    public void testRetryableCaseInsensitive() {
        assertTrue(I2PTunnelRunner.isRetryableRequest("get / HTTP/1.1\r\n\r\n".getBytes()));
        assertTrue(I2PTunnelRunner.isRetryableRequest("Get / HTTP/1.1\r\n\r\n".getBytes()));
        assertTrue(I2PTunnelRunner.isRetryableRequest("HEAD / HTTP/1.1\r\n\r\n".getBytes()));
        assertTrue(I2PTunnelRunner.isRetryableRequest("hEaD / HTTP/1.1\r\n\r\n".getBytes()));
    }

    @Test
    public void testShorterThanMethodTokenNotRetryable() {
        assertFalse(I2PTunnelRunner.isRetryableRequest("GE".getBytes()));
        assertFalse(I2PTunnelRunner.isRetryableRequest("G".getBytes()));
    }

    /** Reconnect when a truly empty, retryable, callback-wired transfer completes. */
    @Test
    public void testReconnectEmptyResponse() {
        assertTrue(I2PTunnelRunner.shouldReconnectEmptyResponse(0L, true, true));
    }

    /** A real upstream response (a received 502 that failed to flush to the browser) must NOT retry. */
    @Test
    public void testNoReconnectWhenResponseReceivedEvenIfWriteFailed() {
        // totalReceived is incremented before the browser write, so a response
        // whose write threw (Pipe closed) is still a received response: do not
        // re-drive it against the outproxy.
        assertFalse(I2PTunnelRunner.shouldReconnectEmptyResponse(93L, true, true));
    }

    @Test
    public void testNoReconnectWithoutCallback() {
        assertFalse(I2PTunnelRunner.shouldReconnectEmptyResponse(0L, false, true));
    }

    @Test
    public void testNoReconnectForNonRetryableRequest() {
        // POST/PUT must never be re-sent even when empty and callback is wired.
        assertFalse(I2PTunnelRunner.shouldReconnectEmptyResponse(0L, true, false));
    }

    // ---------- shouldResumeIncompleteBody ----------

    @Test
    public void testResumeIncompleteBody() {
        assertTrue(I2PTunnelRunner.shouldResumeIncompleteBody(100L, 1000L, true, true, true));
    }

    @Test
    public void testResumeFromZeroBodyAfterHeaders() {
        // Headers written, Content-Length known, no body byte yet — still resume.
        assertTrue(I2PTunnelRunner.shouldResumeIncompleteBody(0L, 1000L, true, true, true));
    }

    @Test
    public void testNoResumeWhenBodyComplete() {
        assertFalse(I2PTunnelRunner.shouldResumeIncompleteBody(1000L, 1000L, true, true, true));
        assertFalse(I2PTunnelRunner.shouldResumeIncompleteBody(1001L, 1000L, true, true, true));
    }

    @Test
    public void testNoResumeWithoutContentLength() {
        assertFalse(I2PTunnelRunner.shouldResumeIncompleteBody(100L, -1L, true, true, true));
        assertFalse(I2PTunnelRunner.shouldResumeIncompleteBody(100L, 0L, true, true, true));
    }

    @Test
    public void testNoResumeWithoutCallbackOrRetryable() {
        assertFalse(I2PTunnelRunner.shouldResumeIncompleteBody(100L, 1000L, false, true, true));
        assertFalse(I2PTunnelRunner.shouldResumeIncompleteBody(100L, 1000L, true, false, true));
    }

    @Test
    public void testNoResumeWhenCannotRangeResume() {
        // gzip decode, chunked, or headers not yet written — splice unsafe.
        assertFalse(I2PTunnelRunner.shouldResumeIncompleteBody(100L, 1000L, true, true, false));
    }

    // ---------- shouldFallbackFullBody ----------

    @Test
    public void testFallbackFullBodyWhenIncomplete() {
        assertTrue(I2PTunnelRunner.shouldFallbackFullBody(3L, 10L));
        assertTrue(I2PTunnelRunner.shouldFallbackFullBody(0L, 10L));
    }

    @Test
    public void testNoFallbackWhenBodyComplete() {
        assertFalse(I2PTunnelRunner.shouldFallbackFullBody(10L, 10L));
        assertFalse(I2PTunnelRunner.shouldFallbackFullBody(11L, 10L));
    }

    @Test
    public void testNoFallbackWithoutContentLength() {
        assertFalse(I2PTunnelRunner.shouldFallbackFullBody(3L, -1L));
        assertFalse(I2PTunnelRunner.shouldFallbackFullBody(3L, 0L));
    }

    @Test
    public void testNoFallbackOnNegativeProgress() {
        assertFalse(I2PTunnelRunner.shouldFallbackFullBody(-1L, 10L));
    }

    // ---------- withoutRangeHeader ----------

    @Test
    public void testWithoutRangeHeaderStripsExistingRange() {
        byte[] req = "GET /f HTTP/1.1\r\nRange: bytes=0-1023\r\nHost: x\r\n\r\n".getBytes();
        byte[] out = I2PTunnelRunner.withoutRangeHeader(req);
        String s = new String(out);
        assertFalse(s.contains("Range:"));
        assertTrue(s.startsWith("GET /f HTTP/1.1\r\n"));
        assertTrue(s.contains("Host: x\r\n"));
        assertTrue(s.endsWith("\r\n\r\n"));
    }

    @Test
    public void testWithoutRangeHeaderNoRangeReturnsOriginal() {
        byte[] req = GET_REQ_BYTES;
        assertSame(req, I2PTunnelRunner.withoutRangeHeader(req));
    }

    @Test
    public void testWithoutRangeHeaderNullSafe() {
        assertNull(I2PTunnelRunner.withoutRangeHeader(null));
    }

    @Test
    public void testWithoutRangeHeaderNoTerminatorReturnsOriginal() {
        byte[] req = "GET / HTTP/1.1\r\nHost: x".getBytes();
        assertSame(req, I2PTunnelRunner.withoutRangeHeader(req));
    }

    @Test
    public void testWithoutRangeHeaderStillRetryableAsGet() {
        byte[] req = "GET /f HTTP/1.1\r\nRange: bytes=0-1023\r\nHost: x\r\n\r\n".getBytes();
        assertTrue(I2PTunnelRunner.isRetryableRequest(I2PTunnelRunner.withoutRangeHeader(req)));
    }

    // ---------- withRangeHeader ----------

    private static final String GET_REQ = "GET /installers/i2pinstall.exe HTTP/1.1\r\nHost: skank.i2p\r\n\r\n";
    private static final byte[] GET_REQ_BYTES = GET_REQ.getBytes();

    @Test
    public void testWithRangeHeaderInsertsBeforeBlankLine() {
        byte[] out = I2PTunnelRunner.withRangeHeader(GET_REQ.getBytes(), 45294029L);
        String s = new String(out);
        assertTrue(s.endsWith("\r\n\r\n"));
        assertTrue(s.contains("\r\nRange: bytes=45294029-\r\n\r\n"));
        assertTrue(s.startsWith("GET /installers/i2pinstall.exe HTTP/1.1\r\n"));
        assertTrue(s.contains("Host: skank.i2p\r\n"));
    }

    @Test
    public void testWithRangeHeaderZeroLeavesRequestUnchanged() {
        byte[] req = GET_REQ.getBytes();
        assertSame(req, I2PTunnelRunner.withRangeHeader(req, 0L));
        assertSame(req, I2PTunnelRunner.withRangeHeader(req, -5L));
    }

    @Test
    public void testWithRangeHeaderNullSafe() {
        assertNull(I2PTunnelRunner.withRangeHeader(null, 10L));
    }

    @Test
    public void testWithRangeHeaderReplacesExistingRange() {
        byte[] req = "GET /f HTTP/1.1\r\nRange: bytes=0-1023\r\nHost: x\r\n\r\n".getBytes();
        byte[] out = I2PTunnelRunner.withRangeHeader(req, 999L);
        String s = new String(out);
        assertTrue(s.contains("Range: bytes=999-\r\n"));
        assertFalse(s.contains("bytes=0-1023"));
        assertEquals(1, countOccurrences(s, "Range:"));
    }

    @Test
    public void testWithRangeHeaderNoTerminatorReturnsOriginal() {
        byte[] req = "GET / HTTP/1.1\r\nHost: x".getBytes();
        assertSame(req, I2PTunnelRunner.withRangeHeader(req, 10L));
    }

    @Test
    public void testWithRangeHeaderStillRetryableAsGet() {
        byte[] out = I2PTunnelRunner.withRangeHeader(GET_REQ.getBytes(), 42L);
        assertTrue(I2PTunnelRunner.isRetryableRequest(out));
    }

    private static int countOccurrences(String s, String sub) {
        int n = 0;
        int i = 0;
        while ((i = s.indexOf(sub, i)) >= 0) {n++; i += sub.length();}
        return n;
    }

    // ---------- initial-response deadline ----------

    /** Anchor value used by the deadline tests; arbitrary epoch-ms. */
    private static final long T0 = 1_000_000L;

    @Test
    public void testInitialResponseNotExpiredBeforeWindow() {
        long timeout = I2PTunnelRunner.INITIAL_RESPONSE_TIMEOUT_MS;
        assertFalse(I2PTunnelRunner.initialResponseExpired(T0, 0, T0 + timeout - 1, timeout));
    }

    @Test
    public void testInitialResponseExpiresAtWindowBoundary() {
        long timeout = I2PTunnelRunner.INITIAL_RESPONSE_TIMEOUT_MS;
        assertTrue(I2PTunnelRunner.initialResponseExpired(T0, 0, T0 + timeout, timeout));
        assertTrue(I2PTunnelRunner.initialResponseExpired(T0, 0, T0 + timeout + 1, timeout));
    }

    @Test
    public void testInitialResponseDeadlineSlidesWithAnchor() {
        long timeout = I2PTunnelRunner.INITIAL_RESPONSE_TIMEOUT_MS;
        // Request write slid forward: what looked late against the old anchor
        // is inside the window against the new one (slow POST upload case).
        assertFalse(I2PTunnelRunner.initialResponseExpired(T0 + timeout, 0, T0 + timeout, timeout));
    }

    @Test
    public void testFirstByteRetiresInitialResponseDeadline() {
        long timeout = I2PTunnelRunner.INITIAL_RESPONSE_TIMEOUT_MS;
        assertFalse(I2PTunnelRunner.initialResponseExpired(T0, T0 + 1, T0 + 10 * timeout, timeout));
    }

    @Test
    public void testNoRequestWrittenNeverExpires() {
        long timeout = I2PTunnelRunner.INITIAL_RESPONSE_TIMEOUT_MS;
        assertFalse(I2PTunnelRunner.initialResponseExpired(0, 0, T0 + 10 * timeout, timeout));
    }

    @Test
    public void testNonPositiveTimeoutNeverExpires() {
        assertFalse(I2PTunnelRunner.initialResponseExpired(T0, 0, T0 + 1_000_000, 0));
        assertFalse(I2PTunnelRunner.initialResponseExpired(T0, 0, T0 + 1_000_000, -1));
    }

    @Test
    public void testDeadlineClosesSocketOncePerAttempt() {
        long saved = I2PTunnelRunner.initialResponseTimeoutMs;
        try {
            final AtomicBoolean closed = new AtomicBoolean();
            I2PTunnelRunner r = newRunner(closed);
            long timeout = I2PTunnelRunner.INITIAL_RESPONSE_TIMEOUT_MS;
            r.noteRequestWritten(T0);
            assertFalse(r.checkInitialResponseDeadline(T0 + timeout - 1));
            assertFalse(closed.get());
            assertTrue(r.checkInitialResponseDeadline(T0 + timeout));
            assertTrue(closed.get());
            // Latched: subsequent ticks must not warn or close again.
            assertFalse(r.checkInitialResponseDeadline(T0 + 2 * timeout));
            assertTrue(closed.get());
            // An empty-response re-send restarts the attempt; it may fire again.
            r.noteRequestWritten(T0 + 2 * timeout);
            assertFalse(r.checkInitialResponseDeadline(T0 + 2 * timeout + timeout - 1));
            assertTrue(r.checkInitialResponseDeadline(T0 + 3 * timeout));
        } finally {
            I2PTunnelRunner.initialResponseTimeoutMs = saved;
        }
    }

    @Test
    public void testFirstBytePreventsSocketClose() {
        final AtomicBoolean closed = new AtomicBoolean();
        I2PTunnelRunner r = newRunner(closed);
        r.noteRequestWritten(T0);
        r.noteFirstResponseByte();
        assertFalse(r.checkInitialResponseDeadline(T0 + 10 * I2PTunnelRunner.INITIAL_RESPONSE_TIMEOUT_MS));
        assertFalse(closed.get());
    }

    @Test
    public void testDisabledTimeoutNeverClosesSocket() {
        long saved = I2PTunnelRunner.initialResponseTimeoutMs;
        try {
            I2PTunnelRunner.initialResponseTimeoutMs = 0;
            final AtomicBoolean closed = new AtomicBoolean();
            I2PTunnelRunner r = newRunner(closed);
            r.noteRequestWritten(T0);
            assertFalse(r.checkInitialResponseDeadline(T0 + 10_000_000));
            assertFalse(closed.get());
        } finally {
            I2PTunnelRunner.initialResponseTimeoutMs = saved;
        }
    }

    @Test
    public void testDeadlineScopedToHttpRunner() {
        final AtomicBoolean closed = new AtomicBoolean();
        I2PTunnelRunner base = newRunner(closed);
        assertFalse(base.trackInitialResponseDeadline());
        I2PTunnelRunner http = new I2PTunnelHTTPClientRunner(new Socket(), mockI2PSocket(closed),
                new Object(), null, null, null, false, false, false);
        assertTrue(http.trackInitialResponseDeadline());
    }

    /**
     * Construct a never-started base runner around a mock I2PSocket whose
     * close() flips the supplied flag.
     */
    private static I2PTunnelRunner newRunner(final AtomicBoolean closed) {
        return new I2PTunnelRunner(new Socket(), mockI2PSocket(closed), new Object(),
                                   null, null, null, null, false, false);
    }

    private static I2PSocket mockI2PSocket(final AtomicBoolean closed) {
        return (I2PSocket) Proxy.newProxyInstance(I2PSocket.class.getClassLoader(),
                new Class<?>[]{I2PSocket.class}, new InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, Method m, Object[] args) {
                        if ("close".equals(m.getName())) {
                            closed.set(true);
                            return null;
                        }
                        Class<?> rt = m.getReturnType();
                        if (rt == boolean.class) {return Boolean.FALSE;}
                        if (rt == int.class) {return Integer.valueOf(0);}
                        if (rt == long.class) {return Long.valueOf(0);}
                        return null;
                    }
                });
    }
}

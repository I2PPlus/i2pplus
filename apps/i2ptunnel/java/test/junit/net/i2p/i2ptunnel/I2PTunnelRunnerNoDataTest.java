package net.i2p.i2ptunnel;

import static org.junit.Assert.*;

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
}

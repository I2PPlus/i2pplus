package net.i2p.i2ptunnel;

import static org.junit.Assert.*;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;

import net.i2p.i2ptunnel.util.LimitOutputStream.DoneCallback;

/**
 * Tests the header parsing, keepalive, and compression decisions of
 * HTTPResponseOutputStream, exercising the writeHeader() helpers.
 *
 * @since 0.9.70+
 */
public class HTTPResponseOutputStreamTest {

    private static final String RESPONSE_LINE = "HTTP/1.1 200 OK\r\n";
    private static final String CONNECTION_CLOSE = "Connection: close\r\n";

    /** Plain stream, no keepalive. */
    private static HTTPResponseOutputStream newPlain(ByteArrayOutputStream baos) {
        return new HTTPResponseOutputStream(baos);
    }

    /** Keepalive-enabled stream with a counting callback. */
    private static HTTPResponseOutputStream newKeepAlive(ByteArrayOutputStream baos, AtomicInteger done) {
        return new HTTPResponseOutputStream(baos, true, true, false, new DoneCallback() {
            public void streamDone() {
                done.incrementAndGet();
            }
        });
    }

    @Test
    public void testPlainConnectionClose() throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        HTTPResponseOutputStream out = newPlain(baos);
        out.write((RESPONSE_LINE + "Content-Length: 5\r\n\r\nhello").getBytes());
        assertEquals(RESPONSE_LINE + "Content-Length: 5\r\n" + CONNECTION_CLOSE + "\r\nhello", baos.toString());
        assertFalse(out.getKeepAliveIn());
        assertFalse(out.getKeepAliveOut());
    }

    @Test
    public void testKeepAliveWithContentLength() throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        AtomicInteger done = new AtomicInteger();
        HTTPResponseOutputStream out = newKeepAlive(baos, done);
        out.write((RESPONSE_LINE + "Content-Length: 5\r\n\r\n").getBytes());
        assertEquals(0, done.get());
        out.write("ab".getBytes());
        out.write("cde".getBytes());
        assertEquals(RESPONSE_LINE + "Content-Length: 5\r\n\r\nabcde", baos.toString());
        assertEquals(1, done.get());
        assertTrue(out.getKeepAliveIn());
        assertTrue(out.getKeepAliveOut());
    }

    @Test
    public void testNoLengthDisablesKeepAlive() throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        AtomicInteger done = new AtomicInteger();
        HTTPResponseOutputStream out = newKeepAlive(baos, done);
        out.write((RESPONSE_LINE + "\r\n").getBytes());
        assertEquals(RESPONSE_LINE + CONNECTION_CLOSE + "\r\n", baos.toString());
        assertFalse(out.getKeepAliveIn());
        assertFalse(out.getKeepAliveOut());
        assertEquals(0, done.get());
    }

    @Test
    public void testChunkedKeepAlive() throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        AtomicInteger done = new AtomicInteger();
        HTTPResponseOutputStream out = newKeepAlive(baos, done);
        out.write((RESPONSE_LINE + "Transfer-Encoding: chunked\r\n\r\n").getBytes());
        assertTrue(out.getKeepAliveIn());
        out.write("5\r\nhello\r\n0\r\n\r\n".getBytes());
        assertEquals(RESPONSE_LINE + "Transfer-Encoding: chunked\r\n\r\n5\r\nhello\r\n0\r\n\r\n", baos.toString());
        assertEquals(1, done.get());
    }

    @Test
    public void testHeadResponseCallbackImmediate() throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        AtomicInteger done = new AtomicInteger();
        HTTPResponseOutputStream out = new HTTPResponseOutputStream(baos, true, true, true, new DoneCallback() {
            public void streamDone() {
                done.incrementAndGet();
            }
        });
        out.write((RESPONSE_LINE + "\r\n").getBytes());
        assertEquals(RESPONSE_LINE + "\r\n", baos.toString());
        assertEquals(1, done.get());
        assertTrue(out.getKeepAliveIn());
    }

    @Test
    public void testStatus204NoData() throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        AtomicInteger done = new AtomicInteger();
        HTTPResponseOutputStream out = newKeepAlive(baos, done);
        out.write(("HTTP/1.1 204 No Content\r\n\r\n").getBytes());
        assertEquals(1, done.get());
        assertTrue(out.getKeepAliveIn());
    }

    @Test
    public void testHttp10DisablesKeepAlive() throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        AtomicInteger done = new AtomicInteger();
        HTTPResponseOutputStream out = newKeepAlive(baos, done);
        out.write(("HTTP/1.0 200 OK\r\nContent-Length: 3\r\n\r\nabc").getBytes());
        assertEquals("HTTP/1.0 200 OK\r\nContent-Length: 3\r\n" + CONNECTION_CLOSE + "\r\nabc", baos.toString());
        assertFalse(out.getKeepAliveIn());
        assertFalse(out.getKeepAliveOut());
        assertEquals(0, done.get());
    }

    @Test
    public void testConnectionKeepAliveHeaderDropped() throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        AtomicInteger done = new AtomicInteger();
        HTTPResponseOutputStream out = newKeepAlive(baos, done);
        out.write((RESPONSE_LINE + "Connection: keep-alive\r\nContent-Length: 3\r\n\r\nabc").getBytes());
        assertEquals(RESPONSE_LINE + "Content-Length: 3\r\n\r\nabc", baos.toString());
        assertFalse(out.getKeepAliveIn());
        assertTrue(out.getKeepAliveOut());
        assertEquals(0, done.get());
    }

    @Test
    public void testConnectionUpgradePassedThrough() throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        HTTPResponseOutputStream out = newPlain(baos);
        out.write(("HTTP/1.1 101 Switching Protocols\r\nConnection: upgrade\r\n\r\n").getBytes());
        assertEquals("HTTP/1.1 101 Switching Protocols\r\nConnection: upgrade\r\n\r\n", baos.toString());
        assertFalse(out.getKeepAliveOut());
    }

    @Test
    public void testConnectionCloseHeaderNotDuplicated() throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        HTTPResponseOutputStream out = newPlain(baos);
        out.write((RESPONSE_LINE + "Connection: close\r\n\r\n").getBytes());
        assertEquals(RESPONSE_LINE + CONNECTION_CLOSE + "\r\n", baos.toString());
    }

    @Test
    public void testGzipContentEncodingStripped() throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        HTTPResponseOutputStream out = newPlain(baos);
        out.write((RESPONSE_LINE + "Content-Encoding: x-i2p-gzip\r\n\r\n").getBytes());
        assertEquals(RESPONSE_LINE + CONNECTION_CLOSE + "\r\n", baos.toString());
    }

    @Test
    public void testProxyConnectionStripped() throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        HTTPResponseOutputStream out = newPlain(baos);
        out.write((RESPONSE_LINE + "Proxy-Connection: keep-alive\r\n\r\n").getBytes());
        assertEquals(RESPONSE_LINE + CONNECTION_CLOSE + "\r\n", baos.toString());
    }

    @Test
    public void testProxyAuthenticateStripped() throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        HTTPResponseOutputStream out = newPlain(baos);
        out.write(("HTTP/1.1 407 Proxy Authentication Required\r\n" +
                   "Proxy-Authenticate: Basic realm=\"x\"\r\n\r\n").getBytes());
        assertEquals("HTTP/1.1 407 Proxy Authentication Required\r\n" + CONNECTION_CLOSE + "\r\n", baos.toString());
    }

    @Test
    public void testI2PDomainCookieStripped() throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        HTTPResponseOutputStream out = newPlain(baos);
        out.write((RESPONSE_LINE + "Set-Cookie: a=b; domain=.b32.i2p\r\n\r\n").getBytes());
        assertEquals(RESPONSE_LINE + CONNECTION_CLOSE + "\r\n", baos.toString());
    }

    @Test
    public void testOtherCookiePassedThrough() throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        HTTPResponseOutputStream out = newPlain(baos);
        out.write((RESPONSE_LINE + "Set-Cookie: a=b; domain=example.com\r\n\r\n").getBytes());
        assertEquals(RESPONSE_LINE + "Set-Cookie: a=b; domain=example.com\r\n" + CONNECTION_CLOSE + "\r\n",
                     baos.toString());
    }

    @Test(expected = IOException.class)
    public void testInvalidHeaderThrows() throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        HTTPResponseOutputStream out = newPlain(baos);
        out.write((RESPONSE_LINE + ": bad\r\n\r\n").getBytes());
    }

    @Test
    public void testBadResponseLineThrows() {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        HTTPResponseOutputStream out = newPlain(baos);
        try {
            out.write("blah\r\n\r\n".getBytes());
            fail("expected IOException");
        } catch (IOException ioe) {
            assertTrue(ioe.getMessage().startsWith("Bad HTTP Response"));
        }
    }

    @Test
    public void testLfOnlyTerminator() throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        HTTPResponseOutputStream out = newPlain(baos);
        out.write("HTTP/1.1 200 OK\n\n".getBytes());
        assertEquals("HTTP/1.1 200 OK\r\n" + CONNECTION_CLOSE + "\r\n", baos.toString());
    }

    @Test(expected = IOException.class)
    public void testMaxHeaderSizeThrows() throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        HTTPResponseOutputStream out = newPlain(baos);
        byte[] big = new byte[70000];
        java.util.Arrays.fill(big, (byte) 'a');
        out.write(big);
    }
}

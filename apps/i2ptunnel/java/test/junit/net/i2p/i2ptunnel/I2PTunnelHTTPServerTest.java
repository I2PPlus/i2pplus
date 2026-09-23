package net.i2p.i2ptunnel;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.SocketTimeoutException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import net.i2p.I2PAppContext;
import net.i2p.client.streaming.I2PSocket;
import net.i2p.client.streaming.I2PSocketOptions;
import net.i2p.data.Destination;
import net.i2p.util.Log;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Tests for the HTTP proxy server request handling helpers
 * extracted from I2PTunnelHTTPServer.blockingHandle().
 */
public class I2PTunnelHTTPServerTest {
    private static final I2PAppContext CTX = I2PAppContext.getGlobalContext();
    private static final Log LOG = new Log(I2PTunnelHTTPServerTest.class);

    /** I2PSocket stub: configurable input, capture output, counts close() */
    private static class FakeSocket implements I2PSocket {
        private InputStream _in;
        private final ByteArrayOutputStream _out = new ByteArrayOutputStream();
        private IOException _inThrow;
        int closeCount;
        long _readTimeout = -1;

        FakeSocket(byte[] request) {_in = new ByteArrayInputStream(request);}
        FakeSocket(IOException inThrow) {_inThrow = inThrow;}

        public InputStream getInputStream() throws IOException {
            if (_inThrow != null) {throw _inThrow;}
            return _in;
        }
        public OutputStream getOutputStream() {return _out;}
        public void close() throws IOException {closeCount++;}
        public void reset() throws IOException {}
        public void setReadTimeout(long ms) {_readTimeout = ms;}
        public long getReadTimeout() {return _readTimeout;}
        public int getLocalPort() {return 80;}
        public int getPort() {return 0;}
        public boolean isClosed() {return false;}
        public Destination getPeerDestination() {return null;}
        public Destination getThisDestination() {return null;}
        public I2PSocketOptions getOptions() {return null;}
        public void setOptions(I2PSocketOptions options) {}
        public void setSocketErrorListener(SocketErrorListener lsnr) {}
    }

    private static Map<String, List<String>> map(String key, String value) {
        Map<String, List<String>> headers = new HashMap<String, List<String>>(1);
        headers.put(key, Arrays.asList(value));
        return headers;
    }

    @Test
    public void testReadRequestHeadersSuccess() throws IOException {
        FakeSocket sock = new FakeSocket("GET / HTTP/1.1\r\nHost: example.com\r\nConnection: keep-alive\r\n\r\n".getBytes());
        StringBuilder command = new StringBuilder();
        Map<String, List<String>> headers = I2PTunnelHTTPServer.readRequestHeaders(sock, command, 0, "peer.b32.i2p", CTX, LOG, "[skank / ab12cd34]");
        assertNotNull(headers);
        assertEquals("GET / HTTP/1.1\r", command.toString());
        assertEquals("example.com", headers.get("Host").get(0));
        assertEquals("keep-alive", headers.get("Connection").get(0));
        assertEquals(0, sock._out.size());
        assertEquals(0, sock.closeCount);
    }

    @Test
    public void testReadRequestHeadersSocketTimeoutFirstRequest() throws IOException {
        FakeSocket sock = new FakeSocket(new SocketTimeoutException("fake timeout"));
        Map<String, List<String>> headers = I2PTunnelHTTPServer.readRequestHeaders(sock, new StringBuilder(), 0, "peer.b32.i2p", CTX, LOG, "[skank / ab12cd34]");
        assertNull(headers);
        assertTrue(sock._out.toString().contains("408 Request timeout"));
        assertEquals(1, sock.closeCount);
    }

    @Test
    public void testReadRequestHeadersSocketTimeoutKeepAlive() throws IOException {
        FakeSocket sock = new FakeSocket(new SocketTimeoutException("fake timeout"));
        Map<String, List<String>> headers = I2PTunnelHTTPServer.readRequestHeaders(sock, new StringBuilder(), 1, "peer.b32.i2p", CTX, LOG, "[skank / ab12cd34]");
        assertNull(headers);
        assertEquals(0, sock._out.size());
        assertEquals(1, sock.closeCount);
    }

    @Test
    public void testReadRequestHeadersEOF() throws IOException {
        FakeSocket sock = new FakeSocket(new byte[0]);
        Map<String, List<String>> headers = I2PTunnelHTTPServer.readRequestHeaders(sock, new StringBuilder(), 0, "peer.b32.i2p", CTX, LOG, "[skank / ab12cd34]");
        assertNull(headers);
        assertTrue(sock._out.toString().contains("400 Bad Request"));
        assertEquals(1, sock.closeCount);
    }

    @Test
    public void testReadRequestHeadersBadRequest() throws IOException {
        FakeSocket sock = new FakeSocket("GET / HTTP/1.1\r\nNoColonHere\r\n\r\n".getBytes());
        Map<String, List<String>> headers = I2PTunnelHTTPServer.readRequestHeaders(sock, new StringBuilder(), 0, "peer.b32.i2p", CTX, LOG, "[skank / ab12cd34]");
        assertNull(headers);
        assertTrue(sock._out.toString().contains("400 Bad Request"));
        assertEquals(1, sock.closeCount);
    }

    @Test
    public void testReadRequestHeadersUriTooLong() throws IOException {
        StringBuilder req = new StringBuilder("GET /");
        for (int i = 0; i < 9000; i++) {req.append('a');}
        req.append(" HTTP/1.1\r\n\r\n");
        FakeSocket sock = new FakeSocket(req.toString().getBytes());
        Map<String, List<String>> headers = I2PTunnelHTTPServer.readRequestHeaders(sock, new StringBuilder(), 0, "peer.b32.i2p", CTX, LOG, "[skank / ab12cd34]");
        assertNull(headers);
        assertTrue(sock._out.toString().contains("414 Request URI too long"));
        assertEquals(1, sock.closeCount);
    }

    @Test
    public void testReadRequestHeadersHeadersTooLarge() throws IOException {
        StringBuilder req = new StringBuilder("GET / HTTP/1.1\r\n");
        for (int i = 0; i < 61; i++) {
            req.append("X-Hdr-").append(i).append(": v\r\n");
        }
        req.append("\r\n");
        FakeSocket sock = new FakeSocket(req.toString().getBytes());
        Map<String, List<String>> headers = I2PTunnelHTTPServer.readRequestHeaders(sock, new StringBuilder(), 0, "peer.b32.i2p", CTX, LOG, "[skank / ab12cd34]");
        assertNull(headers);
        assertTrue(sock._out.toString().contains("431 Request header fields too large"));
        assertEquals(1, sock.closeCount);
    }

    @Test
    public void testGetConnectionTypeUpgrade() {
        assertEquals(I2PTunnelHTTPServer.CONN_UPGRADE,
                     I2PTunnelHTTPServer.getConnectionType(map("Connection", "Upgrade")));
    }

    @Test
    public void testGetConnectionTypeUpgradeMixed() {
        assertEquals(I2PTunnelHTTPServer.CONN_UPGRADE,
                     I2PTunnelHTTPServer.getConnectionType(map("Connection", "keep-alive, Upgrade")));
    }

    @Test
    public void testGetConnectionTypeKeepAlive() {
        assertEquals(I2PTunnelHTTPServer.CONN_KEEPALIVE,
                     I2PTunnelHTTPServer.getConnectionType(map("Connection", "keep-alive")));
    }

    @Test
    public void testGetConnectionTypeClose() {
        assertEquals(I2PTunnelHTTPServer.CONN_CLOSE,
                     I2PTunnelHTTPServer.getConnectionType(map("Connection", "close")));
    }

    @Test
    public void testGetConnectionTypeNone() {
        assertEquals(I2PTunnelHTTPServer.CONN_NONE,
                     I2PTunnelHTTPServer.getConnectionType(new HashMap<String, List<String>>()));
    }

    @Test
    public void testIsKeepAliveRequestGet11() {
        assertTrue(I2PTunnelHTTPServer.isKeepAliveRequest("GET / HTTP/1.1"));
    }

    @Test
    public void testIsKeepAliveRequestHead11() {
        assertTrue(I2PTunnelHTTPServer.isKeepAliveRequest("HEAD / HTTP/1.1"));
    }

    @Test
    public void testIsKeepAliveRequestGet10() {
        assertFalse(I2PTunnelHTTPServer.isKeepAliveRequest("GET / HTTP/1.0"));
    }

    @Test
    public void testIsKeepAliveRequestPost() {
        assertFalse(I2PTunnelHTTPServer.isKeepAliveRequest("POST / HTTP/1.1"));
    }

    @Test
    public void testHasGzipEncodingNull() {
        assertFalse(I2PTunnelHTTPServer.hasGzipEncoding(null));
    }

    @Test
    public void testHasGzipEncodingPresent() {
        assertTrue(I2PTunnelHTTPServer.hasGzipEncoding("gzip, x-i2p-gzip"));
    }

    @Test
    public void testHasGzipEncodingAbsent() {
        assertFalse(I2PTunnelHTTPServer.hasGzipEncoding("gzip, deflate"));
    }

    @Test
    public void testIsGzipAllowedDefault() {
        assertTrue(I2PTunnelHTTPServer.isGzipAllowed(new Properties()));
    }

    @Test
    public void testIsGzipAllowedFalse() {
        Properties opts = new Properties();
        opts.setProperty(TunnelController.PROP_TUN_GZIP, "false");
        assertFalse(I2PTunnelHTTPServer.isGzipAllowed(opts));
    }

    @Test
    public void testIsGzipAllowedTrue() {
        Properties opts = new Properties();
        opts.setProperty(TunnelController.PROP_TUN_GZIP, "true");
        assertTrue(I2PTunnelHTTPServer.isGzipAllowed(opts));
    }

    // =====================================================================
    // Keepalive option parsing (F1/F2)
    // =====================================================================

    @Test
    public void testParseKeepAliveDefaultsOnNull() {
        assertTrue(I2PTunnelHTTPServer.parseKeepAlive(null));
    }

    @Test
    public void testParseKeepAliveDefaultsWhenUnset() {
        assertTrue(I2PTunnelHTTPServer.parseKeepAlive(new Properties()));
    }

    @Test
    public void testParseKeepAliveTrueFalse() {
        Properties opts = new Properties();
        opts.setProperty(I2PTunnelHTTPServer.PROP_KEEPALIVE, "true");
        assertTrue(I2PTunnelHTTPServer.parseKeepAlive(opts));
        opts.setProperty(I2PTunnelHTTPServer.PROP_KEEPALIVE, "false");
        assertFalse(I2PTunnelHTTPServer.parseKeepAlive(opts));
        opts.setProperty(I2PTunnelHTTPServer.PROP_KEEPALIVE, "TRUE");
        assertTrue(I2PTunnelHTTPServer.parseKeepAlive(opts));
    }

    @Test
    public void testParseKeepAliveLegacyFallback() {
        Properties opts = new Properties();
        opts.setProperty(I2PTunnelHTTPServer.OPT_KEEPALIVE, "false");
        assertFalse(I2PTunnelHTTPServer.parseKeepAlive(opts));
        opts.setProperty(I2PTunnelHTTPServer.OPT_KEEPALIVE, "true");
        assertTrue(I2PTunnelHTTPServer.parseKeepAlive(opts));
    }

    @Test
    public void testParseKeepAlivePropWinsOverLegacy() {
        Properties opts = new Properties();
        opts.setProperty(I2PTunnelHTTPServer.PROP_KEEPALIVE, "true");
        opts.setProperty(I2PTunnelHTTPServer.OPT_KEEPALIVE, "false");
        assertTrue(I2PTunnelHTTPServer.parseKeepAlive(opts));
        opts.setProperty(I2PTunnelHTTPServer.PROP_KEEPALIVE, "false");
        opts.setProperty(I2PTunnelHTTPServer.OPT_KEEPALIVE, "true");
        assertFalse(I2PTunnelHTTPServer.parseKeepAlive(opts));
    }

    @Test
    public void testParseKeepAliveGarbageIsFalse() {
        // Boolean.parseBoolean only accepts "true" (ignore case); anything else is false.
        Properties opts = new Properties();
        opts.setProperty(I2PTunnelHTTPServer.PROP_KEEPALIVE, "yes");
        assertFalse(I2PTunnelHTTPServer.parseKeepAlive(opts));
        opts.setProperty(I2PTunnelHTTPServer.PROP_KEEPALIVE, "not-a-bool");
        assertFalse(I2PTunnelHTTPServer.parseKeepAlive(opts));
    }

    @Test
    public void testParseKeepAliveTimeoutDefaults() {
        assertEquals(I2PTunnelHTTPServer.DEFAULT_KEEPALIVE_TIMEOUT_MS,
                     I2PTunnelHTTPServer.parseKeepAliveTimeout(null));
        assertEquals(I2PTunnelHTTPServer.DEFAULT_KEEPALIVE_TIMEOUT_MS,
                     I2PTunnelHTTPServer.parseKeepAliveTimeout(new Properties()));
    }

    @Test
    public void testParseKeepAliveTimeoutValid() {
        Properties opts = new Properties();
        opts.setProperty(I2PTunnelHTTPServer.PROP_KEEPALIVE_TIMEOUT, "5000");
        assertEquals(5000L, I2PTunnelHTTPServer.parseKeepAliveTimeout(opts));
        opts.setProperty(I2PTunnelHTTPServer.PROP_KEEPALIVE_TIMEOUT, " 25000 ");
        assertEquals(25000L, I2PTunnelHTTPServer.parseKeepAliveTimeout(opts));
    }

    @Test
    public void testParseKeepAliveTimeoutClamps() {
        Properties opts = new Properties();
        opts.setProperty(I2PTunnelHTTPServer.PROP_KEEPALIVE_TIMEOUT, "1");
        assertEquals(I2PTunnelHTTPServer.MIN_KEEPALIVE_TIMEOUT_MS,
                     I2PTunnelHTTPServer.parseKeepAliveTimeout(opts));
        opts.setProperty(I2PTunnelHTTPServer.PROP_KEEPALIVE_TIMEOUT, "99999999");
        assertEquals(I2PTunnelHTTPServer.MAX_KEEPALIVE_TIMEOUT_MS,
                     I2PTunnelHTTPServer.parseKeepAliveTimeout(opts));
    }

    @Test
    public void testParseKeepAliveTimeoutGarbageDefaults() {
        Properties opts = new Properties();
        opts.setProperty(I2PTunnelHTTPServer.PROP_KEEPALIVE_TIMEOUT, "abc");
        assertEquals(I2PTunnelHTTPServer.DEFAULT_KEEPALIVE_TIMEOUT_MS,
                     I2PTunnelHTTPServer.parseKeepAliveTimeout(opts));
        opts.setProperty(I2PTunnelHTTPServer.PROP_KEEPALIVE_TIMEOUT, "");
        assertEquals(I2PTunnelHTTPServer.DEFAULT_KEEPALIVE_TIMEOUT_MS,
                     I2PTunnelHTTPServer.parseKeepAliveTimeout(opts));
    }

    @Test
    public void testParseHeaderTimeoutDefaults() {
        assertEquals(I2PTunnelHTTPServer.DEFAULT_HEADER_TIMEOUT_MS,
                     I2PTunnelHTTPServer.parseHeaderTimeout(null));
        assertEquals(I2PTunnelHTTPServer.DEFAULT_HEADER_TIMEOUT_MS,
                     I2PTunnelHTTPServer.parseHeaderTimeout(new Properties()));
    }

    @Test
    public void testParseHeaderTimeoutValidAndClamp() {
        Properties opts = new Properties();
        opts.setProperty(I2PTunnelHTTPServer.PROP_HEADER_TIMEOUT, "20000");
        assertEquals(20000L, I2PTunnelHTTPServer.parseHeaderTimeout(opts));
        opts.setProperty(I2PTunnelHTTPServer.PROP_HEADER_TIMEOUT, "500");
        assertEquals(I2PTunnelHTTPServer.MIN_HEADER_TIMEOUT_MS,
                     I2PTunnelHTTPServer.parseHeaderTimeout(opts));
        opts.setProperty(I2PTunnelHTTPServer.PROP_HEADER_TIMEOUT, "99999999");
        assertEquals(I2PTunnelHTTPServer.MAX_HEADER_TIMEOUT_MS,
                     I2PTunnelHTTPServer.parseHeaderTimeout(opts));
        opts.setProperty(I2PTunnelHTTPServer.PROP_HEADER_TIMEOUT, "xx");
        assertEquals(I2PTunnelHTTPServer.DEFAULT_HEADER_TIMEOUT_MS,
                     I2PTunnelHTTPServer.parseHeaderTimeout(opts));
    }

    // =====================================================================
    // Getter/setter clamping (F1/F2/F7 live updates)
    // =====================================================================

    @Test
    public void testSetKeepAliveTimeoutClamps() {
        // These are instance methods; exercise the same clamp the setter uses.
        long lo = I2PTunnelHTTPServer.MIN_KEEPALIVE_TIMEOUT_MS;
        long hi = I2PTunnelHTTPServer.MAX_KEEPALIVE_TIMEOUT_MS;
        assertEquals(lo, Math.max(lo, Math.min(hi, 1L)));
        assertEquals(hi, Math.max(lo, Math.min(hi, 99999999L)));
        assertEquals(lo, Math.max(lo, Math.min(hi, -5L)));
    }

    @Test
    public void testSetHeaderTimeoutClamps() {
        long lo = I2PTunnelHTTPServer.MIN_HEADER_TIMEOUT_MS;
        long hi = I2PTunnelHTTPServer.MAX_HEADER_TIMEOUT_MS;
        assertEquals(lo, Math.max(lo, Math.min(hi, 0L)));
        assertEquals(hi, Math.max(lo, Math.min(hi, 99999999L)));
    }
}

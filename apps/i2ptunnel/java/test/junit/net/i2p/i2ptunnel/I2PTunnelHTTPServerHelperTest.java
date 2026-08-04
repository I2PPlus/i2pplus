package net.i2p.i2ptunnel;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
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

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

import static org.junit.Assert.*;

/**
 * Unit tests for the package-private helper methods extracted from
 * I2PTunnelHTTPServer.blockingHandle() for inproxy/referer/UA/post-throttle rejection.
 */
public class I2PTunnelHTTPServerHelperTest {
    private static final I2PAppContext CTX = I2PAppContext.getGlobalContext();
    private static final Log LOG = new Log(I2PTunnelHTTPServerHelperTest.class);

    private I2PTunnelHTTPServer _server;

    /** I2PSocket stub: configurable input, capture output, counts close() */
    private static class FakeSocket implements I2PSocket {
        private InputStream _in;
        private final ByteArrayOutputStream _out = new ByteArrayOutputStream();
        int closeCount;
        long _readTimeout = -1;

        FakeSocket(byte[] data) { _in = new ByteArrayInputStream(data); }
        FakeSocket() { _in = new ByteArrayInputStream(new byte[0]); }

        public InputStream getInputStream() { return _in; }
        public OutputStream getOutputStream() { return _out; }
        public void close() throws IOException { closeCount++; }
        public void reset() throws IOException {}
        public void setReadTimeout(long ms) { _readTimeout = ms; }
        public long getReadTimeout() { return _readTimeout; }
        public int getLocalPort() { return 80; }
        public int getPort() { return 0; }
        public boolean isClosed() { return false; }
        public Destination getPeerDestination() { return null; }
        public Destination getThisDestination() { return null; }
        public I2PSocketOptions getOptions() { return null; }
        public void setOptions(I2PSocketOptions options) {}
        public void setSocketErrorListener(SocketErrorListener lsnr) {}
        public long getLifetimeBytesSent() { return 0; }
        public long getLifetimeBytesReceived() { return 0; }
    }

    @Before
    public void setUp() throws Exception {
        _server = Mockito.mock(I2PTunnelHTTPServer.class, Mockito.CALLS_REAL_METHODS);
        Field logField = I2PTunnelServer.class.getDeclaredField("_log");
        logField.setAccessible(true);
        logField.set(_server, LOG);
    }

    @After
    public void tearDown() {
        _server = null;
    }

    private static Map<String, List<String>> headers(String key, String value) {
        Map<String, List<String>> h = new HashMap<String, List<String>>();
        h.put(key, Arrays.asList(value));
        return h;
    }

    private static Properties props(String key, String value) {
        Properties p = new Properties();
        p.setProperty(key, value);
        return p;
    }

    private boolean invokeIsInproxyRejection(Map<String, List<String>> headers,
                                              I2PSocket socket,
                                              String peerB32,
                                              Properties opts) throws Exception {
        Method m = I2PTunnelHTTPServer.class.getDeclaredMethod("isInproxyRejection",
            Map.class, I2PSocket.class, String.class, Properties.class);
        m.setAccessible(true);
        return (Boolean) m.invoke(_server, headers, socket, peerB32, opts);
    }

    private boolean invokeIsRefererRejection(Map<String, List<String>> headers,
                                              I2PSocket socket,
                                              String peerB32,
                                              Properties opts) throws Exception {
        Method m = I2PTunnelHTTPServer.class.getDeclaredMethod("isRefererRejection",
            Map.class, I2PSocket.class, String.class, Properties.class);
        m.setAccessible(true);
        return (Boolean) m.invoke(_server, headers, socket, peerB32, opts);
    }

    private boolean invokeIsUserAgentRejection(Map<String, List<String>> headers,
                                                I2PSocket socket,
                                                String peerB32,
                                                Properties opts) throws Exception {
        Method m = I2PTunnelHTTPServer.class.getDeclaredMethod("isUserAgentRejection",
            Map.class, I2PSocket.class, String.class, Properties.class);
        m.setAccessible(true);
        return (Boolean) m.invoke(_server, headers, socket, peerB32, opts);
    }

    @Test
    public void testIsInproxyRejectionDisabled() throws Exception {
        FakeSocket sock = new FakeSocket();
        Properties opts = props(OPT_REJECT_INPROXY, "false");
        assertFalse(invokeIsInproxyRejection(headers("X-Forwarded-For", "1.2.3.4"),
                                              sock, "peer.b32.i2p", opts));
        assertEquals(0, sock.closeCount);
    }

    @Test
    public void testIsInproxyRejectionNoForwardedHeaders() throws Exception {
        FakeSocket sock = new FakeSocket();
        Properties opts = props(OPT_REJECT_INPROXY, "true");
        assertFalse(invokeIsInproxyRejection(new HashMap<String, List<String>>(),
                                              sock, "peer.b32.i2p", opts));
        assertEquals(0, sock.closeCount);
    }

    @Test
    public void testIsInproxyRejectionXForwardedFor() throws Exception {
        FakeSocket sock = new FakeSocket();
        Properties opts = props(OPT_REJECT_INPROXY, "true");
        assertTrue(invokeIsInproxyRejection(headers("X-Forwarded-For", "1.2.3.4"),
                                             sock, "peer.b32.i2p", opts));
        assertEquals(1, sock.closeCount);
    }

    @Test
    public void testIsInproxyRejectionXForwardedServer() throws Exception {
        FakeSocket sock = new FakeSocket();
        Properties opts = props(OPT_REJECT_INPROXY, "true");
        assertTrue(invokeIsInproxyRejection(headers("X-Forwarded-Server", "evil"),
                                             sock, "peer.b32.i2p", opts));
        assertEquals(1, sock.closeCount);
    }

    @Test
    public void testIsInproxyRejectionForwarded() throws Exception {
        FakeSocket sock = new FakeSocket();
        Properties opts = props(OPT_REJECT_INPROXY, "true");
        assertTrue(invokeIsInproxyRejection(headers("Forwarded", "for=1.2.3.4"),
                                             sock, "peer.b32.i2p", opts));
        assertEquals(1, sock.closeCount);
    }

    @Test
    public void testIsInproxyRejectionXForwardedHost() throws Exception {
        FakeSocket sock = new FakeSocket();
        Properties opts = props(OPT_REJECT_INPROXY, "true");
        assertTrue(invokeIsInproxyRejection(headers("X-Forwarded-Host", "evil.i2p"),
                                             sock, "peer.b32.i2p", opts));
        assertEquals(1, sock.closeCount);
    }

    @Test
    public void testIsRefererRejectionDisabled() throws Exception {
        FakeSocket sock = new FakeSocket();
        Properties opts = props(OPT_REJECT_REFERER, "false");
        assertFalse(invokeIsRefererRejection(headers("Referer", "http://evil.com/"),
                                               sock, "peer.b32.i2p", opts));
        assertEquals(0, sock.closeCount);
    }

    @Test
    public void testIsRefererRejectionNoReferer() throws Exception {
        FakeSocket sock = new FakeSocket();
        Properties opts = props(OPT_REJECT_REFERER, "true");
        assertFalse(invokeIsRefererRejection(new HashMap<String, List<String>>(),
                                               sock, "peer.b32.i2p", opts));
        assertEquals(0, sock.closeCount);
    }

    @Test
    public void testIsRefererRejectionAbsoluteHttp() throws Exception {
        FakeSocket sock = new FakeSocket();
        Properties opts = props(OPT_REJECT_REFERER, "true");
        assertTrue(invokeIsRefererRejection(headers("Referer", "http://evil.com/page"),
                                             sock, "peer.b32.i2p", opts));
        assertEquals(1, sock.closeCount);
    }

    @Test
    public void testIsRefererRejectionAbsoluteHttps() throws Exception {
        FakeSocket sock = new FakeSocket();
        Properties opts = props(OPT_REJECT_REFERER, "true");
        assertTrue(invokeIsRefererRejection(headers("Referer", "https://evil.com/"),
                                             sock, "peer.b32.i2p", opts));
        assertEquals(1, sock.closeCount);
    }

    @Test
    public void testIsRefererRejectionRelativeOk() throws Exception {
        FakeSocket sock = new FakeSocket();
        Properties opts = props(OPT_REJECT_REFERER, "true");
        assertFalse(invokeIsRefererRejection(headers("Referer", "/relative/path"),
                                               sock, "peer.b32.i2p", opts));
        assertEquals(0, sock.closeCount);
    }

    @Test
    public void testIsUserAgentRejectionDisabled() throws Exception {
        FakeSocket sock = new FakeSocket();
        Properties opts = props(OPT_REJECT_USER_AGENTS, "false");
        assertFalse(invokeIsUserAgentRejection(headers("User-Agent", "Mozilla/5.0"),
                                                 sock, "peer.b32.i2p", opts));
        assertEquals(0, sock.closeCount);
    }

    @Test
    public void testIsUserAgentRejectionNoBlockList() throws Exception {
        FakeSocket sock = new FakeSocket();
        Properties opts = props(OPT_REJECT_USER_AGENTS, "true");
        assertFalse(invokeIsUserAgentRejection(headers("User-Agent", "Mozilla/5.0"),
                                                 sock, "peer.b32.i2p", opts));
        assertEquals(0, sock.closeCount);
    }

    @Test
    public void testIsUserAgentRejectionMatched() throws Exception {
        FakeSocket sock = new FakeSocket();
        Properties opts = props(OPT_REJECT_USER_AGENTS, "true");
        opts.setProperty(OPT_USER_AGENTS, "BadBot,Scrapy");
        assertTrue(invokeIsUserAgentRejection(headers("User-Agent", "Mozilla/5.0 BadBot/1.0"),
                                                 sock, "peer.b32.i2p", opts));
        assertEquals(1, sock.closeCount);
    }

    @Test
    public void testIsUserAgentRejectionNotMatched() throws Exception {
        FakeSocket sock = new FakeSocket();
        Properties opts = props(OPT_REJECT_USER_AGENTS, "true");
        opts.setProperty(OPT_USER_AGENTS, "BadBot,Scrapy");
        assertFalse(invokeIsUserAgentRejection(headers("User-Agent", "Mozilla/5.0"),
                                                 sock, "peer.b32.i2p", opts));
        assertEquals(0, sock.closeCount);
    }

    @Test
    public void testIsUserAgentRejectionNoneBlocklist() throws Exception {
        FakeSocket sock = new FakeSocket();
        Properties opts = props(OPT_REJECT_USER_AGENTS, "true");
        opts.setProperty(OPT_USER_AGENTS, "none");
        assertTrue(invokeIsUserAgentRejection(new HashMap<String, List<String>>(),
                                                 sock, "peer.b32.i2p", opts));
        assertEquals(1, sock.closeCount);
    }

    @Test
    public void testIsUserAgentRejectionMyobExcluded() throws Exception {
        FakeSocket sock = new FakeSocket();
        Properties opts = props(OPT_REJECT_USER_AGENTS, "true");
        opts.setProperty(OPT_USER_AGENTS, "BadBot,MYOB");
        assertFalse(invokeIsUserAgentRejection(headers("User-Agent", "MYOB/1.0"),
                                                 sock, "peer.b32.i2p", opts));
        assertEquals(0, sock.closeCount);
    }

    private static final String OPT_REJECT_INPROXY = "rejectInproxy";
    private static final String OPT_REJECT_REFERER = "rejectReferer";
    private static final String OPT_REJECT_USER_AGENTS = "rejectUserAgents";
    private static final String OPT_USER_AGENTS = "userAgentRejectList";
}

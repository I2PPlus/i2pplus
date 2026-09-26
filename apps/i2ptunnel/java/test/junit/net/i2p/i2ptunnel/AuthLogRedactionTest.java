package net.i2p.i2ptunnel;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.net.InetAddress;
import java.net.Socket;
import java.util.HashMap;
import java.util.Map;

import org.junit.Test;

/**
 * Unit tests for the log redaction helpers in I2PTunnelHTTPClientBase, which
 * keep credential material and attacker-supplied values out of the logs.
 *
 * @since 0.9.71+
 */
public class AuthLogRedactionTest {

    @Test
    public void testSummarizeAuthorizationNeverLogsCredentials() {
        assertEquals("null", I2PTunnelHTTPClientBase.summarizeAuthorization(null));
        String cred = "dXNlcjpwYXNz";
        assertEquals("Basic (" + cred.length() + " bytes)",
                     I2PTunnelHTTPClientBase.summarizeAuthorization("Basic " + cred));
        assertEquals("Digest (7 bytes)",
                     I2PTunnelHTTPClientBase.summarizeAuthorization("Digest abc def"));
        assertEquals("Basic (0 bytes)",
                     I2PTunnelHTTPClientBase.summarizeAuthorization("Basic"));
        assertFalse(I2PTunnelHTTPClientBase.summarizeAuthorization("Basic " + cred)
                    .contains(cred));
    }

    @Test
    public void testSummarizeAuthorizationRejectsHostileScheme() {
        assertEquals("malformed",
                     I2PTunnelHTTPClientBase.summarizeAuthorization("Basic\r\nInjected: 1"));
        assertEquals("malformed",
                     I2PTunnelHTTPClientBase.summarizeAuthorization("B\nic"));
        assertEquals("malformed",
                     I2PTunnelHTTPClientBase.summarizeAuthorization("Basic\0cred"));
        StringBuilder pad = new StringBuilder();
        for (int i = 0; i < 33; i++) {pad.append('B');}
        assertEquals("malformed",
                     I2PTunnelHTTPClientBase.summarizeAuthorization(pad.toString()));
    }

    @Test
    public void testSanitizeLogValue() {
        assertEquals("", I2PTunnelHTTPClientBase.sanitizeLogValue(null));
        assertEquals("user", I2PTunnelHTTPClientBase.sanitizeLogValue("user"));
        assertEquals("user  FAKE LOG LINE",
                     I2PTunnelHTTPClientBase.sanitizeLogValue("user\r\nFAKE LOG LINE"));
        assertEquals("a b c", I2PTunnelHTTPClientBase.sanitizeLogValue("a\rb\nc"));
        assertEquals("a b", I2PTunnelHTTPClientBase.sanitizeLogValue("a\0b"));
        StringBuilder pad = new StringBuilder();
        for (int i = 0; i < 200; i++) {pad.append('x');}
        assertEquals(64, I2PTunnelHTTPClientBase.sanitizeLogValue(pad.toString()).length());
    }

    @Test
    public void testSanitizeAuthArgsRedactsResponse() {
        Map<String, String> args = new HashMap<String, String>();
        args.put("username", "alice");
        args.put("response", "0123456789abcdef0123456789abcdef");
        Map<String, String> out = I2PTunnelHTTPClientBase.sanitizeAuthArgs(args);
        assertEquals("redacted", out.get("response"));
        assertEquals("alice", out.get("username"));
        // the original is left alone, only the copy is for logging
        assertEquals("0123456789abcdef0123456789abcdef", args.get("response"));
        assertTrue(I2PTunnelHTTPClientBase.sanitizeAuthArgs(null).isEmpty());
        Map<String, String> noResponse = new HashMap<String, String>();
        noResponse.put("username", "bob");
        assertEquals("bob", I2PTunnelHTTPClientBase.sanitizeAuthArgs(noResponse)
                     .get("username"));
    }

    @Test
    public void testAddrAndPort() throws Exception {
        assertEquals("unknown", I2PTunnelHTTPClientBase.addrAndPort(null));
        assertEquals("unknown", I2PTunnelHTTPClientBase.addrAndPort(new Socket()));
        assertEquals("127.0.0.1:4444",
                     I2PTunnelHTTPClientBase.addrAndPort(new FakeSocket("127.0.0.1", 4444)));
        // the platform expands ::1 to its full form, which must still be bracketed
        String v6 = InetAddress.getByName("::1").getHostAddress();
        assertEquals("[" + v6 + "]:1234",
                     I2PTunnelHTTPClientBase.addrAndPort(new FakeSocket("::1", 1234)));
    }

    /** Socket reporting a fixed peer address without connecting. */
    private static final class FakeSocket extends Socket {
        private final InetAddress _addr;
        private final int _port;

        FakeSocket(String addr, int port) throws Exception {
            _addr = InetAddress.getByName(addr);
            _port = port;
        }

        @Override
        public InetAddress getInetAddress() {return _addr;}

        @Override
        public int getPort() {return _port;}
    }
}

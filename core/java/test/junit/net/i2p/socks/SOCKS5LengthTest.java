package net.i2p.socks;

import static org.junit.Assert.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;

import org.junit.Test;

/**
 * Pins how the SOCKS5 client frames the fields a single octet sizes.
 *
 * <p>The username, the password and a domain name are each preceded by a one-byte
 * length, and the client wrote that byte without checking the value. A length of 256
 * went out as 0, so the proxy read a zero-length username and then took the first byte
 * of the real username as the password length and swallowed the rest of the request -
 * including the connect command - as credential bytes. What came back was a version or
 * authorization error that said nothing about the length.
 *
 * @since 0.9.72+
 */
public class SOCKS5LengthTest {

    /**
     * One instance per test method, as JUnit builds a fresh test object for each one.
     *
     * @since 0.9.72+
     */
    public SOCKS5LengthTest() {}

    /**
     * A proxy that agrees to whatever is asked: it selects username/password
     * authentication, accepts the credentials, and reports the connect as successful
     * with a four-byte address in the reply.
     *
     * @param auth whether the proxy will be asked to authenticate
     * @return the bytes the client reads back
     */
    private static ByteArrayInputStream proxyReplies(boolean auth) {
        byte[] greeting = auth ? new byte[] {5, 2} : new byte[] {5, 0};
        byte[] authReply = auth ? new byte[] {1, 0} : new byte[0];
        byte[] connectReply = new byte[] {5, 0, 0, 1, 127, 0, 0, 1, 0x20, 0x00};
        byte[] all = new byte[greeting.length + authReply.length + connectReply.length];
        System.arraycopy(greeting, 0, all, 0, greeting.length);
        System.arraycopy(authReply, 0, all, greeting.length, authReply.length);
        System.arraycopy(connectReply, 0, all, greeting.length + authReply.length,
                         connectReply.length);
        return new ByteArrayInputStream(all);
    }

    /**
     * The bytes the client wrote before it failed, as a hex-free array of the
     * greeting only: 05 then the method count then the methods.
     *
     * @param auth whether the client offered username/password
     * @return the greeting bytes, and nothing else
     */
    private static byte[] greeting(boolean auth) {
        return auth ? new byte[] {5, 2, 0, 2} : new byte[] {5, 1, 0};
    }

    /**
     * A username of exactly the largest length the protocol can carry is written with
     * that length, not a wrapped one.
     *
     * @throws IOException if the canned proxy reports a failure
     */
    @Test
    public void testA255ByteUsernameIsFramedCorrectly() throws IOException {
        StringBuilder name = new StringBuilder();
        for (int i = 0; i < 255; i++) {
            name.append('a');
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        SOCKS5Client.connect(proxyReplies(true), out, "v4.example.i2p", 80, name.toString(), "pw");
        byte[] sent = out.toByteArray();
        int at = indexOf(sent, new byte[] {1, (byte) 255});
        assertTrue("no auth version and a 255 length byte in the request", at >= 0);
        for (int i = 0; i < 255; i++) {
            assertEquals("username byte " + i, 'a', sent[at + 2 + i]);
        }
    }

    /**
     * The defect: a username that does not fit is announced as length 0 and the
     * proxy then reads the request as credential bytes. It is refused instead, and
     * nothing of the attempt reaches the wire.
     *
     * @throws IOException if the canned proxy reports an unexpected failure
     */
    @Test
    public void testAnOverLongUsernameIsRefused() throws IOException {
        StringBuilder name = new StringBuilder();
        // 128 characters, 256 bytes in UTF-8 - over the line in bytes, not in
        // characters, which is what the protocol sizes
        for (int i = 0; i < 128; i++) {
            name.append('\u00e9');
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            SOCKS5Client.connect(proxyReplies(true), out, "v4.example.i2p", 80,
                                 name.toString(), "pw");
            fail("a 256-byte username was sent");
        } catch (SOCKSException expected) {
            // expected
        }
        assertGreetingOnly(out, true);
    }

    /**
     * A password one byte too long is refused the same way.
     *
     * @throws IOException if the canned proxy reports an unexpected failure
     */
    @Test
    public void testAnOverLongPasswordIsRefused() throws IOException {
        StringBuilder pw = new StringBuilder();
        for (int i = 0; i < 256; i++) {
            pw.append('p');
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            SOCKS5Client.connect(proxyReplies(true), out, "v4.example.i2p", 80,
                                 "user", pw.toString());
            fail("a 256-byte password was sent");
        } catch (SOCKSException expected) {
            // expected
        }
        assertGreetingOnly(out, true);
    }

    /**
     * A domain name of the largest length the protocol can carry is written with that
     * length.
     *
     * @throws IOException if the canned proxy reports a failure
     */
    @Test
    public void testA255ByteHostnameIsFramedCorrectly() throws IOException {
        StringBuilder host = new StringBuilder();
        for (int i = 0; i < 255; i++) {
            host.append('b');
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        SOCKS5Client.connect(proxyReplies(false), out, host.toString(), 80, null, null);
        byte[] sent = out.toByteArray();
        int at = indexOf(sent, new byte[] {3, (byte) 255});
        assertTrue("no domain type and a 255 length byte in the request", at >= 0);
        assertEquals('b', sent[at + 2]);
        assertEquals('b', sent[at + 256]);
    }

    /**
     * The defect, on the other field: a hostname from the ipmapping. property of any
     * length is accepted and then framed wrong.
     *
     * @throws IOException if the canned proxy reports an unexpected failure
     */
    @Test
    public void testAnOverLongHostnameIsRefused() throws IOException {
        StringBuilder host = new StringBuilder();
        for (int i = 0; i < 256; i++) {
            host.append('c');
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            SOCKS5Client.connect(proxyReplies(false), out, host.toString(), 80, null, null);
            fail("a 256-byte hostname was sent");
        } catch (SOCKSException expected) {
            // expected
        }
        assertGreetingOnly(out, false);
    }

    /**
     * Assert the client wrote its greeting and nothing else, which is what a refusal
     * before the first protocol byte has to look like.
     *
     * @param out the bytes the client wrote
     * @param auth whether the client offered username/password
     */
    private static void assertGreetingOnly(ByteArrayOutputStream out, boolean auth) {
        assertArrayEquals("the refusal wrote past the greeting", greeting(auth), out.toByteArray());
    }

    /**
     * Where a byte pattern first appears in a request.
     *
     * @param haystack the bytes the client wrote
     * @param needle the pattern to find
     * @return the offset, or -1 if the pattern is not there
     */
    private static int indexOf(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    /**
     * The canned reply is read in full, so a case that passes did complete the
     * handshake rather than stopping early - checked here so the framing assertions
     * above are about a finished request.
     *
     * @throws IOException if the canned proxy reports a failure
     */
    @Test
    public void testAShortCredentialCompletesTheHandshake() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayInputStream in = proxyReplies(true);
        SOCKS5Client.connect(in, out, "v4.example.i2p", 80, "user", "pw");
        assertEquals("the client left proxy bytes unread", -1, in.read());
        byte[] sent = out.toByteArray();
        assertTrue("the request is shorter than a full handshake: " + Arrays.toString(sent),
                   sent.length > 4 + 2 + 2 + 4);
    }
}

package i2p.susi.webmail.smtp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import org.junit.Test;

/**
 * Tests the bounds on one SMTP server reply, and that a reply which never finished
 * is not taken for a reply.
 *
 * <p>The defect: the reader kept reading continuation lines for as long as the
 * server kept sending them, and stopped as soon as the connection ended. A server
 * that closed mid-reply left a code behind with no last line, so the caller read a
 * truncated reply as a command the server had accepted; an endless reply grew the
 * response without limit, and the whole of it is kept in memory and shown to the
 * user when a command fails.
 *
 * <p>No server: the socket is a stub handing out a fixed or endless stream, and the
 * private reader is called directly.
 *
 * @since 0.9.72+
 */
public class SMTPReplyLimitsTest {

    /** A reply line longer than RFC 5321 allows. */
    private static final int OVERLONG_LINE = SMTPClient.MAX_REPLY_LINE_CHARS + 88;

    /**
     * A fixture group for the SMTP reply reader; it holds no state.
     *
     * @since 0.9.72+
     */
    public SMTPReplyLimitsTest() {}

    /**
     * The ordinary case the bounds must not disturb: one line, code and text.
     *
     * @throws Exception if the private reader cannot be reached
     */
    @Test
    public void testASingleLineReplyIsAccepted() throws Exception {
        SMTPClient client = client("250 OK\r\n");
        Object reply = readReply(client);
        assertEquals(250, _int(reply, "result"));
        assertEquals("OK\r", _string(reply, "recv"));
    }

    /**
     * A multi-line reply, the shape of an EHLO capability list, ends at the line
     * whose fourth character is a space, and its lines are kept in order with the
     * code and hyphen removed.
     *
     * @throws Exception if the private reader cannot be reached
     */
    @Test
    public void testAMultiLineReplyIsAcceptedWhole() throws Exception {
        SMTPClient client = client("250-postman.i2p\r\n250-PIPELINING\r\n250 SIZE 10485760\r\n");
        Object reply = readReply(client);
        assertEquals(250, _int(reply, "result"));
        assertEquals("postman.i2p\rPIPELINING\rSIZE 10485760\r", _string(reply, "recv"));
        assertEquals("", client.error);
    }

    /**
     * The defect: a connection that ends in the middle of a multi-line reply leaves
     * a code with no last line, and that is a command the server never finished
     * answering, not one it accepted.
     *
     * @throws Exception if the private reader cannot be reached
     */
    @Test
    public void testAReplyThatEndsBeforeItsLastLineIsRefused() throws Exception {
        SMTPClient client = client("250-PIPELINING\r\n");
        assertEquals("a truncated reply was accepted", 0, _int(readReply(client), "result"));
        assertFalse("the refusal said nothing about why", client.error.isEmpty());
    }

    /**
     * A server that never sends the line that closes the reply is stopped by the
     * line count. The timeout is part of the test: a reader with no line count never
     * stops at all.
     *
     * @throws Exception if the private reader cannot be reached
     */
    @Test(timeout = 60000)
    public void testAnEndlessReplyIsRefusedByTheLineCount() throws Exception {
        SMTPClient client = client(null);
        setStream(client, endless("250-a reply that never ends\r\n"));
        assertEquals(0, _int(readReply(client), "result"));
        assertFalse(client.error.isEmpty());
    }

    /**
     * A line past RFC 5321's 512 characters is not a reply this client can show the
     * user, so it is refused rather than kept.
     *
     * @throws Exception if the private reader cannot be reached
     */
    @Test
    public void testAnOverlongReplyLineIsRefused() throws Exception {
        SMTPClient client = client("250-" + repeat('x', OVERLONG_LINE) + "\r\n");
        assertEquals(0, _int(readReply(client), "result"));
        assertFalse(client.error.isEmpty());
    }

    /**
     * A line at the length the RFC allows is kept, so the bound is the RFC's and not
     * a guess at what a server sends.
     *
     * @throws Exception if the private reader cannot be reached
     */
    @Test
    public void testAReplyLineAtTheRfcLengthIsAccepted() throws Exception {
        // the limit counts the line's CRLF, and readLine() hands back the line
        // without the line feed, so two characters of the allowance are already spent
        int text = SMTPClient.MAX_REPLY_LINE_CHARS - 4 - 2;
        SMTPClient client = client("250 " + repeat('x', text) + "\r\n");
        assertEquals(250, _int(readReply(client), "result"));
    }

    /**
     * A client whose socket hands out one fixed reply.
     *
     * @param reply the bytes the server sends, null for a caller that sets its own
     *              stream
     * @return a client that was never connected
     * @throws Exception if the private socket field is gone
     */
    private static SMTPClient client(String reply) throws Exception {
        SMTPClient client = new SMTPClient();
        if (reply != null) {
            setStream(client, new ByteArrayInputStream(reply.getBytes(StandardCharsets.UTF_8)));
        }
        return client;
    }

    /**
     * @param client the client to hand a stream to
     * @param stream what its socket will report as the connection's input
     * @throws Exception if the private socket field is gone
     */
    private static void setStream(SMTPClient client, InputStream stream) throws Exception {
        Field f = SMTPClient.class.getDeclaredField("socket");
        f.setAccessible(true);
        f.set(client, new FixedSocket(stream));
    }

    /**
     * @param client the client to read one reply from
     * @return the reader's Result, read by field name below
     * @throws Exception if the private reader cannot be reached
     */
    private static Object readReply(SMTPClient client) throws Exception {
        Method m = SMTPClient.class.getDeclaredMethod("getFullResult");
        m.setAccessible(true);
        return m.invoke(client);
    }

    /**
     * The Result class is private, so its fields are read by name.
     *
     * @param reply the Result to read
     * @param name the field to read
     * @return the value of that field
     * @throws Exception if the field is gone
     */
    private static int _int(Object reply, String name) throws Exception {
        Field f = reply.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.getInt(reply);
    }

    /**
     * @param reply the Result to read
     * @param name the field to read
     * @return the value of that field
     * @throws Exception if the field is gone
     */
    private static String _string(Object reply, String name) throws Exception {
        Field f = reply.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return (String) f.get(reply);
    }

    /**
     * Java 8 has no String.repeat().
     *
     * @param c the character to repeat
     * @param n how many times
     * @return a string of n copies of c
     */
    private static String repeat(char c, int n) {
        StringBuilder buf = new StringBuilder(n);
        for (int i = 0; i < n; i++) {
            buf.append(c);
        }
        return buf.toString();
    }

    /**
     * A stream that replays one line forever, standing for a server that never
     * stops sending continuation lines.
     *
     * @param line the line to repeat, including its terminator
     * @return a stream that never ends
     */
    private static InputStream endless(String line) {
        final byte[] bytes = line.getBytes(StandardCharsets.UTF_8);
        return new InputStream() {

            /** Where in the repeated line this stream stands. */
            private int at;

            @Override
            public int read() {
                int b = bytes[at] & 0xff;
                at = (at + 1) % bytes.length;
                return b;
            }
        };
    }

    /**
     * A socket that was never connected and hands out one stream.
     */
    private static class FixedSocket extends Socket {

        /** What this socket reports as the connection's input. */
        private final InputStream _in;

        /**
         * Hands out the stream the reader sees.
         *
         * @param in the bytes the server sends
         */
        FixedSocket(InputStream in) {
            _in = in;
        }

        @Override
        public InputStream getInputStream() {
            return _in;
        }
    }
}

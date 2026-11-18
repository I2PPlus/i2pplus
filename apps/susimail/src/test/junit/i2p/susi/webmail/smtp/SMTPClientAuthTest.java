package i2p.susi.webmail.smtp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import i2p.susi.webmail.encoding.Encoding;
import i2p.susi.webmail.encoding.EncodingFactory;
import i2p.susi.webmail.encoding.EncodingException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.Test;

/**
 * Tests the base64 an AUTH LOGIN sends, and that a command is refused before a
 * byte of it reaches the server.
 *
 * <p>The defect: the AUTH arguments were encoded with this package's MIME
 * content-transfer encoder, which by design breaks its output at 76 characters.
 * RFC 4954 wants the base64 on one line, so a credential whose UTF-8 form is long
 * enough to reach that break went out as two lines: the server read the token as
 * ending early, took the remainder as a separate invalid command, and the login
 * failed. A 64-character generated password is well past the break.
 *
 * <p>No server: the encoder is called directly, and the writer is driven with a
 * socket whose output stream is a byte counter, so a refusal can be shown to have
 * written nothing.
 *
 * @since 0.9.72+
 */
public class SMTPClientAuthTest {

    /** A password as long as a password manager generates. */
    private static final String LONG_PASSWORD = repeat('q', 100);

    /**
     * A fixture group for the AUTH LOGIN encoding; it holds no state.
     *
     * @since 0.9.72+
     */
    public SMTPClientAuthTest() {}

    /**
     * The regression: a long credential stays on one line and decodes back to
     * exactly what the form gave.
     */
    @Test
    public void testALongPasswordStaysOnOneLine() {
        String token = SMTPClient.authToken(LONG_PASSWORD);
        assertFalse("the token was wrapped onto a second line", token.contains("\r"));
        assertFalse("the token was wrapped onto a second line", token.contains("\n"));
        assertEquals(LONG_PASSWORD, utf8(Base64.getDecoder().decode(token)));
    }

    /**
     * A credential that is not ASCII is sent as its UTF-8 bytes, which is what the
     * server hashes against the account, and it still comes back as one line.
     */
    @Test
    public void testANonAsciiCredentialIsUtf8() {
        String user = "susi\u00e4r\u00fc@example.i2p";
        String token = SMTPClient.authToken(user);
        assertFalse(token.contains("\r"));
        assertFalse(token.contains("\n"));
        assertEquals(user, utf8(Base64.getDecoder().decode(token)));
    }

    /**
     * The counter-example, pinned so nobody reaches for this encoder again: the
     * MIME content-transfer encoder in this package breaks a long credential,
     * which is exactly what an AUTH LOGIN argument must not be.
     *
     * @throws EncodingException if the MIME encoder refuses the bytes, which it
     *                           never does for base64
     */
    @Test
    public void testTheMimeEncoderWrapsALongCredential() throws EncodingException {
        Encoding mime = EncodingFactory.getEncoding("base64");
        String shortToken = mime.encode(new byte[30]);
        assertFalse(shortToken.contains("\r"));
        String longToken = mime.encode(new byte[57]);
        assertTrue("the MIME encoder stopped wrapping", longToken.contains("\r\n"));
    }

    /**
     * A command carrying a line break is refused before anything is written, or
     * the server has already taken the first half as the whole command and the
     * rest of it as another one.
     *
     * @throws Exception if the private writer or the socket field cannot be
     *                   reached
     */
    @Test
    public void testACommandBreakIsRefusedBeforeItIsWritten() throws Exception {
        ByteArrayOutputStream written = new ByteArrayOutputStream();
        try {
            send("PASS p\nass", written);
            fail("a command break went out to the server");
        } catch (InvocationTargetException ite) {
            assertTrue("the writer failed for the wrong reason: " + ite.getCause(),
                       ite.getCause() instanceof IOException);
        }
        assertEquals("the refused command still wrote bytes", 0, written.size());
    }

    /**
     * A command that passes the check goes out with its terminator, so the
     * refusal above is not simply every write failing.
     *
     * @throws Exception if the private writer or the socket field cannot be
     *                   reached
     */
    @Test
    public void testAnOrdinaryCommandIsWritten() throws Exception {
        ByteArrayOutputStream written = new ByteArrayOutputStream();
        send("AUTH LOGIN", written);
        assertEquals("AUTH LOGIN\r\n", utf8(written.toByteArray()));
    }

    /**
     * Drive the private writer over a socket that only counts what it is given.
     *
     * @param cmd the command to write
     * @param written where the socket collects what was written
     * @throws Exception if the private writer or the socket field cannot be
     *                   reached
     */
    private static void send(String cmd, ByteArrayOutputStream written) throws Exception {
        SMTPClient client = new SMTPClient();
        Field f = SMTPClient.class.getDeclaredField("socket");
        f.setAccessible(true);
        f.set(client, new CountingSocket(written));
        Method m = SMTPClient.class.getDeclaredMethod("sendCmdNoWait", String.class,
                                                      boolean.class);
        m.setAccessible(true);
        m.invoke(client, cmd, false);
    }

    /**
     * A socket that is never connected and hands out one byte sink, so a write can
     * be seen without a server.
     */
    private static class CountingSocket extends Socket {

        /** Where this socket collects what the writer sends. */
        private final ByteArrayOutputStream _written;

        /**
         * Hands out the sink the test inspects.
         *
         * @param written where writes are collected
         */
        CountingSocket(ByteArrayOutputStream written) {
            _written = written;
        }

        @Override
        public OutputStream getOutputStream() {
            return _written;
        }
    }

    /**
     * @param c the character to repeat
     * @param count how many times
     * @return a string of count copies of c
     */
    private static String repeat(char c, int count) {
        StringBuilder buf = new StringBuilder(count);
        for (int i = 0; i < count; i++) {
            buf.append(c);
        }
        return buf.toString();
    }

    /**
     * @param bytes the bytes to read back as text
     * @return the same characters the test put into the encoder
     */
    private static String utf8(byte[] bytes) {
        return new String(bytes, StandardCharsets.UTF_8);
    }
}

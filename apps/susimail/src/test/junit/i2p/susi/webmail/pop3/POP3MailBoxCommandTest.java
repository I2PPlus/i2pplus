package i2p.susi.webmail.pop3;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import org.junit.Test;

/**
 * Tests that a POP3 command is refused before a byte of it reaches the server.
 *
 * <p>The defect: the writer appends its own terminator and writes the command
 * through an 8-bit truncation without looking at what is in it, and the USER and
 * PASS arguments come straight from the login form. A CR or LF in a password ends
 * the command early and the rest of it reaches the server as a second command, so
 * a password can carry a DELE or a NOOP that the session never agreed to.
 *
 * <p>No server: the writer is driven with a socket whose output stream is a byte
 * counter, so a refusal can be shown to have written nothing at all.
 *
 * @since 0.9.72+
 */
public class POP3MailBoxCommandTest {

    /**
     * A fixture group for the POP3 command writer; it holds no state.
     *
     * @since 0.9.72+
     */
    public POP3MailBoxCommandTest() {}

    /**
     * A command carrying a line break is refused before anything is written.
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
     * A carriage return breaks the command just as a line feed does, and a
     * credential that merely contains a non-ASCII character is still sent.
     *
     * @throws Exception if the private writer or the socket field cannot be
     *                   reached
     */
    @Test
    public void testAReturnAndANonAsciiCredential() throws Exception {
        ByteArrayOutputStream written = new ByteArrayOutputStream();
        try {
            send("PASS p\rass", written);
            fail("a command break went out to the server");
        } catch (InvocationTargetException ite) {
            assertTrue(ite.getCause() instanceof IOException);
        }
        assertEquals(0, written.size());
        send("USER susi\u00e4", written);
        byte[] sent = written.toByteArray();
        // the character goes out as its low byte - mangled, but not a command break
        assertEquals("USER susi", new String(sent, 0, 9, StandardCharsets.US_ASCII));
        assertEquals(0xe4, sent[9] & 0xff);
        assertEquals("\r\n", new String(sent, 10, 2, StandardCharsets.US_ASCII));
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
        send("RETR 7", written);
        assertEquals("RETR 7\r\n", new String(written.toByteArray(), StandardCharsets.US_ASCII));
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
        POP3MailBox mailbox = new POP3MailBox("pop3.example.i2p", 110, "susi", "secret");
        Field f = POP3MailBox.class.getDeclaredField("socket");
        f.setAccessible(true);
        f.set(mailbox, new CountingSocket(written));
        Method m = POP3MailBox.class.getDeclaredMethod("sendCmd1aNoWait", String.class);
        m.setAccessible(true);
        m.invoke(mailbox, cmd);
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
}

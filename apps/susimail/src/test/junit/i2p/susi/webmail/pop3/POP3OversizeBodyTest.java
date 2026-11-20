package i2p.susi.webmail.pop3;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import i2p.susi.util.Buffer;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import net.i2p.data.DataHelper;
import org.junit.Test;

/**
 * What one message too large to store costs the rest of the mailbox.
 *
 * <p>The defect: the body reader threw the moment its byte count passed the
 * ceiling, leaving the remainder of that body, and the server's reply to
 * whatever had been pipelined behind it, sitting in the stream. The caller could
 * not resync, so it closed the connection and every message behind the oversized
 * one in the batch came back failed as well. On a server without TOP support the
 * oversized message is in the batch at all - a header-only fetch is not
 * available - so one 20 MB message leaves 19 smaller ones unloaded, refresh after
 * refresh, and it is never deleted from the server because it has no body.
 *
 * <p>The fix being pinned: read the remainder off the wire to the terminating
 * dot, fail that one request, and keep the connection.
 *
 * <p>No server: the socket is a stub handing out a scripted stream.
 *
 * @since 0.9.72+
 */
public class POP3OversizeBodyTest {

    /**
     * A fixture group for the oversized-body path; it holds no state.
     *
     * @since 0.9.72+
     */
    public POP3OversizeBodyTest() {}

    /** One body line, long enough to reach the ceiling in a sane number of lines. */
    private static final String BODY_LINE = repeat('x', 64) + "\r\n";

    /** How many copies of the body line it takes to pass the ceiling. */
    private static final int LINES_PAST_THE_LIMIT =
            (POP3MailBox.MAX_BODY_BYTES / BODY_LINE.length()) + 3;

    /** The reply the next pipelined command is answered with. */
    private static final String NEXT_REPLY = "+OK the next message\r\n";

    /**
     * An oversized body is read to its terminating dot rather than abandoned, so
     * the next reply is where the next reply should be.
     *
     * @throws Exception if the private reader cannot be reached
     */
    @Test(timeout = 60000)
    public void anOversizedBodyIsReadOffTheConnection() throws Exception {
        CountingStream in = new CountingStream(body(LINES_PAST_THE_LIMIT) + ".\r\n" + NEXT_REPLY);
        POP3MailBox box = mailbox(in);
        RecordingBuffer buffer = new RecordingBuffer(0);
        try {
            readBody(box, buffer);
            fail("an oversized body was accepted");
        } catch (InvocationTargetException ite) {
            Throwable cause = ite.getCause();
            assertEquals("the wrong failure was reported: " + cause,
                         "OversizeBodyException", cause.getClass().getSimpleName());
            assertTrue("the failure did not name the size limit: " + cause.getMessage(),
                       cause.getMessage().contains("too large to store"));
        }
        assertEquals(Boolean.FALSE, buffer.completed());
        assertTrue("the buffer heard about the failure only after its stream closed",
                   buffer.heardBeforeClose());
        assertTrue("the reader kept writing past the bound, " + buffer.written() + " bytes",
                   buffer.written() <= POP3MailBox.MAX_BODY_BYTES + BODY_LINE.length());
        // and the connection is sitting on the next command's reply, not in the
        // middle of the body that was just refused
        String next = DataHelper.readLine(boxInput(box));
        assertEquals("+OK the next message\r", next);
    }

    /**
     * A server that never ends the oversized body is not given an unbounded second
     * read: the drain stops at the same ceiling and the failure says the end was
     * never found, which puts the caller back on the close-the-connection path it
     * already handles.
     *
     * @throws Exception if the private reader cannot be reached
     */
    @Test(timeout = 60000)
    public void aDrainThatNeverEndsGivesUp() throws Exception {
        CountingStream in = new CountingStream(null);
        POP3MailBox box = mailbox(in);
        RecordingBuffer buffer = new RecordingBuffer(0);
        try {
            readBody(box, buffer);
            fail("an endless oversized body was accepted");
        } catch (InvocationTargetException ite) {
            Throwable cause = ite.getCause();
            assertTrue("the wrong failure was reported: " + cause, cause instanceof IOException);
            assertEquals("IOException", cause.getClass().getSimpleName());
            assertTrue("the failure did not say the body never ended: " + cause.getMessage(),
                       cause.getMessage().contains("end was not found"));
        }
        // the drain stopped at its own ceiling rather than reading on forever:
        // one ceiling for the body kept, one for the bytes thrown away
        long read = in.bytesRead();
        assertTrue("the drain ran on, reading " + read + " bytes",
                   read <= 2L * POP3MailBox.MAX_BODY_BYTES + 4 * BODY_LINE.length());
        assertTrue("the drain stopped too early, reading " + read + " bytes",
                   read > POP3MailBox.MAX_BODY_BYTES);
    }

    /**
     * The batch behind an oversized message still gets its answers. This is the
     * whole point of reading the remainder off the wire: the connection is good,
     * so the messages pipelined after the one that was refused are fetched
     * instead of being reported as failed and left on the server.
     *
     * @throws Exception if the private command runner cannot be reached
     */
    @Test(timeout = 60000)
    public void theBatchBehindAnOversizedMessageStillGetsItsAnswers() throws Exception {
        String stream = "+OK\r\n" + body(LINES_PAST_THE_LIMIT) + ".\r\n" +
                        "+OK\r\n" + "a small body\r\n.\r\n";
        CountingStream in = new CountingStream(stream);
        POP3MailBox box = mailbox(in);
        Object tooBig = retrieRequest(new RecordingBuffer(0));
        Object small = retrieRequest(new RecordingBuffer(4096));
        List<Object> cmds = new ArrayList<>(2);
        cmds.add(tooBig);
        cmds.add(small);
        Object ok = runCommands(box, cmds);
        assertEquals(Boolean.FALSE, ok);
        assertFalse("the oversized message was reported as fetched", result(tooBig));
        assertTrue("the message behind it was reported as failed", result(small));
        assertEquals("a small body\r\n", ((RecordingBuffer) field(small, "rb")).text());
        assertNotNull("the connection was closed and the batch lost", socket(box));
    }

    /**
     * The body of a message, n lines long, each one the same length.
     *
     * @param lines how many lines
     * @return the bytes the server sends for that body
     */
    private static String body(int lines) {
        StringBuilder buf = new StringBuilder(lines * BODY_LINE.length());
        for (int i = 0; i < lines; i++) {
            buf.append(BODY_LINE);
        }
        return buf.toString();
    }

    /**
     * A mailbox whose socket hands out one stream.
     *
     * @param in what its socket will report as the connection's input
     * @return a mailbox that was never connected
     */
    private static POP3MailBox mailbox(CountingStream in) {
        POP3MailBox box = new POP3MailBox("pop3.example.i2p", 110, "susi", "secret");
        try {
            Field f = POP3MailBox.class.getDeclaredField("socket");
            f.setAccessible(true);
            f.set(box, new FixedSocket(in));
        } catch (ReflectiveOperationException ree) {
            throw new AssertionError("the socket field is gone", ree);
        }
        return box;
    }

    /**
     * @param box the mailbox to read a body from
     * @param buffer where the body is written
     * @throws Exception if the private reader cannot be reached
     */
    private static void readBody(POP3MailBox box, Buffer buffer) throws Exception {
        Method m = POP3MailBox.class.getDeclaredMethod("getResultNa", Buffer.class);
        m.setAccessible(true);
        m.invoke(box, buffer);
    }

    /**
     * @param box the mailbox to run commands on
     * @param cmds the commands to run, one SendRecv each
     * @return what the runner returned, true only if every command succeeded
     * @throws Exception if the private runner cannot be reached
     */
    private static Object runCommands(POP3MailBox box, List<Object> cmds) throws Exception {
        Method m = POP3MailBox.class.getDeclaredMethod("sendCmds", List.class);
        m.setAccessible(true);
        return m.invoke(box, cmds);
    }

    /**
     * A RETR command reading its reply into a buffer.
     *
     * @param buffer where the body is written
     * @return a SendRecv for the command runner
     * @throws Exception if the private command class cannot be built
     */
    private static Object retrieRequest(Buffer buffer) throws Exception {
        Class<?> cls = Class.forName("i2p.susi.webmail.pop3.POP3MailBox$SendRecv");
        return cls.getDeclaredConstructor(String.class, Buffer.class).newInstance("RETR 1", buffer);
    }

    /**
     * @param cmd the command whose outcome is wanted
     * @return whether the runner reported it as fetched
     * @throws Exception if the field is gone
     */
    private static boolean result(Object cmd) throws Exception {
        return Boolean.TRUE.equals(field(cmd, "result"));
    }

    /**
     * @param obj the object to read
     * @param name the field name
     * @return what that field holds
     * @throws Exception if the field is gone
     */
    private static Object field(Object obj, String name) throws Exception {
        Field f = obj.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(obj);
    }

    /**
     * @param box the mailbox to read
     * @return the socket it is holding, null if the runner closed it
     * @throws Exception if the field is gone
     */
    private static Socket socket(POP3MailBox box) throws Exception {
        return (Socket) field(box, "socket");
    }

    /**
     * @param box the mailbox to read from
     * @return the stream its socket hands out
     * @throws Exception if the field is gone
     */
    private static InputStream boxInput(POP3MailBox box) throws Exception {
        return socket(box).getInputStream();
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
     * A stream that hands out one scripted reply, or one line forever, and counts
     * what was taken from it, so a runaway reader shows up as a number.
     */
    private static class CountingStream extends InputStream {

        /** The scripted bytes, null for the endless shape. */
        private final byte[] _bytes;
        /** One line to repeat forever, for the endless shape. */
        private final byte[] _line;
        /** Where in the bytes or the repeated line this stream stands. */
        private int _at;
        /** How many bytes have been handed out. */
        private long _read;

        /**
         * Scripts one reply, or one line forever.
         *
         * @param reply the bytes the server sends, null to repeat a line forever
         */
        CountingStream(String reply) {
            if (reply == null) {
                _bytes = null;
                _line = BODY_LINE.getBytes(StandardCharsets.UTF_8);
            } else {
                _bytes = reply.getBytes(StandardCharsets.UTF_8);
                _line = null;
            }
        }

        @Override
        public int read() {
            _read++;
            if (_bytes != null) {
                if (_at >= _bytes.length) {return -1;}
                return _bytes[_at++] & 0xff;
            }
            int b = _line[_at] & 0xff;
            _at = (_at + 1) % _line.length;
            return b;
        }

        /**
         * @return how many bytes the reader took from this stream
         */
        long bytesRead() {
            return _read;
        }
    }

    /**
     * A socket that was never connected and hands out one stream in each
     * direction.
     */
    private static class FixedSocket extends Socket {

        /** What this socket reports as the connection's input. */
        private final InputStream _in;
        /** Where this socket writes what the client sends. */
        private final OutputStream _out = new ByteArrayOutputStream();

        /**
         * Hands out the streams the command runner sees.
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

        @Override
        public OutputStream getOutputStream() {
            return _out;
        }
    }

    /**
     * A buffer that counts what it is written, keeps the first bytes of it for the
     * test to read back, and records how it was told the transfer ended.
     */
    private static class RecordingBuffer implements Buffer {

        /** How many of the bytes written are kept for the test to read back. */
        private final int _keep;
        /** The bytes kept. */
        private final ByteArrayOutputStream _bytes = new ByteArrayOutputStream();
        /** How many bytes were written, kept or not. */
        private int _written;
        /** Whether the writer closed the stream. */
        private boolean _closed;
        /** How the buffer was told the transfer ended, null until it was. */
        private Boolean _completed;
        /** True if the buffer heard the outcome before its stream was closed. */
        private boolean _heardBeforeClose;

        /**
         * Counts everything and keeps what the test asks for.
         *
         * @param keep how many of the bytes to keep, 0 to keep none
         */
        RecordingBuffer(int keep) {
            _keep = keep;
        }

        @Override
        public InputStream getInputStream() {
            return new ByteArrayInputStream(_bytes.toByteArray());
        }

        @Override
        public OutputStream getOutputStream() {
            return new OutputStream() {

                @Override
                public void write(int b) {
                    _written++;
                    if (_written <= _keep) {_bytes.write(b);}
                }

                @Override
                public void close() {
                    _closed = true;
                }
            };
        }

        @Override
        public void readComplete(boolean success) { /* not our side */ }

        @Override
        public void writeComplete(boolean success) {
            _completed = Boolean.valueOf(success);
            _heardBeforeClose = !_closed;
        }

        @Override
        public int getLength() {
            return _written;
        }

        @Override
        public int getOffset() {
            return 0;
        }

        /**
         * @return how the transfer ended, or null if the buffer was never told
         */
        Boolean completed() {
            return _completed;
        }

        /**
         * @return true if writeComplete() ran before the stream was closed
         */
        boolean heardBeforeClose() {
            return _heardBeforeClose;
        }

        /**
         * @return the bytes that were kept, as text
         */
        String text() {
            return new String(_bytes.toByteArray(), StandardCharsets.UTF_8);
        }

        /**
         * @return how many bytes were written, kept or not
         */
        int written() {
            return _written;
        }
    }
}

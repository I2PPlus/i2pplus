package i2p.susi.webmail.pop3;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
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
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import org.junit.Test;

/**
 * Tests the bounds on what a POP3 server can make this client read, and what it
 * may claim about a message.
 *
 * <p>The defect: the readers took whatever the server kept sending. A body had no
 * byte count, a UIDL or LIST reply had no line or character count, and both ended
 * happily at EOF, so a connection that dropped mid-body looked like a complete
 * body: the truncated file was committed, the caller saw a message with a body,
 * and the server's intact copy was deleted. The UIDL list also accepted any id of
 * any length and let one message number be claimed by two ids, which is two folder
 * entries for one message on the server.
 *
 * <p>No server: the socket is a stub handing out a fixed or endless stream, and the
 * private readers are called directly.
 *
 * @since 0.9.72+
 */
public class POP3ReplyLimitsTest {

    /**
     * A fixture group for the POP3 reply readers; it holds no state.
     *
     * @since 0.9.72+
     */
    public POP3ReplyLimitsTest() {}

    /** One body line a server that never stops sending repeats. */
    private static final String ENDLESS_BODY_LINE = "a body line that is reasonably long\r\n";

    /**
     * The mail loss: a body that never reached its terminating dot is a failed
     * transfer, not a message, so the buffer is told it failed and nothing is
     * committed.
     *
     * @throws Exception if the private reader cannot be reached
     */
    @Test
    public void testABodyWithoutItsTerminatorIsNotCommitted() throws Exception {
        POP3MailBox box = mailbox("one\r\ntwo\r\n");
        RecordingBuffer buffer = new RecordingBuffer(4096);
        try {
            readBody(box, buffer);
            fail("a body that never ended was accepted");
        } catch (InvocationTargetException ite) {
            assertTrue("the reader failed for the wrong reason: " + ite.getCause(),
                       ite.getCause() instanceof IOException);
        }
        assertEquals(Boolean.FALSE, buffer.completed());
        assertTrue("the buffer heard about the failure only after its stream closed",
                   buffer.heardBeforeClose());
    }

    /**
     * A correctly terminated body is committed, with the byte-stuffed dot removed
     * from any line that was doubled up.
     *
     * @throws Exception if the private reader cannot be reached
     */
    @Test
    public void testATerminatedBodyIsCommittedUnstuffed() throws Exception {
        POP3MailBox box = mailbox("one\r\n..stuffed\r\n.\r\n");
        RecordingBuffer buffer = new RecordingBuffer(4096);
        readBody(box, buffer);
        assertEquals(Boolean.TRUE, buffer.completed());
        assertEquals("one\r\n.stuffed\r\n", buffer.text());
    }

    /**
     * A server that ends its lines with a bare line feed writes its terminator as a
     * bare dot too; that is the end of the body, not a line of it.
     *
     * @throws Exception if the private reader cannot be reached
     */
    @Test
    public void testABareDotEndsTheBody() throws Exception {
        POP3MailBox box = mailbox("one\r\n.\n");
        RecordingBuffer buffer = new RecordingBuffer(4096);
        readBody(box, buffer);
        assertEquals(Boolean.TRUE, buffer.completed());
        assertEquals("one\r\n", buffer.text());
    }

    /**
     * A server that never stops sending body lines is stopped by the byte count
     * rather than by the disk. The timeout is part of the test: a reader with no
     * byte count never stops at all.
     *
     * @throws Exception if the private reader cannot be reached
     */
    @Test(timeout = 60000)
    public void testAnEndlessBodyIsRefusedByTheByteCount() throws Exception {
        POP3MailBox box = mailbox(null);
        setStream(box, endless(ENDLESS_BODY_LINE));
        RecordingBuffer buffer = new RecordingBuffer(0);
        try {
            readBody(box, buffer);
            fail("the body ran past the byte limit");
        } catch (InvocationTargetException ite) {
            assertTrue(ite.getCause() instanceof IOException);
        }
        // the reader read up to the bound and stopped there, rather than running on
        assertTrue("the reader stopped after only " + buffer.written() + " bytes",
                   buffer.written() > POP3MailBox.MAX_BODY_BYTES / 2);
        assertTrue("the reader ran past the bound, writing " + buffer.written() + " bytes",
                   buffer.written() <= POP3MailBox.MAX_BODY_BYTES + ENDLESS_BODY_LINE.length());
        assertEquals(Boolean.FALSE, buffer.completed());
    }

    /**
     * A listing is held in memory as one String per line, so an endless listing is
     * stopped by the line count. The timeout is part of the test: a reader with no
     * line count never stops at all.
     *
     * @throws Exception if the private reader cannot be reached
     */
    @Test(timeout = 60000)
    public void testAnEndlessListingIsRefusedByTheLineCount() throws Exception {
        POP3MailBox box = mailbox(null);
        setStream(box, endless("1 abc\r\n"));
        try {
            readListing(box);
            fail("the listing ran past the line limit");
        } catch (InvocationTargetException ite) {
            assertTrue(ite.getCause() instanceof IOException);
        }
    }

    /**
     * The other shape of the same attack: few lines, each of them enormous, is
     * stopped by the character count instead. The line is long enough that the
     * character count is reached well before the line count. The timeout is part of
     * the test: a reader with no character count never stops at all.
     *
     * @throws Exception if the private reader cannot be reached
     */
    @Test(timeout = 60000)
    public void testVeryLongListingLinesAreRefusedByTheCharacterCount() throws Exception {
        POP3MailBox box = mailbox(null);
        setStream(box, endless(repeat('x', 4096) + "\r\n"));
        try {
            readListing(box);
            fail("the listing ran past the character limit");
        } catch (InvocationTargetException ite) {
            assertTrue(ite.getCause() instanceof IOException);
        }
    }

    /**
     * A listing under both counts is read whole - the bounds are a ceiling on a
     * remote server, not a cap on the user's mailbox.
     *
     * @throws Exception if the private reader cannot be reached
     */
    @Test
    public void testAnOrdinaryListingIsReadWhole() throws Exception {
        POP3MailBox box = mailbox("1 aaa\r\n2 bbb\r\n.\r\n");
        @SuppressWarnings("unchecked")
        List<String> lines = (List<String>) readListing(box);
        assertEquals(2, lines.size());
        assertEquals("1 aaa\r", lines.get(0));
    }

    /**
     * One message number claimed by two ids: the second id is refused, or the
     * folder shows two messages where the server has one, and DELE for either takes
     * the message the other is standing for.
     *
     * @throws Exception if the private updater cannot be reached
     */
    @Test
    public void testADuplicateMessageNumberKeepsTheFirstId() throws Exception {
        POP3MailBox box = mailbox(null);
        updateUIDLs(box, Arrays.asList("1 aaa\r", "1 bbb\r", "2 ccc\r"));
        HashMap<?, ?> ids = ids(box);
        assertEquals(2, ids.size());
        assertEquals(Integer.valueOf(1), ids.get("aaa"));
        assertNull(ids.get("bbb"));
        assertEquals(Integer.valueOf(2), ids.get("ccc"));
    }

    /**
     * An id the server invented that this client cannot store or name in a URL is
     * skipped rather than kept: one with a space in it, one with a control character
     * in it, and one longer than RFC 1939 allows.
     *
     * @throws Exception if the private updater cannot be reached
     */
    @Test
    public void testAnUnusableIdIsSkipped() throws Exception {
        POP3MailBox box = mailbox(null);
        updateUIDLs(box, Arrays.asList("1 has a space\r", "2 ta\r00d\r",
                                       "3 " + longId() + "x\r"));
        assertEquals(0, ids(box).size());
    }

    /**
     * The shape test itself: printable, at most 70 characters, never empty.
     */
    @Test
    public void testTheIdShapeTheServerMustKeep() {
        assertTrue(POP3MailBox.validUIDL("AAA@123"));
        assertTrue(POP3MailBox.validUIDL(longId()));
        assertFalse(POP3MailBox.validUIDL(longId() + "x"));
        assertFalse(POP3MailBox.validUIDL(""));
        assertFalse(POP3MailBox.validUIDL(null));
        assertFalse(POP3MailBox.validUIDL("with space"));
        assertFalse(POP3MailBox.validUIDL("tab\there"));
        assertFalse(POP3MailBox.validUIDL("delete\u007f"));
        assertFalse(POP3MailBox.validUIDL("non\u00e4scii"));
    }

    /**
     * A size the server cannot mean - a negative octet count, or a message number
     * that is not one - is skipped, so nothing downstream sizes a message from it.
     *
     * @throws Exception if the private updater cannot be reached
     */
    @Test
    public void testAnImpossibleSizeIsSkipped() throws Exception {
        POP3MailBox box = mailbox(null);
        updateSizes(box, Arrays.asList("1 100\r", "2 -5\r", "0 7\r", "-3 9\r"));
        HashMap<?, ?> sizes = sizes(box);
        assertEquals(1, sizes.size());
        assertEquals(Integer.valueOf(100), sizes.get(Integer.valueOf(1)));
    }

    /**
     * A mailbox whose socket hands out one fixed reply.
     *
     * @param reply the bytes the server sends, null for a caller that sets its own
     *              stream
     * @return a mailbox that was never connected
     */
    private static POP3MailBox mailbox(String reply) {
        POP3MailBox box = new POP3MailBox("pop3.example.i2p", 110, "susi", "secret");
        if (reply != null) {
            setStream(box, new ByteArrayInputStream(reply.getBytes(StandardCharsets.UTF_8)));
        }
        return box;
    }

    /**
     * @param box the mailbox to hand a stream to
     * @param stream what its socket will report as the connection's input
     */
    private static void setStream(POP3MailBox box, InputStream stream) {
        try {
            Field f = POP3MailBox.class.getDeclaredField("socket");
            f.setAccessible(true);
            f.set(box, new FixedSocket(stream));
        } catch (ReflectiveOperationException ree) {
            throw new AssertionError("the socket field is gone", ree);
        }
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
     * @param box the mailbox to read a listing from
     * @return whatever the reader returned
     * @throws Exception if the private reader cannot be reached
     */
    private static Object readListing(POP3MailBox box) throws Exception {
        Method m = POP3MailBox.class.getDeclaredMethod("getResultNl");
        m.setAccessible(true);
        return m.invoke(box);
    }

    /**
     * @param box the mailbox to update
     * @param lines the UIDL reply lines
     * @throws Exception if the private updater cannot be reached
     */
    private static void updateUIDLs(POP3MailBox box, List<String> lines) throws Exception {
        Method m = POP3MailBox.class.getDeclaredMethod("updateUIDLs", List.class);
        m.setAccessible(true);
        m.invoke(box, lines);
    }

    /**
     * @param box the mailbox to update
     * @param lines the LIST reply lines
     * @throws Exception if the private updater cannot be reached
     */
    private static void updateSizes(POP3MailBox box, List<String> lines) throws Exception {
        Method m = POP3MailBox.class.getDeclaredMethod("updateSizes", List.class);
        m.setAccessible(true);
        m.invoke(box, lines);
    }

    /**
     * @param box the mailbox to read
     * @return the id to number map the last UIDL reply left behind
     * @throws Exception if the private field is gone
     */
    private static HashMap<?, ?> ids(POP3MailBox box) throws Exception {
        return map(box, "uidlToID");
    }

    /**
     * @param box the mailbox to read
     * @return the number to size map the last LIST reply left behind
     * @throws Exception if the private field is gone
     */
    private static HashMap<?, ?> sizes(POP3MailBox box) throws Exception {
        return map(box, "sizes");
    }

    /**
     * @param box the mailbox to read
     * @param name the private map's field name
     * @return the map that field holds
     * @throws Exception if the field is gone
     */
    private static HashMap<?, ?> map(POP3MailBox box, String name) throws Exception {
        Field f = POP3MailBox.class.getDeclaredField(name);
        f.setAccessible(true);
        return (HashMap<?, ?>) f.get(box);
    }

    /**
     * A UIDL of exactly the longest length the server is allowed.
     *
     * @return 70 printable characters
     */
    private static String longId() {
        return repeat('u', POP3MailBox.MAX_UIDL_LENGTH);
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
     * stops sending.
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
         * Hands out the stream the readers see.
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

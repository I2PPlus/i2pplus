package net.i2p.sam;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import org.junit.Test;

/**
 * The pooled v3 reader holds a partial command line in a per-connection buffer
 * until the newline arrives, so that buffer has to be capped: a client that
 * never sends one grows it at wire speed. 32 MB of loopback traffic put 32 MB on
 * the router's heap and kept the connection open, and the newline that followed
 * echoed the whole line back as the command token of an error reply. The limit
 * is the one {@link ReadLine} has always applied on the unpooled path.
 *
 * @since 0.9.72+
 */
public class SAMLineLimitTest {

    /** Holds no state; each case feeds one read to the splitter. */
    public SAMLineLimitTest() {}

    /** A complete line is handed over, and nothing is left buffered. */
    @Test
    public void aCompleteLineIsDelivered() {
        List<String> lines = lines("SESSION CREATE STYLE=TUNNEL\n");
        assertEquals(1, lines.size());
        assertEquals("SESSION CREATE STYLE=TUNNEL", lines.get(0));
    }

    /** Carriage returns are dropped, so CRLF and bare LF both work. */
    @Test
    public void carriageReturnsAreDropped() {
        List<String> lines = lines("PING\r\nPONG\r\n");
        assertEquals(2, lines.size());
        assertEquals("PING", lines.get(0));
        assertEquals("PONG", lines.get(1));
    }

    /**
     * A line that arrives in pieces is buffered between reads and delivered once
     * the newline lands.
     */
    @Test
    public void aLineSplitAcrossReadsIsReassembled() {
        StringBuilder buf = new StringBuilder();
        List<String> lines = new ArrayList<>(2);
        feed(buf, "STREAM FORWA", lines::add);
        assertTrue("a partial line must not be dispatched", lines.isEmpty());
        feed(buf, "RD ID=app\nQUIT\n", lines::add);
        assertEquals(2, lines.size());
        assertEquals("STREAM FORWARD ID=app", lines.get(0));
        assertEquals("QUIT", lines.get(1));
        assertEquals("the buffer must be empty after a newline", 0, buf.length());
    }

    /**
     * A line exactly at the limit is a line the unpooled path accepts, so the
     * pooled one has to accept it too.
     */
    @Test
    public void aLineAtTheLimitIsAccepted() {
        List<String> lines = lines(repeat('a', ReadLine.MAX_LINE_LENGTH) + "\n");
        assertEquals(1, lines.size());
        assertEquals(ReadLine.MAX_LINE_LENGTH, lines.get(0).length());
    }

    /**
     * One character past the limit ends the connection instead of growing the
     * buffer, and the partial line is left in it rather than thrown away.
     */
    @Test
    public void oneCharPastTheLimitIsRefused() {
        StringBuilder buf = new StringBuilder();
        try {
            SAMHandlerPool.readLines(buf, bytes(repeat('a', ReadLine.MAX_LINE_LENGTH + 1)), s -> {
                throw new AssertionError("a line that never ended was dispatched");
            });
            fail("a line longer than " + ReadLine.MAX_LINE_LENGTH + " characters was accepted");
        } catch (ReadLine.LineTooLongException expected) {
            assertEquals(ReadLine.MAX_LINE_LENGTH, buf.length());
        }
    }

    /**
     * The limit is measured against the buffered line, not against one read, so
     * a client that dribbles out 2 KB at a time is refused at the same length as
     * one that sends the whole line at once.
     */
    @Test
    public void theLimitCountsTheWholeLineNotOneRead() {
        StringBuilder buf = new StringBuilder();
        int chunk = 2048;
        int reads = 0;
        while (true) {
            try {
                SAMHandlerPool.readLines(buf, bytes(repeat('a', chunk)), s -> {});
            } catch (ReadLine.LineTooLongException expected) {
                break;
            }
            reads++;
        }
        assertEquals(ReadLine.MAX_LINE_LENGTH, buf.length());
        assertEquals("refused after the wrong number of reads",
                     ReadLine.MAX_LINE_LENGTH / chunk, reads);
    }

    /**
     * The reader stops at the character that passed the limit rather than
     * running the loop to the end of the read; that character is consumed and
     * the rest is left in the read, which is dropped when the connection closes.
     */
    @Test
    public void theReadStopsAtTheOffendingChar() {
        StringBuilder buf = new StringBuilder();
        int over = 512;
        ByteBuffer tmp = bytes(repeat('a', ReadLine.MAX_LINE_LENGTH + over));
        try {
            SAMHandlerPool.readLines(buf, tmp, s -> {});
            fail("a line longer than " + ReadLine.MAX_LINE_LENGTH + " characters was accepted");
        } catch (ReadLine.LineTooLongException expected) {
            // expected
        }
        assertEquals(ReadLine.MAX_LINE_LENGTH, buf.length());
        assertEquals("the read was not stopped at the limit", over - 1, tmp.remaining());
    }

    /**
     * @param s the text of one read
     * @return the bytes of that read, ready to consume
     */
    private static ByteBuffer bytes(String s) {
        return ByteBuffer.wrap(s.getBytes(StandardCharsets.US_ASCII));
    }

    /**
     * @param buf the connection's partial line, updated in place
     * @param s the text of one read
     * @param lines called once per complete line, without its newline
     */
    private static void feed(StringBuilder buf, String s, Consumer<String> lines) {
        try {
            SAMHandlerPool.readLines(buf, bytes(s), lines);
        } catch (ReadLine.LineTooLongException ltte) {
            throw new AssertionError("refused a read within " + ReadLine.MAX_LINE_LENGTH +
                                     " characters: " + s, ltte);
        }
    }

    /**
     * @param s the text of one read holding complete lines
     * @return the lines it named, in order
     */
    private static List<String> lines(String s) {
        List<String> lines = new ArrayList<>(2);
        feed(new StringBuilder(), s, lines::add);
        return lines;
    }

    /**
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
}

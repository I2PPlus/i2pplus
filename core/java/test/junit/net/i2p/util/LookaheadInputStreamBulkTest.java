package net.i2p.util;

import static org.junit.Assert.*;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Random;

import org.junit.Test;

/**
 * Tests the bulk (array) reads of LookaheadInputStream against the byte-at-a-time
 * path they replaced: the same data, the same footer, whatever the footer size,
 * the read size, or how the source chooses to split its reads.
 *
 * @since 0.9.72+
 */
public class LookaheadInputStreamBulkTest {

    /** Footer sizes exercised, from one byte to a full block. */
    private static final int[] FOOTERS = {1, 2, 7, 8, 31, 1024};

    /** Read sizes exercised, including sizes below, equal to, and above the footer. */
    private static final int[] BLOCKS = {1, 2, 3, 7, 8, 31, 512, 4096};

    /**
     * Every footer size against every read size: the bytes delivered are the
     * source minus its footer, in order, and getFooter() is the footer.
     *
     * @throws IOException if the generated source cannot be read back
     */
    @Test
    public void testBulkReadsPreserveDataAndFooter() throws IOException {
        byte[] data = new byte[8193];
        new Random(42).nextBytes(data);
        for (int footer : FOOTERS) {
            for (int block : BLOCKS) {
                LookaheadInputStream in = new LookaheadInputStream(footer);
                in.initialize(new ByteArrayInputStream(data));
                byte[] out = new byte[data.length];
                int off = 0;
                int count;
                while (off < out.length && (count = in.read(out, off, Math.min(block, out.length - off))) >= 0)
                    off += count;
                String what = "footer " + footer + " block " + block;
                assertEquals(what, data.length - footer, off);
                assertArrayEquals(what, Arrays.copyOf(data, off), Arrays.copyOf(out, off));
                assertArrayEquals(what, Arrays.copyOfRange(data, off, data.length), in.getFooter());
            }
        }
    }

    /**
     * The bulk path must answer exactly what reading one byte at a time answers,
     * for random data, footers, and read sizes.
     *
     * @throws IOException if one of the generated sources cannot be read back
     */
    @Test
    public void testBulkMatchesByteAtATime() throws IOException {
        Random rnd = new Random(11);
        for (int round = 0; round < 300; round++) {
            // initialize() primes the footer buffer, so the source has to be at
            // least as long as the footer
            int footer = rnd.nextInt(32);
            byte[] data = new byte[footer + 1 + rnd.nextInt(600)];
            rnd.nextBytes(data);
            LookaheadInputStream bulk = new LookaheadInputStream(footer);
            LookaheadInputStream single = new LookaheadInputStream(footer);
            bulk.initialize(new ByteArrayInputStream(data));
            single.initialize(new ByteArrayInputStream(data));

            byte[] out = new byte[data.length];
            int off = 0;
            while (off < out.length) {
                int len = 1 + rnd.nextInt(40);
                int count = bulk.read(out, off, Math.min(len, out.length - off));
                if (count < 0)
                    break;
                off += count;
            }

            byte[] one = new byte[data.length];
            int at = 0;
            int c;
            while (at < one.length && (c = single.read()) >= 0)
                one[at++] = (byte) c;

            String what = "footer " + footer + " len " + data.length;
            assertEquals(what, at, off);
            assertArrayEquals(what, Arrays.copyOf(one, at), Arrays.copyOf(out, off));
            assertArrayEquals(what, single.getFooter(), bulk.getFooter());
        }
    }

    /**
     * Mixed single-byte and offset bulk reads over a source that hands back at
     * most five bytes at a time, so the short-read path is exercised as well.
     *
     * @throws IOException if the short-read source cannot be read back
     */
    @Test
    public void testMixedReadsWithAShortReadSource() throws IOException {
        byte[] data = new byte[257];
        new Random(7).nextBytes(data);
        InputStream source = new ByteArrayInputStream(data) {
            @Override
            public synchronized int read(byte[] target, int offset, int length) {
                return super.read(target, offset, Math.min(5, length));
            }
        };
        LookaheadInputStream in = new LookaheadInputStream(8);
        in.initialize(source);

        byte[] target = new byte[23];
        int pos = 0;
        while (pos < data.length - 8) {
            assertEquals(data[pos++] & 0xff, in.read());
            int count = in.read(target, 3, 13);
            if (count < 0)
                break;
            for (int i = 0; i < count; i++)
                assertEquals(data[pos++] & 0xff, target[3 + i] & 0xff);
        }
        assertArrayEquals(Arrays.copyOfRange(data, data.length - 8, data.length), in.getFooter());
    }

    /**
     * A stream with no footer passes everything through, and the bulk path must
     * not shift the caller's array.
     *
     * @throws IOException not expected; a byte array source never fails
     */
    @Test
    public void testZeroSizedFooterPassesEverythingThrough() throws IOException {
        byte[] data = new byte[64];
        new Random(3).nextBytes(data);
        LookaheadInputStream in = new LookaheadInputStream(0);
        in.initialize(new ByteArrayInputStream(data));
        byte[] out = new byte[64];
        assertEquals(64, in.read(out, 0, 64));
        assertArrayEquals(data, out);
        assertEquals(0, in.getFooter().length);
    }

    /**
     * A null buffer is rejected before the source is touched, not left for the
     * source to trip over - a source that tolerates null would otherwise write
     * nothing and report a short read.
     *
     * @throws IOException not expected; the null buffer is rejected before the
     *                     source is read
     */
    @Test
    public void testNullBufferRejected() throws IOException {
        Tolerant source = new Tolerant();
        LookaheadInputStream in = new LookaheadInputStream(4);
        in.initialize(source);
        int baseline = source.calls;
        try {
            in.read(null, 0, 4);
            fail("a null buffer was accepted");
        } catch (NullPointerException expected) {
            // expected
        }
        assertEquals("the source must not be read", baseline, source.calls);
    }

    @Test
    public void testNegativeOffsetRejected() throws IOException {
        assertRangeRejected(new byte[8], -1, 4);
    }

    @Test
    public void testNegativeLengthRejected() throws IOException {
        assertRangeRejected(new byte[8], 0, -1);
    }

    @Test
    public void testRangePastTheEndRejected() throws IOException {
        assertRangeRejected(new byte[8], 5, 4);
    }

    /**
     * The range check is written as offset &gt; buf.length - len; adding the two
     * overflows for a huge len and the range then looks legal.
     *
     * @throws IOException not expected; the range is rejected before the source
     *                     is read
     */
    @Test
    public void testOverflowingRangeRejected() throws IOException {
        assertRangeRejected(new byte[8], Integer.MAX_VALUE - 1, Integer.MAX_VALUE - 1);
    }

    /**
     * A bad range fails at once, against a source that would happily accept any
     * arguments, so the failure is this stream's check and not the source's.
     *
     * @param buffer the caller's array
     * @param off where in the array to start
     * @param len how many bytes to ask for
     * @throws IOException if the source reports one
     */
    private static void assertRangeRejected(byte[] buffer, int off, int len) throws IOException {
        Tolerant source = new Tolerant();
        LookaheadInputStream in = new LookaheadInputStream(4);
        in.initialize(source);
        int baseline = source.calls;
        try {
            in.read(buffer, off, len);
            fail("range off=" + off + " len=" + len + " was accepted");
        } catch (IndexOutOfBoundsException expected) {
            // expected
        }
        assertEquals("the source must not be read", baseline, source.calls);
    }

    /**
     * A zero-length read returns at once without touching the source, so a caller
     * cannot stall a stream by polling it with an empty buffer.
     *
     * @throws IOException not expected; the zero-length read never reaches the
     *                     source
     */
    @Test
    public void testZeroLengthReadReturnsAtOnce() throws IOException {
        Tolerant source = new Tolerant();
        LookaheadInputStream in = new LookaheadInputStream(4);
        in.initialize(source);
        int baseline = source.calls;
        assertEquals(0, in.read(new byte[8], 4, 0));
        assertEquals("the source must not be read", baseline, source.calls);
    }

    /**
     * A source that accepts any arguments and reports the whole request as read.
     * Anything this stream does not check itself therefore cannot be caught here,
     * which is what makes the range assertions above real assertions.
     */
    private static class Tolerant extends InputStream {

        /** How many array reads the source has been asked for. */
        int calls;

        @Override
        public int read() {
            return 0;
        }

        @Override
        public int read(byte[] buf, int off, int len) {
            calls++;
            return len;
        }
    }
}

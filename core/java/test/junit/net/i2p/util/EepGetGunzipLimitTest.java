package net.i2p.util;

import static org.junit.Assert.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.zip.GZIPOutputStream;

import net.i2p.I2PAppContext;
import org.junit.Test;

/**
 * Pins that the size ceiling of a fetch also bounds the inflated body.
 *
 * <p>The ceiling a fetch is created with was checked against the bytes that arrived on
 * the socket, while a second thread inflated the gzipped body into the destination with
 * no ceiling of its own. A reseed fetch even asks for gzip, so a host on the reseed
 * list can answer with a few kilobytes that expand to whatever it chose: the response
 * passed the transport check, wrote gigabytes to disk, and only then had its signature
 * looked at.
 *
 * @since 0.9.72+
 */
public class EepGetGunzipLimitTest {

    /**
     * One instance per test method, as JUnit builds a fresh test object for each one.
     *
     * @since 0.9.72+
     */
    public EepGetGunzipLimitTest() {}

    /**
     * The ceiling these cases fetch with.
     */
    private static final long MAX_SIZE = 4096;

    /**
     * A gzip member holding count bytes of filler - a few hundred bytes on the wire
     * for anything from a few kilobytes up.
     *
     * @param count how many bytes the member inflates to
     * @return the compressed bytes
     * @throws IOException if the compression fails
     */
    private static byte[] gzip(int count) throws IOException {
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        GZIPOutputStream out = new GZIPOutputStream(raw);
        byte[] filler = new byte[Math.min(count, 8192)];
        Arrays.fill(filler, (byte) 'x');
        int left = count;
        while (left > 0) {
            int n = Math.min(left, filler.length);
            out.write(filler, 0, n);
            left -= n;
        }
        out.finish();
        out.close();
        return raw.toByteArray();
    }

    /**
     * Inflate a member through the bounded copy and report what came out.
     *
     * @param member the compressed bytes
     * @param maxSize the ceiling to enforce
     * @return the bytes written before the copy stopped
     * @throws IOException if the copy reports one
     */
    private static long inflate(byte[] member, long maxSize) throws IOException {
        ReusableGZIPInputStream in = ReusableGZIPInputStream.acquire();
        try {
            in.initialize(new ByteArrayInputStream(member));
            return EepGet.copyBounded(in, new ByteArrayOutputStream(), maxSize);
        } finally {
            ReusableGZIPInputStream.release(in);
        }
    }

    /**
     * The defect: a body that inflates far past the ceiling is written in full.
     *
     * @throws IOException if the gzip fixture cannot be built
     */
    @Test
    public void testABombedBodyStopsAtTheCeiling() throws IOException {
        byte[] member = gzip(200000);
        assertTrue("the compressed member is not smaller than the ceiling: " + member.length,
                   member.length < MAX_SIZE);
        try {
            long written = inflate(member, MAX_SIZE);
            fail("inflated " + written + " bytes past a " + MAX_SIZE + " byte ceiling");
        } catch (IOException expected) {
            // expected
        }
    }

    /**
     * The count is checked before each write, so the destination never holds more
     * than the ceiling even by one buffer.
     *
     * @throws IOException if the copy reports an unexpected failure
     */
    @Test
    public void testTheCeilingIsNotOvershot() throws IOException {
        byte[] member = gzip(200000);
        // a ceiling above one buffer, so the copy does write some chunks before it
        // refuses the next one
        long ceiling = 20000;
        Counting out = new Counting();
        ReusableGZIPInputStream in = ReusableGZIPInputStream.acquire();
        try {
            in.initialize(new ByteArrayInputStream(member));
            EepGet.copyBounded(in, out, ceiling);
            fail("inflated past a " + ceiling + " byte ceiling");
        } catch (IOException expected) {
            // expected
        } finally {
            ReusableGZIPInputStream.release(in);
        }
        assertTrue("wrote " + out.count + " bytes", out.count > 0);
        assertTrue("wrote " + out.count + " bytes for a ceiling of " + ceiling,
                   out.count <= ceiling);
    }

    /**
     * A body inside the ceiling still round-trips byte for byte.
     *
     * @throws IOException if the copy reports a failure
     */
    @Test
    public void testABodyInsideTheCeilingRoundTrips() throws IOException {
        byte[] member = gzip(1000);
        ReusableGZIPInputStream in = ReusableGZIPInputStream.acquire();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            in.initialize(new ByteArrayInputStream(member));
            assertEquals(1000, EepGet.copyBounded(in, out, MAX_SIZE));
        } finally {
            ReusableGZIPInputStream.release(in);
        }
        byte[] inflated = out.toByteArray();
        assertEquals(1000, inflated.length);
        for (byte b : inflated) {
            assertEquals('x', b);
        }
    }

    /**
     * A fetch with no ceiling is unaffected: everything is inflated.
     *
     * @throws IOException if the copy reports a failure
     */
    @Test
    public void testNoCeilingInflatesEverything() throws IOException {
        assertEquals(200000, inflate(gzip(200000), -1));
    }

    /**
     * End to end through the decompressor thread the fetch actually starts: the
     * failure reaches the fetch as its decompression exception rather than as a file
     * of the wrong size.
     *
     * @throws IOException if the gzip fixture cannot be built
     */
    @Test
    public void testTheDecompressorReportsTheFailureToTheFetch() throws IOException {
        EepGet get = new EepGet(I2PAppContext.getGlobalContext(), false, null, -1, 0,
                                -1, MAX_SIZE, null, new ByteArrayOutputStream(),
                                "http://reseed.example.i2p/", true, null, null, null);
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        get.new Gunzipper(new ByteArrayInputStream(gzip(200000)), sink).run();
        IOException ioe = get._decompressException;
        assertNotNull("the decompressor kept the oversized body to itself", ioe);
        assertTrue("the failure does not name the ceiling: " + ioe.getMessage(),
                   ioe.getMessage().contains("violates maximum"));
    }

    /**
     * An output stream that counts the bytes written to it.
     */
    private static class Counting extends ByteArrayOutputStream {

        /**
         * How many bytes have been written.
         */
        long count;

        @Override
        public void write(byte[] buf, int off, int len) {
            count += len;
        }
    }
}

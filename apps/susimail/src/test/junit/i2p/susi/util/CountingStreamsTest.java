package i2p.susi.util;

import static org.junit.Assert.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import org.junit.Test;

/**
 *  Tests for CountingInputStream and CountingOutputStream.
 */
public class CountingStreamsTest {

    @Test
    public void testInputStreamCountsRead() throws Exception {
        byte[] data = {1, 2, 3, 4, 5};
        CountingInputStream cis = new CountingInputStream(new ByteArrayInputStream(data));
        assertEquals(0, cis.getRead());
        cis.read();
        assertEquals(1, cis.getRead());
        byte[] buf = new byte[3];
        cis.read(buf, 0, 3);
        assertEquals(4, cis.getRead());
    }

    @Test
    public void testInputStreamReadAll() throws Exception {
        byte[] data = {10, 20, 30, 40, 50};
        CountingInputStream cis = new CountingInputStream(new ByteArrayInputStream(data));
        byte[] buf = new byte[5];
        int n = cis.read(buf, 0, 5);
        assertEquals(5, n);
        assertEquals(5, cis.getRead());
        assertArrayEquals(data, buf);
    }

    @Test
    public void testInputStreamEOF() throws Exception {
        byte[] data = {1};
        CountingInputStream cis = new CountingInputStream(new ByteArrayInputStream(data));
        assertEquals(1, cis.getRead() + 0); // initial 0
        cis.read();
        assertEquals(1, cis.getRead());
        assertEquals(-1, cis.read());
        assertEquals(1, cis.getRead());
    }

    @Test
    public void testInputStreamSkip() throws Exception {
        byte[] data = new byte[100];
        CountingInputStream cis = new CountingInputStream(new ByteArrayInputStream(data));
        long skipped = cis.skip(50);
        assertEquals(50, skipped);
        assertEquals(50, cis.getRead());
    }

    @Test
    public void testOutputStreamCountsWrite() throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        CountingOutputStream cos = new CountingOutputStream(baos);
        assertEquals(0, cos.getWritten());
        cos.write(1);
        assertEquals(1, cos.getWritten());
        cos.write(new byte[] {2, 3, 4}, 0, 3);
        assertEquals(4, cos.getWritten());
        assertArrayEquals(new byte[] {1, 2, 3, 4}, baos.toByteArray());
    }

    @Test
    public void testOutputStreamFlush() throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        CountingOutputStream cos = new CountingOutputStream(baos);
        cos.write(42);
        cos.flush();
        assertEquals(1, cos.getWritten());
    }

    @Test
    public void testLargeWrite() throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        CountingOutputStream cos = new CountingOutputStream(baos);
        byte[] data = new byte[10000];
        cos.write(data, 0, data.length);
        assertEquals(10000, cos.getWritten());
    }
}

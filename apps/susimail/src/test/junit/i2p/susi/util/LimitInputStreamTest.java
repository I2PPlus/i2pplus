package i2p.susi.util;

import static org.junit.Assert.*;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import org.junit.Test;

/**
 *  Tests for LimitInputStream.
 */
public class LimitInputStreamTest {

    @Test
    public void testLimitsRead() throws Exception {
        byte[] data = new byte[100];
        LimitInputStream lis = new LimitInputStream(new ByteArrayInputStream(data), 50);
        byte[] buf = new byte[100];
        int total = 0;
        int n;
        while ((n = lis.read(buf, 0, buf.length)) > 0) {
            total += n;
        }
        assertEquals(50, total);
    }

    @Test
    public void testReturnsNegativeOneAfterLimit() throws Exception {
        byte[] data = new byte[100];
        LimitInputStream lis = new LimitInputStream(new ByteArrayInputStream(data), 3);
        assertEquals(0, lis.read());
        assertEquals(0, lis.read());
        assertEquals(0, lis.read());
        assertEquals(-1, lis.read());
    }

    @Test
    public void testGetReadCountsCorrectly() throws Exception {
        byte[] data = new byte[100];
        LimitInputStream lis = new LimitInputStream(new ByteArrayInputStream(data), 10);
        lis.read();
        assertEquals(1, lis.getRead());
        byte[] buf = new byte[20];
        lis.read(buf, 0, 10);
        assertEquals(10, lis.getRead());
    }

    @Test
    public void testSkipRespectsLimit() throws Exception {
        byte[] data = new byte[100];
        LimitInputStream lis = new LimitInputStream(new ByteArrayInputStream(data), 20);
        long skipped = lis.skip(50);
        assertEquals(20, skipped);
        assertEquals(20, lis.getRead());
    }

    @Test
    public void testAvailable() throws Exception {
        byte[] data = new byte[100];
        LimitInputStream lis = new LimitInputStream(new ByteArrayInputStream(data), 10);
        assertTrue(lis.available() <= 10);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testNegativeLimitThrows() {
        new LimitInputStream(new ByteArrayInputStream(new byte[10]), -1);
    }

    @Test
    public void testZeroLimit() throws Exception {
        byte[] data = new byte[100];
        LimitInputStream lis = new LimitInputStream(new ByteArrayInputStream(data), 0);
        assertEquals(-1, lis.read());
    }

    @Test
    public void testSingleByteRead() throws Exception {
        byte[] data = {10, 20, 30};
        LimitInputStream lis = new LimitInputStream(new ByteArrayInputStream(data), 2);
        assertEquals(10, lis.read());
        assertEquals(20, lis.read());
        assertEquals(-1, lis.read());
    }
}

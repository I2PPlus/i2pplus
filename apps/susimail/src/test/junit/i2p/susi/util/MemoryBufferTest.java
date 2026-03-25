package i2p.susi.util;

import static org.junit.Assert.*;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import org.junit.Test;

/**
 *  Tests for MemoryBuffer.
 */
public class MemoryBufferTest {

    @Test
    public void testWriteAndRead() throws Exception {
        MemoryBuffer buf = new MemoryBuffer();
        OutputStream out = buf.getOutputStream();
        out.write(new byte[] {1, 2, 3, 4, 5});
        out.close();
        buf.writeComplete(true);

        InputStream in = buf.getInputStream();
        byte[] data = new byte[5];
        assertEquals(5, in.read(data));
        assertArrayEquals(new byte[] {1, 2, 3, 4, 5}, data);
    }

    @Test(expected = IOException.class)
    public void testReadBeforeWriteThrows() throws Exception {
        MemoryBuffer buf = new MemoryBuffer();
        buf.getInputStream();
    }

    @Test
    public void testWriteCompleteFalse() throws Exception {
        MemoryBuffer buf = new MemoryBuffer();
        OutputStream out = buf.getOutputStream();
        out.write(1);
        buf.writeComplete(false);

        // After writeComplete(false), getInputStream should throw
        try {
            buf.getInputStream();
            fail("Expected IOException");
        } catch (IOException expected) {}
    }

    @Test
    public void testDefaultCapacity() throws Exception {
        MemoryBuffer buf = new MemoryBuffer();
        assertNotNull(buf.getOutputStream());
    }

    @Test
    public void testCustomCapacity() throws Exception {
        MemoryBuffer buf = new MemoryBuffer(1024);
        OutputStream out = buf.getOutputStream();
        byte[] data = new byte[500];
        for (int i = 0; i < data.length; i++) data[i] = (byte) i;
        out.write(data);
        buf.writeComplete(true);

        InputStream in = buf.getInputStream();
        byte[] read = new byte[500];
        assertEquals(500, in.read(read));
        assertArrayEquals(data, read);
    }

    @Test
    public void testReadComplete() throws Exception {
        MemoryBuffer buf = new MemoryBuffer();
        buf.readComplete(true);
        // Should not throw
    }
}

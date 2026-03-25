package net.i2p.util;

import static org.junit.Assert.*;

import org.junit.Test;

public class SimpleByteCacheTest {

    @Test
    public void testAcquireSize() {
        byte[] buf = SimpleByteCache.acquire(32);
        assertNotNull(buf);
        assertEquals(32, buf.length);
    }

    @Test
    public void testAcquireReleaseReuse() {
        byte[] buf = SimpleByteCache.acquire(16);
        SimpleByteCache.release(buf);
        byte[] reused = SimpleByteCache.acquire(16);
        assertSame(buf, reused);
    }

    @Test
    public void testAcquireMultiple() {
        byte[] a = SimpleByteCache.acquire(8);
        byte[] b = SimpleByteCache.acquire(8);
        assertNotNull(a);
        assertNotNull(b);
        assertNotSame(a, b);
        SimpleByteCache.release(a);
        SimpleByteCache.release(b);
    }

    @Test
    public void testDifferentSizesGetDifferentCaches() {
        byte[] a = SimpleByteCache.acquire(16);
        byte[] b = SimpleByteCache.acquire(32);
        assertEquals(16, a.length);
        assertEquals(32, b.length);
    }

    @Test
    public void testClearAll() {
        byte[] a = SimpleByteCache.acquire(16);
        byte[] b = SimpleByteCache.acquire(32);
        SimpleByteCache.release(a);
        SimpleByteCache.release(b);
        SimpleByteCache.clearAll();
        byte[] c = SimpleByteCache.acquire(16);
        assertNotNull(c);
    }

    @Test
    public void testRepeatedAcquireRelease() {
        for (int i = 0; i < 100; i++) {
            byte[] buf = SimpleByteCache.acquire(64);
            assertNotNull(buf);
            assertEquals(64, buf.length);
            SimpleByteCache.release(buf);
        }
    }
}

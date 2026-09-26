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

    /**
     * getInstance(cacheSize, size) documents that cacheSize is applied to the
     * shared cache, so a later smaller value must shrink it. Uses an array
     * size no other component asks for, so the shared cache is untouched.
     */
    @Test
    public void testResizeShrinksSharedCache() {
        int size = 3001;
        SimpleByteCache.getInstance(4, size);
        byte[] a = new byte[size];
        byte[] b = new byte[size];
        byte[] c = new byte[size];
        byte[] d = new byte[size];
        SimpleByteCache.release(a);
        SimpleByteCache.release(b);
        SimpleByteCache.release(c);
        SimpleByteCache.release(d);
        SimpleByteCache.getInstance(1, size);
        int reused = 0;
        for (int i = 0; i < 4; i++) {
            byte[] got = SimpleByteCache.acquire(size);
            if (got == a || got == b || got == c || got == d) {
                reused++;
            }
        }
        assertEquals("only one array may be retained after shrinking to 1", 1, reused);
    }

    @Test
    public void testResizeGrowsSharedCache() {
        int size = 3002;
        SimpleByteCache.getInstance(1, size);
        byte[] a = new byte[size];
        SimpleByteCache.release(a);
        // grow before releasing the rest, so none is discarded
        SimpleByteCache.getInstance(4, size);
        byte[] b = new byte[size];
        byte[] c = new byte[size];
        SimpleByteCache.release(b);
        SimpleByteCache.release(c);
        assertSame(c, SimpleByteCache.acquire(size));
        assertSame(b, SimpleByteCache.acquire(size));
        assertSame(a, SimpleByteCache.acquire(size));
    }
}

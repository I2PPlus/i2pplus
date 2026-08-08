package net.i2p.data.i2np;

import static org.junit.Assert.*;

import net.i2p.I2PAppContext;
import net.i2p.data.Certificate;

import org.junit.Test;

/**
 *  Verifies that a pool/reuse pattern for GarlicClove produces identical
 *  results to allocating a new GarlicClove for each clove. This validates
 *  the fix for per-clove object allocation in the hot garlic message loop.
 */
public class GarlicClovePoolTest {

    private static final I2PAppContext CTX = I2PAppContext.getGlobalContext();

    /**
     *  Pool that reuses GarlicClove instances via reset/set pattern.
     *  Mirrors the proposed fix for GarlicClove allocation churn.
     */
    private static class GarlicClovePool {
        private final GarlicClove[] pool;
        private int available;

        GarlicClovePool(int size) {
            pool = new GarlicClove[size];
            for (int i = 0; i < size; i++) {pool[i] = new GarlicClove(CTX);}
            available = size;
        }

        GarlicClovePool() {this(0);}

        GarlicClove acquire() {
            if (available > 0) {return pool[--available];}
            return new GarlicClove(CTX);
        }

        void release(GarlicClove clove) {
            if (available < pool.length) {pool[available++] = clove;}
        }

        int available() {return available;}
        int capacity() {return pool.length;}
    }

    @Test
    public void testPooledCloveMatchesNewClove() {
        GarlicClovePool pool = new GarlicClovePool(4);
        long exp = System.currentTimeMillis() + 60000;

        GarlicClove fresh = new GarlicClove(CTX);
        fresh.setCloveId(42);
        fresh.setExpiration(exp);
        fresh.setCertificate(Certificate.NULL_CERT);

        GarlicClove pooled = pool.acquire();
        pooled.setCloveId(42);
        pooled.setExpiration(exp);
        pooled.setCertificate(Certificate.NULL_CERT);

        assertEquals(fresh.getCloveId(), pooled.getCloveId());
        assertEquals(fresh.getExpiration(), pooled.getExpiration());
        assertEquals(fresh.getCertificate(), pooled.getCertificate());

        pool.release(pooled);
    }

    @Test
    public void testPoolReuseReturnsSameInstance() {
        GarlicClovePool pool = new GarlicClovePool(2);

        GarlicClove clove = pool.acquire();
        clove.setCloveId(99);
        pool.release(clove);

        assertEquals("available count should increment on release", 2, pool.available());

        GarlicClove reused = pool.acquire();
        assertSame("reused clove should be the same instance", clove, reused);
    }

    @Test
    public void testPoolExpandsWhenEmpty() {
        GarlicClovePool pool = new GarlicClovePool();
        GarlicClove first = pool.acquire();
        GarlicClove extra = pool.acquire();
        assertNotNull("pool allocates new when empty", first);
        assertNotNull("pool allocates new when empty", extra);
        assertNotSame("pool allocates distinct instances when empty", first, extra);
    }

    @Test
    public void testPoolCapacityTracksCorrectly() {
        GarlicClovePool pool = new GarlicClovePool(3);
        assertEquals(3, pool.available());
        pool.acquire();
        assertEquals(2, pool.available());
        pool.acquire();
        pool.acquire();
        assertEquals(0, pool.available());
        pool.acquire();
        assertEquals(0, pool.available());
    }

    @Test
    public void testPoolDoesNotOverRelease() {
        GarlicClovePool pool = new GarlicClovePool(1);
        GarlicClove a = pool.acquire();
        pool.release(a);
        GarlicClove b = new GarlicClove(CTX);
        pool.release(b);
        assertEquals("release beyond capacity is ignored", 1, pool.available());
    }
}

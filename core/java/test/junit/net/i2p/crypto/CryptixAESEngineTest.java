package net.i2p.crypto;

/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain
 * with no warranty of any kind, either expressed or implied.
 * It probably won't  make your computer catch on fire, or eat
 * your children, but it might.  Use at your own risk.
 *
 */

import junit.framework.TestCase;

import net.i2p.I2PAppContext;
import net.i2p.data.DataHelper;
import net.i2p.data.SessionKey;

public class CryptixAESEngineTest extends TestCase {
    public void testED() {
        I2PAppContext ctx = I2PAppContext.getGlobalContext();
        SessionKey key = ctx.keyGenerator().generateSessionKey();
        byte[] iv = new byte[16];
        byte[] orig = new byte[128];
        byte[] encrypted = new byte[128];
        byte[] decrypted = new byte[128];
        ctx.random().nextBytes(iv);
        ctx.random().nextBytes(orig);
        CryptixAESEngine aes = new CryptixAESEngine(ctx);
        aes.encrypt(orig, 0, encrypted, 0, key, iv, orig.length);
        aes.decrypt(encrypted, 0, decrypted, 0, key, iv, encrypted.length);
        assertTrue(DataHelper.eq(decrypted, orig));
    }

    public static void testED2() {
        I2PAppContext ctx = I2PAppContext.getGlobalContext();
        SessionKey key = ctx.keyGenerator().generateSessionKey();
        byte[] iv = new byte[16];
        byte[] orig = new byte[128];
        byte[] data = new byte[128];
        ctx.random().nextBytes(iv);
        ctx.random().nextBytes(orig);
        CryptixAESEngine aes = new CryptixAESEngine(ctx);
        aes.encrypt(orig, 0, data, 0, key, iv, data.length);
        aes.decrypt(data, 0, data, 0, key, iv, data.length);
        assertTrue(DataHelper.eq(data, orig));
    }

    public static void testFake() {
        I2PAppContext ctx = I2PAppContext.getGlobalContext();
        SessionKey key = ctx.keyGenerator().generateSessionKey();
        SessionKey wrongKey = ctx.keyGenerator().generateSessionKey();
        byte[] iv = new byte[16];
        byte[] orig = new byte[128];
        byte[] encrypted = new byte[128];
        byte[] decrypted = new byte[128];
        ctx.random().nextBytes(iv);
        ctx.random().nextBytes(orig);
        CryptixAESEngine aes = new CryptixAESEngine(ctx);
        aes.encrypt(orig, 0, encrypted, 0, key, iv, orig.length);
        aes.decrypt(encrypted, 0, decrypted, 0, wrongKey, iv, encrypted.length);
        assertFalse(DataHelper.eq(decrypted, orig));
    }

    public static void testNull() {
        I2PAppContext ctx = I2PAppContext.getGlobalContext();
        SessionKey key = ctx.keyGenerator().generateSessionKey();
        SessionKey wrongKey = ctx.keyGenerator().generateSessionKey();
        byte[] iv = new byte[16];
        byte[] orig = new byte[128];
        byte[] encrypted = new byte[128];
        byte[] decrypted = new byte[128];
        ctx.random().nextBytes(iv);
        ctx.random().nextBytes(orig);
        CryptixAESEngine aes = new CryptixAESEngine(ctx);
        aes.encrypt(orig, 0, encrypted, 0, key, iv, orig.length);

        boolean error = false;
        try {
            aes.decrypt(null, 0, null, 0, wrongKey, iv, encrypted.length);
        } catch (IllegalArgumentException iae) {
            error = true;
        }
        assertTrue(error);
    }

    public static void testEDBlock() {
        I2PAppContext ctx = I2PAppContext.getGlobalContext();
        SessionKey key = ctx.keyGenerator().generateSessionKey();
        byte[] iv = new byte[16];
        byte[] orig = new byte[16];
        byte[] encrypted = new byte[16];
        byte[] decrypted = new byte[16];
        ctx.random().nextBytes(iv);
        ctx.random().nextBytes(orig);
        CryptixAESEngine aes = new CryptixAESEngine(ctx);
        aes.encryptBlock(orig, 0, key, encrypted, 0);
        aes.decryptBlock(encrypted, 0, key, decrypted, 0);
        assertTrue(DataHelper.eq(decrypted, orig));
    }

    public static void testEDBlock2() {
        I2PAppContext ctx = I2PAppContext.getGlobalContext();
        SessionKey key = ctx.keyGenerator().generateSessionKey();
        byte[] iv = new byte[16];
        byte[] orig = new byte[16];
        byte[] data = new byte[16];
        ctx.random().nextBytes(iv);
        ctx.random().nextBytes(orig);
        CryptixAESEngine aes = new CryptixAESEngine(ctx);
        aes.encryptBlock(orig, 0, key, data, 0);
        aes.decryptBlock(data, 0, key, data, 0);
        assertTrue(DataHelper.eq(data, orig));
    }

    /**
     *  Round trip through the JVM (system-AES) path, which only engages for
     *  payloads of at least 640 bytes on AES-NI/unlimited-policy JVMs; on
     *  other JVMs this exercises the Cryptix fallback path, which is fine — the
     *  test's job is a large-payload round trip.
     */
    public void testEDLarge() {
        I2PAppContext ctx = I2PAppContext.getGlobalContext();
        SessionKey key = ctx.keyGenerator().generateSessionKey();
        byte[] iv = new byte[16];
        byte[] orig = new byte[1024];
        byte[] encrypted = new byte[1024];
        byte[] decrypted = new byte[1024];
        ctx.random().nextBytes(iv);
        ctx.random().nextBytes(orig);
        CryptixAESEngine aes = new CryptixAESEngine(ctx);
        aes.encrypt(orig, 0, encrypted, 0, key, iv, orig.length);
        aes.decrypt(encrypted, 0, decrypted, 0, key, iv, encrypted.length);
        assertTrue(DataHelper.eq(decrypted, orig));
    }

    /** Same session key object must reuse the memoized key spec. */
    public void testKeySpecReusedForSameSessionKey() {
        I2PAppContext ctx = I2PAppContext.getGlobalContext();
        SessionKey key = ctx.keyGenerator().generateSessionKey();
        CryptixAESEngine aes = new CryptixAESEngine(ctx);
        javax.crypto.spec.SecretKeySpec a = aes.getKeySpec(key);
        javax.crypto.spec.SecretKeySpec b = aes.getKeySpec(key);
        assertTrue("same key must reuse the same SecretKeySpec instance", a == b);
    }

    /** A SessionKey's equals is value-based, so a new object with equal bytes must share the entry. */
    public void testKeySpecSharesAcrossEqualKeyObjects() {
        I2PAppContext ctx = I2PAppContext.getGlobalContext();
        byte[] raw = ctx.keyGenerator().generateSessionKey().getData();
        SessionKey k1 = new SessionKey(raw);
        SessionKey k2 = new SessionKey(raw); // equal bytes, distinct object
        CryptixAESEngine aes = new CryptixAESEngine(ctx);
        javax.crypto.spec.SecretKeySpec a = aes.getKeySpec(k1);
        javax.crypto.spec.SecretKeySpec b = aes.getKeySpec(k2);
        assertTrue("value-equal keys must share the memoized spec", a == b);
    }

    /** Distinct keys must not collide in the cache. */
    public void testKeySpecDistinctForDistinctKeys() {
        I2PAppContext ctx = I2PAppContext.getGlobalContext();
        SessionKey k1 = ctx.keyGenerator().generateSessionKey();
        SessionKey k2 = ctx.keyGenerator().generateSessionKey();
        CryptixAESEngine aes = new CryptixAESEngine(ctx);
        javax.crypto.spec.SecretKeySpec a = aes.getKeySpec(k1);
        javax.crypto.spec.SecretKeySpec b = aes.getKeySpec(k2);
        assertTrue("distinct keys must not share a spec", a != b);
    }

    /** The LRU must evict the least-recently-used entry once the bound is reached. */
    public void testKeySpecCacheBoundedByLru() {
        I2PAppContext ctx = I2PAppContext.getGlobalContext();
        CryptixAESEngine aes = new CryptixAESEngine(ctx);
        SessionKey first = ctx.keyGenerator().generateSessionKey();
        javax.crypto.spec.SecretKeySpec firstSpec = aes.getKeySpec(first);
        // Touch exactly KEY_SPEC_CACHE_SIZE other distinct keys so `first`
        // becomes the least recently used and is evicted by the next insert.
        for (int i = 0; i < CryptixAESEngine.KEY_SPEC_CACHE_SIZE; i++) {
            aes.getKeySpec(ctx.keyGenerator().generateSessionKey());
        }
        javax.crypto.spec.SecretKeySpec again = aes.getKeySpec(first);
        assertTrue("LRU eviction must rebuild the spec for a displaced key", again != firstSpec);
        // The rebuilt spec must still encrypt/decrypt correctly.
        byte[] iv = new byte[16];
        byte[] orig = new byte[1024];
        byte[] encrypted = new byte[1024];
        byte[] decrypted = new byte[1024];
        ctx.random().nextBytes(iv);
        ctx.random().nextBytes(orig);
        aes.encrypt(orig, 0, encrypted, 0, first, iv, orig.length);
        aes.decrypt(encrypted, 0, decrypted, 0, first, iv, encrypted.length);
        assertTrue(DataHelper.eq(decrypted, orig));
    }
}

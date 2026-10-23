package net.i2p.crypto;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Field;
import java.util.Queue;
import java.util.concurrent.LinkedBlockingQueue;

import javax.crypto.Cipher;

import org.junit.Test;

import net.i2p.I2PAppContext;
import net.i2p.data.SessionKey;

/**
 * The pooled Cipher must go back to the cache on every exit from the system-AES
 * branch. decrypt() has no mod-16 guard, so doFinal() throws
 * IllegalBlockSizeException for a non-aligned length on every such call, and a
 * Cipher dropped there is re-created by the next acquire() - provider lookup and
 * key schedule, per call, on a path a peer can steer us down.
 * <p>
 * These tests only traverse the system-AES branch when the JVM has AES-NI and
 * the unlimited policy, which is what the surrounding suite already assumes.
 */
public class CryptixAESEngineCipherPoolTest {

    /**
     * The engine's Cipher cache, reached reflectively so the leak is observable.
     *
     * @param aes the engine whose private _ciphers field is read
     * @return the engine's live Cipher cache queue
     * @throws Exception if the private field is missing or inaccessible by reflection
     */
    @SuppressWarnings("unchecked")
    private static Queue<Cipher> poolOf(CryptixAESEngine aes) throws Exception {
        Field f = CryptixAESEngine.class.getDeclaredField("_ciphers");
        f.setAccessible(true);
        return (Queue<Cipher>) f.get(aes);
    }

    /**
     * Whether the engine itself takes the system-AES branch on this JVM. Read
     * from the engine's own constant instead of re-deriving it from AES-NI
     * alone: a JVM with AES-NI but a restricted JCE policy has the branch off,
     * and must skip these tests rather than drive a path the engine never takes.
     *
     * @return true if the engine's USE_SYSTEM_AES constant is set, false if it
     *         cannot be read reflectively
     */
    private static boolean systemAESActive() {
        try {
            Field f = CryptixAESEngine.class.getDeclaredField("USE_SYSTEM_AES");
            f.setAccessible(true);
            return f.getBoolean(null);
        } catch (ReflectiveOperationException e) {
            return false;
        }
    }

    /**
     * A decrypt whose length is not a multiple of 16 makes doFinal() throw. The
     * Cipher taken for that attempt must still be back in the cache afterwards.
     *
     * @throws Exception if a seeded Cipher instance cannot be created
     */
    @Test
    public void testNonAlignedDecryptReturnsCipherToPool() throws Exception {
        org.junit.Assume.assumeTrue("system AES path inactive on this JVM", systemAESActive());
        I2PAppContext ctx = I2PAppContext.getGlobalContext();
        CryptixAESEngine aes = new CryptixAESEngine(ctx);
        SessionKey key = ctx.keyGenerator().generateSessionKey();
        byte[] iv = new byte[16];
        ctx.random().nextBytes(iv);
        byte[] payload = new byte[1024];
        ctx.random().nextBytes(payload);
        byte[] out = new byte[1024];

        Queue<Cipher> pool = poolOf(aes);
        pool.clear();
        // seed the cache with two known ciphers
        Cipher seed1 = Cipher.getInstance("AES/CBC/NoPadding");
        Cipher seed2 = Cipher.getInstance("AES/CBC/NoPadding");
        pool.add(seed1);
        pool.add(seed2);

        // 641 >= MIN_SYSTEM_AES_LENGTH and is not a multiple of 16
        aes.decrypt(payload, 0, out, 0, key, iv, 641);

        assertEquals("the Cipher used by the failed attempt must be back in the cache",
                     2, pool.size());
        assertTrue("cache must still hold real Ciphers",
                   pool.contains(seed1) || pool.contains(seed2));
    }

    /**
     * Same invariant on the encrypt side, whose length guard makes the throw rare.
     *
     * @throws Exception if the seeded Cipher instance cannot be created
     */
    @Test
    public void testAlignedEncryptReturnsCipherToPool() throws Exception {
        org.junit.Assume.assumeTrue("system AES path inactive on this JVM", systemAESActive());
        I2PAppContext ctx = I2PAppContext.getGlobalContext();
        CryptixAESEngine aes = new CryptixAESEngine(ctx);
        SessionKey key = ctx.keyGenerator().generateSessionKey();
        byte[] iv = new byte[16];
        ctx.random().nextBytes(iv);
        byte[] payload = new byte[1024];
        ctx.random().nextBytes(payload);
        byte[] out = new byte[1024];

        Queue<Cipher> pool = poolOf(aes);
        pool.clear();
        pool.add(Cipher.getInstance("AES/CBC/NoPadding"));

        aes.encrypt(payload, 0, out, 0, key, iv, payload.length);

        assertEquals(1, pool.size());
    }

    /**
     * Repeated non-aligned decrypts must not drain the cache: each one borrows a
     * Cipher and puts it back, so the cache size stays flat instead of churning.
     *
     * @throws Exception if a seeded Cipher instance cannot be created
     */
    @Test
    public void testRepeatedNonAlignedDecryptsDoNotDrainCache() throws Exception {
        org.junit.Assume.assumeTrue("system AES path inactive on this JVM", systemAESActive());
        I2PAppContext ctx = I2PAppContext.getGlobalContext();
        CryptixAESEngine aes = new CryptixAESEngine(ctx);
        SessionKey key = ctx.keyGenerator().generateSessionKey();
        byte[] iv = new byte[16];
        ctx.random().nextBytes(iv);
        byte[] payload = new byte[1024];
        ctx.random().nextBytes(payload);
        byte[] out = new byte[1024];

        Queue<Cipher> pool = poolOf(aes);
        pool.clear();
        for (int i = 0; i < 3; i++)
            pool.add(Cipher.getInstance("AES/CBC/NoPadding"));

        for (int i = 0; i < 200; i++)
            aes.decrypt(payload, 0, out, 0, key, iv, 641);

        assertEquals("200 failed attempts must not have consumed any cached Cipher",
                     3, pool.size());
    }

    /**
     * The cache is bounded; seeding past the cap must not grow it unboundedly.
     *
     * @throws Exception if the private cache field is inaccessible by reflection
     */
    @Test
    public void testCacheIsBounded() throws Exception {
        org.junit.Assume.assumeTrue("system AES path inactive on this JVM", systemAESActive());
        I2PAppContext ctx = I2PAppContext.getGlobalContext();
        CryptixAESEngine aes = new CryptixAESEngine(ctx);
        Queue<Cipher> pool = poolOf(aes);
        assertTrue(pool instanceof LinkedBlockingQueue<?>);
        int cap = ((LinkedBlockingQueue<Cipher>) pool).remainingCapacity() + pool.size();
        assertTrue("cache must have a finite cap, got " + cap, cap > 0 && cap < 10000);
    }
}

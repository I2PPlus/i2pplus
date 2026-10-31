package net.i2p.util;

import static org.junit.Assert.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.junit.Test;

public class PasswordManagerTest {

    @Test
    public void testMd5HexBasic() {
        String hash = PasswordManager.md5Hex("test");
        assertNotNull(hash);
        assertEquals(32, hash.length()); // MD5 hex = 32 chars
    }

    @Test
    public void testMd5HexDeterministic() {
        String h1 = PasswordManager.md5Hex("password");
        String h2 = PasswordManager.md5Hex("password");
        assertEquals(h1, h2);
    }

    @Test
    public void testMd5HexDifferentInputs() {
        String h1 = PasswordManager.md5Hex("password1");
        String h2 = PasswordManager.md5Hex("password2");
        assertNotEquals(h1, h2);
    }

    @Test
    public void testMd5HexRealm() {
        String hash = PasswordManager.md5Hex("realm", "user", "pass");
        assertNotNull(hash);
        assertEquals(32, hash.length());
    }

    @Test
    public void testMd5HexRealmDifferent() {
        String h1 = PasswordManager.md5Hex("realm1", "user", "pass");
        String h2 = PasswordManager.md5Hex("realm2", "user", "pass");
        assertNotEquals(h1, h2);
    }

    @Test
    public void testSha256HexBasic() {
        String hash = PasswordManager.sha256Hex("test");
        assertNotNull(hash);
        assertEquals(64, hash.length()); // SHA-256 hex = 64 chars
    }

    @Test
    public void testSha256HexDeterministic() {
        String h1 = PasswordManager.sha256Hex("password");
        String h2 = PasswordManager.sha256Hex("password");
        assertEquals(h1, h2);
    }

    @Test
    public void testSha256HexDifferentInputs() {
        String h1 = PasswordManager.sha256Hex("password1");
        String h2 = PasswordManager.sha256Hex("password2");
        assertNotEquals(h1, h2);
    }

    @Test
    public void testSha256HexRealm() {
        String hash = PasswordManager.sha256Hex("realm", "user", "pass");
        assertNotNull(hash);
        assertEquals(64, hash.length());
    }

    @Test
    public void testMd5Sum() {
        byte[] data = "test".getBytes(StandardCharsets.UTF_8);
        byte[] sum = PasswordManager.md5Sum(data);
        assertNotNull(sum);
        assertEquals(16, sum.length);
    }

    @Test
    public void testMd5SumDeterministic() {
        byte[] data = "test".getBytes(StandardCharsets.UTF_8);
        byte[] s1 = PasswordManager.md5Sum(data);
        byte[] s2 = PasswordManager.md5Sum(data);
        assertArrayEquals(s1, s2);
    }

    @Test
    public void testMd5AndSha256Differ() {
        String md5 = PasswordManager.md5Hex("test");
        String sha256 = PasswordManager.sha256Hex("test");
        assertNotEquals(md5, sha256);
    }

    /** The bytes of a string, as the sums take UTF-8. */
    private static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    /** The reference digest, taken from a fresh provider instance. */
    private static byte[] sum(String alg, byte[] data) throws Exception {
        return MessageDigest.getInstance(alg).digest(data);
    }

    /** Copy a sum into the caller's array, for the other-thread test. */
    private static void copy(byte[] from, byte[] to) {
        System.arraycopy(from, 0, to, 0, from.length);
    }

    /**
     * The per-thread digest has to produce what a fresh MessageDigest
     * produces, which is the whole point of caching it.
     */
    @Test
    public void testSumsMatchAFreshMessageDigest() throws Exception {
        byte[][] inputs = {new byte[0], utf8("x"),
                           utf8("the quick brown fox")};
        for (byte[] data : inputs) {
            assertArrayEquals(sum("SHA-256", data),
                              PasswordManager.sha256Sum(data));
            assertArrayEquals(sum("MD5", data),
                              PasswordManager.md5Sum(data));
        }
    }

    /**
     * Both digests run on one thread, so a SHA-256 call must not leave
     * update state for the MD5 call that follows it.
     */
    @Test
    public void testInterleavedSumsDoNotShareState() throws Exception {
        byte[] a = utf8("alpha");
        byte[] b = utf8("bravo");
        PasswordManager.sha256Sum(a);
        assertArrayEquals(sum("MD5", b), PasswordManager.md5Sum(b));
        assertArrayEquals(sum("SHA-256", a),
                          PasswordManager.sha256Sum(a));
    }

    /**
     * A MessageDigest is not thread-safe, so the cache is per-thread: a
     * second thread must get its own instance and agree on the answer.
     */
    @Test
    public void testSumsFromAnotherThread() throws Exception {
        byte[] data = utf8("shared across threads");
        byte[] expected = sum("SHA-256", data);
        byte[] other = new byte[expected.length];
        Runnable run = () -> copy(PasswordManager.sha256Sum(data),
                                  other);
        Thread t = new Thread(run);
        t.start();
        t.join();
        assertArrayEquals(expected, other);
    }
}

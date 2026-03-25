package net.i2p.util;

import static org.junit.Assert.*;

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
        byte[] data = "test".getBytes();
        byte[] sum = PasswordManager.md5Sum(data);
        assertNotNull(sum);
        assertEquals(16, sum.length);
    }

    @Test
    public void testMd5SumDeterministic() {
        byte[] data = "test".getBytes();
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
}

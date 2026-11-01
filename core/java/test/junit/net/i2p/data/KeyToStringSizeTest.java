package net.i2p.data;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * The key classes' toString()s report the size, never the bytes. The base
 * form inherited from {@link SimpleDataStructure} prints any structure of 32
 * bytes or less in full base64, which is exactly the size of an AES session
 * key, so every log site that concatenated a SessionKey echoed the key.
 * SessionKey and PrivateKey now override it; these tests pin the override so
 * a future refactor back onto the base form fails loudly.
 *
 * @since 0.9.73+
 */
public class KeyToStringSizeTest {

    /** Deterministic key bytes, so the leaked string is knowable. */
    private static final byte[] KEY_DATA = keyData();

    /**
     * A fresh test instance per method, as JUnit builds one per test; the
     * class carries only the fixed key bytes.
     *
     * @since 0.9.73+
     */
    public KeyToStringSizeTest() {}

    /**
     * Deterministic key bytes.
     *
     * @return 32 bytes
     */
    private static byte[] keyData() {
        byte[] data = new byte[32];
        for (int i = 0; i < data.length; i++) {
            data[i] = (byte) (i * 3 + 1);
        }
        return data;
    }

    /**
     * A session key renders as its size, not its base64.
     */
    @Test
    public void testSessionKeyReportsSize() {
        SessionKey key = new SessionKey(KEY_DATA);
        String s = key.toString();
        assertTrue(s, s.contains("Size: 32 bytes"));
        assertFalse("key bytes leaked: " + s, s.contains(key.toBase64()));
    }

    /**
     * A session key with no data says so, as the base form does for a
     * destroyed key.
     */
    @Test
    public void testSessionKeyWithoutData() {
        SessionKey key = new SessionKey();
        String s = key.toString();
        assertTrue(s, s.contains("null"));
    }

    /**
     * A private key renders as its type and size, not its base64.
     */
    @Test
    public void testPrivateKeyReportsSize() {
        PrivateKey key = new PrivateKey(net.i2p.crypto.EncType.ECIES_X25519, KEY_DATA);
        String s = key.toString();
        assertTrue(s, s.contains("Size: 32 bytes"));
        assertFalse("key bytes leaked: " + s, s.contains(key.toBase64()));
    }
}

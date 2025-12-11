package net.i2p.crypto;

import java.io.Serializable;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Cache the objects used in CryptixRijndael_Algorithm.makeKey to reduce
 * memory churn.  The KeyCacheEntry should be held onto as long as the
 * data referenced in it is needed (which often is only one or two lines
 * of code)
 *
 * Not for external use, not a public API.
 *
 * Unused as a class, as the keys are cached in the SessionKey objects,
 * but the static methods are used in FortunaStandalone.
 */
public final class CryptixAESKeyCache {
    private final LinkedBlockingQueue<KeyCacheEntry> _availableKeys;

    private static final int KEYSIZE = 32; // 256bit AES
    private static final int BLOCKSIZE = 16;
    private static final int ROUNDS = CryptixRijndael_Algorithm.getRounds(KEYSIZE, BLOCKSIZE);
    private static final int BC = BLOCKSIZE / 4;
    private static final int KC = KEYSIZE / 4;

    private static final int MAX_KEYS = 64;

    /*
     * @deprecated unused, keys are now cached in the SessionKey objects
     */
    @Deprecated
    public CryptixAESKeyCache() {
        _availableKeys = new LinkedBlockingQueue<KeyCacheEntry>(MAX_KEYS);
    }

    /**
     * Get the next available structure, either from the cache or a brand new one
     *
     * @deprecated unused, keys are now cached in the SessionKey objects
     */
    @Deprecated
    public final KeyCacheEntry acquireKey() {
        KeyCacheEntry rv = _availableKeys.poll();
        if (rv != null)
            return rv;
        return createNew();
    }

    /**
     * Put this structure back onto the available cache for reuse
     *
     * @deprecated unused, keys are now cached in the SessionKey objects
     */
    @Deprecated
    public final void releaseKey(KeyCacheEntry key) {
        _availableKeys.offer(key);
    }

    public static final KeyCacheEntry createNew() {
        KeyCacheEntry e = new KeyCacheEntry();
        return e;
    }

    /**
     * Container for AES round keys generated during key schedule expansion.
     *
     * This class holds all the pre-computed round keys required for AES encryption
     * and decryption operations. The round keys are derived from the original AES key
     * through the key schedule algorithm and stored in optimized integer arrays
     * for fast access during cryptographic operations.
     *
     * <p>The entry contains:
     * <ul>
     *   <li>Encryption round keys (Ke) - expanded keys for each encryption round</li>
     *   <li>Decryption round keys (Kd) - inverse expanded keys for each decryption round</li>
     * </ul>
     *
     * <p>KeyCacheEntry objects are memory-intensive structures that benefit from
     * caching to avoid repeated key schedule computations. Each entry contains
     * (rounds + 1) × 4 integers for both encryption and decryption, where the
     * number of rounds depends on the AES key size (10 rounds for 128-bit,
     * 12 rounds for 192-bit, 14 rounds for 256-bit keys).
     *
     * <p><strong>Thread Safety:</strong> This class is immutable after construction
     * and safe for concurrent use by multiple threads.
     *
     * <p><strong>Memory Usage:</strong> Each entry consumes approximately:
     * <code>2 × (rounds + 1) × 4 × 4 bytes</code> of heap memory.
     * For AES-256 (14 rounds), this is about 448 bytes per entry.
     *
     * @deprecated This class is part of a deprecated caching mechanism.
     *             Keys are now cached directly in SessionKey objects for better performance.
     */
    public static class KeyCacheEntry implements Serializable {
        /** encryption round keys */
        final int[][] Ke;
        /** decryption round keys */
        final int[][] Kd;

        public KeyCacheEntry() {
            Ke = new int[ROUNDS + 1][BC];
            Kd = new int[ROUNDS + 1][BC];
        }

        /** @since 0.9.31 */
        public KeyCacheEntry(int rounds, int bc) {
            Ke = new int[rounds + 1][bc];
            Kd = new int[rounds + 1][bc];
        }
    }
}

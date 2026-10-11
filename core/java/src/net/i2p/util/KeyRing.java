package net.i2p.util;

import net.i2p.data.Hash;
import net.i2p.data.SessionKey;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe mapping from router hashes to session keys.
 *
 * Extends ConcurrentHashMap for concurrent access to cryptographic key storage.
 */
public class KeyRing extends ConcurrentHashMap<Hash, SessionKey> {
    /**
     * Creates an empty ring with no initial capacity reserved.
     */
    public KeyRing() {
        super(0);
    }
}

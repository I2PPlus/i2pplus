package net.i2p.util;

import java.io.IOException;
import java.io.Writer;

import java.util.concurrent.ConcurrentHashMap;

import net.i2p.data.Hash;
import net.i2p.data.SessionKey;

/**
 * Simple key ring for storing session keys by hash.
 * 
 * <p>This class provides a thread-safe mapping from router hashes to their
 * corresponding session keys. It extends ConcurrentHashMap to provide
 * concurrent access capabilities for cryptographic key storage.</p>
 * 
 * <p><strong>Note:</strong> The HTML rendering functionality has been deprecated
 * and moved to the router console since version 0.9.33.</p>
 */
public class KeyRing extends ConcurrentHashMap<Hash, SessionKey> {
    public KeyRing() {
        super(0);
    }

    /**
     *  @deprecated unused since 0.9.33; code moved to routerconsole
     */
    @Deprecated
    public void renderStatusHTML(Writer out) throws IOException {}
}

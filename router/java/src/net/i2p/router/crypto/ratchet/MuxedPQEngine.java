package net.i2p.router.crypto.ratchet;

import net.i2p.crypto.EncAlgo;
import net.i2p.crypto.EncType;
import net.i2p.data.DataFormatException;
import net.i2p.data.PrivateKey;
import net.i2p.router.RouterContext;
import net.i2p.router.message.CloveSet;
import net.i2p.util.Log;

/**
 * Post-quantum hybrid decryption engine supporting both ECIES and ML-KEM with adaptive ordering.
 *
 * @since 0.9.67
 */
final class MuxedPQEngine {
    private final RouterContext _context;
    private final Log _log;

    /**
     * Create the engine for the given router context.
     *
     * @param ctx the router context supplying the log manager used by this engine
     */
    public MuxedPQEngine(RouterContext ctx) {
        _context = ctx;
        _log = _context.logManager().getLog(MuxedPQEngine.class);
    }

    /**
     * The minimum new session size for the given encryption type.
     * Sizes come from ECIESAEADEngine, the single source of truth for
     * the wire format.
     *
     * @param type the encryption type to get the size for
     * @return the minimum new-session message size in bytes
     */
    private static int getMinNSSize(EncType type) {
        switch(type) {
          case ECIES_X25519:
              return ECIESAEADEngine.MIN_NS_SIZE;
          case MLKEM512_X25519:
              return ECIESAEADEngine.MIN_NS_MLKEM512_SIZE;
          case MLKEM768_X25519:
              return ECIESAEADEngine.MIN_NS_MLKEM768_SIZE;
          case MLKEM1024_X25519:
              return ECIESAEADEngine.MIN_NS_MLKEM1024_SIZE;
          default:
              return ECIESAEADEngine.MIN_NS_SIZE;
        }
    }

    /**
     * Decrypt the message with the given private keys.
     *
     * @param data the data to decrypt
     * @param ecKey must be EC, non-null
     * @param pqKey must be PQ, non-null
     * @param keyManager the key manager
     * @return decrypted data or null on failure
     * @throws net.i2p.data.DataFormatException if the data is malformed
     */
    public CloveSet decrypt(byte[] data, PrivateKey ecKey, PrivateKey pqKey, MuxedPQSKM keyManager) throws DataFormatException {
        if (ecKey.getType() != EncType.ECIES_X25519 ||
            pqKey.getType().getBaseAlgorithm() != EncAlgo.ECIES_MLKEM) {
            if (_log.shouldWarn()) {
                _log.warn("Invalid key types for PQ decrypt - EC: " + ecKey.getType() +
                         " PQ: " + pqKey.getType() + " Base: " + pqKey.getType().getBaseAlgorithm());
            }
            throw new IllegalArgumentException("Invalid key types - EC: " + ecKey.getType() +
                                           " PQ: " + pqKey.getType());
        }
        final boolean debug = _log.shouldDebug();
        final boolean warn = _log.shouldWarn();
        CloveSet rv = null;
        // Try in-order from fastest to slowest
        boolean preferRatchet = keyManager.preferRatchet();

        if (preferRatchet) {
            // Ratchet Tag
            rv = _context.eciesEngine().decryptFast(data, ecKey, keyManager.getECSKM());
            if (rv != null) {
                if (debug)
                    _log.debug("Ratchet tag decryption successful");
                return rv;
            }
            if (debug)
                _log.debug("Ratchet tag not found before PQ -> Attempting to use PQ tag...");
        }

        // PQ Tag
        rv = _context.eciesEngine().decryptFast(data, pqKey, keyManager.getPQSKM());
        if (rv != null) {
            if (debug)
                _log.debug("PQ tag decryption successful");
            return rv;
        }
        if (debug)
            _log.debug("PQ tag not found -> Attempting fallback to ratchet tag...");

        if (!preferRatchet) {
            // Ratchet Tag
            rv = _context.eciesEngine().decryptFast(data, ecKey, keyManager.getECSKM());
            if (rv != null) {
                if (debug)
                    _log.debug("Fallback to ratchet tag decryption successful");
                return rv;
            }
            if (debug)
                _log.debug("Fallback ratchet tag not found -> Attempting to create a new session...");
        }

        // New Session attempts
        if (preferRatchet) {
            // Ratchet DH
            rv = _context.eciesEngine().decryptSlow(data, ecKey, keyManager.getECSKM());
            boolean ok = rv != null;
            keyManager.reportDecryptResult(true, ok);
            if (ok) {
                if (debug)
                    _log.debug("Ratchet new session decryption successful");
                return rv;
            }
            if (debug)
                _log.debug("Ratchet new session decryption failed before PQ - attempting PQ new session");
        }

        // PQ DH
        // Minimum size checks for the larger New Session message are in ECIESAEADEngine.x_decryptSlow().
        rv = _context.eciesEngine().decryptSlow(data, pqKey, keyManager.getPQSKM());
        boolean isok = rv != null;
        keyManager.reportDecryptResult(false, isok);
        if (isok) {
            if (debug)
                _log.debug("PQ new session decryption successful!");
            return rv;
        }
        if (debug || warn) {
            String msg = "PQ new session decryption failed";
            int minSize = getMinNSSize(pqKey.getType());
            if (data.length < minSize) {
                msg += " -> Data too small (" + data.length + " < " + minSize + ")";
            } else {
                msg += " -> Cryptographic failure";
            }
            if (warn) {
                // Throttled - this fires on every junk NewSession message.
                // The key must not vary per occurrence (no data.length in it)
                // or the suppressor cannot collapse it.
                _log.warnThrottled("pq-ns-fail", msg + " after all tag attempts failed");
            } else {
                _log.debug(msg);
            }
        }

        if (!preferRatchet) {
            // Ratchet DH
            rv = _context.eciesEngine().decryptSlow(data, ecKey, keyManager.getECSKM());
            boolean ok = rv != null;
            keyManager.reportDecryptResult(true, ok);
            if (!ok && debug)
                _log.debug("Fallback ratchet new session decryption failed after PQ");
        }
        return rv;
    }
}

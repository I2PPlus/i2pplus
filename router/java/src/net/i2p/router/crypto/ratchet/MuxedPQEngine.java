package net.i2p.router.crypto.ratchet;

import java.util.concurrent.atomic.AtomicLong;
import net.i2p.crypto.EncAlgo;
import net.i2p.crypto.EncType;
import net.i2p.data.DataFormatException;
import net.i2p.data.PrivateKey;
import net.i2p.router.RouterContext;
import net.i2p.router.message.CloveSet;
import net.i2p.util.Log;

/**
 * Post-quantum hybrid decryption engine supporting both ECIES and ML-KEM with adaptive ordering
 *
 * @since 0.9.67
 */
final class MuxedPQEngine {
    private static final long WARN_THROTTLE_MS = 5_000;
    private static final AtomicLong _lastPQWarn = new AtomicLong(0);

    private final RouterContext _context;
    private final Log _log;

    public MuxedPQEngine(RouterContext ctx) {
        _context = ctx;
        _log = _context.logManager().getLog(MuxedPQEngine.class);
    }

    // Constants for minimum new session sizes (copied from ECIESAEADEngine)
    private static final int TAGLEN = 8;
    private static final int MACLEN = 16;
    private static final int KEYLEN = 32;
    private static final int BHLEN = 3; // RatchetPayload.BLOCK_HEADER_SIZE
    private static final int DATETIME_SIZE = BHLEN + 4; // 7
    private static final int NS_OVERHEAD = KEYLEN + KEYLEN + MACLEN + MACLEN; // 96
    private static final int MIN_NS_SIZE = NS_OVERHEAD + DATETIME_SIZE; // 103
    private static final int NS_MLKEM_OVERHEAD = NS_OVERHEAD + MACLEN; // 112
    private static final int MIN_NS_MLKEM512_SIZE = 800 + NS_MLKEM_OVERHEAD + DATETIME_SIZE; // 919
    private static final int MIN_NS_MLKEM768_SIZE = 1184 + NS_MLKEM_OVERHEAD + DATETIME_SIZE; // 1303
    private static final int MIN_NS_MLKEM1024_SIZE = 1568 + NS_MLKEM_OVERHEAD + DATETIME_SIZE; // 1687

    /**
     * Get minimum new session size for the given encryption type
     */
    private static int getMinNSSize(EncType type) {
        switch(type) {
          case ECIES_X25519:
              return MIN_NS_SIZE;
          case MLKEM512_X25519:
              return MIN_NS_MLKEM512_SIZE;
          case MLKEM768_X25519:
              return MIN_NS_MLKEM768_SIZE;
          case MLKEM1024_X25519:
              return MIN_NS_MLKEM1024_SIZE;
          default:
              return MIN_NS_SIZE;
        }
    }

    /**
     * Decrypt the message with the given private keys
     *
     * @param ecKey must be EC, non-null
     * @param pqKey must be PQ, non-null
     * @return decrypted data or null on failure
     */
    public CloveSet decrypt(byte data[], PrivateKey ecKey, PrivateKey pqKey, MuxedPQSKM keyManager) throws DataFormatException {
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
                long now = _context.clock().now();
                if (_lastPQWarn.getAndSet(now) < now - WARN_THROTTLE_MS) {
                    _log.warn(msg + " after all tag attempts failed (throttled)");
                }
            } else if (debug) {
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

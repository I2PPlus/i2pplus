package net.i2p.router.crypto.ratchet;

import net.i2p.crypto.EncType;
import net.i2p.data.DataFormatException;
import net.i2p.data.PrivateKey;
import net.i2p.router.RouterContext;
import net.i2p.router.message.CloveSet;
import net.i2p.util.Log;

/**
 * Multiplexed decryption engine supporting both ElGamal and ECIES+AEAD with adaptive ordering.
 *
 * @since 0.9.44
 */
final class MuxedEngine {
    /** The router context the engine runs in. */
    private final RouterContext _context;
    /** Logger for this class. */
    private final Log _log;

    /**
     * Create the ratchet engine.
     *
     * @param ctx the router context
     */
    public MuxedEngine(RouterContext ctx) {
        _context = ctx;
        _log = _context.logManager().getLog(MuxedEngine.class);
    }

    /**
     * Decrypt the message with the given private keys.
     *
     * @param data the encrypted CloveSet to decrypt
     * @param elgKey must be ElG, non-null
     * @param ecKey must be EC, non-null
     * @param keyManager holds the session key material and ratchet preference
     * @return decrypted data or null on failure
     * @throws DataFormatException if the underlying garlic parse fails and cannot be
     * recovered by trying the remaining crypto schemes
     */
    public CloveSet decrypt(byte[] data, PrivateKey elgKey, PrivateKey ecKey, MuxedSKM keyManager) throws DataFormatException {
        if (elgKey.getType() != EncType.ELGAMAL_2048 ||
            ecKey.getType() != EncType.ECIES_X25519)
            throw new IllegalArgumentException();
        final boolean debug = _log.shouldDebug();
        CloveSet rv = null;
        // Try in-order from fastest to slowest
        boolean preferRatchet = keyManager.preferRatchet();
        if (preferRatchet) {
            // Ratchet Tag
            rv = _context.eciesEngine().decryptFast(data, ecKey, keyManager.getECSKM());
            if (rv != null)
                return rv;
            if (debug)
                _log.debug("Ratchet tag not found before AES");
        }
        // AES Tag
        if (data.length >= 128 && (data.length & 0x0f) == 0) {
            byte[] dec = _context.elGamalAESEngine().decryptFast(data, elgKey, keyManager.getElgSKM());
            if (dec != null) {
                try {
                    rv = _context.garlicMessageParser().readCloveSet(dec, 0);
                    if (rv == null && _log.shouldInfo())
                        _log.info("AES decryption produced an unparseable message");
                } catch (DataFormatException dfe) {
                    if (_log.shouldInfo())
                        _log.info("AES decryption produced an unparseable message", dfe);
                }
                return rv;
            } else {
                if (debug)
                    _log.debug("AES tag not found");
            }
        }
        if (!preferRatchet) {
            // Ratchet Tag
            rv = _context.eciesEngine().decryptFast(data, ecKey, keyManager.getECSKM());
            if (rv != null)
                return rv;
            if (debug)
                _log.debug("Ratchet tag not found after AES");
        }

        if (preferRatchet) {
            // Ratchet DH
            rv = _context.eciesEngine().decryptSlow(data, ecKey, keyManager.getECSKM());
            boolean ok = rv != null;
            keyManager.reportDecryptResult(true, ok);
            if (ok)
                return rv;
            if (debug)
                _log.debug("Ratchet NextSession decrypt failed before ElGamal");
        }
        // ElG DH
        if (data.length >= 514 && (data.length & 0x0f) == 2) {
            byte[] dec = _context.elGamalAESEngine().decryptSlow(data, elgKey, keyManager.getElgSKM());
            if (dec != null) {
                try {
                    rv = _context.garlicMessageParser().readCloveSet(dec, 0);
                    boolean ok = rv != null;
                    keyManager.reportDecryptResult(false, ok);
                    if (ok)
                        return rv;
                    if (_log.shouldInfo())
                        _log.info("ElGamal decryption produced an unparseable message after Ratchet attempt -> " +
                                  (preferRatchet ? "Discarding" : "Trying Ratchet DH"));
                } catch (DataFormatException dfe) {
                    if (_log.shouldInfo())
                        _log.info("ElGamal decryption produced an unparseable message after Ratchet attempt -> " +
                                  (preferRatchet ? "Discarding" : "Trying Ratchet DH"), dfe);
                }
            } else {
                if (_log.shouldInfo())
                    _log.info("ElGamal decrypt failed after Ratchet attempt -> " +
                              (preferRatchet ? "Discarding message" : "Trying Ratchet DH"));
            }
            keyManager.reportDecryptResult(false, false);
        }
        if (!preferRatchet) {
            // Ratchet DH
            rv = _context.eciesEngine().decryptSlow(data, ecKey, keyManager.getECSKM());
            boolean ok = rv != null;
            keyManager.reportDecryptResult(true, ok);
            if (!ok && debug)
                _log.debug("Ratchet NextSession decrypt failed after ElGamal");
        }
        return rv;
    }
}

package net.i2p.router.crypto.ratchet;

import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.ArrayList;

import java.util.List;
import java.util.Set;

import com.southernstorm.noise.crypto.x25519.Curve25519;
import com.southernstorm.noise.protocol.ChaChaPolyCipherState;
import com.southernstorm.noise.protocol.CipherState;
import com.southernstorm.noise.protocol.CipherStatePair;
import com.southernstorm.noise.protocol.DHState;
import com.southernstorm.noise.protocol.HandshakeState;
import com.southernstorm.noise.protocol.NoiseInit;

import net.i2p.crypto.EncType;
import net.i2p.crypto.HKDF;
import net.i2p.crypto.KeyFactory;

import net.i2p.data.Base64;
import net.i2p.data.Certificate;
import net.i2p.data.DatabaseEntry;
import net.i2p.data.DataFormatException;
import net.i2p.data.DataHelper;
import net.i2p.data.Destination;

import net.i2p.data.LeaseSet2;
import net.i2p.data.PrivateKey;
import net.i2p.data.PublicKey;
import net.i2p.data.SessionKey;

import net.i2p.data.i2np.DatabaseStoreMessage;
import net.i2p.data.i2np.GarlicClove;
import net.i2p.data.i2np.I2NPMessage;
import net.i2p.router.crypto.pqc.MLKEM;
import net.i2p.router.crypto.pqc.MLKEMKeyFactory;
import static net.i2p.router.crypto.ratchet.RatchetPayload.*;
import net.i2p.router.LeaseSetKeys;
import net.i2p.router.Router;
import net.i2p.router.RouterContext;
import net.i2p.router.message.CloveSet;
import net.i2p.util.Log;

/**
 * ECIES+AEAD encryption engine providing New Session, New Session Reply, and Existing Session message handling
 * <p>
 * No, this does not extend ElGamalAESEngine or AEADEngine or CryptixAEADEngine.
 *
 * @since 0.9.44
 */
public final class ECIESAEADEngine {
    /** The router context. */
    private final RouterContext _context;
    /** Logger for ratchet decryption. */
    private final Log _log;
    /** Engine for the legacy muxed encryption types. */
    private final MuxedEngine _muxedEngine;
    /** Engine for the post-quantum muxed types. */
    private final MuxedPQEngine _muxedPQEngine;
    /** Key derivation function for the ratchet. */
    private final HKDF _hkdf;
    /** Key factory for the X25519 ratchet. */
    private final Elg2KeyFactory _edhThread;
    /** Key factory for ML-KEM 768, started on demand. */
    private MLKEMKeyFactory _mlkem768Thread;
    /** True while the engine is running. */
    private volatile boolean _isRunning;

    /** Shared empty array for zero-length values. */
    private static final byte[] ZEROLEN = new byte[0];
    /** Length of a short ratchet tag, in bytes. */
    private static final int TAGLEN = 8;
    /** Length of a truncated MAC, in bytes. */
    private static final int MACLEN = 16;
    /** Length of an X25519 key, in bytes. */
    private static final int KEYLEN = 32;
    /** Length of a block header, in bytes. */
    private static final int BHLEN = RatchetPayload.BLOCK_HEADER_SIZE;  // 3
    /** Size of a datetime block, in bytes. */
    private static final int DATETIME_SIZE = BHLEN + 4; // 7
    /** Overhead of a NS-encrypted payload, in bytes. */
    private static final int NS_OVERHEAD = KEYLEN + KEYLEN + MACLEN + MACLEN; // 96
    /** Overhead of an NS-N encrypted payload, in bytes. */
    private static final int NS_N_OVERHEAD = KEYLEN + MACLEN; // 48
    /** Overhead of an NSR-encrypted payload, in bytes. */
    private static final int NSR_OVERHEAD = TAGLEN + KEYLEN + MACLEN + MACLEN; // 72
    /** Overhead of an ES-encrypted payload, in bytes. */
    private static final int ES_OVERHEAD = TAGLEN + MACLEN; // 24
    /** Minimum new-session message size in bytes (103). */
    static final int MIN_NS_SIZE = NS_OVERHEAD + DATETIME_SIZE; // 103
    /** Smallest valid NS-N encrypted payload, in bytes. */
    private static final int MIN_NS_N_SIZE = NS_N_OVERHEAD + DATETIME_SIZE; // 55
    /** Smallest valid NSR-encrypted payload, in bytes. */
    private static final int MIN_NSR_SIZE = NSR_OVERHEAD; // 72
    /** Smallest valid ES-encrypted payload, in bytes. */
    private static final int MIN_ES_SIZE = ES_OVERHEAD; // 24
    /** Smallest payload any encryption type accepts. */
    private static final int MIN_ENCRYPTED_SIZE = MIN_ES_SIZE;
    /** All-zero public key for comparisons. */
    private static final byte[] NULLPK = new byte[KEYLEN];
    /** Maximum padding added to an encrypted payload. */
    private static final int MAXPAD = 16;
    /** Maximum age of a New Session request in ms. */
    static final long MAX_NS_AGE = 5*60*1000L;
    /** An NS datetime may be up to 2 minutes in the future. */
    private static final long MAX_NS_FUTURE = 2*60*1000L;
    /** Debug flag to send an ACKREQ in every ES. */
    private static final boolean ACKREQ_IN_ES = false;
    /** Empty clove array for failed ElGamal decryptions. */
    private static final GarlicClove[] NO_GARLIC = new GarlicClove[] {};
    /** Empty clove set returned on failure. */
    private static final CloveSet NO_CLOVES = new CloveSet(NO_GARLIC, Certificate.NULL_CERT, 0, 0);

    /** HKDF info string for the attach payload key. */
    private static final String INFO_6 = "AttachPayloadKDF";

    /** Rate stat period for the decryption statistics. */
    private static final long[] RATES = new long[] { 60*1000L };

    /** Next session overhead for the ML-KEM types: 112 bytes. */
    private static final int NS_MLKEM_OVERHEAD = NS_OVERHEAD + MACLEN;
    /** Minimum ML-KEM-512 new-session message size, 800 + 112 + 7 = 919 bytes. */
    static final int MIN_NS_MLKEM512_SIZE =
        EncType.MLKEM512_X25519_INT.getPubkeyLen() + NS_MLKEM_OVERHEAD + DATETIME_SIZE;
    /** Minimum ML-KEM-768 new-session message size, 1184 + 112 + 7 = 1303 bytes. */
    static final int MIN_NS_MLKEM768_SIZE =
        EncType.MLKEM768_X25519_INT.getPubkeyLen() + NS_MLKEM_OVERHEAD + DATETIME_SIZE;
    /** Minimum ML-KEM-1024 new-session message size, 1568 + 112 + 7 = 1687 bytes. */
    static final int MIN_NS_MLKEM1024_SIZE =
        EncType.MLKEM1024_X25519_INT.getPubkeyLen() + NS_MLKEM_OVERHEAD + DATETIME_SIZE;
    /** Smallest next session request with ML-KEM-512: 856 bytes. */
    private static final int MIN_NSR_MLKEM512_SIZE = EncType.MLKEM512_X25519_CT.getPubkeyLen() + MIN_NSR_SIZE + MACLEN;
    /** Smallest next session request with ML-KEM-768: 1176 bytes. */
    private static final int MIN_NSR_MLKEM768_SIZE = EncType.MLKEM768_X25519_CT.getPubkeyLen() + MIN_NSR_SIZE + MACLEN;
    /** Smallest next session request with ML-KEM-1024: 1656 bytes. */
    private static final int MIN_NSR_MLKEM1024_SIZE = EncType.MLKEM1024_X25519_CT.getPubkeyLen() + MIN_NSR_SIZE + MACLEN;
    /**
     * Caller MUST call {@link #startup()} to get threaded generation.
     * Will still work without, will just generate inline.
     * <p>
     * startup() is called from {@link RatchetSKM} constructor so it's deferred until we need it.
     * @param ctx the router context used to obtain the log, stats and sub-engines
     */
    public ECIESAEADEngine(RouterContext ctx) {
        _context = ctx;
        _log = _context.logManager().getLog(ECIESAEADEngine.class);
        _muxedEngine = new MuxedEngine(ctx);
        _muxedPQEngine = new MuxedPQEngine(ctx);
        _hkdf = new HKDF(ctx);
        _edhThread = new Elg2KeyFactory(ctx);

        _context.statManager().createFrequencyStat("crypto.eciesAEAD.decryptFailed", "How often we fail to decrypt with ECIES/AEAD+SessionTag",
                                                   "Encryption", RATES);
    }

    /**
     * May be called multiple times.
     */
    public synchronized void startup() {
        if (!_isRunning) {
            _edhThread.start();
            _isRunning = true;
        }
    }

    /**
     * Cannot be restarted.
     */
    public synchronized void shutdown() {
        _isRunning = false;
        _edhThread.shutdown();
        synchronized(this) {
            if (_mlkem768Thread != null)
                _mlkem768Thread.shutdown();
        }
    }


    /**
     * Try to decrypt the message with one or both of the given private keys.
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
        return _muxedEngine.decrypt(data, elgKey, ecKey, keyManager);
    }

    /**
     * Try to decrypt the message with one or both of the given private keys.
     *
     * @param data the encrypted CloveSet to decrypt
     * @param ecKey must be EC, non-null
     * @param pqKey must be PQ, non-null
     * @param keyManager holds the session key material and ratchet preference
     * @return decrypted data or null on failure
     * @throws DataFormatException if the data is malformed
     * @since 0.9.67
     */
    public CloveSet decrypt(byte[] data, PrivateKey ecKey, PrivateKey pqKey, MuxedPQSKM keyManager) throws DataFormatException {
        return _muxedPQEngine.decrypt(data, ecKey, pqKey, keyManager);
    }

    /**
     * Decrypt the message using the given private key
     * and using tags from the specified key manager.
     * This works according to the
     * ECIES+AEAD algorithm in the data structure spec.
     * <p>
     * Warning - use the correct SessionKeyManager. Clients should instantiate their own.
     * Clients using {@link net.i2p.I2PAppContext#sessionKeyManager} may be correlated with the router,
     * unless you are careful to use different keys.
     *
     * @param data the encrypted CloveSet to decrypt
     * @param targetPrivateKey private key matching the encryption type of the data
     * @param keyManager holds the session tags and key material used to decrypt
     * @return decrypted data or null on failure
     * @throws DataFormatException if the underlying garlic parse fails and cannot be
     * recovered by trying the remaining crypto schemes
     */
    public CloveSet decrypt(byte[] data, PrivateKey targetPrivateKey,
                            RatchetSKM keyManager) throws DataFormatException {
        try {
            return x_decrypt(data, targetPrivateKey, keyManager);
        } catch (DataFormatException dfe) {
            if (_log.shouldWarn())
                _log.warn("ECIES decryption error -> " + dfe.getMessage());
            return NO_CLOVES;
        } catch (Exception e) {
            _log.warn("ECIES decryption error", e);
            return NO_CLOVES;
        }
    }

    /**
     * Decrypts a received message into cloves.
     *
     * @param data the message bytes
     * @param targetPrivateKey the key to decrypt with
     * @param keyManager the manager of the session keys
     * @return the decrypted clove set
     * @throws DataFormatException if the message is invalid
     */
    private CloveSet x_decrypt(byte[] data, PrivateKey targetPrivateKey,
                               RatchetSKM keyManager) throws DataFormatException {
        checkType(targetPrivateKey.getType());
        if (data == null) {
            if (_log.shouldError()) _log.error("Null data being decrypted?");
            return null;
        }
        if (data.length < MIN_ENCRYPTED_SIZE) {
            if (_log.shouldWarn())
                _log.warn("Data is less than the minimum size (" + data.length + " < " + MIN_ENCRYPTED_SIZE + ")");
            return null;
        }

        byte[] tag = new byte[TAGLEN];
        System.arraycopy(data, 0, tag, 0, TAGLEN);
        RatchetSessionTag st = new RatchetSessionTag(tag);
        SessionKeyAndNonce key = keyManager.consumeTag(st);
        CloveSet decrypted;
        if (key != null) {
            decrypted = xx_decryptFast(tag, st, key, data, targetPrivateKey, keyManager);
            // we do NOT retry as NS
        } else {
            decrypted = x_decryptSlow(data, targetPrivateKey, keyManager);
        }
        if (decrypted == null && _log.shouldDebug())
            _log.info("Decrypt fail NS/NSR/ES, possible tag: " + st);
        return decrypted;
    }

    /**
     * NSR/ES only. For MuxedEngine use only.
     *
     * @param data the encrypted CloveSet to decrypt
     * @param targetPrivateKey private key matching the encryption type of the data
     * @param keyManager holds the session tags and key material used to decrypt
     * @return the decrypted cloves, null if no key was found for the tag, or an
     *         empty set if the decrypt or the payload parse failed
     * @throws DataFormatException if the payload cannot be parsed as a garlic clove set
     * @since 0.9.46
     */
    CloveSet decryptFast(byte[] data, PrivateKey targetPrivateKey,
                         RatchetSKM keyManager) throws DataFormatException {
        try {
            return x_decryptFast(data, targetPrivateKey, keyManager);
        } catch (DataFormatException dfe) {
            if (_log.shouldWarn())
                _log.warn("ECIES decryption error -> " +  dfe.getMessage());
            return NO_CLOVES;
        } catch (Exception e) {
            _log.warn("ECIES decryption error", e);
            return NO_CLOVES;
        }
    }

    /**
     * NSR/ES only.
     *
     * @param data the encrypted NewSessionReply or ExistingSession message
     * @param targetPrivateKey our private key matching the message's encryption type
     * @param keyManager the key manager whose session tags are searched for the tag
     * @return the decrypted cloves, or null if the 8-byte tag is unknown or the
     *         data is below the minimum size
     * @throws DataFormatException if the payload cannot be parsed as a garlic clove set
     * @since 0.9.46
     */
    private CloveSet x_decryptFast(byte[] data, PrivateKey targetPrivateKey,
                                   RatchetSKM keyManager) throws DataFormatException {
        if (data.length < MIN_ENCRYPTED_SIZE) {
            if (_log.shouldDebug())
                _log.debug("Data is less than the minimum size (" + data.length + " < " + MIN_ENCRYPTED_SIZE + ")");
            return null;
        }
        byte[] tag = new byte[TAGLEN];
        System.arraycopy(data, 0, tag, 0, TAGLEN);
        RatchetSessionTag st = new RatchetSessionTag(tag);
        SessionKeyAndNonce key = keyManager.consumeTag(st);
        CloveSet decrypted;
        if (key != null) {
            decrypted = xx_decryptFast(tag, st, key, data, targetPrivateKey, keyManager);
        } else {
            decrypted = null;
        }
        return decrypted;
    }

    /**
     * NSR/ES only.
     *
     * @param tag the 8-byte session tag copied out of the start of data
     * @param st the session tag object built from those 8 bytes
     * @param key non-null key and nonce the key manager holds for that tag
     * @param data the encrypted NewSessionReply or ExistingSession message
     * @param targetPrivateKey our private key matching the message's encryption type
     * @param keyManager holds the session tags and key material used to decrypt
     * @return the decrypted cloves, or null if the reply is too short or the MAC
     *         check fails
     * @throws DataFormatException if the payload cannot be parsed as a garlic clove set
     * @since 0.9.46
     */
    private CloveSet xx_decryptFast(byte[] tag, RatchetSessionTag st, SessionKeyAndNonce key,
                                    byte[] data, PrivateKey targetPrivateKey,
                                    RatchetSKM keyManager) throws DataFormatException {
        CloveSet decrypted;
        final boolean shouldDebug = _log.shouldDebug();
        HandshakeState state = key.getHandshakeState();
        if (state == null) {
            if (shouldDebug) {
                _log.debug("Decrypting ExistingSession with tag: " + st.toBase64() + "\n* Key: " + key + " (" + data.length + " bytes)");
            }
            decrypted = decryptExistingSession(tag, data, key, targetPrivateKey, keyManager);
        } else {
            // it's important not to attempt decryption for too-short packets,
            // because Noise will destroy() the handshake state on failure,
            // and we don't clone() on the first one, so it's fatal.
            EncType type = targetPrivateKey.getType();
            int min = getMinNSRSize(type);
            if (data.length >= min) {
                if (shouldDebug) {
                    _log.debug("Decrypting NewSessionReply with tag: " + st.toBase64() + "\n* Key: " + key + "(" + data.length + " bytes)");
                }
                decrypted = decryptNewSessionReply(tag, data, state, keyManager);
            } else {
                decrypted = null;
                if (_log.shouldWarn()) {
                    _log.warn("NewSessionReply decryption failure -> Tag found but no state" +
                              "\n* Too small: " + data.length + " bytes for NewSessionReply" +
                              " (minimum " + min + " bytes)");
                }
            }
            if (decrypted == null) {
                _context.statManager().updateFrequency("crypto.eciesAEAD.decryptFailed");
                if (_log.shouldWarn()) {
                    _log.warn("ECIES decryption failure -> Known tag [" + st + "] but failed decrypt with key \n* Key: " + key +
                             "\n* Failure type: Cryptographic verification failed (MAC or decryption error)");
                }
            }
        }
        return decrypted;
    }

    /**
     * NS only. For MuxedEngine use only.
     *
     * @param data the encrypted CloveSet to decrypt
     * @param targetPrivateKey private key matching the encryption type of the data
     * @param keyManager holds the session tags and key material used to decrypt
     * @return decrypted data or null on failure
     * @throws DataFormatException if the payload cannot be parsed as a garlic clove set
     * @since 0.9.46
     */
    CloveSet decryptSlow(byte[] data, PrivateKey targetPrivateKey,
                            RatchetSKM keyManager) throws DataFormatException {
        try {
            return x_decryptSlow(data, targetPrivateKey, keyManager);
        } catch (DataFormatException dfe) {
            if (_log.shouldWarn())
                _log.warn("ECIES decryption error", dfe);
            return NO_CLOVES;
        } catch (Exception e) {
            _log.warn("ECIES decryption error", e);
            return NO_CLOVES;
        }
    }

    /**
     * NS only.
     *
     * @param data the encrypted NewSession or NewSession/LeaseSet message
     * @param targetPrivateKey our private key matching the message's encryption type
     * @param keyManager holds the session tags and key material; a null destination
     *                   marks the router variant, NewSession_N
     * @return the decrypted cloves, or null if the data is below the minimum size
     *         for the key type or the decrypt fails
     * @throws DataFormatException if the payload cannot be parsed as a garlic clove set
     * @since 0.9.46
     */
    private CloveSet x_decryptSlow(byte[] data, PrivateKey targetPrivateKey,
                                   RatchetSKM keyManager) throws DataFormatException {
        CloveSet decrypted;
        EncType type = targetPrivateKey.getType();
        int minns = getMinNSSize(type);
        boolean isRouter = keyManager.getDestination() == null;
        if (data.length >= minns || (isRouter && data.length >= MIN_NS_N_SIZE)) {
            if (isRouter) {decrypted = decryptNewSession_N(data, targetPrivateKey, keyManager);}
            else {decrypted = decryptNewSession(data, targetPrivateKey, keyManager);}
            if (decrypted == null) {
                _context.statManager().updateFrequency("crypto.eciesAEAD.decryptFailed");
                // we'll get this a lot on muxed SKM
                if (_log.shouldInfo()) {
                    _log.info("ECIES decryption failure for " + (isRouter ? "NewSession" : "NewSession/LeaseSet") +
                             " -> Type: " + type + " Data size: " + data.length + " (minimum " + minns + ")");
                }
            }
        } else {
            decrypted = null;
            if (_log.shouldDebug()) {
                _log.debug("ECIES decryption failure, too small (" + data.length + " bytes) for NewSession (minimum is " + minns + " for " + type + ")");
            }
        }
        return decrypted;
    }

    /**
     * Verify the encryption type is supported by this engine.
     *
     * @param type the encryption type this engine must be able to handle
     * @throws IllegalArgumentException if type is not one this engine handles
     * @since 0.9.67
     */
    private static void checkType(EncType type) {
        switch(type) {
          case ECIES_X25519:
          case MLKEM512_X25519:
          case MLKEM768_X25519:
          case MLKEM1024_X25519:
              return;
          default:
              throw new IllegalArgumentException("Unsupported key type " + type);
        }
    }

    /**
     * The Noise handshake pattern for the given encryption type.
     *
     * @param type the encryption type to select a handshake pattern for
     * @return IK for X25519, or IKHFS_n for the matching ML-KEM hybrid type
     * @throws IllegalArgumentException if no pattern is defined for the type
     * @since 0.9.67
     */
    private static NoiseInit.PatternID getNoisePattern(EncType type) {
        switch(type) {
          case ECIES_X25519:
              return NoiseInit.PatternID.IK;
          case MLKEM512_X25519:
              return NoiseInit.PatternID.IKHFS_512;
          case MLKEM768_X25519:
              return NoiseInit.PatternID.IKHFS_768;
          case MLKEM1024_X25519:
              return NoiseInit.PatternID.IKHFS_1024;
          default:
              throw new IllegalArgumentException("No pattern for " + type);
        }
    }

    /**
     * The hybrid key factory for the given encryption type, or null if not a hybrid type.
     *
     * @param type the hybrid encryption type to get a key factory for
     * @return the hybrid key factory
     * @since 0.9.67, public since 0.9.69 for transports
     */
    public KeyFactory getHybridKeyFactory(EncType type) {
        switch(type) {
          case MLKEM512_X25519:
              return MLKEM.MLKEM512KeyFactory;
          case MLKEM768_X25519:
              // non-threaded
              //return MLKEM.MLKEM768KeyFactory;
              // threaded, start on demand for now
              synchronized(this) {
                  if (_mlkem768Thread == null) {
                      _mlkem768Thread = new MLKEMKeyFactory(_context, EncType.MLKEM768_X25519_INT);
                      _mlkem768Thread.start();
                  }
                  return _mlkem768Thread;
              }
          case MLKEM1024_X25519:
              return MLKEM.MLKEM1024KeyFactory;
          default:
              return null;
        }
    }

    /**
     * The minimum New Session message size for the given encryption type.
     *
     * @param type the encryption type whose New Session minimum is wanted
     * @return the minimum new-session message size in bytes for this type
     * @throws IllegalArgumentException if no minimum is defined for the type
     * @since 0.9.67
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
              throw new IllegalArgumentException("No pattern for " + type);
        }
    }

    /**
     * The minimum New Session Reply message size for the given encryption type.
     *
     * @param type the encryption type whose reply minimum is wanted
     * @return the minimum new-session-reply message size in bytes for this type
     * @throws IllegalArgumentException if no minimum is defined for the type
     * @since 0.9.67
     */
    private static int getMinNSRSize(EncType type) {
        switch(type) {
          case ECIES_X25519:
              return MIN_NSR_SIZE;
          case MLKEM512_X25519:
              return MIN_NSR_MLKEM512_SIZE;
          case MLKEM768_X25519:
              return MIN_NSR_MLKEM768_SIZE;
          case MLKEM1024_X25519:
              return MIN_NSR_MLKEM1024_SIZE;
          default:
              throw new IllegalArgumentException("No pattern for " + type);
        }
    }

    /**
     * The lease set key types that go with the given encryption type.
     *
     * @param type the encryption type to map to a lease set key set
     * @return the {@link LeaseSetKeys} set for that type, one of SET_EC, SET_PQ1,
     *         SET_PQ2 or SET_PQ3
     * @throws IllegalArgumentException if no set is defined for the type
     * @since 0.9.67
     */
    private static Set<EncType> getEncTypeSet(EncType type) {
        switch(type) {
          case ECIES_X25519:
              return LeaseSetKeys.SET_EC;
          case MLKEM512_X25519:
              return LeaseSetKeys.SET_PQ1;
          case MLKEM768_X25519:
              return LeaseSetKeys.SET_PQ2;
          case MLKEM1024_X25519:
              return LeaseSetKeys.SET_PQ3;
          default:
              throw new IllegalArgumentException("No pattern for " + type);
        }
    }

    /**
     * Scenario 1: New Session Message
     * <p>
     * Begin with 80 bytes, ECIES encrypted, containing:
     * <pre>
     * - 32 byte Elligator2 key
     * - 32 byte static key
     * - 16 byte MAC
     * </pre>
     * And then the data:
     * <pre>
     * - payload (7 bytes minimum for DateTime block)
     * - 16 byte MAC
     * </pre>
     *
     * @param data 103 bytes minimum
     * @param targetPrivateKey our private key matching the message's encryption type
     * @param keyManager holds the ephemeral-key replay cache and receives the new
     *                   session on success
     * @return the decrypted cloves, null if the Elligator2 decode or the handshake
     *         fails, or an empty set for a replayed key or a payload with no garlic
     * @throws DataFormatException if the decrypted payload blocks cannot be parsed
     */
    private CloveSet decryptNewSession(byte[] data, PrivateKey targetPrivateKey, RatchetSKM keyManager)
                                     throws DataFormatException {
        // Elg2
        byte[] xx = new byte[KEYLEN];
        System.arraycopy(data, 0, xx, 0, KEYLEN);
        // Replay check on the raw encoded key, before paying for Elligator2
        // (3 modPows) plus DH plus AEAD. Check only - the entry is added on
        // success below, so a retransmission after a failed attempt (e.g. a
        // tag race) is not blocked.
        if (keyManager.isDuplicateRaw(xx)) {
            if (_log.shouldWarn())
                _log.warn("Duplicate raw ephemeral key in Inbound NewSession: " + Base64.encode(xx));
            return NO_CLOVES;
        }
        // decode corrupts last byte, save for restore below
        byte xx31 = xx[KEYLEN - 1];
        PublicKey pk = Elligator2.decode(xx);
        if (pk == null) {
            // very unlikely
            if (_log.shouldDebug())
                _log.debug("Decoding failure for NewSession -> No PublicKey");
            data[KEYLEN - 1] = xx31;
            return null;
        }
        // fast MSB check for key < 2^255
        if ((pk.getData()[KEYLEN - 1] & 0x80) != 0) {
            if (_log.shouldDebug())
                _log.debug("Decoding failure for NewSession -> Bad PublicKey (wrong length)");
            data[KEYLEN - 1] = xx31;
            return null;
        }

        // rewrite in place, must restore below on failure
        System.arraycopy(pk.getData(), 0, data, 0, KEYLEN);

        HandshakeState state;
        EncType type = targetPrivateKey.getType();
        NoiseInit.PatternID pattern = null;
        try {
            pattern = getNoisePattern(type);
            // Bob does not need a key factory
            state = new HandshakeState(pattern, HandshakeState.RESPONDER, _edhThread);
        } catch (GeneralSecurityException gse) {
            if (_log.shouldWarn()) {
                _log.warn("Handshake initialization failed for pattern: " + pattern +
                         " Type: " + type, gse);
            }
            throw new IllegalStateException("Bad protocol - Pattern: " + pattern +
                                          " Type: " + type, gse);
        }
        state.getLocalKeyPair().setKeys(targetPrivateKey.getData(), 0,
                                        targetPrivateKey.toPublic().getData(), 0);
        state.start();
        if (_log.shouldDebug())
            _log.debug("State before decrypting NewSession: " + state);

        int payloadlen = data.length - (KEYLEN + KEYLEN + MACLEN + MACLEN);
        DHState hyb = state.getRemoteHybridKeyPair();
        if (hyb != null) {
            payloadlen -= hyb.getPublicKeyLength() + MACLEN;
        }
        byte[] payload = new byte[payloadlen];
        try {
            state.readMessage(data, 0, data.length, payload, 0);
        } catch (GeneralSecurityException gse) {
            // we'll get this a lot on muxed SKM
            // logged at INFO in caller
            if (_log.shouldDebug()) {
                String failureType = gse.getMessage();
                if (failureType != null) {
                    if (failureType.contains("MAC") || failureType.contains("mac")) {
                        failureType = "MAC verification failed";
                    } else if (failureType.contains("decrypt") || failureType.contains("Decrypt")) {
                        failureType = "Decryption failed";
                    } else if (failureType.contains("length") || failureType.contains("size")) {
                        failureType = "Invalid data length";
                    } else {
                        failureType = "Cryptographic error: " + failureType;
                    }
                } else {
                    failureType = "Unknown cryptographic error";
                }
                _log.debug("NewSession decryption failure -> " + failureType +
                           "\n* State at failure: " + state, gse);
            }
            // restore original data for subsequent ElG attempt
            System.arraycopy(xx, 0, data, 0, KEYLEN - 1);
            data[KEYLEN - 1] = xx31;
            state.destroy();
            return null;
        }
        // bloom filter here based on ephemeral key
        // or should we do it based on apparent elg2-encoded key
        // at the very top, to prevent excess DH resource usage?
        // But that would put everything in the bloom filter.
        if (keyManager.isDuplicate(pk)) {
            if (_log.shouldWarn())
                _log.warn("Duplicate ephemeral key in Inbound NewSession: " + pk);
            state.destroy();
            return NO_CLOVES;
        }
        // Also remember the raw encoding, so a replay of this exact packet
        // is caught by the pre-decode check at the top next time.
        keyManager.addRaw(xx);

        // payload
        if (payloadlen == 0) {
            // disallowed, datetime block required
            if (_log.shouldWarn())
                _log.warn("Zero length payload in NewSession");
            state.destroy();
            return NO_CLOVES;
        }
        PLCallback pc = new PLCallback();
        try {
            int blocks = RatchetPayload.processPayload(_context, pc, payload, 0, payload.length, true);
            if (_log.shouldDebug())
                _log.debug("Processed " + blocks + " blocks in Inbound NewSession");
        } catch (DataFormatException e) {
            state.destroy();
            throw e;
        } catch (Exception e) {
            state.destroy();
            throw new DataFormatException("NewSession payload error", e);
        }

        if (pc.datetime == 0) {
            // disallowed, datetime block required
            if (_log.shouldWarn())
                _log.warn("No datetime block in Inbound NewSession");
            state.destroy();
            return NO_CLOVES;
        }

        if (pc.cloveSet.isEmpty()) {
            // this is legal
            if (_log.shouldDebug())
                _log.debug("No garlic block in NewSession payload");
            state.destroy();
            return NO_CLOVES;
        }

        byte[] alicePK = new byte[KEYLEN];
        state.getRemotePublicKey().getPublicKey(alicePK, 0);
        if (_log.shouldDebug()) {
            _log.debug("NewSession decryption success from PublicKey [" + Base64.encode(alicePK) + "]");
            _log.debug("State after decrypting NewSession: " + state);
        }
        if (Arrays.equals(alicePK, NULLPK)) {
            state.destroy();
        } else {
            // tell the SKM
            PublicKey alice = new PublicKey(type, alicePK);
            keyManager.createSession(alice, null, state, null);
            setResponseTimerNS(alice, pc.cloveSet, keyManager);
        }

        int num = pc.cloveSet.size();
        GarlicClove[] arr = new GarlicClove[num];
        // msg id and expiration not checked in GarlicMessageReceiver
        return new CloveSet(pc.cloveSet.toArray(arr), Certificate.NULL_CERT, 0, pc.datetime);
    }

    /**
     * Scenario 1A: New Session Message Anonymous.
     * <pre>
     * - 32 byte ephemeral key (NOT Elligator2)
     * - payload (7 bytes minimum for DateTime block)
     * - 16 byte MAC
     * </pre>
     *
     * @param data 55 bytes minimum
     * @param targetPrivateKey our private key matching the message's encryption type
     * @param keyManager holds the ephemeral-key replay cache checked before the DH work
     * @return the decrypted cloves, null if the key or the handshake check fails,
     *         or an empty set for a replayed key or a payload with no garlic
     * @throws DataFormatException if the decrypted payload blocks cannot be parsed
     * @since 0.9.49
     */
    private CloveSet decryptNewSession_N(byte[] data, PrivateKey targetPrivateKey, RatchetSKM keyManager)
                                     throws DataFormatException {
        // fast MSB check for key < 2^255
        if ((data[KEYLEN - 1] & 0x80) != 0) {
            if (_log.shouldDebug())
                _log.debug("Bad public key in the Noise N handshake");
            return null;
        }
        // Replay check before DH/AEAD work, see decryptNewSession() above.
        // Check only - added on success below.
        if (keyManager.isDuplicateRaw(data)) {
            if (_log.shouldWarn())
                _log.warn("Duplicate raw ephemeral key in an inbound Noise N handshake: " + Base64.encode(data, 0, KEYLEN));
            return NO_CLOVES;
        }

        HandshakeState state;
        try {
            state = new HandshakeState(NoiseInit.PatternID.N_NO_RESPONSE, HandshakeState.RESPONDER, _context.commSystem().getXDHFactory());
        } catch (GeneralSecurityException gse) {
            throw new IllegalStateException("Bad protocol", gse);
        }
        state.getLocalKeyPair().setKeys(targetPrivateKey.getData(), 0,
                                        targetPrivateKey.toPublic().getData(), 0);
        state.start();
        if (_log.shouldDebug())
            _log.debug("State before decrypting NewSession: " + state);

        int payloadlen = data.length - (KEYLEN + MACLEN);
        byte[] payload = new byte[payloadlen];
        try {
            state.readMessage(data, 0, data.length, payload, 0);
        } catch (GeneralSecurityException gse) {
            // we'll get this a lot from very old routers
            // logged at INFO in caller
            if (_log.shouldDebug())
                _log.debug("Decrypt failed in the Noise N handshake, state at failure: " + state, gse);
            state.destroy();
            return null;
        }
        // bloom filter here based on ephemeral key
        byte[] xx = new byte[KEYLEN];
        System.arraycopy(data, 0, xx, 0, KEYLEN);
        PublicKey pk = new PublicKey(EncType.ECIES_X25519, xx);
        if (keyManager.isDuplicate(pk)) {
            if (_log.shouldWarn())
                _log.warn("Duplicate ephemeral key in an inbound Noise N handshake: " + pk);
            state.destroy();
            return NO_CLOVES;
        }
        // Remember the raw bytes for the pre-check at the top, see above.
        keyManager.addRaw(xx);

        // payload
        if (payloadlen == 0) {
            // disallowed, datetime block required
            if (_log.shouldWarn())
                _log.warn("Zero length payload in a Noise N handshake");
            state.destroy();
            return NO_CLOVES;
        }
        PLCallback pc = new PLCallback();
        try {
            int blocks = RatchetPayload.processPayload(_context, pc, payload, 0, payload.length, true);
            if (_log.shouldDebug())
                _log.debug("Processed " + blocks + " blocks in an inbound Noise N handshake");
        } catch (DataFormatException e) {
            state.destroy();
            throw e;
        } catch (Exception e) {
            state.destroy();
            throw new DataFormatException("Noise N payload error", e);
        }

        if (pc.datetime == 0) {
            // disallowed, datetime block required
            if (_log.shouldWarn())
                _log.warn("No datetime block in an inbound Noise N handshake");
            state.destroy();
            return NO_CLOVES;
        }

        if (pc.cloveSet.isEmpty()) {
            // this is legal ?
            if (_log.shouldDebug())
                _log.debug("No garlic block in the Noise N payload");
            state.destroy();
            return NO_CLOVES;
        }

        if (_log.shouldDebug()) {
            _log.debug("Noise N handshake decrypted successfully");
        }
        state.destroy();

        int num = pc.cloveSet.size();
        GarlicClove[] arr = new GarlicClove[num];
        // msg id and expiration not checked in GarlicMessageReceiver
        return new CloveSet(pc.cloveSet.toArray(arr), Certificate.NULL_CERT, 0, pc.datetime);
    }

    /**
     * Scenario 2: New Session Reply Message
     * <p>
     * Begin with 56 bytes, containing:
     * <pre>
     * - 8 byte SessionTag
     * - 32 byte Elligator2 key
     * - 16 byte MAC
     * </pre>
     * And then the data:
     * <pre>
     * - payload
     * - 16 byte MAC
     * </pre>
     *
     * @param tag 8 bytes, same as first 8 bytes of data
     * @param data 72 bytes minimum
     * @param oldState will be cloned here, then destroyed on failure
     * @param keyManager the session key manager for this handshake
     * @return the decrypted cloves, null if the state clone, the Elligator2 decode
     *         or part 1 fails, or an empty set if the payload MAC fails or the
     *         payload has no garlic block
     * @throws DataFormatException if the decrypted payload blocks cannot be parsed
     */
    private CloveSet decryptNewSessionReply(byte[] tag, byte[] data, HandshakeState oldState, RatchetSKM keyManager)
                                          throws DataFormatException {
        HandshakeState state;
        try {
            state = oldState.clone();
        } catch (CloneNotSupportedException e) {
            if (_log.shouldWarn())
                _log.warn("ECIES decryption failure: Unable to clone handshake state for NewSessionReply", e);
            return null;
        }

        // part 1 - handshake
        byte[] yy = new byte[KEYLEN];
        System.arraycopy(data, TAGLEN, yy, 0, KEYLEN);
        // decode corrupts last byte, save for restore below
        byte yy31 = yy[KEYLEN - 1];
        PublicKey k = Elligator2.decode(yy);
        if (k == null) {
            // very unlikely
            if (_log.shouldDebug())
                _log.debug("ElGamal2 decryption failed for NewSessionReply");
            data[TAGLEN + KEYLEN - 1] = yy31;
            state.destroy();
            return null;
        }
        if (_log.shouldDebug())
            _log.debug("State before decryption of NewSessionReply: " + state);
        // rewrite in place, must restore below on failure
        System.arraycopy(k.getData(), 0, data, TAGLEN, KEYLEN);
        state.mixHash(tag, 0, TAGLEN);
        if (_log.shouldDebug())
            _log.debug("State after mixhash tag before decryption of NewSessionReply: " + state);
        int tmplen = 48;
        DHState hyb = state.getRemoteHybridKeyPair();
        if (hyb != null) {
            tmplen += hyb.getPublicKeyLength() + MACLEN;
        }
        try {
            state.readMessage(data, 8, tmplen, ZEROLEN, 0);
        } catch (GeneralSecurityException gse) {
            if (_log.shouldWarn()) {
                boolean gseNotNull = gse.getMessage() != null && !gse.getMessage().equals("null");
                _log.warn("Decryption failure of NewSessionReply part 1" +
                          (gseNotNull ? " (General Security Exception: " + gse.getMessage() + ")" : ""));
                if (_log.shouldDebug()) {_log.debug("State at failure: " + state);}
            }
            // restore original data for subsequent ElG attempt
            // unlikely since we already matched the tag
            System.arraycopy(yy, 0, data, TAGLEN, KEYLEN - 1);
            data[TAGLEN + KEYLEN - 1] = yy31;
            state.destroy();
            return null;
        }
        if (_log.shouldDebug())
            _log.debug("State after decrypting NewSessionReply: " + state);

        // split()
        // Noise does it too but it trashes the keys
        SplitKeys split = new SplitKeys(state, _hkdf);
        CipherStatePair ckp = state.split();
        CipherState rcvr = ckp.getReceiver();
        byte[] hash = state.getHandshakeHash();

        // part 2 - payload
        byte[] encpayloadkey = new byte[32];
        _hkdf.calculate(split.k_ba.getData(), ZEROLEN, INFO_6, encpayloadkey);
        rcvr.initializeKey(encpayloadkey, 0);
        int off = TAGLEN + KEYLEN + MACLEN;
        int plen = data.length - (TAGLEN + KEYLEN + MACLEN + MACLEN);
        if (hyb != null) {
            int len = hyb.getPublicKeyLength() + MACLEN;
            off += len;
            plen -= len;
        }
        byte[] payload = new byte[plen];
        try {
            rcvr.decryptWithAd(hash, data, off, payload, 0, plen + MACLEN);
        } catch (GeneralSecurityException gse) {
            if (_log.shouldWarn()) {
                boolean gseNotNull = gse.getMessage() != null && !gse.getMessage().equals("null");
                _log.warn("Decryption failure of NewSessionReply part 2" +
                          (gseNotNull ? " (General Security Exception: " + gse.getMessage() + ")" : ""));
                if (_log.shouldDebug()) {_log.debug("State at failure: " + state);}
            }
            state.destroy();
            return NO_CLOVES;
        }

        PLCallback pc;
        if (payload.length == 0) {
            // this is legal
            pc = null;
            if (_log.shouldDebug())
                _log.debug("Zero length payload in Inbound NewSessionReply");
        } else {
            pc = new PLCallback();
            try {
                int blocks = RatchetPayload.processPayload(_context, pc, payload, 0, payload.length, false);
                if (_log.shouldDebug())
                    _log.debug("Processed " + blocks + " blocks in Inbound NewSessionReply");
            } catch (DataFormatException e) {
                state.destroy();
                throw e;
            } catch (Exception e) {
                state.destroy();
                throw new DataFormatException("NewSessionReply payload error", e);
            }
        }

        byte[] bobPK = new byte[KEYLEN];
        state.getRemotePublicKey().getPublicKey(bobPK, 0);
        if (_log.shouldDebug())
            _log.debug("NewSessionReply decryption success from PK " + Base64.encode(bobPK));
        if (Arrays.equals(bobPK, NULLPK)) {
            // A reply that carries an all-zero static key cannot name a
            // session we hold: we never publish a zero static key, and
            // x_encrypt() refuses to encrypt to one, so there is no valid
            // peer to attach this handshake to. Drop the handshake rather
            // than registering a session under an unusable key.
            if (_log.shouldWarn())
                _log.warn("NewSessionReply reply to zero static key NewSession");
            state.destroy();
            return NO_CLOVES;
        }

        // tell the SKM
        PublicKey bob = new PublicKey(keyManager.getType(), bobPK);
        keyManager.updateSession(bob, oldState, state, null, split);

        if (pc == null)
            return NO_CLOVES;
        if (pc.cloveSet.isEmpty()) {
            // this is legal
            if (_log.shouldDebug())
                _log.debug("No garlic block in NewSessionReply payload");
            return NO_CLOVES;
        }
        int num = pc.cloveSet.size();
        GarlicClove[] arr = new GarlicClove[num];
        // msg id and expiration not checked in GarlicMessageReceiver
        CloveSet rv = new CloveSet(pc.cloveSet.toArray(arr), Certificate.NULL_CERT, 0, pc.datetime);
        setResponseTimer(bob, pc.cloveSet, keyManager);
        return rv;
    }

    /**
     * Scenario 3: Existing Session Message.
     * <pre>
     * - 8 byte SessionTag
     * - payload
     * - 16 byte MAC
     * </pre>
     * <p>
     * If anything doesn't match up in decryption, it returns null
     *
     * @param tag 8 bytes for ad, same as first 8 bytes of data
     * @param data 24 bytes minimum, first 8 bytes will be skipped
     * @param key the session key and nonce the key manager holds for that tag
     * @param _targetPrivateKey our private key, unused as the session key is set up
     * @param keyManager for ack callbacks
     * @return the decrypted cloves, null if the MAC check fails, or an empty set if
     *         the payload is empty or carries no garlic block
     * @throws DataFormatException if the decrypted payload blocks cannot be parsed
     */
    private CloveSet decryptExistingSession(byte[] tag, byte[] data, SessionKeyAndNonce key,
                                            PrivateKey _targetPrivateKey, RatchetSKM keyManager)
                                          throws DataFormatException {
        int nonce = key.getNonce();
        // this decrypts in-place
        boolean ok = decryptAEADBlock(tag, data, TAGLEN, data.length - TAGLEN, key, nonce);
        if (!ok) {
            if (_log.shouldWarn())
                _log.warn("Decryption of ExistingSession failed");
            return null;
        }
        if (data.length == TAGLEN + MACLEN) {
            // legal?
            if (_log.shouldWarn())
                _log.warn("Zero length payload in ExistingSession");
            return NO_CLOVES;
        }
        PublicKey remote = key.getRemoteKey();
        PLCallback pc = new PLCallback(keyManager, remote);
        try {
            int blocks = RatchetPayload.processPayload(_context, pc, data, TAGLEN, data.length - (TAGLEN + MACLEN), false);
            if (_log.shouldDebug())
                _log.debug("Processed " + blocks + " blocks in Inbound ExistingSession");
        } catch (DataFormatException e) {
            throw e;
        } catch (Exception e) {
            throw new DataFormatException("ExistingSession payload error", e);
        }
        boolean shouldAck = false;
        if (pc.nextKeys != null) {
            for (NextSessionKey nextKey : pc.nextKeys) {
                keyManager.nextKeyReceived(remote, nextKey);
                if (!nextKey.isReverse())
                    shouldAck = true;
            }
        }
        if (pc.ackRequested) {
            keyManager.ackRequested(remote, key.getID(), nonce);
            shouldAck = true;
        }
        if (shouldAck) {
            setResponseTimer(remote, pc.cloveSet, keyManager);
        }
        if (pc.cloveSet.isEmpty()) {
            // this is legal
            if (_log.shouldDebug())
                _log.debug("No Garlic block in ExistingSession payload");
            return NO_CLOVES;
        }
        int num = pc.cloveSet.size();
        GarlicClove[] arr = new GarlicClove[num];
        // msg id and expiration not checked in GarlicMessageReceiver
        return new CloveSet(pc.cloveSet.toArray(arr), Certificate.NULL_CERT, 0, pc.datetime);
    }

    /** Per-thread ChaCha-Poly cipher state, reused to avoid per-decrypt allocation. */
    private static final ThreadLocal<ChaChaPolyCipherState> _chachaLocal = ThreadLocal.withInitial(ChaChaPolyCipherState::new);

    /**
     * With optional AD. Decrypts IN PLACE. Decrypted data will be at
     * encrypted[offset:offset + len - 16].
     *
     * @param ad optional associated data, may be null
     * @param encrypted the ciphertext, overwritten in place with the plaintext
     * @param offset where the ciphertext starts in encrypted
     * @param encryptedLen bytes to decrypt at offset, including the 16-byte MAC
     * @param key the 32-byte ChaCha20-Poly1305 key to decrypt with
     * @param n the nonce for this message, taken from the session key's counter
     * @return true if the MAC verified and the data was decrypted in place
     */
    private boolean decryptAEADBlock(byte[] ad, byte[] encrypted, int offset, int encryptedLen, SessionKey key,
                                    long n) {
        ChaChaPolyCipherState chacha = _chachaLocal.get();
        chacha.initializeKey(key.getData(), 0);
        chacha.setNonce(n);
        try {
            chacha.decryptWithAd(ad, encrypted, offset, encrypted, offset, encryptedLen);
            return true;
        } catch (GeneralSecurityException e) {
            if (_log.shouldWarn()) {
                String failureType = e.getMessage();
                if (failureType != null) {
                    if (failureType.contains("MAC") || failureType.contains("mac")) {
                        failureType = "AEAD MAC verification failed";
                    } else if (failureType.contains("decrypt") || failureType.contains("Decrypt")) {
                        failureType = "AEAD decryption failed";
                    } else if (failureType.contains("nonce") || failureType.contains("Nonce")) {
                        failureType = "AEAD nonce error";
                    } else {
                        failureType = "AEAD cryptographic error: " + failureType;
                    }
                } else {
                    failureType = "AEAD unknown error";
                }
                _log.warn("Unable to decrypt AEAD block -> " + failureType, e);
            }
            return false;
        } finally {
            chacha.destroy();
        }
    }


    /**
     * Encrypt the data to the target using the given key and deliver the specified tags
     * No new session key
     * This is the one called from GarlicMessageBuilder and is the primary entry point.
     *
     * @param cloves the cloves to encrypt and send
     * @param target public key to which the data should be encrypted.
     * @param to destination to encrypt for, required when priv is non-null
     * @param priv local private key to encrypt with, from the leaseset
     *             may be null for anonymous (N-in-IK)
     *
     * @param keyManager session key manager, required when priv is non-null
     * @param callback may be null, if non-null an ack will be requested (except NS/NSR),
     *                 not used when priv is null
     *
     * @return encrypted data or null on failure
     */
    public byte[] encrypt(CloveSet cloves, PublicKey target, Destination to, PrivateKey priv,
                          RatchetSKM keyManager,
                          ReplyCallback callback) {
        try {
            return x_encrypt(cloves, target, to, priv, keyManager, callback);
        } catch (Exception e) {
            _log.error("ECIES encryption error", e);
            return null;
        }
    }

    /**
     * Encrypt the cloves to the target, selecting New Session, New Session Reply, or Existing
     * Session encryption as appropriate.
     *
     * @param cloves the cloves to encrypt and send
     * @param target the peer's static public key to encrypt to, never the all-zero key
     * @param to destination to encrypt for, required when priv is non-null
     * @param priv local private key to encrypt with, from the leaseset
     *             may be null for anonymous (N-in-IK)
     *
     * @param keyManager session key manager, required when priv is non-null
     * @param callback may be null, not used when priv is null
     * @return the encrypted message, or null if the target is the all-zero key or
     *         the selected scheme fails
     */
    private byte[] x_encrypt(CloveSet cloves, PublicKey target, Destination to, PrivateKey priv,
                             RatchetSKM keyManager,
                             ReplyCallback callback) {
        checkType(target.getType());
        if (Arrays.equals(target.getData(), NULLPK)) {
            if (_log.shouldWarn())
                _log.warn("Zero static key target");
            return null;
        }
        if (priv == null) {
            if (_log.shouldDebug())
                _log.debug("Encrypting as Null -> Target: " + target);
            // N-in-IK, used for anonymous garlic messages
            return encryptNewSession(cloves, target);
        }
        RatchetEntry re = keyManager.consumeNextAvailableTag(target);
        if (re == null) {
            if (_log.shouldDebug())
                _log.debug("Encrypting as NewSession -> Target: " + target);
            return encryptNewSession(cloves, target, to, priv, keyManager, callback);
        }

        HandshakeState state = re.key.getHandshakeState();
        if (state != null) {
            try {
                state = state.clone();
            } catch (CloneNotSupportedException e) {
                if (_log.shouldWarn())
                    _log.warn("ECIES encryption failure: clone()", e);
                return null;
            }
            if (_log.shouldDebug())
                _log.debug("Encrypting as NewSession Reply -> Target: " + target + "\n* Tag: " + re.tag.toBase64());
            return encryptNewSessionReply(cloves, target, state, re.tag, keyManager, callback);
        }
        if (_log.shouldDebug())
            _log.debug("Encrypting as ExistingSession -> Target: " + target + "\n* Key: " + re.key + "\n* Tag: " + re.tag.toBase64());
        byte[] rv = encryptExistingSession(cloves, target, re, callback, keyManager);
        if (rv == null) {
            if (_log.shouldWarn())
                _log.warn("ECIES ExistingSession encryption failure");
        } else if (_log.shouldDebug())
            _log.debug("Encrypting as ExistingSession -> To: " + target + "\n* Key: " + re.key +
                       "\n* Tag: " + re.tag.toBase64() +
                       (re.nextForwardKey != null ? "\n* Forward key: " + re.nextForwardKey : "") +
                       (re.nextReverseKey != null ? "\n* Reverse key: " + re.nextReverseKey : "") +
                       "\n* Result: " + rv.length + " bytes");
        return rv;
    }

    /**
     * Scenario 1: New Session Message
     * <p>
     * Begin with 80 bytes, ECIES encrypted, containing:
     * <pre>
     * - 32 byte Elligator2 key
     * - 32 byte static key
     * - 16 byte MAC
     * </pre>
     * And then the data:
     * <pre>
     * - payload
     * - 16 byte MAC
     * </pre>
     *
     * @param cloves the cloves to encrypt and send
     * @param target the peer's static public key to encrypt to
     * @param to destination to encrypt for, required when priv is non-null
     * @param priv local private key to encrypt with, from the leaseset
     *             may be null for anonymous (N-in-IK)
     *
     * @param keyManager session key manager, required when priv is non-null
     * @param callback may be null, not used when priv is null
     * @return the encrypted NewSession message, or null if the handshake or the
     *         Elligator2 encoding fails
     */
    private byte[] encryptNewSession(CloveSet cloves, PublicKey target, Destination to, PrivateKey priv,
                                     RatchetSKM keyManager,
                                     ReplyCallback callback) {
        EncType type = target.getType();
        if (priv == null || type != priv.getType()) {
            throw new IllegalArgumentException("Key mismatch " + target + ' ' + priv);
        }
        HandshakeState state;

        try {
            NoiseInit.PatternID pattern = getNoisePattern(target.getType());
            state = new HandshakeState(pattern, HandshakeState.INITIATOR, _edhThread, getHybridKeyFactory(type));
        } catch (GeneralSecurityException gse) {
            throw new IllegalStateException("Bad protocol", gse);
        }

        state.getRemotePublicKey().setPublicKey(target.getData(), 0);
        state.getLocalKeyPair().setKeys(priv.getData(), 0, priv.toPublic().getData(), 0);
        state.start();
        if (_log.shouldDebug()) {
            _log.debug("State before encrypting NewSession: " + state);
        }

        byte[] payload = createPayload(cloves, cloves.getExpiration(), NS_OVERHEAD);

        int enclen = KEYLEN + KEYLEN + MACLEN + payload.length + MACLEN;
        DHState hyb = state.getLocalHybridKeyPair();
        if (hyb != null) {
            enclen += hyb.getPublicKeyLength() + MACLEN;
        }
        byte[] enc = new byte[enclen];
        try {
            state.writeMessage(enc, 0, payload, 0, payload.length);
        } catch (GeneralSecurityException gse) {
            if (_log.shouldWarn()) {_log.warn("Encrypt fail NewSession", gse);}
            state.destroy();
            return null;
        }
        if (_log.shouldDebug()) {
            _log.debug("Encrypted NewSession: " + enc.length + " bytes " + state);
        }

        // overwrite eph. key with encoded key
        DHState eph = state.getLocalEphemeralKeyPair();
        if (eph == null || !eph.hasEncodedPublicKey()) {
            if (_log.shouldWarn())
                _log.warn("Bad NewSession state");
            state.destroy();
            return null;
        }

        eph.getEncodedPublicKey(enc, 0);
        if (_log.shouldDebug()) {
            _log.debug("Elligator2 encoded ephemeral key [" + Base64.encode(enc, 0, 32) + "]");
        }

        // tell the SKM
        keyManager.createSession(target, to, state, callback);
        return enc;
    }

    /**
     * Scenario 1A: New Session Message from an anonymous source.
     * <pre>
     * - 32 byte ephemeral key (NOT Elligator2)
     * - payload
     * - 16 byte MAC
     * </pre>
     *
     * @param cloves the cloves to encrypt and send
     * @param target the peer's static public key to encrypt to
     * @return the encrypted anonymous NewSession message, or null if the handshake fails
     * @since 0.9.49
     */
    private byte[] encryptNewSession(CloveSet cloves, PublicKey target) {
        HandshakeState state;
        try {
            state = new HandshakeState(NoiseInit.PatternID.N_NO_RESPONSE, HandshakeState.INITIATOR, _context.commSystem().getXDHFactory());
        } catch (GeneralSecurityException gse) {
            throw new IllegalStateException("Bad protocol", gse);
        }
        state.getRemotePublicKey().setPublicKey(target.getData(), 0);
        state.start();
        if (_log.shouldDebug())
            _log.debug("State before encrypt new session: " + state);

        byte[] payload = createPayload(cloves, cloves.getExpiration(), NS_N_OVERHEAD);

        byte[] enc = new byte[KEYLEN + payload.length + MACLEN];
        try {
            state.writeMessage(enc, 0, payload, 0, payload.length);
        } catch (GeneralSecurityException gse) {
            if (_log.shouldWarn())
                _log.warn("Encrypt failed in the Noise N handshake", gse);
            state.destroy();
            return null;
        }
        if (_log.shouldDebug())
            _log.debug("Encrypted the Noise N handshake: " + enc.length + " bytes, state: " + state);

        state.destroy();
        return enc;
    }

    /**
     * Scenario 2: New Session Reply Message
     * <p>
     * Begin with 56 bytes, containing:
     * <pre>
     * - 8 byte SessionTag
     * - 32 byte Elligator2 key
     * - 16 byte MAC
     * </pre>
     * And then the data:
     * <pre>
     * - payload
     * - 16 byte MAC
     * </pre>
     *
     * @param cloves the cloves to encrypt and send
     * @param target the peer's static public key we are replying to
     * @param state must have already been cloned
     * @param currentTag the 8-byte session tag to prefix the message with, mixed
     *                   into the handshake hash
     * @param keyManager updated with the split keys and the callback on success
     * @param callback may be null
     * @return the encrypted NewSessionReply, or null if either part of the encrypt fails
     */
    private byte[] encryptNewSessionReply(CloveSet cloves, PublicKey target, HandshakeState state,
                                          RatchetSessionTag currentTag, RatchetSKM keyManager,
                                          ReplyCallback callback) {
        if (_log.shouldDebug())
            _log.debug("State before encrypting NewSessionReply: " + state);
        byte[] tag = currentTag.getData();
        state.mixHash(tag, 0, TAGLEN);
        if (_log.shouldDebug())
            _log.debug("State after MixHash tag before encrypting NewSessionReply: " + state);

        byte[] payload = createPayload(cloves, 0, NSR_OVERHEAD);

        // part 1 - tag and empty payload
        int enclen = TAGLEN + KEYLEN + MACLEN + payload.length + MACLEN;
        DHState hyb = state.getLocalHybridKeyPair();
        if (hyb != null) {
            enclen += hyb.getPublicKeyLength() + MACLEN;
        }
        byte[] enc = new byte[enclen];
        System.arraycopy(tag, 0, enc, 0, TAGLEN);
        try {
            state.writeMessage(enc, TAGLEN, ZEROLEN, 0, 0);
        } catch (GeneralSecurityException gse) {
            if (_log.shouldWarn())
                _log.warn("Encryption failure for NewSessionReply part 1", gse);
            return null;
        }
        if (_log.shouldDebug())
            _log.debug("Encrypted NewSessionReply: " + enc.length + " bytes " + state);

        // overwrite eph. key with encoded key
        DHState eph = state.getLocalEphemeralKeyPair();
        if (eph == null || !eph.hasEncodedPublicKey()) {
            if (_log.shouldWarn())
                _log.warn("Bad NewSessionReply state");
            return null;
        }
        eph.getEncodedPublicKey(enc, TAGLEN);

        // split()
        // Noise does it too but it trashes the keys
        SplitKeys split = new SplitKeys(state, _hkdf);
        CipherStatePair ckp = state.split();
        CipherState sender = ckp.getSender();
        byte[] hash = state.getHandshakeHash();

        // part 2 - payload
        byte[] encpayloadkey = new byte[32];
        _hkdf.calculate(split.k_ba.getData(), ZEROLEN, INFO_6, encpayloadkey);
        sender.initializeKey(encpayloadkey, 0);
        int off = TAGLEN + KEYLEN + MACLEN;
        if (hyb != null) {
            off += hyb.getPublicKeyLength() + MACLEN;
        }
        try {
            sender.encryptWithAd(hash, payload, 0, enc, off, payload.length);
        } catch (GeneralSecurityException gse) {
            if (_log.shouldWarn())
                _log.warn("Encrypt failure for NewSessionReply part 2", gse);
            return null;
        }
        // tell the SKM
        keyManager.updateSession(target, null, state, callback, split);

        return enc;
    }

    /**
     * Scenario 3: Existing Session Message.
     * <pre>
     * - 8 byte SessionTag
     * - payload
     * - 16 byte MAC
     * </pre>
     *
     * @param cloves the cloves to encrypt and send
     * @param target only used if callback is non-null to register it
     * @param re the tag, key and pending next keys and acks taken from the key manager
     * @param callback may be null, if non-null an ack is requested and the callback
     *                 registered for this tag
     * @param keyManager registers the callback for this tag when callback is non-null
     * @return the encrypted ExistingSession message, or null if the AEAD encrypt fails
     */
    private byte[] encryptExistingSession(CloveSet cloves, PublicKey target, RatchetEntry re,
                                          ReplyCallback callback,
                                          RatchetSKM keyManager) {
        boolean ackreq = callback != null || ACKREQ_IN_ES;
        byte[] rawTag = re.tag.getData();
        byte[] payload = createPayload(cloves, 0, ackreq, re.nextForwardKey, re.nextReverseKey, re.acksToSend, ES_OVERHEAD);
        SessionKeyAndNonce key = re.key;
        int nonce = key.getNonce();
        byte[] encr = encryptAEADBlock(rawTag, payload, key, nonce);
        if (encr != null) {
            System.arraycopy(rawTag, 0, encr, 0, TAGLEN);
            if (callback != null)
                keyManager.registerCallback(target, re.keyID, nonce, callback);
        }
        return encr;
    }

    /**
     * Create an Existing Session Message to an anonymous target
     * using the given session key and tag, for netdb DSM/DSRM replies.
     * Called from MessageWrapper.
     * <p>
     * No datetime, no next key, no acks, no ack requests.
     * n=0, ad = the 8-byte session tag.
     * <pre>
     * - 8 byte SessionTag
     * - payload
     * - 16 byte MAC
     * </pre>
     *
     * @param cloves the cloves to encrypt and send
     * @param key existing session key for the ES scheme
     * @param tag session tag to prefix the block with, naming the key to the receiver
     * @return encrypted data or null on failure
     * @since 0.9.46
     */
    public byte[] encrypt(CloveSet cloves, SessionKey key, RatchetSessionTag tag) {
        byte[] rawTag = tag.getData();
        byte[] payload = createPayload(cloves, 0, ES_OVERHEAD);
        byte[] encr = encryptAEADBlock(rawTag, payload, key, 0);
        if (encr != null) {
        System.arraycopy(rawTag, 0, encr, 0, TAGLEN);
        } else if (_log.shouldWarn()) {
            _log.warn("ECIES ExistingSession encryption failure");
        }
        return encr;
    }

    /**
     * Encrypt the data to the target using the given key from an anonymous source,
     * for netdb lookups.
     * Called from MessageWrapper.
     *
     * @param cloves the cloves to encrypt and send
     * @param target public key to which the data should be encrypted.
     * @return encrypted data or null on failure
     * @since 0.9.48
     */
    public byte[] encrypt(CloveSet cloves, PublicKey target) {
        return encrypt(cloves, target, null, null, null, null);
    }

    /**
     * Encrypt the data with the given key and nonce.
     *
     * @param ad may be null
     * @param data the payload to encrypt
     * @param key the 32-byte ChaCha20-Poly1305 session key
     * @param n the nonce for this message, 0 for the anonymous netdb replies
     * @return a buffer with room for the ad, then the ciphertext and the 16-byte
     *         MAC, or null if the cipher fails
     */
    private final byte[] encryptAEADBlock(byte[] ad, byte[] data, SessionKey key, long n) {
        ChaChaPolyCipherState chacha = _chachaLocal.get();
        chacha.initializeKey(key.getData(), 0);
        chacha.setNonce(n);
        int adsz = ad != null ? ad.length : 0;
        byte[] enc = new byte[adsz + data.length + MACLEN];
        try {
            chacha.encryptWithAd(ad, data, 0, enc, adsz, data.length);
        } catch (GeneralSecurityException e) {
            if (_log.shouldWarn())
                _log.warn("Unable to encrypt AEAD block", e);
            return null;
        } finally {
            chacha.destroy();
        }
        return enc;
    }

    /**
     * Compute the Curve25519 Diffie-Hellman shared secret for the given key pair.
     *
     * @param privkey local ECIES_X25519 private key
     * @param pubkey remote ECIES_X25519 public key
     * @return the 32 byte shared secret wrapped as an ECIES_X25519 private key
     */
    static final PrivateKey doDH(PrivateKey privkey, PublicKey pubkey) {
        byte[] dh = new byte[KEYLEN];
        Curve25519.eval(dh, 0, privkey.getData(), pubkey.getData());
        return new PrivateKey(EncType.ECIES_X25519, dh);
    }

    /////////////////////////////////////////////////////////
    // payload stuff
    /////////////////////////////////////////////////////////

    private class PLCallback implements RatchetPayload.PayloadCallback {
        /** Non-null, may be empty. */
        public final List<GarlicClove> cloveSet = new ArrayList<>(3);
        /** The session key manager for the sender. */
        private final RatchetSKM skm;
        /** The sender's ratchet public key. */
        private final PublicKey remote;
        /** The datetime value. */
        public long datetime;
        /** Null or non-empty. */
        public List<NextSessionKey> nextKeys;
        /** Whether an ack was requested. */
        public boolean ackRequested;

        /**
         * NS/NSR.
         */
        public PLCallback() {
            this(null, null);
        }

        /**
         * Callback for an ES payload, which unlike NS/NSR may carry an ACK to hand
         * back to the ratchet session key manager.
         *
         * @param keyManager only for ES, otherwise null
         * @param remoteKey only for ES, otherwise null
         * @since 0.9.46
         */
        public PLCallback(RatchetSKM keyManager, PublicKey remoteKey) {
            skm = keyManager;
            remote = remoteKey;
        }
        /** Process a datetime field from the payload. */

        public void gotDateTime(long time) throws DataFormatException {
            if (_log.shouldDebug())
                _log.debug("Received DATE block: " + DataHelper.formatTime(time));
            if (datetime != 0)
                throw new DataFormatException("Multiple DATETIME blocks");
            datetime = time;
            long now = _context.clock().now();
            if (time < now - MAX_NS_AGE ||
                time > now + MAX_NS_FUTURE) {
                throw new DataFormatException("Excess clock skew in Inbound NewSession: " + DataHelper.formatTime(time));
            }
        }
        /**
         * Process an options field from the payload.
         * <p>Intentionally a log-only no-op. This router never emits an OPTIONS
         * block (no option bytes are negotiated in {@code createPayload()}) and no
         * local state transition, tag, session key, or acknowledgement behavior is
         * defined in terms of OPTIONS, so ignoring a received block is
         * protocol-correct. The length and framing are already validated by the
         * parser.
         *
         * @param options the option bytes
         * @param isHandshake true only for message 3 part 2
         */
        public void gotOptions(byte[] options, boolean isHandshake) {
            if (_log.shouldDebug())
                _log.debug("Received OPTIONS block (" + options.length + " bytes)");
        }
        /** Process a garlic clove field from the payload. */

        public void gotGarlic(GarlicClove clove) {
            if (_log.shouldDebug())
                _log.debug("Received GARLIC block: " + clove);
            cloveSet.add(clove);
        }
        /** Process a next key field from the payload. */

        public void gotNextKey(NextSessionKey next) {
            if (_log.shouldDebug())
                _log.debug("Received NEXTKEY block: " + next);
            // could have both a forward and reverse.
            // shouldn't have two forwards or two reverses
            if (nextKeys == null)
                nextKeys = new ArrayList<>(2);
            nextKeys.add(next);
        }
        /** Process an ack field from the payload. */

        public void gotAck(int id, int n) {
            if (_log.shouldDebug())
                _log.debug("Received ACK block: " + id + " / " + n);
            if (skm != null)
                skm.receivedACK(remote, id, n);
            else if (_log.shouldWarn())
                _log.warn("ACK received during NewSession or NewSessionReply -> Ignored");
        }
        /** Process an ack request field from the payload. */

        public void gotAckRequest() {
            if (_log.shouldDebug())
                _log.debug("Received ACK REQUEST block");
            ackRequested = true;
        }
        /**
         * Process a termination field from the payload.
         * <p>Intentionally a log-only no-op. Ratchet sessions here end by
         * ratchet key rotation or by the session expiring in
         * {@code RatchetSKM.aggressiveExpire()}, never by a peer-sent
         * TERMINATION block, and this router never emits one. The parser
         * already rejects any block following a TERMINATION other than
         * padding, so the framing is enforced without acting on the reason.
         *
         * @param reason the peer's termination reason code, 0-255
         */
        public void gotTermination(int reason) {
            if (_log.shouldDebug())
                _log.debug("Received TERMINATION block, reason: " + reason);
        }
        /**
         * Process a PN field from the payload.
         * <p>Intentionally a log-only no-op. The message number block is an
         * optional advisory hint for out-of-order reassembly; ratchet
         * decryption here is authenticated per message and does not dedupe by
         * number, and this router never emits a PN block. Accepting and
         * ignoring it is therefore protocol-correct.
         *
         * @param pn the peer's message number, 0-65535
         */
        public void gotPN(int pn) {
            if (_log.shouldDebug())
                _log.debug("Received PN block, pn: " + pn);
        }
        /** Process an unknown field from the payload. */

        public void gotUnknown(int type, int len) {
            if (_log.shouldDebug())
                _log.debug("Received UNKNOWN block, type: " + type + " len: " + len);
        }
        /** Process a padding field from the payload. */

        public void gotPadding(int paddingLength, int frameLength) {
            if (_log.shouldDebug())
                _log.debug("Received PADDING block, len: " + paddingLength + " in frame len: " + frameLength);
        }
    }

    /**
     * Create the payload, adding a DateTime block if expiration is greater than zero.
     *
     * @param cloves the garlic cloves to include as payload blocks
     * @param expiration if greater than zero, add a DateTime block
     * @param overhead bytes to be added later, to assist in padding calculation
     * @return the padded payload blocks, ready for the handshake or AEAD layer
     * @since 0.9.46
     */
    private byte[] createPayload(CloveSet cloves, long expiration, int overhead) {
        return createPayload(cloves, expiration, false, null, null, null, overhead);
    }

    /** First size bucket for the padding distribution. */
    private static final int B1 = 944;
    /** Second size bucket for the padding distribution. */
    private static final int B2 = 1936;
    /** Third size bucket for the padding distribution. */
    private static final int B3 = 2932;

    /**
     * Create the payload from the given cloves and optional blocks.
     *
     * @param cloves the garlic cloves to include as payload blocks
     * @param expiration if greater than zero, add a DateTime block
     * @param ackreq to request an ack, must be false for NS/NSR
     * @param nextKey1 next session key block to include, may be null
     * @param nextKey2 second next session key block, may be null
     * @param acksToSend acknowledgement ids to send, may be null
     * @param overhead bytes to be added later, to assist in padding calculation
     * @return the payload blocks, padded to stay under the tunnel message size
     *         boundaries of 944, 1936 or 2932 bytes
     */
    private byte[] createPayload(CloveSet cloves, long expiration,
                                 boolean ackreq, NextSessionKey nextKey1,
                                 NextSessionKey nextKey2, List<Integer> acksToSend,
                                 int overhead) {
        int count = cloves.getCloveCount();
        int len = 0;
        BlockPool pool = _blockPool.get();
        List<Block> blocks = pool.getBlocks();
        if (expiration > 0) {
            Block block = pool.acquireDateTime(expiration);
            blocks.add(block);
            len += block.getTotalLength();
        }
        if (nextKey1 != null) {
            Block block = pool.acquireNextKey(nextKey1);
            blocks.add(block);
            len += block.getTotalLength();
        }
        if (nextKey2 != null) {
            Block block = pool.acquireNextKey(nextKey2);
            blocks.add(block);
            len += block.getTotalLength();
        }
        for (int i = 0; i < count; i++) {
            GarlicClove clove = cloves.getClove(i);
            Block block = pool.acquireClove(clove);
            blocks.add(block);
            len += block.getTotalLength();
        }
        if (ackreq) {
            // put after the cloves so recipient has any LS garlic
            Block block = pool.acquireAckReq();
            blocks.add(block);
            len += block.getTotalLength();
        }
        if (acksToSend != null) {
            Block block = pool.acquireAck(acksToSend);
            blocks.add(block);
            len += block.getTotalLength();
        }

        // Padding
        // Key lengths we're trying to not exceed:
        // 944 for one tunnel message
        // 1936 for two tunnel messages
        // 2932 for three tunnel messages
        // See streaming ConnectionOptions for the math
        int fixedpad;
        int randompad;
        int totlen = len + overhead;
        if ((totlen > B1 - BHLEN && totlen <= B1) ||
            (totlen > B2 - BHLEN && totlen <= B2) ||
            (totlen > B3 - BHLEN && totlen <= B3)) {
            // no room for block
            fixedpad = 0;
            randompad = 0;
        } else if (totlen > B1 - BHLEN - MAXPAD && totlen <= B1 - BHLEN) {
            // fill it up
            fixedpad = B1 - BHLEN - totlen;
            randompad = 0;
        } else if (totlen > B2 - BHLEN - MAXPAD && totlen <= B2 - BHLEN) {
            // fill it up
            fixedpad = B2 - BHLEN - totlen;
            randompad = 0;
        } else if (totlen > B3 - BHLEN - MAXPAD && totlen <= B3 - BHLEN) {
            // fill it up
            fixedpad = B3 - BHLEN - totlen;
            randompad = 0;
        } else {
            // we're not close to a boundary, just do random
            fixedpad = 0;
            randompad = MAXPAD;
        }
        if (fixedpad > 0 || randompad > 0) {
            int padlen;
            if (fixedpad > 0) {
                padlen = fixedpad;
            } else {
                padlen = _context.random().nextInt(randompad);
                if (overhead == NS_OVERHEAD &&
                    ((totlen + BHLEN + padlen) & 0x0f) == 2) {
                    // do a favor for muxed decrypt
                    if (padlen > 0)
                        padlen--;
                    else
                        padlen++;
                }
            }
            // zeros
            Block block = pool.acquirePadding(padlen);
            blocks.add(block);
            len += block.getTotalLength();
        }
        byte[] payload = new byte[len];
        int payloadlen = RatchetPayload.writePayload(payload, 0, blocks);
        if (payloadlen != len)
            throw new IllegalStateException("Payload size mismatch");
        pool.release(blocks);
        return payload;
    }

    /**
     * Schedules the delayed reply to an NS message.
     *
     * @param from the sender's ratchet key
     * @param cloveSet the decrypted cloves to reply with
     * @param skm the session key manager for the sender
     */
    private void setResponseTimerNS(PublicKey from, List<GarlicClove> cloveSet, RatchetSKM skm) {
        Destination us = skm.getDestination();
        // temp for router SKM
        if (us == null)
            return;
        for (GarlicClove clove : cloveSet) {
            I2NPMessage msg = clove.getData();
            if (msg.getType() != DatabaseStoreMessage.MESSAGE_TYPE)
                continue;
            DatabaseStoreMessage dsm = (DatabaseStoreMessage) msg;
            DatabaseEntry entry = dsm.getEntry();
            if (entry.getType() != DatabaseEntry.KEY_TYPE_LS2)
                continue;
            LeaseSet2 ls2 = (LeaseSet2) entry;
            // i2pd bug?
            if (ls2.getLeaseCount() == 0)
                return;
            if (!ls2.isCurrent(Router.CLOCK_FUDGE_FACTOR))
                continue;
            PublicKey pk = ls2.getEncryptionKey(getEncTypeSet(from.getType()));
            if (!from.equals(pk))
                continue;
            if (!ls2.verifySignature())
                continue;
            // OK, we have a valid place to send the reply
            Destination d = ls2.getDestination();
            if (_log.shouldInfo())
                _log.info("Validated NewSession sender: " + d.toBase32());
            ACKTimer ack = new ACKTimer(_context, us, d);
            if (skm.registerTimer(from, d, ack)) {
                ack.schedule(1000);
            }
            return;
        }
        if (_log.shouldInfo())
            _log.info("Unvalidated NewSession sender: " + from);
    }

    /**
     * Schedules the delayed reply to an NSR message.
     *
     * @param from the sender's ratchet key
     * @param cloveSet the decrypted cloves to reply with
     * @param skm the session key manager for the sender
     */
    private void setResponseTimer(PublicKey from, List<GarlicClove> cloveSet, RatchetSKM skm) {
        Destination d = skm.getDestination(from);
        if (d != null) {
            Destination us = skm.getDestination();
            // temp for router SKM
            if (us == null)
                return;
            ACKTimer ack = new ACKTimer(_context, us, d);
            if (skm.registerTimer(from, null, ack)) {
                ack.schedule(1000);
            }
        } else {
            // we didn't get a LS in the original NS, but maybe we have one now
            if (_log.shouldInfo())
                _log.info("No full dest to ACK to, looking for LeaseSet from: " + from);
            setResponseTimerNS(from, cloveSet, skm);
        }
    }

    /**
     * Per-thread pool of payload block objects and the block list,
     * to avoid allocation per outbound payload.
     *
     * @since 0.9.71+
     */
    static class BlockPool {

        /**
         * Constructs an empty pool. Every list is initialised inline, so a bare
         * instance is immediately usable by the acquire methods.
         *
         * @since 0.9.71+
         */
        BlockPool() {}

        /** Blocks taken from the pool, returned on release. */
        private final List<Block> blocks = new ArrayList<>(8);
        /** Pooled garlic blocks. */
        private final List<GarlicBlock> cloveBlocks = new ArrayList<>(4);
        /** Pooled padding blocks. */
        private final List<PaddingBlock> paddingBlocks = new ArrayList<>(2);
        /** Pooled datetime blocks. */
        private final List<DateTimeBlock> datetimeBlocks = new ArrayList<>(1);
        /** Pooled next-key blocks. */
        private final List<NextKeyBlock> nextKeyBlocks = new ArrayList<>(1);
        /** Pooled ack blocks. */
        private final List<AckBlock> ackBlocks = new ArrayList<>(1);
        /** Pooled ack-request blocks. */
        private final List<AckRequestBlock> ackReqBlocks = new ArrayList<>(1);

        /**
         * The reusable block list, already cleared.
         *
         * @return the empty mutable list the caller fills with this payload's blocks
         */
        List<Block> getBlocks() {
            blocks.clear();
            return blocks;
        }

        /**
         * Take a pooled GarlicBlock for this clove, or allocate one.
         *
         * @param clove the clove to wrap, borrowed for the payload's lifetime
         * @return a GarlicBlock holding clove, ready to be serialized
         */
        GarlicBlock acquireClove(GarlicClove clove) {
            if (cloveBlocks.isEmpty())
                return new GarlicBlock(clove);
            GarlicBlock b = cloveBlocks.remove(cloveBlocks.size() - 1);
            b.setClove(clove);
            return b;
        }

        /**
         * Take a pooled PaddingBlock of this size, or allocate one.
         *
         * @param size the padding length in bytes, 0 or more
         * @return a PaddingBlock writing that many zero bytes
         */
        PaddingBlock acquirePadding(int size) {
            if (paddingBlocks.isEmpty())
                return new PaddingBlock(size);
            PaddingBlock b = paddingBlocks.remove(paddingBlocks.size() - 1);
            b.setSize(size);
            return b;
        }

        /**
         * Take a pooled DateTimeBlock for this expiry, or allocate one.
         *
         * @param time the expiry in ms since the epoch (4-byte seconds on the wire)
         * @return a DateTimeBlock carrying time
         */
        DateTimeBlock acquireDateTime(long time) {
            if (datetimeBlocks.isEmpty())
                return new DateTimeBlock(time);
            DateTimeBlock b = datetimeBlocks.remove(datetimeBlocks.size() - 1);
            b.setTime(time);
            return b;
        }

        /**
         * Take a pooled NextKeyBlock for this key, or allocate one.
         *
         * @param nextKey the next ratchet key to hand to the recipient
         * @return a NextKeyBlock carrying nextKey
         */
        NextKeyBlock acquireNextKey(NextSessionKey nextKey) {
            if (nextKeyBlocks.isEmpty())
                return new NextKeyBlock(nextKey);
            NextKeyBlock b = nextKeyBlocks.remove(nextKeyBlocks.size() - 1);
            b.setNext(nextKey);
            return b;
        }

        /**
         * Take a pooled AckBlock for these acknowledgements, or allocate one.
         *
         * @param acks the packed acks, each id &lt;&lt; 16 | n, for the messages being acknowledged
         * @return an AckBlock listing acks, in the order given
         */
        AckBlock acquireAck(List<Integer> acks) {
            if (ackBlocks.isEmpty())
                return new AckBlock(acks);
            AckBlock b = ackBlocks.remove(ackBlocks.size() - 1);
            b.setAcks(acks);
            return b;
        }

        /**
         * Take a pooled AckRequestBlock, or allocate one.
         *
         * @return an empty AckRequestBlock, asking the recipient to acknowledge
         */
        AckRequestBlock acquireAckReq() {
            if (ackReqBlocks.isEmpty())
                return new AckRequestBlock();
            return ackReqBlocks.remove(ackReqBlocks.size() - 1);
        }

        /**
         * Return all blocks to their pools and clear the list.
         * <p>
         * A pooled block is handed back with whatever it last carried still
         * reachable from it, so the reference-holding types are cleared on the
         * way in: a GarlicBlock pins the whole clove and everything the clove
         * holds, and a NextKeyBlock pins a live session key. The pools are
         * per-thread, so an uncleared block keeps that object for the life of
         * the transport thread. Every acquire re-arms the field before the block
         * is returned to the caller, so a cleared block is never read.
         *
         * @param blocks the block list to recycle, emptied by this call
         */
        void release(List<Block> blocks) {
            for (Block b : blocks) {
                if (b instanceof GarlicBlock) {
                    GarlicBlock gb = (GarlicBlock) b;
                    gb.setClove(null);
                    cloveBlocks.add(gb);
                } else if (b instanceof PaddingBlock) {
                    paddingBlocks.add((PaddingBlock) b);
                } else if (b instanceof DateTimeBlock) {
                    datetimeBlocks.add((DateTimeBlock) b);
                } else if (b instanceof NextKeyBlock) {
                    NextKeyBlock nb = (NextKeyBlock) b;
                    nb.setNext(null);
                    nextKeyBlocks.add(nb);
                } else if (b instanceof AckBlock) {
                    ackBlocks.add((AckBlock) b);
                } else if (b instanceof AckRequestBlock) {
                    ackReqBlocks.add((AckRequestBlock) b);
                }
            }
            blocks.clear();
        }
    }

    /** Per-thread pool of ratchet payload blocks. */
    private static final ThreadLocal<BlockPool> _blockPool = new ThreadLocal<BlockPool>() {
        @Override
        protected BlockPool initialValue() {
            return new BlockPool();
        }
    };

}

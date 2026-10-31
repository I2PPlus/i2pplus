package net.i2p.crypto;

// License: BSD. See docs/LICENSES.md
import net.i2p.I2PAppContext;
import net.i2p.crypto.eddsa.EdDSAEngine;
import net.i2p.crypto.eddsa.EdDSAKey;
import net.i2p.crypto.eddsa.RedDSAEngine;
import net.i2p.data.Hash;
import net.i2p.data.Signature;
import net.i2p.data.SigningPrivateKey;
import net.i2p.data.SigningPublicKey;
import net.i2p.data.SimpleDataStructure;
import net.i2p.util.Log;
import net.i2p.util.NativeBigInteger;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.security.GeneralSecurityException;
import java.security.Key;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.interfaces.DSAKey;
import java.security.interfaces.ECKey;
import java.security.interfaces.RSAKey;

/**
 * Digital signature algorithm engine supporting DSA, ECDSA, RSA, and EdDSA.
 * Provides signing and verification operations for all supported signature types.
 *
 * @since 0.8.1
 */
public final class DSAEngine {
    /** Logger for this class. */
    private final Log _log;
    /** The context supplying the random. */
    private final I2PAppContext _context;

    /** Whether to use the JVM's own DSA implementation. */
    private static final boolean _useJavaLibs = false; // = _isAndroid;

    /**
     * Thread-local SHA-1 digest for {@link #calculateHash(byte[], int, int)} and
     * {@link #calculateHash(InputStream)}, so that a DSA-SHA1 hash does not
     * allocate a fresh SHA1 (a MessageDigestSpi plus a 64 byte pad) each time.
     * Reset on every acquire and never handed outside this class, so there is
     * no state that can leak between calls.
     */
    private static final ThreadLocal<MessageDigest> _sha1 = ThreadLocal.withInitial(SHA1::getInstance);

    /**
     * Thread-local SHA-512 digest for the EdDSA sign/verify paths, so that
     * each operation does not clone a fresh MessageDigest off the SigType
     * prototype. The engine resets it on every init, and it is never handed
     * outside this class, so no state can leak between calls.
     */
    private static final ThreadLocal<MessageDigest> _sha512 = ThreadLocal.withInitial(DSAEngine::sha512);

    /**
     * Two less than dsaq, the Fermat exponent for the constant-time DSA nonce inverse.
     *
     * @see #signIt
     */
    private static final BigInteger DSAQ_MINUS_2 = CryptoConstants.dsaq.subtract(BigInteger.valueOf(2));

    /**
     * Gives a SHA-512 digest, from a pool.
     *
     * @return a digest ready for use
     */
    private static MessageDigest sha512() {
        try {
            return MessageDigest.getInstance("SHA-512");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-512 unavailable", e);
        }
    }

    /**
     * New EdDSA or RedDSA engine for the given type, sharing the caller's
     * (thread-local) SHA-512 digest.
     *
     * @param type the signature type; RedDSA_SHA512_Ed25519 selects the RedDSA
     *             engine, any other type the standard EdDSA engine
     * @param md the SHA-512 digest the new engine writes into
     * @return a newly allocated engine, non-null
     */
    private static EdDSAEngine engineFor(SigType type, MessageDigest md) {
        if (type == SigType.RedDSA_SHA512_Ed25519)
            return new RedDSAEngine(md);
        return new EdDSAEngine(md);
    }

    /**
     * Create a DSA engine for the given context.
     *
     * @param context the application context, supplying the log, clock and random source
     */
    public DSAEngine(I2PAppContext context) {
        _log = context.logManager().getLog(DSAEngine.class);
        _context = context;
    }

    /**
     * Get the DSA engine instance from the global context.
     *
     * @return the DSA engine
     */
    public static DSAEngine getInstance() {
        return I2PAppContext.getGlobalContext().dsa();
    }

    /**
     * Verify a signature using any supported signature type.
     *
     * @param signature the signature to verify
     * @param signedData the data that was signed
     * @param verifyingKey the public key to verify with
     * @return true if the signature is valid, false otherwise
     */
    public boolean verifySignature(Signature signature, byte[] signedData, SigningPublicKey verifyingKey) {
        return verifySignature(signature, signedData, 0, signedData.length, verifyingKey);
    }

    /**
     * Verify using any sig type as of 0.9.12 (DSA only prior to that).
     *
     * @param signature the signature to verify
     * @param signedData the data that was signed
     * @param offset index within signedData of the first signed byte
     * @param size number of signed bytes to verify, starting at offset
     * @param verifyingKey the public key to verify with
     * @return true if valid, false otherwise
     */
    public boolean verifySignature(Signature signature, byte[] signedData, int offset, int size, SigningPublicKey verifyingKey) {
        boolean rv;
        SigType type = signature.getType();
        if (type != verifyingKey.getType()) throw new IllegalArgumentException("type mismatch sig=" + signature.getType() + " key=" + verifyingKey.getType());
        if (type != SigType.DSA_SHA1) {
            try {
                rv = altVerifySig(signature, signedData, offset, size, verifyingKey);
                if (!rv && _log.shouldWarn())
                    _log.warn(type + " sig verify failed -> Signed data " + size +
                              "B * Key [" + verifyingKey.calculateHash().toBase64().substring(0, 6) + ']');
                return rv;
            } catch (GeneralSecurityException gse) {
                if (_log.shouldWarn())
                    _log.warn(type + " sig verify failed -> Signed data " + size +
                              "B * Key [" + verifyingKey.calculateHash().toBase64().substring(0, 6) + ']', gse);
                return false;
            }
        }
        if (_useJavaLibs) {
            try {
                rv = altVerifySigSHA1(signature, signedData, offset, size, verifyingKey);
                if (!rv && _log.shouldWarn()) _log.warn("Library DSA signature verification failed");
                return rv;
            } catch (GeneralSecurityException gse) {
                if (_log.shouldWarn())
                    _log.warn("Library DSA signature verification threw -> Falling back to TheCrypto", gse);
                // now try TheCrypto
            }
        }
        rv = verifySignature(signature, calculateHash(signedData, offset, size), verifyingKey);
        if (!rv && _log.shouldWarn()) _log.warn("TheCrypto DSA Signature verification failure");
        return rv;
    }

    /**
     * Verify using DSA-SHA1 ONLY.
     *
     * @param signature the signature to verify
     * @param in the data to hash and verify, read to EOF but not closed
     * @param verifyingKey the public key to verify with
     * @return true if valid, false otherwise
     */
    public boolean verifySignature(Signature signature, InputStream in, SigningPublicKey verifyingKey) {
        return verifySignature(signature, calculateHash(in), verifyingKey);
    }

    /**
     * Verify using DSA-SHA1 ONLY.
     *
     * @param signature the signature to verify
     * @param hash SHA-1 hash, NOT a SHA-256 hash
     * @param verifyingKey the public key to verify with
     * @return true if valid, false otherwise
     */
    public boolean verifySignature(Signature signature, SHA1Hash hash, SigningPublicKey verifyingKey) {
        return verifySig(signature, hash, verifyingKey);
    }

    /**
     * Nonstandard.
     * Used by Syndie.
     *
     * @param signature the signature to verify
     * @param hash the pre-computed hash, of the length required by the key's type
     * @param verifyingKey the public key to verify with
     * @return true if valid, false otherwise
     * @since 0.8.3 (restored, was removed in 0.8.1 and 0.8.2)
     */
    public boolean verifySignature(Signature signature, Hash hash, SigningPublicKey verifyingKey) {
        return verifySig(signature, hash, verifyingKey);
    }

    /**
     * Generic signature type.
     * <p>
     * Warning, nonstandard for EdDSA, double-hashes, not recommended.
     *
     * @param signature the signature to verify
     * @param hash SHA1Hash, Hash, Hash384, or Hash512
     * @param verifyingKey the public key to verify with
     * @return true if valid, false otherwise
     * @since 0.9.9
     */
    public boolean verifySignature(Signature signature, SimpleDataStructure hash, SigningPublicKey verifyingKey) {
        SigType type = signature.getType();
        if (type != verifyingKey.getType()) throw new IllegalArgumentException("type mismatch sig=" + type + " key=" + verifyingKey.getType());
        int hashlen = type.getHashLen();
        if (hash.length() != hashlen) throw new IllegalArgumentException("type mismatch hash=" + hash.getClass() + " sig=" + type);
        if (type == SigType.DSA_SHA1) return verifySig(signature, hash, verifyingKey);
        try {
            return altVerifySigRaw(signature, hash, verifyingKey);
        } catch (GeneralSecurityException gse) {
            if (_log.shouldWarn()) _log.warn("Failed to verify signature [" + type + "]", gse);
            return false;
        }
    }

    /**
     * Generic signature type.
     * If you have a Java pubkey, use this, so you don't lose the key parameters,
     * which may be different than the ones defined in {@link SigType}.
     * <p>
     * Warning, nonstandard for EdDSA, double-hashes, not recommended.
     *
     * @param signature the signature to verify
     * @param hash SHA1Hash, Hash, Hash384, or Hash512
     * @param pubKey Java key
     * @return true if valid, false otherwise
     * @since 0.9.9
     */
    public boolean verifySignature(Signature signature, SimpleDataStructure hash, PublicKey pubKey) {
        try {
            return altVerifySigRaw(signature, hash, pubKey);
        } catch (GeneralSecurityException gse) {
            if (_log.shouldWarn()) _log.warn("Failed to verify signature [" + signature.getType() + "]", gse);
            return false;
        }
    }

    /**
     * Verify using DSA-SHA1 or Syndie DSA-SHA256 ONLY.
     *
     * @param signature a DSA-SHA1 signature, the only type accepted
     * @param hash either a Hash or a SHA1Hash
     * @param verifyingKey a DSA-SHA1 public key, the only type accepted
     * @return true if valid, false otherwise
     * @throws IllegalArgumentException if the signature or the key is not
     *                                  DSA-SHA1
     * @since 0.8.3
     */
    private boolean verifySig(Signature signature, SimpleDataStructure hash, SigningPublicKey verifyingKey) {
        if (signature.getType() != SigType.DSA_SHA1) throw new IllegalArgumentException("Bad signature type " + signature.getType());
        if (verifyingKey.getType() != SigType.DSA_SHA1) throw new IllegalArgumentException("Bad key type " + verifyingKey.getType());
        long start = _context.clock().now();

        try {
            byte[] sigbytes = signature.getData();
            // BigInteger has no (array, offset, length) constructor, so each half
            // of the signature needs its own copy; BigInteger may alias the array
            // it is handed, so these cannot be pooled across calls.
            int half = sigbytes.length >> 1;
            byte[] rbytes = new byte[half];
            byte[] sbytes = new byte[half];
            System.arraycopy(sigbytes, 0, rbytes, 0, half);
            System.arraycopy(sigbytes, half, sbytes, 0, half);

            BigInteger s = new NativeBigInteger(1, sbytes);
            BigInteger r = new NativeBigInteger(1, rbytes);
            BigInteger y = new NativeBigInteger(1, verifyingKey.getData());
            // q is prime and s is read from 20 bytes, so s < 2q and the only
            // values with no inverse mod q are 0 and q itself. Ruled out here
            // rather than left to the catch below, because the native
            // implementation behind modInverse() can take a non-coprime operand
            // as a divide-by-zero that ends the JVM instead of throwing.
            if (s.signum() == 0 || s.equals(CryptoConstants.dsaq)) {
                _log.warnThrottled("dsa-s-modinverse",
                                   "Invalid signature: s value has no inverse mod q");
                return false;
            }
            BigInteger w;
            try {
                w = s.modInverse(CryptoConstants.dsaq);
            } catch (ArithmeticException ae) {
                // s == 0 (mod q) has no modular inverse, and the native path
                // behind modInverse() can take a non-coprime operand as a
                // divide-by-zero that ends the JVM rather than throwing what
                // this catch handles - so the value is rejected before the call
                // (see the guard above), and this catch is the fallback. Anyone
                // who can send a signature can reach it, so throttle it and skip
                // the stack trace - the throw site is always this line.
                _log.warnThrottled("dsa-s-modinverse",
                                   "Invalid signature: modInverse() failed for s value");
                return false;
            }
            byte[] data = hash.getData();
            NativeBigInteger bi = new NativeBigInteger(1, data);
            BigInteger u1 = bi.multiply(w).mod(CryptoConstants.dsaq);
            BigInteger u2 = r.multiply(w).mod(CryptoConstants.dsaq);
            BigInteger modval = CryptoConstants.dsag.modPow(u1, CryptoConstants.dsap);
            BigInteger modmulval = modval.multiply(y.modPow(u2, CryptoConstants.dsap));
            BigInteger v = modmulval.mod(CryptoConstants.dsap).mod(CryptoConstants.dsaq);

            boolean ok = v.compareTo(r) == 0;

            long diff = _context.clock().now() - start;
            if (diff > 1000 && _log.shouldWarn()) {
                _log.warn("Took too long to verify the signature (" + diff + "ms)");
            }
            return ok;
        } catch (RuntimeException e) {
            _log.log(Log.CRIT, "Error verifying the signature", e);
            return false;
        }
    }

    /**
     * Sign using any key type.
     * Uses TheCrypto code unless configured to use the java.security libraries.
     *
     * @param data the data to sign
     * @param signingKey the private key matching the signature type
     * @return null on error
     */
    public Signature sign(byte[] data, SigningPrivateKey signingKey) {
        return sign(data, 0, data.length, signingKey);
    }

    /**
     * Sign using any key type as of 0.9.12 (DSA-SHA1 only prior to that).
     *
     * @param data the data to sign
     * @param offset index within data of the first byte to sign
     * @param length number of bytes to sign, starting at offset
     * @param signingKey the private key matching the signature type
     * @return null on error
     */
    public Signature sign(byte[] data, int offset, int length, SigningPrivateKey signingKey) {
        if ((signingKey == null) || (data == null) || (data.length <= 0)) return null;
        SigType type = signingKey.getType();
        if (type != SigType.DSA_SHA1) {
            try {
                return altSign(data, offset, length, signingKey);
            } catch (GeneralSecurityException gse) {
                if (_log.shouldError()) _log.error(type + " Sign Fail", gse);
                return null;
            }
        }
        if (_useJavaLibs) {
            try {
                return altSignSHA1(data, offset, length, signingKey);
            } catch (GeneralSecurityException gse) {
                if (_log.shouldWarn()) _log.warn("Library signing failed -> Falling back to native", gse);
                // now try TheCrypto
            }
        }
        SHA1Hash h = calculateHash(data, offset, length);
        return sign(h, signingKey);
    }

    /**
     * Sign using DSA-SHA1 ONLY.
     * Reads the stream until EOF. Does not close the stream.
     *
     * @param in the data to hash and sign, read to EOF but not closed
     * @param signingKey the private key matching the signature type
     * @return null on error
     */
    public Signature sign(InputStream in, SigningPrivateKey signingKey) {
        if ((signingKey == null) || (in == null)) return null;
        SHA1Hash h = calculateHash(in);
        return sign(h, signingKey);
    }

    /**
     * Sign using DSA-SHA1 ONLY.
     *
     * @param hash SHA-1 hash, NOT a SHA-256 hash
     * @param signingKey the private key matching the signature type
     * @return null on error
     */
    public Signature sign(SHA1Hash hash, SigningPrivateKey signingKey) {
        if ((signingKey == null) || (hash == null)) return null;
        return signIt(hash, signingKey);
    }

    /**
     * Nonstandard.
     * Used by Syndie.
     *
     * @param hash the pre-computed hash, of the length required by the key's type
     * @param signingKey the private key matching the signature type
     * @return null on error
     * @since 0.8.3 (restored, was removed in 0.8.1 and 0.8.2)
     */
    public Signature sign(Hash hash, SigningPrivateKey signingKey) {
        if ((signingKey == null) || (hash == null)) return null;
        return signIt(hash, signingKey);
    }

    /**
     * Generic signature type.
     * <p>
     * Warning, nonstandard for EdDSA, double-hashes, not recommended.
     *
     * @param hash SHA1Hash, Hash, Hash384, or Hash512
     * @param signingKey the private key matching the signature type
     * @return null on error
     * @since 0.9.9
     */
    public Signature sign(SimpleDataStructure hash, SigningPrivateKey signingKey) {
        SigType type = signingKey.getType();
        int hashlen = type.getHashLen();
        if (hash.length() != hashlen) throw new IllegalArgumentException("type mismatch hash=" + hash.getClass() + " key=" + type);
        if (type == SigType.DSA_SHA1) return signIt(hash, signingKey);
        try {
            return altSignRaw(hash, signingKey);
        } catch (GeneralSecurityException gse) {
            if (_log.shouldWarn()) _log.warn(type + " Sign Fail", gse);
            return null;
        }
    }

    /**
     * Generic signature type.
     * If you have a Java privkey, use this, so you don't lose the key parameters,
     * which may be different than the ones defined in SigType.
     * <p>
     * Warning, nonstandard for EdDSA, double-hashes, not recommended.
     *
     * @param hash SHA1Hash, Hash, Hash384, or Hash512
     * @param privKey Java key
     * @param type returns a Signature of this type
     * @return null on error
     * @since 0.9.9
     */
    public Signature sign(SimpleDataStructure hash, PrivateKey privKey, SigType type) {
        String algo = getRawAlgo(privKey);
        String talgo = getRawAlgo(type);
        if (!algo.equals(talgo)) throw new IllegalArgumentException("type mismatch type=" + type + " key=" + privKey.getClass().getSimpleName());
        try {
            return altSignRaw(algo, hash, privKey, type);
        } catch (GeneralSecurityException gse) {
            if (_log.shouldWarn()) _log.warn(type + " Sign Fail", gse);
            return null;
        }
    }

    /**
     * Sign using DSA-SHA1 or Syndie DSA-SHA256 ONLY.
     *
     * @param hash either a Hash or a SHA1Hash
     * @param signingKey a DSA-SHA1 private key, the only type accepted
     * @return the 40-byte signature (r then s), or null on error
     * @throws IllegalArgumentException if the key is not DSA-SHA1
     * @since 0.8.3
     */
    private Signature signIt(SimpleDataStructure hash, SigningPrivateKey signingKey) {
        if (signingKey.getType() != SigType.DSA_SHA1) throw new IllegalArgumentException("Bad key type " + signingKey.getType());
        long start = _context.clock().now();

        NativeBigInteger k;
        boolean ok;
        do {
            k = new NativeBigInteger(160, _context.random());
            ok = k.compareTo(CryptoConstants.dsaq) < 0;
            ok = ok && !k.equals(BigInteger.ZERO);
        } while (!ok);

        BigInteger r = CryptoConstants.dsag.modPowCT(k, CryptoConstants.dsap).mod(CryptoConstants.dsaq);
        // Constant-time inverse via Fermat's little theorem: dsaq is prime and
        // 0 < k < q, so k^-1 = k^(q-2) mod q. The GMP mpz_invert behind
        // k.modInverse() is variable-time on the secret nonce.
        BigInteger kinv = k.modPowCT(DSAQ_MINUS_2, CryptoConstants.dsaq);

        BigInteger m = new NativeBigInteger(1, hash.getData());
        BigInteger x = new NativeBigInteger(1, signingKey.getData());
        BigInteger s = kinv.multiply(m.add(x.multiply(r))).mod(CryptoConstants.dsaq);

        byte[] rbytes = r.toByteArray();
        byte[] sbytes = s.toByteArray();
        byte[] out = new byte[40];

        // (q^random)%p is computationally random
        _context.random().harvester().feedEntropy("DSA.sign", rbytes, 0, rbytes.length);

        if (rbytes.length == 20) {
            for (int i = 0; i < 20; i++) {
                out[i] = rbytes[i];
            }
        } else if (rbytes.length == 21) {
            for (int i = 0; i < 20; i++) {
                out[i] = rbytes[i + 1];
            }
        } else if (rbytes.length > 21) {
            _log.error("Bad R length " + rbytes.length);
            return null;
        } else {
            for (int i = 0; i < rbytes.length; i++) out[i + 20 - rbytes.length] = rbytes[i];
        }
        if (sbytes.length == 20) {
            for (int i = 0; i < 20; i++) {
                out[i + 20] = sbytes[i];
            }
        } else if (sbytes.length == 21) {
            for (int i = 0; i < 20; i++) {
                out[i + 20] = sbytes[i + 1];
            }
        } else if (sbytes.length > 21) {
            _log.error("Bad S length " + sbytes.length);
            return null;
        } else {
            for (int i = 0; i < sbytes.length; i++) out[i + 20 + 20 - sbytes.length] = sbytes[i];
        }

        long diff = _context.clock().now() - start;
        if (diff > 1000 && _log.shouldWarn()) {
            _log.warn("Took too long to sign (" + diff + "ms)");
        }

        return new Signature(out);
    }

    /**
     * Reads the stream until EOF. Does not close the stream.
     * Prefer calculateHash(byte[], int, int) for byte array inputs.
     *
     * @param in the stream to read to EOF, not closed here
     * @return the SHA-1 hash, NOT a SHA-256 hash, or null on an IO error
     */
    public SHA1Hash calculateHash(InputStream in) {
        MessageDigest digest = _sha1.get();
        digest.reset();
        byte[] buf = new byte[64];
        int read = 0;
        try {
            while ((read = in.read(buf)) != -1) {
                digest.update(buf, 0, read);
            }
        } catch (IOException ioe) {
            if (_log.shouldWarn()) _log.warn("Unable to hash the stream", ioe);
            return null;
        }
        return new SHA1Hash(digest.digest());
    }

    /**
     * Calculate a SHA-1 hash of the source data.
     *
     * @param source the data to hash
     * @param offset the offset in the source data
     * @param len the length of data to hash
     * @return the SHA-1 hash
     */
    public static SHA1Hash calculateHash(byte[] source, int offset, int len) {
        MessageDigest h = _sha1.get();
        h.reset();
        h.update(source, offset, len);
        byte[] digested = h.digest();
        return new SHA1Hash(digested);
    }

    /**
     * Generic verify any type.
     *
     * @param signature the signature to check, of any signature type
     * @param data the data the signature was made over
     * @param offset where the signed data starts in data
     * @param len how many bytes of data to verify
     * @param verifyingKey the public key to check against, of the same
     *                     type as the signature
     * @return true if valid, false otherwise
     * @throws GeneralSecurityException if algorithm unavailable or on other errors
     * @throws IllegalArgumentException if the signature and key types differ
     * @since 0.9.9 added off/len 0.9.12
     */
    private boolean altVerifySig(Signature signature, byte[] data, int offset, int len, SigningPublicKey verifyingKey) throws GeneralSecurityException {
        SigType type = signature.getType();
        if (type != verifyingKey.getType()) throw new IllegalArgumentException("type mismatch sig=" + type + " key=" + verifyingKey.getType());
        if (type == SigType.DSA_SHA1) return altVerifySigSHA1(signature, data, offset, len, verifyingKey);

        PublicKey pubKey = SigUtil.toJavaKey(verifyingKey);
        byte[] sigbytes = SigUtil.toJavaSig(signature);
        boolean rv;
        if (type.getBaseAlgorithm() == SigAlgo.EdDSA) {
            // take advantage of one-shot mode
            MessageDigest md = _sha512.get();
            EdDSAEngine jsig = engineFor(type, md);
            jsig.initVerify(pubKey);
            rv = jsig.verifyOneShot(data, offset, len, sigbytes);
        } else {
            java.security.Signature jsig = java.security.Signature.getInstance(type.getAlgorithmName());
            jsig.initVerify(pubKey);
            jsig.update(data, offset, len);
            rv = jsig.verify(sigbytes);
        }
        return rv;
    }

    /**
     * Generic raw verify any type
     * <p>
     * Warning, nonstandard for EdDSA, double-hashes, not recommended.
     *
     * @param signature the signature to check, of any signature type
     * @param hash the pre-computed hash, of the signature type's hash length
     * @param verifyingKey the public key to check against, of the same
     *                     type as the signature
     * @return true if valid, false otherwise
     * @throws GeneralSecurityException if algorithm unavailable or on other errors
     * @throws IllegalArgumentException if the signature and key types differ
     * @since 0.9.9
     */
    private boolean altVerifySigRaw(Signature signature, SimpleDataStructure hash, SigningPublicKey verifyingKey) throws GeneralSecurityException {
        SigType type = signature.getType();
        if (type != verifyingKey.getType()) throw new IllegalArgumentException("type mismatch sig=" + type + " key=" + verifyingKey.getType());

        PublicKey pubKey = SigUtil.toJavaKey(verifyingKey);
        return verifySignature(signature, hash, pubKey);
    }

    /**
     * Generic raw verify any type.
     * If you have a Java pubkey, use this, so you don't lose the key parameters,
     * which may be different than the ones defined in SigType.
     * <p>
     * Warning, nonstandard for EdDSA, double-hashes, not recommended.
     *
     * @param signature the signature to check, of any signature type
     * @param hash the pre-computed hash, of the signature type's hash length
     * @param pubKey the public key as a Java key, with its own parameters
     *               intact
     * @return true if valid, false otherwise
     * @throws GeneralSecurityException if algorithm unavailable or on other errors
     * @throws IllegalArgumentException if the hash length does not match the
     *                                  signature type's hash length
     * @since 0.9.9
     */
    private boolean altVerifySigRaw(Signature signature, SimpleDataStructure hash, PublicKey pubKey) throws GeneralSecurityException {
        SigType type = signature.getType();
        int hashlen = hash.length();
        if (type.getHashLen() != hashlen) throw new IllegalArgumentException("type mismatch hash=" + hash.getClass() + " key=" + type);

        byte[] sigbytes = SigUtil.toJavaSig(signature);
        boolean rv;
        if (type.getBaseAlgorithm() == SigAlgo.EdDSA) {
            // take advantage of one-shot mode
            // Ignore algo, EdDSAKey includes a hash specification.
            MessageDigest md = _sha512.get();
            EdDSAEngine jsig = engineFor(type, md);
            jsig.initVerify(pubKey);
            rv = jsig.verifyOneShot(hash.getData(), sigbytes);
        } else {
            String algo = getRawAlgo(type);
            java.security.Signature jsig = java.security.Signature.getInstance(algo);
            jsig.initVerify(pubKey);
            jsig.update(hash.getData());
            rv = jsig.verify(sigbytes);
        }
        return rv;
    }

    /**
     * Alternate to {@link #verifySignature(Signature, byte[], int, int, SigningPublicKey)}
     * using java.security libraries.
     *
     * @param signature the DSA-SHA1 signature to check
     * @param data the data the signature was made over
     * @param offset where the signed data starts in data
     * @param len how many bytes of data to verify
     * @param verifyingKey the DSA public key to check against
     * @return true if valid, false otherwise
     * @throws GeneralSecurityException if algorithm unavailable or on other errors
     * @since 0.8.7 added off/len 0.9.12
     */
    private boolean altVerifySigSHA1(Signature signature, byte[] data, int offset, int len, SigningPublicKey verifyingKey) throws GeneralSecurityException {
        java.security.Signature jsig = java.security.Signature.getInstance("SHA1withDSA");
        PublicKey pubKey = SigUtil.toJavaDSAKey(verifyingKey);
        jsig.initVerify(pubKey);
        jsig.update(data, offset, len);
        return jsig.verify(SigUtil.toJavaSig(signature));
    }

    /**
     * Generic sign any type.
     *
     * @param data the data to sign
     * @param offset where the data to sign starts in data
     * @param len how many bytes of data to sign
     * @param privateKey the key to sign with, whose type selects the algorithm
     * @return the Signature, or null on error
     * @throws GeneralSecurityException if algorithm unavailable or on other errors
     * @since 0.9.9 added off/len 0.9.12
     */
    private Signature altSign(byte[] data, int offset, int len, SigningPrivateKey privateKey) throws GeneralSecurityException {
        SigType type = privateKey.getType();
        if (type == SigType.DSA_SHA1) return altSignSHA1(data, offset, len, privateKey);

        PrivateKey privKey = SigUtil.toJavaKey(privateKey);
        byte[] sigbytes;
        if (type.getBaseAlgorithm() == SigAlgo.EdDSA) {
            // take advantage of one-shot mode
            MessageDigest md = _sha512.get();
            EdDSAEngine jsig = engineFor(type, md);
            jsig.initSign(privKey);
            sigbytes = jsig.signOneShot(data, offset, len);
        } else {
            java.security.Signature jsig = java.security.Signature.getInstance(type.getAlgorithmName());
            jsig.initSign(privKey, _context.random());
            jsig.update(data, offset, len);
            sigbytes = jsig.sign();
            if (type.getBaseAlgorithm() == SigAlgo.RSA) {
                // verify to prevent corrupted sig key factoring
                // (RSA fault attack) https://eprint.iacr.org/2023/1711.pdf
                SigningPrivateKey priv = SigUtil.fromJavaKey(privKey, type);
                SigningPublicKey pub = priv.toPublic();
                PublicKey pubKey = SigUtil.toJavaKey(pub);
                jsig.initVerify(pubKey);
                jsig.update(data, offset, len);
                if (!jsig.verify(sigbytes)) throw new GeneralSecurityException("Verify of RSA Signature failed");
            }
        }
        return SigUtil.fromJavaSig(sigbytes, type);
    }

    /**
     * Generic raw sign any type.
     * <p>
     * Warning, nonstandard for EdDSA, double-hashes, not recommended.
     *
     * @param hash SHA1Hash, Hash, Hash384, or Hash512
     * @param privateKey the key to sign with, whose type selects the algorithm
     * @return the signature over the hash, of the private key's type
     * @throws GeneralSecurityException if algorithm unavailable or on other errors
     * @since 0.9.9
     */
    private Signature altSignRaw(SimpleDataStructure hash, SigningPrivateKey privateKey) throws GeneralSecurityException {
        SigType type = privateKey.getType();
        String algo = getRawAlgo(type);
        PrivateKey privKey = SigUtil.toJavaKey(privateKey);
        return altSignRaw(algo, hash, privKey, type);
    }

    /**
     * Generic raw sign any type.
     * <p>
     * Warning, nonstandard for EdDSA, double-hashes, not recommended.
     *
     * @param algo the raw JCA algorithm name, e.g. {@code NONEwithDSA}, ignored
     *             for EdDSA types
     * @param hash SHA1Hash, Hash, Hash384, or Hash512
     * @param privKey the key to sign with, as a Java key
     * @param type returns a Signature of this type
     * @return the signature over the hash, of the type named by algo
     * @throws GeneralSecurityException if algorithm unavailable or on other errors
     * @throws IllegalArgumentException if the hash length does not match the
     *                                  type's hash length
     * @since 0.9.9
     */
    private Signature altSignRaw(String algo, SimpleDataStructure hash, PrivateKey privKey, SigType type) throws GeneralSecurityException {
        int hashlen = hash.length();
        if (type.getHashLen() != hashlen) throw new IllegalArgumentException("type mismatch hash=" + hash.getClass() + " key=" + type);

        byte[] sigbytes;
        if (type.getBaseAlgorithm() == SigAlgo.EdDSA) {
            // take advantage of one-shot mode
            // Ignore algo, EdDSAKey includes a hash specification.
            MessageDigest md = _sha512.get();
            EdDSAEngine jsig = engineFor(type, md);
            jsig.initSign(privKey);
            sigbytes = jsig.signOneShot(hash.getData());
        } else {
            java.security.Signature jsig = java.security.Signature.getInstance(algo);
            jsig.initSign(privKey, _context.random());
            jsig.update(hash.getData());
            sigbytes = jsig.sign();
            if (type.getBaseAlgorithm() == SigAlgo.RSA) {
                // verify to prevent corrupted sig key factoring
                // (RSA fault attack) https://eprint.iacr.org/2023/1711.pdf
                SigningPrivateKey priv = SigUtil.fromJavaKey(privKey, type);
                SigningPublicKey pub = priv.toPublic();
                PublicKey pubKey = SigUtil.toJavaKey(pub);
                jsig.initVerify(pubKey);
                jsig.update(hash.getData());
                if (!jsig.verify(sigbytes)) throw new GeneralSecurityException("Verify of RSA Signature failed");
            }
        }
        return SigUtil.fromJavaSig(sigbytes, type);
    }

    /**
     * Alternate to {@link #sign(byte[], int, int, SigningPrivateKey)} using
     * java.security libraries.
     *
     * @param data the data to sign
     * @param offset where the data to sign starts in data
     * @param len how many bytes of data to sign
     * @param privateKey the DSA private key to sign with
     * @return the DSA-SHA1 signature, non-null
     * @throws GeneralSecurityException if algorithm unavailable or on other errors
     * @since 0.8.7 added off/len args 0.9.12
     */
    private Signature altSignSHA1(byte[] data, int offset, int len, SigningPrivateKey privateKey) throws GeneralSecurityException {
        java.security.Signature jsig = java.security.Signature.getInstance("SHA1withDSA");
        PrivateKey privKey = SigUtil.toJavaDSAKey(privateKey);
        jsig.initSign(privKey, _context.random());
        jsig.update(data, offset, len);
        return SigUtil.fromJavaSig(jsig.sign(), SigType.DSA_SHA1);
    }

    /**
     * Map a signature type to the raw (prehashed) JCA algorithm name.
     *
     * @param type the signature type whose base algorithm selects the name
     * @return the JCA algorithm name, e.g. {@code NONEwithDSA}
     * @throws UnsupportedOperationException if the type's base algorithm has
     *                                       no raw form
     * @since 0.9.9
     */
    private static String getRawAlgo(SigType type) {
        switch (type.getBaseAlgorithm()) {
            case DSA: return "NONEwithDSA";
            case EC: return "NONEwithECDSA";
            case EdDSA: return "NONEwithEdDSA";
            case RSA: return "NONEwithRSA";
            default: throw new UnsupportedOperationException("Raw signatures unsupported for " + type);
        }
    }

    /**
     * Map a Java key to the raw (prehashed) JCA algorithm name.
     *
     * @param key the Java key whose class selects the algorithm name
     * @return the JCA algorithm name, e.g. {@code NONEwithDSA}
     * @throws UnsupportedOperationException if the key implements none of the
     *                                       four supported key interfaces
     * @since 0.9.9
     */
    private static String getRawAlgo(Key key) {
        if (key instanceof DSAKey) return "NONEwithDSA";
        if (key instanceof ECKey) return "NONEwithECDSA";
        if (key instanceof EdDSAKey) return "NONEwithEdDSA";
        if (key instanceof RSAKey) return "NONEwithRSA";
        throw new UnsupportedOperationException("Raw signatures unsupported for " + key.getClass().getName());
    }
}

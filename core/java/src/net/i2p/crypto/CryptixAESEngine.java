package net.i2p.crypto;

/*
 * Written by jrandom in 2003 and released into the public domain
 * with no warranty of any kind, either expressed or implied.
 */

import freenet.support.CPUInformation.CPUID;
import freenet.support.CPUInformation.UnknownCPUException;

import net.i2p.I2PAppContext;
import net.i2p.data.DataHelper;
import net.i2p.data.SessionKey;
import net.i2p.util.Log;
import net.i2p.util.SimpleByteCache;
import net.i2p.util.SystemVersion;

import java.security.GeneralSecurityException;
import java.security.InvalidKeyException;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * AES encryption engine using Cryptix's Rijndael implementation with CBC mode.
 * Supports 128-bit block size with 16-byte IV. No padding is provided.
 *
 * @author jrandom,crypto
 */
public final class CryptixAESEngine extends AESEngine {
    private final LinkedBlockingQueue<Cipher> _ciphers;

    private static final int MIN_SYSTEM_AES_LENGTH = 640;
    private static final boolean USE_SYSTEM_AES = hasAESNI() && CryptoCheck.isUnlimited();

    private static final boolean CACHE = true;
    private static final int CACHE_SIZE = 8;
    private static final SecretKeySpec ZERO_KEY = new SecretKeySpec(new byte[32], "AES");
    private static final IvParameterSpec ZERO_IV = new IvParameterSpec(new byte[16], 0, 16);

    /**
     *  Upper bound on the per-session {@link SecretKeySpec} memo cache. Plenty
     *  for the handful of concurrent transport and session keys the router
     *  works with at any moment. Package-visible for the unit tests.
     *
     *  @since 0.9.71+
     */
    static final int KEY_SPEC_CACHE_SIZE = 64;

    /**
     *  Memoized {@link SecretKeySpec} per {@link SessionKey} for the system-AES
     *  path, in access order so hot sessions survive eviction.
     *  <p>
     *  Why memoize at all: building the spec clones the key material, but
     *  {@link SecretKeySpec#getEncoded()} returns the spec's *internal* array,
     *  so a reused instance passes the same byte[] reference to
     *  {@link Cipher#init} and skips JCE makeSessionKey re-expansion of the
     *  256-bit key on every JVM-path encrypt/decrypt.
     *  <p>
     *  This cache is deliberately NOT the same as
     *  {@link SessionKey#preparedKey()}, which is owned by the legacy Cryptix
     *  path and lives per SessionKey - keep the two fast paths decoupled.
     *  {@link SessionKey} equality is value-based, so distinct-but-identical
     *  keys share one spec (their bytes are equal, so sharing is correct).
     *
     *  @since 0.9.71+
     */
    private final Map<SessionKey, SecretKeySpec> _keySpecs = new LinkedHashMap<>(16, 0.75f, true);

    /**
     * Check for AES-NI support in processor and JVM.
     *
     * @return whether a e s n i is present
     * @since 0.9.14
     */
    private static boolean hasAESNI() {
        if (SystemVersion.isX86() && SystemVersion.is64Bit() && SystemVersion.isJava7() && !SystemVersion.isApache() && !SystemVersion.isGNU()) {
            try {
                return CPUID.getInfo().hasAES();
            } catch (UnknownCPUException e) {
                return false;
            }
        } else {
            return false;
        }
    }

    public CryptixAESEngine(I2PAppContext context) {
        super(context);
        _ciphers = USE_SYSTEM_AES ? new LinkedBlockingQueue<>(CACHE_SIZE) : null;
    }

    /**
     *  Encrypt the payload with the session key.
     *
     *  @param iv must be 16 bytes
     *  @param length must be a multiple of 16
     */
    @Override
    public void encrypt(byte[] payload, int payloadIndex, byte[] out, int outIndex, SessionKey sessionKey, byte[] iv, int length) {
        encrypt(payload, payloadIndex, out, outIndex, sessionKey, iv, 0, length);
    }

    /**
     *  Encrypt the payload with the session key.
     *
     *  @param iv must be 16 bytes
     *  @param length must be a multiple of 16
     */
    @Override
    public void encrypt(byte[] payload, int payloadIndex, byte[] out, int outIndex, SessionKey sessionKey, byte[] iv, int ivOffset, int length) {
        if (payload == null) throw new NullPointerException("invalid args to aes - payload");
        if (out == null) throw new NullPointerException("invalid args to aes - out");
        if (sessionKey == null) throw new NullPointerException("invalid args to aes - sessionKey");
        if (iv == null) throw new NullPointerException("invalid args to aes - iv");
        if (payload.length < payloadIndex + length) throw new IllegalArgumentException("Payload is too short");
        if (out.length < outIndex + length) throw new IllegalArgumentException("Output is too short");
        if (length <= 0) throw new IllegalArgumentException("Length is too small");
        if (length % 16 != 0) throw new IllegalArgumentException("Only lengths mod 16 are supported here");

        if (USE_SYSTEM_AES && length >= MIN_SYSTEM_AES_LENGTH) {
            try {
                SecretKeySpec key = getKeySpec(sessionKey);
                IvParameterSpec ivps = new IvParameterSpec(iv, ivOffset, 16);
                Cipher cipher = acquire();
                cipher.init(Cipher.ENCRYPT_MODE, key, ivps, _context.random());
                cipher.doFinal(payload, payloadIndex, length, out, outIndex);
                release(cipher);
                return;
            } catch (GeneralSecurityException gse) {
                if (_log.shouldWarn()) _log.warn("Java encrypt fail", gse);
            }
        }

        DataHelper.xor(iv, ivOffset, payload, payloadIndex, out, outIndex, 16);
        encryptBlock(out, outIndex, sessionKey, out, outIndex);
        for (int x = 16; x < length; x += 16) {
            int off = outIndex + x;
            DataHelper.xor(out, off - 16, payload, payloadIndex + x, out, off, 16);
            encryptBlock(out, off, sessionKey, out, off);
        }
    }

    /**
     *  Decrypt the payload with the session key.
     *
     *  @param iv 16 bytes
     *  @param length must be a multiple of 16 (will overrun to next mod 16 if not)
     */
    @Override
    public void decrypt(byte[] payload, int payloadIndex, byte[] out, int outIndex, SessionKey sessionKey, byte[] iv, int length) {
        decrypt(payload, payloadIndex, out, outIndex, sessionKey, iv, 0, length);
    }

    /**
     *  Decrypt the payload with the session key.
     *
     *  @param iv 16 bytes starting at ivOffset
     *  @param length must be a multiple of 16 (will overrun to next mod 16 if not)
     */
    @Override
    public void decrypt(byte[] payload, int payloadIndex, byte[] out, int outIndex, SessionKey sessionKey, byte[] iv, int ivOffset, int length) {
        if ((iv == null) || (payload == null) || (payload.length <= 0) || (sessionKey == null)) throw new IllegalArgumentException("Bad setup");
        else if (out == null) throw new IllegalArgumentException("Out is null");
        else if (out.length - outIndex < length) throw new IllegalArgumentException("Out is too small (out.length=" + out.length + " outIndex=" + outIndex + " length=" + length);

        if (USE_SYSTEM_AES && length >= MIN_SYSTEM_AES_LENGTH) {
            try {
                SecretKeySpec key = getKeySpec(sessionKey);
                IvParameterSpec ivps = new IvParameterSpec(iv, ivOffset, 16);
                Cipher cipher = acquire();
                cipher.init(Cipher.DECRYPT_MODE, key, ivps, _context.random());
                cipher.doFinal(payload, payloadIndex, length, out, outIndex);
                release(cipher);
                return;
            } catch (GeneralSecurityException gse) {
                if (_log.shouldWarn()) _log.warn("Java decrypt fail", gse);
            }
        }

        int numblock = length / 16;
        if (length % 16 != 0) {
            // may not work, it will overrun payload length and could AIOOBE
            numblock++;
            if (_log.shouldWarn()) _log.warn("Not %16 " + length, new Exception());
        }

        byte[] prev = SimpleByteCache.acquire(16);
        byte[] cur = SimpleByteCache.acquire(16);
        System.arraycopy(iv, ivOffset, prev, 0, 16);

        for (int x = 0; x < numblock; x++) {
            System.arraycopy(payload, payloadIndex, cur, 0, 16);
            decryptBlock(payload, payloadIndex, sessionKey, out, outIndex);
            payloadIndex += 16;
            for (int i = 0; i < 16; i++) {
                out[outIndex++] ^= prev[i];
            }
            iv = prev; // just use IV to switch 'em around
            prev = cur;
            cur = iv;
        }

        /*
        decryptBlock(payload, payloadIndex, sessionKey, out, outIndex);
        DataHelper.xor(out, outIndex, iv, 0, out, outIndex, 16);
        for (int x = 1; x < numblock; x++) {
            decryptBlock(payload, payloadIndex + (x * 16), sessionKey, out, outIndex + (x * 16));
            DataHelper.xor(out, outIndex + x * 16, payload, payloadIndex + (x - 1) * 16, out, outIndex + x * 16, 16);
        }
         */

        SimpleByteCache.release(prev);
        SimpleByteCache.release(cur);
    }

    /** Encrypt exactly 16 bytes using the session key.
     *
     * @param payload plaintext data, 16 bytes starting at inIndex
     * @param sessionKey private session key
     * @param out out parameter, 16 bytes starting at outIndex
     */
    @Override
    public final void encryptBlock(byte[] payload, int inIndex, SessionKey sessionKey, byte[] out, int outIndex) {
        Object pkey = sessionKey.getPreparedKey();
        if (pkey == null) {
            try {
                pkey = CryptixRijndael_Algorithm.makeKey(sessionKey.getData(), 16);
                sessionKey.setPreparedKey(pkey);
            } catch (InvalidKeyException ike) {
                _log.log(Log.CRIT, "Invalid key", ike);
                throw new IllegalArgumentException("invalid key?  " + ike.getMessage());
            }
        }

        CryptixRijndael_Algorithm.blockEncrypt(payload, out, inIndex, outIndex, pkey);
    }

    /** Decrypt exactly 16 bytes of data with the session key provided.
     *
     * @param payload encrypted data, 16 bytes starting at inIndex
     * @param sessionKey private session key
     * @param rv out parameter, 16 bytes starting at outIndex
     */
    @Override
    public final void decryptBlock(byte[] payload, int inIndex, SessionKey sessionKey, byte[] rv, int outIndex) {
        //                                       + " outIndex="+outIndex);
        Object pkey = sessionKey.getPreparedKey();
        if (pkey == null) {
            try {
                pkey = CryptixRijndael_Algorithm.makeKey(sessionKey.getData(), 16);
                sessionKey.setPreparedKey(pkey);
            } catch (InvalidKeyException ike) {
                _log.log(Log.CRIT, "Invalid key", ike);
                throw new IllegalArgumentException("Invalid key?  " + ike.getMessage());
            }
        }

        CryptixRijndael_Algorithm.blockDecrypt(payload, rv, inIndex, outIndex, pkey);
    }

    /**
     *  Get (or build and remember) the {@link SecretKeySpec} for a session
     *  key. Package-visible so the unit tests can verify reuse, longevity
     *  across sessions and the size bound.
     *  <p>
     *  Bounded to {@link #KEY_SPEC_CACHE_SIZE} entries: on overflow the least
     *  recently used spec is evicted. Reached from {@link #encrypt}/
     *  {@link #decrypt}, which per tunnel is effectively single-threaded, so no
     *  contention exists; a lock is held anyway to keep reentry safe.
     *
     *  @param sessionKey the session key (value-equality, may be a fresh object
     *                    each call carrying the same bytes as a cached one)
     *  @return a spec reusing the key's internal byte array; never null
     *  @since 0.9.71+
     */
    SecretKeySpec getKeySpec(SessionKey sessionKey) {
        synchronized (_keySpecs) {
            SecretKeySpec rv = _keySpecs.get(sessionKey);
            if (rv != null)
                return rv;
            rv = new SecretKeySpec(sessionKey.getData(), "AES");
            if (_keySpecs.size() >= KEY_SPEC_CACHE_SIZE) {
                Iterator<Map.Entry<SessionKey, SecretKeySpec>> it = _keySpecs.entrySet().iterator();
                it.next();
                it.remove();
            }
            _keySpecs.put(sessionKey, rv);
            return rv;
        }
    }

    /**
     *  Obtain a Cipher from the cache, or create a new one if empty.
     *
     *  @return cached or new
     *  @since 0.9.49
     */
    private Cipher acquire() {
        Cipher rv = _ciphers.poll();
        if (rv == null) {
            try {
                rv = Cipher.getInstance("AES/CBC/NoPadding");
            } catch (GeneralSecurityException e) {
                throw new UnsupportedOperationException("AES/CBC/NoPadding", e);
            }
        }
        return rv;
    }

    /**
     *  Cipher will be initialized with a zero key and IV.
     *
     *  @since 0.9.49
     */
    private void release(Cipher cipher) {
        if (CACHE) {
            try {
                cipher.init(Cipher.DECRYPT_MODE, ZERO_KEY, ZERO_IV, _context.random());
            } catch (GeneralSecurityException e) {
                return;
            }
            _ciphers.offer(cipher);
        }
    }

    /**
     *  Test results 10K timing runs.
     *  July 2011 eeepc.
     *  Not worth enabling System version.
     *  And we can't get rid of Cryptix because AES-256 is unavailable
     *  in several JVMs.
     *  Make USE_SYSTEM_AES above non-final to run this.
     *  You also must comment out the length check in encrypt() and decrypt() above.
     *
     *<pre>
     *  JVM	Cryptix (ms)	System (ms)
     *  Sun	 8662		n/a
     *  OpenJDK	 8616		  8510
     *  Harmony	14732		 16986
     *  JamVM	50013		761494 (!)
     *  gij	51130		761693 (!)
     *  jrockit	 9780		n/a
     *</pre>
     *
     *  Speed ups with AES-NI:
     *  May 2014 AMD Hexcore 100K runs (1024 bytes):
     *<pre>
     *  JVM		Cryptix (ms)	System (ms)
     *  OpenJDK 6	3314		  5030
     *  OpenJDK 7	3285		  2476
     *</pre>
     *
     *  Cryptix is faster for data smaller than 704 bytes.
     */
}

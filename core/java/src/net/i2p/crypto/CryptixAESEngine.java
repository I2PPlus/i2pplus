package net.i2p.crypto;

/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't  make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import java.security.InvalidKeyException;

// for using system version
import java.util.concurrent.LinkedBlockingQueue;
import java.security.GeneralSecurityException;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import freenet.support.CPUInformation.CPUID;
import freenet.support.CPUInformation.CPUInfo;
import freenet.support.CPUInformation.UnknownCPUException;

import net.i2p.I2PAppContext;
import net.i2p.data.DataHelper;
import net.i2p.data.SessionKey;
import net.i2p.util.Log;
import net.i2p.util.SimpleByteCache;
import net.i2p.util.SystemVersion;

/** 
 * Wrapper for AES cypher operation using Cryptix's Rijndael implementation.  Implements
 * CBC with a 16 byte IV.
 * Problems: 
 * Only supports data of size mod 16 bytes - no inherent padding.
 *
 * @author jrandom, thecrypto
 */
public final class CryptixAESEngine extends AESEngine {
    private final LinkedBlockingQueue<Cipher> _ciphers;

    // keys are now cached in the SessionKey objects
    //private CryptixAESKeyCache _cache;
    
    /** see test results below */
    private static final int MIN_SYSTEM_AES_LENGTH = 640;
    private static final boolean USE_SYSTEM_AES = hasAESNI() && CryptoCheck.isUnlimited();

    private static final boolean CACHE = true;
    private static final int CACHE_SIZE = 8;
    private static final SecretKeySpec ZERO_KEY = new SecretKeySpec(new byte[32], "AES");
    private static final IvParameterSpec ZERO_IV = new IvParameterSpec(new byte[16], 0, 16);

    /**
     *  Do we have AES-NI support in the processor and JVM?
     *  Only on 64-bit x86 Java 7 fast JVMs, with AES-NI support.
     *  See comments in main() below.
     *  @since 0.9.14
     */
    private static boolean hasAESNI() {
        if (SystemVersion.isX86() && SystemVersion.is64Bit() && SystemVersion.isJava7() &&
            !SystemVersion.isApache() && !SystemVersion.isGNU()) {
            try {
                return CPUID.getInfo().hasAES();
            } catch (UnknownCPUException e) {
                return false;
            }
        } else {
            return false;
        }
    }

    /** */
    public CryptixAESEngine(I2PAppContext context) {
        super(context);
        // testing
        //_ciphers = new LinkedBlockingQueue<Cipher>(CACHE_SIZE);
        _ciphers = USE_SYSTEM_AES ? new LinkedBlockingQueue<Cipher>(CACHE_SIZE) : null;
        //_cache = new CryptixAESKeyCache();
    }
    
    /**
     *  @param iv must be 16 bytes
     *  @param length must be a multiple of 16
     */
    @Override
    public void encrypt(byte payload[], int payloadIndex, byte out[], int outIndex, SessionKey sessionKey, byte iv[], int length) {
        encrypt(payload, payloadIndex, out, outIndex, sessionKey, iv, 0, length);
    }
    
    /**
     *  @param iv must be 16 bytes
     *  @param length must be a multiple of 16
     */
    @Override
    public void encrypt(byte payload[], int payloadIndex, byte out[], int outIndex, SessionKey sessionKey, byte iv[], int ivOffset, int length) {
        if (payload == null) 
            throw new NullPointerException("invalid args to aes - payload");
        if (out == null)
            throw new NullPointerException("invalid args to aes - out");
        if (sessionKey == null)
            throw new NullPointerException("invalid args to aes - sessionKey");
        if (iv == null) 
            throw new NullPointerException("invalid args to aes - iv");
        if (payload.length < payloadIndex + length)
            throw new IllegalArgumentException("Payload is too short");
        if (out.length < outIndex + length)
            throw new IllegalArgumentException("Output is too short");
        if (length <= 0) 
            throw new IllegalArgumentException("Length is too small");
        if (length % 16 != 0) 
            throw new IllegalArgumentException("Only lengths mod 16 are supported here");

        if (USE_SYSTEM_AES && length >= MIN_SYSTEM_AES_LENGTH) {
            try {
                SecretKeySpec key = new SecretKeySpec(sessionKey.getData(), "AES");
                IvParameterSpec ivps = new IvParameterSpec(iv, ivOffset, 16);
                Cipher cipher = acquire();
                cipher.init(Cipher.ENCRYPT_MODE, key, ivps, _context.random());
                cipher.doFinal(payload, payloadIndex, length, out, outIndex);
                release(cipher);
                return;
            } catch (GeneralSecurityException gse) {
                if (_log.shouldLog(Log.WARN))
                    _log.warn("Java encrypt fail", gse);
            }
        }

        int numblock = length / 16;
        
        DataHelper.xor(iv, ivOffset, payload, payloadIndex, out, outIndex, 16);
        encryptBlock(out, outIndex, sessionKey, out, outIndex);
        for (int x = 1; x < numblock; x++) {
            DataHelper.xor(out, outIndex + (x-1) * 16, payload, payloadIndex + x * 16, out, outIndex + x * 16, 16);
            encryptBlock(out, outIndex + x * 16, sessionKey, out, outIndex + x * 16);
        }
    }
    
    /**
     *  @param iv 16 bytes
     *  @param length must be a multiple of 16 (will overrun to next mod 16 if not)
     */
    @Override
    public void decrypt(byte payload[], int payloadIndex, byte out[], int outIndex, SessionKey sessionKey, byte iv[], int length) {
        decrypt(payload, payloadIndex, out, outIndex, sessionKey, iv, 0, length);
    }

    /**
     *  @param iv 16 bytes starting at ivOffset
     *  @param length must be a multiple of 16 (will overrun to next mod 16 if not)
     */
    @Override
    public void decrypt(byte payload[], int payloadIndex, byte out[], int outIndex, SessionKey sessionKey, byte iv[], int ivOffset, int length) {
        if ((iv== null) || (payload == null) || (payload.length <= 0) || (sessionKey == null) ) 
            throw new IllegalArgumentException("bad setup");
        else if (out == null)
            throw new IllegalArgumentException("out is null");
        else if (out.length - outIndex < length)
            throw new IllegalArgumentException("out is too small (out.length=" + out.length 
                                               + " outIndex=" + outIndex + " length=" + length);

        if (USE_SYSTEM_AES && length >= MIN_SYSTEM_AES_LENGTH) {
            try {
                SecretKeySpec key = new SecretKeySpec(sessionKey.getData(), "AES");
                IvParameterSpec ivps = new IvParameterSpec(iv, ivOffset, 16);
                Cipher cipher = acquire();
                cipher.init(Cipher.DECRYPT_MODE, key, ivps, _context.random());
                cipher.doFinal(payload, payloadIndex, length, out, outIndex);
                release(cipher);
                return;
            } catch (GeneralSecurityException gse) {
                if (_log.shouldLog(Log.WARN))
                    _log.warn("Java decrypt fail", gse);
            }
        }

        int numblock = length / 16;
        if (length % 16 != 0) {
            // may not work, it will overrun payload length and could AIOOBE
            numblock++;
            if (_log.shouldLog(Log.WARN))
                _log.warn("not %16 " + length, new Exception());
        }

        byte prev[] = SimpleByteCache.acquire(16);
        byte cur[] = SimpleByteCache.acquire(16);
        System.arraycopy(iv, ivOffset, prev, 0, 16);
        
        for (int x = 0; x < numblock; x++) {
            System.arraycopy(payload, payloadIndex, cur, 0, 16);
            decryptBlock(payload, payloadIndex, sessionKey, out, outIndex);
            payloadIndex += 16;
            //DataHelper.xor(out, outIndex + x * 16, prev, 0, out, outIndex + x * 16, 16);
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
    
    /** encrypt exactly 16 bytes using the session key
     * @param payload plaintext data, 16 bytes starting at inIndex
     * @param sessionKey private session key
     * @param out out parameter, 16 bytes starting at outIndex
     */
    @Override
    public final void encryptBlock(byte payload[], int inIndex, SessionKey sessionKey, byte out[], int outIndex) {
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

    /** decrypt exactly 16 bytes of data with the session key provided
     * @param payload encrypted data, 16 bytes starting at inIndex
     * @param sessionKey private session key
     * @param rv out parameter, 16 bytes starting at outIndex
     */
    @Override
    public final void decryptBlock(byte payload[], int inIndex, SessionKey sessionKey, byte rv[], int outIndex) {
        // just let it throw NPE or IAE later for speed, you'll figure it out
        //if ( (payload == null) || (rv == null) )
        //    throw new IllegalArgumentException("null block args");
        //if (payload.length - inIndex > rv.length - outIndex)
        //    throw new IllegalArgumentException("bad block args [payload.len=" + payload.length 
        //                                       + " inIndex=" + inIndex + " rv.len=" + rv.length 
        //                                       + " outIndex="+outIndex);
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

        CryptixRijndael_Algorithm.blockDecrypt(payload, rv, inIndex, outIndex, pkey);
    }
    
    /**
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
     * OpenJDK	 8616		  8510
     * Harmony	14732		 16986
     * JamVM	50013		761494 (!)
     * gij	51130		761693 (!)
     * jrockit	 9780		n/a
     *</pre>
     *
     * Speed ups with AES-NI:
     * May 2014 AMD Hexcore 100K runs (1024 bytes):
     *<pre>
     *  JVM		Cryptix (ms)	System (ms)
     * OpenJDK 6	3314		  5030
     * OpenJDK 7	3285		  2476
     *</pre>
     *
     * Cryptix is faster for data smaller than 704 bytes.
     */
/*******
    public static void main(String args[]) {
        final int MATCH_RUNS = 11000;
        final int TIMING_RUNS = 100000;
        I2PAppContext ctx = I2PAppContext.getGlobalContext();
        try {
            boolean canTestSystem = USE_SYSTEM_AES;
            if (!canTestSystem)
                System.out.println("System AES 256 not available, testing Cryptix only");
            testEDBlock(ctx);
            testEDBlock2(ctx);
            testED2(ctx);
            if (canTestSystem) {
                System.out.println("Start Cryptix vs. System verification run of " + MATCH_RUNS);
                for (int i = 0; i < MATCH_RUNS; i++) {
                    testED(ctx, false, true, 1024);
                    testED(ctx, true, false, 1024);
                }
            }
            for (int len = 512; len <= 1024; len += 32) {
                System.out.println("Start Cryptix run of " + TIMING_RUNS + " length " + len);
                long start = System.currentTimeMillis();
                for (int i = 0; i < TIMING_RUNS; i++) {
                    testED(ctx, false, false, len);
                }
                long cryptix = System.currentTimeMillis() - start;
                System.out.println("Cryptix took " + cryptix);
                if (canTestSystem) {
                    System.out.println("Start System run of " + TIMING_RUNS + " length " + len);
                    start = System.currentTimeMillis();
                    for (int i = 0; i < TIMING_RUNS; i++) {
                        testED(ctx, true, true, len);
                    }
                    long system = System.currentTimeMillis() - start;
                    System.out.println("System took " + system);
                    if (system < cryptix)
                        System.out.println("***System is " + (100 - (system * 100 / cryptix)) + "% better");
                    else
                        System.out.println("***System is " + ((system * 100 / cryptix) - 100) + "% worse");
                }
            }
            //testFake(ctx);
            //testNull(ctx);
        } catch (Exception e) {
            e.printStackTrace();
        }
        //try { Thread.sleep(5*1000); } catch (InterruptedException ie) {}
    }

    private static final byte[] _iv = new byte[16];
    private static byte[] _orig = new byte[1024];
    private static byte[] _encrypted = new byte[1024];
    private static byte[] _decrypted = new byte[1024];

    private static void testED(I2PAppContext ctx, boolean systemEnc, boolean systemDec, int len) {
        SessionKey key = ctx.keyGenerator().generateSessionKey();
        ctx.random().nextBytes(_iv);
        ctx.random().nextBytes(_orig);
        CryptixAESEngine aes = new CryptixAESEngine(ctx);
        USE_SYSTEM_AES = systemEnc;
        aes.encrypt(_orig, 0, _encrypted, 0, key, _iv, len);
        USE_SYSTEM_AES = systemDec;
        aes.decrypt(_encrypted, 0, _decrypted, 0, key, _iv, len);
        if (!DataHelper.eq(_decrypted, 0, _orig, 0, len))
            throw new RuntimeException("full D(E(orig)) != orig");
        //else
        //    System.out.println("full D(E(orig)) == orig");
    }

    // this verifies decryption in-place
    private static void testED2(I2PAppContext ctx) {
        SessionKey key = ctx.keyGenerator().generateSessionKey();
        byte iv[] = new byte[16];
        byte orig[] = new byte[128];
        byte data[] = new byte[128];
        ctx.random().nextBytes(iv);
        ctx.random().nextBytes(orig);
        CryptixAESEngine aes = new CryptixAESEngine(ctx);
        aes.encrypt(orig, 0, data, 0, key, iv, data.length);
        aes.decrypt(data, 0, data, 0, key, iv, data.length);
        if (!DataHelper.eq(data,orig))
            throw new RuntimeException("full D(E(orig)) != orig");
        else
            System.out.println("full D(E(orig)) == orig");
    }

    private static void testFake(I2PAppContext ctx) {
        SessionKey key = ctx.keyGenerator().generateSessionKey();
        SessionKey wrongKey = ctx.keyGenerator().generateSessionKey();
        byte iv[] = new byte[16];
        byte orig[] = new byte[128];
        byte encrypted[] = new byte[128];
        byte decrypted[] = new byte[128];
        ctx.random().nextBytes(iv);
        ctx.random().nextBytes(orig);
        CryptixAESEngine aes = new CryptixAESEngine(ctx);
        aes.encrypt(orig, 0, encrypted, 0, key, iv, orig.length);
        aes.decrypt(encrypted, 0, decrypted, 0, wrongKey, iv, encrypted.length);
        if (DataHelper.eq(decrypted,orig))
            throw new RuntimeException("full D(E(orig)) == orig when we used the wrong key!");
        else
            System.out.println("full D(E(orig)) != orig when we used the wrong key");
    }

    private static void testNull(I2PAppContext ctx) {
        SessionKey key = ctx.keyGenerator().generateSessionKey();
        SessionKey wrongKey = ctx.keyGenerator().generateSessionKey();
        byte iv[] = new byte[16];
        byte orig[] = new byte[128];
        byte encrypted[] = new byte[128];
        byte decrypted[] = new byte[128];
        ctx.random().nextBytes(iv);
        ctx.random().nextBytes(orig);
        CryptixAESEngine aes = new CryptixAESEngine(ctx);
        aes.encrypt(orig, 0, encrypted, 0, key, iv, orig.length);
        try { 
            aes.decrypt(null, 0, null, 0, wrongKey, iv, encrypted.length);
        } catch (IllegalArgumentException iae) {
            return;
        } 
        
        throw new RuntimeException("full D(E(orig)) didn't fail when we used null!");
    }

    private static void testEDBlock(I2PAppContext ctx) {
        SessionKey key = ctx.keyGenerator().generateSessionKey();
        byte iv[] = new byte[16];
        byte orig[] = new byte[16];
        byte encrypted[] = new byte[16];
        byte decrypted[] = new byte[16];
        ctx.random().nextBytes(iv);
        ctx.random().nextBytes(orig);
        CryptixAESEngine aes = new CryptixAESEngine(ctx);
        aes.encryptBlock(orig, 0, key, encrypted, 0);
        aes.decryptBlock(encrypted, 0, key, decrypted, 0);
        if (!DataHelper.eq(decrypted,orig))
            throw new RuntimeException("block D(E(orig)) != orig");
        else
            System.out.println("block D(E(orig)) == orig");
    }

    private static void testEDBlock2(I2PAppContext ctx) {
        SessionKey key = ctx.keyGenerator().generateSessionKey();
        byte iv[] = new byte[16];
        byte orig[] = new byte[16];
        byte data[] = new byte[16];
        ctx.random().nextBytes(iv);
        ctx.random().nextBytes(orig);
        CryptixAESEngine aes = new CryptixAESEngine(ctx);
        aes.encryptBlock(orig, 0, key, data, 0);
        aes.decryptBlock(data, 0, key, data, 0);
        if (!DataHelper.eq(data,orig))
            throw new RuntimeException("block D(E(orig)) != orig");
        else
            System.out.println("block D(E(orig)) == orig");
    }
*******/
}

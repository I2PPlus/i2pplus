package net.i2p.crypto;

/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain
 * with no warranty of any kind, either expressed or implied.
 * It probably won't  make your computer catch on fire, or eat
 * your children, but it might.  Use at your own risk.
 *
 */

import net.i2p.I2PAppContext;
import net.i2p.data.DataHelper;
import net.i2p.data.Hash;
import net.i2p.data.SessionKey;
import net.i2p.util.Log;
import net.i2p.util.SimpleByteCache;

/**
 * Dummy wrapper for AES cipher operation.
 * Warning - most methods UNUSED.
 * See CryptixAESEngine overrides for the real thing.
 */
public class AESEngine {
    protected final Log _log;
    protected final I2PAppContext _context;

    protected AESEngine(I2PAppContext ctx) {
        _context = ctx;
        _log = _context.logManager().getLog(getClass());
        if (getClass().equals(AESEngine.class))
            _log.logAlways(Log.WARN, "AES is disabled");
    }

    /** Encrypt the payload with the session key
     * @param payload data to be encrypted
     * @param payloadIndex index into the payload to start encrypting
     * @param out where to store the result
     * @param outIndex where in out to start writing
     * @param sessionKey private esession key to encrypt to
     * @param iv IV for CBC, must be 16 bytes
     * @param length how much data to encrypt
     */
    public void encrypt(byte payload[], int payloadIndex, byte out[], int outIndex, SessionKey sessionKey, byte iv[], int length) {
        encrypt(payload, payloadIndex, out, outIndex, sessionKey, iv, 0, length);
    }

    /**
     * Encrypt the payload with the session key.
     * This just copies payload to out, see extension for the real thing.
     *
     * @param payload data to be encrypted
     * @param payloadIndex index into the payload to start encrypting
     * @param out where to store the result
     * @param outIndex where in out to start writing
     * @param sessionKey private esession key to encrypt to
     * @param iv IV for CBC
     * @param length how much data to encrypt
     */
    public void encrypt(byte payload[], int payloadIndex, byte out[], int outIndex, SessionKey sessionKey, byte iv[], int ivOffset, int length) {
        System.arraycopy(payload, payloadIndex, out, outIndex, length);
        _log.logAlways(Log.WARN, "AES is disabled");
    }

    /**
     * Encrypt the SHA-256 Hash of the IV, the 4 byte length, and the payload,
     * with random padding up to the paddedSize, rounded up to the next multiple of 16.
     *
     * @param paddedSize minimum size of the output
     * @param iv IV for CBC, must be 16 bytes
     * @return null on error
     * @deprecated unused
     */
    @Deprecated
    public byte[] safeEncrypt(byte payload[], SessionKey sessionKey, byte iv[], int paddedSize) {
        if ((iv == null) || (payload == null) || (sessionKey == null) || (iv.length != 16)) return null;

        int size = Hash.HASH_LENGTH
                 + 4 // sizeof(payload)
                 + payload.length;
        int padding = getPaddingSize(size, paddedSize);

        byte data[] = new byte[size + padding];
        _context.sha().calculateHash(iv, 0, 16, data, 0);
        int cur = Hash.HASH_LENGTH;

        DataHelper.toLong(data, cur, 4, payload.length);
        cur += 4;
        System.arraycopy(payload, 0, data, cur, payload.length);
        cur += payload.length;
        byte paddingData[] = getPadding(_context, size, paddedSize);
        System.arraycopy(paddingData, 0, data, cur, paddingData.length);

        encrypt(data, 0, data, 0, sessionKey, iv, data.length);
        return data;
    }

    /**
     * See safeEncrypt() for description.
     * WARNING - no check for maximum length here, OOM DOS possible, fix it if you're going to use this.
     *
     * @param iv IV for CBC, must be 16 bytes
     * @return null on error
     * @deprecated unused
     */
    @Deprecated
    public byte[] safeDecrypt(byte payload[], SessionKey sessionKey, byte iv[]) {
        if ((iv == null) || (payload == null) || (sessionKey == null) || (iv.length != 16)) return null;

        byte decr[] = new byte[payload.length];
        decrypt(payload, 0, decr, 0, sessionKey, iv, payload.length);

        byte h[] = SimpleByteCache.acquire(Hash.HASH_LENGTH);
        _context.sha().calculateHash(iv, 0, 16, h, 0);
        boolean eq = DataHelper.eq(decr, 0, h, 0, Hash.HASH_LENGTH);
        SimpleByteCache.release(h);
        if (!eq) {
                _log.error("Hash does not match [key=" + sessionKey + " / iv =" + DataHelper.toString(iv, iv.length)
                           + "]", new Exception("Hash error"));
                return null;
        }
        int cur = Hash.HASH_LENGTH;

        long len = DataHelper.fromLong(decr, cur, 4);
        cur += 4;

        if (cur + len > decr.length) {
            _log.error("Not enough to read");
            return null;
        }

        byte data[] = new byte[(int)len];
        System.arraycopy(decr, cur, data, 0, (int)len);
        return data;
    }

    /** Decrypt the data with the session key
     * @param payload data to be decrypted
     * @param payloadIndex index into the payload to start decrypting
     * @param out where to store the cleartext
     * @param outIndex where in out to start writing
     * @param sessionKey private session key to decrypt to
     * @param iv IV for CBC
     * @param length how much data to decrypt
     */
    public void decrypt(byte payload[], int payloadIndex, byte out[], int outIndex, SessionKey sessionKey, byte iv[], int length) {
        decrypt(payload, payloadIndex, out, outIndex, sessionKey, iv, 0, length);
    }

    /**
     * Decrypt the data with the session key.
     * This just copies payload to out, see extension for the real thing.
     *
     * @param payload data to be decrypted
     * @param payloadIndex index into the payload to start decrypting
     * @param out where to store the cleartext
     * @param outIndex where in out to start writing
     * @param sessionKey private session key to decrypt to
     * @param iv IV for CBC
     * @param length how much data to decrypt
     */
    public void decrypt(byte payload[], int payloadIndex, byte out[], int outIndex, SessionKey sessionKey, byte iv[], int ivOffset, int length) {
        System.arraycopy(payload, payloadIndex, out, outIndex, length);
        _log.logAlways(Log.WARN, "AES is disabled");
    }

    /**
     * This just copies payload to out, see extension for the real thing.
     *   @param sessionKey unused
     */
    public void encryptBlock(byte payload[], int inIndex, SessionKey sessionKey, byte out[], int outIndex) {
        System.arraycopy(payload, inIndex, out, outIndex, out.length - outIndex);
    }

    /**
     * This just copies payload to rv, see extension for the real thing.
     *
     * @param payload encrypted data
     * @param sessionKey private session key
     */
    public void decryptBlock(byte payload[], int inIndex, SessionKey sessionKey, byte rv[], int outIndex) {
        System.arraycopy(payload, inIndex, rv, outIndex, rv.length - outIndex);
    }

    /**
     * Return random bytes for padding the data to a mod 16 size so that it is
     * at least minPaddedSize
     *
     * Public for ElGamalAESEngine.
     * Not a public API, not for external use.
     *
     * @since 0.9.38 moved from ElGamalAESEngine
     */
    public final static byte[] getPadding(I2PAppContext context, int curSize, long minPaddedSize) {
        int size = getPaddingSize(curSize, minPaddedSize);
        byte rv[] = new byte[size];
        context.random().nextBytes(rv);
        return rv;
    }

    /**
     * Return size for padding the data to a mod 16 size so that it is
     * at least minPaddedSize
     *
     * Public for ElGamalAESEngine.
     * Not a public API, not for external use.
     *
     * @since 0.9.38 moved from ElGamalAESEngine
     */
    public final static int getPaddingSize(int curSize, long minPaddedSize) {
        int diff = 0;
        if (curSize < minPaddedSize) {
            diff = (int) minPaddedSize - curSize;
        }

        int numPadding = diff;
        if (((curSize + diff) % 16) != 0) numPadding += (16 - ((curSize + diff) % 16));
        return numPadding;
    }


/******
    public static void main(String args[]) {
        I2PAppContext ctx = new I2PAppContext();
        SessionKey key = ctx.keyGenerator().generateSessionKey();
        byte iv[] = new byte[16];
        RandomSource.getInstance().nextBytes(iv);

        byte sbuf[] = new byte[16];
        RandomSource.getInstance().nextBytes(sbuf);
        byte se[] = new byte[16];
        ctx.aes().encrypt(sbuf, 0, se, 0, key, iv, sbuf.length);
        byte sd[] = new byte[16];
        ctx.aes().decrypt(se, 0, sd, 0, key, iv, se.length);
        ctx.logManager().getLog(AESEngine.class).debug("Short test: " + DataHelper.eq(sd, sbuf));

        byte lbuf[] = new byte[1024];
        RandomSource.getInstance().nextBytes(sbuf);
        byte le[] = ctx.aes().safeEncrypt(lbuf, key, iv, 2048);
        byte ld[] = ctx.aes().safeDecrypt(le, key, iv);
        ctx.logManager().getLog(AESEngine.class).debug("Long test: " + DataHelper.eq(ld, lbuf));
    }
******/
}

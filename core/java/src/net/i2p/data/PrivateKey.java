package net.i2p.data;

/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain
 * with no warranty of any kind, either expressed or implied.
 * It probably won't make your computer catch on fire, or eat
 * your children, but it might.  Use at your own risk.
 *
 */

import net.i2p.crypto.EncType;
import net.i2p.crypto.KeyGenerator;
import net.i2p.util.SimpleByteCache;

import java.util.Arrays;

import javax.security.auth.Destroyable;

/**
 * Private (secret) key for the asymmetric encryption of an I2P destination.
 *
 * <p>Type and length:</p>
 * <ul>
 *   <li>The {@link EncType} is fixed at construction and never changes, and
 *       it determines the length: 256 bytes for the default
 *       {@link EncType#ELGAMAL_2048}, 32 for {@link EncType#ECIES_X25519}.
 *       {@link #KEYSIZE_BYTES} is the default type's length only.</li>
 *   <li>For ElGamal the array is the private exponent padded to the key size,
 *       which is why {@link #hashCode()} uses the last 4 bytes - the leading
 *       ones are all zero.</li>
 *   <li>Call {@link #destroy()} when the key is no longer needed. That zeroes
 *       the array and returns it to the byte cache, so after it the key
 *       reports {@link #isDestroyed()} and {@link #length()} still returns the
 *       type's length but the data is gone.</li>
 * </ul>
 *
 * <p>Caching:</p>
 * <ul>
 *   <li>The derived public key is cached in memory: the first
 *       {@link #toPublic()} computes it, later calls return the same
 *       {@link PublicKey} instance. A key built with the
 *       {@link #PrivateKey(EncType, byte[], PublicKey)} constructor starts
 *       with the caller's instance. There is no key cache of any kind here,
 *       and no create() factory method - build the key directly, or read it
 *       from a {@link PrivateKeyFile}.</li>
 * </ul>
 *
 * <p>Thread safety:</p>
 * <ul>
 *   <li>Not thread-safe. The data cannot be reassigned once set, but the
 *       cached public key and the zeroing done by {@link #destroy()} are
 *       unsynchronized writes to plain fields, and {@link #getData()} hands
 *       out the backing array. Share a fully populated key, or pass it
 *       through a synchronized structure, and do not destroy() or write to
 *       the array while another thread may be using it.</li>
 * </ul>
 *
 * @author jrandom
 */
public class PrivateKey extends SimpleDataStructure implements Destroyable {
    private static final EncType DEF_TYPE = EncType.ELGAMAL_2048;
    /** Default key size in bytes (256 for ElGamal). */
    public static final int KEYSIZE_BYTES = DEF_TYPE.getPrivkeyLen();

    private final EncType _type;
    // cache
    private PublicKey _pubKey;

    /**
     *  Constructor for an empty key of the default type, for reading from a
     *  stream. Call readBytes() or fromBase64() to fill it in.
     */
    public PrivateKey() {
        this(DEF_TYPE);
    }

    /**
     *  Constructor with type.
     *
     *  @param type non-null
     *  @since 0.9.38
     */
    public PrivateKey(EncType type) {
        super();
        _type = type;
    }

    /**
     * Private key from raw key data, of the default type.
     *
     * @param data key data, non-null, 256 bytes
     * @throws IllegalArgumentException if data is null or the wrong length
     */
    public PrivateKey(byte[] data) {
        this(DEF_TYPE, data);
    }

    /**
     *  Constructor with type and data.
     *
     *  @param type non-null
     *  @param data must be non-null, and of the type's key length
     *  @throws IllegalArgumentException if data is null or the wrong length,
     *                                  or if type is null
     *  @since 0.9.38
     */
    public PrivateKey(EncType type, byte[] data) {
        this(type);
        if (data == null) throw new IllegalArgumentException("Data must be specified");
        setData(data);
    }

    /**
     *  Constructor with type, data, and cached public key.
     *
     *  @param type non-null
     *  @param data must be non-null, and of the type's key length
     *  @param pubKey corresponding pubKey to be cached, non-null
     *  @throws IllegalArgumentException if data is null or the wrong length,
     *                                  or if pubKey is null or of another type
     *  @since 0.9.44
     */
    public PrivateKey(EncType type, byte[] data, PublicKey pubKey) {
        this(type, data);
        if (type != pubKey.getType()) throw new IllegalArgumentException("Pubkey mismatch");
        _pubKey = pubKey;
    }

    /**
     * Private key of the default type from a string of base64 data.
     *
     * @param base64Data a string of base64 data (the output of .toBase64() called
     * on a prior instance of PrivateKey)
     * @throws DataFormatException if the data is not valid
     */
    public PrivateKey(String base64Data) throws DataFormatException {
        this(DEF_TYPE);
        fromBase64(base64Data);
    }

    /** Key length in bytes, determined by the encryption type. */
    @Override
    public int length() {
        return _type.getPrivkeyLen();
    }

    /**
     *  Encryption type of this private key.
     *
     *  @return non-null
     *  @since 0.9.38
     */
    public EncType getType() {
        return _type;
    }

    /**
     * Derives the PublicKey corresponding to the secret contents of this
     * PrivateKey. As of 0.9.44 the result is cached, so every call after the
     * first returns the same instance.
     *
     * @return a PublicKey object
     * @throws IllegalArgumentException on bad key
     */
    public PublicKey toPublic() {
        if (_pubKey == null) _pubKey = KeyGenerator.getPublicKey(this);
        return _pubKey;
    }

    /**
     *  Destroy this key and clear its data, per the javax.security.auth.Destroyable
     *  interface. The data is zeroed and returned to the byte cache, the cached
     *  public key is dropped, and the key reports isDestroyed() afterwards.
     *  Calling this more than once is harmless.
     *
     *  @since 0.9.40
     */
    @Override
    public void destroy() {
        byte[] data = _data;
        if (data != null) {
            _data = null;
            Arrays.fill(data, (byte) 0);
            SimpleByteCache.release(data);
        }
        _pubKey = null;
    }

    /**
     * Whether this key has been destroyed.
     *
     * @return whether destroyed
     * @since 0.9.40
     */
    @Override
    public boolean isDestroyed() {
        return _data == null;
    }

    /**
     *  The type and either the base64 data (keys of 32 bytes or less) or just
     *  the size, so the secret is not printed. Prints "null" for the data of
     *  a destroyed key.
     *
     *  @return a string representation
     *  @since 0.9.38
     */
    @Override
    public String toString() {
        StringBuilder buf = new StringBuilder(64);
        buf.append("[PrivateKey ").append(_type).append(' ');
        if (_data == null) {
            buf.append("null");
        } else {
            int length = length();
            if (length <= 32) buf.append(toBase64());
            else buf.append("Size: ").append(length);
        }
        buf.append(']');
        return buf.toString();
    }

    /**
     * We assume the data has enough randomness in it, so use the last 4 bytes
     * for speed. Overridden since we use short exponents, so the first 227
     * bytes are all zero. Non-default types hash the whole array instead.
     *
     * @return the hash code
     */
    @Override
    public int hashCode() {
        if (_data == null) return 0;
        if (_type != DEF_TYPE) return DataHelper.hashCode(_data);
        int rv = _data[KEYSIZE_BYTES - 4];
        for (int i = 1; i < 4; i++) rv ^= (_data[i + (KEYSIZE_BYTES - 4)] << (i * 8));
        return rv;
    }

    /**
     * Two private keys are equal when their types and data are equal. A key
     * and a destroyed copy of it are not equal, since the data differs.
     *
     * @param obj the object to compare
     * @return true if the types and data match
     */
    @Override
    public boolean equals(Object obj) {
        if (obj == this) return true;
        if ((obj == null) || !(obj instanceof PrivateKey)) return false;
        PrivateKey p = (PrivateKey) obj;
        return _type == p._type && Arrays.equals(_data, p._data);
    }
}

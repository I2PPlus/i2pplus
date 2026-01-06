package net.i2p.data;

/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain
 * with no warranty of any kind, either expressed or implied.
 * It probably won't make your computer catch on fire, or eat
 * your children, but it might.  Use at your own risk.
 *
 */

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import net.i2p.crypto.EncType;

/**
 * Cryptographic public key for asymmetric encryption in I2P.
 * 
 * <p>PublicKey provides the encryption component of I2P's asymmetric cryptography:</p>
 * <ul>
 *   <li><strong>Default Algorithm:</strong> ElGamal 2048-bit (256 bytes)</li>
 *   <li><strong>Modern Support:</strong> Variable length and type support since 0.9.38</li>
 *   <li><strong>Key Structure:</strong> Contains only public exponent</li>
 *   <li><strong>Constants:</strong> Prime numbers defined in crypto specification</li>
 * </ul>
 * 
 * <p><strong>Supported Algorithms:</strong></p>
 * <ul>
 *   <li><strong>ElGamal 2048:</strong> Legacy algorithm, 256-byte keys</li>
 *   <li><strong>ECIES X25519:</strong> Modern elliptic curve, 32-byte keys</li>
 *   <li><strong>Unknown Types:</strong> Forward-compatible support for future algorithms</li>
 * </ul>
 * 
 * <p><strong>Key Format:</strong></p>
 * <ul>
 *   <li><strong>ElGamal:</strong> 256-byte public exponent only</li>
 *   <li><strong>Elliptic Curve:</strong> Variable length based on curve parameters</li>
 *   <li><strong>Type Encoding:</strong> Algorithm type stored with key data</li>
 *   <li><strong>Validation:</strong> Length and format checking per algorithm</li>
 * </ul>
 * 
 * <p><strong>Usage:</strong></p>
 * <ul>
 *   <li><strong>Encryption:</strong> Encrypt messages for corresponding {@link PrivateKey}</li>
 *   <li><strong>Key Exchange:</strong> Participate in ElGamal key exchange</li>
 *   <li><strong>Identity:</strong> Part of {@link Destination} cryptographic identity</li>
 *   <li><strong>Verification:</strong> Public key validation and distribution</li>
 * </ul>
 * 
 * <p><strong>Performance Features:</strong></p>
 * <ul>
 *   <li><strong>LRU Caching:</strong> Frequently used keys cached for efficiency</li>
 *   <li><strong>Factory Methods:</strong> Static creation methods for cache access</li>
 *   <li><strong>Efficient Storage:</strong> Optimized byte representation</li>
 *   <li><strong>Fast Comparison:</strong> Optimized equals() and hashCode()</li>
 * </ul>
 * 
 * <p><strong>Security Considerations:</strong></p>
 * <ul>
 *   <li><strong>Algorithm Choice:</strong> Prefer modern algorithms (X25519)</li>
 *   <li><strong>Key Validation:</strong> Verify key parameters before use</li>
 *   <li><strong>Key Distribution:</strong> Safely transmit public keys</li>
 *   <li><strong>Size Limits:</strong> Currently limited to 256 bytes maximum</li>
 * </ul>
 * 
 * <p><strong>Migration Path:</strong></p>
 * <ul>
 *   <li><strong>Legacy:</strong> ElGamal 2048-bit for backward compatibility</li>
 *   <li><strong>Modern:</strong> ECIES X25519 for better performance</li>
 *   <li><strong>Future:</strong> Extensible design for new algorithms</li>
 * </ul>
 * 
 * <p><strong>Implementation Notes:</strong></p>
 * <ul>
 *   <li><strong>Size Limitation:</strong> Support for keys >256 bytes not yet implemented</li>
 *   <li><strong>Cache Index:</strong> First 4 bytes used for LRU cache lookup</li>
 *   <li><strong>Type Safety:</strong> Algorithm type validation on creation</li>
 * </ul>
 * 
 * <p><strong>Thread Safety:</strong></p>
 * <ul>
 *   <li><strong>Immutable Data:</strong> Key data cannot be modified after creation</li>
 *   <li><strong>Thread-Safe Cache:</strong> Static factory methods are thread-safe</li>
 *   <li><strong>Safe Sharing:</strong> Instances can be safely shared between threads</li>
 * </ul>
 *
 * @author jrandom
 */
public class PublicKey extends SimpleDataStructure {
    private static final EncType DEF_TYPE = EncType.ELGAMAL_2048;
    public final static int KEYSIZE_BYTES = DEF_TYPE.getPubkeyLen();
    private static final int CACHE_SIZE = 1024;
    private static final SDSCache<PublicKey> _cache = new SDSCache<PublicKey>(PublicKey.class, KEYSIZE_BYTES, CACHE_SIZE);
    private final EncType _type;
    private final int _unknownTypeCode;

    /**
     * Pull from cache or return new.
     * ELGAMAL_2048 only!
     * Deprecated - used only by deprecated Destination.readBytes(data, off)
     *
     * @throws DataFormatException if not enough bytes
     * @since 0.8.3
     */
    public static PublicKey create(byte[] data, int off) {return _cache.get(data, off);}

    /**
     * Pull from cache or return new.
     * ELGAMAL_2048 only!
     * @since 0.8.3
     */
    public static PublicKey create(InputStream in) throws IOException {return _cache.get(in);}

    public PublicKey() {this(DEF_TYPE);}

    /**
     *  @param type if null, type is unknown
     *  @since 0.9.38
     */
    public PublicKey(EncType type) {
        super();
        _type = type;
        _unknownTypeCode = (type != null) ? type.getCode() : -1;
    }

    /** @param data must be non-null */
    public PublicKey(byte data[]) {this(DEF_TYPE, data);}

    /**
     *  @param type if null, type is unknown
     *  @param data must be non-null
     *  @since 0.9.38
     */
    public PublicKey(EncType type, byte data[]) {
        this(type);
        if (data == null) {throw new IllegalArgumentException("Data must be specified");}
        setData(data);
    }

    /**
     *  Unknown type only.
     *  @param typeCode must not match a known type. 1-255
     *  @param data must be non-null
     *  @since 0.9.38
     */
    public PublicKey(int typeCode, byte data[]) {
        _type = null;
        if (data == null) {throw new IllegalArgumentException("Data must be specified");}
        _data = data;
        if (typeCode <= 0 || typeCode > 255) {throw new IllegalArgumentException();}
        _unknownTypeCode = typeCode;
    }

    /**
     * Constructs from base64. ElGamal only.
     * @param base64Data a string of base64 data (the output of .toBase64() called
     * on a prior instance of PublicKey
     */
    public PublicKey(String base64Data)  throws DataFormatException {
        this(DEF_TYPE);
        fromBase64(base64Data);
    }

    @Override
    public int length() {
        if (_type != null) {return _type.getPubkeyLen();}
        if (_data != null) {return _data.length;}
        return KEYSIZE_BYTES;
    }

    /**
      *  Gets the encryption type of this public key.
      *
      *  @return null if unknown
      *  @since 0.9.38
      */
    public EncType getType() {return _type;}

    /**
      *  Gets the type code for unknown encryption types.
      *
      *  Only valid if getType() returns null
      *  @since 0.9.38
      */
    public int getUnknownTypeCode() {return _unknownTypeCode;}

    /**
     *  Up-convert this from an untyped (type 0) PK to a typed PK based on the Key Cert given.
     *  The type of the returned key will be null if the kcert sigtype is null.
     *
     *  @throws IllegalArgumentException if this is already typed to a different type
     *  @since 0.9.42
     */
    PublicKey toTypedKey(KeyCertificate kcert) {
        if (_data == null) {throw new IllegalStateException();}
        EncType newType = kcert.getEncType();
        if (_type == newType) {return this;}
        if (_type != EncType.ELGAMAL_2048) {throw new IllegalArgumentException("Cannot convert " + _type + " to " + newType);}
        if (newType == null) {return new PublicKey(null, _data);} // unknown type, keep the 256 bytes of data
        int newLen = newType.getPubkeyLen();
        if (newLen == KEYSIZE_BYTES) {return new PublicKey(newType, _data);}
        byte[] newData = new byte[newLen];
        if (newLen < KEYSIZE_BYTES) {System.arraycopy(_data, 0, newData, 0, newLen);} // LEFT justified, padding at end
        else {throw new IllegalArgumentException("TODO");} // full 256 bytes + fragment in kcert
        return new PublicKey(newType, newData);
    }

    /**
     *  Get the portion of this (type 0) PK that is really padding based on the Key Cert type given,
     *  if any
     *
     *  @return trailing padding length &gt; 0 or null if no padding or type is unknown
     *  @throws IllegalArgumentException if this is already typed to a different type
     *  @since 0.9.42
     */
    public byte[] getPadding(KeyCertificate kcert) {
        if (_data == null) {throw new IllegalStateException();}
        EncType newType = kcert.getEncType();
        if (_type == newType || newType == null) {return null;}
        if (_type != EncType.ELGAMAL_2048) {throw new IllegalStateException("Cannot convert " + _type + " to " + newType);}
        int newLen = newType.getPubkeyLen();
        if (newLen >= KEYSIZE_BYTES) {return null;}
        int padLen = KEYSIZE_BYTES - newLen;
        byte[] pad = new byte[padLen];
        System.arraycopy(_data, _data.length - padLen, pad, 0, padLen);
        return pad;
    }

    /**
      *  Clears the public key cache.
      *
      *  @since 0.9.17
      */
    public static void clearCache() {_cache.clear();}

    /**
     *  @since 0.9.38
     */
    @Override
    public String toString() {
        StringBuilder buf = new StringBuilder(64);
        buf.append((_type != null) ? "[" + _type.toString() + "]" : "[Unknown type: " + _unknownTypeCode).append("] -> ");
        if (_data != null) {
            int length = length();
            if (length <= 32) {buf.append("[").append(toBase64()).append("]");}
            else {buf.append("Size: ").append(length).append(" bytes");}
        }
        return buf.toString();
    }

    /**
     *  @since 0.9.42
     */
    @Override
    public int hashCode() {return DataHelper.hashCode(_type) ^ super.hashCode();}

    /**
     *  @since 0.9.42
     */
    @Override
    public boolean equals(Object obj) {
        if (obj == this) {return true;}
        if (obj == null || !(obj instanceof PublicKey)) {return false;}
        PublicKey s = (PublicKey) obj;
        return _type == s._type && Arrays.equals(_data, s._data);
    }
}

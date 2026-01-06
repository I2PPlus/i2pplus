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
import java.io.OutputStream;
import java.util.Arrays;
import net.i2p.crypto.Blinding;
import net.i2p.crypto.SigType;

/**
 * Cryptographic public key for digital signature verification in I2P.
 * 
 * <p>SigningPublicKey provides signature verification capabilities:</p>
 * <ul>
 *   <li><strong>Default Algorithm:</strong> DSA-SHA1 (128 bytes)</li>
 *   <li><strong>Modern Support:</strong> Variable length and type support since 0.9.8</li>
 *   <li><strong>Key Structure:</strong> Contains only public exponent/coordinates</li>
 *   <li><strong>Verification:</strong> Used to verify signatures and identities</li>
 * </ul>
 * 
 * <p><strong>Supported Algorithms:</strong></p>
 * <ul>
 *   <li><strong>DSA-SHA1:</strong> Legacy algorithm, 128-byte keys</li>
 *   <li><strong>ECDSA-P256:</strong> Modern algorithm, variable length keys</li>
 *   <li><strong>EdDSA-Ed25519:</strong> Modern algorithm, 32-byte keys</li>
 *   <li><strong>Future Types:</strong> Extensible design for new algorithms</li>
 * </ul>
 * 
 * <p><strong>Key Format:</strong></p>
 * <ul>
 *   <li><strong>DSA:</strong> 128-byte public parameters (p, q, g, y)</li>
 *   <li><strong>ECDSA:</strong> Variable length elliptic curve coordinates</li>
 *   <li><strong>EdDSA:</strong> 32-byte compressed curve point</li>
 *   <li><strong>Type Encoding:</strong> Algorithm type embedded in data</li>
 * </ul>
 * 
 * <p><strong>Usage:</strong></p>
 * <ul>
 *   <li><strong>Signature Verification:</strong> Verify signatures from {@link SigningPrivateKey}</li>
 *   <li><strong>Identity Verification:</strong> Part of {@link Destination} identity</li>
 *   <li><strong>LeaseSet Verification:</strong> Verify LeaseSet authenticity</li>
 *   <li><strong>Router Identity:</strong> Verify router signatures in NetDb</li>
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
 *   <li><strong>Algorithm Choice:</strong> Prefer modern algorithms (Ed25519, ECDSA-P256)</li>
 *   <li><strong>Key Validation:</strong> Verify key parameters before use</li>
 *   <li><strong>Signature Verification:</strong> Always verify with correct algorithm</li>
 *   <li><strong>Key Distribution:</strong> Safely transmit public keys</li>
 * </ul>
 * 
 * <p><strong>Blinding Support:</strong></p>
 * <ul>
 *   <li><strong>Key Blinding:</strong> Support for blinded key variants</li>
 *   <li><strong>Privacy:</strong> Enable anonymous service endpoints</li>
 *   <li><strong>BlindData:</strong> Integration with {@link BlindData} for blinding</li>
 * </ul>
 * 
 * <p><strong>Migration Path:</strong></p>
 * <ul>
 *   <li><strong>Legacy:</strong> DSA-SHA1 for backward compatibility</li>
 *   <li><strong>Modern:</strong> Ed25519 for better performance and security</li>
 *   <li><strong>Transition:</strong> Mixed algorithm support during migration</li>
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
public class SigningPublicKey extends SimpleDataStructure {
    private static final SigType DEF_TYPE = SigType.DSA_SHA1;
    public final static int KEYSIZE_BYTES = DEF_TYPE.getPubkeyLen();
    private static final int CACHE_SIZE = 1024;

    private static final SDSCache<SigningPublicKey> _cache = new SDSCache<SigningPublicKey>(SigningPublicKey.class, KEYSIZE_BYTES, CACHE_SIZE);

    private final SigType _type;

    /**
     * Pull from cache or return new.
     * Deprecated - used only by deprecated Destination.readBytes(data, off)
     *
     * @throws DataFormatException if not enough bytes
     * @since 0.8.3
     */
    public static SigningPublicKey create(byte[] data, int off) {return _cache.get(data, off);}

    /**
     * Pull from cache or return new
     * @since 0.8.3
     */
    public static SigningPublicKey create(InputStream in) throws IOException {return _cache.get(in);}

    public SigningPublicKey() {this(DEF_TYPE);}

    /**
     *  @param type if null, type is unknown
     *  @since 0.9.8
     */
    public SigningPublicKey(SigType type) {super(); _type = type;}

    public SigningPublicKey(byte data[]) {this(DEF_TYPE, data);}

    /**
     *  @param type if null, type is unknown
     *  @since 0.9.8
     */
    public SigningPublicKey(SigType type, byte data[]) {
        super();
        _type = type;
        if (type != null || data == null) {setData(data);}
        else {_data = data;}  // bypass length check
    }

    /** constructs from base64
     * @param base64Data a string of base64 data (the output of .toBase64() called
     * on a prior instance of SigningPublicKey
     */
    public SigningPublicKey(String base64Data)  throws DataFormatException {
        this();
        fromBase64(base64Data);
    }

    /**
      *  @return if type unknown, the length of the data, or 128 if no data
      */
    @Override
    public int length() {
        if (_type != null) {return _type.getPubkeyLen();}
        if (_data != null) {return _data.length;}
        return KEYSIZE_BYTES;
    }

    /**
      *  Gets the signature type of this public key.
      *
      *  @return null if unknown
      *  @since 0.9.8
      */
    public SigType getType() {return _type;}

    /**
      *  Up-convert this from an untyped (type 0) SPK to a typed SPK based on the Key Cert given.
     *  The type of the returned key will be null if the kcert sigtype is null.
     *
     *  @throws IllegalArgumentException if this is already typed to a different type
     *  @since 0.9.12 (changed from public to package private in 0.9.66, not for external use)

     */
    SigningPublicKey toTypedKey(KeyCertificate kcert) {
        if (_data == null) {throw new IllegalStateException();}
        SigType newType = kcert.getSigType();
        if (_type == newType) {return this;}
        if (_type != SigType.DSA_SHA1) {throw new IllegalArgumentException("Cannot convert " + _type + " to " + newType);}
        if (newType == null) {return new SigningPublicKey(null, _data);} // unknown type, keep the 128 bytes of data
        int newLen = newType.getPubkeyLen();
        int ctype = kcert.getCryptoTypeCode();
        if (ctype == 0) {
            int sz = 7;
            if (newLen > KEYSIZE_BYTES) {sz += newLen - KEYSIZE_BYTES;}
            // prohibit excess key data - TODO non-zero crypto type if added
            if (kcert.size() != sz) {throw new IllegalArgumentException("Excess data in key certificate");}
        }
        if (newLen == KEYSIZE_BYTES) {return new SigningPublicKey(newType, _data);}
        byte[] newData = new byte[newLen];
        if (newLen < KEYSIZE_BYTES) {
            System.arraycopy(_data, _data.length - newLen, newData, 0, newLen); // right-justified
        } else {
            // full 128 bytes + fragment in kcert
            System.arraycopy(_data, 0, newData, 0, _data.length);
            System.arraycopy(kcert.getPayload(), KeyCertificate.HEADER_LENGTH, newData, _data.length, newLen - _data.length);
        }
        return new SigningPublicKey(newType, newData);
    }

    /**
     *  Get the portion of this (type 0) SPK that is really padding based on the Key Cert type given,
     *  if any
     *
     *  @return leading padding length &gt; 0 or null if no padding or type is unknown
     *  @throws IllegalArgumentException if this is already typed to a different type
     *  @since 0.9.12
     */
    public byte[] getPadding(KeyCertificate kcert) {
        if (_data == null) {throw new IllegalStateException();}
        SigType newType = kcert.getSigType();
        if (_type == newType || newType == null) {return null;}
        if (_type != SigType.DSA_SHA1) {throw new IllegalStateException("Cannot convert " + _type + " to " + newType);}
        int newLen = newType.getPubkeyLen();
        if (newLen >= KEYSIZE_BYTES) {return null;}
        int padLen = KEYSIZE_BYTES - newLen;
        byte[] pad = new byte[padLen];
        System.arraycopy(_data, 0, pad, 0, padLen);
        return pad;
    }

    /**
     *  Write the data up to a max of 128 bytes.
     *  If longer, the rest will be written in the KeyCertificate.
     *  @since 0.9.12 (changed from public to package private in 0.9.66, not for external use)
     */
    void writeTruncatedBytes(OutputStream out) throws DataFormatException, IOException {
        // we don't use _type here so we can write the data even for unknown type
        //if (_type.getPubkeyLen() <= KEYSIZE_BYTES)
        if (_data == null) throw new DataFormatException("No data to write out");
        if (_data.length <= KEYSIZE_BYTES) {out.write(_data);}
        else {out.write(_data, 0, KEYSIZE_BYTES);}
    }

    /**
     *  Only for SigType EdDSA_SHA512_Ed25519
     *
     *  @param alpha the secret data
     *  @throws UnsupportedOperationException unless supported
     *  @since 0.9.38
     */
    public SigningPublicKey blind(SigningPrivateKey alpha) {
        return Blinding.blind(this, alpha);
    }

    /**
     *  @since 0.9.8
     */
    @Override
    public String toString() {
        StringBuilder buf = new StringBuilder(64);
        buf.append("SigningPublicKey ").append((_type != null) ? _type.toString() : "unknown type").append(' ');
        int length = length();
        if (_data == null) {buf.append("null");}
        else if (length <= 32) {buf.append(toBase64().substring(0,6));}
        else {buf.append("Size: ").append(length).append(" bytes");}
        return buf.toString();
    }

    /**
      *  Clears the public key cache.
      *
      *  @since 0.9.17
      */
    public static void clearCache() {_cache.clear();}

    /**
     *  @since 0.9.17
     */
    @Override
    public int hashCode() {return DataHelper.hashCode(_type) ^ super.hashCode();}

    /**
     *  @since 0.9.17
     */
    @Override
    public boolean equals(Object obj) {
        if (obj == this) {return true;}
        if (obj == null || !(obj instanceof SigningPublicKey)) {return false;}
        SigningPublicKey s = (SigningPublicKey) obj;
        return _type == s._type && Arrays.equals(_data, s._data);
    }

}

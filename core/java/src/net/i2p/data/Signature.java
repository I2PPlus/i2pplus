package net.i2p.data;

/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain
 * with no warranty of any kind, either expressed or implied.
 * It probably won't make your computer catch on fire, or eat
 * your children, but it might.  Use at your own risk.
 *
 */

import java.util.Arrays;
import net.i2p.crypto.SigType;

/**
 * Cryptographic signature implementation for I2P data structures and identity verification.
 * 
 * <p>Signature provides digital signature capabilities with support for multiple algorithms:</p>
 * <ul>
 *   <li><strong>Default Algorithm:</strong> DSA-SHA1 (40 bytes: 20-byte R + 20-byte S)</li>
 *   <li><strong>Modern Algorithms:</strong> ECDSA-P256, EdDSA-Ed25519 with variable lengths</li>
 *   <li><strong>Algorithm Support:</strong> Extensible design for future signature types</li>
 *   <li><strong>Verification:</strong> Used throughout I2P for identity and data integrity</li>
 * </ul>
 * 
 * <p><strong>Signature Structure:</strong></p>
 * <ul>
 *   <li><strong>DSA-SHA1:</strong> 40 bytes total (R: 20 bytes, S: 20 bytes)</li>
 *   <li><strong>ECDSA-P256:</strong> Variable length (typically 64-72 bytes)</li>
 *   <li><strong>EdDSA-Ed25519:</strong> 64 bytes (fixed length)</li>
 *   <li><strong>Type Information:</strong> Embedded in signature data for verification</li>
 * </ul>
 * 
 * <p><strong>Supported Algorithms:</strong></p>
 * <ul>
 *   <li><strong>DSA-SHA1:</strong> Legacy algorithm, 1024-bit keys</li>
 *   <li><strong>ECDSA-P256:</strong> Modern algorithm, 256-bit elliptic curve</li>
 *   <li><strong>EdDSA-Ed25519:</strong> Modern algorithm, 25519 elliptic curve</li>
 *   <li><strong>RSA:</strong> Supported but discouraged due to performance issues</li>
 * </ul>
 * 
 * <p><strong>Usage:</strong></p>
 * <ul>
 *   <li><strong>Identity Verification:</strong> Proves ownership of {@link Destination}</li>
 *   <li><strong>Data Integrity:</strong> Ensures data hasn't been tampered with</li>
 *   <li><strong>NetDb Entries:</strong> Signs RouterInfo and LeaseSet structures</li>
 *   <li><strong>Messages:</strong> Authenticates I2NP messages and I2CP communications</li>
 * </ul>
 * 
 * <p><strong>Security Considerations:</strong></p>
 * <ul>
 *   <li><strong>Algorithm Selection:</strong> Prefer modern algorithms (Ed25519, ECDSA-P256)</li>
 *   <li><strong>Key Management:</strong> Protect private signing keys</li>
 *   <li><strong>Verification:</strong> Always verify signatures before trusting data</li>
 *   <li><strong>Performance:</strong> RSA signatures are slow and may be used for DoS</li>
 * </ul>
 * 
 * <p><strong>Evolution:</strong></p>
 * <ul>
 *   <li><strong>Pre-0.9.8:</strong> Only DSA-SHA1 supported (40 bytes fixed)</li>
 *   <li><strong>Post-0.9.8:</strong> Arbitrary length and type support via {@link SigType}</li>
 *   <li><strong>Current:</strong> Multiple algorithms with automatic type detection</li>
 * </ul>
 *
 * @author jrandom
 */
public class Signature extends SimpleDataStructure {
    private static final SigType DEF_TYPE = SigType.DSA_SHA1;
    /** 40 */
    public final static int SIGNATURE_BYTES = DEF_TYPE.getSigLen();
    private final SigType _type;
    public Signature() {this(DEF_TYPE);}

    /**
     *  Unknown type not allowed as we won't know the length to read in the data.
     *
     *  @param type non-null
     *  @since 0.9.8
     */
    public Signature(SigType type) {
        super();
        if (type == null) {throw new IllegalArgumentException("Unknown type");}
        _type = type;
    }

    public Signature(byte data[]) {this(DEF_TYPE, data);}

    /**
     *  Should we allow an unknown type here?
     *
     *  @param type non-null
     *  @since 0.9.8
     */
    public Signature(SigType type, byte data[]) {
        super();
        if (type == null) {throw new IllegalArgumentException("Unknown type");}
        _type = type;
        setData(data);
    }

    public int length() {return _type.getSigLen();}

    /**
      *  Gets the signature type.
      *
      *  @return non-null
      *  @since 0.9.8
      */
    public SigType getType() {return _type;}

    /**
     *  @since 0.9.8
     */
    @Override
    public String toString() {
        StringBuilder buf = new StringBuilder(64);
        buf.append(_type).append(" (");
        int length = length();
        if (_data == null) {buf.append("null");}
        else if (length <= 32) {buf.append(toBase64());}
        else {buf.append("Size: ").append(Integer.toString(length)).append(" bytes)");}
        return buf.toString();
    }

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
        if (obj == this) return true;
        if (obj == null || !(obj instanceof Signature)) return false;
        Signature s = (Signature) obj;
        return _type == s._type && Arrays.equals(_data, s._data);
    }
}

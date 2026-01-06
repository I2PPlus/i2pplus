package net.i2p.data;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import net.i2p.crypto.EncType;
import net.i2p.crypto.SigType;

/**
 * Specialized certificate for defining cryptographic key types used in I2P identities.
 *
 * <p>KeyCertificate specifies the encryption and signature algorithms for I2P identities:</p>
 * <ul>
 *   <li><strong>Signature Type:</strong> Algorithm used for signing (2 bytes)</li>
 *   <li><strong>Encryption Type:</strong> Algorithm used for encryption (2 bytes)</li>
 *   <li><strong>Excess Data:</strong> Additional key-specific data if needed</li>
 *   <li><strong>Optimized:</strong> Frequently used, so has dedicated class for performance</li>
 * </ul>
 *
 * <p><strong>Format Structure:</strong></p>
 * <ul>
 *   <li><strong>Header:</strong> 4 bytes total (2-byte sig type + 2-byte crypto type)</li>
 *   <li><strong>Signature Data:</strong> Optional excess data for signature algorithm</li>
 *   <li><strong>Encryption Data:</strong> Optional excess data for encryption algorithm</li>
 * </ul>
 *
 * <p><strong>Supported Combinations:</strong></p>
 * <ul>
 *   <li><strong>ElGamal + DSA-SHA1:</strong> Legacy combination (crypto type 0x0000)</li>
 *   <li><strong>ElGamal + Ed25519:</strong> Modern signing with legacy encryption</li>
 *   <li><strong>ElGamal + ECDSA-P256:</strong> Modern signing with legacy encryption</li>
 *   <li><strong>X25519 + Ed25519:</strong> Modern combination (both algorithms)</li>
 * </ul>
 *
 * <p><strong>Predefined Certificates:</strong></p>
 * <ul>
 *   <li><strong>Ed25519:</strong> ElGamal + Ed25519 signing key</li>
 *   <li><strong>ECDSA256:</strong> ElGamal + ECDSA-P256 signing key</li>
 *   <li><strong>X25519_Ed25519:</strong> X25519 encryption + Ed25519 signing</li>
 * </ul>
 *
 * <p><strong>Usage:</strong></p>
 * <ul>
 *   <li><strong>Identity Specification:</strong> Defines algorithms for {@link Destination}</li>
 *   <li><strong>Algorithm Negotiation:</strong> Communicates supported crypto to peers</li>
 *   <li><strong>Backward Compatibility:</strong> Supports legacy and modern algorithms</li>
 *   <li><strong>Future Proofing:</strong> Extensible for new algorithm combinations</li>
 * </ul>
 *
 * <p><strong>Migration Path:</strong></p>
 * <ul>
 *   <li><strong>Legacy:</strong> ElGamal encryption (assumed 0x0000) with various signing</li>
 *   <li><strong>Modern:</strong> X25519 encryption with Ed25519 signing</li>
 *   <li><strong>Transition:</strong> Mixed combinations during migration period</li>
 * </ul>
 *
 * <p><strong>Performance Considerations:</strong></p>
 * <ul>
 *   <li><strong>Frequently Used:</strong> Every Destination contains a KeyCertificate</li>
 *   <li><strong>Optimized Creation:</strong> Pre-defined certificates for common combinations</li>
 *   <li><strong>Caching:</strong> Immutable instances can be safely shared</li>
 * </ul>
 *
 * @since 0.9.12
 */
public class KeyCertificate extends Certificate {

    public static final int HEADER_LENGTH = 4;

    /**
     * ElG + Ed25519
     *
     * @since 0.9.22 pkg private for Certificate.create()
     */
    static final byte[] Ed25519_PAYLOAD = new byte[] {
        0, (byte) SigType.EdDSA_SHA512_Ed25519.getCode(), 0, 0
    };

    /**
      * ElG + P256
      *
      * @since 0.9.22 pkg private for Certificate.create()
      */
    static final byte[] ECDSA256_PAYLOAD = new byte[] {
        0, (byte) SigType.ECDSA_SHA256_P256.getCode(), 0, 0
    };

    /**
      * X25519 + Ed25519
      *
      * @since 0.9.54
      */
    static final byte[] X25519_Ed25519_PAYLOAD = new byte[] {
        0, (byte) SigType.EdDSA_SHA512_Ed25519.getCode(), 0, (byte) EncType.ECIES_X25519.getCode()
    };

    /**
     *  An immutable ElG/ECDSA-P256 certificate.
     */
    public static final KeyCertificate ELG_ECDSA256_CERT;

    /**
     *  An immutable ElG/Ed25519 certificate.
     *  @since 0.9.22
     */
    public static final KeyCertificate ELG_Ed25519_CERT;

    /**
     *  An immutable X25519/Ed25519 certificate.
     *  @since 0.9.54
     */
    public static final KeyCertificate X25519_Ed25519_CERT;

    static {
        KeyCertificate kc;
        try {
            kc = new ECDSA256Cert();
        } catch (DataFormatException dfe) {
            throw new RuntimeException(dfe);  // won't happen
        }
        ELG_ECDSA256_CERT = kc;
        try {
            kc = new Ed25519Cert();
        } catch (DataFormatException dfe) {
            throw new RuntimeException(dfe);  // won't happen
        }
        ELG_Ed25519_CERT = kc;
        try {
            kc = new X25519_Ed25519Cert();
        } catch (DataFormatException dfe) {
            throw new RuntimeException(dfe);  // won't happen
        }
        X25519_Ed25519_CERT = kc;
    }

    /**
      *  @param payload 4 bytes minimum if non-null
      *  @throws DataFormatException if payload is too short
      */
    public KeyCertificate(byte[] payload) throws DataFormatException {
         super(CERTIFICATE_TYPE_KEY, payload);
         if (payload != null && payload.length < HEADER_LENGTH)
             throw new DataFormatException("data");
    }

    /**
     *  A KeyCertificate with crypto type 0 (ElGamal)
     *  and the signature type and extra data from the given public key.
     *
     *  @param spk non-null data non-null
     *  @throws IllegalArgumentException if spk or spk data is null
     */
    public KeyCertificate(SigningPublicKey spk) {
         super(CERTIFICATE_TYPE_KEY, null);
         if (spk == null || spk.getData() == null)
             throw new IllegalArgumentException();
         SigType type = spk.getType();
         int len = type.getPubkeyLen();
         int extra = Math.max(0, len - 128);
         _payload = new byte[HEADER_LENGTH + extra];
         int code = type.getCode();
         _payload[0] = (byte) (code >> 8);
         _payload[1] = (byte) (code & 0xff);
         // 2 and 3 always 0, it is the only crypto code for now
         if (extra > 0)
             System.arraycopy(spk.getData(), 128, _payload, HEADER_LENGTH, extra);
    }

    /**
     *  A KeyCertificate with enc type from the given public key,
     *  and the signature type and extra data from the given public key.
     *  EncType lengths greater than 256 not supported.
     *
     *  @param spk non-null data non-null
     *  @param pk non-null
     *  @throws IllegalArgumentException if spk, pk, or their data is null
     *  @since 0.9.42
     */
    public KeyCertificate(SigningPublicKey spk, PublicKey pk) {
         super(CERTIFICATE_TYPE_KEY, null);
         if (spk == null || spk.getData() == null ||
             pk == null || pk.getData() == null)
             throw new IllegalArgumentException();
         SigType type = spk.getType();
         int len = type.getPubkeyLen();
         int extra = Math.max(0, len - 128);
         _payload = new byte[HEADER_LENGTH + extra];
         int code = type.getCode();
         _payload[0] = (byte) (code >> 8);
         _payload[1] = (byte) (code & 0xff);
         code = pk.getType().getCode();
         _payload[2] = (byte) (code >> 8);
         _payload[3] = (byte) (code & 0xff);
         if (extra > 0)
             System.arraycopy(spk.getData(), 128, _payload, HEADER_LENGTH, extra);
    }

    /**
     *  A KeyCertificate with crypto type 0 (ElGamal)
     *  and the signature type as specified.
     *  Payload is created.
     *  If type.getPubkeyLen() is greater than 128, caller MUST
     *  fill in the extra key data in the payload.
     *
     *  @param type non-null
     *  @throws IllegalArgumentException if type is null
     */
     public KeyCertificate(SigType type) {
        this(type, EncType.ELGAMAL_2048);
    }

    /**
     *  A KeyCertificate with crypto type
     *  and the signature type as specified.
     *  Payload is created.
     *  If type.getPubkeyLen() is greater than 128, caller MUST
     *  fill in the extra key data in the payload.
     *  EncType lengths greater than 256 not supported.
     *
     *  @param type non-null
     *  @param etype non-null
     *  @throws IllegalArgumentException if type or etype is null
     *  @since 0.9.42
     */
    public KeyCertificate(SigType type, EncType etype) {
         super(CERTIFICATE_TYPE_KEY, null);
         int len = type.getPubkeyLen();
         int extra = Math.max(0, len - 128);
         _payload = new byte[HEADER_LENGTH + extra];
         int code = type.getCode();
         _payload[0] = (byte) (code >> 8);
         _payload[1] = (byte) (code & 0xff);
         code = etype.getCode();
         _payload[2] = (byte) (code >> 8);
         _payload[3] = (byte) (code & 0xff);
    }

    /**
     *  Up-convert a cert to this class
     *
     *  @param cert payload 4 bytes minimum if non-null
     *  @throws DataFormatException if cert type != CERTIFICATE_TYPE_KEY
     */
    public KeyCertificate(Certificate cert) throws DataFormatException {
        this(cert.getPayload());
        if (cert.getCertificateType() != CERTIFICATE_TYPE_KEY)
            throw new DataFormatException("type");
    }

    /**
      *  Gets the signature type code from the certificate.
      *
      *  @return -1 if unset
      */
    public int getSigTypeCode() {
        if (_payload == null)
            return -1;
        return ((_payload[0] & 0xff) << 8) | (_payload[1] & 0xff);
    }

    /**
      *  Gets the crypto type code from the certificate.
      *
      *  @return -1 if unset
      */
    public int getCryptoTypeCode() {
        if (_payload == null)
            return -1;
        return ((_payload[2] & 0xff) << 8) | (_payload[3] & 0xff);
    }

    /**
      *  Gets the signature type from the certificate.
      *
      *  @return null if unset or unknown
      */
    public SigType getSigType() {
        return SigType.getByCode(getSigTypeCode());
    }

    /**
      *  Gets the encryption type from the certificate.
      *
      *  @return null if unset or unknown
      *  @since 0.9.42
      */
    public EncType getEncType() {
        return EncType.getByCode(getCryptoTypeCode());
    }

    /**
     *  Signing Key extra data, if any, is first in the array.
     *  Crypto Key extra data, if any, is second in the array,
     *  at offset max(0, getSigType().getPubkeyLen() - 128)
     *
     *  @return null if unset or none
     */
    public byte[] getExtraKeyData() {
        if (_payload == null || _payload.length <= HEADER_LENGTH)
            return null;
        byte[] rv = new byte[_payload.length - HEADER_LENGTH];
        System.arraycopy(_payload, HEADER_LENGTH, rv, 0, rv.length);
        return rv;
    }


    /**
     *  Signing Key extra data, if any.
     *
     *  @return null if unset or none
     *  @throws UnsupportedOperationException if the sig type is unsupported
     */
    public byte[] getExtraSigningKeyData() {
        // we assume no crypto key data
        if (_payload == null || _payload.length <= HEADER_LENGTH)
            return null;
        SigType type = getSigType();
        if (type == null)
            throw new UnsupportedOperationException("Unknown signature type");
        int extra = Math.max(0, type.getPubkeyLen() - 128);
        if (_payload.length == HEADER_LENGTH + extra)
            return getExtraKeyData();
        byte[] rv = new byte[extra];
        System.arraycopy(_payload, HEADER_LENGTH, rv, 0, extra);
        return rv;
    }

    // todo
    // constructor w/ crypto type
    // getCryptoType()
    // getCryptoDataOffset()

    @Override
    public KeyCertificate toKeyCertificate() {
        return this;
    }

    @Override
    public String toString() {
        StringBuilder buf = new StringBuilder(64);
        buf.append("Key");
        if (_payload == null) {
            buf.append(" null payload");
        } else {
            buf.append("\n* Crypto type: ").append(getCryptoTypeCode())
               .append(" (").append(getEncType()).append(')')
               .append("\n* Sig type: ").append(getSigTypeCode())
               .append(" (").append(getSigType()).append(')');
            if (_payload.length > HEADER_LENGTH)
                buf.append("\n* Key data: ").append(_payload.length - HEADER_LENGTH).append(" bytes");
        }
        return buf.toString();
    }

    /**
     *  An immutable ElG/ECDSA-256 certificate.
     */
    private static final class ECDSA256Cert extends KeyCertificate {
        private static final byte[] ECDSA256_DATA = new byte[] {
            CERTIFICATE_TYPE_KEY, 0, HEADER_LENGTH, 0, (byte) SigType.ECDSA_SHA256_P256.getCode(), 0, 0
        };
        private static final int ECDSA256_LENGTH = ECDSA256_DATA.length;
        private final int _hashcode;

        public ECDSA256Cert() throws DataFormatException {
            super(ECDSA256_PAYLOAD);
            _hashcode = super.hashCode();
        }

        /** @throws RuntimeException always */
        @Override
        public void setCertificateType(int type) {
            throw new RuntimeException("Data already set");
        }

        /** @throws RuntimeException always */
        @Override
        public void setPayload(byte[] payload) {
            throw new RuntimeException("Data already set");
        }

        /** @throws RuntimeException always */
        @Override
        public void readBytes(InputStream in) throws DataFormatException, IOException {
            throw new RuntimeException("Data already set");
        }

        /** Overridden for efficiency */
        @Override
        public void writeBytes(OutputStream out) throws IOException {
            out.write(ECDSA256_DATA);
        }

        /** Overridden for efficiency */
        @Override
        public int writeBytes(byte target[], int offset) {
            System.arraycopy(ECDSA256_DATA, 0, target, offset, ECDSA256_LENGTH);
            return ECDSA256_LENGTH;
        }

        /** @throws RuntimeException always */
        @Override
        public int readBytes(byte source[], int offset) throws DataFormatException {
            throw new RuntimeException("Data already set");
        }

        /** Overridden for efficiency */
        @Override
        public int size() {
            return ECDSA256_LENGTH;
        }

        /** Overridden for efficiency */
        @Override
        public int hashCode() {
            return _hashcode;
        }
    }

    /**
     *  An immutable ElG/Ed25519 certificate.
     *  @since 0.9.22
     */
    private static final class Ed25519Cert extends KeyCertificate {
        private static final byte[] ED_DATA = new byte[] { CERTIFICATE_TYPE_KEY,
                                                           0, HEADER_LENGTH,
                                                           0, (byte) SigType.EdDSA_SHA512_Ed25519.getCode(),
                                                           0, 0
        };
        private static final int ED_LENGTH = ED_DATA.length;
        private final int _hashcode;

        public Ed25519Cert() throws DataFormatException {
            super(Ed25519_PAYLOAD);
            _hashcode = super.hashCode();
        }

        /** @throws RuntimeException always */
        @Override
        public void setCertificateType(int type) {
            throw new RuntimeException("Data already set");
        }

        /** @throws RuntimeException always */
        @Override
        public void setPayload(byte[] payload) {
            throw new RuntimeException("Data already set");
        }

        /** @throws RuntimeException always */
        @Override
        public void readBytes(InputStream in) throws DataFormatException, IOException {
            throw new RuntimeException("Data already set");
        }

        /** Overridden for efficiency */
        @Override
        public void writeBytes(OutputStream out) throws IOException {
            out.write(ED_DATA);
        }

        /** Overridden for efficiency */
        @Override
        public int writeBytes(byte target[], int offset) {
            System.arraycopy(ED_DATA, 0, target, offset, ED_LENGTH);
            return ED_LENGTH;
        }

        /** @throws RuntimeException always */
        @Override
        public int readBytes(byte source[], int offset) throws DataFormatException {
            throw new RuntimeException("Data already set");
        }

        /** Overridden for efficiency */
        @Override
        public int size() {
            return ED_LENGTH;
        }

        /** Overridden for efficiency */
        @Override
        public int hashCode() {
            return _hashcode;
        }
    }

    /**
     *  An immutable X25519/Ed25519 certificate.
     *  @since 0.9.54
     */
    private static final class X25519_Ed25519Cert extends KeyCertificate {
        private static final byte[] ED_DATA = new byte[] { CERTIFICATE_TYPE_KEY,
                                                           0, HEADER_LENGTH,
                                                           0, (byte) SigType.EdDSA_SHA512_Ed25519.getCode(),
                                                           0, (byte) EncType.ECIES_X25519.getCode()
        };
        private static final int ED_LENGTH = ED_DATA.length;
        private final int _hashcode;

        public X25519_Ed25519Cert() throws DataFormatException {
            super(X25519_Ed25519_PAYLOAD);
            _hashcode = super.hashCode();
        }

        /** @throws RuntimeException always */
        @Override
        public void setCertificateType(int type) {
            throw new RuntimeException("Data already set");
        }

        /** @throws RuntimeException always */
        @Override
        public void setPayload(byte[] payload) {
            throw new RuntimeException("Data already set");
        }

        /** @throws RuntimeException always */
        @Override
        public void readBytes(InputStream in) throws DataFormatException, IOException {
            throw new RuntimeException("Data already set");
        }

        /** Overridden for efficiency */
        @Override
        public void writeBytes(OutputStream out) throws IOException {
            out.write(ED_DATA);
        }

        /** Overridden for efficiency */
        @Override
        public int writeBytes(byte target[], int offset) {
            System.arraycopy(ED_DATA, 0, target, offset, ED_LENGTH);
            return ED_LENGTH;
        }

        /** @throws RuntimeException always */
        @Override
        public int readBytes(byte source[], int offset) throws DataFormatException {
            throw new RuntimeException("Data already set");
        }

        /** Overridden for efficiency */
        @Override
        public int size() {
            return ED_LENGTH;
        }

        /** Overridden for efficiency */
        @Override
        public int hashCode() {
            return _hashcode;
        }
    }
}

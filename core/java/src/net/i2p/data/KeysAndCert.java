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
import net.i2p.crypto.EncType;
import net.i2p.crypto.SHA256Generator;
import net.i2p.crypto.SigType;
import net.i2p.util.ByteArrayStream;
import net.i2p.I2PAppContext;
import net.i2p.util.Log;

/**
 * Container for cryptographic keys and certificate as used by I2P identities.
 * 
 * <p>KeysAndCert provides the fundamental cryptographic identity components:</p>
 * <ul>
 *   <li><strong>Public Key:</strong> Encryption key for encrypted communication</li>
 *   <li><strong>Signing Public Key:</strong> Key for verifying signatures and identity</li>
 *   <li><strong>Certificate:</strong> Optional metadata about the keys and identity</li>
 *   <li><strong>Cached Hash:</strong> Pre-computed SHA-256 hash for efficient identification</li>
 * </ul>
 * 
 * <p><strong>Structure:</strong></p>
 * <ul>
 *   <li>Public key (variable length, typically 256 bytes for ElGamal)</li>
 *   <li>Signing public key (variable length, typically 128 bytes for DSA, 32 bytes for Ed25519)</li>
 *   <li>Certificate (type + length + payload)</li>
 *   <li>Optional padding for consistent serialization</li>
 * </ul>
 * 
 * <p><strong>Usage:</strong></p>
 * <ul>
 *   <li><strong>Base Class:</strong> Extended by {@link Destination} and {@link net.i2p.data.router.RouterIdentity}</li>
 *   <li><strong>Identity:</strong> Forms the core of I2P cryptographic identities</li>
 *   <li><strong>Verification:</strong> Provides keys for signature verification</li>
 *   <li><strong>Encryption:</strong> Contains encryption key for secure communication</li>
 * </ul>
 * 
 * <p><strong>Key Types:</strong></p>
 * <ul>
 *   <li><strong>Encryption:</strong> ElGamal 2048-bit (legacy) or ECIES X25519 (modern)</li>
 *   <li><strong>Signing:</strong> DSA-SHA1 (legacy), ECDSA-P256, or EdDSA-Ed25519 (modern)</li>
 *   <li><strong>Certificates:</strong> NULL, HIDDEN, SIGNED, MULTIPLE, or KEY types</li>
 * </ul>
 * 
 * <p><strong>Immutability:</strong></p>
 * <ul>
 *   <li>As of 0.9.9, instances are immutable after keys and certificate are set</li>
 *   <li>Attempts to modify will throw {@link IllegalStateException}</li>
 *   <li>Ensures thread safety and prevents accidental corruption</li>
 * </ul>
 * 
 * <p><strong>History:</strong></p>
 * <ul>
 *   <li>Implemented in 0.8.2 and retrofitted over existing Destination and RouterIdentity classes</li>
 *   <li>No functional difference between Destination and RouterIdentity at this level</li>
 *   <li>Provides unified interface for identity management across I2P</li>
 * </ul>
 *
 * @since 0.8.2
 * @author zzz
 */
public class KeysAndCert extends DataStructureImpl {
    protected PublicKey _publicKey;
    protected SigningPublicKey _signingKey;
    protected Certificate _certificate;
    private Hash __calculatedHash;
    // if compressed, 32 bytes only
    private byte[] _padding;
    /**
     *  If compressed, the padding size / 32, else 0
     *  @since 0.9.62
     */
    protected int _paddingBlocks;

    private static final int PAD_COMP_LEN = 32;
    private static final Log _log = I2PAppContext.getGlobalContext().logManager().getLog(KeysAndCert.class);

    public Certificate getCertificate() {
        return _certificate;
    }

    /**
      *  Sets the certificate.
      *
      * @throws IllegalStateException if was already set
      */
    public void setCertificate(Certificate cert) {
        if (_certificate != null)
            throw new IllegalStateException();
        _certificate = cert;
    }

    /**
      *  Gets the signature type from the certificate.
      *
      *  @return null if not set or unknown
      *  @since 0.9.17
      */
    public SigType getSigType() {
        if (_certificate == null)
            return null;
        if (_certificate.getCertificateType() == Certificate.CERTIFICATE_TYPE_KEY) {
            try {
                KeyCertificate kcert = _certificate.toKeyCertificate();
                return kcert.getSigType();
            } catch (DataFormatException dfe) {
                // invalid certificate format, fall through to default
            }
        }
        return SigType.DSA_SHA1;
    }

    /**
      *  Gets the encryption type from the certificate.
      *
      *  @return null if not set or unknown
      *  @since 0.9.42
      */
    public EncType getEncType() {
        if (_certificate == null)
            return null;
        if (_certificate.getCertificateType() == Certificate.CERTIFICATE_TYPE_KEY) {
            try {
                KeyCertificate kcert = _certificate.toKeyCertificate();
                return kcert.getEncType();
            } catch (DataFormatException dfe) {
                // invalid certificate format, fall through to default
            }
        }
        return EncType.ELGAMAL_2048;
    }

    /**
     *  Valid for RouterIdentities. May contain random padding for Destinations.
     *  @since 0.9.42
     */
    public PublicKey getPublicKey() {
        return _publicKey;
    }

    /**
      *  Sets the public key.
      *
      * @throws IllegalStateException if was already set
      */
    public void setPublicKey(PublicKey key) {
        if (_publicKey != null)
            throw new IllegalStateException();
        _publicKey = key;
    }

    public SigningPublicKey getSigningPublicKey() {
        return _signingKey;
    }

    /**
      *  Sets the signing public key.
      *
      * @throws IllegalStateException if was already set
      */
    public void setSigningPublicKey(SigningPublicKey key) {
        if (_signingKey != null)
            throw new IllegalStateException();
        _signingKey = key;
    }

    /**
      *  Gets the padding bytes.
      *
      * @since 0.9.16
      */
    public byte[] getPadding() {
        if (_paddingBlocks <= 1) {return _padding;}
        byte[] rv = new byte[PAD_COMP_LEN * _paddingBlocks];
        for (int i = 0; i <_paddingBlocks; i++) {
            System.arraycopy(_padding, 0, rv, i * PAD_COMP_LEN, PAD_COMP_LEN);
        }
        return rv;
    }

    /**
      *  Sets the padding bytes.
      *
      * @throws IllegalStateException if was already set
      * @since 0.9.12
      */
    public void setPadding(byte[] padding) {
        if (_padding != null) {throw new IllegalStateException();}
        _padding = padding;
        compressPadding();
    }

    /**
     * Is there compressible padding?
     * @since 0.9.66
     */
    public boolean isCompressible() {
        return _paddingBlocks > 1;
    }

    /**
      * @throws IllegalStateException if data already set
      */
    @Override
    public void readBytes(InputStream in) throws DataFormatException, IOException {
        if (_publicKey != null || _signingKey != null || _certificate != null)
            throw new IllegalStateException();
        PublicKey pk = PublicKey.create(in);
        SigningPublicKey spk = SigningPublicKey.create(in);
        Certificate cert = Certificate.create(in);
        if (cert.getCertificateType() == Certificate.CERTIFICATE_TYPE_KEY) {
            // convert PK and SPK to new PK and SPK and padding
            KeyCertificate kcert = cert.toKeyCertificate();
            _publicKey = pk.toTypedKey(kcert);
            _signingKey = spk.toTypedKey(kcert);
            byte[] pad1 = pk.getPadding(kcert);
            byte[] pad2 = spk.getPadding(kcert);
            _padding = combinePadding(pad1, pad2);
            compressPadding();
            _certificate = kcert;
        } else {
            _publicKey = pk;
            _signingKey = spk;
            _certificate = cert;
        }
    }

    /**
      *  Combines two padding arrays.
      *
      * @return null if both are null
      * @since 0.9.42
      */
    protected static byte[] combinePadding(byte[] pad1, byte[] pad2) {
        if (pad1 == null)
            return pad2;
        if (pad2 == null)
            return pad1;
        byte[] rv = new byte[pad1.length + pad2.length];
        System.arraycopy(pad1, 0, rv, 0, pad1.length);
        System.arraycopy(pad2, 0, rv, pad1.length, pad2.length);
        return rv;
    }

    /**
     * This only does the padding, does not compress the unused 256 byte LS public key.
     * Savings is 288 bytes for RI and 64 bytes for LS.
     * @since 0.9.62
     */
    private void compressPadding() {
        _paddingBlocks = 0;
        // > 32 and a mult. of 32
        if (_padding == null || _padding.length <= 32 || (_padding.length & (PAD_COMP_LEN - 1)) != 0) {
            return;
        }
        int blks = _padding.length / PAD_COMP_LEN;
        for (int i = 1; i < blks; i++) {
            if (!DataHelper.eq(_padding, 0, _padding, i * PAD_COMP_LEN, PAD_COMP_LEN)) {return;}
        }
        byte[] comp = new byte[PAD_COMP_LEN];
        System.arraycopy(_padding, 0, comp, 0, PAD_COMP_LEN);
        _padding = comp;
        _paddingBlocks = blks;
    }

    /**
     * For Destination.writeBytes()
     * @return the new offset
     * @since 0.9.62
     */
   protected int writePaddingBytes(byte[] target, int off) {
        if (_padding == null)
            return off;
        if (_paddingBlocks > 1) {
            for (int i = 0; i < _paddingBlocks; i++) {
                System.arraycopy(_padding, 0, target, off, _padding.length);
                off += PAD_COMP_LEN;
           }
        } else {
            System.arraycopy(_padding, 0, target, off, _padding.length);
            off += _padding.length;
        }
        return off;
    }

    @Override
    public void writeBytes(OutputStream out) throws DataFormatException, IOException {
        if ((_certificate == null) || (_publicKey == null) || (_signingKey == null))
            throw new DataFormatException("Not enough data to format the router identity");
        _publicKey.writeBytes(out);
        if (_padding != null) {
            if (_paddingBlocks <= 1) {
                out.write(_padding);
            } else {
                for (int i = 0; i <_paddingBlocks; i++) {
                    out.write(_padding, 0, PAD_COMP_LEN);
                }
            }
        } else if (_signingKey.length() < SigningPublicKey.KEYSIZE_BYTES ||
                   _publicKey.length() < PublicKey.KEYSIZE_BYTES) {
            throw new DataFormatException("No padding set");
        }
        _signingKey.writeTruncatedBytes(out);
        _certificate.writeBytes(out);
    }

    @Override
    public boolean equals(Object object) {
        if (object == this) return true;
        if ((object == null) || !(object instanceof KeysAndCert)) return false;
        KeysAndCert  ident = (KeysAndCert) object;
        return
               DataHelper.eq(_signingKey, ident._signingKey)
               && DataHelper.eq(_publicKey, ident._publicKey)
               && DataHelper.eq(_certificate, ident._certificate)
               && (Arrays.equals(_padding, ident._padding) ||
                   // failsafe as some code paths may not compress padding
                   ((_paddingBlocks > 1 || ident._paddingBlocks > 1) &&
                    Arrays.equals(getPadding(), ident.getPadding())));
    }

    /** the signing key has enough randomness in it to use it by itself for speed */
    @Override
    public int hashCode() {
        // don't use public key, some app devs thinking of using
        // an all-zeros or leading-zeros public key for destinations
        if (_signingKey == null) {
            return 0;
        }
        return _signingKey.hashCode();
    }

    @Override
    public String toString() {
        StringBuilder buf = new StringBuilder(256);
        String cls = getClass().getSimpleName();
        if (cls.equals("RouterIdentity")) {cls = "Router";}
        buf.append(cls);
        buf.append(" [");
        if (cls.equals("Destination")) {
            buf.append(getHash().toBase32().substring(0,8));
        } else {
            buf.append(getHash().toBase64().substring(0,6));
        }
        buf.append("]");

        if (_log.shouldInfo()) {
            buf.append("\n* Certificate: ").append(_certificate);
            if ((_publicKey != null && _publicKey.getType() != EncType.ELGAMAL_2048) ||
                !cls.equals("Destination")) {
                buf.append("\n* Public Key: ").append(_publicKey); // router identities only
            }
            buf.append("\n* Public Signing Key: ").append(_signingKey);
            if (_padding != null) {
                int len = _padding.length;
                if (_paddingBlocks > 1) {
                    len *= _paddingBlocks;
                }
                buf.append("\n* Padding: ").append(len).append(" bytes");
            }
        }

        return buf.toString();
    }

    /**
      *  Throws IllegalStateException if keys and cert are not initialized,
      *  as of 0.9.12. Prior to that, returned null.
      *
      *  @throws IllegalStateException if keys and cert are not initialized
      */
     @Override
    public Hash calculateHash() {
        return getHash();
    }

    /**
      *  Throws IllegalStateException if keys and cert are not initialized,
      *  as of 0.9.12. Prior to that, returned null.
      *
      *  @throws IllegalStateException if keys and cert are not initialized
      */
    public Hash getHash() {
        if (__calculatedHash != null)
            return __calculatedHash;
        byte identBytes[];
        try {
            if (_certificate == null)
                throw new IllegalStateException("KAC hash error");
            ByteArrayStream baos = new ByteArrayStream(384 + _certificate.size());
            writeBytes(baos);
            identBytes = baos.toByteArray();
        } catch (IOException ioe) {
            throw new IllegalStateException("KAC hash error", ioe);
        } catch (DataFormatException dfe) {
            throw new IllegalStateException("KAC hash error", dfe);
        }
        __calculatedHash = SHA256Generator.getInstance().calculateHash(identBytes);
        return __calculatedHash;
    }
}

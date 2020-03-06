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

/**
 * Defines a certificate that can be attached to various I2P structures, such
 * as RouterIdentity and Destination, allowing routers and clients to help
 * manage denial of service attacks and the network utilization.  Certificates
 * can even be defined to include identifiable information signed by some
 * certificate authority, though that use probably isn't appropriate for an
 * anonymous network ;)
 *
 * Todo: Properly support multiple certificates
 *
 * @author jrandom
 */
public class Certificate extends DataStructureImpl {
    public final static Certificate NULL_CERT = new NullCert();

    protected int _type;
    protected byte[] _payload;

    /** Specifies a null certificate type with no payload */
    public final static int CERTIFICATE_TYPE_NULL = 0;
    /** specifies a Hashcash style certificate */
    public final static int CERTIFICATE_TYPE_HASHCASH = 1;
    /** we should not be used for anything (don't use us in the netDb, in tunnels, or tell others about us) */
    public final static int CERTIFICATE_TYPE_HIDDEN = 2;
    /** Signed with 40-byte Signature and (optional) 32-byte hash */
    public final static int CERTIFICATE_TYPE_SIGNED = 3;
    public final static int CERTIFICATE_LENGTH_SIGNED_WITH_HASH = Signature.SIGNATURE_BYTES + Hash.HASH_LENGTH;
    /** Contains multiple certs */
    public final static int CERTIFICATE_TYPE_MULTIPLE = 4;
    /** @since 0.9.12 */
    public final static int CERTIFICATE_TYPE_KEY = 5;

    /**
     * If null, P256 key, or Ed25519 key cert, return immutable static instance, else create new
     * @throws DataFormatException if not enough bytes
     * @since 0.8.3
     */
    public static Certificate create(byte[] data, int off) throws DataFormatException {
    	int type;
    	byte[] payload;
        int length;
    	try {
            type = data[off] & 0xff;
            length = (int) DataHelper.fromLong(data, off + 1, 2);
            if (type == 0 && length == 0)
                return NULL_CERT;
            // from here down roughly the same as readBytes() below
            if (length == 0)
                return new Certificate(type, null);
            payload = new byte[length];
            System.arraycopy(data, off + 3, payload, 0, length);
    	} catch (ArrayIndexOutOfBoundsException aioobe) {
    		throw new DataFormatException("not enough bytes", aioobe);
    	}
        if (type == CERTIFICATE_TYPE_KEY) {
            if (length == 4) {
                if (Arrays.equals(payload, KeyCertificate.Ed25519_PAYLOAD))
                    return KeyCertificate.ELG_Ed25519_CERT;
                if (Arrays.equals(payload, KeyCertificate.ECDSA256_PAYLOAD))
                    return KeyCertificate.ELG_ECDSA256_CERT;
            }
            try {
                return new KeyCertificate(payload);
            } catch (DataFormatException dfe) {
                throw new IllegalArgumentException(dfe);
            }
        }
        return new Certificate(type, payload);
    }

    /**
     * If null, P256 key, or Ed25519 key cert, return immutable static instance, else create new
     * @since 0.8.3
     */
    public static Certificate create(InputStream in) throws DataFormatException, IOException {
        // EOF will be thrown in next read
        int type = in.read();
        int length = (int) DataHelper.readLong(in, 2);
        if (type == 0 && length == 0)
            return NULL_CERT;
        // from here down roughly the same as readBytes() below
        if (length == 0)
            return new Certificate(type, null);
        byte[] payload = new byte[length];
        int read = DataHelper.read(in, payload);
        if (read != length)
            throw new DataFormatException("Not enough bytes for the payload (read: " + read + " length: " + length + ')');
        if (type == CERTIFICATE_TYPE_KEY) {
            if (length == 4) {
                if (Arrays.equals(payload, KeyCertificate.Ed25519_PAYLOAD))
                    return KeyCertificate.ELG_Ed25519_CERT;
                if (Arrays.equals(payload, KeyCertificate.ECDSA256_PAYLOAD))
                    return KeyCertificate.ELG_ECDSA256_CERT;
            }
            return new KeyCertificate(payload);
        }
        return new Certificate(type, payload);
    }

    public Certificate() {
    }

    /**
     *  @throws IllegalArgumentException if type &lt; 0
     */
    public Certificate(int type, byte[] payload) {
        if (type < 0)
            throw new IllegalArgumentException();
        _type = type;
        _payload = payload;
    }

    /** */
    public int getCertificateType() {
        return _type;
    }

    /**
     *  @throws IllegalArgumentException if type &lt; 0
     *  @throws IllegalStateException if already set
     */
    public void setCertificateType(int type) {
        if (type < 0)
            throw new IllegalArgumentException();
        if (_type != 0 && _type != type)
            throw new IllegalStateException("already set");
        _type = type;
    }

    public byte[] getPayload() {
        return _payload;
    }

    /**
     *  @throws IllegalStateException if already set
     */
    public void setPayload(byte[] payload) {
        if (_payload != null)
            throw new IllegalStateException("already set");
        _payload = payload;
    }

    /**
     *  @throws IllegalStateException if already set
     */
    public void readBytes(InputStream in) throws DataFormatException, IOException {
        if (_type != 0 || _payload != null)
            throw new IllegalStateException("already set");
        // EOF will be thrown in next read
        _type = in.read();
        int length = (int) DataHelper.readLong(in, 2);
        if (length > 0) {
            _payload = new byte[length];
            int read = read(in, _payload);
            if (read != length)
                throw new DataFormatException("Not enough bytes for the payload (read: " + read + " length: " + length
                                              + ")");
        }
    }

    public void writeBytes(OutputStream out) throws DataFormatException, IOException {
        if (_type < 0) throw new DataFormatException("Invalid certificate type: " + _type);
        //if ((_type != 0) && (_payload == null)) throw new DataFormatException("Payload is required for non null type");

        out.write((byte) _type);
        if (_payload != null) {
            DataHelper.writeLong(out, 2, _payload.length);
            out.write(_payload);
        } else {
            DataHelper.writeLong(out, 2, 0L);
        }
    }

    /**
     *  @return the written length (NOT the new offset)
     */
    public int writeBytes(byte target[], int offset) {
        int cur = offset;
        DataHelper.toLong(target, cur, 1, _type);
        cur++;
        if (_payload != null) {
            DataHelper.toLong(target, cur, 2, _payload.length);
            cur += 2;
            System.arraycopy(_payload, 0, target, cur, _payload.length);
            cur += _payload.length;
        } else {
            DataHelper.toLong(target, cur, 2, 0);
            cur += 2;
        }
        return cur - offset;
    }

    /**
     *  @throws IllegalStateException if already set
     */
    public int readBytes(byte source[], int offset) throws DataFormatException {
        if (_type != 0 || _payload != null)
            throw new IllegalStateException("already set");
        if (source == null) throw new DataFormatException("Cert is null");
        if (source.length < offset + 3)
            throw new DataFormatException("Cert is too small [" + source.length + " off=" + offset + "]");

        int cur = offset;
        _type = source[cur] & 0xff;
        cur++;
        int length = (int)DataHelper.fromLong(source, cur, 2);
        cur += 2;
        if (length > 0) {
            if (length + cur > source.length)
                throw new DataFormatException("Payload on the certificate is insufficient (len="
                                              + source.length + " off=" + offset + " cur=" + cur
                                              + " payloadLen=" + length);
            _payload = new byte[length];
            System.arraycopy(source, cur, _payload, 0, length);
            cur += length;
        }
        return cur - offset;
    }

    public int size() {
        return 1 + 2 + (_payload != null ? _payload.length : 0);
    }

    /**
     *  Up-convert this to a KeyCertificate
     *
     *  @throws DataFormatException if cert type != CERTIFICATE_TYPE_KEY
     *  @since 0.9.12
     */
    public KeyCertificate toKeyCertificate() throws DataFormatException {
        if (_type != CERTIFICATE_TYPE_KEY)
            throw new DataFormatException("type");
        return new KeyCertificate(this);
    }

    @Override
    public boolean equals(Object object) {
        if (object == this) return true;
        if ((object == null) || !(object instanceof Certificate)) return false;
        Certificate cert = (Certificate) object;
        return _type == cert.getCertificateType() && Arrays.equals(_payload, cert.getPayload());
    }

    @Override
    public int hashCode() {
        return _type + DataHelper.hashCode(_payload);
    }

    @Override
    public String toString() {
        StringBuilder buf = new StringBuilder(64);
        buf.append("\n* Certificate: Type: ");
        if (getCertificateType() == CERTIFICATE_TYPE_NULL)
            buf.append("Null");
        else if (getCertificateType() == CERTIFICATE_TYPE_KEY)
            buf.append("Key");
        else if (getCertificateType() == CERTIFICATE_TYPE_HASHCASH)
            buf.append("HashCash");
        else if (getCertificateType() == CERTIFICATE_TYPE_HIDDEN)
            buf.append("Hidden");
        else if (getCertificateType() == CERTIFICATE_TYPE_SIGNED)
            buf.append("Signed");
        else
            buf.append("Unknown type (").append(getCertificateType()).append(')');

        if (_payload == null) {
            buf.append("; Payload: null");
        } else {
            buf.append("; Payload size: ").append(_payload.length);
            if (getCertificateType() == CERTIFICATE_TYPE_HASHCASH) {
                buf.append("; Stamp: ").append(DataHelper.getUTF8(_payload));
            } else if (getCertificateType() == CERTIFICATE_TYPE_SIGNED && _payload.length == CERTIFICATE_LENGTH_SIGNED_WITH_HASH) {
                buf.append("; Signed by hash: ").append(Base64.encode(_payload, Signature.SIGNATURE_BYTES, Hash.HASH_LENGTH));
            } else {
                int len = 32;
                if (len > _payload.length) len = _payload.length;
                buf.append(" first ").append(len).append(" bytes: ");
                buf.append(DataHelper.toString(_payload, len));
            }
        }
        return buf.toString();
    }

    /**
     *  An immutable null certificate.
     *  @since 0.8.3
     */
    private static final class NullCert extends Certificate {
        private static final int NULL_LENGTH = 1 + 2;
        private static final byte[] NULL_DATA = new byte[NULL_LENGTH];

        public NullCert() {
            // zero already
            //_type = CERTIFICATE_TYPE_NULL;
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
            out.write(NULL_DATA);
        }

        /** Overridden for efficiency */
        @Override
        public int writeBytes(byte target[], int offset) {
            System.arraycopy(NULL_DATA, 0, target, offset, NULL_LENGTH);
            return NULL_LENGTH;
        }

        /** @throws RuntimeException always */
        @Override
        public int readBytes(byte source[], int offset) throws DataFormatException {
            throw new RuntimeException("Data already set");
        }

        /** Overridden for efficiency */
        @Override
        public int size() {
            return NULL_LENGTH;
        }

        /** Overridden for efficiency */
        @Override
        public int hashCode() {
            // must be the same as type + payload above
            return 0;
        }
    }
}

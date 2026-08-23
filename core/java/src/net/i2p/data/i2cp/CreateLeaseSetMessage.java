package net.i2p.data.i2cp;

/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain
 * with no warranty of any kind, either expressed or implied.
 * It probably won't make your computer catch on fire, or eat
 * your children, but it might.  Use at your own risk.
 *
 */

import net.i2p.crypto.EncType;
import net.i2p.crypto.SigType;
import net.i2p.data.DataFormatException;
import net.i2p.data.DataHelper;
import net.i2p.data.LeaseSet;
import net.i2p.data.PrivateKey;
import net.i2p.data.SigningPrivateKey;
import net.i2p.util.ByteArrayStream;

import java.io.ByteArrayInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;

/**
 * Defines the message a client sends to a router when authorizing
 * the LeaseSet
 *
 * @author jrandom
 */
public class CreateLeaseSetMessage extends I2CPMessageImpl {
    /**
     * MESSAGE_TYPE.
     */
    public static final int MESSAGE_TYPE = 4;
    /** The session ID. */
    protected SessionId _sessionId;
    /** The lease set. */
    protected LeaseSet _leaseSet;
    /** The signing private key. */
    private SigningPrivateKey _signingPrivateKey;
    /** The private key. */
    protected PrivateKey _privateKey;

    /** @since 0.9.38 */
    public CreateLeaseSetMessage() { /* required for I2CP deserialization */ }

    /**
     * Session identifier for this message.
     * @return the session ID
     */
    public SessionId getSessionId() {
        return _sessionId;
    }

    /**
     * Return the SessionId for this message.
     *
     * @since 0.9.21
     */
    @Override
    public SessionId sessionId() {
        return _sessionId;
    }

    /**
     * Session identifier for this message.
     * @param id the session ID
     */
    public void setSessionId(SessionId id) {
        _sessionId = id;
    }

    /**
     * Signing private key for the lease set.
     * @return the signing private key
     */
    public SigningPrivateKey getSigningPrivateKey() {
        return _signingPrivateKey;
    }

    /**
     * Signing private key for the lease set.
     * @param key the signing private key
     */
    public void setSigningPrivateKey(SigningPrivateKey key) {
        _signingPrivateKey = key;
    }

    /**
     * ElGamal encryption key.
     * @return the private key
     */
    public PrivateKey getPrivateKey() {
        return _privateKey;
    }

    /**
     * ElGamal encryption key.
     * @param privateKey the private key
     */
    public void setPrivateKey(PrivateKey privateKey) {
        _privateKey = privateKey;
    }

    /**
     * Lease set being created.
     * @return the lease set
     */
    public LeaseSet getLeaseSet() {
        return _leaseSet;
    }

    /**
     * Lease set being created.
     * @param leaseSet the lease set
     */
    public void setLeaseSet(LeaseSet leaseSet) {
        _leaseSet = leaseSet;
    }

    /**
     * Read the message body from the input stream.
     *
     * The signing and encryption private keys precede the LeaseSet but carry no
     * type or length prefix, so their sizes cannot be known up front. The
     * historical reader assumed DSA (20 bytes) + ElGamal (256 bytes); an external
     * client sending ECIES or EdDSA keys therefore shifts every following field,
     * and the recovered "LeaseSet" is garbage (insane lease dates, wrong
     * destination). We now buffer the payload and try the known key layouts in
     * turn, taking the first that yields a parseable LeaseSet. The classic
     * layout is tried first so well-formed legacy messages behave exactly as
     * before.
     */
    @Override
    protected void doReadMessage(InputStream in, int size) throws I2CPMessageException, IOException {
        try {
            _sessionId = new SessionId();
            _sessionId.readBytes(in);
            int hdr = 2; // sessionId is a fixed 2-byte value (see SessionId.writeBytes)
            if (size < hdr) {
                throw new EOFException("Short CreateLeaseSetMessage");
            }
            byte[] buf = new byte[size - hdr];
            DataHelper.read(in, buf);
            // signing private key lengths by type: DSA 20, ECDSA-256 32, EdDSA 64.
            // encryption private key lengths: ElGamal 256, ECIES-X25519 32.
            // Order: classic first for exact legacy compatibility.
            int[][] layouts = {
                {SigningPrivateKey.KEYSIZE_BYTES, PrivateKey.KEYSIZE_BYTES},   // DSA + ElGamal
                {SigningPrivateKey.KEYSIZE_BYTES, EncType.ECIES_X25519.getPrivkeyLen()}, // DSA + ECIES
                {64, PrivateKey.KEYSIZE_BYTES},                                // EdDSA + ElGamal
                {64, EncType.ECIES_X25519.getPrivkeyLen()},                    // EdDSA + ECIES
                {32, PrivateKey.KEYSIZE_BYTES},                                // ECDSA + ElGamal
                {32, EncType.ECIES_X25519.getPrivkeyLen()}                     // ECDSA + ECIES
            };
            // A parse that consumes the buffer exactly is authoritative: a
            // misaligned attempt virtually never lands the signature flush
            // with the end of payload. Fall back to first-parseable only if
            // no layout consumes everything.
            SigningPrivateKey fallbackSpk = null;
            PrivateKey fallbackPk = null;
            LeaseSet fallbackLs = null;
            for (int[] layout : layouts) {
                int spkLen = layout[0];
                int pkLen = layout[1];
                int off = spkLen + pkLen;
                if (off > buf.length) {
                    continue;
                }
                SigningPrivateKey spk = new SigningPrivateKey(spkTypeForLen(spkLen));
                spk.setData(DataHelper.copyOfRange(buf, 0, spkLen));
                PrivateKey pk = new PrivateKey(pkTypeForLen(pkLen));
                pk.setData(DataHelper.copyOfRange(buf, spkLen, off));
                try {
                    ByteArrayInputStream bin = new ByteArrayInputStream(buf, off, buf.length - off);
                    LeaseSet ls = new LeaseSet();
                    ls.readBytes(bin);
                    if (bin.available() == 0) {
                        _signingPrivateKey = spk;
                        _privateKey = pk;
                        _leaseSet = ls;
                        return;
                    }
                    if (fallbackSpk == null) {
                        fallbackSpk = spk;
                        fallbackPk = pk;
                        fallbackLs = ls;
                    }
                } catch (DataFormatException dfe) {
                    // try next layout
                } catch (IOException ioe) {
                    // Ran past the buffer end at this misalignment; try next.
                }
            }
            if (fallbackSpk != null) {
                _signingPrivateKey = fallbackSpk;
                _privateKey = fallbackPk;
                _leaseSet = fallbackLs;
                return;
            }
            // No layout parsed. Re-run the classic parse so callers see the
            // familiar exception for genuinely malformed messages.
            ByteArrayInputStream bin = new ByteArrayInputStream(buf);
            _signingPrivateKey = new SigningPrivateKey();
            _signingPrivateKey.readBytes(bin);
            _privateKey = new PrivateKey();
            _privateKey.readBytes(bin);
            _leaseSet = new LeaseSet();
            _leaseSet.readBytes(bin);
        } catch (DataFormatException dfe) {
            throw new I2CPMessageException("Error reading the CreateLeaseSetMessage", dfe);
        }
    }

    /**
     * Map a known signing private key length to its most likely type.
     *
     * @param len key length in bytes
     * @return DSA_SHA1 for 20, EdDSA_SHA512_Ed25519 for 64, else ECDSA_SHA256_P256
     * @since 0.9.70+
     */
    private static SigType spkTypeForLen(int len) {
        if (len == 20) {return SigType.DSA_SHA1;}
        if (len == 64) {return SigType.EdDSA_SHA512_Ed25519;}
        return SigType.ECDSA_SHA256_P256;
    }

    /**
     * Map a known encryption private key length to its most likely type.
     *
     * @param len key length in bytes
     * @return ELGAMAL_2048 for 256, else ECIES_X25519
     * @since 0.9.70+
     */
    private static EncType pkTypeForLen(int len) {
        if (len == PrivateKey.KEYSIZE_BYTES) {return EncType.ELGAMAL_2048;}
        return EncType.ECIES_X25519;
    }

    /**
     * Write the message body to the output stream.
     */
    @Override
    protected byte[] doWriteMessage() throws I2CPMessageException, IOException {
        if ((_sessionId == null) || (_signingPrivateKey == null) || (_privateKey == null) || (_leaseSet == null)) throw new I2CPMessageException("Unable to write out the message as there is not enough data");
        int size = 4 // sessionId
                        + _signingPrivateKey.length()
                        + PrivateKey.KEYSIZE_BYTES
                        + _leaseSet.size();
        ByteArrayStream os = new ByteArrayStream(size);
        try {
            _sessionId.writeBytes(os);
            _signingPrivateKey.writeBytes(os);
            _privateKey.writeBytes(os);
            _leaseSet.writeBytes(os);
        } catch (DataFormatException dfe) {
            throw new I2CPMessageException("Error writing out the message data", dfe);
        }
        return os.toByteArray();
    }

    /**
     * Type.
     * @return the type
     */
    @Override
    public int getType() {
        return MESSAGE_TYPE;
    }
/** Returns a string representation of this message. */
    @Override
    public String toString() {
        StringBuilder buf = new StringBuilder(); // NOPMD - AvoidUnnecessaryStringBuilderCreation
        buf.append("[CreateLeaseSetMessage: ");
        buf.append("\n\tLeaseSet: ").append(getLeaseSet());
        buf.append("\n\tSigningPrivateKey: ").append(getSigningPrivateKey());
        buf.append("\n\tPrivateKey: ").append(getPrivateKey());
        buf.append("\n\tSessionId: ").append(getSessionId());
        buf.append("]");
        return buf.toString();
    }
}

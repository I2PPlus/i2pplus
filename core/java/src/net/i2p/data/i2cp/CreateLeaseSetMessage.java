package net.i2p.data.i2cp;

/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain
 * with no warranty of any kind, either expressed or implied.
 * It probably won't make your computer catch on fire, or eat
 * your children, but it might.  Use at your own risk.
 *
 */

import net.i2p.data.DataFormatException;
import net.i2p.data.LeaseSet;
import net.i2p.data.PrivateKey;
import net.i2p.data.SigningPrivateKey;
import net.i2p.util.ByteArrayStream;

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
     */
    @Override
    protected void doReadMessage(InputStream in, int size) throws I2CPMessageException, IOException {
        try {
            _sessionId = new SessionId();
            _sessionId.readBytes(in);
            // Revocation is unimplemented.
            // As the SPK comes before the LeaseSet, we don't know the key type.
            // We could have some sort of callback or state setting so we get the
            // expected type from the session. But for now, we just assume it's 20 bytes.
            // Clients outside router context should throw in a dummy 20 bytes.
            _signingPrivateKey = new SigningPrivateKey();
            _signingPrivateKey.readBytes(in);
            _privateKey = new PrivateKey();
            _privateKey.readBytes(in);
            _leaseSet = new LeaseSet();
            _leaseSet.readBytes(in);
        } catch (DataFormatException dfe) {
            throw new I2CPMessageException("Error reading the CreateLeaseSetMessage", dfe);
        }
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

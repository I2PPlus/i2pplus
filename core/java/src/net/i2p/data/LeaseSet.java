package net.i2p.data;

/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain
 * with no warranty of any kind, either expressed or implied.
 * It probably won't make your computer catch on fire, or eat
 * your children, but it might.  Use at your own risk.
 *
 */

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import net.i2p.I2PAppContext;
import net.i2p.crypto.EncType;
import net.i2p.crypto.SigType;
import net.i2p.util.ByteArrayStream;
import net.i2p.util.Clock;
import net.i2p.util.Log;
import net.i2p.util.RandomSource;

/**
 * Defines the set of leases that a destination currently has available for routing messages.
 *
 * <p>LeaseSet is the fundamental routing structure in I2P, containing:</p>
 * <ul>
 *   <li><strong>Destination:</strong> The service endpoint identity</li>
 *   <li><strong>Encryption Key:</strong> Public key for encrypting messages to the destination</li>
 *   <li><strong>Signing Key:</strong> Public key for verifying LeaseSet authenticity</li>
 *   <li><strong>Leases:</strong> List of tunnel endpoints and their validity periods</li>
 *   <li><strong>Signature:</strong> Cryptographic signature proving authenticity</li>
 * </ul>
 *
 * <p><strong>Lease Structure:</strong></p>
 * <ul>
 *   <li><strong>Gateway:</strong> Router identity that hosts the tunnel endpoint</li>
 *   <li><strong>Tunnel ID:</strong> Unique identifier for the tunnel on the gateway</li>
 *   <li><strong>Expiration:</strong> Time when the lease becomes invalid</li>
 * </ul>
 *
 * <p><strong>Encryption Support (Legacy):</strong></p>
 * <ul>
 *   <li><strong>⚠️ SECURITY WARNING:</strong> Encryption is poorly designed and probably insecure</li>
 *   <li><strong>Not Recommended:</strong> Use modern alternatives like EncryptedLeaseSet</li>
 *   <li><strong>Limited Scope:</strong> Only encrypts gateway and tunnel ID data</li>
 *   <li><strong>No Indication:</strong> Encrypted leases appear identical to unencrypted ones</li>
 *   <li><strong>Keyring Required:</strong> Routers need desthash and key to decrypt</li>
 * </ul>
 *
 * <p><strong>Encryption Process:</strong></p>
 * <ul>
 *   <li><strong>Client Side:</strong> Encryption performed in I2CP client</li>
 *   <li><strong>Router Side:</strong> Local router must decrypt for usage</li>
 *   <li><strong>Network:</strong> Encrypted form transmitted to floodfills</li>
 *   <li><strong>Access:</strong> Decrypted leases only available via {@link #getLease(int)}</li>
 * </ul>
 *
 * <p><strong>Usage:</strong></p>
 * <ul>
 *   <li><strong>Routing:</strong> Primary mechanism for message delivery in I2P</li>
 *   <li><strong>Load Balancing:</strong> Multiple leases provide redundancy and distribution</li>
 *   <li><strong>Mobility:</strong> Leases can be updated as endpoints change</li>
 *   <li><strong>Discovery:</strong> Published to network database for lookup</li>
 * </ul>
 *
 * <p><strong>Security Considerations:</strong></p>
 * <ul>
 *   <li><strong>Signature Verification:</strong> Always verify LeaseSet signatures</li>
 *   <li><strong>Expiration Checking:</strong> Ensure leases are still valid</li>
 *   <li><strong>Encryption Avoidance:</strong> Legacy encryption should not be used</li>
 *   <li><strong>Modern Alternatives:</strong> Use LeaseSet2, EncryptedLeaseSet, or MetaLeaseSet</li>
 * </ul>
 *
 * <p><strong>Migration Path:</strong></p>
 * <ul>
 *   <li><strong>LeaseSet2:</strong> Enhanced format with better security and features</li>
 *   <li><strong>EncryptedLeaseSet:</strong> Proper encryption with authentication</li>
 *   <li><strong>MetaLeaseSet:</strong> Advanced routing and load balancing</li>
 * </ul>
 * leases and the original leaseset signature.
 *
 * Revocation (zero leases) isn't used anywhere. In addition:
 *  - A revoked leaseset has an EarliestLeaseDate of -1, so it will
 *    never be stored successfully.
 *  - Revocation of an encrypted leaseset will explode.
 *  - So having an included signature at all is pointless?
 *
 *
 * @author jrandom
 */
public class LeaseSet extends DatabaseEntry {
    protected Destination _destination;
    protected PublicKey _encryptionKey;
    // The revocation key for LS1, null for LS2 except blinded key for encrypted LS2
    protected SigningPublicKey _signingKey;
    // Keep leases in the order received, or else signature verification will fail!
    protected final List<Lease> _leases;
    // Store these since isCurrent() and getEarliestLeaseDate() are called frequently
    private long _firstExpiration;
    protected long _lastExpiration;
    private List<Lease> _decryptedLeases;
    private boolean _decrypted;
    protected boolean _checked;
    // cached byte version
    protected volatile byte _byteified[];

    /**
     *  Unlimited before 0.6.3;
     *  6 as of 0.6.3;
     *  Increased in version 0.9.
     *
     *  Leasesets larger than 6 should be used with caution,
     *  as each lease adds 44 bytes, and routers older than version 0.9
     *  will not be able to connect as they will throw an exception in
     *  readBytes(). Also, the churn will be quite rapid, leading to
     *  frequent netdb stores and transmission on existing connections.
     *
     *  However we increase it now in case some hugely popular eepsite arrives.
     *  Strategies elsewhere in the router to efficiently handle
     *  large leasesets are TBD.
     */
    public static final int MAX_LEASES = 16;

    public LeaseSet() {
        _leases = new ArrayList<Lease>(2);
        _firstExpiration = Long.MAX_VALUE;
    }

    /**
      * Same as getEarliestLeaseDate()
      */
    @Override
    public long getDate() {return getEarliestLeaseDate();}

    @Override
    public KeysAndCert getKeysAndCert() {return _destination;}

    @Override
    public int getType() {return KEY_TYPE_LEASESET;}

    /**
     *  Warning - will be null for LS2 EncryptedLeaseSets if not decrypted
     *
     *  @return Destination or null
     */
    public Destination getDestination() {return _destination;}

    /**
      *  Sets the destination for this leaseset.
      *
      * @throws IllegalStateException if already signed
      */
    public void setDestination(Destination dest) {
        if (_signature != null) {throw new IllegalStateException();}
        _destination = dest;
    }

    public PublicKey getEncryptionKey() {return _encryptionKey;}

    /**
     *  If more than one key, return the first supported one.
     *  If none supported, return null.
     *
     *  @param supported what return types are allowed
     *  @return ElGamal key or null if ElGamal not in supported
     *  @since 0.9.44
     */
    public PublicKey getEncryptionKey(Set<EncType> supported) {
        if (supported.contains(EncType.ELGAMAL_2048)) {return _encryptionKey;}
        return null;
    }

    /**
      *  Sets the encryption key for this leaseset.
      *
      * @throws IllegalStateException if already signed
      */
    public void setEncryptionKey(PublicKey encryptionKey) {
        if (_signature != null) {throw new IllegalStateException();}
        _encryptionKey = encryptionKey; // subclasses may set an ECIES key
    }

    /**
     *  The revocation key.
     *  Undeprecated as of 0.9.38, used for the blinded key in EncryptedLeaseSet.
     *  @return the revocation key for LS1, null for LS2 except blinded key for encrypted LS2
     */
    public SigningPublicKey getSigningKey() {return _signingKey;}

    /**
     *  The revocation key. Unused except for encrypted LS2.
     *  Must be the same type as the Destination's SigningPublicKey.
     *  @throws IllegalArgumentException if different type
     */
    public void setSigningKey(SigningPublicKey key) {
        if (key != null && _destination != null && key.getType() != _destination.getSigningPublicKey().getType()) {
            throw new IllegalArgumentException("Signing key type mismatch");
        }
        _signingKey = key;
    }

    /**
      * As of 0.9.65, no longer sets receivedAsReply to true
      * @param localClient may be null
      * @since 0.9.47
      */
    @Override
    public void setReceivedBy(Hash localClient) {
        super.setReceivedBy(localClient);
        //setReceivedAsReply();
    }

    /**
      *  Adds a lease to this leaseset.
      *
      * @throws IllegalStateException if already signed
      */
    public void addLease(Lease lease) {
        if (lease == null) throw new IllegalArgumentException("Error:! Null lease!");
        if (lease.getGateway() == null) throw new IllegalArgumentException("Error: Lease has no gateway!");
        if (getType() != KEY_TYPE_META_LS2 && lease.getTunnelId() == null) {
            throw new IllegalArgumentException("Error: Lease has no tunnel!");
        }
        if (_signature != null) {throw new IllegalStateException();}
        if (_leases.size() >= MAX_LEASES) {
            throw new IllegalArgumentException("Error: Too many leases! -> Maximum permitted is " + MAX_LEASES);
        }
        _leases.add(lease);
        long expire = lease.getEndTime();
        if (expire < _firstExpiration) {_firstExpiration = expire;}
        if (expire > _lastExpiration) {_lastExpiration = expire;}
    }

    /**
      *  Gets the number of leases in this leaseset.
      *
      *  @return 0-16
      *  A LeaseSet with no leases is revoked.
      */
    public int getLeaseCount() {
        if (isEncrypted()) {return _leases.size() - 1;}
        else {return _leases.size();}
    }

    public Lease getLease(int index) {
        if (isEncrypted()) {return _decryptedLeases.get(index);}
        else {return _leases.get(index);}
    }

    /**
     * Retrieve the end date of the earliest lease included in this leaseSet.
     * This is the date that should be used in comparisons for leaseSet age - to
     * determine which LeaseSet was published more recently (later earliestLeaseSetDate
     * means it was published later)
     *
     * Warning - do not use this for version comparison for LeaseSet2.
     * Use LeaseSet2.getPublished() instead.
     *
     * @return earliest end date of any lease in the set, or -1 if there are no leases
     */
    public long getEarliestLeaseDate() {
        if (_leases.isEmpty()) {return -1;}
        return _firstExpiration;
    }

    /**
     * Retrieve the end date of the latest lease included in this leaseSet.
     * This is the date used in isCurrent().
     *
     * @return latest end date of any lease in the set, or 0 if there are no leases
     * @since 0.9.7
     */
    public long getLatestLeaseDate() {return _lastExpiration;}

    /**
     * Verify that the signature matches the leaseset's destination's signing public key.
     * As of 0.9.47, revocation is not checked.
     *
     * @return true only if the signature matches
     */
    @Override
    public boolean verifySignature() {return super.verifySignature();} // Revocation unused (see above)

    /**
     * Verify that the signature matches the leaseset's destination's signing public key.
     * As of 0.9.47, revocation is not checked.
     *
     * @deprecated revocation unused
     * @return true only if the signature matches
     */
    @Deprecated
    public boolean verifySignature(SigningPublicKey signingKey) {return super.verifySignature();} // Revocation unused (see above)

    /**
     * Determine whether ANY lease is currently valid, at least within a given
     * fudge factor
     *
     * @param fudge milliseconds fudge factor to allow between the current time
     * @return true if there are current leases, false otherwise
     */
    public boolean isCurrent(long fudge) {
        long now = Clock.getInstance().now();
        return _lastExpiration > now - fudge;
    }

    /** without sig! */
    @Override
    protected byte[] getBytes() {
        if (_byteified != null) {return _byteified;}
        if ((_destination == null) || (_encryptionKey == null) || (_signingKey == null)) {return null;}
        int len = size();
        ByteArrayStream out = new ByteArrayStream(len);
        try {
            _destination.writeBytes(out);
            _encryptionKey.writeBytes(out);
            _signingKey.writeBytes(out);
            out.write((byte) _leases.size());
            for (Lease lease : _leases) {lease.writeBytes(out);}
        } catch (IOException ioe) {return null;}
        catch (DataFormatException dfe) {return null;}
        byte rv[] = out.toByteArray();
        if (getReceivedAsPublished()) {_byteified = rv;} // if we are floodfill and this was published to us
        return rv;
    }

    /**
      *  This does NOT validate the signature
      *
      *  @throws IllegalStateException if called more than once or Destination already set
      */
    @Override
    public void readBytes(InputStream in) throws DataFormatException, IOException {
        if (_destination != null) {throw new IllegalStateException();}
        _destination = Destination.create(in);
        _encryptionKey = PublicKey.create(in);
        // revocation signing key must be same type as the destination signing key
        SigType type = _destination.getSigningPublicKey().getType();
        // Even if not verifying, we have to construct a Signature object
        // below, which will fail for null type.
        if (type == null) {throw new DataFormatException("Unknown Signature Type");}
        _signingKey = new SigningPublicKey(type);
        // EOF will be thrown in signature read below
        _signingKey.readBytes(in);
        int numLeases = in.read();
        if (numLeases > MAX_LEASES) {
            throw new DataFormatException("Error: Too many leases (" + numLeases + ") -> Maximum permitted is " + MAX_LEASES);
        }
        //_version = DataHelper.readLong(in, 4);
        for (int i = 0; i < numLeases; i++) {
            Lease lease = new Lease();
            lease.readBytes(in);
            addLease(lease);
        }
        // signature must be same type as the destination signing key
        _signature = new Signature(type);
        _signature.readBytes(in);
    }

    /**
      *  This does NOT validate the signature
      */
    @Override
    public void writeBytes(OutputStream out) throws DataFormatException, IOException {
        if ((_destination == null) || (_encryptionKey == null) || (_signingKey == null)
            || (_signature == null)) {
            throw new DataFormatException("Not enough data to write out a LeaseSet");
        }
        _destination.writeBytes(out);
        _encryptionKey.writeBytes(out);
        _signingKey.writeBytes(out);
        out.write((byte) _leases.size());
        for (Lease lease : _leases) {lease.writeBytes(out);}
        _signature.writeBytes(out);
    }

    /**
     *  Number of bytes, NOT including signature
     */
    public int size() {
        return _destination.size()
             + PublicKey.KEYSIZE_BYTES // encryptionKey
             + _signingKey.length() // signingKey
             + 1 // number of leases
             + _leases.size() * 44;
    }

    @Override
    public boolean equals(Object object) {
        if (object == this) {return true;}
        if ((object == null) || !(object instanceof LeaseSet)) {return false;}
        LeaseSet ls = (LeaseSet) object;
        return
               DataHelper.eq(_signature, ls.getSignature())
               && DataHelper.eq(_leases, ls._leases)
               && DataHelper.eq(getEncryptionKey(), ls.getEncryptionKey())
               && DataHelper.eq(_signingKey, ls.getSigningKey())
               && DataHelper.eq(_destination, ls.getDestination());
    }

    /** the destination has enough randomness in it to use it by itself for speed */
    @Override
    public int hashCode() {
        if (_destination == null) {return 0;}
        return _destination.hashCode();
    }

    @Override
    public String toString() {
        StringBuilder buf = new StringBuilder(128);
        if (_destination != null) {
            buf.append("\n* Destination: ").append(_destination.toBase32());
        }
        if (_encryptionKey != null) {buf.append("\n* EncryptionKey: ").append(_encryptionKey);}
        if (_signingKey != null) {buf.append("\n* Signing Key: ").append(_signingKey);}
        if (_signature != null) {buf.append("\n* Signature: ").append(_signature);}
        buf.append("\n* Leases: ").append(getLeaseCount());
        for (int i = 0; i < getLeaseCount(); i++) {buf.append(getLease(i));}
        return buf.toString();
    }

    private static final int DATA_LEN = Hash.HASH_LENGTH + 4;
    private static final int IV_LEN = 16;

    /**
     *  Encrypt the gateway and tunnel ID of each lease, leaving the expire dates unchanged.
     *  This adds an extra dummy lease, because AES data must be padded to 16 bytes.
     *  The fact that it is encrypted is not stored anywhere.
     *  Must be called after all the leases are in place, but before sign().
     */
    public void encrypt(SessionKey key) {
        try {
            encryp(key);
        } catch (DataFormatException dfe) {
            Log log = I2PAppContext.getGlobalContext().logManager().getLog(LeaseSet.class);
            log.error("Error encrypting Lease: " + _destination.calculateHash(), dfe);
        } catch (IOException ioe) {
            Log log = I2PAppContext.getGlobalContext().logManager().getLog(LeaseSet.class);
            log.error("Error encrypting Lease: " + _destination.calculateHash(), ioe);
        }
    }

    /**
     *  - Put the {Gateway Hash, TunnelID} pairs for all the leases in a buffer
     *  - Pad with random data to a multiple of 16 bytes
     *  - Use the first part of the dest's public key as an IV
     *  - Encrypt
     *  - Pad with random data to a multiple of 36 bytes
     *  - Add an extra lease
     *  - Replace the Hash and TunnelID in each Lease
     */
    private void encryp(SessionKey key) throws DataFormatException, IOException {
        int size = _leases.size();
        if (size < 1 || size > MAX_LEASES-1) {
            throw new IllegalArgumentException("Bad number of leases (" + size + ") for encrypted LeaseSet");
        }
        int datalen = ((DATA_LEN * size / 16) + 1) * 16;
        ByteArrayStream baos = new ByteArrayStream(datalen);
        for (int i = 0; i < size; i++) {
            _leases.get(i).getGateway().writeBytes(baos);
            _leases.get(i).getTunnelId().writeBytes(baos);
        }
        // pad out to multiple of 16 with random data before encryption
        int padlen = datalen - (DATA_LEN * size);
        byte[] pad = new byte[padlen];
        RandomSource.getInstance().nextBytes(pad);
        baos.write(pad);
        byte[] iv = new byte[IV_LEN];
        System.arraycopy(_destination.getPublicKey().getData(), 0, iv, 0, IV_LEN);
        byte[] enc = new byte[DATA_LEN * (size + 1)];
        I2PAppContext.getGlobalContext().aes().encrypt(baos.toByteArray(), 0, enc, 0, key, iv, datalen);
        // pad out to multiple of 36 with random data after encryption
        // (even for 4 leases, where 36*4 is a multiple of 16, we add another, just to be consistent)
        padlen = enc.length - datalen;
        RandomSource.getInstance().nextBytes(enc, datalen, padlen);
        // add the padded lease...
        Lease padLease = new Lease();
        padLease.setEndDate(_leases.get(0).getEndTime());
        _leases.add(padLease);
        // ...and replace all the gateways and tunnel ids
        ByteArrayInputStream bais = new ByteArrayInputStream(enc);
        for (int i = 0; i < size+1; i++) {
            Hash h = new Hash();
            h.readBytes(bais);
            _leases.get(i).setGateway(h);
            TunnelId t = new TunnelId();
            t.readBytes(bais);
            _leases.get(i).setTunnelId(t);
        }
    }

    /**
     *  Decrypt the leases, except for the last one which is partially padding.
     *  Store the new decrypted leases in a backing store,
     *  and keep the original leases so that verify() still works and the
     *  encrypted leaseset can be sent on to others (via writeBytes())
     */
    private void decrypt(SessionKey key) throws DataFormatException, IOException {
        int size = _leases.size();
        if (size < 2) {
            throw new DataFormatException("Bad number of leases (" + size + ") decrypting " + _destination.toBase32() +
                                          " -> Is this destination encrypted?");
        }
        int datalen = DATA_LEN * size;
        ByteArrayStream baos = new ByteArrayStream(datalen);
        for (int i = 0; i < size; i++) {
            _leases.get(i).getGateway().writeBytes(baos);
            _leases.get(i).getTunnelId().writeBytes(baos);
        }
        byte[] iv = new byte[IV_LEN];
        System.arraycopy(_destination.getPublicKey().getData(), 0, iv, 0, IV_LEN);
        int enclen = ((DATA_LEN * (size - 1) / 16) + 1) * 16;
        byte[] enc = new byte[enclen];
        System.arraycopy(baos.toByteArray(), 0, enc, 0, enclen);
        byte[] dec = new byte[enclen];
        I2PAppContext.getGlobalContext().aes().decrypt(enc, 0, dec, 0, key, iv, enclen);
        ByteArrayInputStream bais = new ByteArrayInputStream(dec);
        _decryptedLeases = new ArrayList<Lease>(size - 1);
        for (int i = 0; i < size-1; i++) {
            Lease l = new Lease();
            Hash h = new Hash();
            h.readBytes(bais);
            l.setGateway(h);
            TunnelId t = new TunnelId();
            t.readBytes(bais);
            l.setTunnelId(t);
            l.setEndDate(_leases.get(i).getEndTime());
            _decryptedLeases.add(l);
        }
    }

    /**
     * @return true if it was encrypted, and we decrypted it successfully.
     * Decrypts on first call.
     */
    private synchronized boolean isEncrypted() {
        if (_decrypted) {return true;}
        // If the encryption key is not set yet, it can't have been encrypted yet.
        // Router-side I2CP sets the destination (but not the encryption key)
        // on an unsigned LS which is pending signature (and possibly encryption)
        // by the client, and we don't want to attempt 'decryption' on it.
        if (_checked || _encryptionKey == null || _destination == null) {return false;}
        SessionKey key = I2PAppContext.getGlobalContext().keyRing().get(_destination.calculateHash());
        if (key != null) {
            try {
                decrypt(key);
                _decrypted = true;
            } catch (DataFormatException dfe) {
                Log log = I2PAppContext.getGlobalContext().logManager().getLog(LeaseSet.class);
                log.error("Error decrypting " + _destination.toBase32() +
                          " -> Is this destination encrypted?", dfe);
            } catch (IOException ioe) {
                Log log = I2PAppContext.getGlobalContext().logManager().getLog(LeaseSet.class);
                log.error("Error decrypting " + _destination.toBase32() +
                          " -> Is this destination encrypted?", ioe);
            }
        }
        _checked = true;
        return _decrypted;
    }

}

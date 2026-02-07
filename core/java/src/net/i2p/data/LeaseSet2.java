package net.i2p.data;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import net.i2p.I2PAppContext;
import net.i2p.crypto.EncType;
import net.i2p.crypto.SigAlgo;
import net.i2p.crypto.SigType;
import net.i2p.util.ByteArrayStream;
import net.i2p.util.Clock;
import net.i2p.util.Log;
import net.i2p.util.OrderedProperties;
import net.i2p.util.SystemVersion;

/**
 * Implementation of LeaseSet2 as specified in
 * <a href="https://geti2p.net/spec/proposals/123-new-netdb-entries">Proposal 123: New NetDb Entries</a>.
 *
 * <p>LeaseSet2 provides several enhancements over the original LeaseSet format:</p>
 * <ul>
 *   <li>Support for multiple encryption keys with server preference ordering</li>
 *   <li>Offline signature support for reduced router load</li>
 *   <li>Published and expiration timestamps independent of lease times</li>
 *   <li>Options and statistics support</li>
 *   <li>Blinded and encrypted LeaseSet support</li>
 * </ul>
 *
 * <p>Key differences from LeaseSet:</p>
 * <ul>
 *   <li>Uses {@link #getPublished()} for version comparison instead of lease dates</li>
 *   <li>Supports multiple encryption keys via {@link #getEncryptionKeys()}</li>
 *   <li>Includes offline signing capabilities via {@link #setOfflineSignature(long, SigningPublicKey, Signature)}</li>
 *   <li>Has separate published and expires timestamps</li>
 * </ul>
 *
 * @since 0.9.38
 */
public class LeaseSet2 extends LeaseSet {
    protected int _flags;
    protected long _published; // stored as absolute ms
    protected long _expires; // stored as absolute ms
    protected long _transientExpires; // stored as absolute ms
    protected SigningPublicKey _transientSigningPublicKey; // if non-null, type of this is type of _signature in super
    protected Signature _offlineSignature; // if non-null, type of this is type of SPK in the dest
    protected Properties _options; // may be null
    private List<PublicKey> _encryptionKeys; // only used if more than one key, otherwise null
    private Hash _blindedHash; // If this leaseset was formerly blinded, the blinded hash, so we can find it again
    private static final int FLAG_OFFLINE_KEYS = 0x01;
    private static final int FLAG_UNPUBLISHED = 0x02;
    /**
     *  Set if the unencrypted LS, when published, will be blinded/encrypted
     *  @since 0.9.42
     */
    private static final int FLAG_BLINDED = 0x04;
    private static final int MAX_KEYS = 8;
    private static final Log _log = I2PAppContext.getGlobalContext().logManager().getLog(LeaseSet2.class);

    public LeaseSet2() {
        super();
        _checked = true; // prevents decryption in super
    }

    /**
     * Published timestamp, as received.
     * Different than getDate() or getEarliestLeaseDate(), which are the earliest lease expiration.
     *
     * Use this for LS2 version comparison, NOT getEarliestLeaseDate(), because
     * that will return -1 for EncryptedLS and MetaLS.
     *
     * @return in ms, with 1 second resolution
     * @since 0.9.39
     */
    public long getPublished() {return _published;}

    /**
     * Set published timestamp.
     * Will be rounded to nearest second.
     * If not called, will be set on write.
     *
     * @since 0.9.64
     */
    public void setPublished(long now) {
        _published = ((now + 500) / 1000) * 1000; // we round it here, so comparisons during verifies aren't wrong
    }

    /**
     * Published expiration, as received.
     * May be different than getLatestLeaseDate(), which is the latest lease expiration.
     *
     * @return in ms, with 1 second resolution
     * @since 0.9.39
     */
    public long getExpires() {return _expires;}

    public boolean isUnpublished() {return (_flags & FLAG_UNPUBLISHED) != 0;}

    /**
      *  Marks this leaseset as unpublished.
      *
      *  @throws IllegalStateException if already signed
      */
    public void setUnpublished() {
        if (_signature != null && (_flags & FLAG_UNPUBLISHED) == 0) {
            throw new IllegalStateException();
        }
        _flags |= FLAG_UNPUBLISHED;
    }

    /**
     *  Set if the unencrypted LS, when published, will be blinded/encrypted
     *  @since 0.9.42
     */
    public boolean isBlindedWhenPublished() {return (_flags & FLAG_BLINDED) != 0;}

    /**
     *  Set if the unencrypted LS, when published, will be blinded/encrypted
     *  @throws IllegalStateException if already signed
     *  @since 0.9.42
     */
    public void setBlindedWhenPublished() {
        if (_signature != null && (_flags & FLAG_BLINDED) == 0) {
            throw new IllegalStateException();
        }
        _flags |= FLAG_BLINDED;
    }

    /**
     * If true, we received this LeaseSet by a remote peer publishing it to
     * us, AND the unpublished flag is not set.
     * Default false.
     *
     *  @since 0.9.39 overridden
     */
    @Override
    public boolean getReceivedAsPublished() {
        return super.getReceivedAsPublished() && !isUnpublished();
    }

    public String getOption(String opt) {
        if (_options == null) {return null;}
        return _options.getProperty(opt);
    }

    /**
      *  Gets the leaseset options.
      *
      *  @return not a copy, do not modify, or null
      *  @since 0.9.63
      */
    public Properties getOptions() {return _options;}

    /**
     *  If more than one key, return the first supported one.
     *  If none supported, return the first one.
     *
     *  @since 0.9.39 overridden
     */
    @Override
    public PublicKey getEncryptionKey() {
        if (_encryptionKeys != null) {
            for (PublicKey pk : _encryptionKeys) {
                EncType type = pk.getType();
                if (type != null && type.isAvailable()) {return pk;}
            }
        }
        return _encryptionKey;
    }

    /**
     *  If more than one key, return the first supported one.
     *  If none supported, return null.
     *
     *  @return first supported key or null
     *  @since 0.9.44
     */
    @Override
    public PublicKey getEncryptionKey(Set<EncType> supported) {
        List<PublicKey> keys = getEncryptionKeys();
        if (keys == null) {return null;}
        for (PublicKey pk : keys) { // Honor order in LS
            if (supported.contains(pk.getType())) {return pk;}
        }
        return null;
    }

    /**
     *  Add an encryption key.
     *
     *  Encryption keys should be added in order of server preference, most-preferred first.
     */
    public void addEncryptionKey(PublicKey key) {
        if (_encryptionKey == null) {setEncryptionKey(key);}
        else {
            if (_encryptionKeys == null) {
                _encryptionKeys = new ArrayList<PublicKey>(4);
                _encryptionKeys.add(_encryptionKey);
            } else {
                if (_encryptionKeys.size() >= MAX_KEYS) {throw new IllegalStateException();}
            }
            _encryptionKeys.add(key);
        }
    }

    /**
     *  This returns all the keys. getEncryptionKey() returns the first one.
     *
     *  Encryption keys should be in order of server preference, most-preferred first.
     *  Client behavior should be to select the first key with a supported encryption type.
     *  Clients may use other selection algorithms based on encryption support, relative performance, and other factors.
     *
     *  @return not a copy, do not modify, null if none
     */
    public List<PublicKey> getEncryptionKeys() {
        if (_encryptionKeys != null) {return _encryptionKeys;}
        if (_encryptionKey != null) {return Collections.singletonList(_encryptionKey);}
        return null;
    }

    /**
     * Configure a set of options or statistics that the router can expose.
     * Makes a copy.
     *
     * @param options if null, clears current options
     * @throws IllegalStateException if LeaseSet2 is already signed
     */
    public void setOptions(Properties options) {
        if (_signature != null) {throw new IllegalStateException();}
        if (_options != null) {_options.clear();}
        else {_options = new OrderedProperties();}
        _options.putAll(options);
    }

    /**
      *  Checks if this leaseset uses offline keys.
      */
    public boolean isOffline() {return (_flags & FLAG_OFFLINE_KEYS) != 0;}

    /**
      *  Gets the transient public key for offline signing.
      *
      *  @return transient public key or null if not offline signed
      */
    public SigningPublicKey getTransientSigningKey() {return _transientSigningPublicKey;}

    /**
     *  Absolute time, not time from now.
     *  @return transient expiration time or 0 if not offline signed
     *  @since 0.9.48
     */
    public long getTransientExpiration() {return _transientExpires;}

    /**
     *  Destination must be previously set.
     *
     *  @param expires absolute ms
     *  @param transientSPK the key that will sign the leaseset
     *  @param offlineSig the signature by the spk in the destination
     *  @return success, false if verify failed or expired
     *  @throws IllegalStateException if already signed
     */
    public boolean setOfflineSignature(long expires, SigningPublicKey transientSPK, Signature offlineSig) {
        if (_signature != null) {throw new IllegalStateException();}
        _flags |= FLAG_OFFLINE_KEYS;
        _transientExpires = expires;
        _transientSigningPublicKey = transientSPK;
        _offlineSignature = offlineSig;
        return verifyOfflineSignature();
    }

    /**
     *  Generate a Signature to pass to setOfflineSignature()
     *
     *  @param expires absolute ms
     *  @param transientSPK the key that will sign the leaseset
     *  @param priv the private signing key for the destination
     *  @return null on error
     */
    public static Signature offlineSign(long expires, SigningPublicKey transientSPK, SigningPrivateKey priv) {
        ByteArrayStream baos = new ByteArrayStream(4 + 2 + transientSPK.length());
        try {
            DataHelper.writeLong(baos, 4, expires / 1000);
            DataHelper.writeLong(baos, 2, transientSPK.getType().getCode());
            transientSPK.writeBytes(baos);
        }
        catch (IOException ioe) {return null;}
        catch (DataFormatException dfe) {return null;}
        return baos.sign(priv);
    }

    public boolean verifyOfflineSignature() {
        return verifyOfflineSignature(_destination.getSigningPublicKey());
    }

    protected boolean verifyOfflineSignature(SigningPublicKey spk) {
        if (!isOffline()) {return false;}
        I2PAppContext ctx = I2PAppContext.getGlobalContext();
        if (_transientExpires < ctx.clock().now()) {return false;}
        ByteArrayStream baos = new ByteArrayStream(4 + 2 + _transientSigningPublicKey.length());
        try {
            DataHelper.writeLong(baos, 4, _transientExpires / 1000);
            DataHelper.writeLong(baos, 2, _transientSigningPublicKey.getType().getCode());
            _transientSigningPublicKey.writeBytes(baos);
        }
        catch (IOException ioe) {return false;}
        catch (DataFormatException dfe) {return false;}
        return baos.verifySignature(ctx, _offlineSignature, getSigningPublicKey());
    }

    /**
     * Set this on creation if known
     */
    public void setBlindedHash(Hash bh) {_blindedHash = bh;}

    /**
     * The orignal blinded hash, where this came from.
     * @return null if unknown or not previously blinded
     */
    public Hash getBlindedHash() {return _blindedHash;}


    ///// overrides below here

    @Override
    public int getType() {return KEY_TYPE_LS2;}

    /**
     *  The revocation key. Overridden to do nothing,
     *  as we're using the _signingKey field for the blinded key in Enc LS2.
     *
     * @since 0.9.39
     */
    @Override
    public void setSigningKey(SigningPublicKey key) {
        Log log = I2PAppContext.getGlobalContext().logManager().getLog(LeaseSet2.class);
        if (log.shouldWarn()) {
            log.warn("Don't set revocation key in LS2", new Exception("I did it"));
        }
    }

    /**
     * Determine whether the leaseset is currently valid, at least within a given
     * fudge factor.
     * Overridden to use the expiration time instead of the last expiration.
     *
     * @param fudge milliseconds fudge factor to allow between the current time
     * @return true if there are current leases, false otherwise
     * @since 0.9.39
     */
    @Override
    public boolean isCurrent(long fudge) {
        long now = Clock.getInstance().now();
        return _expires > now - fudge;
    }

    /** without sig! */
    @Override
    protected byte[] getBytes() {
        if (_byteified != null) return _byteified;
        if (_destination == null) {return null;}
        int len = size();
        ByteArrayStream out = new ByteArrayStream(len);
        try {writeBytesWithoutSig(out);}
        catch (IOException ioe) {
            ioe.printStackTrace();
            return null;
        }
        catch (DataFormatException dfe) {
            dfe.printStackTrace();
            return null;
        }
        byte rv[] = out.toByteArray();
        // if we are floodfill and this was published to us
        if (getReceivedAsPublished()) {_byteified = rv;}
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
        readHeader(in); // LS2 header
        _options = DataHelper.readProperties(in, null); // LS2 part - null arg to get an EmptyProperties back
        int numKeys = in.read();
        if (numKeys <= 0 || numKeys > MAX_KEYS) {throw new DataFormatException("Bad key count: " + numKeys);}
        if (numKeys > 1) {_encryptionKeys = new ArrayList<PublicKey>(numKeys);}
        for (int i = 0; i < numKeys; i++) {
            int encType = (int) DataHelper.readLong(in, 2);
            int encLen = (int) DataHelper.readLong(in, 2);
            // TODO
            if (encType == 0) {_encryptionKey = PublicKey.create(in);}
            else {
                EncType type = EncType.getByCode(encType);
                // type will be null if unknown
                byte[] encKey = new byte[encLen];
                DataHelper.read(in, encKey);
                // this will throw IAE if type is non-null and length is wrong
                if (type != null) {_encryptionKey = new PublicKey(type, encKey);}
                else {_encryptionKey = new PublicKey(encType, encKey);}
            }
            if (numKeys > 1) {_encryptionKeys.add(_encryptionKey);}
        }
        int numLeases = in.read();
        if (numLeases > MAX_LEASES) {
            throw new DataFormatException("Too many leases - max is " + MAX_LEASES);
        }
        for (int i = 0; i < numLeases; i++) {
            Lease lease = new Lease2();
            lease.readBytes(in);
            super.addLease(lease); // super to bypass overwrite of _expiration
        }
        // signature type depends on offline or not
        SigType type = isOffline() ? _transientSigningPublicKey.getType() : _destination.getSigningPublicKey().getType();
        _signature = new Signature(type);
        _signature.readBytes(in);
    }

    /**
     *  Including sig. This does NOT validate the signature
     */
    @Override
    public void writeBytes(OutputStream out) throws DataFormatException, IOException {
        if (_signature == null) {throw new DataFormatException("Not enough data to write out a LeaseSet");}
        writeBytesWithoutSig(out);
        _signature.writeBytes(out);
    }

    /**
     *  Without sig. This does NOT validate the signature
     */
    protected void writeBytesWithoutSig(OutputStream out) throws DataFormatException, IOException {
        if (_destination == null || _encryptionKey == null) {
            throw new DataFormatException("Not enough data to write out a LeaseSet");
        }
        writeHeader(out); // LS2 header
        writeBody(out); // LS2 body
    }

    /**
     *  Without sig. This does NOT validate the signature
     */
    protected void writeBody(OutputStream out) throws DataFormatException, IOException {
        if (_options != null && !_options.isEmpty()) {
            DataHelper.writeProperties(out, _options);
        } else {DataHelper.writeLong(out, 2, 0);}
        List<PublicKey> keys = getEncryptionKeys();
        out.write((byte) keys.size());
        for (PublicKey key : keys) {
            EncType type = key.getType();
            if (type != null) {DataHelper.writeLong(out, 2, type.getCode());}
            else {DataHelper.writeLong(out, 2, key.getUnknownTypeCode());}
            DataHelper.writeLong(out, 2, key.length());
            key.writeBytes(out);
        }
        out.write((byte) _leases.size());
        for (Lease lease : _leases) {lease.writeBytes(out);}
    }

    protected void readHeader(InputStream in) throws DataFormatException, IOException {
        _destination = Destination.create(in);
        _published = DataHelper.readLong(in, 4) * 1000;
        _expires = _published + (DataHelper.readLong(in, 2) * 1000);
        _flags = (int) DataHelper.readLong(in, 2);
        if (isOffline()) {readOfflineBytes(in);}
    }

    protected void writeHeader(OutputStream out) throws DataFormatException, IOException {
        _destination.writeBytes(out);
        if (_published <= 0) {setPublished(Clock.getInstance().now());}
        long pub1k = _published / 1000;
        DataHelper.writeLong(out, 4, pub1k);
        long exp = (_expires / 1000) - pub1k; // Divide separately to prevent rounding errors
        // writeLong() will throw if we try to write a negative, so preempt it with a better message
        // This will only happen on the client side for leasesets we create.
        if (exp < 0) {
            // During attacks (low build success), allow slight expiry to accommodate tunnel delays
            int buildSuccess = SystemVersion.getTunnelBuildSuccess();
            if (buildSuccess > 0 && buildSuccess < 35 && exp >= -10) {
                // Clamp to 0 during attacks if within 10 seconds expired
                exp = 0;
            } else {
                throw new DataFormatException("LeaseSet expired " + (0 - exp) + " seconds ago");
            }
        }
        DataHelper.writeLong(out, 2, exp);
        DataHelper.writeLong(out, 2, _flags);
        if (isOffline()) {writeOfflineBytes(out);}
    }

    protected void readOfflineBytes(InputStream in) throws DataFormatException, IOException {
        _transientExpires = DataHelper.readLong(in, 4) * 1000;
        int itype = (int) DataHelper.readLong(in, 2);
        SigType type = SigType.getByCode(itype);
        if (type == null) {throw new DataFormatException("Unknown Signature type " + itype);}
        _transientSigningPublicKey = new SigningPublicKey(type);
        _transientSigningPublicKey.readBytes(in);
        SigType stype = _destination.getSigningPublicKey().getType();
        _offlineSignature = new Signature(stype);
        _offlineSignature.readBytes(in);
    }

    protected void writeOfflineBytes(OutputStream out) throws DataFormatException, IOException {
        if (_transientSigningPublicKey == null || _offlineSignature == null) {
            throw new DataFormatException("No offline key/sig");
        }
        DataHelper.writeLong(out, 4, _transientExpires / 1000);
        DataHelper.writeLong(out, 2, _transientSigningPublicKey.getType().getCode());
        _transientSigningPublicKey.writeBytes(out);
        _offlineSignature.writeBytes(out);
    }

    /**
     *  Number of bytes, NOT including signature
     */
    @Override
    public int size() {
        int rv = _destination.size() + 10 + (_leases.size() * Lease2.LENGTH);
        for (PublicKey key : getEncryptionKeys()) {
            rv += 4;
            rv += key.length();
        }
        if (isOffline()) {
            rv += 6 + _transientSigningPublicKey.length() + _offlineSignature.length();
        }
        if (_options != null && !_options.isEmpty()) {
            try {rv += DataHelper.toProperties(_options).length;}
            catch (DataFormatException dfe) {throw new IllegalStateException("Bad options", dfe);}
        } else {rv += 2;}
        return rv;
    }

    /**
     * @param lease must be a Lease2
     * @throws IllegalArgumentException if not a Lease2
     */
    @Override
    public void addLease(Lease lease) {
        if (getType() == KEY_TYPE_LS2 && !(lease instanceof Lease2)) {
            throw new IllegalArgumentException();
        }
        super.addLease(lease);
        _expires = _lastExpiration;
    }

    /**
     * Sign the structure using the supplied signing key.
     * Overridden because LS2 sigs cover the type byte.
     *
     * @throws IllegalStateException if already signed
     */
    @Override
    public void sign(SigningPrivateKey key) throws DataFormatException {
        if (_signature != null) {throw new IllegalStateException();}
        if (key == null) {throw new DataFormatException("No Signing Key");}
        int len = size();
        ByteArrayStream out = new ByteArrayStream(1 + len);
        try {
            out.write(getType()); // unlike LS1, sig covers type
            writeBytesWithoutSig(out);
        } catch (IOException ioe) {throw new DataFormatException("Signature failed", ioe);}
        // now sign with the key
        _signature = out.sign(key);
        if (_signature == null) {
            throw new DataFormatException("Signature failed with " + key.getType() + " key");
        }
    }

    /**
     * Verify with the SPK in the dest for online sigs.
     * Verify with the SPK in the offline sig section for offline sigs.
     * @return valid
     */
    @Override
    public boolean verifySignature() {
        if (_signature == null) {return false;}
        // Disallow RSA as it's so slow it could be used as a DoS
        SigType type = _signature.getType();
        if (type == null || type.getBaseAlgorithm() == SigAlgo.RSA) {return false;}
        SigningPublicKey spk;
        if (isOffline()) {
            // verify LS2 using offline block's SPK
            // Disallow RSA as it's so slow it could be used as a DoS
            type = _transientSigningPublicKey.getType();
            if (type == null || type.getBaseAlgorithm() == SigAlgo.RSA || !verifyOfflineSignature()) {
                return false;
            }
            spk = _transientSigningPublicKey;
        } else {spk = getSigningPublicKey();}
        int len = size();
        ByteArrayStream out = new ByteArrayStream(1 + len);
        try {
            out.write(getType()); // unlike LS1, sig covers type
            writeBytesWithoutSig(out);
        } catch (IOException ioe) {
            ioe.printStackTrace();
            return false;
        } catch (DataFormatException dfe) {
            dfe.printStackTrace();
            return false;
        }
        return out.verifySignature(_signature, spk);
    }

    @Override
    public boolean equals(Object object) {
        if (object == this) return true;
        if ((object == null) || !(object instanceof LeaseSet2)) return false;
        LeaseSet2 ls = (LeaseSet2) object;
        return DataHelper.eq(_signature, ls.getSignature())
               && DataHelper.eq(_leases, ls._leases)
               && DataHelper.eq(getEncryptionKey(), ls.getEncryptionKey())
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
        if (_log.shouldInfo()) {
            buf.append("\nLeaseSet2: ");
            if (_destination != null) {
                buf.append("\n* Destination: ").append(_destination.toBase32());
            }
            List<PublicKey> keys = getEncryptionKeys();
            int sz = keys.size();
            if (sz > 1) {buf.append("\n* Encryption Keys: ").append(sz);}
            for (int i = 0; i < sz; i++) {
                buf.append("\n* ").append((sz > 1 ? "Key #" + (i+1) : "Encryption Key")).append(": ").append(keys.get(i));
            }
            if (isOffline()) {
                buf.append("\n* Transient Key: ").append(_transientSigningPublicKey);
                buf.append("\n* Transient Expiry: ").append(new java.util.Date(_transientExpires));
                buf.append("\n* Offline Signature: ").append(_offlineSignature);
            }
            buf.append("\n* Published: ").append(!isUnpublished());
            buf.append("\n* Published date: ").append(new java.util.Date(_published));
            if (isBlindedWhenPublished()) {buf.append("\n* Blinded: ").append(isBlindedWhenPublished());}
            buf.append("\n* Signature: ").append(_signature);
            buf.append("\n* Expires: ").append(new java.util.Date(_expires));
            buf.append("\n* Leases: ").append(getLeaseCount());
            for (int i = 0; i < getLeaseCount(); i++) {buf.append(getLease(i));}
            if (_options != null && _options.size() > 0) {
                buf.append("\nOptions: ").append(_options.size());
                for (Map.Entry<Object, Object> e : _options.entrySet()) {
                    String key = (String) e.getKey();
                    String val = (String) e.getValue();
                    buf.append("\n* ").append(key).append(": ").append(val);
                }
            }
        } else if (_log.shouldWarn() && _destination != null) {
            buf.append("\n* LeaseSet2: " + _destination.toBase32());
        }
        return buf.toString();
    }

    @Override
    public void encrypt(SessionKey key) {throw new UnsupportedOperationException();}

}

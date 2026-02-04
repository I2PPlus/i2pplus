package net.i2p.data;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Map;
import net.i2p.crypto.SigType;

/**
 * Implementation of MetaLeaseSet as specified in
 * <a href="https://geti2p.net/spec/proposals/123-new-netdb-entries">Proposal 123: New NetDb Entries</a>.
 *
 * <p>MetaLeaseSet is a specialized LeaseSet2 that contains references to other LeaseSets
 * rather than direct tunnel endpoints. This enables advanced routing and load balancing strategies.</p>
 *
 * <p>Key characteristics of MetaLeaseSet:</p>
 * <ul>
 *   <li>Contains {@link MetaLease} objects instead of regular {@link Lease} objects</li>
 *   <li>Does not support encryption keys (throws {@link UnsupportedOperationException})</li>
 *   <li>References other LeaseSets by hash rather than containing tunnel endpoints directly</li>
 *   <li>Supports offline signatures for reduced router load</li>
 *   <li>Includes options and statistics support</li>
 * </ul>
 *
 * <p><strong>Usage:</strong> MetaLeaseSets are used for:</p>
 * <ul>
 *   <li>Load balancing across multiple LeaseSets</li>
 *   <li>Geographic or performance-based routing decisions</li>
 *   <li>Fallback and redundancy strategies</li>
 *   <li>Service discovery and aggregation</li>
 * </ul>
 *
 * <p><strong>Implementation Status:</strong> PRELIMINARY - Subject to change as the proposal evolves</p>
 *
 * @since 0.9.38
 */
public class MetaLeaseSet extends LeaseSet2 {

    public MetaLeaseSet() {super();}

    ///// overrides below here

    @Override
    public int getType() {return KEY_TYPE_META_LS2;}

    /**
     *  @throws UnsupportedOperationException always
     */
    @Override
    public void setEncryptionKey(PublicKey key) {
        throw new UnsupportedOperationException();
    }

    /**
     *  @throws UnsupportedOperationException always
     */
    @Override
    public void addEncryptionKey(PublicKey key) {
        throw new UnsupportedOperationException();
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
        _options = DataHelper.readProperties(in, null); // Meta LS2 part - null arg to get an EmptyProperties back
        int numLeases = in.read();
        for (int i = 0; i < numLeases; i++) {
            Lease lease = new MetaLease();
            lease.readBytes(in);
            super.addLease(lease); // super to bypass overwrite of _expiration
        }
        int numRevokes = in.read();
        for (int i = 0; i < numRevokes; i++) {DataHelper.skip(in, 32);} // TODO
        // signature type depends on offline or not
        SigType type = isOffline() ? _transientSigningPublicKey.getType() : _destination.getSigningPublicKey().getType();
        _signature = new Signature(type);
        _signature.readBytes(in);
    }

    /**
     *  Without sig. This does NOT validate the signature
     */
    @Override
    protected void writeBytesWithoutSig(OutputStream out) throws DataFormatException, IOException {
        if (_destination == null) {
            throw new DataFormatException("Not enough data to write out a LeaseSet");
        }
        writeHeader(out); // LS2 header
        // Meta LS2 part
        if (_options != null && !_options.isEmpty()) {DataHelper.writeProperties(out, _options);}
        else {DataHelper.writeLong(out, 2, 0);}
        out.write(_leases.size());
        for (Lease lease : _leases) {lease.writeBytes(out);}
        // revocations
        out.write(0);
    }

    /**
     *  Number of bytes, NOT including signature
     */
    @Override
    public int size() {
        int rv = _destination.size() + 10 + (_leases.size() * MetaLease.LENGTH);
        if (isOffline()) {rv += 6 + _transientSigningPublicKey.length() + _offlineSignature.length();}
        if (_options != null && !_options.isEmpty()) {
            try {rv += DataHelper.toProperties(_options).length;}
            catch (DataFormatException dfe) {throw new IllegalStateException("Bad options", dfe);}
        } else {rv += 2;}
        return rv;
    }

    /**
     * @param lease must be a MetaLease
     * @throws IllegalArgumentException if not a MetaLease
     */
    @Override
    public void addLease(Lease lease) {
        if (!(lease instanceof MetaLease)) {throw new IllegalArgumentException();}
        super.addLease(lease);
        _expires = _lastExpiration;
    }

    @Override
    public boolean equals(Object object) {
        if (object == this) return true;
        if ((object == null) || !(object instanceof MetaLeaseSet)) return false;
        MetaLeaseSet ls = (MetaLeaseSet) object;

        return DataHelper.eq(_signature, ls.getSignature()) &&
               DataHelper.eq(_leases, ls._leases) &&
               DataHelper.eq(_destination, ls.getDestination());
    }

    /** the destination has enough randomness in it to use it by itself for speed */
    @Override
    public int hashCode() {return super.hashCode();}

    @Override
    public String toString() {
        StringBuilder buf = new StringBuilder(128);
        buf.append("\nMetaLeaseSet: ");
        buf.append("\n* Destination: ").append(_destination.toBase32());
        if (isOffline()) {
            buf.append("\n* Transient Key: ").append(_transientSigningPublicKey)
               .append("\n* Transient Expiry: ").append(new java.util.Date(_transientExpires))
               .append("\n* Offline Signature: ").append(_offlineSignature);
        }
        if (_options != null && _options.size() > 0) {
            buf.append("\nOptions: ").append(_options.size());
            for (Map.Entry<Object, Object> e : _options.entrySet()) {
                String key = (String) e.getKey();
                String val = (String) e.getValue();
                buf.append("\n* ").append(key).append(": ").append(val);
            }
        }
        buf.append("\n* Published: ").append(!isUnpublished() ? "yes" : "no")
           .append("\n* Published date: ").append(new java.util.Date(_published))
           .append("\n* Signature: ").append(_signature)
           .append("\n* Expires: ").append(new java.util.Date(_expires))
           .append("\n* Leases: ").append(getLeaseCount());
        for (int i = 0; i < getLeaseCount(); i++) {buf.append(getLease(i));}
        return buf.toString();
    }

}

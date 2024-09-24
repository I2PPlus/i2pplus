package net.i2p.data;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Map;

import net.i2p.crypto.SigType;

/**
 * PRELIMINARY - Subject to change - see proposal 123
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
            catch (DataFormatException dfe) {throw new IllegalStateException("bad options", dfe);}
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
        buf.append("\n* Destination: ").append(_destination);
        if (isOffline()) {
            buf.append("\n* Transient Key: ").append(_transientSigningPublicKey);
            buf.append("\n* Transient Expires: ").append(new java.util.Date(_transientExpires));
            buf.append("\n* Offline Signature: ").append(_offlineSignature);
        }
        buf.append("\n* Options: ").append((_options != null) ? _options.size() : 0);
        if (_options != null && !_options.isEmpty()) {
            for (Map.Entry<Object, Object> e : _options.entrySet()) {
                String key = (String) e.getKey();
                String val = (String) e.getValue();
                buf.append(" [").append(key).append("] = [").append(val).append("]");
            }
        }
        buf.append("\n* Unpublished? ").append(isUnpublished());
        buf.append("\n* Signature: ").append(_signature);
        buf.append("\n* Published: ").append(new java.util.Date(_published));
        buf.append("\n* Expires: ").append(new java.util.Date(_expires));
        buf.append("\n* Leases: ").append(getLeaseCount());
        for (int i = 0; i < getLeaseCount(); i++) {buf.append(getLease(i));}
        return buf.toString();
    }

}

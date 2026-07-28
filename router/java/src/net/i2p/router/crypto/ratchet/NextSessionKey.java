package net.i2p.router.crypto.ratchet;

import net.i2p.crypto.EncType;
import net.i2p.data.PublicKey;

/**
 * X25519 public key with identifier for next session key exchange in ratchet protocol
 *
 * @since 0.9.44
 */
class NextSessionKey extends PublicKey {
    private final int _id;
    private final boolean _isReverse;
    private final boolean _isRequest;

    /**
     * Create a NextSessionKey from raw key data.
     *
     * @param data non-null
     * @param id the session identifier
     * @param isReverse whether this is a reverse session
     * @param isRequest whether this is a request
     */
    public NextSessionKey(byte[] data, int id, boolean isReverse, boolean isRequest) {
        super(EncType.ECIES_X25519, data);
        _id = id;
        _isReverse = isReverse;
        _isRequest = isRequest;
    }

    /**
     * Create a NextSessionKey with null data, for acks/requests only.
     * Type will be ElG but doesn't matter.
     * Don't call setData().
     *
     * @param id the session identifier
     * @param isReverse whether this is a reverse session
     * @param isRequest whether this is a request
     * @since 0.9.46
     */
    public NextSessionKey(int id, boolean isReverse, boolean isRequest) {
        super();
        _id = id;
        _isReverse = isReverse;
        _isRequest = isRequest;
    }

    /**
     * @return the session identifier
     */
    public int getID() {
        return _id;
    }

    /**
     * @return true if this is a reverse session
     * @since 0.9.46
     */
    public boolean isReverse() {
        return _isReverse;
    }

    /**
     * @return true if this is a request
     * @since 0.9.46
     */
    public boolean isRequest() {
        return _isRequest;
    }

    /**
     *  @since 0.9.46
     * @return whether h code is present
     */
    @Override
    public int hashCode() {
        int rv = super.hashCode() ^ _id;
        if (_isReverse)
            rv ^= 1 << 31;
        if (_isRequest)
            rv ^= 1 << 30;
        return rv;
    }

    /**
     *  @since 0.9.46
     */
    @Override
    public boolean equals(Object obj) {
        if (obj == null)
            return false;
        if (!(obj instanceof NextSessionKey))
            return false;
        NextSessionKey o = (NextSessionKey) obj;
        return _id == o._id &&
               _isReverse == o._isReverse &&
               _isRequest == o._isRequest &&
               super.equals(o);
    }

    @Override
    public String toString() {
        return "\n* NextSessionKey: " + super.toString() + " ID: " + _id + " Reverse? " + _isReverse + " Request? " + _isRequest;
    }
}

package net.i2p.router.crypto.ratchet;

import net.i2p.crypto.EncType;
import net.i2p.data.PublicKey;

/**
 * X25519 public key with identifier for next session key exchange in ratchet protocol.
 *
 * @since 0.9.44
 */
class NextSessionKey extends PublicKey {
    /** Which key in the sequence this one is. */
    private final int _id;
    /** True for the key sent by the other side. */
    private final boolean _isReverse;
    /** True when this key is asked for, not offered. */
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
     * Which key in the sequence this one is.
     *
     * @return the id as carried on the wire, 0-65535
     */
    public int getID() {
        return _id;
    }

    /**
     * Whether this is a reverse session.
     *
     * @return true if this is a reverse session
     * @since 0.9.46
     */
    public boolean isReverse() {
        return _isReverse;
    }

    /**
     * Whether this is a request.
     *
     * @return true if this is a request
     * @since 0.9.46
     */
    public boolean isRequest() {
        return _isRequest;
    }

    /**
     * Hash code combining the id with the reverse and request flags.
     *
     * @return the hash code
     * @since 0.9.46
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
     * Equal if the ratchet and message number match.
     *
     * @since 0.9.46
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

    /**
     * The key as a log continuation line, labelled NextSessionKey. It opens
     * with the continuation separator so a caller can append a whole key to a
     * message; a caller that wants its own label on the key's line asks for
     * the labelled form, since a label written in front of this lands alone
     * on the line above the key.
     */
    @Override
    public String toString() {
        return toString(null);
    }

    /**
     * The key as a log continuation line under the caller's label, e.g.
     * `* Stored: ECIES_X25519 79jT5yX8 ID: 3 Reverse? false Request? true`.
     * The label replaces the default one so the label and the key share a
     * line, which is what keeps a three-key comparison from taking six. The
     * key is a prefix, not its 44 characters of base64: the point of printing
     * two keys is to see that they differ, and a whole key on every line
     * pushes each one past what the console wraps.
     *
     * @param label what the key is to the caller, e.g. "Stored"; null for the
     *              default label
     * @return the key as a ` * ` continuation line
     * @since 0.9.73+
     */
    String toString(String label) {
        return "\n* " + (label != null ? label : "NextSessionKey") + ": "
               + getType() + ' ' + shortKey()
               + " ID: " + _id + " Reverse? " + _isReverse + " Request? " + _isRequest;
    }

    /**
     * The key as eight characters of its base64, enough to tell two keys apart
     * in a log. Unbracketed: the console linkifier reads a bracketed eight
     * characters as a lease set, and this is a key, not a hash.
     *
     * @return the key prefix, or "no key data" for a request or ack with no key
     * @since 0.9.73+
     */
    String shortKey() {
        if (getData() == null) {
            return "no key data";
        }
        String b64 = toBase64();
        return b64.length() <= 8 ? b64 : b64.substring(0, 8);
    }

    /**
     * The received key beside the stored ones, as one continuation line, for
     * the warning that fires when an ack repeats a key ID with different data.
     * The caller's branch already fixes the direction (Reverse) and the ID of
     * the stored keys, so this names the type and ID once, on the received key,
     * and the stored keys as prefixes - three full key dumps would run to three
     * lines of 44-character base64 to say that two prefixes differ. When the
     * two stored keys are the same key, one Stored segment says it.
     *
     * @param received the key that arrived, non-null
     * @param stored the key stored for this ID, non-null
     * @param storedWithData the stored key that carries data, non-null
     * @return the comparison as one ` * ` continuation line
     * @since 0.9.73+
     */
    static String keyComparison(NextSessionKey received, NextSessionKey stored,
                                NextSessionKey storedWithData) {
        String rv = "\n* Received: " + received.getType() + ' ' + received.shortKey()
                    + " ID: " + received.getID()
                    + " * Stored: " + stored.shortKey();
        if (!stored.equals(storedWithData)) {
            rv += " * Stored with data: " + storedWithData.shortKey();
        }
        return rv;
    }
}

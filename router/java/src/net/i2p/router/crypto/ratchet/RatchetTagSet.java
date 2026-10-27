package net.i2p.router.crypto.ratchet;

import com.southernstorm.noise.protocol.DHState;
import com.southernstorm.noise.protocol.HandshakeState;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import net.i2p.I2PAppContext;
import net.i2p.crypto.EncType;
import net.i2p.crypto.HKDF;
import net.i2p.crypto.KeyPair;
import net.i2p.crypto.TagSetHandle;
import net.i2p.data.Base64;
import net.i2p.data.DataHelper;
import net.i2p.data.PublicKey;
import net.i2p.data.SessionKey;
import net.i2p.router.RouterContext;
import net.i2p.router.transport.crypto.X25519KeyFactory;
import net.i2p.util.Log;

/**
 * Manages session tags and keys for a single ratchet direction with automatic key generation, supporting both inbound lookahead and on-demand outbound operations
 * <p>
 * For outbound, uses very little memory. Tags and keys are generated on demand.
 * See proposal 144.
 * <p>
 * For inbound, generates tags in advance, maintaining minSize lookahead.
 * Keys are generated as required.
 * <p>
 * Caller must synch on all methods.
 *
 * @since 0.9.44
 */
class RatchetTagSet implements TagSetHandle {
    /**
     * Cached once. A tag miss is reachable from remote input, so looking the
     * logger up through the context on every miss is work an attacker can drive.
     */
    private static final Log _log =
        I2PAppContext.getGlobalContext().logManager().getLog(RatchetTagSet.class);

    /** Listener notified when a tag is consumed. */
    private final SessionTagListener _lsnr;
    /** The peer's ratchet public key. */
    private final PublicKey _remoteKey;
    /** The session key. */
    protected final SessionKey _key;
    /** Handshake state, set for NSR only. */
    private final HandshakeState _state;
    /** The tags by index, resolved through this map. */
    private final SparseArray<RatchetSessionTag> _sessionTags;
    /** Derived keys by index, stored as byte arrays. */
    private final SparseArray<byte[]> _sessionKeys;
    /** Key derivation function for the ratchet steps. */
    private final HKDF hkdf;
    /** When the tag set was created. */
    private final long _created;
    /** When the tag set expires. */
    private final long _timeout;
    /** When the tag set was last used. */
    private long _date;
    /** Id of the ratchet step this set belongs to. */
    private final int _id;
    /** Id of the ratchet key this set derives from. */
    private final int _keyid;
    /** Number of tags when the set was created. */
    private final int _originalSize;
    /** Number of tags the set can hold. */
    private final int _maxSize;
    /** True once the peer has acked the set. */
    private boolean _acked;
    /** Root key for the next receive chain. */
    private final byte[] _nextRootKey;
    /** Chaining key for the session tag chain. */
    private final byte[] _sesstag_ck;
    /** Constant mixed into the session tag derivation. */
    private final byte[] _sesstag_constant;
    /** Chaining key for the symmetric key chain. */
    private final byte[] _symmkey_ck;
    /** Constant mixed into the symmetric key derivation. */
    private final byte[] _symmkey_constant;
    /** Index of the last tag consumed, -1 if none. */
    private int _lastTag = -1;
    /** Index of the last key derived, -1 if none. */
    private int _lastKey = -1;
    /** Our next ratchet key pair. */
    private KeyPair _nextKeys;
    /** Our next key, sent ahead of use. */
    private NextSessionKey _nextKey;
    /** For debugging. */
    private static final AtomicInteger __tagSetID = new AtomicInteger();
    /** Unique id of this set for the logs. */
    private final int _tagSetID = __tagSetID.incrementAndGet();

    /** HKDF info string for the ratchet step. */
    private static final String INFO_1 = "KDFDHRatchetStep";
    /** HKDF info string for the tag and key generation. */
    private static final String INFO_2 = "TagAndKeyGenKeys";
    /** HKDF info string for the session tag init. */
    private static final String INFO_3 = "STInitialization";
    /** HKDF info string for the session tag key generation. */
    private static final String INFO_4 = "SessionTagKeyGen";
    /** HKDF info string for the symmetric ratchet. */
    private static final String INFO_5 = "SymmetricRatchet";
    /** Shared empty array for zero-length values. */
    private static final byte[] ZEROLEN = new byte[0];
    /** Largest ratchet index value. */
    private static final int MAX = 65535;
    /** Debug flag for extra ratchet logging. */
    private static final boolean DEBUG = false;
    /** Test flag for the ratchet wrap-around values. */
    private static final boolean TEST_RATCHET = false;
    /** Index below which the tag set is running low. */
    private static final int LOW = TEST_RATCHET ? (MAX - 512) : (MAX - 4096);
    /** Debug flag for outbound New Session Requests. */
    static final int DEBUG_OB_NSR = 0x10001;
    /** Debug flag for inbound New Session Requests. */
    static final int DEBUG_IB_NSR = 0x10002;
    /** Debug flag for a single encrypted session. */
    static final int DEBUG_SINGLE_ES = 0x10003;
    /** Initial capacity of the derived key array. */
    private static final int INITIAL_KEY_CAPACITY = 0;
    /**
     * Tags registered in the SKM's _inboundTagSets map.
     * Used for O(tags_in_set) removal in {@link net.i2p.router.crypto.ratchet.RatchetSKM#consumeTag} instead of
     * O(total_tags) scan of the entire _inboundTagSets map.
     * Only populated for inbound tagsets (where _lsnr != null).
     * Pruned as the ratchet advances and when the handshake finishes, so it
     * tracks the live window instead of every tag the session ever generated.
     *
     * @since 0.9.70
     */
    private final Set<RatchetSessionTag> _registeredTags = ConcurrentHashMap.newKeySet();
    /**
     * What each tag in _sessionTags is numbered by, so consume() can find a
     * presented tag in constant time. Without it every inbound ES message scans
     * the whole receive window comparing tags - up to 320 equals() calls per
     * message. Maintained alongside _sessionTags: every append, removal and
     * clear does both, so a tag in one is in the other.
     */
    private final Map<RatchetSessionTag, Integer> _tagNumbers = new ConcurrentHashMap<>(32);

    /**
     * Outbound NSR Tagset.
     *
     * @param hkdf the HKDF used to derive the next root, tag and symmetric keys
     * @param state the handshake state
     * @param rootKey the root key
     * @param data the data key
     * @param date For outbound: creation time
     */
    public RatchetTagSet(HKDF hkdf, HandshakeState state, SessionKey rootKey, SessionKey data,
                         long date) {
        this(hkdf, null, state, null, rootKey, data, date, RatchetSKM.SESSION_PENDING_DURATION_MS, DEBUG_OB_NSR, -2, false, 0, 0);
    }

    /**
     * Outbound ES Tagset.
     *
     * @param hkdf the HKDF used to derive the next root, tag and symmetric keys
     * @param rootKey the root key
     * @param data the data key
     * @param date For outbound: creation time
     * @param tagsetid the tagset ID
     * @param keyid the key ID
     */
    public RatchetTagSet(HKDF hkdf, SessionKey rootKey, SessionKey data,
                         long date, int tagsetid, int keyid) {
        this(hkdf, null, null, null, rootKey, data, date, RatchetSKM.SESSION_TAG_DURATION_MS, tagsetid, keyid, false, 0, 0);
    }

    /**
     * Inbound NSR Tagset.
     *
     * @param hkdf the HKDF used to derive the next root, tag and symmetric keys
     * @param lsnr the session tag listener
     * @param state the handshake state
     * @param rootKey the root key
     * @param data the data key
     * @param date For inbound: creation time
     * @param minSize the minimum tag set size
     * @param maxSize the maximum tag set size
     */
    public RatchetTagSet(HKDF hkdf, SessionTagListener lsnr, HandshakeState state, SessionKey rootKey, SessionKey data,
                         long date, int minSize, int maxSize) {
        this(hkdf, lsnr, state, null, rootKey, data, date, RatchetSKM.SESSION_PENDING_DURATION_MS, DEBUG_IB_NSR, -2, true, minSize, maxSize);
    }

    /**
     * Inbound ES Tagset.
     *
     * @param hkdf the HKDF used to derive the next root, tag and symmetric keys
     * @param lsnr the session tag listener
     * @param remoteKey the remote public key
     * @param rootKey the root key
     * @param data the data key
     * @param date For inbound: creation time
     * @param tagsetid the tagset ID
     * @param keyid the key ID
     * @param minSize the minimum tag set size
     * @param maxSize the maximum tag set size
     */
    public RatchetTagSet(HKDF hkdf, SessionTagListener lsnr,
                         PublicKey remoteKey, SessionKey rootKey, SessionKey data,
                         long date, int tagsetid, int keyid, int minSize, int maxSize) {
        this(hkdf, lsnr, null, remoteKey, rootKey, data, date, RatchetSKM.SESSION_LIFETIME_MAX_MS, tagsetid, keyid, true, minSize, maxSize);
    }

    /**
     * Tag set for a ratchet session with the given keys and sizes.
     *
     * @param hkdf the HKDF used to derive the next root, tag and symmetric keys
     * @param lsnr the session tag listener
     * @param state the handshake state
     * @param remoteKey the remote public key
     * @param rootKey the root key
     * @param data the data key
     * @param date For inbound and outbound: creation time
     * @param timeout the timeout duration
     * @param tagsetid the tagset ID
     * @param keyid the key ID
     * @param isInbound whether this is an inbound tagset
     * @param minSize the minimum tag set size
     * @param maxSize the maximum tag set size
     */
    private RatchetTagSet(HKDF hkdf, SessionTagListener lsnr, HandshakeState state,
                          PublicKey remoteKey, SessionKey rootKey, SessionKey data,
                          long date, long timeout, int tagsetid, int keyid, boolean isInbound, int minSize, int maxSize) {
        _lsnr = lsnr;
        _state = state;
        _remoteKey = remoteKey;
        _key = rootKey;
        _created = date;
        _timeout = timeout;
        _date = date;
        _id = tagsetid;
        _keyid = keyid;
        _originalSize = minSize;
        _maxSize = maxSize;
        _nextRootKey = new byte[32];
        byte[] ck = new byte[32];
        _sesstag_ck = new byte[32];
        _sesstag_constant = new byte[32];
        _symmkey_ck = new byte[32];
        _symmkey_constant = ZEROLEN;
        this.hkdf = hkdf;
        hkdf.calculate(rootKey.getData(), data.getData(), INFO_1, _nextRootKey, ck, 0);
        hkdf.calculate(ck, ZEROLEN, INFO_2, _sesstag_ck, _symmkey_ck, 0);
        hkdf.calculate(_sesstag_ck, ZEROLEN, INFO_3, _sesstag_ck, _sesstag_constant, 0);
        if (isInbound) {
            _sessionTags = new SparseArray<>(minSize);
            if (state == null)
                _sessionKeys = new SparseArray<>(INITIAL_KEY_CAPACITY);
            else
                _sessionKeys = null;
            for (int i = 0; i < minSize; i++) {
               storeNextTag();
            }
        } else {
            _sessionTags = null;
            _sessionKeys = null;
        }
        // prevent adjusted expiration and search for matching OB ts
        if (tagsetid > 0 && tagsetid <= 65535)
            _acked = true;
    }

    /**
     * For SingleTagSet.
     *
     * @param lsnr the session tag listener
     * @param rootKey the root key
     * @param date the creation time
     * @param timeout the timeout duration
     * @since 0.9.46
     */
    protected RatchetTagSet(SessionTagListener lsnr, SessionKey rootKey, long date, long timeout) {
        _lsnr = lsnr;
        _state = null;
        _remoteKey = null;
        _key = rootKey;
        _created = date;
        _timeout = timeout;
        _date = date;
        _id = DEBUG_SINGLE_ES;
        _keyid = -3;
        _originalSize = 1;
        _maxSize = 1;
        _nextRootKey = null;
        _sesstag_ck = null;
        _sesstag_constant = null;
        _symmkey_ck = null;
        _symmkey_constant = null;
        hkdf = null;
        _sessionTags = null;
        _sessionKeys = null;
        // prevent adjusted expiration and search for matching OB ts
        _acked = true;
    }

    /** Clear all tags and keys. */
    public void clear() {
        if (_sessionTags != null)
            _sessionTags.clear();
        if (_sessionKeys != null)
            _sessionKeys.clear();
        _tagNumbers.clear();
    }

    /**
     * The far-end's public key.
     * Valid for NSR and inbound ES tagsets.
     *
     * @return the remote key, or null for outbound ES tagsets
     */
    public PublicKey getRemoteKey() {
        if (_state != null) {
            DHState kp = _state.getRemotePublicKey();
            if (kp != null) {
                byte[] rv = new byte[32];
                kp.getPublicKey(rv, 0);
                return new PublicKey(EncType.ECIES_X25519, rv);
            }
        }
        return _remoteKey;
    }

    /**
     * The root key for the tag set.
     * Used to match the OB and IB ES tagset 0, where both
     * will have the same root key.
     * Not used for cryptographic operations after setup.
     *
     * @return the associated key
     */
    public SessionKey getAssociatedKey() {
        return _key;
    }

    /**
     * For inbound/outbound NSR only, else null.
     * MUST be cloned before processing NSR.
     *
     * @return the handshake state, or null
     */
    public HandshakeState getHandshakeState() {
        return _state;
    }

    /**
     * For inbound and outbound: last used time
     * Expiration is {@link #getDate()} + getTimeout().
     *
     * @return the last used time
     */
    public long getDate() {
        return _date;
    }

    /**
     * For inbound and outbound: set last used time.
     *
     * @param when the last used time
     */
    public void setDate(long when) {
        _date = when;
    }

    /**
     * For inbound and outbound: creation time, for debugging only.
     *
     * @return the creation time
     */
    public long getCreated() {
        return _created;
    }

    /**
     * For inbound and outbound: Idle timeout interval.
     * Expiration is getDate() + {@link #getTimeout()}.
     *
     * @return the timeout duration
     * @since 0.9.46
     */
    public long getTimeout() {
        return _timeout;
    }

    /**
     * For inbound and outbound: Expiration.
     * Expiration is getDate() + getTimeout() if acked.
     * Before acked, uses _created + _timeout (full lifetime).
     * <p>
     * Previously this used {@link java.lang.Math#min}_timeout, SESSION_PENDING_DURATION_MS)
     * which capped unacked ES tagsets at 3 minutes instead of their designed
     * 10-minute SESSION_LIFETIME_MAX_MS. With {@code i2cp.disableLoopback}=true,
     * the NSR round-trip through the full tunnel path could exceed 3 minutes,
     * causing tags to expire before the first ES message arrived.
     * <p>
     * NSR tagsets are unaffected — they have _timeout = SESSION_PENDING_DURATION_MS
     * (3 min), so the min() was a no-op for them.
     *
     * @return the expiration time
     * @since 0.9.46
     */
    public synchronized long getExpiration() {
        if (_acked)
            return _date + _timeout;
        return _created + _timeout;
    }

    /**
     * Unused tags generated.
     *
     * @return 0 for outbound
     */
    public synchronized int size() {
        return _sessionTags != null ? _sessionTags.size() : 0;
    }

    /**
     * Tags remaining.
     *
     * @return 0 - 65536
     */
    public synchronized int remaining() {
        int nextKey = _lastTag + 1;
        if (_sessionTags != null) {
            // IB
            nextKey -= _sessionTags.size();
        } else {
            // OB
        }
        return MAX + 1 - nextKey;
    }

    /**
     * Next Forward Key if applicable (outbound ES and we're running low).
     * Null if NSR or inbound or remaining is sufficient.
     * Once non-null, will be constant for the remaining life of the tagset.
     *
     * @return key or null
     * @since 0.9.46
     */
    public NextSessionKey getNextKey() {
        if (_sessionTags != null || _state != null || remaining() > LOW)
            return null;
        if (_nextKey == null) {
            boolean isFirst = _id == 0;
            if (isFirst || (_id & 0x01) != 0) {
                // new keys only needed first time and odd times
                _nextKeys = nextKeys(I2PAppContext.getGlobalContext());
                _nextKey = new NextSessionKey(_nextKeys.getPublic().getData(), _keyid + 1, false, isFirst);
            } else {
                // even times, just send old ID
                _nextKey = new NextSessionKey(_keyid, false, true);
            }
        }
        return _nextKey;
    }

    /**
     * A fresh XDHE key pair to carry in the next forward key.
     * <p>
     * Takes a precalculated pair from the running router when there is one.
     * Outside a router - tests, tools, early startup - the global context is
     * not a RouterContext and commSystem() has no factory, so the pair is
     * generated on the spot, which is what the factory itself does when its
     * queue runs dry.
     *
     * @param ctx the context to work from, normally the global context
     * @return an X25519 key pair, never null
     * @since 0.9.72+
     */
    static KeyPair nextKeys(I2PAppContext ctx) {
        if (ctx instanceof RouterContext) {
            X25519KeyFactory factory = ((RouterContext) ctx).commSystem().getXDHFactory();
            if (factory != null)
                return factory.getKeys();
        }
        return ctx.keyGenerator().generatePKIKeys(EncType.ECIES_X25519);
    }

    /**
     * Next Forward KeyPair if applicable (we're running low).
     * Null if remaining is sufficient.
     * Once non-null, will be constant for the remaining life of the tagset.
     *
     * @return keys or null
     * @since 0.9.46
     */
    public KeyPair getNextKeys() {
        return _nextKeys;
    }

    /**
     * Root key for the next DH ratchet.
     * Should only be needed for ES, but valid for NSR also.
     *
     * @return key
     * @since 0.9.46
     */
    public SessionKey getNextRootKey() {
        return new SessionKey(_nextRootKey);
    }

    /**
     * Inbound only.
     *
     * @param tag the message tag presented by the inbound message, looked up in the
     *            set and removed once found
     * @return associated SessionKey or null if not found.
     */
    public SessionKeyAndNonce consume(RatchetSessionTag tag) {
        if (_sessionTags == null)
            throw new IllegalStateException("Outbound tagset");
        // Constant-time lookup in the side index, then the array is searched by
        // key, which is a binary search on a sorted SparseArray.
        Integer num = _tagNumbers.get(tag);
        if (num == null) {
            if (_log.shouldWarn())
                _log.warn("Tag not found " + tag.toBase64());
            return null;
        }
        _acked = true;
        int tagnum = num.intValue();
        int idx = _sessionTags.indexOfKey(tagnum);
        if (idx >= 0)
            _sessionTags.removeAt(idx);
        _tagNumbers.remove(tag);
        // The caller took this tag out of the SKM's inbound map before asking us
        // for the key, so it is unreachable there already. Drop it from the index
        // as well, or the index keeps every tag the session ever spent and the
        // SKM's two removal loops re-remove them for the rest of the session.
        _registeredTags.remove(tag);

        // NSR
        if (_state != null) {
            addTags(tagnum);
            return new SessionKeyAndNonce(_state);
        }

        // ES
        // now get the key
        int kidx = _sessionKeys.indexOfKey(tagnum);
        if (kidx >= 0) {
            // already calculated
            byte[] rv = _sessionKeys.valueAt(kidx);
            _sessionKeys.removeAt(kidx);
            addTags(tagnum);
            return new SessionKeyAndNonce(rv, _id, tagnum, _remoteKey);
        } else if (tagnum > _lastKey) {
            // if there's any gaps, catch up and store
            for (int i = _lastKey + 1; i < tagnum; i++) {
                _sessionKeys.append(i, consumeNextKey().getData());
            }
            SessionKeyAndNonce rv = consumeNextKey();
            addTags(tagnum);
            return rv;
        } else {
            // dup or some other error
            if (_log.shouldWarn())
                _log.warn("No key found for tag " + tag.toBase64() + " at index " + idx +
                          " * tagnum = " + tagnum + " * lastkey = " + _lastKey);
            return null;
        }
    }

    /**
     * Inbound only.
     *
     * @param usedTagNumber the tag number the peer just used, which drives the window size
     */
    private void addTags(int usedTagNumber) {
        int lookAhead;
        int trimBehind;
        if (_maxSize > _originalSize) {
            // grow from originalSize at N = 0 to
            // maxSize at N = 2 * (maxSize - originalSize)
            // for typical loss rates, this keeps us at about maxSize,
            // but worst case about maxSize * 2
            lookAhead = Math.min(_maxSize, _originalSize + (usedTagNumber / 2));
            trimBehind = lookAhead;
        } else {
            lookAhead = _originalSize;
            trimBehind = _originalSize;
        }

        // add as many as we need to maintain minSize from the tag used
        int remaining = _lastTag - usedTagNumber;
        int toAdd = lookAhead - remaining;
        if (toAdd > 0) {
            for (int i = 0; i < toAdd; i++) {
                storeNextTag();
            }
        }

        // trim any too far behind
        int tooOld = usedTagNumber - trimBehind;
        if (tooOld > 0) {
            int toTrim = 0;
            int tagnum;
            while ((tagnum = _sessionTags.keyAt(toTrim)) < tooOld) {
                if (_sessionKeys != null) {
                    // only for ES tagsets
                    int kidx = _sessionKeys.indexOfKey(tagnum);
                    if (kidx >= 0)
                        _sessionKeys.removeAt(kidx);
                }
                RatchetSessionTag old = _sessionTags.valueAt(toTrim);
                if (_lsnr != null)
                    _lsnr.expireTag(old, this);
                // The SKM no longer maps this tag, so it is no longer reachable
                // and nothing will ever consume it. Leaving it in the index only
                // grows it - the two consumers in RatchetSKM iterate the whole
                // set, and an ES tagset generates a fresh tag for every one it
                // spends, so an unpruned index reaches roughly the lifetime
                // total rather than the live window.
                _registeredTags.remove(old);
                _tagNumbers.remove(old);
                toTrim++;
            }
            if (toTrim > 0)
                _sessionTags.removeAtRange(0, toTrim);
        }
    }

    /**
     * Inbound only.
     */
    private void storeNextTag() {
        RatchetSessionTag tag = consumeNext();
        if (tag == null)
            return;
        _sessionTags.append(_lastTag, tag);
        _tagNumbers.put(tag, _lastTag);
        if (_lsnr != null) {
            _registeredTags.add(tag);
            _lsnr.addTag(tag, this);
        }
    }

    /**
     * Return the set of tags registered in the SKM's _inboundTagSets map.
     * Used for O(tags_in_set) removal in {@link net.i2p.router.crypto.ratchet.RatchetSKM#consumeTag}.
     * <p>
     * Live view only: a tag leaves this set when the SKM expires it, so its size
     * tracks the ratchet window, not the number of tags the session has used.
     *
     * @return the tags this set currently has registered with the session key manager
     * @since 0.9.70
     */
    Set<RatchetSessionTag> getRegisteredTags() {
        return _registeredTags;
    }

    /**
     * Forget every tag this set registered with the SKM, for a caller that has
     * just removed them all from _inboundTagSets - handshake completion or a
     * stale-session cleanup. Without this the tags of a finished handshake stay
     * reachable for the rest of the tagset's life.
     *
     * @since 0.9.72+
     */
    void clearRegisteredTags() {
        _registeredTags.clear();
    }

    /**
     * Public for outbound only. Used internally for inbound.
     * Call before {@link #consumeNextKey()};
     *
     * @return a tag or null if we ran out
     */
    public RatchetSessionTag consumeNext() {
        if (_lastTag >= MAX)
            return null;
        byte[] tmp = new byte[32];
        hkdf.calculate(_sesstag_ck, _sesstag_constant, INFO_4, _sesstag_ck, tmp, 0);
        _lastTag++;
        return new RatchetSessionTag(tmp);
    }

    /**
     * For outbound, call after {@link #consumeNext()}.
     * Also called by {@link #consume} to catch up for inbound.
     *
     * @return a key and nonce, non-null
     */
    public SessionKeyAndNonce consumeNextKey() {
        // NSR
        if (_state != null)
            return new SessionKeyAndNonce(_state);
        // ES
        byte[] key = new byte[32];
        hkdf.calculate(_symmkey_ck, _symmkey_constant, INFO_5, _symmkey_ck, key, 0);
        _lastKey++;
        // for outbound, set acked
        if (_sessionTags == null && _lastKey == 0)
            _acked = true;
        // fill in ID and remoteKey as this may be for inbound
        return new SessionKeyAndNonce(key, _id, _lastKey, _remoteKey);
    }

    /**
     * For inbound, returns true after first consume() call.
     * For outbound, returns true after first consumeNextKey() call.
     *
     * @return true if acked
     */
    public boolean getAcked() { return _acked; }

    /**
     * The TagSet ID, starting at 0.
     * After that = 1 + my key id + his key id.
     *
     * @return the tagset ID
     */
    public int getID() {
        return _id;
    }

    /**
     * A unique ID for debugging only.
     *
     * @return the debug ID
     */
    public int getDebugID() {
        return _tagSetID;
    }

    /**
     * Debug string for this tag set.
     */
    @Override
    public synchronized String toString() {
        StringBuilder buf = new StringBuilder(256);
        if (_sessionTags != null)
            buf.append("Inbound ");
        else
            buf.append("Outbound ");
        if (_state != null)
            buf.append("NSR ");
        else
            buf.append("ES ");
        buf.append("TagSet #").append(_tagSetID)
           .append(" ID #").append(_id)
           .append("\n* Created: ").append(DataHelper.formatTime(_created))
           .append(" * Last used: ").append(DataHelper.formatTime(_date));
        PublicKey pk = getRemoteKey();
        if (pk != null && !pk.toString().trim().isEmpty())
            buf.append("\n* Remote Public Key: ").append(pk.toBase64());
        buf.append("\n* Root Key: ").append(_key.toBase64());
        if (_nextKey != null && !_nextKey.toString().trim().isEmpty())
            buf.append(_nextKey);
        int sz = size();
        buf.append("\n* Size: ").append(sz)
           .append(" * Original size: ").append(_originalSize)
           .append(" * Max size: ").append(_maxSize)
           .append(" * Remaining: ").append(remaining())
           .append(" * Acked: ").append(_acked ? "Yes" : "No");
        if (_sessionTags != null) {
            for (int i = 0; i < sz; i++) {
                int n = _sessionTags.keyAt(i);
                RatchetSessionTag tag = _sessionTags.valueAt(i);
                if (tag == null)
                    continue;
                buf.append("\n* Session Tag ").append(n).append(": ").append(tag.toBase64());
                if (_sessionKeys != null) {
                    byte[] key = _sessionKeys.get(n);
                    if (key != null) {
                        buf.append("\n* Session Key: ").append(Base64.encode(key));
                    } else {
                        buf.append("\n* Session Key: TBD");
                        // set DEBUG if you want to see them all
                        if (!DEBUG) {
                            buf.append(" (").append(sz - (i +1)).append(" more)");
                            break;
                        }
                    }
                }
            }
        }
        return buf.toString();
    }

}

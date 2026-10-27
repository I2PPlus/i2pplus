package net.i2p.router.crypto.ratchet;

import com.southernstorm.noise.protocol.HandshakeState;
import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.LongAdder;
import net.i2p.crypto.EncType;
import net.i2p.crypto.HKDF;
import net.i2p.crypto.KeyPair;
import net.i2p.crypto.SessionKeyManager;
import net.i2p.crypto.TagSetHandle;
import net.i2p.data.Base64;
import net.i2p.data.DataHelper;
import net.i2p.data.Destination;
import net.i2p.data.Hash;
import net.i2p.data.PrivateKey;
import net.i2p.data.PublicKey;
import net.i2p.data.SessionKey;
import net.i2p.data.SessionTag;
import net.i2p.router.RouterContext;
import net.i2p.router.util.DecayingHashSet;
import net.i2p.util.Log;
import net.i2p.util.SimpleTimer2;
import net.i2p.util.Translate;

/**
 *
 * Session key manager for ratchet encryption.
 *
 * @since 0.9.44
 */
public class RatchetSKM extends SessionKeyManager implements SessionTagListener {
    /** Logger for ratchet session management. */
    private final Log _log;
    /** Map allowing us to go from the targeted PublicKey to the OutboundSession used. */
    private final ConcurrentHashMap<PublicKey, OutboundSession> _outboundSessions;
    /** Outbound sessions waiting for a reply, by key. */
    private final HashMap<PublicKey, List<OutboundSession>> _pendingOutboundSessions;
    /** Map allowing us to go from a SessionTag to the containing RatchetTagSet. */
    private final ConcurrentHashMap<RatchetSessionTag, RatchetTagSet> _inboundTagSets;
    /**
     * Lifetime counters for the debug page, incremented only at
     * low-frequency events (session add/replace, inbound set completion,
     * tag lookup miss), never per tag.
     */
    private final LongAdder _sessionsCreated = new LongAdder();
    /** Sessions replaced by a newer one. */
    private final LongAdder _sessionsReplaced = new LongAdder();
    /** Inbound handshakes completed. */
    private final LongAdder _inboundCompleted = new LongAdder();
    /** Tag mismatches seen. */
    private final LongAdder _tagMisses = new LongAdder();
    /** The router context. */
    protected final RouterContext _context;
    /** False once the manager has shut down. */
    private volatile boolean _alive;
    /** The queued cleanup event, kept for cancellation. */
    private CleanupEvent _cleanupEvent;
    /** Key derivation function for the ratchet tags. */
    private final HKDF _hkdf;
    /** Filter rejecting replayed messages. */
    private final DecayingHashSet _replayFilter;
    /** The destination these sessions serve. */
    private final Destination _destination;
    /** The encryption type these sessions use. */
    private final EncType _type;

    /**
     * Let outbound session tags sit around for this long before expiring them.
     * Inbound tag expiration is set by SESSION_LIFETIME_MAX_MS.
     */
    static final long SESSION_TAG_DURATION_MS = 8 * 60 * 1000L;

    /**
     * How long unused inbound session tags are kept.
     * A few minutes longer than the outbound tag lifetime, so no reasonable
     * network lag can cause failed decrypts.
     * <p>
     * This is also the session idle timeout used by TransientSessionKeyManager.
     */
    static final long SESSION_LIFETIME_MAX_MS = SESSION_TAG_DURATION_MS + 2 * 60 * 1000L;

    /** How long a session stays pending in ms. */
    static final long SESSION_PENDING_DURATION_MS = 3 * 60 * 1000L;
    /** Age after which a session created earlier is replaced when a new NS arrives. */
    private static final long SESSION_REPLACE_AGE = 2*60*1000L;

    /** Shared empty array for zero-length values. */
    private static final byte[] ZEROLEN = new byte[0];

    /**
     * For the router SKM only.
     *
     * @param context the router context
     * @since 0.9.48
     */
    public RatchetSKM(RouterContext context) {
        this(context, null, EncType.ECIES_X25519);
    }

    /**
     * ECIES only.
     *
     * @param context the router context
     * @param dest null for router's SKM only
     * @since 0.9.48
     */
    public RatchetSKM(RouterContext context, Destination dest) {
        this(context, dest, EncType.ECIES_X25519);
    }

    /**
     * The session key manager is constructed and accessed through the
     * client manager.
     *
     * @param context the router context
     * @param dest null for router's SKM only
     * @param type the encryption type
     * @since 0.9.67
     */
    public RatchetSKM(RouterContext context, Destination dest, EncType type) {
        super(context);
        _log = context.logManager().getLog(RatchetSKM.class);
        _context = context;
        _destination = dest;
        _type = type;
        _outboundSessions = new ConcurrentHashMap<>(64);
        _pendingOutboundSessions = new HashMap<>(64);
        _inboundTagSets = new ConcurrentHashMap<>(128);
        _hkdf = new HKDF(context);
        _replayFilter = new DecayingHashSet(context, (int) ECIESAEADEngine.MAX_NS_AGE, 32, "Ratchet-NS");
        // start the precalc of Elg2 keys if it wasn't already started
        context.eciesEngine().startup();
         _alive = true;
        _cleanupEvent = new CleanupEvent();
    }

    /**
     * Cannot be restarted.
     */
    @Override
    public void shutdown() {
         _alive = false;
        if (_cleanupEvent != null) _cleanupEvent.cancel();
        _inboundTagSets.clear();
        _outboundSessions.clear();
        synchronized (_pendingOutboundSessions) {
            _pendingOutboundSessions.clear();
        }
        _replayFilter.stopDecaying();
    }

    /** Periodically cleans up stale ratchet sessions. */
    private class CleanupEvent extends SimpleTimer2.TimedEvent {
        /** Creates cleanup timer with initial delay for first expiration pass. */
        public CleanupEvent() {
            // wait until first expiration time to start
            super(_context.simpleTimer2(), SESSION_PENDING_DURATION_MS);
        }
        /** Perform recurring maintenance. */

        public void timeReached() {
            if (!_alive)
                return;
            aggressiveExpire();
            schedule(60*1000L);
        }
    }

    /**
     * The local destination for this SKM.
     *
     * @return our destination or null for the router SKM
     * @since 0.9.46
     */
    public Destination getDestination() {
        return _destination;
    }

    /**
     * The encryption type for this session key manager.
     *
     * @return the encryption type
     * @since 0.9.44
     */
    public EncType getType() {
        return _type;
    }

    /**
     * A snapshot of all inbound ratchet tag sets.
     *
     * @return a new set, safe to iterate
     */
    private Set<RatchetTagSet> getRatchetTagSets() {
        synchronized (_inboundTagSets) {
            return new HashSet<>(_inboundTagSets.values());
        }
    }

    /**
     * OutboundSession - used only by HTML.
     *
     * @return a snapshot copy of the current outbound sessions, never null
     */
    private Set<OutboundSession> getOutboundSessions() {
        return new HashSet<>(_outboundSessions.values());
    }

    /**
     * Not supported for this key manager.
     *
     * @return the current key
     * @throws UnsupportedOperationException always; the ratchet key manager does not support this
     */
    @Override
    public SessionKey getCurrentKey(PublicKey target) {
        throw new UnsupportedOperationException();
    }

    /**
     * Not supported for this key manager.
     *
     * @return the current or new key
     * @throws UnsupportedOperationException always; the ratchet key manager does not support this
     */
    @Override
    public SessionKey getCurrentOrNewKey(PublicKey target) {
        throw new UnsupportedOperationException();
    }

    /**
     * Not supported for this key manager.
     *
     * @throws UnsupportedOperationException always; the ratchet key manager does not support this
     */
    @Override
    public void createSession(PublicKey target, SessionKey key) {
        throw new UnsupportedOperationException();
    }

    /**
     * Check if a public key is a duplicate.
     *
     * @param pk the public key to check
     * @return true if a dup
     * @since 0.9.46
     */
    boolean isDuplicate(PublicKey pk) {
        return _replayFilter.add(pk.getData(), 0, 32);
    }

    /**
     * Check whether the raw (pre-decode) bytes of a NewSession ephemeral key
     * are a replay of a previously processed one, without adding them.
     * Lets replays be rejected before paying for Elligator2 decoding.
     *
     * @param data at least 32 bytes
     * @return true if a dup
     * @since 0.9.71+
     */
    boolean isDuplicateRaw(byte[] data) {
        return _replayFilter.isKnown(data, 0, 32);
    }

    /**
     * Remember the raw (pre-decode) bytes of a successfully processed
     * NewSession key, so a later replay of the same packet is caught by
     * isDuplicateRaw() before any crypto work.
     *
     * @param data at least 32 bytes
     * @since 0.9.71+
     */
    void addRaw(byte[] data) {
        _replayFilter.add(data, 0, 32);
    }

    /**
     * Inbound or outbound. Checks state.getRole() to determine.
     * For outbound (NS sent), adds to list of pending inbound sessions and returns true.
     * For inbound (NS rcvd), if no other pending outbound sessions, creates one
     * and returns true, or false if one already exists.
     *
     * @param target the target public key
     * @param d null if unknown
     * @param state the handshake state
     * @param callback null for inbound, may be null for outbound
     * @return true if the session was added, false if one for this target already existed
     */
    boolean createSession(PublicKey target, Destination d, HandshakeState state, ReplyCallback callback) {
        EncType type = target.getType();
        if (type != _type)
            throw new IllegalArgumentException("Bad public key type " + type + " expected " + _type);
        OutboundSession sess = new OutboundSession(target, d, null, state, callback);
        boolean isInbound = state.getRole() == HandshakeState.RESPONDER;
        if (isInbound) {
            // we are Bob, NS received
            boolean rv = addSession(sess, true);
            if (_log.shouldInfo()) {
                if (rv)
                    _log.info("New outbound session as Bob [" + state.hashCode() + "] * Peer: " + toString(target));
                else
                    _log.info("Duplicate outbound session as Bob [" + state.hashCode() + "] * Peer: " + toString(target));
            }
            return rv;
        } else {
            // we are Alice, NS sent
            synchronized (_pendingOutboundSessions) {
                List<OutboundSession> pending = _pendingOutboundSessions.get(target);
                if (pending != null) {
                    pending.add(sess);
                    if (_log.shouldInfo())
                        _log.info("Another new outbound session as Alice [" + state.hashCode() +
                                  "] * Total pending: " + pending.size() + " * Peer: " + toString(target));
                } else {
                    pending = new ArrayList<>(4);
                    pending.add(sess);
                    _pendingOutboundSessions.put(target, pending);
                    if (_log.shouldInfo())
                        _log.info("First new outbound session as Alice [" + state.hashCode() + "] * Peer: " + toString(target));
                }
            }
            return true;
        }
    }

    /**
     * Inbound or outbound. Checks state.getRole() to determine.
     * For outbound (NSR rcvd by Alice), sets session to transition to ES mode outbound.
     * For inbound (NSR sent by Bob), sets up inbound ES tagset.
     *
     * @param target the target public key
     * @param oldState null for inbound, pre-clone for outbound
     * @param state the handshake state
     * @param callback the reply callback
     * @param split the split keys
     * @return true if this was the first NSR received
     */
    boolean updateSession(PublicKey target, HandshakeState oldState, HandshakeState state,
                          ReplyCallback callback, SplitKeys split) {
        EncType type = target.getType();
        if (type != _type)
            throw new IllegalArgumentException("Bad public key type " + type + " expected " + _type);
        boolean isInbound = state.getRole() == HandshakeState.RESPONDER;
        if (isInbound) {
            // we are Bob, NSR sent
            if (_log.shouldInfo())
                _log.info("Handshake " + state.hashCode() + " completed as Bob, NSR sent * Peer: " + toString(target));
            OutboundSession sess = getSession(target);
            if (sess == null) {
                // Not recoverable, and no recovery is needed. The session is
                // gone because it expired, was removed, or was superseded by a
                // rekey, so the handshake state the peer replied to no longer
                // exists and the ratchet chain cannot be continued. The caller
                // (ECIESAEADEngine.decryptNewSessionReply) destroys the
                // handshake state and returns no cloves; the next message to
                // the peer starts a fresh handshake.
                if (_log.shouldWarn())
                    _log.warn("Bob session update -> No session found for peer: " + toString(target));
                return false;
            }
            sess.updateSession(state, callback, split);
        } else {
            // we are Alice, NSR received
            if (_log.shouldInfo())
                _log.info("Handshake " + oldState.hashCode() + " -> " + state.hashCode() +
                          " completed as Alice, NSR received * Peer: " + toString(target));
            OutboundSession sess;
            List<OutboundSession> stale;
            synchronized (_pendingOutboundSessions) {
                List<OutboundSession> pending = _pendingOutboundSessions.get(target);
                if (pending == null) {
                    if (_log.shouldDebug())
                        _log.debug("Alice session update -> No pending sessions for peer: " + toString(target));
                    // Normal case for multiple NSRs, was already removed
                    return false;
                }
                sess = takePendingFor(pending, oldState);
                if (sess == null) {
                    // Not recoverable, and no recovery is needed. Pending
                    // sessions exist for the target but none matches oldState,
                    // so this is a late or duplicate NSR for a handshake that
                    // was already promoted to ES (or already cleaned up as
                    // stale). The already-established session, if any, is left
                    // untouched, and the caller destroys the handshake state
                    // and returns no cloves. Retrying cannot help: the old
                    // state this reply belongs to is gone.
                    if (_log.shouldDebug())
                        _log.debug("Alice session update -> No matching session (of " + pending.size() +
                                   ") for peer: " + toString(target));
                    return false;
                }
                // Whatever is still queued for this target came from a handshake
                // that lost the race. Drop it from the map here, destroy it
                // below.
                stale = _pendingOutboundSessions.remove(target);
            }
            // The derivations and the inbound tagset inserts below cost
            // microseconds each, and this map monitor is a single lock for every
            // NSR the router handles, so keep them off it. The session is out of
            // the pending map by now, so no second NSR for this handshake can
            // promote it as well.
            sess.updateSession(state, null, split);
            boolean ok = addSession(sess, false);
            if (_log.shouldDebug()) {
                if (ok)
                    _log.debug("Alice session updated from NSR to ES * Peer: " + toString(target));
                else
                    _log.debug("Alice session already updated from NSR to ES * Peer: " + toString(target));
            }
            if (!stale.isEmpty()) {
                for (OutboundSession other : stale) {
                    other.getHandshakeState().destroy();
                    for (RatchetTagSet ts : other.getTagSets()) {
                        for (RatchetSessionTag tag : ts.getRegisteredTags()) {
                            _inboundTagSets.remove(tag);
                        }
                        ts.clearRegisteredTags();
                    }
                }
                if (_log.shouldDebug())
                    _log.debug("Cleaned up " + stale.size() + " stale pending sessions * Peer: " + toString(target));
            }
        }
        return true;
    }

    /**
     * Detach the pending session this New Session Reply belongs to, so the
     * caller can promote it outside the map monitor. The first match wins and is
     * removed from the list; a second entry for the same handshake is left
     * behind for the caller to clean up as stale.
     *
     * @param pending the sessions queued for one target, not null, modified in
     *                place
     * @param oldState the pre-clone handshake state the reply carries
     * @return the session to promote, or null if none matches, in which case
     *         pending is left as it was
     * @since 0.9.72+
     */
    static OutboundSession takePendingFor(List<OutboundSession> pending, HandshakeState oldState) {
        for (Iterator<OutboundSession> iter = pending.iterator(); iter.hasNext(); ) {
            OutboundSession sess = iter.next();
            if (oldState.equals(sess.getHandshakeState())) {
                iter.remove();
                return sess;
            }
        }
        return null;
    }

    /**
     * Process a received next key.
     *
     * @param target the target public key
     * @param key the next session key
     * @since 0.9.46
     */
    void nextKeyReceived(PublicKey target, NextSessionKey key) {
        OutboundSession sess = getSession(target);
        if (sess == null) {
            if (_log.shouldWarn())
                _log.warn("Received NextKey but no session found for "  + target);
            return;
        }
        sess.nextKeyReceived(key);
    }

    /**
     * Side effect - binds this session to the supplied destination.
     *
     * @param target the target public key
     * @param d the far-end Destination for this PublicKey if known, or null
     * @param timer the timer event
     * @return true if registered
     * @since 0.9.47
     */
    boolean registerTimer(PublicKey target, Destination d, SimpleTimer2.TimedEvent timer) {
        OutboundSession sess = getSession(target);
        if (sess == null) {
            if (_log.shouldWarn())
                _log.warn("Cannot register timer, no session found for " + target);
            return false;
        }
        return sess.registerTimer(d, timer);
    }

    /**
     * The far-end destination for a public key.
     *
     * @param target the target public key
     * @return the far-end Destination for this PublicKey, or null
     * @since 0.9.47
     */
    Destination getDestination(PublicKey target) {
        OutboundSession sess = getSession(target);
        if (sess != null) {
            return sess.getDestination();
        }
        return null;
    }

    /**
     * Not supported for this key manager.
     *
     * @throws UnsupportedOperationException always; the ratchet key manager does not support this
     */
    @Override
    public SessionTag consumeNextAvailableTag(PublicKey target, SessionKey key) {
        throw new UnsupportedOperationException();
    }

    /**
     * Takes the next outbound session tag and key for sending a message to the target.
     * <p>
     * If this returns null, no session is set up yet, and a New Session message should be sent.
     * <p>
     * If this returns non-null, the tag in the RatchetEntry will be non-null.
     * <p>
     * If the SessionKeyAndNonce contains a HandshakeState, then the session setup is in progress,
     * and a New Session Reply message should be sent.
     * Otherwise, an Existing Session message should be sent.
     *
     * @param target the remote router to take the next outbound tag for
     * @return the next available tag, or null
     */
    public RatchetEntry consumeNextAvailableTag(PublicKey target) {
        OutboundSession sess = getSession(target);
        if (sess == null) {
            return null;
        }
        RatchetEntry rv = sess.consumeNext();
        if (_log.shouldDebug()) {
            if (rv != null)
                _log.debug("Using tag " + rv + " to " + toString(target));
            else
                _log.debug("No more tags in Outbound session to " + toString(target));
        }
        return rv;
    }

    /**
     * How many to send, IF we need to.
     *
     * @return the configured value (not adjusted for current available)
     */
    @Override
    public int getTagsToSend() { return 0; }

    /**
     * The configured threshold for sending more tags.
     *
     * @return the configured value
     */
    @Override
    public int getLowThreshold() { return 999999; }

    /**
     * Whether to send more tags, always false here.
     *
     * @return false always
     */
    @Override
    public boolean shouldSendTags(PublicKey target, SessionKey key, int lowThreshold) {
        return false;
    }

    /**
     * Determine (approximately) how many available session tags for the current target
     * have been confirmed and are available.
     *
     * @return the available tags
     */
    @Override
    public int getAvailableTags(PublicKey target, SessionKey key) {
        OutboundSession sess = getSession(target);
        if (sess == null) { return 0; }
        SessionKey currentKey = sess.getCurrentKey();
        if (currentKey != null && currentKey.equals(key)) {
            return sess.availableTags();
        }
        return 0;
    }

    /**
     * Determine how long the available tags will be available for before expiring, in
     * milliseconds.
     * @return the available time left
     */
    @Override
    public long getAvailableTimeLeft(PublicKey target, SessionKey key) {
        OutboundSession sess = getSession(target);
        if (sess == null) { return 0; }
        SessionKey currentKey = sess.getCurrentKey();
        if (currentKey != null && currentKey.equals(key)) {
            long end = sess.getLastExpirationDate();
            if (end <= 0)
                return 0;
            else
                return end - _context.clock().now();
        }
        return 0;
    }

    /**
     * Not supported for this key manager.
     *
     * @throws UnsupportedOperationException always; the ratchet key manager does not support this
     */
    @Override
    public TagSetHandle tagsDelivered(PublicKey target, SessionKey key, Set<SessionTag> sessionTags) {
        throw new UnsupportedOperationException();
    }

    /**
     * Not supported for this key manager.
     *
     * @throws UnsupportedOperationException always; the ratchet key manager does not support this
     */
    @Override
    public void failTags(PublicKey target, SessionKey key, TagSetHandle ts) {
        throw new UnsupportedOperationException();
    }

    /**
     * Not supported for this key manager.
     *
     * @throws UnsupportedOperationException always; the ratchet key manager does not support this
     */
    @Override
    public void tagsAcked(PublicKey target, SessionKey key, TagSetHandle ts) {
        throw new UnsupportedOperationException();
    }

    /**
     * Not supported for this key manager.
     *
     * @throws UnsupportedOperationException always; the ratchet key manager does not support this
     */
    @Override
    public void tagsReceived(SessionKey key, Set<SessionTag> sessionTags) {
        throw new UnsupportedOperationException();
    }

    /**
     * Not supported for this key manager.
     *
     * @throws UnsupportedOperationException always; the ratchet key manager does not support this
     */
    @Override
    public void tagsReceived(SessionKey key, Set<SessionTag> sessionTags, long expire) {
        throw new UnsupportedOperationException();
    }

    /**
     * One time session.
     *
     * @param key the session key
     * @param tag the ratchet session tag
     * @param expire time from now
     */
    public void tagsReceived(SessionKey key, RatchetSessionTag tag, long expire) {
        new SingleTagSet(this, key, tag, _context.clock().now(),  expire);
    }

    /**
     * Not supported for this key manager.
     *
     * @throws UnsupportedOperationException always; the ratchet key manager does not support this
     */
    @Override
    public SessionKey consumeTag(SessionTag tag) {
        throw new UnsupportedOperationException();
    }

    /**
     * Determines whether a session key was received for the given session tag.
     * <p>
     * If one was, it is discarded and returned along with the decryption
     * key it was received with (via tagsReceived(...)).  returns null if no session key
     * matches
     * <p>
     * If the return value has null data, it will have a non-null HandshakeState.
     *
     * @param tag the ratchet session tag to consume
     * @return a SessionKeyAndNonce or null
     */
    public SessionKeyAndNonce consumeTag(RatchetSessionTag tag) {
        RatchetTagSet tagSet;
        tagSet = _inboundTagSets.remove(tag);
        if (tagSet == null) {
            _tagMisses.increment();
            return null;
        }
        boolean firstInbound;
        SessionKeyAndNonce key;
        synchronized(tagSet) {
            firstInbound = !tagSet.getAcked();
            key = tagSet.consume(tag);
            if (key != null)
                tagSet.setDate(_context.clock().now());
        }
        if (key != null) {
            HandshakeState state = tagSet.getHandshakeState();
            if (state == null) {
                // Known ordering limitation, tracked and safe. A handshake tag
                // is consumed before the ratchet decryption that follows, so
                // the session is touched (firstTagConsumed / tagConsumed) even
                // if that decryption later fails. The bookkeeping is therefore
                // "a tag was spent", not "a message was received": the
                // tagset advances one tag and the peer's ratchet chain moves
                // on, which is exactly what a dropped or forged message does
                // anyway. Deferring the update until after decrypt would require
                // holding the tag unconsumed across the decryption, which would
                // break the constant-time tag lookup.
                PublicKey pk = tagSet.getRemoteKey();
                if (pk != null) {
                    OutboundSession sess = getSession(pk);
                    if (sess != null) {
                        if (firstInbound)
                            sess.firstTagConsumed(tagSet);
                        else
                            sess.tagConsumed(tagSet);
                    } else {
                        if (_log.shouldDebug())
                            _log.debug("Tag consumed but session is gone");
                    }
                } // else null for SingleTagSets
            }
            if (_log.shouldDebug()) {
                if (state != null)
                    _log.debug("Inbound NSR Tag " + key.getNonce() + " consumed: " + tag.toBase64() + "\n* " + tagSet);
                else
                    _log.debug("Inbound ES Tag " + key.getNonce() + " consumed: " + tag.toBase64() + "\n* " + tagSet);
            }
            if (state != null) {
                _inboundCompleted.increment();
                // O(tags_in_set) removal using the tagset's registered tags,
                // instead of O(total_tags) scan of the entire _inboundTagSets map.
                for (RatchetSessionTag t : tagSet.getRegisteredTags()) {
                    _inboundTagSets.remove(t);
                }
                tagSet.clearRegisteredTags();
            }
        } else {
            if (_log.shouldInfo())
                _log.info("Tag " + tag.toBase64() + " not found in session tag set" +
                          "\n* " + tagSet);
        }
        return key;
    }

    /**
     * Returns the pending session for a target key.
     *
     * @param target the peer's ratchet key
     * @return the session, or null if none is pending
     */
    private OutboundSession getSession(PublicKey target) {
        return _outboundSessions.get(target);
    }

    /**
     * For inbound, we are Bob, NS received, replace if very old
     * For outbound, we are Alice, NSR received, never replace.
     *
     * @param sess the outbound session to add, keyed by its target key
     * @param isInbound Bob if true
     * @return true if added
     */
    private boolean addSession(OutboundSession sess, boolean isInbound) {
        synchronized (_outboundSessions) {
            OutboundSession old = _outboundSessions.putIfAbsent(sess.getTarget(), sess);
            boolean rv = old == null;
            if (rv) {
                _sessionsCreated.increment();
            }
            if (!rv) {
                if (isInbound && old.getEstablishedDate() < _context.clock().now() - SESSION_REPLACE_AGE) {
                    // He restarted with same key, or something went wrong. Start over.
                    _outboundSessions.put(sess.getTarget(), sess);
                    rv = true;
                    _sessionsCreated.increment();
                    _sessionsReplaced.increment();
                    if (_log.shouldInfo())
                        _log.info("Replaced old session, new request from [" +
                                  sess.getTarget().calculateHash().toBase32()
                                      .substring(0, 8) + ']');
                } else {
                    if (_log.shouldDebug())
                        _log.debug("Not replacing existing session for " + sess.getTarget());
                }
            }
            return rv;
        }
    }

    /**
     * Aggressively expire inbound tag sets and outbound sessions.
     *
     * @return number of tag sets expired (bogus as it overcounts inbound)
     */
    private int aggressiveExpire() {
        long now = _context.clock().now();

        // inbound - single pass over entrySet, remove all entries with expired tagsets
        int removed = 0;
        for (Iterator<Map.Entry<RatchetSessionTag, RatchetTagSet>> iter = _inboundTagSets.entrySet().iterator(); iter.hasNext();) {
            Map.Entry<RatchetSessionTag, RatchetTagSet> entry = iter.next();
            if (entry.getValue().getExpiration() < now) {
                iter.remove();
                removed++;
            }
        }

        // outbound
        int oremoved = 0;
        int cremoved = 0;
        long exp = now - SESSION_TAG_DURATION_MS;
        for (Iterator<OutboundSession> iter = _outboundSessions.values().iterator(); iter.hasNext();) {
            OutboundSession sess = iter.next();
            oremoved += sess.expireTags(now);
            cremoved += sess.expireCallbacks(now);
            if (sess.getLastUsedDate() < exp || sess.getLastReceivedDate() < exp) {
                iter.remove();
                oremoved++;
            }
        }

        // pending outbound
        int premoved = 0;
        exp = now - SESSION_PENDING_DURATION_MS;
        synchronized (_pendingOutboundSessions) {
            for (Iterator<List<OutboundSession>> iter = _pendingOutboundSessions.values().iterator(); iter.hasNext();) {
                List<OutboundSession> pending = iter.next();
                for (Iterator<OutboundSession> liter = pending.iterator(); liter.hasNext();) {
                    OutboundSession sess = liter.next();
                    cremoved += sess.expireCallbacks(now);
                    if (sess.getEstablishedDate() < exp) {
                        liter.remove();
                        premoved++;
                    }
                }
                if (pending.isEmpty())
                    iter.remove();
            }
        }
        if ((removed > 0 || oremoved > 0 || premoved > 0 || cremoved > 0) && _log.shouldInfo())
            _log.info("Expired Inbound: " + removed + ", Outbound: " + oremoved +
                      ", Pending: " + premoved + ", Callbacks: " + cremoved);

        return removed + oremoved + premoved;
    }

    /**
     * SessionTagListener: map the tag to this tagset.
     *
     * @return true if added, false if dup
     */
    @Override
    public boolean addTag(RatchetSessionTag tag, RatchetTagSet ts) {
        return _inboundTagSets.putIfAbsent(tag, ts) == null;
    }

    /**
     * Remove the tag associated with this tagset.
     */
    @Override
    public void expireTag(RatchetSessionTag tag, RatchetTagSet ts) {
        _inboundTagSets.remove(tag, ts);
    }

    /**
     * ACKS: Register a callback for an ACK.
     *
     * @param target the target public key
     * @param id the callback ID
     * @param n the callback count
     * @param callback the reply callback
     * @since 0.9.46
     */
    void registerCallback(PublicKey target, int id, int n, ReplyCallback callback) {
        if (_log.shouldInfo())
            _log.info("Register callback target " + target + " id=" + id + " n=" + n + " callback " + callback);
        OutboundSession sess = getSession(target);
        if (sess != null)
            sess.registerCallback(id, n, callback);
        else if (_log.shouldWarn())
            _log.warn("No session found for register callback");
    }

    /**
     * Process a received ACK.
     *
     * @param target the target public key
     * @param id the ACK ID
     * @param n the ACK count
     * @since 0.9.46
     */
    void receivedACK(PublicKey target, int id, int n) {
        OutboundSession sess = getSession(target);
        if (sess != null)
            sess.receivedACK(id, n);
        else if (_log.shouldWarn())
            _log.warn("No session found for received ACK");
    }

    /**
     * Process an ACK request.
     *
     * @param target the target public key
     * @param id the ACK ID
     * @param n the ACK count
     * @since 0.9.46
     */
    void ackRequested(PublicKey target, int id, int n) {
        if (_log.shouldInfo())
            _log.info("Received ACK request [id=" + id + " n=" + n + "]");
        OutboundSession sess = getSession(target);
        if (sess != null)
            sess.ackRequested(id, n);
        else if (_log.shouldWarn())
            _log.warn("No session found for ACK request");
    }

    /**
     * Return a map of PublicKey to a set of inbound RatchetTagSets for that key.
     * Only for {@link #renderStatusHTML(Writer)} below.
     * Does not return expired sets or sets with null keys.
     *
     * @return the ratchet tag sets by public key
     */
    private Map<PublicKey, Set<RatchetTagSet>> getRatchetTagSetsByPublicKey() {
        Set<RatchetTagSet> inbound = getRatchetTagSets();
        Map<PublicKey, Set<RatchetTagSet>> inboundSets = new HashMap<>(inbound.size());
        long now = _context.clock().now();
        // Build a map of the inbound tag sets, grouped by PublicKey
        for (RatchetTagSet ts : inbound) {
            PublicKey pk = ts.getRemoteKey();
            if (pk == null)
                continue;
            if (ts.getExpiration() < now)
                continue;
            Set<RatchetTagSet> sets = inboundSets.get(pk);
            if (sets == null) {
                sets = new HashSet<>(4);
                inboundSets.put(pk, sets);
            }
            sets.add(ts);
        }
        return inboundSets;
    }

    /**
     * {@inheritDoc}
     * Snapshot counts only, no key material.
     */
    @Override
    public long[] getDebugStats() {
        return new long[] {_inboundTagSets.size(), _outboundSessions.size(),
                           _sessionsCreated.sum(), _sessionsReplaced.sum(),
                           _inboundCompleted.sum(), _tagMisses.sum()};
    }

    /**
     * Render the session status HTML as three tables: inbound tag
     * sets, outbound sessions, and outbound tag sets, each with a totals
     * line, plus a line of lifetime counters.
     */
    @Override
    public void renderStatusHTML(Writer out) throws IOException {
        StringBuilder buf = new StringBuilder(1024);
        boolean isPQ = _type != EncType.ECIES_X25519;
        long now = _context.clock().now();

        buf.append("<h3>").append(isPQ ? "PQ " : "").append(_t("Ratchet Inbound sessions")).append("</h3>\n");
        Map<PublicKey, Set<RatchetTagSet>> inboundSets = getRatchetTagSetsByPublicKey();
        List<String[]> inbound = buildInboundRows(inboundSets, now);
        if (inbound.isEmpty()) {
            buf.append("<p class=infohelp>").append(_t("No inbound sessions.")).append("</p>\n");
        } else {
            buf.append("<table>\n<thead>\n" +
                      "<tr><th>").append(_t("Peer")).append("</th><th>").append(_t("Set")).
                      append("</th><th>").append(_t("Expires in")).append("</th><th>").append(_t("Tags")).
                      append("</th><th>").append(_t("Remaining")).append("</th></tr>\n</thead>\n");
            for (String[] row : inbound) {
                buf.append("<tr><td>").append(peerCell(row[0])).append("</td><td>").append(row[1])
                   .append("</td><td>").append(row[2]).append("</td><td>").append(row[3])
                   .append("</td><td>").append(row[4]).append("</td></tr>\n");
            }
            buf.append("</table>\n");
        }

        buf.append("<h3>").append(isPQ ? "PQ " : "").append(_t("Ratchet Outbound sessions")).append("</h3>\n");
        Set<OutboundSession> outbound = getOutboundSessions();
        List<String[]> obSessions = buildOutboundRows(outbound, now);
        if (obSessions.isEmpty()) {
            buf.append("<p class=infohelp>").append(_t("No outbound sessions.")).append("</p>\n");
        } else {
            List<String[]> obSets = buildOutboundSetRows(outbound, now);
            buf.append("<table>\n<thead>\n" +
                      "<tr><th>").append(_t("Peer")).append("</th><th>").append(_t("Established")).
                      append("</th><th>").append(_t("Last Used")).append("</th><th>").append(_t("Last Rcvd")).
                      append("</th><th>").append(_t("Sets")).append("</th></tr>\n</thead>\n");
            for (String[] row : obSessions) {
                buf.append("<tr><td>").append(peerCell(row[0])).append("</td><td>").append(row[1])
                   .append("</td><td>").append(row[2]).append("</td><td>").append(row[3])
                   .append("</td><td>").append(row[4]).append("</td></tr>\n");
            }
            buf.append("</table>\n");

            buf.append("<table>\n<thead>\n" +
                      "<tr><th>").append(_t("Peer")).append("</th><th>").append(_t("Set")).
                      append("</th><th>").append(_t("Acked")).append("</th><th>").append(_t("Created")).
                      append("</th><th>").append(_t("Last Used")).append("</th><th>").append(_t("Expires in")).
                      append("</th><th>").append(_t("Remaining")).append("</th><th>").append(_t("NK sent")).
                      append("</th></tr>\n</thead>\n");
            for (String[] row : obSets) {
                buf.append("<tr><td>").append(peerCell(row[0])).append("</td><td>").append(row[1])
                   .append("</td><td>").append(row[2]).append("</td><td>").append(row[3])
                   .append("</td><td>").append(row[4]).append("</td><td>").append(row[5])
                   .append("</td><td>").append(row[6]).append("</td><td>").append(row[7]).append("</td></tr>\n");
            }
            buf.append("</table>\n");
        }

        long created = _sessionsCreated.sum();
        long replaced = _sessionsReplaced.sum();
        long completed = _inboundCompleted.sum();
        long misses = _tagMisses.sum();
        if (created + replaced + completed + misses > 0) {
            buf.append("<p class=infohelp>").append(summaryText(created, replaced, completed, misses)).append("</p>\n");
        }
        out.append(buf);
    }

    /**
     * Render a peer cell as a router-info reference (flag, netdb link,
     * bandwidth) when the label is a base64 hash; the label itself is the
     * fallback for the "null" key.
     *
     * @param hashLabel the peer cell from a debug row
     * @return the cell html
     */
    private String peerCell(String hashLabel) {
        byte[] data = Base64.decode(hashLabel);
        if (data == null || data.length != Hash.HASH_LENGTH) {return hashLabel;}
        return _context.commSystem().renderPeerHTML(new Hash(data), true);
    }

    /**
     * Console translation for the debug page strings.
     *
     * @param s the message key in the router console bundle
     * @return the translated string
     */
    private String _t(String s) {
        return Translate.getString(s, _context, "net.i2p.router.web.messages");
    }

    /**
     * Unified debug rows for the consolidated client-SKM table: one row per
     * inbound tag set, outbound session and outbound tag set, sharing the
     * columns {kind, peer, set, acked, created, established, last used,
     * last received, expires in, tags, remaining, NK sent}. Cells that do
     * not apply to a row's kind are an em-dash.
     * Only for the debug console.
     *
     * @return the rows, never null, empty if no sessions
     * @since 0.9.71
     */
    public List<String[]> getDebugRows() {
        long now = _context.clock().now();
        return unifyRows(buildInboundRows(getRatchetTagSetsByPublicKey(), now),
                         buildOutboundRows(getOutboundSessions(), now),
                         buildOutboundSetRows(getOutboundSessions(), now));
    }

    /**
     * The session counters line, or null when all counters are zero.
     * Only for the debug console.
     *
     * @return the summary text, or null
     * @since 0.9.71
     */
    public String getDebugSummary() {
        long created = _sessionsCreated.sum();
        long replaced = _sessionsReplaced.sum();
        long completed = _inboundCompleted.sum();
        long misses = _tagMisses.sum();
        if (created + replaced + completed + misses <= 0) {return null;}
        return summaryText(created, replaced, completed, misses);
    }

    /**
     * Formats the session statistics for the log.
     *
     * @param created sessions created since startup
     * @param replaced sessions replaced by newer ones
     * @param completed inbound handshakes completed
     * @param misses decrypts with no matching tag
     * @return the summary line
     */
    private String summaryText(long created, long replaced, long completed, long misses) {
        return Translate.getString("Sessions created: {0}; replaced: {1}; inbound sessions completed: {2}; inbound tag misses: {3}",
                                   _context, "net.i2p.router.web.messages",
                                   created, replaced, completed, misses);
    }

    /**
     * Map the three per-kind row shapes of buildInboundRows,
     * buildOutboundRows and buildOutboundSetRows into the shared
     * 12-column layout, padding non-applicable cells with an em-dash.
     * The session row's set count is dropped, as it is derivable from
     * the set rows. Pure, for unit tests.
     *
     * @param inbound rows from buildInboundRows
     * @param sessions rows from buildOutboundRows
     * @param sets rows from buildOutboundSetRows
     * @return the unified rows, in inbound, session, set order
     * @since 0.9.71
     */
    static List<String[]> unifyRows(List<String[]> inbound, List<String[]> sessions, List<String[]> sets) {
        String dash = "—";
        List<String[]> rv = new ArrayList<>(inbound.size() + sessions.size() + sets.size());
        for (String[] r : inbound) {
            rv.add(new String[] {"Inbound", r[0], r[1], dash, dash, dash, dash, dash, r[2], r[3], r[4], dash});
        }
        for (String[] r : sessions) {
            rv.add(new String[] {"Session", r[0], dash, dash, dash, r[1], r[2], r[3], dash, dash, dash, dash});
        }
        for (String[] r : sets) {
            rv.add(new String[] {"Set", r[0], r[1], r[2], r[3], dash, r[4], dash, r[5], dash, r[6], r[7]});
        }
        return rv;
    }

    /**
     * Label a peer by the base64 hash of its public key, so the debug
     * console can turn the cell into a router-info reference (flag, link,
     * bandwidth) via {@code commSystem().renderPeerHTML()}.
     *
     * @param key the peer's public key, may be null
     * @return the base64 key hash, or "null", never null
     * @since 0.9.71
     */
    static String keyLabel(PublicKey key) {
        return key == null ? "null" : key.calculateHash().toBase64();
    }

    /**
     * Label a tag set as its id (NSR for a new-session-request set, ES for a
     * single established-session set) followed by its debug id.
     *
     * @param id the tag set id, one of the RatchetTagSet DEBUG constants or a session number
     * @param debugID the per-set debug sequence number
     * @return a label like "NSR/12", never null
     * @since 0.9.71
     */
    static String setIdLabel(int id, int debugID) {
        String s;
        if (id == RatchetTagSet.DEBUG_IB_NSR || id == RatchetTagSet.DEBUG_OB_NSR) {
            s = "NSR";
        } else if (id == RatchetTagSet.DEBUG_SINGLE_ES) {
            s = "ES";
        } else {
            s = Integer.toString(id);
        }
        return s + '/' + debugID;
    }

    /**
     * Label a next-session key as its direction, a bracketed eight-character
     * fingerprint, and its key id. The key itself goes on the wire in the
     * PNBlock, so a log line needs only enough to identify it: the 44-character
     * base64 of an X25519 key pushes the whole message onto continuation lines,
     * and {@link NextSessionKey#toString()} opens with a newline of its own, so
     * it cannot be prefixed on a single line at all.
     *
     * @param key the key to label, not null
     * @param label the direction to name, e.g. Forward
     * @return a segment like "Forward: [AbCdEfGh] ID: 3", never null
     * @since 0.9.72+
     */
    static String keyFingerprint(NextSessionKey key, String label) {
        String b64 = key.toBase64();
        // A key built for an ack or a request carries no data at all
        String fp = b64 == null ? "TBD" :
                    '[' + b64.substring(0, Math.min(8, b64.length())) + ']';
        return label + ": " + fp + " ID: " + key.getID();
    }

    /** Row order for the peer/set tables: peer (column 0), then set (column 1). */
    private static final Comparator<String[]> ROW_PEER_SET =
        Comparator.comparing((String[] r) -> r[0]).thenComparing(r -> r[1]);

    /** Row order for the peer-keyed table: peer (column 0). */
    private static final Comparator<String[]> ROW_PEER =
        Comparator.comparing((String[] r) -> r[0]);

    /**
     * One row per inbound tag set of {peer, set, expires in, tags,
     * remaining}, sorted by peer then set. The caller must have already
     * dropped null-key and expired sets.
     * Pure, for unit tests.
     *
     * @param sets inbound tag sets by peer key
     * @param now the current time in ms
     * @return one row per set, never null
     * @since 0.9.71
     */
    static List<String[]> buildInboundRows(Map<PublicKey, Set<RatchetTagSet>> sets, long now) {
        List<String[]> rows = new ArrayList<>();
        for (Map.Entry<PublicKey, Set<RatchetTagSet>> e : sets.entrySet()) {
            String peer = keyLabel(e.getKey());
            for (RatchetTagSet ts : e.getValue()) {
                synchronized (ts) {
                    rows.add(new String[] {peer, setIdLabel(ts.getID(), ts.getDebugID()),
                                           DataHelper.formatDuration(ts.getExpiration() - now),
                                           Integer.toString(ts.size()), Integer.toString(ts.remaining())});
                }
            }
        }
        rows.sort(ROW_PEER_SET);
        return rows;
    }

    /**
     * One row per outbound session of {peer, established, last used, last
     * received, sets}, sorted by peer.
     * Pure, for unit tests.
     *
     * @param sessions the outbound sessions
     * @param now the current time in ms
     * @return one row per session, never null
     * @since 0.9.71
     */
    static List<String[]> buildOutboundRows(Collection<OutboundSession> sessions, long now) {
        List<String[]> rows = new ArrayList<>(sessions.size());
        for (OutboundSession sess : sessions) {
            rows.add(new String[] {keyLabel(sess.getTarget()),
                                   DataHelper.formatDuration(now - sess.getEstablishedDate()) + " ago",
                                   DataHelper.formatDuration(now - sess.getLastUsedDate()) + " ago",
                                   DataHelper.formatDuration(now - sess.getLastReceivedDate()) + " ago",
                                   Integer.toString(sess.getTagSets().size())});
        }
        rows.sort(ROW_PEER);
        return rows;
    }

    /**
     * One row per live (not yet expired) outbound tag set of {peer, set,
     * acked, created, last used, expires in, remaining, NK sent}, sorted by
     * peer then set.
     * Pure, for unit tests.
     *
     * @param sessions the outbound sessions
     * @param now the current time in ms
     * @return one row per live tag set, never null
     * @since 0.9.71
     */
    static List<String[]> buildOutboundSetRows(Collection<OutboundSession> sessions, long now) {
        List<String[]> rows = new ArrayList<>();
        for (OutboundSession sess : sessions) {
            String peer = keyLabel(sess.getTarget());
            for (RatchetTagSet ts : sess.getTagSets()) {
                synchronized (ts) {
                    long expires = ts.getExpiration() - now;
                    if (expires <= 0) {continue;}
                    rows.add(new String[] {peer, setIdLabel(ts.getID(), ts.getDebugID()),
                                           ts.getAcked() ? "yes" : "no",
                                           DataHelper.formatTime(ts.getCreated()),
                                           DataHelper.formatTime(ts.getDate()),
                                           DataHelper.formatDuration(expires),
                                           Integer.toString(ts.remaining()),
                                           ts.getNextKey() != null ? "yes" : "no"});
                }
            }
        }
        rows.sort(ROW_PEER_SET);
        return rows;
    }

    /**
     * For debugging.
     *
     * @param target the public key to abbreviate, null renders "null"
     * @return the first 20 characters of the base64 key with an ellipsis
     */
    private static String toString(PublicKey target) {
        if (target == null)
            return "null";
        return target.toBase64().substring(0, 20) + "...";
    }

    /**
     * The state for a crypto session to a single public key.
     */
    class OutboundSession {
        /** The peer's ratchet public key. */
        private final PublicKey _target;
        /** Current state of the handshake. */
        private final HandshakeState _state;
        /** Callback for an NS reply. */
        private ReplyCallback _NScallback;
        /** Callback for an NSR reply. */
        private ReplyCallback _NSRcallback;
        /** Key used for the most recent messages. */
        private SessionKey _currentKey;
        /** When the session was created. */
        private final long _established;
        /** When we last sent through the session. */
        private long _lastUsed;
        /** When we last received through the session. */
        private long _lastReceived;
        /**
         * Before the first ack, all tagsets go here. These are dropped by
         * clear() when the session's tag state is reset or the session dies;
         * a failsafe expire also removes stale sets.
         * Unsynchronized, sync to use.
         * No particular order.
         */
        private final Set<RatchetTagSet> _unackedTagSets;
        /**
         * There is only one active outbound tagset.
         * Synch on _unackedTagSets to access this.
         */
        private RatchetTagSet _tagSet;
        /** Reply callbacks by tag id. */
        private final ConcurrentHashMap<Integer, ReplyCallback> _callbacks;
        /** Acknowledgements waiting to be sent. */
        private final LinkedBlockingQueue<Integer> _acksToSend;
        /** Timer that flushes the pending acks. */
        private SimpleTimer2.TimedEvent _ackTimer;
        /** The local destination owning the session. */
        private Destination _destination;

        /** Key id for our next outbound ratchet key. */
        private int _myOBKeyID = -1;
        /** Key id for our next inbound ratchet key. */
        private int _myIBKeyID = -1;
        /** Messages sent under our current inbound key. */
        private int _myIBKeySendCount;
        /** Our current inbound ratchet key pair. */
        private KeyPair _myIBKeys;
        /** Our current outbound ratchet key pair. */
        private KeyPair _myOBKeys;
        /** Our next inbound key, sent ahead of use. */
        private NextSessionKey _myIBKey;
        /** The last key received from the peer, possibly without data, for the duplicate check. */
        private NextSessionKey _hisIBKey;
        /** Their latest outbound key, possibly without data. */
        private NextSessionKey _hisOBKey;
        /** The last key received from the peer that carried data. */
        private NextSessionKey _hisIBKeyWithData;
        /** Their latest outbound key that carried data. */
        private NextSessionKey _hisOBKeyWithData;
        /** Root key for the next inbound receive chain. */
        private SessionKey _nextIBRootKey;

        /** Smallest NSR receive window, in keys. */
        private static final int MIN_RCV_WINDOW_NSR = 12;
        /** Largest NSR receive window, in keys. */
        private static final int MAX_RCV_WINDOW_NSR = 12;
        /** Smallest ES receive window, in keys. */
        private static final int MIN_RCV_WINDOW_ES = 24;
        /** Largest ES receive window, in keys. */
        private static final int MAX_RCV_WINDOW_ES = 320;

        /** HKDF info string for the reply tags. */
        private static final String INFO_0 = "SessionReplyTags";
        /** HKDF info string for the ratchet tag set. */
        private static final String INFO_7 = "XDHRatchetTagSet";
        /** Most acknowledgements sent in one message. */
        private static final int MAX_SEND_ACKS = 16;
        /** Most reverse keys sent in one message. */
        private static final int MAX_SEND_REVERSE_KEY = 128;

        /**
         * Outbound session for the given target.
         *
         * @param d may be null
         * @param key may be null
         * @param callback may be null. Always null for IB.
         * @param target the far-end public key this session talks to
         * @param state the completed handshake state, source of the chaining
         *              key and the role that decides the tagset direction
         */
        public OutboundSession(PublicKey target, Destination d, SessionKey key, HandshakeState state, ReplyCallback callback) {
            _target = target;
            _destination = d;
            _currentKey = key;
            _NScallback = callback;
            _established = _context.clock().now();
            _lastUsed = _established;
            _lastReceived = _established;
            _unackedTagSets = new HashSet<>(4);
            _callbacks = new ConcurrentHashMap<>();
            _acksToSend = new LinkedBlockingQueue<>();
            // generate expected tagset
            byte[] ck = state.getChainingKey();
            byte[] tagsetkey = new byte[32];
            _hkdf.calculate(ck, ZEROLEN, INFO_0, tagsetkey);
            boolean isInbound = state.getRole() == HandshakeState.RESPONDER;
            SessionKey rk = new SessionKey(ck);
            SessionKey tk = new SessionKey(tagsetkey);
            if (isInbound) {
                // We are Bob
                // This is an INBOUND NS, we make an OUTBOUND tagset for the NSR
                RatchetTagSet tagset = new RatchetTagSet(_hkdf, state, rk, tk, _established);
                _tagSet = tagset;
                _state = null;
                if (_log.shouldDebug())
                    _log.debug("New Outbound Session -> Root key: " + rk +
                               " * Tagset key: " + tk + "\n* 1st tag set:\n* " + tagset);
            } else {
                // We are Alice
                // This is an OUTBOUND NS, we make an INBOUND tagset for the NSR
                RatchetTagSet tagset = new RatchetTagSet(_hkdf, RatchetSKM.this, state, rk, tk, _established, MIN_RCV_WINDOW_NSR, MAX_RCV_WINDOW_NSR);
                // store the state so we can find the right session when we receive the NSR
                _state = state;
                if (_log.shouldDebug())
                    _log.debug("New Inbound Session -> Root key: " + rk +
                               " * Tagset key: " + tk + "\n* 1st tag set:\n* " + tagset);
            }
        }

        /**
         * Inbound or outbound. Checks state.getRole() to determine.
         * For outbound (NSR rcvd by Alice), sets session to transition to ES mode outbound.
         * For inbound (NSR sent by Bob), sets up inbound ES tagset.
         *
         * @param state the handshake state to advance, whose role decides the direction
         * @param callback only for inbound (NSR sent by Bob), may be null
         * @param split the NSR split keys - new root chaining key ck plus the
         *              per-direction session keys k_ab and k_ba
         */
        void updateSession(HandshakeState state, ReplyCallback callback, SplitKeys split) {
            SessionKey rk = split.ck;
            long now = _context.clock().now();
            _lastUsed = now;
            _lastReceived = now;
            boolean isInbound = state.getRole() == HandshakeState.RESPONDER;
            if (isInbound) {
                // We are Bob
                // This is an OUTBOUND NSR, we make an INBOUND tagset for ES
                RatchetTagSet tagset_ab = new RatchetTagSet(_hkdf, RatchetSKM.this, _target, rk, split.k_ab,
                                                            now, 0, -1,
                                                            MIN_RCV_WINDOW_ES, MAX_RCV_WINDOW_ES);
                // and a pending outbound one
                // Created eagerly rather than deferred to firstTagConsumed():
                // the key derivation is deterministic, so building it now
                // yields the same tagset the deferred path would have built,
                // and only the allocation is saved by deferring.
                RatchetTagSet tagset_ba = new RatchetTagSet(_hkdf, rk, split.k_ba,
                                                            now, 0, -1);
                if (_log.shouldDebug()) {
                    _log.debug("Update Inbound Session -> Root key: " + rk +
                               " * Tagset key: " + split.k_ab +
                               "\n* Existing Session tag set:\n* " + tagset_ab);
                    _log.debug("Pending Outbound Session -> Root key: " + rk +
                               " * Tagset key: " + split.k_ba +
                               "\n* Existing Session tag set:\n* " + tagset_ba);
                }
                synchronized (_unackedTagSets) {
                    _unackedTagSets.add(tagset_ba);
                    _NSRcallback = callback;
                }
            } else {
                // We are Alice
                // This is an INBOUND NSR, we make an OUTBOUND tagset for ES
                RatchetTagSet tagset_ab = new RatchetTagSet(_hkdf, rk, split.k_ab,
                                                            now, 0, -1);
                // and an inbound one
                RatchetTagSet tagset_ba = new RatchetTagSet(_hkdf, RatchetSKM.this, _target, rk, split.k_ba,
                                                            now, 0, -1,
                                                            MIN_RCV_WINDOW_ES, MAX_RCV_WINDOW_ES);
                if (_log.shouldDebug()) {
                    _log.debug("Update Outbound Session -> Root key: " + rk +
                               " * Tagset key: " + split.k_ab +
                               "\n* Existing Session tag set:\n* " + tagset_ab);
                    _log.debug("Update Inbound Session -> Root key: " + rk +
                               " * Tagset key: " + split.k_ba +
                               "\n* Existing Session tag set:\n* " + tagset_ba);
                }
                synchronized (_unackedTagSets) {
                    _tagSet = tagset_ab;
                    _unackedTagSets.clear();
                    // Bob received the NS, call the callback
                    if (_NScallback != null) {
                        _NScallback.onReply();
                        _NScallback = null;
                    }
                }
                // We can't destroy the original state, as more NSRs may come in
                //_state.destroy();
            }
            // kills the keys for future NSRs
            //state.destroy();
        }

        /**
         * Process a Next Session Key message, validating the ID sequence
         * before advancing the ratchet to the new tag set key.
         *
         * @param key the received next-session-key message
         * @since 0.9.46
         */
        public void nextKeyReceived(NextSessionKey key) {
            boolean isReverse = key.isReverse();
            boolean isRequest = key.isRequest();
            boolean hasKey = key.getData() != null;
            int id = key.getID();
            synchronized (_unackedTagSets) {
                if (isReverse) {
                    // this is about my outbound tag set,
                    // and is an ack of new key sent
                    if (isRequest) {
                        if (_log.shouldWarn())
                            _log.warn("Invalid req+rev in nextkey " + key);
                        return;
                    }
                    if (key.equals(_hisIBKey)) {
                        if (_log.shouldDebug())
                            _log.debug("Received duplicate nextkey for Outbound " + key);
                        return;
                    }
                    int hisLastIBKeyID;
                    if (_hisIBKey == null)
                        hisLastIBKeyID = -1;
                    else
                        hisLastIBKeyID = _hisIBKey.getID();
                    // save as it may be replaced below; will be stored after all error checks complete
                    NextSessionKey receivedKey = key;
                    if (hisLastIBKeyID != id) {
                        // got a new key, use it
                        if (hisLastIBKeyID != id - 1) {
                            if (_log.shouldInfo())
                                _log.info("Received nextkey for Outbound: " + key + " expected " + (hisLastIBKeyID + 1));
                            return;
                        }
                        if (!hasKey) {
                            if (_log.shouldWarn())
                                _log.warn("Received nextkey for Outbound without key but we don't have it " + key);
                            return;
                        }
                        // save the new key with data
                        _hisIBKeyWithData = key;
                    } else {
                        if (hasKey) {
                            // got a old key id but new data?
                            if (_hisIBKeyWithData != null && _log.shouldWarn())
                                _log.warn("Received nextkey for Outbound with data: " + key + " didn't match previous " + _hisIBKey + " / " + _hisIBKeyWithData);
                            return;
                        } else {
                            if (_hisIBKeyWithData == null ||
                                _hisIBKeyWithData.getID() != key.getID()) {
                                if (_log.shouldWarn())
                                    _log.warn("Received nextkey for Outbound without key but we don't have it " + key);
                                return;
                            }
                            // got a old key, use it
                            key = _hisIBKeyWithData;
                        }
                    }

                    int oldtsID;
                    if (_myOBKeyID == -1 && hisLastIBKeyID == -1)
                        oldtsID = 0;
                    else
                        oldtsID = 1 + _myOBKeyID + hisLastIBKeyID;
                    RatchetTagSet oldts = null;
                    if (_tagSet != null && _tagSet.getID() == oldtsID)
                        oldts = _tagSet;
                    if (oldts == null) {
                        if (_log.shouldWarn())
                            _log.warn("Received nextkey for Outbound " + key + " but can't find existing OB tagset " + oldtsID);
                        return;
                    }
                    KeyPair nextKeys = oldts.getNextKeys();
                    if (nextKeys == null) {
                        if (oldtsID == 0 || (oldtsID & 0x01) != 0 || _myOBKeys == null) {
                            if (_log.shouldWarn())
                                _log.warn("Received nextkey for Outbound " + key + " but we didn't send OB keys " + oldtsID);
                            return;
                        }
                        // reuse last keys for tsIDs 2,4,6,...
                        nextKeys = _myOBKeys;
                    } else {
                        // new keys for tsIDs 0,1,3,5...
                        _myOBKeys = nextKeys;
                        _myOBKeyID++;
                    }
                    _hisIBKey = receivedKey;

                    // create new OB TS, delete old one
                    PrivateKey priv = nextKeys.getPrivate();
                    PrivateKey sharedSecret = ECIESAEADEngine.doDH(priv, key);
                    byte[] sk = new byte[32];
                    _hkdf.calculate(sharedSecret.getData(), ZEROLEN, INFO_7, sk);
                    SessionKey ssk = new SessionKey(sk);
                    int newtsID = oldtsID + 1;
                    RatchetTagSet ts = new RatchetTagSet(_hkdf, oldts.getNextRootKey(), ssk,
                                                         _context.clock().now(), newtsID, _myOBKeyID);
                    _tagSet = ts;
                    if (_log.shouldInfo())
                        _log.info("Received nextkey " + key +
                                  "\n* From " + (_destination != null ? _destination.toBase32() : "???") +
                                  "\n* Old OutBound TagSet: " + oldts +
                                  "\n* Ratchet to new Outbound ES TS: " + ts);
                } else {
                    // this is about my inbound tag set
                    if (key.equals(_hisOBKey)) {
                        if (_log.shouldDebug())
                            _log.debug("Received duplicate nextkey for Inbound " + key);
                        return;
                    }
                    int hisLastOBKeyID;
                    if (_hisOBKey == null)
                        hisLastOBKeyID = -1;
                    else
                        hisLastOBKeyID = _hisOBKey.getID();
                    // save as it may be replaced below; will be stored after all error checks complete
                    NextSessionKey receivedKey = key;
                    if (hisLastOBKeyID != id) {
                        // got a new key, use it
                        if (hisLastOBKeyID != id - 1) {
                            if (_log.shouldWarn())
                                _log.warn("Received nextkey for Inbound: " + key + "\n* Expected: " + (hisLastOBKeyID + 1));
                            return;
                        }
                        if (!hasKey) {
                            if (_log.shouldWarn())
                                _log.warn("Received nextkey for Inbound without key but we don't have it " + key);
                            return;
                        }
                        // save the new key with data
                        _hisOBKeyWithData = key;
                    } else {
                        if (hasKey) {
                            // got a old key id but new data?
                            if (_hisOBKeyWithData != null && _log.shouldWarn())
                                _log.warn("Received nextkey for Inbound with data: " + key + " didn't match previous " + _hisOBKey + " / " + _hisOBKeyWithData);
                            return;
                        } else {
                            if (_hisOBKeyWithData == null ||
                                _hisOBKeyWithData.getID() != key.getID()) {
                                if (_log.shouldWarn())
                                    _log.warn("Received nextkey for Inbound without key but we don't have it " + key);
                                return;
                            }
                            // got a old key, use it
                            key = _hisOBKeyWithData;
                        }
                    }
                    if (_nextIBRootKey == null) {
                        // first IB ES tagset never used?
                        if (_log.shouldWarn())
                            _log.warn("Received nextkey for Inbound but we don't have next root key " + key);
                        return;
                    }
                    // The previous inbound tagset is not looked up here. The
                    // tagset id is derived arithmetically below from the two
                    // key ids, so the old tagset is only needed for its id
                    // bookkeeping; its key material is never reused. Checking
                    // whether the old inbound tagset was fully consumed would
                    // only affect a diagnostic, not the derived id.

                    int oldtsID;
                    if (_myIBKeyID == -1 && hisLastOBKeyID == -1)
                        oldtsID = 0;
                    else
                        oldtsID = 1 + _myIBKeyID + hisLastOBKeyID;
                    // generate or reuse reverse key
                    // store next key for sending via getReverseSendKey()
                    if ((oldtsID & 0x01) == 0) {
                        // new keys for 0,2,4,...
                        if (!isRequest && _log.shouldWarn())
                            _log.warn("Received reverse key without request, generating new key anyway " + key);
                        _myIBKeys = _context.commSystem().getXDHFactory().getKeys();
                        _myIBKeyID++;
                        _myIBKey = new NextSessionKey(_myIBKeys.getPublic().getData(), _myIBKeyID, true, false);
                    } else {
                        // reuse keys for 1,3,5...
                        if (_myIBKeys == null) {
                            if (_log.shouldWarn())
                                _log.warn("Received nextkey Inbound but we don't have old keys " + key);
                            return;
                        }
                        if (isRequest && _log.shouldWarn())
                            _log.warn("Received reverse with request, using old key anyway " + key);
                        _myIBKey = new NextSessionKey(_myIBKeyID, true, false);
                    }
                    _hisOBKey = receivedKey;

                    PrivateKey sharedSecret = ECIESAEADEngine.doDH(_myIBKeys.getPrivate(), key);
                    int newtsID = oldtsID + 1;
                    _myIBKeySendCount = 0;
                    // create new IB TS
                    byte[] sk = new byte[32];
                    _hkdf.calculate(sharedSecret.getData(), ZEROLEN, INFO_7, sk);
                    SessionKey ssk = new SessionKey(sk);
                    // max size from the beginning
                    RatchetTagSet ts = new RatchetTagSet(_hkdf, RatchetSKM.this, _target, _nextIBRootKey, ssk,
                                                         _context.clock().now(), newtsID, _myIBKeyID,
                                                         MAX_RCV_WINDOW_ES, MAX_RCV_WINDOW_ES);
                    _nextIBRootKey = ts.getNextRootKey();
                    if (_log.shouldInfo())
                        _log.info("Received nextkey " + key +
                                  "\n* From: [" + (_destination != null ? _destination.toBase64().substring(0,6) : "???") +
                                  "] \n* Old Inbound TagSet ID #" + oldtsID +
                                  "\n* Ratchet to new Inbound ES TagSet: " + ts);
                }
            }
        }

        /**
         * Reverse key to send, or null.
         *
         * @return the reverse send key
         * @since 0.9.46
         */
        private NextSessionKey getReverseSendKey() {
            synchronized (_unackedTagSets) {
                if (_myIBKey == null)
                    return null;
                if (_myIBKeySendCount > MAX_SEND_REVERSE_KEY)
                    return null;
                _myIBKeySendCount++;
                return _myIBKey;
            }
        }

        /**
         * A tag was received for this inbound (ES) tagset.
         *
         * @param set the inbound tagset
         * @since 0.9.46
         */
        void tagConsumed(RatchetTagSet set) {
            _lastReceived = set.getDate();
        }

        /**
         * First tag was received for this inbound (ES) tagset.
         * Find the corresponding outbound (ES) tagset in _unackedTagSets,
         * move it to _tagSets, and remove all others from _unackedTagSets.
         * <p>
         * Note: We do NOT remove remaining inbound tags from _inboundTagSets here.
         * The addTags() sliding window in consume() already handles tag lifecycle —
         * generating new tags ahead and trimming old ones via {@link #expireTag}. Purging
         * all tags on first consumption broke subsequent ES decryptions because
         * Alice's retransmitted SYNs (using pre-generated tags 1-N) could no longer
         * be found in _inboundTagSets.
         *
         * @param set the inbound tagset
         */
        void firstTagConsumed(RatchetTagSet set) {
            tagConsumed(set);
            SessionKey sk = set.getAssociatedKey();
            synchronized (_unackedTagSets) {
                // save next root key
                _nextIBRootKey = set.getNextRootKey();
                for (RatchetTagSet obSet : _unackedTagSets) {
                    if (obSet.getAssociatedKey().equals(sk)) {
                        if (_log.shouldDebug())
                            _log.debug("First tag received from Inbound ES\n* " + set +
                                       "\n* Promoting Outbound ES: " + obSet);
                        _unackedTagSets.clear();
                        _tagSet = obSet;
                        if (_NSRcallback != null) {
                            _NSRcallback.onReply();
                            _NSRcallback = null;
                        }
                        _lastUsed = _context.clock().now();
                        return;
                    }
                }
                if (_log.shouldDebug())
                    _log.debug("First tag received from Inbound Existing Session\n* " + set +
                               "\n* No corresponding Outbound Existing Session tag set found" +
                               " * Unacked size: " + _unackedTagSets.size() +
                               " * Acked size: " + ((_tagSet != null) ? 1 : 0));
            }
        }

        /**
         * This is used only by renderStatusHTML().
         * It includes both acked and unacked RatchetTagSets.
         *
         * @return list of RatchetTagSet objects
         */
        List<RatchetTagSet> getTagSets() {
            List<RatchetTagSet> rv;
            synchronized (_unackedTagSets) {
                rv = new ArrayList<>(_unackedTagSets);
                if (_tagSet != null)
                    rv.add(_tagSet);
            }
            return rv;
        }
        /**
         * The far-end public key this session talks to.
         *
         * @return the target key, set at construction
         */
        public PublicKey getTarget() {
            return _target;
        }

        /**
         * Original outbound state, null for inbound.
         * @return the handshake state
         */
        public HandshakeState getHandshakeState() {
            return _state;
        }
        /**
         * The session key stored at construction; never updated afterwards.
         *
         * @return the key, or null if the session was created without one
         */
        public SessionKey getCurrentKey() {
            return _currentKey;
        }
        /**
         * When this session was established.
         *
         * @return creation time in ms
         */
        public long getEstablishedDate() {
            return _established;
        }

        /**
         * NOT updated for inbound except for NSR and first ES tag used.
         * @return the last used date
         */
        public long getLastUsedDate() {
            return _lastUsed;
        }

        /**
         * ONLY updated for inbound NS/NSR/ES tag used.
         *
         * @return the last received date
         * @since 0.9.46
         */
        public long getLastReceivedDate() {
            return _lastReceived;
        }

        /**
         * Expire old tags, returning the number of tag sets removed.
         *
         * @param now current time in ms, tag sets expiring at or before it
         * are removed
         * @return the number of tag sets removed
         */
        public int expireTags(long now) {
            int removed = 0;
            synchronized (_unackedTagSets) {
                if (_tagSet != null && _tagSet.getExpiration() <= now) {
                    _tagSet = null;
                    removed++;
                }
                for (Iterator<RatchetTagSet> iter = _unackedTagSets.iterator(); iter.hasNext(); ) {
                    RatchetTagSet set = iter.next();
                    if (set.getExpiration() <= now) {
                        iter.remove();
                        removed++;
                    }
                }
            }
            return removed;
        }
        /**
         * Consume the next available tag and advance.
         *
         * @return the next tag with its key, rotation keys and pending acks,
         * or null if the session or tag set has expired or drained
         */
        public RatchetEntry consumeNext() {
            long now = _context.clock().now();
            if (_lastReceived + SESSION_TAG_DURATION_MS < now) {
                if (_log.shouldInfo())
                    _log.info("Expired Outbound session because Inbound TagSet expired");
                return null;
            }
            synchronized (_unackedTagSets) {
                if (_tagSet != null) {
                    if (_ackTimer != null) {
                        // cancel all ratchet-layer acks
                        _ackTimer.cancel();
                        _ackTimer = null;
                    }
                    synchronized(_tagSet) {
                        // use even if expired, this will reset the expiration
                        RatchetSessionTag tag = _tagSet.consumeNext();
                        if (tag != null) {
                            _lastUsed = now;
                            _tagSet.setDate(now);
                            SessionKeyAndNonce skn = _tagSet.consumeNextKey();
                            // Tracked limitation: the previous chain length is
                            // always reported as 0. A PNBlock is never written
                            // (RatchetPayload.PNBlock has no caller) and a
                            // received PN is logged and discarded by
                            // ECIESAEADEngine.PLCallback.gotPN(). A peer that
                            // reconstructs its ratchet state from the reported
                            // length alone will therefore see 0 and must fall
                            // back to its own key-id accounting; skipping key
                            // recovery across such a peer is not supported.
                            NextSessionKey fwd = _tagSet.getNextKey();
                            NextSessionKey rev = getReverseSendKey();
                            if ((fwd != null || rev != null) && _log.shouldInfo())
                                _log.info("Sending keys for Set " +
                                          setIdLabel(_tagSet.getID(), _tagSet.getDebugID()) +
                                          (fwd != null ? " * " + keyFingerprint(fwd, "Forward") : "") +
                                          (rev != null ? " * " + keyFingerprint(rev, "Reverse") : ""));
                            return new RatchetEntry(tag, skn, _tagSet.getID(), 0, fwd,
                                                    rev, getAcksToSend());
                        } else if (_log.shouldInfo()) {
                            _log.info("Removing empty " + _tagSet);
                        }
                    }
                    _tagSet = null;
                }
            }
            return null;
        }

        /**
         * A timer that we will cancel when we send something.
         * Side effect - binds this session to the supplied destination.
         *
         * @param d the far-end Destination for this PublicKey if known, or null
         * @param timer the ack timer to hold, cancelled when this session
         *              sends; ignored if a timer is already registered
         * @return true if registered
         * @since 0.9.47
         */
        public boolean registerTimer(Destination d, SimpleTimer2.TimedEvent timer) {
            synchronized (_unackedTagSets) {
                if (_ackTimer != null)
                    return false;
                if (d != null) {
                    if (_destination == null)
                        _destination = d;
                    else if (_log.shouldWarn() && !_destination.equals(d))
                        _log.warn("Destination mismatch? was: " + _destination.toBase32() + " now: " + d.toBase32());
                }
                _ackTimer = timer;
                if (_log.shouldDebug())
                    _log.debug("Registered an ack timer to: " + (_destination != null ? _destination.toBase32() : _target.toString()));
            }
            return true;
        }

        /**
         * Destination for the current session's far end.
         *
         * @return the far-end Destination for this PublicKey, or null
         * @since 0.9.47
         */
        public Destination getDestination() {
            synchronized (_unackedTagSets) {
                return _destination;
            }
        }

        /**
         * Number of tags remaining in acked tag sets.
         *
         * @return the total number of tags in acked RatchetTagSets
         */
        public int availableTags() {
            long now = _context.clock().now();
            synchronized (_unackedTagSets) {
                if (_tagSet != null) {
                    synchronized(_tagSet) {
                        if (_tagSet.getExpiration() > now)
                            return _tagSet.remaining();
                    }
                }
            }
            return 0;
        }

        /**
         * Furthest away tag set expiration date, after which all of the
         * tags will have expired.
         *
         * @return the last expiration date
         */
        public long getLastExpirationDate() {
            synchronized (_unackedTagSets) {
                if (_tagSet != null)
                    return _tagSet.getExpiration();
            }
            return -1;
        }

        /**
         * Register the callback to run when the ACK for one ratchet tag
         * arrives; a live callback for the same tag is not replaced.
         *
         * @param id tag set ID the ACK is for
         * @param n tag index within the tag set
         * @param callback callback to run on ACK
         * @since 0.9.46
         */
        public void registerCallback(int id, int n, ReplyCallback callback) {
            Integer key = Integer.valueOf((id << 16) | n);
            ReplyCallback old = _callbacks.putIfAbsent(key, callback);
            if (old != null) {
                if (old.getExpiration() < _context.clock().now())
                    _callbacks.put(key, callback);
                else if (_log.shouldWarn())
                    _log.warn("Not replacing callback: " + old);
            }
        }

        /**
         * Run and remove the callback registered for the ACKed tag, if any.
         *
         * @param id tag set ID the ACK is for
         * @param n tag index within the tag set
         * @since 0.9.46
         */
        public void receivedACK(int id, int n) {
            Integer key = Integer.valueOf((id << 16) | n);
            ReplyCallback callback = _callbacks.remove(key);
            if (callback != null) {
                if (_log.shouldInfo())
                    _log.info("ACK received ID: " + id + " * n: " + n + " * callback: " + callback);
                callback.onReply();
            } else {
                if (_log.shouldInfo())
                    _log.info("ACK received ID: " + id + " * n: " + n + " -> No callback");
            }
        }

        /**
         * Queue the ACK for one ratchet tag to be sent back.
         *
         * @param id tag set ID being ACKed
         * @param n tag index within the tag set
         * @since 0.9.46
         */
        public void ackRequested(int id, int n) {
            Integer key = Integer.valueOf((id << 16) | n);
            _acksToSend.offer(key);
        }

        /**
         * Pending acks drained from the queue, up to the send limit.
         *
         * @return the acks to send, non empty, or null
         * @since 0.9.46
         */
        private List<Integer> getAcksToSend() {
            if (_acksToSend == null)
                return null;
            int sz = _acksToSend.size();
            if (sz == 0)
                return null;
            List<Integer> rv = new ArrayList<>(Math.min(sz, MAX_SEND_ACKS));
            _acksToSend.drainTo(rv, MAX_SEND_ACKS);
            if (rv.isEmpty())
                return null;
            return rv;
        }

        /**
         * Drop reply callbacks whose expiration has passed.
         *
         * @param now current time in ms
         * @return the number of callbacks removed
         * @since 0.9.46
         */
        public int expireCallbacks(long now) {
            if (_callbacks.isEmpty())
                return 0;
            int rv = 0;
            for (Iterator<ReplyCallback> iter = _callbacks.values().iterator(); iter.hasNext();) {
                ReplyCallback cb = iter.next();
                if (cb.getExpiration() < now) {
                    iter.remove();
                    rv++;
                }
            }
            return rv;
        }
    }
}

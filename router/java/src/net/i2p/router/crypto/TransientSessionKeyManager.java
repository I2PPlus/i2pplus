package net.i2p.router.crypto;

/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain
 * with no warranty of any kind, either expressed or implied.
 * It probably won't  make your computer catch on fire, or eat
 * your children, but it might.  Use at your own risk.
 */

import java.io.IOException;
import java.io.Serializable;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import net.i2p.I2PAppContext;
import net.i2p.crypto.EncType;
import net.i2p.crypto.SessionKeyManager;
import net.i2p.crypto.TagSetHandle;
import net.i2p.data.DataHelper;
import net.i2p.data.PublicKey;
import net.i2p.data.SessionKey;
import net.i2p.data.SessionTag;
import net.i2p.util.Log;
import net.i2p.util.SimpleTimer2;

/**
 * Implement the session key management, but keep everything in memory (don't write to disk).
 * However, this being java, we cannot guarantee that the keys aren't swapped out to disk so
 * this should not be considered secure in that sense.
 * <p>
 * The outbound and inbound sides are completely independent, each with their own keys and tags.
 * <p>
 * For a new session, outbound tags are not considered delivered until an ack is received.
 * Otherwise, the loss of the first message would render all subsequent messages undecryptable.
 * <p>
 * For an existing session, outbound tags are immediately considered delivered, and are later
 * revoked if the ack times out. This prevents massive stream slowdown caused by repeated tag
 * delivery after the minimum tag threshold is reached. Included tags pushes messages above the
 * ideal 1956 size by ~2KB and causes excessive fragmentation and padding. As the tags are not
 * seen by the streaming lib, they aren't accounted for in the window size, and one or more of a
 * series of large messages is likely to be dropped, either due to high fragmentation or drop
 * priorities at the tunnel OBEP.
 * <p>
 * For this to work, the minimum tag threshold and tag delivery quantity defined in
 * GarlicMessageBuilder must be chosen with streaming lib windows sizes in mind.
 * If a single TagSet is not delivered, there will be no stall as long as the current window
 * size is smaller than the minimum tag threshold. Additional TagSets will be sent before the
 * acked tags completely run out. See below. All subsequent messages will fail to decrypt.
 * See ConnectionOptions in streaming for more information.
 * <p>
 * There are large inefficiencies caused by the repeated delivery of tags in a new session.
 * With an initial streaming window size of 6 and 40 tags per delivery, a web server would
 * deliver up to 240 tags (7680 bytes, not including bundled leaseset, etc.) in the first
 * volley of the response.
 * <p>
 * Could the two directions be linked somehow, such that the initial request could contain a
 * key or tags for the response?
 * <p>
 * Should the tag threshold and quantity be adaptive?
 * <p>
 * Todo: Switch to ConcurrentHashMaps and ReadWriteLocks, only get write lock during cleanup.
 */
public class TransientSessionKeyManager extends SessionKeyManager {
    /** Logger for transient session management. */
    private final Log _log;
    /** Map allowing us to go from the targeted PublicKey to the OutboundSession used. */
    private final Map<PublicKey, OutboundSession> _outboundSessions;
    /**
     * Map allowing us to go from a SessionTag to the containing TagSet.
     * Concurrent: a decrypt takes a tag out of it on every inbound message, so
     * the map itself is not a lock. What a TagSet's own contents are guarded by
     * is the TagSet - see consumeTag().
     */
    private final Map<SessionTag, TagSet> _inboundTagSets;
    /** The router context. */
    protected final I2PAppContext _context;
    /** False once the manager has shut down. */
    private volatile boolean _alive;
    /** For debugging. */
    private final AtomicInteger _rcvTagSetID = new AtomicInteger();
    /** Counter handing out sent tag set ids. */
    private final AtomicInteger _sentTagSetID = new AtomicInteger();
    /** Tags included in each tag set sent. */
    private final int _tagsToSend;
    /** Tag count below which more tag sets are sent. */
    private final int _lowThreshold;

    /**
     * Let outbound session tags sit around for this long before expiring them.
     * Inbound tag expiration is set by SESSION_LIFETIME_MAX_MS.
     */
    private static final long SESSION_TAG_DURATION_MS = 12 * 60 * 1000L;

    /**
     * Keep unused inbound session tags around for this long (a few minutes longer than
     * session tags are used on the outbound side so that no reasonable network lag
     * can cause failed decrypts)
     * <p>
     * This is also the max idle time for an outbound session.
     */
    private static final long SESSION_LIFETIME_MAX_MS = SESSION_TAG_DURATION_MS + 2 * 60 * 1000L;

    /**
     * Time to send more if we are this close to expiration.
     */
    private static final long SESSION_TAG_EXPIRATION_WINDOW = 90 * 1000L;

    /**
     * Maximum number of entries in _inboundTagSets.
     * <p>
     * This is a memory bound, not a count target. Measured on a 64-bit JVM with
     * compressed oops, an entry costs about 126 bytes - the 32-byte tag and its
     * wrapper, the map node, and the hash table slot - so this cap is worth
     * roughly 96 MB. The hourly cleanup only trims once the cap is already
     * exceeded, so that figure is the steady-state ceiling, not a soft target.
     */
    public static final int MAX_INBOUND_SESSION_TAGS = 800 * 1000;

    /**
     * This was 100 since 0.6.1.10 (50 before that). It's important because:
     *  <pre>
     *  - Tags are 32 bytes. So it previously added 3200 bytes to an initial message.
     *  - Too many tags adds a huge overhead to short-duration connections
     *  (like http, datagrams, etc.)
     *  - Large messages have a much higher chance of being dropped due to one of their
     *  1KB fragments being discarded by a tunnel participant.
     *  - This reduces the effective maximum datagram size because the client doesn't
     *  know when tags will be bundled, so the tag size must be subtracted from the
     *  maximum I2NP size or transport limit.
     * </pre>
     * <p>
     * Issues with too small a value:
     * <pre>
     *  - When tags are sent, a reply leaseset (~1KB) is always bundled. Maybe we don't need
     *  to bundle more than every minute or so rather than every time?
     *  - Does the number of tags (and the threshold of 20) limit the effective streaming lib
     *  window size? Should the threshold and the number of sent tags be variable based on
     *  the message rate?
     * </pre>
     * <p>
     * We have to be very careful if we implement an adaptive scheme, since the key manager
     * is per-router, not per-local-dest. Or maybe that's a bad idea, and we need to move to
     * a per-dest manager. This needs further investigation.
     * <p>
     * So a value somewhat higher than the low threshold seems appropriate.
     * <p>
     * Use care when adjusting these values. See ConnectionOptions in streaming,
     * and TransientSessionKeyManager in crypto, for more information.
     *
     * @since 0.9.2 moved from GarlicMessageBuilder to per-SKM config
     */
    public static final int DEFAULT_TAGS = 40;
    /** Same as DEFAULT_RECHECK_INTERVAL. */
    public static final int LOW_THRESHOLD = 30;

    /**
     * The session key manager should only be constructed and accessed through the
     * application context.  This constructor should only be used by the
     * appropriate application context itself.
     *
     * @param context the application context this manager is a service of, whose
     *                log manager supplies the logger
     */
    public TransientSessionKeyManager(I2PAppContext context) {
        this(context, DEFAULT_TAGS, LOW_THRESHOLD);
    }

    /**
     * Log manager supplies the logger.
     *
     * @param context the application context this manager is a service of, whose
     * @param tagsToSend how many to send at a time, may be lower or higher than lowThreshold. 1-128
     * @param lowThreshold below this, send more. 1-128
     * @since 0.9.2
     */
    public TransientSessionKeyManager(I2PAppContext context, int tagsToSend, int lowThreshold) {
        super(context);
        if (tagsToSend <= 0 || tagsToSend > 128 || lowThreshold <= 0 || lowThreshold > 128)
            throw new IllegalArgumentException();
        _tagsToSend = tagsToSend;
        _lowThreshold = lowThreshold;
        _log = context.logManager().getLog(TransientSessionKeyManager.class);
        _context = context;
        _outboundSessions = new HashMap<>(64);
        _inboundTagSets = new ConcurrentHashMap<>(128);
         _alive = true;
        new CleanupEvent().schedule(60L*1000);
    }

    /**
     * Marks the manager dead and drops every tracked session and tag set.
     */
    @Override
    public void shutdown() {
        _alive = false;
        _inboundTagSets.clear();
        synchronized (_outboundSessions) {_outboundSessions.clear();}
    }

    /** Periodically cleans up stale tag sets. */
    private class CleanupEvent extends SimpleTimer2.TimedEvent {
        /** Creates cleanup timer running recurring maintenance on sessions. */
        public CleanupEvent() { super(_context.simpleTimer2()); }
        /** Perform recurring maintenance. */
        public void timeReached() {
            if (!_alive) {return;}
            aggressiveExpire();
            int overage = _inboundTagSets.size() - MAX_INBOUND_SESSION_TAGS;
            if (overage > 0) {clearExcess(overage);}
            schedule(60L*1000);
        }
    }

    /**
     * Snapshot of all inbound tag sets.
     *
     * @return a new set, safe to iterate
     */
    private Set<TagSet> getInboundTagSets() {
        return new HashSet<>(_inboundTagSets.values());
    }

    /**
     * OutboundSession - used only by HTML.
     *
     * @return a snapshot of all outbound sessions
     */
    private Set<OutboundSession> getOutboundSessions() {
        synchronized (_outboundSessions) {
            return new HashSet<>(_outboundSessions.values());
        }
    }

    /**
     * Retrieve the session key currently associated with encryption to the target,
     * or null if a new session key should be generated.
     * <p>
     * Warning - don't generate a new session if this returns null, it's racy, use {@link #getCurrentOrNewKey}.
     * @return the current key
     */
    @Override
    public SessionKey getCurrentKey(PublicKey target) {
        OutboundSession sess = getSession(target);
        if (sess == null) return null;
        long now = _context.clock().now();
        if (sess.getLastUsedDate() < now - SESSION_LIFETIME_MAX_MS) {
            if (_log.shouldInfo())
                _log.info("Expiring old session key established on "
                          + new Date(sess.getEstablishedDate())
                          + " but not used for "
                          + (now-sess.getLastUsedDate())
                          + "ms with target " + toString(target));
            return null;
        }
        return sess.getCurrentKey();
    }

    /**
     * Retrieve the session key currently associated with encryption to the target.
     * Generates a new session and session key if not previously existing.
     *
     * @param target public key to which the data should be encrypted, must be ELGAMAL_2048.
     * @return non-null
     * @throws IllegalArgumentException on bad target EncType
     */
    @Override
    public SessionKey getCurrentOrNewKey(PublicKey target) {
        synchronized (_outboundSessions) {
            OutboundSession sess = _outboundSessions.get(target);
            if (sess != null) {
                long now = _context.clock().now();
                if (sess.getLastUsedDate() < now - SESSION_LIFETIME_MAX_MS)
                    sess = null;
            }
            if (sess == null) {
                SessionKey key = _context.keyGenerator().generateSessionKey();
                createAndReturnSession(target, key);
                return key;
            }
            return sess.getCurrentKey();
        }
    }

    /**
     * Associate a new session key with the specified target.  Metrics to determine
     * when to expire that key begin with this call.
     * <p>
     * Racy if called after {@link #getCurrentKey} to check for a current session;
     * use getCurrentOrNewKey() in that case.
     *
     * @param target public key to which the data should be encrypted, must be ELGAMAL_2048.
     * @throws IllegalArgumentException on bad target EncType
     */
    @Override
    public void createSession(PublicKey target, SessionKey key) {
        createAndReturnSession(target, key);
    }

    /**
     * Same as above but for internal use, returns OutboundSession so we don't have
     * to do a subsequent {@link #getSession(PublicKey)}.
     *
     * @param target the peer's public key, must be ElGamal 2048
     * @param key the session key the new session uses
     * @return the newly created outbound session
     */
    private OutboundSession createAndReturnSession(PublicKey target, SessionKey key) {
        EncType type = target.getType();
        if (type != EncType.ELGAMAL_2048)
            throw new IllegalArgumentException("Bad public key type " + type);
        if (_log.shouldInfo())
            _log.info("New Outbound session: Session key [" + key.toBase64().substring(0,6) + "] Target: ["
                      + toString(target).substring(0,6) + "]");
        OutboundSession sess = new OutboundSession(_context, _log, target, key);
        addSession(sess);
        return sess;
    }

    /**
     * Retrieve the next available session tag for identifying the use of the given
     * key when communicating with the target.  If this returns null, no tags are
     * available so ElG should be used with the given key (a new sessionKey should
     * NOT be used).
     */
    @Override
    public SessionTag consumeNextAvailableTag(PublicKey target, SessionKey key) {
        OutboundSession sess = getSession(target);
        if (sess == null) {
            if (_log.shouldWarn())
                _log.warn("No session for " + toString(target));
            return null;
        }
        if (sess.getCurrentKey().equals(key)) {
            // logged in OutboundSession
            return sess.consumeNext();
        }
        if (_log.shouldWarn())
            _log.warn("Key does not match existing key, no tag");
        return null;
    }

    /**
     * How many to send, IF we need to.
     *
     * @return the configured value (not adjusted for current available)
     * @since 0.9.2
     */
    @Override
    public int getTagsToSend() { return _tagsToSend; }

    /**
     * Tag count below which new tags are sent.
     *
     * @return the configured value
     * @since 0.9.2
     */
    @Override
    public int getLowThreshold() { return _lowThreshold; }

    /**
     * Whether to send a new tag set to the target.
     *
     * @return true if we have less than the threshold or what we have is about to expire
     * @since 0.9.2
     */
    @Override
    public boolean shouldSendTags(PublicKey target, SessionKey key, int lowThreshold) {
        return getAvailableTags(target, key) < lowThreshold ||
               getAvailableTimeLeft(target, key) < SESSION_TAG_EXPIRATION_WINDOW;
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
        if (sess.getCurrentKey().equals(key)) {
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
        if (sess.getCurrentKey().equals(key)) {
            long end = sess.getLastExpirationDate();
            if (end <= 0)
                return 0;
            else
                return end - _context.clock().now();
        }
        return 0;
    }

    /**
     * Take note of the fact that the given sessionTags associated with the key for
     * encryption to the target have been sent.
     * <p>Delivered tags are never usable straight away: every tag set starts
     * unacked and only moves into the usable set when the peer acks it through
     * {@link #tagsAcked(PublicKey, SessionKey, TagSetHandle)}. The ack is what
     * proves the peer received the tags, so the implementation waits for it
     * unconditionally rather than pipelining on how much was sent.
     * <p>If the session does not exist yet it is created here, so the first ack
     * also establishes the session. A tag set delivered under a different
     * session key than the session currently holds is a rekey: the existing tag
     * sets are dropped and the session goes back to waiting for acks.
     *
     * @param target public key to which the data is to be encrypted
     * @param key the session key the tags are bound to
     * @param sessionTags the tags that were sent
     * @return the TagSetHandle, or null if the session could not be created.
     *         Caller MUST subsequently call {@link #failTags} or tagsAcked()
     *         with this handle.
     */
    @Override
    public TagSetHandle tagsDelivered(PublicKey target, SessionKey key, Set<SessionTag> sessionTags) {
        OutboundSession sess = getOrCreateSession(target, key);
        if (sess == null) return null;
        TagSet set = new TagSet(sessionTags, key, _context.clock().now(), _sentTagSetID.incrementAndGet());
        sess.addTags(set);
        if (_log.shouldDebug())
            _log.debug("Tags delivered: " + set +
                       "\n* Target: " + toString(target) /** + ": " + sessionTags */ );
        return set;
    }

    /**
     * Mark all of the tags delivered to the target up to this point as invalid, since the peer
     * has failed to respond when they should have.  This call essentially lets the system recover
     * from corrupted tag sets and crashes.
     *
     * @deprecated unused and rather drastic
     */
    @Override
    @Deprecated
    public void failTags(PublicKey target) {
        removeSession(target);
    }

    /**
     * Mark these tags as invalid, since the peer
     * has failed to ack them in time.
     */
    @Override
    public void failTags(PublicKey target, SessionKey key, TagSetHandle ts) {
        OutboundSession sess = getSession(target);
        if (sess == null) {
            if (_log.shouldWarn())
                _log.warn("No session for failed TagSet: " + ts);
            return;
        }
        if(!key.equals(sess.getCurrentKey())) {
            if (_log.shouldWarn())
                _log.warn("Wrong session key (wanted " + sess.getCurrentKey() + ") for failed TagSet: " + ts);
            return;
        }
        if (_log.shouldWarn())
            _log.warn("Failed " + ts);
        sess.failTags((TagSet)ts);
    }

    /**
     * Mark these tags as acked, start to use them (if we haven't already)
     * If the set was previously failed, it will be added back in.
     */
    @Override
    public void tagsAcked(PublicKey target, SessionKey key, TagSetHandle ts) {
        OutboundSession sess = getSession(target);
        if (sess == null) {
            if (_log.shouldWarn())
                _log.warn("No session for ACKed TagSet: " + ts);
            return;
        }
        if(!key.equals(sess.getCurrentKey())) {
            if (_log.shouldWarn())
                _log.warn("Wrong session key (wanted " + sess.getCurrentKey() + ") for ACKed TagSet: " + ts);
            return;
        }
        if (_log.shouldDebug())
            _log.debug("ACKed: " + ts);
        sess.ackTags((TagSet)ts);
    }

    /**
     * Accept the given tags and associate them with the given key for decryption.
     *
     * @param sessionTags modifiable; NOT copied
     */
    @Override
    public void tagsReceived(SessionKey key, Set<SessionTag> sessionTags) {
        tagsReceived(key, sessionTags, SESSION_LIFETIME_MAX_MS);
    }

    /**
     * Accept the given tags and associate them with the given key for decryption.
     *
     * @param sessionTags modifiable; NOT copied. Non-null, non-empty.
     * @param expire time from now
     * @since 0.9.7
     */
    @Override
    public void tagsReceived(SessionKey key, Set<SessionTag> sessionTags, long expire) {
        TagSet tagSet = new TagSet(sessionTags, key, _context.clock().now() + expire,
                                   _rcvTagSetID.incrementAndGet());
        if (_log.shouldDebug()) {
            _log.debug("Received " + tagSet);
        }
        TagSet old = null;
        SessionTag dupTag = null;
        for (SessionTag tag : sessionTags) {
            old = _inboundTagSets.put(tag, tagSet);
            if (old != null) {
                if (!old.getAssociatedKey().equals(tagSet.getAssociatedKey())) {
                    _inboundTagSets.remove(tag);
                    dupTag = tag;
                    break;
                } else {
                    old = null; // ignore the dup
                }
            }
        }

        if (old != null) {
            // drop both old and tagSet tags
            // old's tag list is read here, so the tag set is what has to be
            // locked; the map operations need no lock of their own.
            synchronized (old) {
                for (SessionTag tag : old.getTags()) {
                    _inboundTagSets.remove(tag);
                }
            }
            for (SessionTag tag : sessionTags) {
                _inboundTagSets.remove(tag);
            }

            if (_log.shouldWarn()) {
                _log.warn("Multiple tags matching! TagSet: " + tagSet + " and old TagSet: " + old + " Tag: " + dupTag + "/" + dupTag);
                _log.warn("Earlier tag set creation: " + old + ": key=" + old.getAssociatedKey());
                _log.warn("Current tag set creation: " + tagSet + ": key=" + tagSet.getAssociatedKey());
            }
        }
    }

    /**
     * Remove a bunch of arbitrarily selected tags, then drop all of
     * the associated tag sets.  This is very time consuming - iterating
     * across the entire _inboundTagSets map, but it should be very rare,
     * and the stats we can gather can hopefully reduce the frequency of
     * using too many session tags in the future.
     *
     * @param overage the number of tags by which the limit was exceeded, the
     *                cleanup removes twice this many
     */
    private void clearExcess(int overage) {
        long now = _context.clock().now();
        int old = 0;
        int large = 0;
        int absurd = 0;
        int recent = 0;
        int tags = 0;
        int toRemove = overage * 2;
        _log.logAlways(Log.WARN, "Session tag limit exceeded -> Starting cleanup; overage: " + overage);
        List<TagSet> removed = new ArrayList<>(toRemove);
        for (TagSet set : _inboundTagSets.values()) {
            int size;
            synchronized (set) {
                size = set.getTags().size();
            }
            if (size > 1000)
                absurd++;
            if (size > 100)
                large++;
            if (set.getDate() - now < 3*60*1000) {
                // expiration is 12 minutes, so these are older than 9 minutes
                old++;
                removed.add(set);
                continue;
            } else if (set.getDate() - now > 8*60*1000) {
                // expiration is 12 minutes, so these were created in last 4 minutes
                recent++;
                continue;
            }

            if (removed.size() < toRemove)
                removed.add(set);
        }
        for (int i = 0; i < removed.size(); i++) {
            TagSet cur = removed.get(i);
            synchronized (cur) {
                for (SessionTag tag : cur.getTags()) {
                    _inboundTagSets.remove(tag);
                    tags++;
                }
            }
        }
        _log.logAlways(Log.WARN, "TOO MANY SESSION TAGS! Removed " + removed.size()
                     + " tag sets arbitrarily, with " + tags + " tags,"
                     + "where there are " + old + " long lasting sessions, "
                     + recent + " ones created in the last few minutes, and "
                     + large + " sessions with more than 100 tags (and "
                     + absurd + " with more than 1000!), leaving a total of "
                     + _inboundTagSets.size() + " tags behind");
    }

    /**
     * Determine if we have received a session key associated with the given session tag,
     * and if so, discard it (but keep track for frequent dups) and return the decryption
     * key it was received with (via tagsReceived(...)).  returns null if no session key
     * matches.
     */
    @Override
    public SessionKey consumeTag(SessionTag tag) {
        // Taking the tag out of the map is atomic by itself, so the map is not
        // part of this critical section - only the tag set the tag came from,
        // whose remaining tags have to be updated consistently. This is the
        // inbound decrypt path: a single monitor around the whole map would put
        // every legacy decrypt in the router in one queue.
        TagSet tagSet = _inboundTagSets.remove(tag);
        if (tagSet == null) {
            if (_log.shouldDebug())
                _log.debug("Cannot process unknown Inbound Tag [" +
                           tag.toString().substring(0,6) + "]");
            return null;
        }
        synchronized (tagSet) {
            tagSet.consume(tag);
        }

        SessionKey key = tagSet.getAssociatedKey();
        if (_log.shouldDebug())
            _log.debug("Inbound Tag [" + tag.toString().substring(0,6) + "] processed from " + tagSet);
        return key;
    }

    /**
     * Returns the pending session for a target key.
     *
     * @param target the peer's public key
     * @return the session, or null if none is pending
     */
    private OutboundSession getSession(PublicKey target) {
        synchronized (_outboundSessions) {
            return _outboundSessions.get(target);
        }
    }

    /**
     * The session for a target with its current key brought up to date, or a new
     * one built under key if there is none yet.
     * <p>
     * Lookup and insert are one step under the session-map monitor, the same
     * shape getCurrentOrNewKey() uses. Taken as a lookup and then a create, two
     * clove threads handing over tags for the same target each found no session
     * and each built one, and the second put discarded the first along with the
     * tag sets already waiting on it.
     *
     * @param target the peer the session is with, ELGAMAL_2048
     * @param key the session key the caller's tags are bound to
     * @return the session to attach the tag set to, or null if none could be built
     * @since 0.9.72+
     */
    private OutboundSession getOrCreateSession(PublicKey target, SessionKey key) {
        synchronized (_outboundSessions) {
            OutboundSession sess = _outboundSessions.get(target);
            if (sess == null) {
                if (_log.shouldWarn())
                    _log.warn("No session for delivered TagSet to target: " + toString(target));
                return createAndReturnSession(target, key);
            }
            sess.setCurrentKey(key);
            return sess;
        }
    }

    /**
     * Registers a new outbound session.
     *
     * @param sess the session to add
     */
    private void addSession(OutboundSession sess) {
        synchronized (_outboundSessions) {
            _outboundSessions.put(sess.getTarget(), sess);
        }
    }

    /**
     * Drops the pending session for a target key.
     *
     * @param target the peer's public key
     */
    private void removeSession(PublicKey target) {
        if (target == null) return;
        OutboundSession session = null;
        synchronized (_outboundSessions) {
            session = _outboundSessions.remove(target);
        }
        if ( (session != null) && (_log.shouldWarn()) )
            _log.warn("Removing session tags -> Available tags: " + session.availableTags() +
                      " * Expires in: " +
                      (session.getLastExpirationDate()-_context.clock().now()) + "ms");
    }

    /**
     * Aggressively expire inbound tag sets and outbound sessions.
     *
     * @return number of tag sets expired (bogus as it overcounts inbound)
     */
    private int aggressiveExpire() {
        int removed = 0;
        int remaining = 0;
        long now = _context.clock().now();

        for (Iterator<TagSet> iter = _inboundTagSets.values().iterator(); iter.hasNext();) {
            TagSet ts = iter.next();
            // for inbound tagsets, getDate() is the expire time
            if (ts.getDate() <= now) {
                iter.remove();
                // bug, this counts inbound tags, not tag sets
                removed++;
            }
        }
        remaining = _inboundTagSets.size();
        if (remaining > 500) {
            // find SessionKeys with a large number of TagSets and trim them
            Map<SessionKey, Set<TagSet>> inboundSets = getInboundTagSetsBySessionKey();
            for (Map.Entry<SessionKey, Set<TagSet>> e : inboundSets.entrySet()) {
                Set<TagSet> sets = e.getValue();
                int count = sets.size();
                if (count >= 10) {
                    if (_log.shouldInfo())
                        _log.info("Trimming tag sets -> Session key with " + count + " tag sets");
                    // for any session key with at least 10 tagsets,
                    // remove all tagsets larger than 8 tags that haven't been used and
                    // are old. The more the tagsets, the more aggressively we expire.
                    // From 9 minutes at 10 down to one minute at 50
                    long backoff = (long) (count - 10) * 8*60*1000L / 40;
                    long age = Math.min(5*60*1000L, Math.max(60*1000L, 9*60*1000L - backoff));
                    for (TagSet ts : sets) {
                        synchronized (ts) {
                            Set<SessionTag> tags = ts.getTags();
                            int curSize = tags.size();
                            int origSize = ts.getOriginalSize();
                            long expires = ts.getDate();
                            if (curSize == origSize && curSize > 8 &&
                                expires < now + SESSION_LIFETIME_MAX_MS - age) {
                                if (_log.shouldInfo())
                                    _log.info("Removed unused TagSet " + ts);
                                for (SessionTag tag : tags) {
                                    _inboundTagSets.remove(tag);
                                }
                                removed += curSize;
                            }
                        }
                    }
                }
            }
        }
        if (removed > 0 && _log.shouldInfo())
            _log.info("Expired " + removed + " Inbound tags");

        int oremoved = 0;
        synchronized (_outboundSessions) {
            for (Iterator<OutboundSession> iter = _outboundSessions.values().iterator(); iter.hasNext();) {
                OutboundSession sess = iter.next();
                oremoved += sess.expireTags();
                // don't kill a new session or one that's temporarily out of tags
                if (sess.getLastUsedDate() < now - (SESSION_LIFETIME_MAX_MS / 2) &&
                    sess.availableTags() <= 0) {
                    iter.remove();
                    oremoved++;   // just to have a non-zero return value?
                }
            }
        }
        if (oremoved > 0 && _log.shouldInfo())
            _log.info("Expired " + oremoved + " Outbound tags");
        return removed + oremoved;
    }

    /**
     * Return a map of session key to a set of inbound TagSets for that SessionKey.
     *
     * @return the inbound tag sets by session key
     * @since 0.9.33 split out from {@link #renderStatusHTML(Writer)}
     */
    private Map<SessionKey, Set<TagSet>> getInboundTagSetsBySessionKey() {
        Set<TagSet> inbound = getInboundTagSets();
        Map<SessionKey, Set<TagSet>> inboundSets = new HashMap<>(inbound.size());
        // Build a map of the inbound tag sets, grouped by SessionKey
        for (TagSet ts : inbound) {
            Set<TagSet> sets = inboundSets.get(ts.getAssociatedKey());
            if (sets == null) {
                sets = new HashSet<>(4);
                inboundSets.put(ts.getAssociatedKey(), sets);
            }
            sets.add(ts);
        }
        return inboundSets;
    }

    /** Renders inbound/outbound ElGamal session state as debug HTML tables. */
    @Override
    public void renderStatusHTML(Writer out) throws IOException {
        StringBuilder buf = new StringBuilder(1024);
        buf.append("<h3 class=debug_inboundsessions>ElGamal Inbound Sessions</h3>\n" +
                   "<table>\n");
        Map<SessionKey, Set<TagSet>> inboundSets = getInboundTagSetsBySessionKey();
        int total = 0;
        int totalSets = 0;
        long now = _context.clock().now();
        Set<TagSet> sets = new TreeSet<>(new TagSetComparator());
        for (Map.Entry<SessionKey, Set<TagSet>> e : inboundSets.entrySet()) {
            SessionKey skey = e.getKey();
            sets.clear();
            sets.addAll(e.getValue());
            totalSets += sets.size();
            buf.append("\n<tr class=skm_key>\n<td><b>Session key:</b> ").append(skey.toBase64()).append("</td>" +
                       "<td><b>Sets:</b> ").append(sets.size()).append("</td>\n</tr>" +
                       "\n<tr class=expiry>\n<td colspan=2>\n<ul>");
            for (TagSet ts : sets) {
                int size = ts.getTags().size();
                total += size;
                buf.append("\n<li><b>ID: ").append(ts.getID());
                long expires = ts.getDate() - now;
                if (expires > 0)
                    buf.append(" expires:</b> ").append(DataHelper.formatDuration2(expires)).append(" with ");
                else
                    buf.append(" expired:</b> ").append(DataHelper.formatDuration2(0 - expires)).append(" ago with ");
                buf.append(size).append('/').append(ts.getOriginalSize()).append(" tags remaining</li>");
            }
            buf.append("\n</ul>\n</td>\n</tr>\n");
            out.append(buf);
            buf.setLength(0);
        }
        buf.append("<tr>\n<th colspan=2>Total inbound tags: ").append(total).append(" (")
           .append(DataHelper.formatSize2(32*(long)total)).append("B); sets: ").append(totalSets)
           .append("; sessions: ").append(inboundSets.size())
           .append("</th>\n</tr>\n" +
                   "</table>\n" +
                   "<h3 class=debug_outboundsessions>ElGamal Outbound sessions</h3>\n" +
                   "<table>");
        total = 0;
        totalSets = 0;
        Set<OutboundSession> outbound = getOutboundSessions();
        for (Iterator<OutboundSession> iter = outbound.iterator(); iter.hasNext();) {
            OutboundSession sess = iter.next();
            sets.clear();
            sets.addAll(sess.getTagSets());
            totalSets += sets.size();
            buf.append("\n<tr class=debug_outboundtarget>\n<td>\n<div class=debug_targetinfo>\n" +
                       "<b>Session key:</b> <span class=debug_sesskey>").append(sess.getCurrentKey().toBase64()).append("</span><br>\n" +
                       "<b>Target public key:</b> <span class=debug_pubkey>").append(toString(sess.getTarget())).append("</span><br>\n" +
                       "<b>Established:</b> ").append(DataHelper.formatDuration2(now - sess.getEstablishedDate())).append(" ago&nbsp;&nbsp;&nbsp;" +
                       "<b>Ack Received?</b> ").append(sess.getAckReceived()).append("&nbsp;&nbsp;&nbsp;" +
                       "<b>Last Used:</b> ").append(DataHelper.formatDuration2(now - sess.getLastUsedDate())).append(" ago\n</div>\n</td>" +
                       "<td><b>Sets:</b> ").append(sess.getTagSets().size()).append("</td></tr>\n" +
                       "<tr class=sentTags>\n<td colspan=2>\n<ul>");
            for (Iterator<TagSet> siter = sets.iterator(); siter.hasNext();) {
                TagSet ts = siter.next();
                int size = ts.getTags().size();
                total += size;
                buf.append("\n<li><b>ID: ").append(ts.getID())
                   .append(" Sent:</b> ").append(DataHelper.formatDuration2(now - ts.getDate())).append(" ago with ");
                buf.append(size).append('/').append(ts.getOriginalSize()).append(" tags remaining; ACKed? ").append(ts.getAcked()).append("</li>");
            }
            buf.append("\n</ul>\n</td>\n</tr>\n");
            out.append(buf);
            buf.setLength(0);
        }
        buf.append("<tr>\n<th colspan=2>Total Outbound tags: ").append(total).append(" (")
           .append(DataHelper.formatSize2(32*(long)total)).append("B); sets: ").append(totalSets)
           .append("; sessions: ").append(outbound.size())
           .append("</th>\n</tr>\n</table>\n");

        out.append(buf);
    }

    /**
     * For debugging.
     *
     * @param target the key to render, null allowed
     * @return the key in Base64, or the string null
     */
    private static String toString(PublicKey target) {
        if (target == null)
            return "null";
        return target.toBase64();
    }

    /**
     * Just for the HTML method above so we can see what's going on easier
     * Earliest first.
     */
    private static class TagSetComparator implements Comparator<TagSet>, Serializable {
         /** Orders tag sets by how full they are. */
         private TagSetComparator() {}
         /** Compare two entries for ordering. */
         public int compare(TagSet l, TagSet r) {
             return compareTagSets(l.getDate(), r.getDate(), l.hashCode(), r.hashCode());
        }
    }

    /**
     * Orders two tag sets by the two values the debug page sorts on: earliest
     * date first, ties broken by hash.
     * <p>
     * Compares instead of subtracting. A date gap over {@code Integer.MAX_VALUE}
     * ms (24.9 days) flips the sign of a truncated subtraction, and a hash is
     * any int, so the tie-break overflows routinely rather than never.
     *
     * @param leftDate the first candidate's tag-set date, in ms
     * @param rightDate the second candidate's tag-set date, in ms
     * @param leftHash the first candidate's hash, consulted only to break a date tie
     * @param rightHash the second candidate's hash
     * @return negative, zero or positive as the first sorts before, alongside, or
     *         after the second
     * @since 0.9.72+
     */
    static int compareTagSets(long leftDate, long rightDate, int leftHash, int rightHash) {
        int rv = Long.compare(leftDate, rightDate);
        return rv != 0 ? rv : Integer.compare(leftHash, rightHash);
    }

    /**
     * The state for a crypto session to a single public key.
     */
    private static class OutboundSession {
        /** The app context. */
        private final I2PAppContext _context;
        /** Logger for this session. */
        private final Log _log;
        /** The peer's public key. */
        private final PublicKey _target;
        /** Key for the most recently sent tag set. */
        private SessionKey _currentKey;
        /** When the session was created. */
        private final long _established;
        /** When a tag was last consumed. */
        private long _lastUsed;
        /**
         * Before the first ack, all tagsets go here. These are never expired, we rely
         * on the callers to call failTags() or ackTags() to remove them from this list.
         * Actually we now do a failsafe expire.
         * Synch on _tagSets to access this.
         * No particular order.
         */
        private final Set<TagSet> _unackedTagSets;
        /**
         * As tagsets are acked, they go here.
         * After the first ack, new tagsets go here (i.e. presumed acked)
         * In order, earliest first.
         */
        private final List<TagSet> _tagSets;
        /**
         * Set to true after first tagset is acked.
         * Upon repeated failures, we may revert back to false.
         * This prevents us getting "stuck" forever, using tags that weren't acked
         * to deliver the next set of tags.
         */
        private volatile boolean _acked;
        /**
         * Fail count
         * Synch on _tagSets to access this.
         */
        private int _consecutiveFailures;

        /** Failed tag sets before the session is dropped. */
        private static final int MAX_FAILS = 2;
        /**
         * Creates state for tracking one outbound ElGamal session to a
         * single target.
         *
         * @param ctx the app context
         * @param log the logger for the session
         * @param target the peer's public key
         * @param key the session key in use
         */
        public OutboundSession(I2PAppContext ctx, Log log, PublicKey target, SessionKey key) {
            _context = ctx;
            _log = log;
            _target = target;
            _currentKey = key;
            _established = ctx.clock().now();
            _lastUsed = _established;
            _unackedTagSets = new HashSet<>(4);
            _tagSets = new ArrayList<>(6);
        }

        /**
         * All tag sets currently held, for tests.
         *
         * @return list of TagSet objects
         * This is used only by renderStatusHTML().
         * It includes both acked and unacked TagSets.
         */
        List<TagSet> getTagSets() {
            List<TagSet> rv;
            synchronized (_tagSets) {
                rv = new ArrayList<>(_unackedTagSets);
                rv.addAll(_tagSets);
            }
            return rv;
        }

        /**
         * Got an ack for these tags
         * For tagsets delivered after the session was acked, this is a nop
         * because the tagset was originally placed directly on the acked list.
         * If the set was previously failed, it will be added back in.
         *
         * @param set the tag set that was acknowledged
         */
        void ackTags(TagSet set) {
            synchronized (_tagSets) {
                if (_unackedTagSets.remove(set)) {
                    // we could perhaps use it even if not previuosly in unacked,
                    // i.e. it was expired already, but _tagSets is a list not a set...
                    _tagSets.add(set);
                } else if (!_tagSets.contains(set)) {
                    // add back (sucess after fail)
                    _tagSets.add(set);
                    if (_log.shouldWarn())
                        _log.warn("ACK of unknown (previously failed?) TagSet\n* " + set);
                } else if (set.getAcked() && _log.shouldWarn()) {
                    _log.warn("Duplicate ACK of TagSet\n* " + set);
                }
                _acked = true;
                _consecutiveFailures = 0;
            }
            set.setAcked();
        }

        /**
         * Didn't get an ack for these tags.
         *
         * @param set the tag set that failed
         */
        void failTags(TagSet set) {
            synchronized (_tagSets) {
                _unackedTagSets.remove(set);
                if (_tagSets.remove(set) && ++_consecutiveFailures >= MAX_FAILS) {
                    // revert back to non-speculative ack mode,
                    // and force full ElG next time by reclassifying all tagsets that weren't really acked
                    _acked = false;
                    int acked = 0;
                    int unacked = 0;
                    for (Iterator<TagSet> iter = _tagSets.iterator(); iter.hasNext(); ) {
                        TagSet ts = iter.next();
                        if (!ts.getAcked()) {
                            iter.remove();
                            _unackedTagSets.add(ts);
                            unacked++;
                        } else {
                            acked++;
                        }
                    }
                    if (_log.shouldWarn())
                        _log.warn("Failed to deliver " + _consecutiveFailures
                                  + " consecutive session tag sets to ["
                                  + _currentKey.toBase64().substring(0,6) + "]"
                                  + "\n* Reverting to full ElGamal and unacknowledged delivery"
                                  + "\n* Unacknowledged tag sets: " + unacked
                                  + " * Remaining acknowledged: " + acked);
                }
            }
        }
        /**
         * The peer this session goes to.
         *
         * @return the target's public key
         */
        public PublicKey getTarget() {
            return _target;
        }
        /**
         * The session key in use now.
         *
         * @return the current key
         */
        public SessionKey getCurrentKey() {
            return _currentKey;
        }
        /**
         * Replaces the session key, dropping the old tags when it changes.
         *
         * @param key the new session key
         */
        public void setCurrentKey(SessionKey key) {
            _lastUsed = _context.clock().now();
            if (_currentKey != null && !_currentKey.equals(key)) {
                synchronized (_tagSets) {
                    if (_log.shouldWarn()) {
                        int dropped = 0;
                        for (TagSet set : _tagSets) {
                            dropped += set.getTags().size();
                        }
                        _log.warn("Rekeyed from " + _currentKey + " to " + key +
                                  " -> Dropping " + dropped + " session tags");
                    }
                    _acked = false;
                    _tagSets.clear();
                }
            }
            _currentKey = key;

        }
        /**
         * When the session was established.
         *
         * @return the time, in epoch ms
         */
        public long getEstablishedDate() {
            return _established;
        }
        /**
         * When a tag from the session was last used.
         *
         * @return the time, in epoch ms
         */
        public long getLastUsedDate() {
            return _lastUsed;
        }

        /**
         * Expires the old tags.
         *
         * @return the number of tag sets removed
         */
        public int expireTags() {
            long now = _context.clock().now();
            int removed = 0;
            synchronized (_tagSets) {
                for (Iterator<TagSet> iter = _tagSets.iterator(); iter.hasNext(); ) {
                    TagSet set = iter.next();
                    if (set.getDate() + SESSION_TAG_DURATION_MS <= now) {
                        iter.remove();
                        removed++;
                    }
                }
                // Failsafe: an unacked set can be left behind by a path that
                // never reaches it again, so sweep it on every pass
                for (Iterator<TagSet> iter = _unackedTagSets.iterator(); iter.hasNext(); ) {
                    TagSet set = iter.next();
                    if (set.getDate() + SESSION_TAG_DURATION_MS <= now) {
                        iter.remove();
                        removed++;
                    }
                }
            }
            return removed;
        }
        /**
         * Consumes the next available tag and advances.
         *
         * @return the tag, null if none are left
         */
        public SessionTag consumeNext() {
            long now = _context.clock().now();
            _lastUsed = now;
            synchronized (_tagSets) {
                while (!_tagSets.isEmpty()) {
                    TagSet set = _tagSets.get(0);
                    if (set.getDate() + SESSION_TAG_DURATION_MS > now) {
                        SessionTag tag = set.consumeNext();
                        if (tag != null) {
                            if (_log.shouldDebug())
                                _log.debug("Outbound Tag [" + tag.toString().substring(0,6) + "] processed " + " from " + set);
                            return tag;
                        } else if (_log.shouldInfo()) {
                            _log.info("Removing empty " + set);
                        }
                    } else {
                        if (_log.shouldInfo())
                            _log.info("Session Tag expired " + set);
                    }
                    _tagSets.remove(0);
                }
            }
            return null;
        }

        /**
         * Total number of tags in acked TagSets not yet expired.
         *
         * @return the total number of tags in acked TagSets
         */
        public int availableTags() {
            int tags = 0;
            long now = _context.clock().now();
            synchronized (_tagSets) {
                for (int i = 0; i < _tagSets.size(); i++) {
                    TagSet set = _tagSets.get(i);
                    if (set.getDate() + SESSION_TAG_DURATION_MS > now) {
                        int sz = set.getTags().size();
                        // so tags are sent when the acked tags are below
                        // 30, 17, and 4.
                        if (!set.getAcked())
                            // round up so we don't report 0 when we have 1 or 2 remaining and get the session removed
                            sz = (sz + 2) / 3;
                        tags += sz;
                    }
                }
            }
            return tags;
        }

        /**
         * The furthest tag set expiration date, after which all of the
         * tags will have expired.
         *
         * @return the last expiration date
         */
        public long getLastExpirationDate() {
            long last = 0;
            synchronized (_tagSets) {
                for (TagSet set : _tagSets) {
                    if ( (set.getDate() > last) && (!set.getTags().isEmpty()) )
                        last = set.getDate();
                }
            }
            if (last > 0)
                return last + SESSION_TAG_DURATION_MS;
            else
                return -1;
        }

        /**
         * Records a tag set as sent but not yet acked.
         *
         * @param set the tag set to add to the unacked sets
         */
        public void addTags(TagSet set) {
            _lastUsed = _context.clock().now();
            synchronized (_tagSets) {
                _unackedTagSets.add(set);
            }
        }

        /**
         * Whether the outbound tag set has been acknowledged.
         * @return the ack received
         */
        public boolean getAckReceived() {
            return _acked;
        }
    }

    /** One set of session tags sent to a peer. */
    private static class TagSet implements TagSetHandle {
        /** The tags in this set. */
        private final Set<SessionTag> _sessionTags;
        /** Key the tags were encrypted with. */
        private final SessionKey _key;
        /** When the set was created. */
        private final long _date;
        /** Id identifying the set among those sent. */
        private final int _id;
        /** Tag count when the set was created. */
        private final int _origSize;
        /** Did we get an ack for this tagset? Only for outbound tagsets. */
        private boolean _acked;

        /**
         * Creates a tag set with its key and expiry.
         *
         * @param date For inbound: when the TagSet will expire; for outbound: creation time
         * @param tags the session tags in the set
         * @param key the session key the tags belong to
         * @param id identifies the set among those sent
         */
        public TagSet(Set<SessionTag> tags, SessionKey key, long date, int id) {
            if (key == null) throw new IllegalArgumentException("Missing key");
            if (tags == null) throw new IllegalArgumentException("Missing tags");
            _sessionTags = tags;
            _key = key;
            _date = date;
            _id = id;
            _origSize = tags.size();
        }

        /**
         * For inbound: when the TagSet will expire; for outbound: creation time.
         * @return that timestamp in milliseconds since the epoch, as passed to
         * the constructor
         */
        public long getDate() {
            return _date;
        }

        /**
         * Original number of tags in this set before any were consumed.
         * @return the original size
         * @since 0.9.3 for debugging
         */
        public int getOriginalSize() {
            return _origSize;
        }

        /**
         * The tags still available.
         *
         * @return the unconsumed tags of the set
         */
        public Set<SessionTag> getTags() {
            return _sessionTags;
        }
        /**
         * The session key the set belongs to.
         *
         * @return the key for the tags
         */
        public SessionKey getAssociatedKey() {
            return _key;
        }

        /**
         * Marks the tag as used. Caller must synch.
         *
         * @param tag the tag to remove from the set
         */
        public void consume(SessionTag tag) {
            _sessionTags.remove(tag);
        }

        /**
         * For outbound only.
         * Caller must synch.
         *
         * @return a tag or null
         */
        public SessionTag consumeNext() {
            Iterator<SessionTag> iter = _sessionTags.iterator();
            if (!iter.hasNext())
                return null;
            SessionTag first = iter.next();
            iter.remove();
            return first;
        }

        /**
         * For outbound only.
         */
        public void setAcked() { _acked = true; }

        /**
         * For outbound only.
         * @return true if {@link #setAcked()} has been called, meaning the peer has
         *         acknowledged using these tags
         */
        public boolean getAcked() { return _acked; }

        /**
         * Unique identifier for this tag set.
         * @return the i d
         */
        public int getID() {return _id;}

        /**
         * Debug string with ID, size, ack status, and key.
         *
         * @return the formatted text
         */
        @Override
        public String toString() {
            return "TagSet [" + _id + "]" +
                   " Size: " + _sessionTags.size() + '/' + _origSize +
                   " ACKed? " + _acked +
                   "\n* Created: " + new Date(_date) +
                   "\n* Session key: " + _key;
        }
    }
}

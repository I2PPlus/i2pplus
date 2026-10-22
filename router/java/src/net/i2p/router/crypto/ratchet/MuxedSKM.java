package net.i2p.router.crypto.ratchet;

import java.io.IOException;
import java.io.Writer;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import net.i2p.crypto.EncType;
import net.i2p.crypto.SessionKeyManager;
import net.i2p.crypto.TagSetHandle;
import net.i2p.data.PublicKey;
import net.i2p.data.SessionKey;
import net.i2p.data.SessionTag;
import net.i2p.router.crypto.TransientSessionKeyManager;

/**
 * Multiplexed session key manager combining ElGamal and ECIES key management.
 *
 * @since 0.9.44
 */
public class MuxedSKM extends SessionKeyManager {

    private final TransientSessionKeyManager _elg;
    private final RatchetSKM _ec;
    private final AtomicInteger _elgCounter = new AtomicInteger();
    private final AtomicInteger _ecCounter = new AtomicInteger();
    /**
     * How much slower the ElGamal slow decrypt is than the ratchet one, so its
     * success count is divided by this much before the two are compared.
     */
    static final int ELG_SLOW_FACTOR = 5;

    /**
     * Size of the sampling window. Once both counters are individually
     * meaningful and their sum passes this, the window rolls over so the
     * ordering tracks recent traffic instead of the whole session.
     */
    static final int RESTART_COUNTERS = 500;
    /**
     * Combines ElGamal and ECIES SKMs with adaptive decrypt ordering.
     *
     * @param elg the ElGamal key manager handling legacy transient sessions
     * @param ec the ratchet key manager handling ECIES-X25519 sessions
     */

    public MuxedSKM(TransientSessionKeyManager elg, RatchetSKM ec) {
        _elg = elg;
        _ec = ec;
    }
    /**
     * Return the ElGamal key manager that handles legacy transient sessions.
     *
     * @return the ElGamal session key manager this instance multiplexes
     */

    public TransientSessionKeyManager getElgSKM() { return _elg; }
    /**
     * Return the ratchet key manager that handles ECIES-X25519 sessions.
     *
     * @return the ratchet session key manager this instance multiplexes
     */

    public RatchetSKM getECSKM() { return _ec; }

    /**
     *  Should we try the Ratchet slow decrypt before ElG slow decrypt?
     *  Adaptive test based on previous mix of traffic for this SKM,
     *  as reported by reportDecryptResult().
     *
     *  @return true if the ratchet slow decrypt should be tried before the ElGamal one
     *  @since 0.9.46
     */
    boolean preferRatchet() {
        int ec = _ecCounter.get();
        int elg = _elgCounter.get();
        if (needsRestart(ec, elg)) {
            rollOver(_ecCounter, ec);
            rollOver(_elgCounter, elg);
            return true;
        }
        return preferRatchet(ec, elg);
    }

    /**
     * Whether the sampling window is full and both counters should start over,
     * so the ordering tracks recent traffic rather than the whole session.
     * Both counters have to be individually meaningful, or a one-sided flood
     * would reset a window that still says something about the other path.
     *
     * @param ec successful ratchet decrypts in the current window
     * @param elg successful ElGamal decrypts in the current window
     * @return true if the window has run its course
     * @since 0.9.72+
     */
    static boolean needsRestart(int ec, int elg) {
        return ec > RESTART_COUNTERS / 10 &&
               elg > RESTART_COUNTERS / 10 &&
               ec + elg > RESTART_COUNTERS;
    }

    /**
     * The ordering the current window favours, with the ElGamal count discounted
     * because the ratchet slow decrypt is the cheaper of the two to try first.
     *
     * @param ec successful ratchet decrypts in the current window
     * @param elg successful ElGamal decrypts in the current window
     * @return true if the ratchet slow decrypt should go first
     * @since 0.9.72+
     */
    static boolean preferRatchet(int ec, int elg) {
        return ec >= elg / ELG_SLOW_FACTOR;
    }

    /**
     * Reset one counter to start a fresh window, only if nothing was recorded
     * since the value the decision was taken on. A plain set(0) would discard
     * any success logged between that read and the reset, which is how the
     * counters used to lose counts under concurrent decrypts.
     *
     * @param counter the counter to roll over
     * @param expected the value read when the decision was taken
     * @return true if the counter was reset, false if it moved in the meantime
     * @since 0.9.72+
     */
    static boolean rollOver(AtomicInteger counter, int expected) {
        return counter.compareAndSet(expected, 0);
    }

    /**
     *  Report the result of a slow decrypt attempt.
     *
     *  @param isRatchet true for EC, false for ElG
     *  @param success true for successful decrypt
     *  @since 0.9.46
     */
    void reportDecryptResult(boolean isRatchet, boolean success) {
        if (success) {
            if (isRatchet)
                _ecCounter.incrementAndGet();
            else
                _elgCounter.incrementAndGet();
        }
    }

    /**
     * ElG only.
     * @return the current key
     */
    @Override
    public SessionKey getCurrentKey(PublicKey target) {
        EncType type = target.getType();
        if (type == EncType.ELGAMAL_2048)
            return _elg.getCurrentKey(target);
        return null;
    }

    /**
     * ElG only.
     * @return the current or new key
     */
    @Override
    public SessionKey getCurrentOrNewKey(PublicKey target) {
        EncType type = target.getType();
        if (type == EncType.ELGAMAL_2048)
            return _elg.getCurrentOrNewKey(target);
        return null;
    }

    /**
     * ElG only.
     */
    @Override
    public void createSession(PublicKey target, SessionKey key) {
        EncType type = target.getType();
        if (type == EncType.ELGAMAL_2048)
            _elg.createSession(target, key);
        else
            throw new IllegalArgumentException();
    }

    /**
     * ElG only.
     */
    @Override
    public SessionKey createSession(PublicKey target) {
        EncType type = target.getType();
        if (type == EncType.ELGAMAL_2048)
            return _elg.createSession(target);
        return null;
    }

    /**
     * ElG only.
     */
    @Override
    public SessionTag consumeNextAvailableTag(PublicKey target, SessionKey key) {
        EncType type = target.getType();
        if (type == EncType.ELGAMAL_2048)
            return _elg.consumeNextAvailableTag(target, key);
        return null;
    }

    /**
     * EC only.
     *
     * @param target the destination to take an available session tag from
     * @return the oldest unconsumed tag, or null if the target is not an ECIES-X25519 key
     */
    public RatchetEntry consumeNextAvailableTag(PublicKey target) {
        EncType type = target.getType();
        if (type == EncType.ECIES_X25519)
            return _ec.consumeNextAvailableTag(target);
        return null;
    }

    /**
     *  Not used; tag management is handled by the ratchet protocol.
     *
     *  @return 0, tag management is handled by ratchet protocol
     */
    @Override
    public int getTagsToSend() { return 0; }

    /**
     *  Not used; thresholds are not used for ratchet sessions.
     *
     *  @return 0, threshold not used for ratchet sessions
     */
    @Override
    public int getLowThreshold() { return 0; }

    /**
     * ElG only.
     * @return whether send tags
     */
    @Override
    public boolean shouldSendTags(PublicKey target, SessionKey key) {
        EncType type = target.getType();
        if (type == EncType.ELGAMAL_2048)
            return _elg.shouldSendTags(target, key);
        return false;
    }

    /**
     * ElG only.
     * @return whether send tags
     */
    @Override
    public boolean shouldSendTags(PublicKey target, SessionKey key, int lowThreshold) {
        EncType type = target.getType();
        if (type == EncType.ELGAMAL_2048)
            return _elg.shouldSendTags(target, key, lowThreshold);
        return false;
    }

    /** Delegates to the appropriate key manager by encryption type. */
    @Override
    public int getAvailableTags(PublicKey target, SessionKey key) {
        EncType type = target.getType();
        if (type == EncType.ELGAMAL_2048)
            return _elg.getAvailableTags(target, key);
        if (type == EncType.ECIES_X25519)
            return _ec.getAvailableTags(target, key);
        return 0;
    }

    /** Delegates to the appropriate key manager by encryption type. */
    @Override
    public long getAvailableTimeLeft(PublicKey target, SessionKey key) {
        EncType type = target.getType();
        if (type == EncType.ELGAMAL_2048)
            return _elg.getAvailableTimeLeft(target, key);
        if (type == EncType.ECIES_X25519)
            return _ec.getAvailableTimeLeft(target, key);
        return 0;
    }

    /**
     * ElG only.
     */
    @Override
    public TagSetHandle tagsDelivered(PublicKey target, SessionKey key, Set<SessionTag> sessionTags) {
        EncType type = target.getType();
        if (type == EncType.ELGAMAL_2048)
            return _elg.tagsDelivered(target, key, sessionTags);
         return null;
    }

    /**
     * ElG only.
     */
    @Override
    public void tagsReceived(SessionKey key, Set<SessionTag> sessionTags) {
        _elg.tagsReceived(key, sessionTags);
    }

    /**
     * ElG only.
     */
    @Override
    public void tagsReceived(SessionKey key, Set<SessionTag> sessionTags, long expire) {
        _elg.tagsReceived(key, sessionTags, expire);
    }

    /**
     * EC only
     * One time session.
     *
     * @param key the session the tag was received on
     * @param tag the one-time tag received
     * @param expire time from now
     * @since 0.9.51
     */
    public void tagsReceived(SessionKey key, RatchetSessionTag tag, long expire) {
        _ec.tagsReceived(key, tag, expire);
    }

    /** Tries ElGamal tag lookup first, falls back to ratchet lookup. */
    @Override
    public SessionKey consumeTag(SessionTag tag) {
        SessionKey rv = _elg.consumeTag(tag);
        if (rv == null) {
            RatchetSessionTag rstag = new RatchetSessionTag(tag.getData());
            rv = _ec.consumeTag(rstag);
        }
        return rv;
    }

    /** Shuts down both ElGamal and ECIES key managers. */
    @Override
    public void shutdown() {
        _elg.shutdown();
        _ec.shutdown();
    }

    /** Renders debug status for both key managers. */
    @Override
    public void renderStatusHTML(Writer out) throws IOException {
        _elg.renderStatusHTML(out);
        _ec.renderStatusHTML(out);
    }

    /** Sums the debug stats of both key managers, null if neither tracks them. */
    @Override
    public long[] getDebugStats() {
        long[] a = _elg.getDebugStats();
        long[] b = _ec.getDebugStats();
        if (a == null) {return b;}
        if (b == null) {return a;}
        long[] rv = new long[a.length];
        for (int i = 0; i < a.length; i++) {rv[i] = a[i] + b[i];}
        return rv;
    }

    /**
     * ElG only.
     */
    @Override
    public void failTags(PublicKey target, SessionKey key, TagSetHandle ts) {
        EncType type = target.getType();
        if (type == EncType.ELGAMAL_2048)
            _elg.failTags(target, key, ts);
    }

    /**
     * ElG only.
     */
    @Override
    public void tagsAcked(PublicKey target, SessionKey key, TagSetHandle ts) {
        EncType type = target.getType();
        if (type == EncType.ELGAMAL_2048)
            _elg.tagsAcked(target, key, ts);
    }
}

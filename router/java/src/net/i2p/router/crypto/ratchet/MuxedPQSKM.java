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
import net.i2p.util.Log;

/**
 * Post-quantum hybrid session key manager combining ECIES and ML-KEM operations.
 *
 * @since 0.9.67
 */
public class MuxedPQSKM extends SessionKeyManager {

    /** The ratcheted ECIES session keys. */
    private final RatchetSKM _ec;
    /** The ratcheted post-quantum session keys. */
    private final RatchetSKM _pq;
    /** Counts the ECIES keys handed out. */
    private final AtomicInteger _ecCounter = new AtomicInteger();
    /** Counts the post-quantum keys handed out. */
    private final AtomicInteger _pqCounter = new AtomicInteger();
    /** Logger for this class. */
    private final Log _log = new Log(MuxedPQSKM.class);
    /**
     * How much slower the PQ slow decrypt is than the ratchet one, so its
     * success count is divided by this much before the two are compared.
     */
    static final int PQ_SLOW_FACTOR = 2;

    /**
     * Size of the sampling window. Once both counters are individually
     * meaningful and their sum passes this, the window rolls over so the
     * ordering tracks recent traffic instead of the whole session.
     */
    static final int RESTART_COUNTERS = 500;
    /**
     * Combines ECIES and PQ SKMs with adaptive decrypt ordering.
     * @param ec the ECIES ratchet key manager
     * @param pq the post-quantum ratchet key manager
     */

    public MuxedPQSKM(RatchetSKM ec, RatchetSKM pq) {
        _ec = ec;
        _pq = pq;
    }
    /**
     * The ECIES ratchet key manager combined into this hybrid.
     * @return the ECIES ratchet key manager
     */

    public RatchetSKM getECSKM() { return _ec; }
    /**
     * The post-quantum ratchet key manager combined into this hybrid.
     * @return the post-quantum ratchet key manager
     */

    public RatchetSKM getPQSKM() { return _pq; }

    /**
     * Should we try the Ratchet slow decrypt before PQ slow decrypt?
     * Adaptive test based on previous mix of traffic for this SKM,
     * as reported by reportDecryptResult().
     *
     * @return true if the Ratchet decrypt is tried first, false for PQ
     */
    boolean preferRatchet() {
        int ec = _ecCounter.get();
        int pq = _pqCounter.get();
        if (needsRestart(ec, pq)) {
            rollOver(_ecCounter, ec);
            rollOver(_pqCounter, pq);
            return true;
        }
        return preferRatchet(ec, pq);
    }

    /**
     * The ordering the current window favours, without rolling the sampling
     * window over. Reporting has to use this rather than preferRatchet(): a
     * log line that resets the counters changes which decrypt the next message
     * tries first, and the value it prints is not the one that decision used.
     *
     * @return true if the Ratchet decrypt would be tried first right now
     * @since 0.9.72+
     */
    boolean preferRatchetNow() {
        return preferRatchet(_ecCounter.get(), _pqCounter.get());
    }

    /**
     * Whether the sampling window is full and both counters should start over,
     * so the ordering tracks recent traffic rather than the whole session.
     *
     * @param ec successful ratchet decrypts in the current window
     * @param pq successful PQ decrypts in the current window
     * @return true if the window has run its course
     * @since 0.9.72+
     */
    static boolean needsRestart(int ec, int pq) {
        return ec > RESTART_COUNTERS / 10 &&
               pq > RESTART_COUNTERS / 10 &&
               ec + pq > RESTART_COUNTERS;
    }

    /**
     * The ordering the current window favours, with the PQ count discounted
     * because the ratchet slow decrypt is the cheaper of the two to try first.
     *
     * @param ec successful ratchet decrypts in the current window
     * @param pq successful PQ decrypts in the current window
     * @return true if the ratchet slow decrypt should go first
     * @since 0.9.72+
     */
    static boolean preferRatchet(int ec, int pq) {
        return ec >= pq / PQ_SLOW_FACTOR;
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
     * Report the result of a slow decrypt attempt.
     *
     * @param isRatchet true for EC, false for PQ
     * @param success true for successful decrypt
     */
    void reportDecryptResult(boolean isRatchet, boolean success) {
        if (success) {
            if (isRatchet)
                _ecCounter.incrementAndGet();
            else
                _pqCounter.incrementAndGet();
        }

        // Structured logging for PQ vs EC success/failure ratios.
        // Only report on successful decrypts: failures do not increment the
        // counters, so a failure flood would otherwise re-trigger the
        // checkpoint on every failure (and total == 0 would match every
        // failure). Every 25th success is a checkpoint; the ratio warnings
        // below are evaluated there, so an "interesting" state is still
        // reported promptly without flooding on steady-state skew.
        int ec = _ecCounter.get();
        int pq = _pqCounter.get();
        int total = ec + pq;

        if (success && total > 0 && (total % 25) == 0 && _log.shouldInfo()) {
            double ecRatio = total > 0 ? (double) ec / total * 100.0 : 0.0;
            double pqRatio = total > 0 ? (double) pq / total * 100.0 : 0.0;
            String status = String.format("PQ vs EC decrypt ratios after %d attempts - EC: %d (%.1f%%) PQ: %d (%.1f%%) - Prefer ratchet: %s",
                                          total, ec, ecRatio, pq, pqRatio, preferRatchetNow());

            // Add warnings for concerning patterns
            if (pq == 0 && total >= 50) {
                status += " [WARNING: No PQ successes detected]";
            } else if (ec == 0 && total >= 50) {
                status += " [INFO: Only PQ successes detected]";
            } else if (pqRatio > 90.0 && total >= 25) {
                status += " [INFO: PQ heavily preferred]";
            } else if (ecRatio > 90.0 && total >= 25) {
                status += " [INFO: EC heavily preferred]";
            }

            _log.info(status);
        }
    }

    /**
     * ElG only.
     * @return the current key
     */
    @Override
    public SessionKey getCurrentKey(PublicKey target) {
        return null;
    }

    /**
     * ElG only.
     * @return the current or new key
     */
    @Override
    public SessionKey getCurrentOrNewKey(PublicKey target) {
        return null;
    }

    /**
     * ElG only.
     */
    @Override
    public void createSession(PublicKey target, SessionKey key) {
        // ElG only - no-op in ECIES/PQ mux
    }

    /**
     * ElG only.
     */
    @Override
    public SessionKey createSession(PublicKey target) {
        return null;
    }

    /**
     * ElG only.
     */
    @Override
    public SessionTag consumeNextAvailableTag(PublicKey target, SessionKey key) {
        return null;
    }

    /**
     * EC/PQ.
     *
     * @param target the target peer's ephemeral key; its type picks the SKM
     * @return the next available tag, or null
     */
    public RatchetEntry consumeNextAvailableTag(PublicKey target) {
        EncType type = target.getType();
        if (type == EncType.ECIES_X25519)
            return _ec.consumeNextAvailableTag(target);
        else
            return _pq.consumeNextAvailableTag(target);
    }

    /**
     * Not used; tag management is handled by the ratchet protocol.
     *
     * @return 0, tag management is handled by ratchet protocol
     */
    @Override
    public int getTagsToSend() { return 0; }

    /**
     * Not used; thresholds are not used for ratchet sessions.
     *
     * @return 0, threshold not used for ratchet sessions
     */
    @Override
    public int getLowThreshold() { return 0; }

    /**
     * ElG only.
     * @return whether send tags
     */
    @Override
    public boolean shouldSendTags(PublicKey target, SessionKey key) {
        return false;
    }

    /**
     * ElG only.
     * @return whether send tags
     */
    @Override
    public boolean shouldSendTags(PublicKey target, SessionKey key, int lowThreshold) {
        return false;
    }

    /** Delegates to the appropriate key manager by encryption type. */
    @Override
    public int getAvailableTags(PublicKey target, SessionKey key) {
        EncType type = target.getType();
        if (type == EncType.ECIES_X25519)
            return _ec.getAvailableTags(target, key);
        else
            return _pq.getAvailableTags(target, key);
    }

    /** Delegates to the appropriate key manager by encryption type. */
    @Override
    public long getAvailableTimeLeft(PublicKey target, SessionKey key) {
        EncType type = target.getType();
        if (type == EncType.ECIES_X25519)
            return _ec.getAvailableTimeLeft(target, key);
        else
            return _pq.getAvailableTimeLeft(target, key);
    }

    /**
     * ElG only.
     */
    @Override
    public TagSetHandle tagsDelivered(PublicKey target, SessionKey key, Set<SessionTag> sessionTags) {
         return null;
    }

    /**
     * ElG only.
     */
    @Override
    public void tagsReceived(SessionKey key, Set<SessionTag> sessionTags) {
        // ElG only - no-op in ECIES/PQ mux
    }

    /**
     * ElG only.
     */
    @Override
    public void tagsReceived(SessionKey key, Set<SessionTag> sessionTags, long expire) {
        // ElG only - no-op in ECIES/PQ mux
    }

    /**
     * EC only.
     * One time session
     * We do not support PQ one-time sessions on MuxedPQSKM.
     *
     * @param key the session key the tag was received under
     * @param tag the ratchet session tag to store for the session
     * @param expire time from now
     */
    public void tagsReceived(SessionKey key, RatchetSessionTag tag, long expire) {
        _ec.tagsReceived(key, tag, expire);
    }

    /** Tries ECIES tag lookup first, falls back to PQ lookup. */
    @Override
    public SessionKey consumeTag(SessionTag tag) {
        RatchetSessionTag rstag = new RatchetSessionTag(tag.getData());
        SessionKey rv = _ec.consumeTag(rstag);
        if (rv == null) {
            rv = _pq.consumeTag(rstag);
        }
        return rv;
    }

    /** Shuts down both ECIES and PQ key managers. */
    @Override
    public void shutdown() {
        _ec.shutdown();
        _pq.shutdown();
    }

    /** Renders debug status for both key managers. */
    @Override
    public void renderStatusHTML(Writer out) throws IOException {
        _ec.renderStatusHTML(out);
        _pq.renderStatusHTML(out);
    }

    /** Sums the debug stats of both key managers, null if neither tracks them. */
    @Override
    public long[] getDebugStats() {
        long[] a = _ec.getDebugStats();
        long[] b = _pq.getDebugStats();
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
        // ElG only - no-op in ECIES/PQ mux
    }

    /**
     * ElG only.
     */
    @Override
    public void tagsAcked(PublicKey target, SessionKey key, TagSetHandle ts) {
        // ElG only - no-op in ECIES/PQ mux
    }
}

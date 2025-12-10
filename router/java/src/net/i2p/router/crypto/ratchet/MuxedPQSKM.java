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
 * Post-quantum hybrid session key manager combining ECIES and ML-KEM operations
 *
 * @since 0.9.67
 */
public class MuxedPQSKM extends SessionKeyManager {

    private final RatchetSKM _ec;
    private final RatchetSKM _pq;
    private final AtomicInteger _ecCounter = new AtomicInteger();
    private final AtomicInteger _pqCounter = new AtomicInteger();
    private final Log _log = new Log(MuxedPQSKM.class);
    // PQ is about this much slower than EC
    private static final int PQ_SLOW_FACTOR = 2;
    private static final int RESTART_COUNTERS = 500;

    public MuxedPQSKM(RatchetSKM ec, RatchetSKM pq) {
        _ec = ec;
        _pq = pq;
    }

    public RatchetSKM getECSKM() { return _ec; }

    public RatchetSKM getPQSKM() { return _pq; }

    /**
     *  Should we try the Ratchet slow decrypt before PQ slow decrypt?
     *  Adaptive test based on previous mix of traffic for this SKM,
     *  as reported by reportDecryptResult().
     */
    boolean preferRatchet() {
        int ec = _ecCounter.get();
        int pq = _pqCounter.get();
        if (ec > RESTART_COUNTERS / 10 &&
            pq > RESTART_COUNTERS / 10 &&
            ec + pq > RESTART_COUNTERS) {
            _ecCounter.set(0);
            _pqCounter.set(0);
            return true;
        }
        return ec >= pq / PQ_SLOW_FACTOR;
    }

    /**
     *  Report the result of a slow decrypt attempt.
     *
     *  @param isRatchet true for EC, false for PQ
     *  @param success true for successful decrypt
     */
    void reportDecryptResult(boolean isRatchet, boolean success) {
        if (success) {
            if (isRatchet)
                _ecCounter.incrementAndGet();
            else
                _pqCounter.incrementAndGet();
        }
        
        // Structured logging for PQ vs EC success/failure ratios
        int ec = _ecCounter.get();
        int pq = _pqCounter.get();
        int total = ec + pq;
        
        // Log every 25th decrypt attempt or when ratios are interesting
        if ((total % 25) == 0 || (total > 0 && (pq == 0 || ec == 0))) {
            if (_log.shouldInfo()) {
                double ecRatio = total > 0 ? (double) ec / total * 100.0 : 0.0;
                double pqRatio = total > 0 ? (double) pq / total * 100.0 : 0.0;
                String status = String.format("PQ vs EC decrypt ratios after %d attempts - EC: %d (%.1f%%) PQ: %d (%.1f%%) - Prefer ratchet: %s", 
                                              total, ec, ecRatio, pq, pqRatio, preferRatchet());
                
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
    }

    /**
     *  ElG only
     */
    @Override
    public SessionKey getCurrentKey(PublicKey target) {
        return null;
    }

    /**
     *  ElG only
     */
    @Override
    public SessionKey getCurrentOrNewKey(PublicKey target) {
        return null;
    }

    /**
     *  ElG only
     */
    @Override
    public void createSession(PublicKey target, SessionKey key) {
    }

    /**
     *  ElG only
     */
    @Override
    public SessionKey createSession(PublicKey target) {
        return null;
    }

    /**
     *  ElG only
     */
    @Override
    public SessionTag consumeNextAvailableTag(PublicKey target, SessionKey key) {
        return null;
    }

    /**
     *  EC/PQ
     */
    public RatchetEntry consumeNextAvailableTag(PublicKey target) {
        EncType type = target.getType();
        if (type == EncType.ECIES_X25519)
            return _ec.consumeNextAvailableTag(target);
        else
            return _pq.consumeNextAvailableTag(target);
    }

    @Override
    public int getTagsToSend() { return 0; };

    @Override
    public int getLowThreshold() { return 0; };

    /**
     *  ElG only
     */
    @Override
    public boolean shouldSendTags(PublicKey target, SessionKey key) {
        return false;
    }

    /**
     *  ElG only
     */
    @Override
    public boolean shouldSendTags(PublicKey target, SessionKey key, int lowThreshold) {
        return false;
    }

    @Override
    public int getAvailableTags(PublicKey target, SessionKey key) {
        EncType type = target.getType();
        if (type == EncType.ECIES_X25519)
            return _ec.getAvailableTags(target, key);
        else
            return _pq.getAvailableTags(target, key);
    }

    @Override
    public long getAvailableTimeLeft(PublicKey target, SessionKey key) {
        EncType type = target.getType();
        if (type == EncType.ECIES_X25519)
            return _ec.getAvailableTimeLeft(target, key);
        else
            return _pq.getAvailableTimeLeft(target, key);
    }

    /**
     *  ElG only
     */
    @Override
    public TagSetHandle tagsDelivered(PublicKey target, SessionKey key, Set<SessionTag> sessionTags) {
         return null;
    }

    /**
     *  ElG only
     */
    @Override
    public void tagsReceived(SessionKey key, Set<SessionTag> sessionTags) {
    }

    /**
     *  ElG only
     */
    @Override
    public void tagsReceived(SessionKey key, Set<SessionTag> sessionTags, long expire) {
    }

    /**
     * EC only.
     * One time session
     * We do not support PQ one-time sessions on MuxedPQSKM.
     *
     * @param expire time from now
     */
    public void tagsReceived(SessionKey key, RatchetSessionTag tag, long expire) {
        _ec.tagsReceived(key, tag, expire);
    }

    @Override
    public SessionKey consumeTag(SessionTag tag) {
        RatchetSessionTag rstag = new RatchetSessionTag(tag.getData());
        SessionKey rv = _ec.consumeTag(rstag);
        if (rv == null) {
            rv = _pq.consumeTag(rstag);
        }
        return rv;
    }

    @Override
    public void shutdown() {
        _ec.shutdown();
        _pq.shutdown();
    }

    @Override
    public void renderStatusHTML(Writer out) throws IOException {
        _ec.renderStatusHTML(out);
        _pq.renderStatusHTML(out);
    }

    /**
     *  ElG only
     */
    @Override
    public void failTags(PublicKey target, SessionKey key, TagSetHandle ts) {
    }

    /**
     *  ElG only
     */
    @Override
    public void tagsAcked(PublicKey target, SessionKey key, TagSetHandle ts) {
    }
}

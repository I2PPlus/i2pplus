package net.i2p.router.crypto.ratchet;

import java.util.List;

/**
 *  Container for outbound ratchet encryption data including tag, key, nonce, and optional next session keys for message preparation
 *  The object returned from SKM.consumeNextAvailableTag() to the engine encrypt.
 *
 *  @since 0.9.44
 */
class RatchetEntry {
    /** The session tag */
    public final RatchetSessionTag tag;
    /** The session key */
    public final SessionKeyAndNonce key;
    /** The key identifier */
    public final int keyID;
    /** The previous chain length */
    public final int pn;
    /** The next {forward} chain key */
    public final NextSessionKey nextForwardKey;
    /** The next {reverse} chain key */
    public final NextSessionKey nextReverseKey;
    /** ACKs queued for delivery with this entry, or null. */
    public final List<Integer> acksToSend;

    /** outbound - calculated key */
    public RatchetEntry(RatchetSessionTag tag, SessionKeyAndNonce key, int keyID, int pn) {
        this(tag, key, keyID, pn, null, null, null);
    }

    /** Full constructor with optional next keys and pending ACKs. */
    public RatchetEntry(RatchetSessionTag tag, SessionKeyAndNonce key, int keyID, int pn,
                        NextSessionKey nextFwdKey, NextSessionKey nextRevKey, List<Integer> acksToSend) {
        this.tag = tag;
        this.key = key;
        this.keyID = keyID;
        this.pn = pn;
        this.nextForwardKey = nextFwdKey;
        this.nextReverseKey = nextRevKey;
        this.acksToSend = acksToSend;
    }

    /** @return debug string with tag and key */
    @Override
    public String toString() {
        return "RatchetEntry[" + tag.toBase64() + ' ' + key + ']';
    }
}

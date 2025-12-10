package net.i2p.router.crypto.ratchet;

import java.util.List;

/**
 *  Container for outbound ratchet encryption data including tag, key, nonce, and optional next session keys for message preparation
 *  The object returned from SKM.consumeNextAvailableTag() to the engine encrypt.
 *
 *  @since 0.9.44
 */
class RatchetEntry {
    public final RatchetSessionTag tag;
    public final SessionKeyAndNonce key;
    public final int keyID;
    public final int pn;
    public final NextSessionKey nextForwardKey;
    public final NextSessionKey nextReverseKey;
    public final List<Integer> acksToSend;

    /** outbound - calculated key */
    public RatchetEntry(RatchetSessionTag tag, SessionKeyAndNonce key, int keyID, int pn) {
        this(tag, key, keyID, pn, null, null, null);
    }

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

    @Override
    public String toString() {
        return "RatchetEntry[" + tag.toBase64() + ' ' + key + ']';
    }
}

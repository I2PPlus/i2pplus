package net.i2p.router.crypto.ratchet;

import com.southernstorm.noise.protocol.HandshakeState;
import net.i2p.crypto.HKDF;
import net.i2p.data.SessionKey;

/**
 * Container for Noise protocol split keys that prevents duplicate HKDF calculations by sharing derived keys between engine and session key manager components
 *
 *  @since 0.9.46
 */
class SplitKeys {

    private static final byte[] ZEROLEN = new byte[0];
    public final SessionKey ck, k_ab, k_ba;

    public SplitKeys(HandshakeState state, HKDF hkdf) {
        byte[] ckd = state.getChainingKey();
        byte[] ab = new byte[32];
        byte[] ba = new byte[32];
        hkdf.calculate(ckd, ZEROLEN, ab, ba, 0);
        ck = new SessionKey(ckd);
        k_ab = new SessionKey(ab);
        k_ba = new SessionKey(ba);
    }
}

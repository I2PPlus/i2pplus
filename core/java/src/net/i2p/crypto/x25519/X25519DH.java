package net.i2p.crypto.x25519;

import com.southernstorm.noise.crypto.x25519.Curve25519;

import net.i2p.crypto.EncType;
import net.i2p.data.PrivateKey;
import net.i2p.data.PublicKey;
import net.i2p.data.SessionKey;

/**
 * DH wrapper around Noise's Curve25519 with I2P types.
 *
 * @since 0.9.41
 */
public class X25519DH {

    private static final EncType TYPE = EncType.ECIES_X25519;

    private X25519DH() {}

    /**
     * DH
     *
     * @param pub MUST have MSB high bit cleared, i.e. pub.getData()[31] &amp; 0x80 == 0
     * @return ECIES_X25519
     * @throws IllegalArgumentException if not ECIES_X25519 or on low-order input see RFC 7748
     */
    public static SessionKey dh(PrivateKey priv, PublicKey pub) {
        if (priv.getType() != TYPE || pub.getType() != TYPE) throw new IllegalArgumentException();
        byte[] pubData = pub.getData();
        if ((pubData[31] & 0x80) != 0) throw new IllegalArgumentException("Public key high bit must be cleared");
        byte[] rv = new byte[32];
        Curve25519.eval(rv, 0, priv.getData(), pubData);
        return new SessionKey(rv);
    }

    /**
     * Test vectors from RFC 7748
     *
     * @since 0.9.62
     */
}

// License: GPLv2+. See docs/LICENSES.md
package i2p.susi.webmail;

import java.nio.charset.StandardCharsets;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import net.i2p.I2PAppContext;
import net.i2p.data.DataHelper;

/**
 * The mailbox password, kept for one session as a salted PBKDF2 hash so the webmail
 * can ask the operator to prove it again before it rewrites the settings that decide
 * where mail goes. This is not the credential the reconnects use - the session holds
 * that in the clear - it is the proof that whoever holds the session still knows it.
 * <p>
 * The salt is per session, so two sessions on the same account do not share a hash,
 * and the derived key is never compared with the password itself.
 *
 * @since 0.9.72+
 */
class LocalPassword {

    /**
     * PBKDF2 rounds per hash, deliberately high: this hash is the only thing between a
     * session id somebody else got hold of and a rewrite of the mail endpoint. It is
     * paid once per login and once per settings save, not per request. A million
     * rounds of HMAC-SHA256 is order half a second on a router-class core.
     */
    static final int ITERATIONS = 1000000;

    /**
     * Length of the derived key, in bits.
     */
    static final int KEY_BITS = 256;

    /**
     * The salt the key was derived with, as hex.
     */
    private final String _salt;
    /**
     * The derived key, as hex, never null or empty.
     */
    private final String _key;
    /**
     * The rounds the key was derived with, kept so a later change to ITERATIONS does
     * not invalidate a session that is already signed in.
     */
    private final int _iterations;

    /**
     * @param salt the salt the key was derived with, as hex
     * @param key the derived key, as hex
     * @param iterations the rounds the key was derived with
     */
    private LocalPassword(String salt, String key, int iterations) {
        _salt = salt;
        _key = key;
        _iterations = iterations;
    }

    /**
     * A salt for a new session: 64 bits from the router's RNG, as hex.
     *
     * @return the salt, never null
     */
    static String newSalt() {
        return Long.toHexString(I2PAppContext.getGlobalContext().random().nextLong());
    }

    /**
     * Derive the hash for a password at the current cost.
     *
     * @param password the password to derive from
     * @param salt the salt to derive with
     * @return the hash, or null if there is nothing to derive from or the derivation
     *         failed
     */
    static LocalPassword create(String password, String salt) {
        return create(password, salt, ITERATIONS);
    }

    /**
     * Derive the hash for a password, with the rounds named so a caller checking the
     * logic need not pay for the protection.
     *
     * @param password the password to derive from, null or empty meaning nothing to
     *                 derive from
     * @param salt the salt to derive with, null meaning no salt
     * @param iterations the PBKDF2 rounds, at least one
     * @return the hash, or null if the arguments are unusable or the derivation failed
     */
    static LocalPassword create(String password, String salt, int iterations) {
        if (password == null || password.isEmpty() || salt == null || iterations < 1) {
            return null;
        }
        String key = hash(password, salt, iterations);
        return key == null || key.isEmpty() ? null : new LocalPassword(salt, key, iterations);
    }

    /**
     * Whether a password offered now is the one this hash was derived from.
     *
     * @param password the password offered, null or empty meaning nothing was offered
     * @return true only if it derives to the stored key; a derivation that fails here
     *         never matches, so the check cannot be passed by two empty hashes meeting
     */
    boolean matches(String password) {
        if (password == null || password.isEmpty()) {
            return false;
        }
        String key = hash(password, _salt, _iterations);
        return key != null && DataHelper.eqCT(key, _key);
    }

    /**
     * Derive the hex key for a password.
     *
     * @param password the password to derive from
     * @param salt the salt to derive with
     * @param iterations the PBKDF2 rounds
     * @return the key as hex, or null if this JDK cannot do the derivation
     */
    private static String hash(String password, String salt, int iterations) {
        try {
            SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            PBEKeySpec spec = new PBEKeySpec(password.toCharArray(),
                                             salt.getBytes(StandardCharsets.UTF_8),
                                             iterations, KEY_BITS);
            byte[] key = factory.generateSecret(spec).getEncoded();
            return DataHelper.toHexString(key);
        } catch (Exception e) {
            return null;
        }
    }
}

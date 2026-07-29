package net.i2p.i2pcontrol.security;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.Locale;

/**
 * Authentication token for I2PControl API access.
 * Provides time-limited authentication tokens for secure API access.
 */
public class AuthToken {
    /** The VALIDITY_TIME. */
    static final int VALIDITY_TIME = 1; // Measured in days
    private static final ThreadLocal<DateFormat> EXPIRY_FORMAT = ThreadLocal.withInitial(() -> new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US));
    private final SecurityManager _secMan;
    private final String id;
    private final Date expiry;

    /**
     * Creates a token from a password hash.
     *
     * @param secMan the security manager
     * @param password the password to hash
     */
    public AuthToken(SecurityManager secMan, String password) {
        _secMan = secMan;
        String hash = _secMan.getPasswdHash(password);
        this.id = _secMan.getHash(hash + System.currentTimeMillis());
        this.expiry = Date.from(Instant.now().plus(VALIDITY_TIME, ChronoUnit.DAYS));
    }

    /**
     * The unique token ID.
     *
     * @return the token ID
     */
    public String getId() {
        return id;
    }

    /**
     * Checks whether the AuthToken has expired.
     *
     * @return True if AuthToken hasn't expired. False in any other case.
     */
    public boolean isValid() {
        return new Date().before(expiry);
    }

    /**
     * The expiry time as a formatted string.
     *
     * @return the expiry time string
     */
    public String getExpiryTime() {
        return EXPIRY_FORMAT.get().format(expiry);
    }

    /**
     * The token ID as a string.
     *
     * @return the token ID
     */
    @Override
    public String toString() {
        return id;
    }

    /**
     * Hash code based on the token ID.
     *
     * @return the hash code
     */
    @Override
    public int hashCode() {
        return id.hashCode();
    }

    /**
     * Equality based on the token ID.
     *
     * @param o the object to compare
     * @return true if equal
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof AuthToken)) return false;
        AuthToken other = (AuthToken) o;
        return id.equals(other.id);
    }
}

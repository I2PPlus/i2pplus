package net.i2p.i2pcontrol.security;

import java.text.SimpleDateFormat;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.Locale;

/**
 * Authentication token for I2PControl API access.
 * Provides time-limited authentication tokens for secure API access.
 */
@SuppressWarnings("PMD.OverrideBothEqualsAndHashcode")
public class AuthToken {
    static final int VALIDITY_TIME = 1; // Measured in days
    private final SecurityManager _secMan;
    private final String id;
    private final Date expiry;

    public AuthToken(SecurityManager secMan, String password) {
        _secMan = secMan;
        String hash = _secMan.getPasswdHash(password);
        this.id = _secMan.getHash(hash + Instant.now().toEpochMilli());
        this.expiry = Date.from(Instant.now().plus(VALIDITY_TIME, ChronoUnit.DAYS));
    }

    public String getId() {
        return id;
    }

    /**
     * Checks whether the AuthToken has expired.
     * @return True if AuthToken hasn't expired. False in any other case.
     */
    public boolean isValid() {
        return Instant.now().isBefore(expiry.toInstant());
    }

    public String getExpiryTime() {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
        return sdf.format(expiry);
    }

    @Override
    public String toString() {
        return id;
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == this) return true;
        if (!(obj instanceof AuthToken)) return false;
        return id.equals(((AuthToken) obj).id);
    }
}

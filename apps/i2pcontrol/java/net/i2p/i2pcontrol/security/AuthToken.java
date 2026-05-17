package net.i2p.i2pcontrol.security;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

/**
 * Authentication token for I2PControl API access.
 * Provides time-limited authentication tokens for secure API access.
 */
public class AuthToken {
    static final int VALIDITY_TIME = 1; // Measured in days
    private static final SimpleDateFormat EXPIRY_FORMAT = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
    private final SecurityManager _secMan;
    private final String id;
    private final Date expiry;

    public AuthToken(SecurityManager secMan, String password) {
        _secMan = secMan;
        String hash = _secMan.getPasswdHash(password);
        this.id = _secMan.getHash(hash + Calendar.getInstance().getTimeInMillis());
        Calendar expiry = Calendar.getInstance();
        expiry.add(Calendar.DAY_OF_YEAR, VALIDITY_TIME);
        this.expiry = expiry.getTime();
    }

    public String getId() {
        return id;
    }

    /**
     * Checks whether the AuthToken has expired.
     * @return True if AuthToken hasn't expired. False in any other case.
     */
    public boolean isValid() {
        return Calendar.getInstance().getTime().before(expiry);
    }

    @SuppressWarnings("PMD.UnsynchronizedStaticFormatter")
    public synchronized String getExpiryTime() {
        return EXPIRY_FORMAT.format(expiry);
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
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof AuthToken)) return false;
        AuthToken other = (AuthToken) o;
        return id.equals(other.id);
    }
}

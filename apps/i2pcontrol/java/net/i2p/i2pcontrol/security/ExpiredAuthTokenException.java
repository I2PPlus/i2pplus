package net.i2p.i2pcontrol.security;

/**
 * Exception thrown when an authentication token has expired.
 * Indicates that the provided token is no longer valid for API access.
 */
public class ExpiredAuthTokenException extends Exception {
    private static final long serialVersionUID = 2279019346592900289L;

    /** Expiry time */
    private String expiryTime;

    /** @param str the exception message
     * @param expiryTime the time when the token expired */
    public ExpiredAuthTokenException(String str, String expiryTime) {
        super(str);
        this.expiryTime = expiryTime;
    }

    /** @return the expiry time */
    public String getExpirytime() {
        return expiryTime;
    }
}

package net.i2p.i2pcontrol.security;

/**
 * Exception thrown when an authentication token has expired.
 * Indicates that the provided token is no longer valid for API access.
 */
public class ExpiredAuthTokenException extends Exception {
    private static final long serialVersionUID = 2279019346592900289L;

    /** Expiry time */
    private String expiryTime;

    /**
     * Signals that the authentication token presented with an API request is past
     * its expiry, so the request was refused.
     *
     * @param str message describing why the token was rejected
     * @param expiryTime when the rejected token expired
     */
    public ExpiredAuthTokenException(String str, String expiryTime) {
        super(str);
        this.expiryTime = expiryTime;
    }

    /**
     * Report when the token presented in the failed request expired, so the
     * caller can tell the client which token to discard and renew.
     *
     * @return the expiry time
     */
    public String getExpirytime() {
        return expiryTime;
    }
}

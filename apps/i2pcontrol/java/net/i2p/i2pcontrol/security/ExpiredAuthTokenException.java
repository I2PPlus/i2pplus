package net.i2p.i2pcontrol.security;

/**
 * Exception thrown when an authentication token has expired.
 * Indicates that the provided token is no longer valid for API access.
 */
public class ExpiredAuthTokenException extends Exception {
    private static final long serialVersionUID = 2279019346592900289L;

    private String expiryTime;

    public ExpiredAuthTokenException(String str, String expiryTime) {
        super(str);
        this.expiryTime = expiryTime;
    }

    public String getExpirytime() {
        return expiryTime;
    }
}

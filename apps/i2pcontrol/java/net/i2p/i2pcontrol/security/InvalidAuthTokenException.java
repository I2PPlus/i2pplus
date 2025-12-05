package net.i2p.i2pcontrol.security;

/**
 * Exception thrown when an authentication token is invalid.
 * Indicates that the provided token is malformed or incorrect.
 */
public class InvalidAuthTokenException extends Exception {
    private static final long serialVersionUID = 7605321329341235577L;

    public InvalidAuthTokenException(String str) {
        super(str);
    }
}

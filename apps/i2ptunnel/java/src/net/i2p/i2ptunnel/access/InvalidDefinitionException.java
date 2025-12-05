package net.i2p.i2ptunnel.access;

/**
 * Exception thrown when filter definition file cannot be parsed.
 */
public class InvalidDefinitionException extends Exception {
    public InvalidDefinitionException(String reason) {
        super(reason);
    }

    public InvalidDefinitionException(String reason, Throwable cause) {
        super(reason, cause);
    }
}

package net.i2p.i2ptunnel.access;

/**
 * Exception thrown when filter definition file cannot be parsed.
 */
public class InvalidDefinitionException extends Exception {
    /**
     * Constructs a new InvalidDefinitionException with the specified detail message.
     *
     * @param reason the detail message
     */
    public InvalidDefinitionException(String reason) {
        super(reason);
    }

    /**
     * Constructs a new InvalidDefinitionException with the specified detail message and cause.
     *
     * @param reason the detail message
     * @param cause the cause
     */
    public InvalidDefinitionException(String reason, Throwable cause) {
        super(reason, cause);
    }
}

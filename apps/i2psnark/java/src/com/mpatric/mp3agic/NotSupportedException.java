package com.mpatric.mp3agic;

/**
 * Exception thrown when an operation is not supported.
 * This occurs when trying to perform operations that are not implemented or supported.
 */
public class NotSupportedException extends BaseException {

    private static final long serialVersionUID = 1L;

    /**
     * Constructs a NotSupportedException with no detail message.
     */
    public NotSupportedException() {
        super();
    }

    /**
     * Constructs a NotSupportedException with the specified detail message.
     *
     * @param message the detail message
     */
    public NotSupportedException(String message) {
        super(message);
    }

    /**
     * Constructs a NotSupportedException with the specified detail message and cause.
     *
     * @param message the detail message
     * @param cause the cause of this exception
     */
    public NotSupportedException(String message, Throwable cause) {
        super(message, cause);
    }
}

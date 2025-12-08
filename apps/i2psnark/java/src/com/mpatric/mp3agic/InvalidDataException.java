package com.mpatric.mp3agic;

/**
 * Exception thrown when invalid MP3 data is encountered.
 * This includes corrupted frame headers, inconsistent data, or other data format issues.
 */
public class InvalidDataException extends BaseException {

    private static final long serialVersionUID = 1L;

    /**
     * Constructs an InvalidDataException with no detail message.
     */
    public InvalidDataException() {
        super();
    }

    /**
     * Constructs an InvalidDataException with the specified detail message.
     *
     * @param message the detail message
     */
    public InvalidDataException(String message) {
        super(message);
    }

    /**
     * Constructs an InvalidDataException with the specified detail message and cause.
     *
     * @param message the detail message
     * @param cause the cause of this exception
     */
    public InvalidDataException(String message, Throwable cause) {
        super(message, cause);
    }
}

package com.mpatric.mp3agic;

/**
 * Exception thrown when an expected ID3 tag is not found.
 * This occurs when trying to read a tag that doesn't exist in the file.
 */
public class NoSuchTagException extends BaseException {

    private static final long serialVersionUID = 1L;

    /**
     * Constructs a NoSuchTagException with no detail message.
     */
    public NoSuchTagException() {
        super();
    }

    /**
     * Constructs a NoSuchTagException with the specified detail message.
     *
     * @param message the detail message
     */
    public NoSuchTagException(String message) {
        super(message);
    }

    /**
     * Constructs a NoSuchTagException with the specified detail message and cause.
     *
     * @param message the detail message
     * @param cause the cause of this exception
     */
    public NoSuchTagException(String message, Throwable cause) {
        super(message, cause);
    }
}

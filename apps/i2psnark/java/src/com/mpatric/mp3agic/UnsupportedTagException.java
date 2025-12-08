package com.mpatric.mp3agic;

/**
 * Exception thrown when an unsupported ID3 tag version is encountered.
 * This occurs when trying to read a tag version that is not supported by the library.
 */
public class UnsupportedTagException extends BaseException {

    private static final long serialVersionUID = 1L;

    /**
     * Constructs an UnsupportedTagException with no detail message.
     */
    public UnsupportedTagException() {
        super();
    }

    /**
     * Constructs an UnsupportedTagException with the specified detail message.
     *
     * @param message the detail message
     */
    public UnsupportedTagException(String message) {
        super(message);
    }

    /**
     * Constructs an UnsupportedTagException with the specified detail message and cause.
     *
     * @param message the detail message
     * @param cause the cause of this exception
     */
    public UnsupportedTagException(String message, Throwable cause) {
        super(message, cause);
    }
}

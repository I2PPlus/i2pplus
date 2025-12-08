package com.mpatric.mp3agic;

/**
 * Base exception class for all mp3agic library exceptions.
 * Provides additional functionality for detailed error messages.
 */
public class BaseException extends Exception {

    private static final long serialVersionUID = 1L;

    /**
     * Constructs a BaseException with no detail message.
     */
    public BaseException() {
        super();
    }

    /**
     * Constructs a BaseException with the specified detail message.
     *
     * @param message the detail message
     */
    public BaseException(String message) {
        super(message);
    }

    /**
     * Constructs a BaseException with the specified detail message and cause.
     *
     * @param message the detail message
     * @param cause the cause of this exception
     */
    public BaseException(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * Returns a detailed message that includes the exception chain.
     * The message includes all exceptions in the cause chain with their class names and messages.
     *
     * @return a detailed message string
     */
    public String getDetailedMessage() {
        Throwable t = this;
        StringBuilder s = new StringBuilder();
        while (true) {
            s.append('[');
            s.append(t.getClass().getName());
            if (t.getMessage() != null && t.getMessage().length() > 0) {
                s.append(": ");
                s.append(t.getMessage());
            }
            s.append(']');
            t = t.getCause();
            if (t != null) {
                s.append(" caused by ");
            } else {
                break;
            }
        }
        return s.toString();
    }
}

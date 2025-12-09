package org.rrd4j.core;

import java.io.IOException;

/**
 * A general purpose RRD4J exception.
 *
 * @since 3.4
 */
public class RrdException extends IOException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates a new RRD exception with the specified message.
     *
     * @param message the error message
     */
    public RrdException(String message) {
        super(message);
    }

    /**
     * Creates a new RRD exception with the specified message and cause.
     *
     * @param message the error message
     * @param cause the cause of the exception
     */
    public RrdException(String message, Throwable cause) {
        super(message, cause);
    }
}

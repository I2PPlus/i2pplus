package org.rrd4j.core;

/**
 * An exception indicating a corrupted RRD.
 *
 * @since 3.4
 */
public class InvalidRrdException extends RrdException {

    /** Pinned for serialized-form compatibility. */
    private static final long serialVersionUID = 1L;

    /**
     * Reports corruption found in an RRD, with no underlying cause.
     *
     * @param message the detail message naming the corruption found
     */
    public InvalidRrdException(String message) {
        super(message);
    }

    /**
     * Reports corruption found in an RRD, keeping the failure that caused it.
     *
     * @param message the detail message naming the corruption found
     * @param cause the underlying failure that made the RRD unreadable
     */
    public InvalidRrdException(String message, Exception cause) {
        super(message, cause);
    }
}

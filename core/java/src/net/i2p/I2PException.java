package net.i2p;

/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain
 * with no warranty of any kind, either expressed or implied.
 * It probably won't make your computer catch on fire, or eat
 * your children, but it might.  Use at your own risk.
 *
 */

/**
 * Base class of I2P exceptions
 *
 * This was originally used to provide chained exceptions, but
 * those were added to Exception in Java 1.4, so this class provides nothing
 * extra at the moment.
 *
 * @author jrandom
 */
public class I2PException extends Exception {

    /**
     * Constructs an exception carrying no detail message.
     */
    public I2PException() {
        super();
    }

    /**
     * Constructs an exception carrying the specified detail message.
     *
     * @param msg the detail message describing the failure
     */
    public I2PException(String msg) {
        super(msg);
    }

    /**
     * Constructs an exception carrying the specified detail message and wrapping the
     * underlying failure.
     *
     * @param msg the detail message describing the failure
     * @param cause the underlying failure that triggered this exception
     */
    public I2PException(String msg, Throwable cause) {
        super(msg, cause);
    }

    /**
     * Constructs an exception that wraps the underlying failure, deriving the detail
     * message from it.
     *
     * @param cause the underlying failure that triggered this exception
     * @since 0.8.2
     */
    public I2PException(Throwable cause) {
        super(cause);
    }
}

package net.i2p.sam;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by human in 2004 and released into the public domain
 * with no warranty of any kind, either expressed or implied.
 * It probably won't  make your computer catch on fire, or eat
 * your children, but it might.  Use at your own risk.
 *
 */

/**
 * Exception thrown by SAM methods.
 *
 */
public class SAMException extends Exception {

    /** Pinned for serialized-form compatibility. */
    private static final long serialVersionUID = 1;

    /**
     * Creates an exception with no detail message.
     */
    public SAMException() {
        super();
    }

    /**
     * Creates an exception carrying the message shown to the SAM client.
     * @param s the detail message naming the SAM call that failed, shown to the SAM client
     */
    public SAMException(String s) {
        super(s);
    }

    /**
     * Wrap a protocol or bridge failure that aborts the SAM call.
     *
     * @param s the detail message naming the SAM call that failed, shown to the SAM client
     * @param cause the underlying failure that triggered the rejection, kept as the exception cause
     * @since 0.9.14
     */
    public SAMException(String s, Throwable cause) {
        super(s, cause);
    }
}

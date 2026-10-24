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
 * Exception thrown by SAM methods when an application tries to create outgoing
 * connections through a receive-only SAM session.
 *
 */
class SAMInvalidDirectionException extends Exception {
    /** Pinned for serialized-form compatibility. */
    private static final long serialVersionUID = 1;

    /**
     * Signals that the session's direction forbids the requested operation.
     */
    public SAMInvalidDirectionException() {
	super();
    }

    /**
     * Signals a direction violation, naming the forbidden direction in the message.
     * @param s the detail message naming the direction the session cannot be used in
     */
    public SAMInvalidDirectionException(String s) {
	super(s);
    }
}

package net.i2p.client.datagram;

/*
 * free (adj.): unencumbered; not under the control of others
 * Written by human in 2004 and released into the public domain
 * with no warranty of any kind, either expressed or implied.
 * It probably won't  make your computer catch on fire, or eat
 * your children, but it might.  Use at your own risk.
 *
 */

/**
 * Exception thrown when I2P repliable datagram signature verification fails.
 *
 * @author human
 */
public class I2PInvalidDatagramException extends Exception {

    /**
     * Creates a new I2PInvalidDatagramException without a detail message.
     */
    public I2PInvalidDatagramException() {
        super();
    }

    /**
     * Creates a new I2PInvalidDatagramException with a detail message.
     *
     * @param s the detail message
     */
    public I2PInvalidDatagramException(String s) {
        super(s);
    }
}

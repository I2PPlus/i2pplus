package net.i2p.sam;

/**
 * Something that can be stopped by the SAMBridge.
 *
 * @since 0.9.20
 */
public interface Handler {

    /** Stop handling, closing the client socket and unregistering from the bridge. */
    public void stopHandling();
}

package net.i2p.client.streaming;

import net.i2p.I2PException;

/**
 * An I2PException thrown from I2PServerSocket.accept()
 * when the router is restarting.
 *
 * @since 0.9.34
 */
public class RouterRestartException extends I2PException {
    /**
     * Carries no message: the reason is the catch clause in
     * I2PServerSocket.accept(), which reports a router restart, not a text.
     */
    public RouterRestartException() {}
}

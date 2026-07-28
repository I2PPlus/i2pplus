package net.i2p.client.impl;

/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain
 * with no warranty of any kind, either expressed or implied.
 * It probably won't  make your computer catch on fire, or eat
 * your children, but it might.  Use at your own risk.
 *
 */

import net.i2p.data.i2cp.I2CPMessage;

/**
 * Define a way to handle a particular type of message
 *
 * @author jrandom
 */
interface I2CPMessageHandler {
    /**
     * I2CP message type this handler processes.
     * @return the message type this handler can process
     */
    public int getType();

    /**
     * Handle an I2CP message.
     *
     * @param message the I2CP message to handle
     * @param session the I2P session context
     */
    public void handleMessage(I2CPMessage message, I2PSessionImpl session);
}

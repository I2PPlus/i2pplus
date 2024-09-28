package net.i2p.client.impl;

/*
 * Released into the public domain
 * with no warranty of any kind, either expressed or implied.
 */

import net.i2p.I2PAppContext;
import net.i2p.data.i2cp.I2CPMessage;
import net.i2p.data.i2cp.BandwidthLimitsMessage;

/**
 * Handle I2CP BW replies from the router
 */
class BWLimitsMessageHandler extends HandlerImpl {
    public BWLimitsMessageHandler(I2PAppContext ctx) {
        super(ctx, BandwidthLimitsMessage.MESSAGE_TYPE);
    }

    public void handleMessage(I2CPMessage message, I2PSessionImpl session) {
        if (_log.shouldDebug()) {_log.debug("Handling " + message);}
        BandwidthLimitsMessage msg = (BandwidthLimitsMessage) message;
        session.bwReceived(msg.getLimits());
    }
}

package net.i2p.client.impl;

/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain
 * with no warranty of any kind, either expressed or implied.
 * It probably won't  make your computer catch on fire, or eat
 * your children, but it might.  Use at your own risk.
 *
 */

import net.i2p.I2PAppContext;
import net.i2p.data.i2cp.I2CPMessage;
import net.i2p.data.i2cp.SetDateMessage;
import net.i2p.util.Clock;

/**
 * Handle I2CP time messages from the router
 *
 * @author jrandom
 */
class SetDateMessageHandler extends HandlerImpl {
    /**
     * Create the handler for I2CP time messages.
     *
     * @param ctx the client context whose clock and log manager this handler uses
     */
    public SetDateMessageHandler(I2PAppContext ctx) {
        super(ctx, SetDateMessage.MESSAGE_TYPE);
    }

    /**
     * Handle an incoming I2CP message.
     *
     * @param message the SetDateMessage carrying the router's time and version
     * @param session the session whose date and capabilities are updated
     */
    @Override
    public void handleMessage(I2CPMessage message, I2PSessionImpl session) {
        if (_log.shouldDebug()) {
            _log.debug("Handling " + message);
        }
        SetDateMessage msg = (SetDateMessage) message;
        // otherwise, it sets getUpdatedSuccessfully() in Clock when all
        // we did was get the time from ourselves.
        if (!_context.isRouterContext()) Clock.getInstance().setNow(msg.getDate().getTime());
        // This saves the various support capabilities based on
        // the router's version string for future reference
        session.dateUpdated(msg.getVersion());
        if (session.isOffline() && !session.supportsLS2()) {
            // TODO check other options also? see RLSMH.requiresLS2()
            session.propagateError("Router does not support offline keys", new Exception());
            session.destroySession(false);
        }
    }
}

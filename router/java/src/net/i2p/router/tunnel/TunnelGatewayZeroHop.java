package net.i2p.router.tunnel;

import net.i2p.data.Hash;
import net.i2p.data.TunnelId;
import net.i2p.data.i2np.I2NPMessage;
import net.i2p.data.i2np.I2NPMessageException;
import net.i2p.data.i2np.TunnelGatewayMessage;
import net.i2p.data.i2np.UnknownI2NPMessage;
import net.i2p.router.OutNetMessage;
import net.i2p.router.RouterContext;

/**
 * Serve as the gatekeeper for a tunnel with no hops, either inbound or outbound.
 *
 */
class TunnelGatewayZeroHop extends TunnelGateway {
    private TunnelCreatorConfig _config;
    private OutboundMessageDistributor _outDistributor;
    private InboundMessageDistributor _inDistributor;

    /**
     *
     */
    public TunnelGatewayZeroHop(RouterContext context, TunnelCreatorConfig config) {
        super(context, null, null, null);
        _config = config;
        if (config.isInbound())
            _inDistributor = new InboundMessageDistributor(context, config.getDestination());
        else
            _outDistributor = new OutboundMessageDistributor(context, OutNetMessage.PRIORITY_MY_DATA);
    }

    /**
     * Add a message to be sent down the tunnel, where we are the inbound gateway.
     * This requires converting the message included in the TGM from an
     * UnknownI2NPMessage to the correct message class.
     * See TunnelGatewayMessage for details.
     *
     * @param msg message received to be sent through the tunnel
     */
    @Override
    public void add(TunnelGatewayMessage msg) {
        I2NPMessage imsg = msg.getMessage();
        if (_config.isInbound()) {
            if (imsg instanceof UnknownI2NPMessage) {
                // Do the delayed deserializing - convert to a standard message class
                try {
                    UnknownI2NPMessage umsg = (UnknownI2NPMessage) imsg;
                    imsg = umsg.convert();
                } catch (I2NPMessageException ime) {
                    if (_log.shouldDebug())
                        _log.debug("Unable to convert to standard message class at zero-hop IBGW", ime);
                    else if (_log.shouldInfo())
                        _log.info("Unable to convert to standard message class at zero-hop IBGW \n* Reason: " + ime.getMessage());
                    return;
                }
            }
        }
        add(imsg, null, null);
    }

    /**
     * Add a message to be sent down the tunnel (immediately forwarding it to the
     * {@link InboundMessageDistributor} or {@link OutboundMessageDistributor}, as
     * necessary).
     *
     * @param msg message to be sent through the tunnel
     * @param toRouter router to send to after the endpoint (or null for endpoint processing)
     * @param toTunnel tunnel to send to after the endpoint (or null for endpoint or router processing)
     */
    @Override
    public void add(I2NPMessage msg, Hash toRouter, TunnelId toTunnel) {
        if (_log.shouldDebug())
            _log.debug("Zero hop gateway: distribute" + (_config.isInbound() ? "Inbound" : " Outbound")
                       + " to [" + (toRouter != null ? toRouter.toBase64().substring(0,6) + "]" : "" )
                       + " " + (toTunnel != null ? toTunnel.getTunnelId() + "" : "")
                       + " " + msg);
        if (_config.isInbound()) {
            _inDistributor.distribute(msg, toRouter, toTunnel);
        } else {
            _outDistributor.distribute(msg, toRouter, toTunnel);
        }
       _config.incrementProcessedMessages();
    }

    /**
     * Destroy this gateway and release all resources.
     * Nulls references to enable timely garbage collection.
     * @since 0.9.68+
     */
    @Override
    public void destroy() {
        super.destroy();
        _config = null;
        _outDistributor = null;
        _inDistributor = null;
    }
}

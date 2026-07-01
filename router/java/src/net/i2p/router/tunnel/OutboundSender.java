package net.i2p.router.tunnel;

import net.i2p.I2PAppContext;

/**
 * Receive the preprocessed data for an outbound gateway, encrypt all of the
 * layers, and forward it on to the first hop.
 */
class OutboundSender implements TunnelGateway.Sender {
    private final TunnelCreatorConfig _config;
    private final OutboundGatewayProcessor _processor;

    public OutboundSender(I2PAppContext ctx, TunnelCreatorConfig config) {
        _config = config;
        _processor = new OutboundGatewayProcessor(ctx, config);
    }

    public long sendPreprocessed(byte[] preprocessed, TunnelGateway.Receiver receiver) {
        _processor.process(preprocessed, 0, preprocessed.length);
        return receiver.receiveEncrypted(preprocessed);
    }
}

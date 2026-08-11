package net.i2p.router.tunnel;

import net.i2p.I2PAppContext;

/**
 * Receive the preprocessed data for an inbound gateway, encrypt it, and forward
 * it on to the first hop.
 */
class InboundSender implements TunnelGateway.Sender {
    private final InboundGatewayProcessor _processor;

    /**
     * Binds the given context and hop config for the inbound gateway.
     */
    public InboundSender(I2PAppContext ctx, HopConfig config) {
        _processor = new InboundGatewayProcessor(ctx, config);
    }

    /**
     * Encrypt the preprocessed data and forward it to the receiver for delivery.
     */
    public long sendPreprocessed(byte[] preprocessed, TunnelGateway.Receiver receiver) {
        _processor.process(preprocessed, 0, preprocessed.length);
        return receiver.receiveEncrypted(preprocessed);
    }
}

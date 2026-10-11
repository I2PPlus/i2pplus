package net.i2p.router.tunnel;

import net.i2p.I2PAppContext;

/**
 * Override the hop processor to seed the message with a random
 * IV.
 */
class InboundGatewayProcessor extends HopProcessor {
    /**
     * Passes the config on with a validator that accepts every message.
     * @param ctx the application context the processor runs under
     * @param config the hop configuration handed to the superclass
     */
    public InboundGatewayProcessor(I2PAppContext ctx, HopConfig config) {
        super(ctx, config, DummyValidator.getInstance());
    }

    /**
     * Use the IV given to us as the first
     * 16 bytes, ignore the 'prev' hop, and encrypt the message like every
     * other participant - we are the inbound gateway.
     *
     * @param orig the buffer holding the message to process
     * @param offset the index within the buffer where the message starts
     * @param length the number of bytes to process starting at that offset
     */
    public void process(byte[] orig, int offset, int length) {
        boolean ok = super.process(orig, offset, length, null);
        if (!ok)
            throw new RuntimeException("we are the gateway, how did it fail?");
    }
}

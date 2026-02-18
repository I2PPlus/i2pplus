package net.i2p.router.tunnel;

import net.i2p.I2PAppContext;

/**
 * Receive the preprocessed data for an outbound gateway, encrypt all of the
 * layers, and forward it on to the first hop.
 *
 */
class OutboundSender implements TunnelGateway.Sender {
    //private final I2PAppContext _context;
    //private final Log _log;
    private final TunnelCreatorConfig _config;
    private final OutboundGatewayProcessor _processor;

    //static final boolean USE_ENCRYPTION = HopProcessor.USE_ENCRYPTION;

    public OutboundSender(I2PAppContext ctx, TunnelCreatorConfig config) {
        //_context = ctx;
        //_log = ctx.logManager().getLog(OutboundSender.class);
        _config = config;
        _processor = new OutboundGatewayProcessor(ctx, config);
    }

    public long sendPreprocessed(byte[] preprocessed, TunnelGateway.Receiver receiver) {
        //if (_log.shouldDebug())
        //    _log.debug("preprocessed data going out " + _config + ": " + Base64.encode(preprocessed));
        if (!_processor.process(preprocessed, 0, preprocessed.length)) {
            return -1;
        }
        //if (_log.shouldDebug())
        //    _log.debug("after wrapping up the preprocessed data on " + _config);
        long rv = receiver.receiveEncrypted(preprocessed);
        //if (_log.shouldDebug())
        //    _log.debug("after receiving on " + _config + ": receiver = " + receiver);
        return rv;
    }
}

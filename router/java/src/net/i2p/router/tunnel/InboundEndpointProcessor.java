package net.i2p.router.tunnel;

import net.i2p.data.Hash;
import net.i2p.router.ProfileManager;
import net.i2p.router.RouterContext;
import net.i2p.util.Log;
import net.i2p.util.SimpleByteCache;

/**
 * Receive the inbound tunnel message, removing all of the layers
 * added by earlier hops to recover the preprocessed data sent
 * by the gateway.  This delegates the crypto to the
 * OutboundGatewayProcessor, since the tunnel creator does the
 * same thing in both instances.
 *
 */
class InboundEndpointProcessor {
    private final RouterContext _context;
    private final Log _log;
    private final TunnelCreatorConfig _config;
    private final IVValidator _validator;

    //static final boolean USE_ENCRYPTION = HopProcessor.USE_ENCRYPTION;

    /**
     *  @deprecated used only by unit tests
     */
    @Deprecated
    InboundEndpointProcessor(RouterContext ctx, TunnelCreatorConfig cfg) {
        this(ctx, cfg, DummyValidator.getInstance());
    }

    public InboundEndpointProcessor(RouterContext ctx, TunnelCreatorConfig cfg, IVValidator validator) {
        _context = ctx;
        _log = ctx.logManager().getLog(InboundEndpointProcessor.class);
        _config = cfg;
        _validator = validator;
    }

    public Hash getDestination() { return _config.getDestination(); }
    public TunnelCreatorConfig getConfig() { return _config; }

    /**
     * Undo all of the encryption done by the peers in the tunnel, recovering the
     * preprocessed data sent by the gateway.
     *
     * @param orig original data with an extra 16 byte IV prepended.
     * @param offset index into the array where the extra 16 bytes (IV) begins
     * @param length how much of orig can we write to (must be a multiple of 16).
     *               Should always be 1024 bytes.
     * @return true if the data was recovered (and written in place to orig), false
     *         if it was a duplicate or from the wrong peer.
     */
    public boolean retrievePreprocessedData(byte orig[], int offset, int length, Hash prev) {
        Hash last = _config.getPeer(_config.getLength()-2);
        if (!last.equals(prev)) {
            // shouldn't happen now that we have good dup ID detection in BuildHandler
            if (_log.shouldLog(Log.WARN))
                _log.warn("Attempted Inbound Endpoint injection from " + prev
                               + ", expected " + last);
            return false;
        }

        //if (_config.getLength() > 1)
        //    _log.debug("IV at inbound endpoint before decrypt: " + Base64.encode(iv));

        boolean ok = _validator.receiveIV(orig, offset, orig, offset + HopProcessor.IV_LENGTH);
        if (!ok) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Invalid IV, dropping at Inbound Endpoint " + _config);
            return false;
        }

        // inbound endpoints and outbound gateways have to undo the crypto in the same way
        decrypt(_context, _config, orig, offset, length);
        
        if (_config.getLength() > 0) {
            int rtt = 0; // dunno... may not be related to an rtt
            //if (_log.shouldLog(Log.DEBUG))
            //    _log.debug("Received " + length + " byte message through: " + _config);
            ProfileManager pm = _context.profileManager();
            // null for unit tests
            if (pm != null) {
                for (int i = 0; i < _config.getLength(); i++) {
                    pm.tunnelDataPushed(_config.getPeer(i), rtt, length);
                }
            }
            _config.incrementVerifiedBytesTransferred(length);
        }

        return true;
    }

    /**
     * Iteratively undo the crypto that the various layers in the tunnel added.
     *
     * @param orig original data with an extra 16 byte IV prepended.
     * @param offset index into the array where the extra 16 bytes (IV) begins
     * @param length how much of orig can we write to (must be a multiple of 16).
     *               Should always be 1024 bytes.
     */
    private void decrypt(RouterContext ctx, TunnelCreatorConfig cfg, byte orig[], int offset, int length) {
        // dont include the endpoint, since that is the creator
        for (int i = cfg.getLength() - 2; i >= 0; i--) {
            OutboundGatewayProcessor.decrypt(ctx, orig, offset, length, cfg.getConfig(i));
            //if (_log.shouldLog(Log.DEBUG)) {
                //_log.debug("IV at hop " + i + ": " + Base64.encode(orig, offset, HopProcessor.IV_LENGTH));
                //_log.debug("hop " + i + ": " + Base64.encode(orig, offset + HopProcessor.IV_LENGTH, length - HopProcessor.IV_LENGTH));
            //}
        }
    }

}

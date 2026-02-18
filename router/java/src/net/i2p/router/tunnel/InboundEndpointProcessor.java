package net.i2p.router.tunnel;

import net.i2p.data.Hash;
import net.i2p.router.ProfileManager;
import net.i2p.router.RouterContext;
import net.i2p.util.Log;

/**
 * Receive the inbound tunnel message, removing all of the layers
 * added by earlier hops to recover the preprocessed data sent
 * by the gateway. This delegates the crypto to the
 * OutboundGatewayProcessor, since the tunnel creator does the
 * same thing in both instances.
 */
class InboundEndpointProcessor {
    private final RouterContext _context;
    private final Log _log;
    private final TunnelCreatorConfig _config;
    private final IVValidator _validator;

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
     * @return true if the data was recovered, false if it was a duplicate, from the wrong peer, or keys were null
     */
    public boolean retrievePreprocessedData(byte orig[], int offset, int length, Hash prev) {
        Hash last = _config.getPeer(_config.getLength()-2);
        if (!last.equals(prev)) {
            if (_log.shouldWarn())
                _log.warn("Attempted Inbound Endpoint injection from " + prev + ", expected " + last);
            return false;
        }

        boolean ok = _validator.receiveIV(orig, offset, orig, offset + HopProcessor.IV_LENGTH);
        if (!ok) {
            if (_log.shouldInfo())
                _log.info("Invalid IV, dropping at Inbound Endpoint... " + _config);
            return false;
        }

        // Inbound endpoints and outbound gateways undo the crypto in the same way
        if (!decrypt(_context, _config, orig, offset, length)) {
            return false;
        }

        if (_config.getLength() > 0) {
            ProfileManager pm = _context.profileManager();
            if (pm != null) {
                for (int i = 0; i < _config.getLength(); i++) {
                    pm.tunnelDataPushed(_config.getPeer(i), 0, length);
                }
            }
            _config.incrementVerifiedBytesTransferred(length);
        }

        return true;
    }

    /**
     * Iteratively undo the crypto that the various layers in the tunnel added.
     * @return true if decryption succeeded, false if tunnel keys were null
     */
    private boolean decrypt(RouterContext ctx, TunnelCreatorConfig cfg, byte orig[], int offset, int length) {
        // Don't include the endpoint, since that is the creator
        for (int i = cfg.getLength() - 2; i >= 0; i--) {
            if (!OutboundGatewayProcessor.decrypt(ctx, orig, offset, length, cfg.getConfig(i))) {
                if (_log.shouldWarn())
                    _log.warn("Tunnel crypto keys are null at hop " + i + " - tunnel has been destroyed");
                return false;
            }
        }
        return true;
    }
}

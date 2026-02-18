package net.i2p.router.tunnel;

import static net.i2p.router.tunnel.HopProcessor.IV_LENGTH;

import net.i2p.I2PAppContext;
import net.i2p.crypto.AESEngine;
import net.i2p.data.SessionKey;
import net.i2p.util.Log;

/**
 * Turn the preprocessed tunnel data into something that can be delivered to the
 * first hop in the tunnel. The crypto used in this class is also used by the
 * InboundEndpointProcessor, as it's the same 'undo' function of the tunnel crypto.
 */
class OutboundGatewayProcessor {
    private final I2PAppContext _context;
    private final Log _log;
    private final TunnelCreatorConfig _config;
    private volatile boolean _loggedKeyWarning;

    public OutboundGatewayProcessor(I2PAppContext ctx, TunnelCreatorConfig cfg) {
        _context = ctx;
        _log = ctx.logManager().getLog(OutboundGatewayProcessor.class);
        _config = cfg;
    }

    /**
     * Check if this processor's tunnel config has valid crypto keys.
     * @return true if all required hop keys are present
     */
    public boolean hasValidKeys() {
        if (_config == null) {
            return false;
        }
        for (int i = _config.getLength() - 1; i >= 1; i--) {
            HopConfig hop = _config.getConfig(i);
            if (hop == null || hop.getIVKey() == null || hop.getLayerKey() == null) {
                return false;
            }
        }
        return true;
    }

    /**
     * Since we are the outbound gateway, pick a random IV and wrap the preprocessed
     * data so that it will be exposed at the endpoint.
     *
     * @param orig original data with an extra 16 byte IV prepended
     * @param offset index into the array where the extra 16 bytes (IV) begins
     * @param length how much of orig can we write to (must be a multiple of 16).
     *               Should always be 1024 bytes.
     * @return true if processing succeeded, false if keys were null
     */
    public boolean process(byte orig[], int offset, int length) {
        return decrypt(_config, orig, offset, length);
    }

    /**
     * Iteratively undo the crypto that the various layers in the tunnel added.
     * This is used by the outbound gateway (preemptively undoing the crypto peers will add).
     *
     * @param orig original data with an extra 16 byte IV prepended
     * @param offset index into the array where the extra 16 bytes (IV) begins
     * @param length how much of orig can we write to (must be a multiple of 16).
     *               Should always be 1024 bytes.
     * @return true if processing succeeded, false if keys were null
     */
    private boolean decrypt(TunnelCreatorConfig cfg, byte orig[], int offset, int length) {
        // Don't include hop 0, since that is the creator
        for (int i = cfg.getLength() - 1; i >= 1; i--) {
            if (!decrypt(_context, orig, offset, length, cfg.getConfig(i))) {
                return false;
            }
        }
        return true;
    }

    /**
     * Undo the crypto for a single hop. This is used by both the outbound gateway
     * (preemptively undoing the crypto peers will add) and by the inbound endpoint.
     *
     * @param orig original data with an extra 16 byte IV prepended
     * @param offset index into the array where the extra 16 bytes (IV) begins
     * @param length how much of orig can we write to (must be a multiple of 16).
     *               Should always be 1024 bytes.
     * @return true if processing succeeded, false if keys were null
     */
    static boolean decrypt(I2PAppContext ctx, byte orig[], int offset, int length, HopConfig config) {
        SessionKey ivkey = config.getIVKey();
        SessionKey layerKey = config.getLayerKey();
        if (ivkey == null || layerKey == null) {
            Log log = ctx.logManager().getLog(OutboundGatewayProcessor.class);
            if (log.shouldWarn()) {
                log.warn("Tunnel crypto keys are null - tunnel has been destroyed, dropping message");
            }
            return false;
        }
        AESEngine aes = ctx.aes();
        // Update the IV for the previous hop
        aes.decryptBlock(orig, offset, ivkey, orig, offset);
        aes.decrypt(orig, offset + IV_LENGTH, orig, offset + IV_LENGTH, layerKey,
                          orig, offset, length - IV_LENGTH);
        // Double IV encryption
        aes.decryptBlock(orig, offset, ivkey, orig, offset);
        return true;
    }
}

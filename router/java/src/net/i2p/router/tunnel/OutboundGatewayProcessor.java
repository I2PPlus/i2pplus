package net.i2p.router.tunnel;

import static net.i2p.router.tunnel.HopProcessor.IV_LENGTH;

import net.i2p.I2PAppContext;
import net.i2p.crypto.AESEngine;
import net.i2p.data.SessionKey;

/**
 * Turn the preprocessed tunnel data into something that can be delivered to the
 * first hop in the tunnel.  The crypto used in this class is also used by the
 * InboundEndpointProcessor, as it's the same 'undo' function of the tunnel crypto.
 *
 */
class OutboundGatewayProcessor {
    private final I2PAppContext _context;
    private final TunnelCreatorConfig _config;

    public OutboundGatewayProcessor(I2PAppContext ctx, TunnelCreatorConfig cfg) {
        _context = ctx;
        _config = cfg;
    }

    /**
     * Since we are the outbound gateway, pick a random IV and wrap the preprocessed
     * data so that it will be exposed at the endpoint.
     *
     * @param orig original data with an extra 16 byte IV prepended.
     * @param offset index into the array where the extra 16 bytes (IV) begins
     * @param length how much of orig can we write to (must be a multiple of 16).
     *               Should always be 1024 bytes.
     */
    public void process(byte[] orig, int offset, int length) {
        decrypt(_config, orig, offset, length);
    }

    /**
     * Iteratively undo the crypto that the various layers in the tunnel added.  This is used
     * by the outbound gateway (preemptively undoing the crypto peers will add).
     *
     * @param orig original data with an extra 16 byte IV prepended.
     * @param offset index into the array where the extra 16 bytes (IV) begins
     * @param length how much of orig can we write to (must be a multiple of 16).
     *               Should always be 1024 bytes.
     */
    private void decrypt(TunnelCreatorConfig cfg, byte[] orig, int offset, int length) {
        // Don't include hop 0, since that is the creator
        for (int i = cfg.getLength() - 1; i >= 1; i--) {
            decrypt(_context, orig, offset, length, cfg.getConfig(i));
        }
    }

    /**
     * Undo the crypto for a single hop.  This is used
     * by both the outbound gateway (preemptively undoing the crypto peers will add)
     * and by the inbound endpoint.
     *
     * @param orig original data with an extra 16 byte IV prepended.
     * @param offset index into the array where the extra 16 bytes (IV) begins
     * @param length how much of orig can we write to (must be a multiple of 16).
     *               Should always be 1024 bytes.
     */
    static void decrypt(I2PAppContext ctx, byte[] orig, int offset, int length, HopConfig config) {
        SessionKey ivkey = config.getIVKey();
        AESEngine aes = ctx.aes();
        aes.decryptBlock(orig, offset, ivkey, orig, offset);
        aes.decrypt(orig, offset + IV_LENGTH, orig, offset + IV_LENGTH, config.getLayerKey(),
                          orig, offset, length - IV_LENGTH);
        aes.decryptBlock(orig, offset, ivkey, orig, offset);
    }
}
